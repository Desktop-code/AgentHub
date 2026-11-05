"""sidecar 配置读取。所属：评测 OpenJudge 本机 sidecar（进程外）。

**凭据优先级（钉死）**：
1. 请求体 `model` / `base_url` / `api_key`——AgentHub Java 从模型中心解密后，
   经 **本机环回**（127.0.0.1）透传；这是选了打分模型的正常路径。
2. 请求体缺哪项就回落对应环境变量（`OPENJUDGE_MODEL` / `OPENAI_MODEL`、`OPENAI_BASE_URL`、
   `OPENAI_API_KEY`）——仅用于未指定实例的历史/手工 curl。
3. 两者都没有 → 抛 503，**禁止**「200 + 空分」。

`judge_model_instance_id` 只回写 metadata 供对账；响应与日志**永不**回写 `api_key`。

线程/进程：只做 os.getenv 读取 + 请求级合并，无状态；uvicorn 单进程即可。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlparse

# 默认端口与 Java 侧 application.yml 的 openjudge.base-url 默认值对齐
DEFAULT_PORT = 18765

# 单次 LLM 调用上限（秒）。应小于 Java 侧 openjudge.timeout-ms（默认 60s），
# 让 sidecar 能先返回错误体（Java 转 ERROR），而不是被 Java 侧硬超时掐断。
DEFAULT_LLM_TIMEOUT_S = 45.0

# 打分阈值（1~5）。只用于 metadata 里的 passed 标记，**不参与 gate_passed**。
DEFAULT_THRESHOLD = 3.0

# 提示词语言：项目 UI 为中文，默认 zh
DEFAULT_LANGUAGE = "zh"


@dataclass(frozen=True)
class JudgeModelConf:
    """Judge 模型连接信息。请求体优先，环境变量兜底。"""

    model: str
    api_key: str
    base_url: str | None
    timeout_s: float
    # 本配置的来源：request / env / mixed；只进 metadata，用于排障，不含密钥
    source: str = "env"


def _env(name: str) -> str:
    """读环境变量并去空白；未设置返回空串（不用 None，便于调用方判断）。"""
    return (os.getenv(name) or "").strip()


def model_name() -> str:
    """Judge 模型名。优先 OPENJUDGE_MODEL，其次通用 OPENAI_MODEL。"""
    return _env("OPENJUDGE_MODEL") or _env("OPENAI_MODEL")


def api_key() -> str:
    """Judge 模型 API Key（OpenAI 兼容协议）。"""
    return _env("OPENAI_API_KEY")


def base_url() -> str | None:
    """OpenAI 兼容网关地址；为空表示走 SDK 默认（官方 OpenAI）。"""
    return _env("OPENAI_BASE_URL") or None


def llm_timeout_s() -> float:
    """单次 LLM 调用超时（秒）；非法值回落默认，避免把 sidecar 卡死。"""
    raw = _env("OPENJUDGE_LLM_TIMEOUT_S")
    if not raw:
        return DEFAULT_LLM_TIMEOUT_S
    try:
        value = float(raw)
    except ValueError:
        return DEFAULT_LLM_TIMEOUT_S
    return value if value > 0 else DEFAULT_LLM_TIMEOUT_S


def threshold() -> float:
    """打分阈值（1~5）。越界或非法一律回落 3。"""
    raw = _env("OPENJUDGE_THRESHOLD")
    if not raw:
        return DEFAULT_THRESHOLD
    try:
        value = float(raw)
    except ValueError:
        return DEFAULT_THRESHOLD
    return value if 1.0 <= value <= 5.0 else DEFAULT_THRESHOLD


def language() -> str:
    """提示词语言：zh / en；其它值回落 zh（本项目中文优先）。"""
    raw = _env("OPENJUDGE_LANGUAGE").lower()
    return raw if raw in ("zh", "en") else DEFAULT_LANGUAGE


def port() -> int:
    """启动脚本用端口；非法值回落为默认 18765（仅本机回环）。"""
    raw = _env("OPENJUDGE_PORT")
    if not raw:
        return DEFAULT_PORT
    try:
        value = int(raw)
    except ValueError:
        return DEFAULT_PORT
    return value if 0 < value < 65536 else DEFAULT_PORT


# response_format 策略：
# - auto（默认）：只有官方 OpenAI 才发 json_schema；其它兼容网关降级为 json_object
# - json_schema / json_object / none：显式指定，用于自建网关排障
STRUCTURED_OUTPUT_MODES = ("auto", "json_schema", "json_object", "none")


def structured_output_mode(base_url: str | None = None) -> str:
    """决定 Judge 模型的结构化输出方式。

    py-openjudge 默认对非 qwen/gemini 模型发 `response_format={"type":"json_schema"}`，
    而 DeepSeek / GLM 等 OpenAI 兼容网关会返回 400
    `This response_format type is unavailable now`，导致全部 grader 失败。

    Args:
        base_url: 实际生效的基址（请求体优先，环境变量兜底），可空

    Returns:
        `json_schema`（保持上游行为）/ `json_object`（降级）/ `none`（不发 response_format）。
    """
    raw = _env("OPENJUDGE_STRUCTURED_OUTPUT").lower()
    if raw in ("json_schema", "json_object", "none"):
        return raw
    # auto：官方 OpenAI 才保证支持 json_schema；其余（含 Azure、自建网关）统一用更通用的 json_object
    host = (urlparse(base_url or "").hostname or "").lower()
    return "json_schema" if host == "api.openai.com" else "json_object"


def judge_conf(request_model: str | None = None, request_api_key: str | None = None,
               request_base_url: str | None = None) -> JudgeModelConf | None:
    """组装 Judge 模型配置：**请求体优先，环境变量兜底**。

    Args:
        request_model: 请求体 `model`（Java 透传的实例 modelId），可空
        request_api_key: 请求体 `api_key`（环回透传），可空
        request_base_url: 请求体 `base_url`（OpenAI 兼容基址），可空

    Returns:
        配置；model 或 api_key 两者仍缺任一（请求 + env 合并后）时返回 None（= 未配置）。
        返回值里的 api_key 只供构造客户端，**禁止**进响应/日志。
    """
    resolved_model = (request_model or "").strip() or model_name()
    resolved_key = (request_api_key or "").strip() or api_key()
    # base_url 允许空：空 = 走 OpenAIChatModel 默认（官方 OpenAI）
    resolved_base = (request_base_url or "").strip() or base_url()
    if not resolved_model or not resolved_key:
        return None
    return JudgeModelConf(
        model=resolved_model,
        api_key=resolved_key,
        base_url=resolved_base,
        timeout_s=llm_timeout_s(),
        source=credential_source(request_model, request_api_key),
    )


def credential_source(request_model: str | None, request_api_key: str | None) -> str:
    """判断凭据来源，只用于 metadata 排障（不含密钥）。

    :return: ``request`` / ``env`` / ``mixed`` / ``none``
    """
    has_request_model = bool((request_model or "").strip())
    has_request_key = bool((request_api_key or "").strip())
    if has_request_model and has_request_key:
        return "request"
    if not has_request_model and not has_request_key:
        return "env" if (model_name() and api_key()) else "none"
    return "mixed"
