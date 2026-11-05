"""OpenJudge grader 装配与聚合。所属：评测 OpenJudge 本机 sidecar（进程外）。

职责：
1. 按 Java `OpenJudgeScorerAdapter#buildBody` 传来的请求体，选对应 py-openjudge grader；
2. 用 Judge 模型跑点式打分（1~5 分），归一化成 0~1 后取均分；
3. 组装成 Java `mapOk` 能识别的响应体：`score` / `reason` / `metadata`。

铁律：
- 本模块**不做**门禁判断：分数只回给 Java 侧存 `scores.openjudge`，绝不参与 `gate_passed`。
- 无成功 grader 一律抛错由上层转非 2xx（Java 转 ERROR），**禁止** 200 + 空分糊过去。
- 不重试：`max_retries=0`，与 Java 侧「无重试」写死一致；失败就交回 ERROR。
"""

from __future__ import annotations

import logging
from typing import Any, Iterable

from openai import BadRequestError
from openjudge.graders.agent.tool.tool_selection import ToolSelectionGrader
from openjudge.graders.base_grader import GraderError
from openjudge.graders.common.correctness import CorrectnessGrader
from openjudge.graders.common.relevance import RelevanceGrader
from openjudge.models.openai_chat_model import OpenAIChatModel
from openjudge.models.schema.prompt_template import LanguageEnum
from pydantic import BaseModel

import settings

log = logging.getLogger("openjudge.scoring")

# py-openjudge 的 Relevance / Correctness / ToolSelection 均为 1~5 分制
RAW_SCORE_MAX = 5.0

# Java 侧默认发出的 grader 类名 → 本 sidecar 内部规范名。
# 同时接受短名（用户手写 curl 时更省事）。
GRADER_ALIASES = {
    "relevancegrader": "relevance",
    "relevance": "relevance",
    "correctnessgrader": "correctness",
    "correctness": "correctness",
    "toolselectiongrader": "tool_selection",
    "tool_selection": "tool_selection",
    "toolselection": "tool_selection",
}

# Java 侧未指定 graders 时的兜底（与其 EvalRunnerAppService#resolveGraders 默认一致）
DEFAULT_GRADERS = ["RelevanceGrader"]


class _CompatCompletions:
    """`AsyncCompletions` 代理：按供应商能力降级 `response_format`。

    背景（踩坑点）：py-openjudge 0.2.4 的 `OpenAIChatModel#achat` 只对
    qwen / gemini / pai-judge 把结构化输出降级为 `json_object`；其余模型会把
    Pydantic 类直接交给 `client.chat.completions.parse()`，SDK 随即发出
    `response_format={"type": "json_schema", ...}`。DeepSeek、GLM 等 OpenAI 兼容
    网关不支持 `json_schema`，会返回 400：
    `This response_format type is unavailable now`。

    这里在客户端层兜住：非官方 OpenAI 基址一律改成 `json_object`；若网关连
    `json_object` 也不支持，再退一步去掉 `response_format`（字段仍由提示词要求
    JSON + 上游 `repair_and_load_json` 兜底解析）。
    """

    def __init__(self, real: Any, mode: str):
        """Args: real 真实 completions 对象；mode `json_object` / `none`。"""
        self._real = real
        self._mode = mode

    async def parse(self, **kwargs: Any) -> Any:
        """拦截 `parse()`：Pydantic 结构化模型不走 `json_schema`。"""
        response_format = kwargs.get("response_format")
        # none 模式对任何形态的 response_format 都不发（含上游 qwen 分支的 dict）
        if self._mode == "none" and response_format is not None:
            return await self._create_degraded(kwargs)
        # 上游只在「结构化模型是 Pydantic 类」时才生成 json_schema；dict 形式原样透传
        if isinstance(response_format, type) and issubclass(response_format, BaseModel):
            return await self._create_degraded(kwargs)
        return await self._real.parse(**kwargs)

    async def create(self, **kwargs: Any) -> Any:
        """普通 chat 调用原样透传（上游非流式 parse 之外的路径也会用到）。"""
        return await self._real.create(**kwargs)

    async def _create_degraded(self, kwargs: dict[str, Any]) -> Any:
        """用 `create()` 替代 `parse()`，并逐级降级 response_format。"""
        payload = dict(kwargs)
        if self._mode == "none":
            payload.pop("response_format", None)
            return await self._real.create(**payload)

        payload["response_format"] = {"type": "json_object"}
        try:
            return await self._real.create(**payload)
        except BadRequestError as ex:
            # 网关连 json_object 都不认时才退到无 response_format；其它 400 不掩盖
            if "response_format" not in str(ex):
                raise
            log.warning("网关不支持 json_object，降级为无 response_format：%s", ex)
            payload.pop("response_format", None)
            return await self._real.create(**payload)

    def __getattr__(self, name: str) -> Any:
        """其余属性（如 with_streaming_response）继续用真实对象。"""
        return getattr(self._real, name)


class _CompatChat:
    """`AsyncChat` 代理：只替换 `completions`，其余命名空间透传。"""

    def __init__(self, real: Any, mode: str):
        self._real = real
        self._mode = mode

    @property
    def completions(self) -> _CompatCompletions:
        return _CompatCompletions(self._real.completions, self._mode)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


class _CompatClient:
    """`AsyncOpenAI` 代理：只替换 `chat`，其余 API 透传。"""

    def __init__(self, real: Any, mode: str):
        self._real = real
        self._mode = mode

    @property
    def chat(self) -> _CompatChat:
        return _CompatChat(self._real.chat, self._mode)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._real, name)


def _install_response_format_compat(model: OpenAIChatModel, base_url: str | None) -> None:
    """按配置给模型客户端装上 response_format 兼容层。

    Args:
        model: 已构造好的 py-openjudge 模型
        base_url: 实际生效的 OpenAI 兼容基址（请求体优先，env 兜底）

    Returns:
        None；`json_schema` 模式保持上游行为，不做包装。
    """
    mode = settings.structured_output_mode(base_url)
    if mode == "json_schema":
        return
    # 单测会用替身工厂替换 OpenAIChatModel，替身没有 client；真实模型必然有
    client = getattr(model, "client", None)
    if client is None:
        return
    model.client = _CompatClient(client, mode)
    # 只记策略与模型名，不记 base_url / key；便于排障时确认兼容层是否生效
    log.info("Judge 结构化输出兼容层已启用：mode=%s model=%s", mode, getattr(model, "model", ""))


class JudgeError(Exception):
    """评分失败。可被 Java 侧识别为 ERROR（非 2xx），不是 SKIPPED。

    Attributes:
        status_code: 建议回给 Java 的 HTTP 码（400 参数问题 / 503 模型未配置 / 502 评分失败）
        detail: 结构化明细，直接进错误响应体
    """

    def __init__(self, message: str, status_code: int = 502, detail: dict[str, Any] | None = None):
        super().__init__(message)
        self.status_code = status_code
        self.detail = detail or {}


def build_model(*, model: str | None = None, api_key: str | None = None,
                base_url: str | None = None) -> OpenAIChatModel:
    """构造 Judge 模型客户端：请求体凭据优先，缺失项回落环境变量。

    Args:
        model: 请求体 `model`（Java 从模型中心解出的实例 modelId），可空
        api_key: 请求体 `api_key`（仅本机环回可见），可空
        base_url: 请求体 `base_url`（OpenAI 兼容基址），可空

    Returns:
        OpenAI 兼容协议的 chat 模型实例。

    Raises:
        JudgeError: 503 — 请求体与环境变量合并后仍缺 model 或 api_key。
    """
    conf = settings.judge_conf(model, api_key, base_url)
    if conf is None:
        raise JudgeError(
            "未配置 Judge 模型：请求体未带 model/api_key，环境变量 OPENAI_API_KEY 与 "
            "OPENJUDGE_MODEL（或 OPENAI_MODEL）也未配置",
            status_code=503,
            detail={
                "need_request": ["model", "api_key"],
                "need_env": ["OPENAI_API_KEY", "OPENJUDGE_MODEL"],
                "optional_env": ["OPENAI_BASE_URL", "OPENJUDGE_LANGUAGE", "OPENJUDGE_THRESHOLD"],
            },
        )
    # max_retries=0：与 Java 侧「评分失败即 ERROR、不重试」保持一致，避免重复计费
    model = OpenAIChatModel(
        model=conf.model,
        api_key=conf.api_key,
        base_url=conf.base_url,
        max_retries=0,
        timeout=conf.timeout_s,
    )
    # DeepSeek/GLM 等网关不支持 json_schema：在客户端层兜住，避免 400 拖垮整批评分
    _install_response_format_compat(model, conf.base_url)
    return model


def _resolve_names(requested: Iterable[str] | None) -> tuple[list[str], list[dict[str, str]]]:
    """把请求里的 grader 名解析成规范名。

    Args:
        requested: Java 传来的 grader 名列表（类名或短名）

    Returns:
        (支持的规范名列表, 不支持的明细列表)；顺序保留请求顺序、去重。
    """
    supported: list[str] = []
    unsupported: list[dict[str, str]] = []
    for raw in requested or []:
        name = (raw or "").strip()
        if not name:
            continue
        canonical = GRADER_ALIASES.get(name.lower())
        if canonical is None:
            unsupported.append({"grader": name, "reason": "本 sidecar 未实现该 grader"})
        elif canonical not in supported:
            supported.append(canonical)
    return supported, unsupported


def _new_grader(canonical: str, model: OpenAIChatModel, threshold: float, language: LanguageEnum):
    """按规范名构造 py-openjudge grader 实例。

    Args:
        canonical: relevance / correctness / tool_selection
        model: Judge 模型
        threshold: 1~5 阈值（仅进 metadata）
        language: 提示词语言

    Returns:
        BaseGrader 子类实例。

    Raises:
        ValueError: 未知规范名（调用前已由 _resolve_names 过滤，正常不可达）
    """
    if canonical == "relevance":
        return RelevanceGrader(model=model, threshold=threshold, language=language)
    if canonical == "correctness":
        return CorrectnessGrader(model=model, threshold=threshold, language=language)
    if canonical == "tool_selection":
        return ToolSelectionGrader(model=model, language=language)
    raise ValueError(f"未知 grader：{canonical}")


def _tool_definitions(expect_tools: list[str], called: list[str]) -> list[dict[str, str]]:
    """由工具名合成最小工具定义。

    Java 契约只传工具**名**（`expect_tools` / `tool_calls[].name`），没有 description 与
    参数 schema；这里把名字同时当作 name 与 description，属**已知降级**（README 已注明）。
    """
    names: list[str] = []
    for name in list(expect_tools) + list(called):
        clean = (name or "").strip()
        if clean and clean not in names:
            names.append(clean)
    return [{"name": name, "description": name} for name in names]


def _is_tool_selection_error(result: Any) -> bool:
    """ToolSelectionGrader 内部吞异常后返回 score=0 + `Evaluation error: ...`。

    这里把它识别成失败，避免把「调用炸了」当成真实 0 分拉低均分。
    """
    reason = getattr(result, "reason", "") or ""
    return float(getattr(result, "score", 0) or 0) == 0.0 and reason.startswith("Evaluation error")


async def _run_one(
    canonical: str,
    model: OpenAIChatModel,
    *,
    query: str,
    answer: str,
    reference: str,
    tool_calls: list[dict[str, Any]],
    tool_definitions: list[dict[str, str]],
    threshold: float,
    language: LanguageEnum,
) -> dict[str, Any]:
    """跑单个 grader，永不抛异常（失败转 error 字段），便于部分成功时仍能出分。"""
    label = {
        "relevance": "RelevanceGrader",
        "correctness": "CorrectnessGrader",
        "tool_selection": "ToolSelectionGrader",
    }[canonical]
    try:
        grader = _new_grader(canonical, model, threshold, language)
        if canonical == "tool_selection":
            result = await grader.aevaluate(
                query=query, tool_definitions=tool_definitions, tool_calls=tool_calls
            )
        else:
            result = await grader.aevaluate(
                query=query, response=answer, reference_response=reference
            )
    except Exception as ex:  # noqa: BLE001 — 单个 grader 失败不能拖垮整次评分
        log.warning("grader %s 调用异常：%s", label, ex)
        return {"grader": label, "error": f"{type(ex).__name__}: {ex}"}

    if isinstance(result, GraderError):
        return {"grader": label, "error": result.error or "grader 返回错误"}
    if canonical == "tool_selection" and _is_tool_selection_error(result):
        return {"grader": label, "error": result.reason}

    raw = float(getattr(result, "score", 0) or 0)
    return {
        "grader": label,
        "score": raw,
        "normalized": round(max(0.0, min(1.0, raw / RAW_SCORE_MAX)), 4),
        "threshold": threshold,
        "passed": raw >= threshold,
        "reason": getattr(result, "reason", "") or "",
        "metadata": getattr(result, "metadata", None) or {},
    }


async def evaluate(
    *,
    case_key: str,
    final_answer: str,
    reference: str | None,
    tool_calls: list[dict[str, Any]],
    expect_tools: list[str],
    graders: list[str] | None,
    judge_model_instance_id: int | None,
    query: str | None,
    model: OpenAIChatModel,
    judge_model_name: str | None = None,
    judge_credential_source: str = "env",
) -> dict[str, Any]:
    """执行一次 OpenJudge 评分并聚合。

    Args:
        case_key: 用例键（同时作为 query 兜底，便于日志对账）
        final_answer: 试跑终答
        reference: expect.reference，可空
        tool_calls: 工具调用摘要 [{name, success}]
        expect_tools: 期望工具名列表
        graders: 请求指定的 grader 名
        judge_model_instance_id: 本 run 选中的模型实例 id（仅回写 metadata）
        query: 用例输入问句，可空（Java 契约当前未传）
        model: Judge 模型
        judge_model_name: 实际使用的模型名（请求体优先），仅回写 metadata
        judge_credential_source: 凭据来源 request/env/mixed/none，仅回写 metadata

    Returns:
        {"score": 0~1, "reason": str, "metadata": {...}}；**永不**回写 api_key

    Raises:
        JudgeError: 400 参数不合法 / 无可跑 grader；502 全部 grader 失败
    """
    answer = (final_answer or "").strip()
    if not answer:
        raise JudgeError("final_answer 为空，跳过评分（请检查用例产物）", status_code=400)

    ref = (reference or "").strip()
    # 请求里有 query 用它；没有就用 case_key 兜底（相关性评分会明显变弱，README 已注明）
    query_source = "request"
    effective_query = (query or "").strip()
    if not effective_query:
        effective_query = (case_key or "").strip()
        query_source = "case_key"

    calls: list[dict[str, Any]] = [
        {"name": (one.get("name") or "").strip(), "success": bool(one.get("success"))}
        for one in (tool_calls or [])
        if (one.get("name") or "").strip()
    ]
    called_names = [one["name"] for one in calls]
    definitions = _tool_definitions(list(expect_tools or []), called_names)

    requested = [name for name in (graders or []) if (name or "").strip()] or DEFAULT_GRADERS
    supported, unsupported = _resolve_names(requested)

    lang_code = settings.language()
    language = LanguageEnum.ZH if lang_code == "zh" else LanguageEnum.EN
    threshold = settings.threshold()

    # 缺输入就降级：不静默给 0 分，挪进 unsupported 明细
    runnable: list[str] = []
    for canonical in supported:
        if canonical == "correctness" and not ref:
            unsupported.append({"grader": "CorrectnessGrader", "reason": "请求未带 reference"})
            continue
        if canonical == "tool_selection" and not definitions:
            unsupported.append({
                "grader": "ToolSelectionGrader",
                "reason": "请求未带 expect_tools / tool_calls，无法构造工具定义",
            })
            continue
        runnable.append(canonical)

    if not runnable:
        raise JudgeError(
            "没有可执行的 grader",
            status_code=400,
            detail={"requested": requested, "unsupported_graders": unsupported},
        )

    outcomes: list[dict[str, Any]] = []
    for canonical in runnable:
        outcomes.append(
            await _run_one(
                canonical,
                model,
                query=effective_query,
                answer=answer,
                reference=ref,
                tool_calls=calls,
                tool_definitions=definitions,
                threshold=threshold,
                language=language,
            )
        )

    ok = [one for one in outcomes if "score" in one]
    errors = [one for one in outcomes if "error" in one]
    if not ok:
        # 全挂：非 2xx，让 Java 落 ERROR（绝不 silent 200 空分）
        raise JudgeError(
            "全部 grader 评分失败",
            status_code=502,
            detail={"graders": outcomes, "unsupported_graders": unsupported},
        )

    mean_norm = sum(one["normalized"] for one in ok) / len(ok)
    reason = "；".join(
        f"{one['grader']}: {one['reason'][:300]}" for one in ok
    )
    if errors:
        reason += "；失败：" + "，".join(one["grader"] for one in errors)

    metadata: dict[str, Any] = {
        "graders": outcomes,
        "unsupported_graders": unsupported,
        # 只回写模型名与实例 id；api_key / base_url 一律不进响应
        "judge_model": (judge_model_name or "").strip() or settings.model_name() or None,
        "judge_model_instance_id": judge_model_instance_id,
        "judge_credential_source": judge_credential_source,
        "language": lang_code,
        "threshold": threshold,
        "query_source": query_source,
        "score_scale": "0-1（原始 1-5 分 ÷ 5 后取均分）",
        "note": "软评分仅记录，不参与 gate_passed",
    }
    return {
        "score": round(mean_norm, 4),
        "reason": reason or "OpenJudge 评分成功",
        "metadata": metadata,
    }
