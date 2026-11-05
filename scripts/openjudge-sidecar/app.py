"""OpenJudge 本机 sidecar HTTP 服务。所属：评测 OpenJudge 本机 sidecar（进程外）。

**契约以现网 Java 为准**（禁另起字段名）：
- `GET  /health`        → 任意 2xx 即健康（`OpenJudgeHttpClient#health`）
- `POST /v1/aevaluate`  → 评分（`OpenJudgeHttpClient#evaluate`）
- 请求体字段：`OpenJudgeScorerAdapter#buildBody`（含环回透传的 `model` / `base_url` / `api_key`）
- 响应体字段：`OpenJudgeScorerAdapter#mapOk`（score / reason / metadata）

只监听 127.0.0.1；不嵌进 JVM，不做门禁判断，不重试。
**凭据**：请求体优先、环境变量兜底；响应/日志永不回写 `api_key`（见 README「模型从哪来」）。
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import scoring
import settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
log = logging.getLogger("openjudge.app")

app = FastAPI(
    title="AgentHub OpenJudge sidecar",
    version="1.0.0",
    description="py-openjudge 的本机 HTTP 包装；仅供 AgentHub 评测软评分使用，不参与 gate_passed。",
)


class ToolCallRow(BaseModel):
    """工具调用摘要。字段名对齐 Java `OpenJudgeScorerAdapter` 的 `tool_calls[]`。"""

    name: str = ""
    success: bool | None = None


class EvaluateRequest(BaseModel):
    """`POST /v1/aevaluate` 请求体。

    字段与 Java 现网 `OpenJudgeScorerAdapter#buildBody` 一一对应：
    - 用例产物 / grader：`case_key` … `judge_model_instance_id`，以及可选 `query`；
    - **打分模型凭据（环回透传）**：`model` / `base_url` / `api_key`。
      Java 选了打分模型时三项随请求带来；缺项时本服务回落自身环境变量。
      这三项**不得**出现在响应体、日志或 metadata 里（`api_key` 尤其）。
    """

    case_key: str = ""
    final_answer: str = ""
    reference: str | None = None
    tool_calls: list[ToolCallRow] = Field(default_factory=list)
    expect_tools: list[str] = Field(default_factory=list)
    graders: list[str] = Field(default_factory=list)
    judge_model_instance_id: int | None = None
    # 可选：用例输入问句。未传时用 case_key 兜底（相关性评分会变弱）
    query: str | None = None
    # 打分模型凭据（仅本机环回）：Java 从模型中心解密后透传
    model: str | None = None
    base_url: str | None = None
    api_key: str | None = None


def get_chat_model(body: EvaluateRequest) -> Any:
    """FastAPI 依赖：按请求体凭据构造 Judge 模型（缺失项回落环境变量）。

    FastAPI 允许依赖声明 body 参数，与路由复用同一份已解析请求体；
    因此「请求体带 Key」时不必依赖任何环境变量。
    单测通过 `app.dependency_overrides[get_chat_model]` 注入假模型，
    保证**永不打真实模型**。
    """
    return scoring.build_model(model=body.model, api_key=body.api_key, base_url=body.base_url)


@app.exception_handler(scoring.JudgeError)
async def handle_judge_error(request: Request, exc: scoring.JudgeError) -> JSONResponse:
    """把评分失败转成结构化非 2xx。

    Java 侧非 2xx 一律 `openjudge_status=ERROR`（带原文），不会是 SKIPPED，
    也不会出现「200 + 空分」被静默当成通过的情况。
    """
    log.warning("评分失败 %s %s：%s", request.method, request.url.path, exc)
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": str(exc), "detail": exc.detail},
    )


@app.get("/health")
async def health() -> dict[str, Any]:
    """健康检查：**恒 200**。

    Java 侧探活只看 2xx，失败会把该 case 记 SKIPPED；
    因此这里不做模型可用性判断（未配模型也在 body 里说明，不返 5xx）。

    注意：`judge_model_configured` 只看**环境变量**；Java 环回透传凭据时即使这里是
    false，评分仍可正常（凭据来自请求体，进程不该也无法预知）。
    """
    conf = settings.judge_conf()
    return {
        "status": "ok",
        "service": "openjudge-sidecar",
        "version": app.version,
        # 便于排障：false 时评分会 503，但进程本身是活的
        "judge_model_configured": conf is not None,
        "judge_model": settings.model_name() or None,
        "language": settings.language(),
    }


@app.post("/v1/aevaluate")
async def aevaluate(body: EvaluateRequest, model: Any = Depends(get_chat_model)) -> dict[str, Any]:
    """执行软评分。

    Returns:
        {"score": 0~1, "reason": str, "metadata": {...}} —— Java `mapOk` 直接消费。
        metadata 只回写 `judge_model` / `judge_model_instance_id` / `judge_credential_source`，
        **永不**回写 `api_key` 或完整 `base_url`。
    """
    # 只取来源与模型名做对账；conf 里的 api_key 不参与响应拼装
    conf = settings.judge_conf(body.model, body.api_key, body.base_url)
    result = await scoring.evaluate(
        case_key=body.case_key,
        final_answer=body.final_answer,
        reference=body.reference,
        tool_calls=[one.model_dump() for one in body.tool_calls],
        expect_tools=body.expect_tools,
        graders=body.graders,
        judge_model_instance_id=body.judge_model_instance_id,
        query=body.query,
        model=model,
        judge_model_name=(conf.model if conf else body.model),
        judge_credential_source=settings.credential_source(body.model, body.api_key),
    )
    log.info(
        "评分完成 case=%s score=%s graders=%s",
        body.case_key,
        result.get("score"),
        [one.get("grader") for one in (result.get("metadata", {}) or {}).get("graders", [])],
    )
    return result


@app.get("/")
async def index() -> dict[str, str]:
    """根路径：给人看的自检入口（AgentHub 不调用）。"""
    return {
        "service": "openjudge-sidecar",
        "health": "/health",
        "evaluate": "POST /v1/aevaluate",
    }
