"""response_format 兼容层窄测：**禁止**真实出站。

覆盖 py-openjudge 0.2.4 在非 qwen/gemini 模型上发 `json_schema` 导致
DeepSeek/GLM 返回 400 `This response_format type is unavailable now` 的兜底：
- 默认 auto：非官方 OpenAI 基址降级为 `json_object`，官方 OpenAI 保留 `json_schema`；
- `json_object` 仍被网关拒绝时，去掉 `response_format` 再试一次；
- 显式 `none` 直接不发 `response_format`。
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Any

import httpx2
import pytest
from openai import BadRequestError
from openai.types.chat import ChatCompletion, ChatCompletionMessage
from openai.types.chat.chat_completion import Choice
from pydantic import BaseModel

# 让测试能从 sidecar 目录直接 import scoring / settings
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scoring  # noqa: E402
import settings  # noqa: E402


class ScoreSchema(BaseModel):
    """模拟 py-openjudge 的 GraderScoreCallback。"""

    score: float
    reason: str


def _bad_request(message: str = "Error code: 400 - This response_format type is unavailable now") -> BadRequestError:
    """构造与真实网关同形状的 400，避免单测依赖网络。"""
    response = httpx2.Response(400, request=httpx2.Request("POST", "https://api.deepseek.com/chat/completions"))
    return BadRequestError(message, response=response, body=None)


class FakeCompletions:
    """记录 parse/create 的入参，可按队列设置异常。"""

    def __init__(self, errors: list[Exception] | None = None):
        self.parse_calls: list[dict[str, Any]] = []
        self.create_calls: list[dict[str, Any]] = []
        self.errors = list(errors or [])

    async def parse(self, **kwargs: Any) -> Any:
        self.parse_calls.append(kwargs)
        return {"via": "parse"}

    async def create(self, **kwargs: Any) -> Any:
        self.create_calls.append(kwargs)
        if self.errors:
            raise self.errors.pop(0)
        return {"via": "create"}


class FakeClient:
    """最小 AsyncOpenAI 替身，只为验证包装器的委托关系。"""

    def __init__(self, completions: FakeCompletions):
        self.chat = type("Chat", (), {"completions": completions})()


def test_non_openai_base_url_degrades_json_schema_to_json_object(monkeypatch):
    """DeepSeek 等网关：Pydantic 结构化模型必须走 create + json_object。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions()
    wrapped = scoring._CompatClient(FakeClient(completions), "json_object").chat.completions

    result = asyncio.run(wrapped.parse(model="deepseek-flash", response_format=ScoreSchema))

    assert result == {"via": "create"}
    assert completions.parse_calls == []
    assert completions.create_calls[0]["response_format"] == {"type": "json_object"}


def test_json_object_rejected_falls_back_without_response_format(monkeypatch):
    """网关连 json_object 都不支持时，去掉 response_format 再试，不能直接失败。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions(errors=[_bad_request()])
    wrapped = scoring._CompatClient(FakeClient(completions), "json_object").chat.completions

    result = asyncio.run(wrapped.parse(model="deepseek-flash", response_format=ScoreSchema))

    assert result == {"via": "create"}
    assert completions.create_calls[0]["response_format"] == {"type": "json_object"}
    assert "response_format" not in completions.create_calls[1]


def test_unrelated_bad_request_is_not_swallowed(monkeypatch):
    """与 response_format 无关的 400 必须原样抛出，不能假装降级成功。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions(errors=[_bad_request("Error code: 400 - invalid api key")])
    wrapped = scoring._CompatClient(FakeClient(completions), "json_object").chat.completions

    with pytest.raises(BadRequestError):
        asyncio.run(wrapped.parse(model="deepseek-flash", response_format=ScoreSchema))

    assert len(completions.create_calls) == 1


def test_none_mode_never_sends_response_format(monkeypatch):
    """显式 none：直接不带 response_format（字段靠提示词 + repair_and_load_json 兜底）。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions()
    wrapped = scoring._CompatClient(FakeClient(completions), "none").chat.completions

    asyncio.run(wrapped.parse(model="deepseek-flash", response_format=ScoreSchema))

    assert "response_format" not in completions.create_calls[0]


def test_none_mode_also_drops_dict_response_format(monkeypatch):
    """none 对上游 qwen 分支的 dict 同样生效。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions()
    wrapped = scoring._CompatClient(FakeClient(completions), "none").chat.completions

    asyncio.run(
        wrapped.parse(model="qwen-plus", response_format={"type": "json_object"})
    )

    assert "response_format" not in completions.create_calls[0]


def test_dict_response_format_is_passed_through(monkeypatch):
    """上游 qwen 分支已经传 dict：不能再二次降级，原样走 parse。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions()
    wrapped = scoring._CompatClient(FakeClient(completions), "json_object").chat.completions

    result = asyncio.run(
        wrapped.parse(model="qwen-plus", response_format={"type": "json_object"})
    )

    assert result == {"via": "parse"}
    assert completions.create_calls == []


def test_auto_mode_keeps_json_schema_for_official_openai(monkeypatch):
    """官方 OpenAI 支持 json_schema：保留上游行为，不无谓降级。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)

    assert settings.structured_output_mode("https://api.openai.com/v1") == "json_schema"
    assert settings.structured_output_mode("https://api.deepseek.com") == "json_object"
    assert settings.structured_output_mode("") == "json_object"


def test_install_compat_skips_everything_for_official_openai(monkeypatch):
    """json_schema 模式不包装 client；真实模型仍走上游 parse。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions()

    class Model:
        client = FakeClient(completions)

    model = Model()
    scoring._install_response_format_compat(model, "https://api.openai.com/v1")

    assert isinstance(model.client, FakeClient)


def test_install_compat_wraps_third_party_gateway(monkeypatch):
    """非官方基址必须装上兼容层；替身没有 client 时安全跳过（不炸单测）。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)
    completions = FakeCompletions()

    class Model:
        client = FakeClient(completions)

    model = Model()
    scoring._install_response_format_compat(model, "https://api.deepseek.com")

    assert isinstance(model.client, scoring._CompatClient)

    class BareModel:
        pass

    scoring._install_response_format_compat(BareModel(), "https://api.deepseek.com")


def test_upstream_achat_uses_degraded_response_format(monkeypatch):
    """走真实 py-openjudge `achat`：structured_model 类应落成 json_object 并解析出分。"""
    monkeypatch.delenv("OPENJUDGE_STRUCTURED_OUTPUT", raising=False)

    class CreatingCompletions:
        def __init__(self):
            self.calls: list[dict[str, Any]] = []

        async def create(self, **kwargs: Any) -> Any:
            self.calls.append(kwargs)
            return ChatCompletion(
                id="chatcmpl-test",
                created=0,
                model="deepseek-flash",
                object="chat.completion",
                choices=[
                    Choice(
                        index=0,
                        finish_reason="stop",
                        message=ChatCompletionMessage(
                            role="assistant",
                            content='{"score": 4, "reason": "mock 判定为相关"}',
                        ),
                    )
                ],
            )

        async def parse(self, **kwargs: Any) -> Any:  # pragma: no cover - 不应被调用
            raise AssertionError("json_schema parse 不应被调用")

    completions = CreatingCompletions()
    model = scoring.OpenAIChatModel(
        model="deepseek-flash",
        api_key="sk-test-no-network",
        base_url="https://api.deepseek.com",
    )
    # 直接换成零出站的假客户端，保留兼容层包装路径
    model.client = scoring._CompatClient(FakeClient(completions), "json_object")

    response = asyncio.run(
        model.achat(
            messages=[
                {"role": "system", "content": "只输出 JSON"},
                {"role": "user", "content": "给这段回答打分"},
            ],
            structured_model=ScoreSchema,
        )
    )

    assert completions.calls[0]["response_format"] == {"type": "json_object"}
    assert response.parsed["score"] == 4
