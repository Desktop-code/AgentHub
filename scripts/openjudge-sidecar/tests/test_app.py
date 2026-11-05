"""sidecar 窄测：只验契约与聚合，**禁止**真实出站打模型。

做法：用 `app.dependency_overrides` 把 Judge 模型换成 `FakeModel`，
它只实现 py-openjudge `LLMGrader` 真正用到的那一个方法 `achat`，
返回 `SimpleNamespace(parsed={"score":..., "reason":...})`，全程零网络。
"""

from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

# 让测试能从 sidecar 目录直接 import app / scoring / settings
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app as app_module  # noqa: E402


class FakeModel:
    """假 Judge 模型：记录被喂进模板的消息，按预设返回分数或抛错。"""

    def __init__(self, score: int = 4, reason: str = "mock 判定为相关", fail: bool = False):
        self.score = score
        self.reason = reason
        self.fail = fail
        self.calls: list[list] = []

    async def achat(self, messages, structured_model=None, callback=None, **kwargs):
        self.calls.append(messages)
        if self.fail:
            raise RuntimeError("mock LLM 调用失败")
        return SimpleNamespace(parsed={"score": self.score, "reason": self.reason})


@pytest.fixture()
def client():
    """注入假模型，结束后清理 override，避免污染其它用例。"""
    model = FakeModel()
    app_module.app.dependency_overrides[app_module.get_chat_model] = lambda: model
    try:
        yield TestClient(app_module.app), model
    finally:
        app_module.app.dependency_overrides.clear()


def _body(**overrides):
    """Java `OpenJudgeScorerAdapter#buildBody` 的最小等价请求体。"""
    base = {
        "case_key": "rag-smoke-1",
        "final_answer": "巴黎是法国首都。",
        "tool_calls": [{"name": "search", "success": True}],
        "expect_tools": [],
        "graders": ["RelevanceGrader"],
    }
    base.update(overrides)
    return base


def test_health_is_always_200_without_model(monkeypatch):
    """未配模型也必须恒 200：否则 Java 探活失败会把 case 记成 SKIPPED。"""
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("OPENJUDGE_MODEL", raising=False)
    monkeypatch.delenv("OPENAI_MODEL", raising=False)
    app_module.app.dependency_overrides.clear()

    resp = TestClient(app_module.app).get("/health")

    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
    assert resp.json()["judge_model_configured"] is False


def test_relevance_is_normalized_and_echoes_judge_model(client):
    """1~5 分制 → 0~1；judge_model_instance_id 只做对账回写。"""
    http, model = client

    resp = http.post(
        "/v1/aevaluate",
        json=_body(query="法国的首都是哪里？", judge_model_instance_id=12),
    )

    assert resp.status_code == 200
    payload = resp.json()
    assert payload["score"] == 0.8
    assert payload["metadata"]["judge_model_instance_id"] == 12
    assert payload["metadata"]["query_source"] == "request"
    assert [one["grader"] for one in payload["metadata"]["graders"]] == ["RelevanceGrader"]
    # 问句确实进了提示词（说明 query 契约被消费，不是摆设）
    assert "法国的首都是哪里" in str(model.calls[0])


def test_query_falls_back_to_case_key(client):
    """Java 现网不传 query：必须能跑，但要如实标记来源。"""
    http, _ = client

    resp = http.post("/v1/aevaluate", json=_body())

    assert resp.status_code == 200
    assert resp.json()["metadata"]["query_source"] == "case_key"


def test_correctness_without_reference_is_reported_not_scored(client):
    """缺 reference 时不伪造 0 分，挪进 unsupported 明细。"""
    http, _ = client

    resp = http.post(
        "/v1/aevaluate",
        json=_body(graders=["RelevanceGrader", "CorrectnessGrader"]),
    )

    assert resp.status_code == 200
    payload = resp.json()
    assert [one["grader"] for one in payload["metadata"]["graders"]] == ["RelevanceGrader"]
    assert payload["metadata"]["unsupported_graders"][0]["grader"] == "CorrectnessGrader"


def test_correctness_with_reference_averages_graders(client):
    """有 reference → 两个 grader 都跑，均分取归一化平均。"""
    http, _ = client

    resp = http.post(
        "/v1/aevaluate",
        json=_body(graders=["RelevanceGrader", "CorrectnessGrader"], reference="巴黎"),
    )

    assert resp.status_code == 200
    payload = resp.json()
    assert [one["grader"] for one in payload["metadata"]["graders"]] == [
        "RelevanceGrader",
        "CorrectnessGrader",
    ]
    assert payload["score"] == 0.8


def test_tool_selection_accepts_expect_tools(client):
    """有 expect_tools → ToolSelectionGrader 可跑（工具定义由名字降级合成）。"""
    http, _ = client

    resp = http.post(
        "/v1/aevaluate",
        json=_body(graders=["ToolSelectionGrader"], expect_tools=["search", "read_file"]),
    )

    assert resp.status_code == 200
    assert resp.json()["metadata"]["graders"][0]["grader"] == "ToolSelectionGrader"


def test_unknown_grader_only_is_400(client):
    """全部 grader 都不认识 → 非 2xx，Java 落 ERROR，不静默出分。"""
    http, _ = client

    resp = http.post("/v1/aevaluate", json=_body(graders=["HallucinationGrader"]))

    assert resp.status_code == 400
    assert "没有可执行的 grader" in resp.json()["error"]


def test_unknown_grader_alongside_known_is_200(client):
    """部分不认识：能跑的照跑，明细列出未支持的。"""
    http, _ = client

    resp = http.post(
        "/v1/aevaluate",
        json=_body(graders=["RelevanceGrader", "HallucinationGrader"]),
    )

    assert resp.status_code == 200
    assert resp.json()["metadata"]["unsupported_graders"][0]["grader"] == "HallucinationGrader"


def test_blank_answer_is_400(client):
    """空终答不打分，也不返回 200 空分。"""
    http, _ = client

    resp = http.post("/v1/aevaluate", json=_body(final_answer="   "))

    assert resp.status_code == 400


def test_all_graders_fail_is_502_not_silent_200(monkeypatch):
    """模型全炸 → 502 且带原因；绝不 200 + 空分。"""
    app_module.app.dependency_overrides[app_module.get_chat_model] = lambda: FakeModel(fail=True)
    try:
        resp = TestClient(app_module.app).post("/v1/aevaluate", json=_body())
    finally:
        app_module.app.dependency_overrides.clear()

    assert resp.status_code == 502
    assert "全部 grader 评分失败" in resp.json()["error"]
    assert resp.json()["detail"]["graders"]


def test_unconfigured_model_is_503(monkeypatch):
    """未配 KEY 时评分类请求直接 503（不是静默 0 分）。"""
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    monkeypatch.delenv("OPENJUDGE_MODEL", raising=False)
    monkeypatch.delenv("OPENAI_MODEL", raising=False)
    app_module.app.dependency_overrides.clear()

    resp = TestClient(app_module.app).post("/v1/aevaluate", json=_body())

    assert resp.status_code == 503
    assert "未配置 Judge 模型" in resp.json()["error"]


# ---------------------------------------------------------------------------
# 环回透传凭据（本刀）：请求体优先，环境变量兜底；响应永不回写 api_key
# ---------------------------------------------------------------------------

def _clear_judge_env(monkeypatch):
    """清空全部 Judge 相关环境变量，模拟「sidecar 不配 env」。"""
    for name in ("OPENAI_API_KEY", "OPENJUDGE_MODEL", "OPENAI_MODEL", "OPENAI_BASE_URL"):
        monkeypatch.delenv(name, raising=False)
    app_module.app.dependency_overrides.clear()


def test_request_body_credentials_work_without_env(monkeypatch):
    """不配任何 env，仅靠请求体 model/base_url/api_key 也能打分（构造器被替身，零出站）。"""
    _clear_judge_env(monkeypatch)
    captured: dict = {}
    fake = FakeModel()

    def fake_ctor(**kwargs):
        captured.update(kwargs)
        return fake

    monkeypatch.setattr(app_module.scoring, "OpenAIChatModel", fake_ctor)
    api_key = "sk-request-only-key-0001"

    resp = TestClient(app_module.app).post(
        "/v1/aevaluate",
        json=_body(judge_model_instance_id=88, model="qwen-plus",
                   base_url="https://api.example.com/v1", api_key=api_key),
    )

    assert resp.status_code == 200
    # 客户端确实用请求体凭据构造（而不是 env）
    assert captured["model"] == "qwen-plus"
    assert captured["api_key"] == api_key
    assert captured["base_url"] == "https://api.example.com/v1"
    payload = resp.json()
    assert payload["metadata"]["judge_model"] == "qwen-plus"
    assert payload["metadata"]["judge_credential_source"] == "request"
    # 响应绝不回写 Key
    assert api_key not in resp.text


def test_request_credentials_win_over_env(monkeypatch):
    """请求体带了 Key 就用它；env 只做兜底，不得冒充所选实例。"""
    _clear_judge_env(monkeypatch)
    monkeypatch.setenv("OPENAI_API_KEY", "sk-env-should-lose")
    monkeypatch.setenv("OPENJUDGE_MODEL", "env-model-should-lose")
    monkeypatch.setenv("OPENAI_BASE_URL", "https://env.example.com/v1")
    captured: dict = {}

    def fake_ctor(**kwargs):
        captured.update(kwargs)
        return FakeModel()

    monkeypatch.setattr(app_module.scoring, "OpenAIChatModel", fake_ctor)
    api_key = "sk-request-wins-0002"

    resp = TestClient(app_module.app).post(
        "/v1/aevaluate",
        json=_body(model="judge-from-request", base_url="https://req.example.com/v1",
                   api_key=api_key),
    )

    assert resp.status_code == 200
    assert captured["model"] == "judge-from-request"
    assert captured["api_key"] == api_key
    assert captured["base_url"] == "https://req.example.com/v1"
    assert resp.json()["metadata"]["judge_credential_source"] == "request"


def test_missing_api_key_falls_back_to_env(monkeypatch):
    """请求只带 model、缺 api_key：按契约回落 env Key，并如实标记 mixed。"""
    _clear_judge_env(monkeypatch)
    monkeypatch.setenv("OPENAI_API_KEY", "sk-env-fallback-0003")
    captured: dict = {}

    def fake_ctor(**kwargs):
        captured.update(kwargs)
        return FakeModel()

    monkeypatch.setattr(app_module.scoring, "OpenAIChatModel", fake_ctor)

    resp = TestClient(app_module.app).post(
        "/v1/aevaluate", json=_body(model="judge-from-request")
    )

    assert resp.status_code == 200
    assert captured["api_key"] == "sk-env-fallback-0003"
    assert resp.json()["metadata"]["judge_credential_source"] == "mixed"


def test_request_model_without_any_key_is_503(monkeypatch):
    """请求带 model、env 也没 Key：503，不静默出分。"""
    _clear_judge_env(monkeypatch)

    resp = TestClient(app_module.app).post(
        "/v1/aevaluate", json=_body(model="judge-without-key")
    )

    assert resp.status_code == 503
    assert "api_key" in resp.json()["detail"]["need_request"]
