# AgentHub 评测 · OpenJudge 本机 sidecar

把上游库 [`py-openjudge`](https://github.com/agentscope-ai/OpenJudge)（**库，不是监听进程**）
包成 AgentHub 评测域要的**本机 HTTP 服务**，供 `OpenJudgeScorerAdapter` 做软评分。

- 只监听 `127.0.0.1:18765`（与 `application.yml` 的 `openjudge.base-url` 默认值一致）
- **不改** `gate_passed` / `verdict`：分数只进 `scores.openjudge`，门禁永远只看规则
- 评分失败**不会**静默返回 200 空分：一律非 2xx，Java 侧落 `openjudge_status=ERROR`

---

## 1. 契约以现网 Java 为准（钉死）

sidecar 的字段名**不是自己定的**，逐字对齐这两个类，改 sidecar 前先读它们：

| 方向 | Java 出处 | 说明 |
|------|-----------|------|
| `GET /health` | `OpenJudgeHttpClient#health` | 只看是否 2xx |
| `POST /v1/aevaluate` | `OpenJudgeHttpClient#evaluate` | 2xx 且可解析才 OK |
| 请求体 | `OpenJudgeScorerAdapter#buildBody` | 见下表 |
| 响应体 | `OpenJudgeScorerAdapter#mapOk` | `score` / `reason` / `metadata` |
| 失败语义 | `OpenJudgeScorerAdapter#score` | 探活失败→`SKIPPED`；非 2xx/超时→`ERROR` |

### 请求体字段对照（`POST /v1/aevaluate`）

| JSON 字段 | 类型 | Java 是否必发 | sidecar 用途 |
|-----------|------|---------------|--------------|
| `case_key` | string | 是（可为空串） | 日志对账；`query` 缺失时兜底当问句 |
| `final_answer` | string | 是（可为空串） | 被评估的模型回答；空则 400 |
| `reference` | string | 否（有 reference 才发） | 送 `reference_response`；驱动 `CorrectnessGrader` |
| `tool_calls` | array | 是（可为空数组） | `[{name, success}]`，送 `ToolSelectionGrader` |
| `expect_tools` | array | 是（可为空数组） | 期望工具名；驱动 `ToolSelectionGrader` |
| `graders` | array | 是（至少 1 个） | grader 类名，见下方映射 |
| `judge_model_instance_id` | number | 否（选了打分模型才发） | **仅回写 metadata 对账**，不反查凭据 |
| `query` | string | **否（现网 Java 未发）** | 可选前向兼容字段；缺失时用 `case_key` 兜底 |
| `model` | string | 否（选了打分模型才发） | Judge 模型名（模型中心的 `modelId`）；优先于 `OPENJUDGE_MODEL` |
| `base_url` | string | 否（供应商配了才发） | OpenAI 兼容基址；优先于 `OPENAI_BASE_URL` |
| `api_key` | string | 否（选了打分模型才发） | 明文 Key，**仅本机环回**；优先于 `OPENAI_API_KEY`，响应/日志永不回写 |

> **`query` 说明（已知缺口）**：现网 `buildBody` 没有传用例输入问句，而 `RelevanceGrader`
> 需要 `query` 才能判"相关性"。sidecar 已支持可选 `query` 字段：Java 侧以后补上就自动生效，
> 在补上之前相关性评分偏弱（问句退化为 `case_key`），响应里 `metadata.query_source`
> 会如实标记 `request` / `case_key`，不会假装是真实问句。

### grader 名映射

| Java 发出的名字 | sidecar 实现 | 前置输入 | 备注 |
|-----------------|--------------|----------|------|
| `RelevanceGrader` / `relevance` | `openjudge.graders.common.relevance.RelevanceGrader` | `final_answer` | 无 `query` 时用 `case_key` 兜底 |
| `CorrectnessGrader` / `correctness` | `openjudge.graders.common.correctness.CorrectnessGrader` | 额外需要 `reference` | 没 reference 时**跳过并明示**，不伪造 0 分 |
| `ToolSelectionGrader` / `tool_selection` | `openjudge.graders.agent.tool.tool_selection.ToolSelectionGrader` | 额外需要 `expect_tools` 或 `tool_calls` | **降级**：工具定义只有名字（无参数 schema） |
| 其它（如 `HallucinationGrader`） | **未实现** | — | 进 `metadata.unsupported_graders`，不影响其它 grader |

### 请求样例（照抄 Java 的真实产物）

```json
{
  "case_key": "rag-smoke-1",
  "final_answer": "巴黎是法国的首都。",
  "reference": "法国的首都是巴黎",
  "tool_calls": [{ "name": "search", "success": true }],
  "expect_tools": ["search"],
  "graders": ["RelevanceGrader", "CorrectnessGrader", "ToolSelectionGrader"],
  "judge_model_instance_id": 12,
  "query": "法国的首都是哪里？",
  "model": "qwen-plus",
  "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "api_key": "sk-from-agenthub-model-center"
}
```

> 后三个字段由 AgentHub Java 从模型中心解密后、经 **本机环回** 透传；
> 手工 curl 时可省略，缺失项自动回落环境变量。
> **`api_key` 只允许出现在请求体里**：不写日志、不入库、不进报告。

### 响应样例（`mapOk` 直接消费）

```json
{
  "score": 0.8,
  "reason": "RelevanceGrader: 回答直接命中问题；CorrectnessGrader: 与参考一致",
  "metadata": {
    "graders": [
      { "grader": "RelevanceGrader", "score": 4.0, "normalized": 0.8, "threshold": 3.0, "passed": true, "reason": "...", "metadata": {} }
    ],
    "unsupported_graders": [],
    "judge_model": "qwen-plus",
    "judge_model_instance_id": 12,
    "judge_credential_source": "request",
    "language": "zh",
    "threshold": 3.0,
    "query_source": "request",
    "score_scale": "0-1（原始 1-5 分 ÷ 5 后取均分）",
    "note": "软评分仅记录，不参与 gate_passed"
  }
}
```

`score` 是 **0~1 归一化均分**（各 grader 原始 1~5 分 ÷ 5 再平均）；`passed` 只是阈值标记，
**不**回传为门禁结论。

---

## 2. 安装与启动

要求 **Python ≥ 3.10**（本项目在 3.12 上验证；启动脚本会自动挑 `3.12 → 3.11 → 3.10`，
还会额外问一次 `uv python find 3.12`（uv 装的解释器不在 `py` 启动器里），挑不到才报错。
想指定解释器就设 `PYTHON` 环境变量）。

### Windows（PowerShell）——**唯一正确入口**

> **不要双击 `start.sh`。** Windows 上 `.sh` 默认关联到 Git Bash，双击等于在 Git Bash 里跑一遍：
> 进程一退出窗口立刻关闭，报错根本来不及看（这就是"闪退"）。`start.sh` 只给 macOS / Linux / Git Bash 用。
> Windows 请开 **PowerShell** 跑 `.\start.ps1`；脚本失败会打印红色原因、写 `start.log` 并 `按回车键关闭窗口`，
> 不会再秒关。

```powershell
cd D:\workspace\AI\AgentHub\scripts\openjudge-sidecar

# Judge 模型：任意 OpenAI 兼容服务
$env:OPENAI_API_KEY = "sk-..."
$env:OPENAI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"   # 按你的供应商改
$env:OPENJUDGE_MODEL = "qwen-plus"

.\start.ps1
```

执行策略拦了一次性放行：`powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`

### macOS / Linux / Git Bash

```bash
cd scripts/openjudge-sidecar
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export OPENJUDGE_MODEL=qwen-plus
./start.sh
```

脚本会：找 Python ≥ 3.10 → 建/复用 `.venv`（解释器丢失或 < 3.10 时自动改名重建）→
**依赖已能 import 就跳过安装**，否则先补 pip（`ensurepip`）再 `pip install -r requirements.txt` →
校验端口未被占用 → 起 `uvicorn app:app --host 127.0.0.1 --port 18765`。
**端口占用只报错退出，不擅自换端口。**

| 变量 | 作用 |
|------|------|
| `OPENJUDGE_FORCE_INSTALL=1` | 忽略"依赖已就绪"，强制重装依赖 |
| `OPENJUDGE_NO_PAUSE=1` | 失败时不等待回车（给 CI / 脚本调用；手点不要设） |
| `PYTHON` | 指定解释器（优先级最高） |

两个脚本都会把里程碑与错误写进本目录 `start.log`（`*.log` 已在 `.gitignore`）。
**py-openjudge 首次 import 较重，起进程到能探活大约 20~40 秒，别急着判死。**

> 从 2026-11 起，经 AgentHub 跑批调用时可**不配**上面三个 Judge 变量：
> Java 会把所选实例的 `model` / `base_url` / `api_key` 环回透传。env 只是手工 curl 或
> 请求缺项时的兜底（见「模型从哪来」）。

### 探活

```bash
curl.exe -s http://127.0.0.1:18765/health
```

```json
{"status":"ok","service":"openjudge-sidecar","version":"1.0.0","judge_model_configured":true,"judge_model":"qwen-plus","language":"zh"}
```

> `/health` **恒 200**（即使没配模型，只在 body 里标 `judge_model_configured=false`）。
> 这是刻意的：Java 侧探活失败会把 case 记成 `SKIPPED`，所以要区分"进程没起"和"模型没配"。
> 刚起进程时可能还没 bind 完，等 20~40 秒再探。

### 闪退 / 起不来怎么查（Windows）

本次（2026-11）修复的根因：`.venv` 是 **uv** 建的（`pyvenv.cfg` 里有 `uv = ...`），
`uv venv` 默认**不装 pip**，旧脚本第一步 `python -m pip install --upgrade pip` 就报
`No module named pip` 直接退出 —— 双击 `.sh` 时窗口一关，什么也看不到。现在：

1. 脚本先探测 pip，缺了就 `ensurepip --upgrade` 补齐（离线可用），再不行用 `uv pip` 兜底；
2. 依赖已能 `import uvicorn, fastapi, openjudge` 就跳过安装，不依赖网络；
3. 任何失败都会：红色错误 + `start.log` + 交互窗口 `按回车键关闭窗口`（不再秒关）。

对照表：

| 现象 | 根因 | 处理 |
|------|------|------|
| 窗口秒关 / 日志有 `No module named pip` | uv 建的 venv 不带 pip，旧脚本第一条 pip 命令就退出 | 已修：脚本自动 `ensurepip`；本机 `.venv` 也已补好 pip |
| `bad interpreter: /usr/bin/env: 'bash\r'` | `start.sh` 被写成 CRLF | `.gitattributes` 已强制 LF；`git config core.autocrlf false` 后重新检出，或 `dos2unix start.sh` |
| `找不到 Python >= 3.10` / `No suitable Python runtime found` | 本机只有 3.14 或 3.9；**uv 装的 3.12 不在 `py` 启动器里**（本机实测 `py -3.12` 报错） | 脚本已加 `uv python find 3.12` 候选；也可 `$env:PYTHON="D:\Environment\Python\python.exe"` |
| `端口 18765 已被占用（PID xxxx）` | 上一份 sidecar 没退干净或他进程占用 | `Get-NetTCPConnection -LocalPort 18765 -State Listen \| Select LocalPort,OwningProcess` 找 PID 后结束；**不换端口** |
| `依赖安装失败（pip 退出码 1）` | 无外网 / 代理 / 镜像不可用 | 配好代理或国内镜像后 `$env:OPENJUDGE_FORCE_INSTALL="1"; .\start.ps1` |
| `curl` 立刻访问失败 | 首次 import 重，进程还没 bind | 等 20~40 秒；看 `start.log` 是否已有 `Uvicorn running on ...` |
| 双击 `.ps1` 只弹出记事本 | 未「使用 PowerShell 运行」 | 开 PowerShell 执行 `.\start.ps1`（或右键 → 使用 PowerShell 运行） |

### 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `OPENAI_API_KEY` | 无 | 兜底 Key；请求体带 `api_key` 时可不配，两者都缺才 503 |
| `OPENJUDGE_MODEL` | 无 | 兜底模型名；请求体带 `model` 时可不配，也可用 `OPENAI_MODEL` |
| `OPENAI_BASE_URL` | SDK 默认 | 兜底 OpenAI 兼容网关；请求体带 `base_url` 时优先用请求体 |
| `OPENJUDGE_LANGUAGE` | `zh` | 提示词语言 `zh` / `en` |
| `OPENJUDGE_THRESHOLD` | `3` | 1~5 阈值，仅进 metadata |
| `OPENJUDGE_LLM_TIMEOUT_S` | `45` | 单次 LLM 超时，应小于 Java 侧 60s |
| `OPENJUDGE_STRUCTURED_OUTPUT` | `auto` | `response_format` 策略：`auto`（官方 OpenAI 用 `json_schema`，其余网关降级 `json_object`）/ `json_schema` / `json_object` / `none` |
| `OPENJUDGE_PORT` | `18765` | 仅启动脚本用 |

### 结构化输出兼容（DeepSeek / GLM 必看）

py-openjudge 0.2.4 只对 `qwen` / `gemini` / `pai-judge` 把结构化输出降级为
`response_format={"type":"json_object"}`；**其它模型**会把 Pydantic 类交给
`client.chat.completions.parse()`，SDK 随即发 `json_schema`。DeepSeek、GLM 等
OpenAI 兼容网关不支持该类型，会返回：

```text
openai.BadRequestError: Error code: 400 - This response_format type is unavailable now
```

sidecar 在 `scoring.build_model` 里装了客户端兼容层：

- 基址不是 `api.openai.com` 时，`json_schema` 自动降级为 `json_object`；
- 网关连 `json_object` 也不支持（仍报 `response_format` 相关 400）时，去掉
  `response_format` 再试一次，字段由提示词要求 JSON + 上游 `repair_and_load_json` 兜底；
- 可用 `OPENJUDGE_STRUCTURED_OUTPUT=json_schema|json_object|none` 强制指定，便于自建网关排障。

> 兼容层只影响「结构化输出用哪种 response_format」，不改变评分语义；
> 修改后需**重启 sidecar** 才生效。

### 模型从哪来（重要，2026-11 起）

**主路径：环回透传。** 跑批弹窗里选中打分模型后：

1. AgentHub Java 按 `judge_model_instance_id` 到模型中心取实例 + 供应商 + 凭证；
2. 用现网 AES-GCM（`AGENTHUB_SECRET_KEY`）解密出 Key；
3. 通过 `POST /v1/aevaluate` 的 `model` / `base_url` / `api_key` 字段，
   **经本机环回（127.0.0.1）** 传给 sidecar；
4. sidecar 用请求体凭据构造 `OpenAIChatModel`，评分结束后只回写
   `metadata.judge_model` / `metadata.judge_model_instance_id` / `metadata.judge_credential_source`。

**兜底：环境变量。** 请求体缺哪项就回落对应 env（`OPENJUDGE_MODEL` / `OPENAI_BASE_URL` /
`OPENAI_API_KEY`），仅用于手工 curl 或未指定实例的历史场景。两侧都没有 → 503，
**不会**静默给 0 分。

> 因此「选了实例却打错模型」在本版本不会发生：请求体凭据优先，env 只是缺项兜底。
> Java 侧若解密/取实例失败，会把该 case 记 `openjudge_status=ERROR`
> （`scores.openjudge.error=JUDGE_CREDENTIAL_UNAVAILABLE`），**不会**偷偷用 env Key 冒充。

### 后排建议：生产环境改为「sidecar 内网鉴权向模型中心取 Key」

本刀（2026-11）为了快速打通链路，选择**环回透传明文 Key**：只在 `127.0.0.1` 上传输，
不进日志/库/报告，风险面收敛在单机进程间。正式环境建议演进为：

1. sidecar 启动时用内网服务身份（mTLS / 服务令牌 / SPIFFE 等）向**模型中心**换取短期凭据；
2. 请求体只带 `judge_model_instance_id`，**不带** `api_key`；
3. 模型中心侧做调用方鉴权、凭据到期与审计，sidecar 不长期持有 Key；
4. Java 侧无需再解密模型 Key，凭据治理集中到模型中心一处。

该演进**不在本刀范围**（本刀只落文档，不实现），待「模型中心内网鉴权」REQ 再开工。

---

## 3. 和 AgentHub 对齐（如何打开软评分）

`AgentHub-server/src/main/resources/application.yml`：

```yaml
agenthub:
  eval:
    openjudge:
      enabled: ${AGENTHUB_EVAL_OPENJUDGE_ENABLED:false}   # 仓库默认 false
      base-url: ${AGENTHUB_EVAL_OPENJUDGE_BASE_URL:http://127.0.0.1:18765}
      timeout-ms: ${AGENTHUB_EVAL_OPENJUDGE_TIMEOUT_MS:60000}
      default-graders:
        - RelevanceGrader
```

本地开关（**不要**把 yml 里的默认值改成 true 提交，用环境变量）：

```powershell
$env:AGENTHUB_EVAL_OPENJUDGE_ENABLED = "true"
$env:AGENTHUB_EVAL_OPENJUDGE_BASE_URL = "http://127.0.0.1:18765"
```

> 若你已在工作区把 yml 的 `:false` 直接改成了 `:true`，那是本机改动，
> 提交前请还原（硬规范：全站 openjudge 默认保持关闭）。

注意：`enabled=true` **还不够**——创建跑批时必须在弹窗里选"打分模型"，
本 run 才会真的出站；不选则该 run 强制 `openjudge.enabled=false`、`SKIPPED`/`DISABLED`。

---

## 4. 手点三步

1. 起 sidecar（上一节的命令）→ `curl.exe -s http://127.0.0.1:18765/health` 返回 200。
2. AgentHub 用 `AGENTHUB_EVAL_OPENJUDGE_ENABLED=true` 启动，评测控制台建跑批时**选中打分模型**并执行。
3. 跑批结果里该 case 的 `scores.openjudge.status=OK`，带 `score` / `reason` /
   `judge_model_instance_id`；`gate_passed` 仍只跟规则断言走。

---

## 5. 常见 `SKIPPED` / `ERROR` 原因

| 现象 | 原因 | 处理 |
|------|------|------|
| `SKIPPED`（健康检查失败） | sidecar 没起 / 端口不对 / 只用 `127.0.0.1` 而 Java 在容器里 | 起 sidecar；确认 `openjudge.base-url` 可达 |
| `SKIPPED`/`DISABLED` | 建跑批时**没选**打分模型（本 run 覆盖为关闭） | 弹窗里选一个 CHAT 实例 |
| `ERROR` + `sidecar HTTP 503` | 请求体未带凭据，且 sidecar 也没配 `OPENAI_API_KEY` / `OPENJUDGE_MODEL` | 在弹窗里选打分模型，或配好 env 后重启 |
| `ERROR` + `JUDGE_CREDENTIAL_UNAVAILABLE` | Java 侧取实例/凭证失败或解密失败（实例停用、凭证被重置、Key 缺失） | 到「模型管理」确认实例启用并重置 Key；错误详情见 `scores.openjudge.reason` |
| `ERROR` + `sidecar HTTP 400` | `final_answer` 为空，或请求的 grader 全都不支持；也可能是网关拒绝 `response_format` | 看响应体 `error` / `detail`；若是 `This response_format type is unavailable now`，确认已重启带兼容层的 sidecar，或设 `OPENJUDGE_STRUCTURED_OUTPUT=json_object` / `none` |
| `ERROR` + `sidecar HTTP 502` | 全部 grader 调用失败（模型拒绝、鉴权错、超时） | 看 `detail.graders[].error` |
| `ERROR` + `sidecar 超时` | LLM 太慢，超过 Java `timeout-ms` | 调小 `OPENJUDGE_LLM_TIMEOUT_S`，或调大 Java 超时 |
| 分数偏低且 `query_source=case_key` | 现网 Java 未传 `query`，相关性用用例键兜底 | 见上文「`query` 说明」 |

---

## 6. 测试（窄测，零出站）

```powershell
cd D:\workspace\AI\AgentHub\scripts\openjudge-sidecar
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest tests -q
```

用例把 Judge 模型换成 `FakeModel`（只实现 `achat`，返回固定的 `parsed`），
覆盖：健康恒 200、归一化、`query` 兜底、缺 reference 跳过、部分/全部 grader 失败、
未配模型 503、**绝不 200 + 空分**；以及本刀的**环回凭据**：
不配 env 仅靠请求体凭据可打分、请求体优先于 env、缺 Key 才回落 env（标记 `mixed`）、
响应永不回写 `api_key`。`test_response_format_compat.py` 另覆盖 DeepSeek/GLM 的
`response_format` 降级（json_schema → json_object → 不带）与官方 OpenAI 保留 `json_schema`。
全程不发任何真实模型请求。

---

## 7. 进 git 的范围（哪些提交 / 哪些不提交）

本目录里**只有我们自己写的壳进仓**；上游库与运行产物一律不入库。

### 提交（我们写的 + 契约文档）

| 文件 | 说明 |
|------|------|
| `app.py` | FastAPI 入口：`/health`、`/v1/aevaluate`、错误映射 |
| `scoring.py` | grader 装配、归一化聚合、失败转非 2xx |
| `settings.py` | 环境变量读取（模型 / 语言 / 阈值 / 超时 / 端口） |
| `requirements.txt` | 运行期依赖，`py-openjudge==0.2.4` **钉死版本** |
| `requirements-dev.txt` | 窄测依赖（pytest / httpx） |
| `start.ps1` / `start.sh` | 一键启动（venv + uvicorn，仅 127.0.0.1:18765） |
| `README.md` | 本文（契约、启动、排障） |
| `tests/test_app.py` | 窄测，`FakeModel` 注入，零出站 |
| `.gitignore` | 本目录的忽略规则（见下） |
| `.gitattributes` | `start.sh` 强制 LF，避免 bad interpreter |

### 不提交（`.gitignore` 拦下）

```gitignore
.venv/
venv/
env/
__pycache__/
*.py[cod]
*.pyo
.pytest_cache/
.mypy_cache/
.ruff_cache/
*.egg-info/
.env
.env.*
!.env.example
*.key
*.pem
*.log
```

根 `.gitignore` 另有 `**/.venv/`、`**/__pycache__/`、`.pytest_cache/`、`*.py[cod]`、
`.env` / `.env.*` 兜底，两层都生效。

### 明确不进仓的第三类

- **`py-openjudge` 上游源码树**：不拷贝、不 vendor。依赖靠
  `pip install -r requirements.txt`，许可证只登记（见下）。
- 任何 Judge 模型的 Key：只走环境变量或本地 `.env`（已被忽略）。

---

## 8. 许可证

| 组件 | 许可证 | 登记位置 |
|------|--------|----------|
| 上游 `py-openjudge==0.2.4` | **Apache-2.0** | 全文 [`LICENSES/py-openjudge-LICENSE.txt`](../../LICENSES/py-openjudge-LICENSE.txt)，登记于根 [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md) |
| 本目录的壳代码 | 随 AgentHub 仓库（根 LICENSE 尚未定，推 Git 前再定） | — |

Apache-2.0 要求保留版权与许可声明：我们**不分发**上游源码，只在运行时 `pip` 安装，
故以「许可证全文 + 文本登记」满足声明义务；不要把上游目录拷进仓。

---

## 9. 明确未做

- 只包了 3 个 grader（Relevance / Correctness / ToolSelection）；Trajectory / Memory /
  Reflection / Hallucination 等未接。
- 不提供批量/异步评分接口（Java 是逐 case 同步调用）。
- 不反查 AgentHub 模型实例表；凭据只接受两种来源：请求体环回透传（主）或环境变量（兜底）。
  **未实现**「sidecar 内网鉴权向模型中心取 Key」——见上文「后排建议」。
- 不做重试（`max_retries=0`），与 Java 侧"失败即 ERROR"一致。
- 不参与 `gate_passed`。
