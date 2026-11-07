# AgentHub

Agent 中台：以「配置驱动」的方式管理模型、工具、知识库、提示词与记忆，向下屏蔽厂商差异，向上提供统一的 Agent 运行 / 对话、多智能体编排与自动评测能力。单模块 Spring Boot 后端 + Vue3 管理台，可独立部署为单 jar 或 Docker 服务。

## 能力亮点

**多模式编排（AgentScope-Java 引擎）**

| 模式 | 语义 |
|---|---|
| `REACT` | 单 Agent 边推理边调工具，多轮工具循环 |
| `ROUTER` | 一层路由父节点：先判意图，再把子问题分发给多个 `REACT` 子 Agent；多意图并行、上下文隔离，终答可拼接（`CONCAT`）或由父模型汇总（`SUMMARIZE`） |
| `PLAN_AND_EXECUTE` | 先规划出有序步骤，再按序串行执行，最后汇总终答；可要求「规划后停住、人工批准再执行」 |

运行限制（轮次 / 墙钟 / 工具与 LLM 调用次数 / 重试 / 工具结果裁剪）平台给硬顶，Agent 可在此之下逐项收紧。

**多智能体（`ma_def` 方案）**

- 方案与候选的**唯一真源**是 `ma_def` + `ma_candidate`；Agent 详情的「编排」Tab 只做只读摘要与深链，不再双写旧 `agent_route`（无启用方案绑根时执行回落读，并给系统提示）。
- 列表入口「编排 → 多智能体」；绑根方案深链到对应 `ROUTER`，`root_agent_id=NULL` 的**虚拟根**可直接按方案运行（无 Agent 模型槽时用方案里的判路由模型）。
- 六种模板：路由并行、固定串行、固定并行、评审环、路由后串行、**竞争选优 `COMPETE`**（同题 fan-out ≥2，默认评选模型选优 `JUDGE_PICK`，失败降级 `FIRST_SUCCESS`；不做拼接 / 投票委员会 / 多轮辩论）。
- 汇总策略：`CONCAT` / `SUMMARIZE` / **`VOTE`**（多路终答进票池，评选模型选一路原文；仅 1 路成功时跳过投票，失败降级 `CONCAT`）。`COMPETE` 是模板选优，`VOTE` 是汇总投票，两者语义与降级方向不同。

**模型中心**

- 供应商 → 凭证 → 实例三段式管理，按**用途**（对话 / 路由 / 改写 / 摘要 / 记忆 / 嵌入 / 重排 / 视觉 / 语音识别 / 语音合成 / 护栏）建实例与绑定，按用途套用温度策略。
- 出站走统一防腐层：`OPENAI_COMPAT`、`DASHSCOPE`（兼容模式与灵积原生自动分流）、`ANTHROPIC` 三协议适配为同一套请求 / 响应 / 流式事件模型，配统一 SSE 写出与 `done` 兜底，客户端断开可取消。
- 连通测试按「协议 × 用途」矩阵分流：生成族走各协议聊天口，嵌入 / 重排走专用端点，视觉走多模态报文，语音走兼容或 DashScope 原生专用口，护栏按协议审核 / 分类口探活；协议确实没有该用途端点时明确报「该协议不支持此用途」，不拿对话接口冒充。
- `TTS` 实例可另填「附加参数」JSON 对象（存 `extra_json.speechParams`，≤8KB，仅 TTS 露出）：平台只校验 JSON 对象、体积、控制字符与凭证 / 系统保留键，**不做键名转换**，出站时原样浅合并进该协议 TTS 报文的可选参数位置（兼容口合并到请求体顶层，DashScope 原生合并到 `input`）；`model` / `input` / 文本 / 音频与凭证始终以平台值为准，厂家不认的键由对方报错并脱敏回显。
- **平台级实例**：`EMBEDDING` / `RERANK` 可各指定**一条**平台级实例（`platform_locked`），供记忆的语义召回与重排使用，不按 Agent 绑定解析；同用途第二条由库层唯一索引 `uk_platform_slot` 拦住。`EMBEDDING` 连通测试成功即写维度快照 `embedding_dim`，这是记忆向量集合的建集维度；没有平台级 Embedding 时记忆不做语义召回，自动回落 MySQL。

**工具**

- 两层目录「工具源 → 工具项」，内置工具与 MCP 源同页管理。
- MCP 支持 SSE（2024-11-05）与 Streamable HTTP（2025-03-26）两种传输，按远端 `tools/list` 同步工具项，同步后默认停用、人工启用。
- 进 schema 只有一条判据：工具源启用 ∧ 工具项启用 ∧ Agent 已绑定；装配与执行共用，不出现「schema 里有、调用时跳过」。
- 工具必填参数闸门：本轮输入 / 会话上下文 / 已注入长期记忆三档都无依据时，不执行工具，改为向用户追问，避免模型脑补参数。

**知识库（RAG，Milvus）**

- 建库即建集合，向量维度跟嵌入模型锁定；Milvus 不支持稀疏向量时自动降级为纯向量并把真实能力落库。
- 文档入库：格式白名单（txt / md / pdf / docx / 常见图片，单文件 20MB）→ 解析（PDF 文字层、DOCX、Markdown / 文本；OCR 与外部解析为可选增强，无 Key 时回退本地不报错）→ 结构识别 → 按文档类型路由切片策略 → 向量化 → 双写向量库与切片表；状态、阶段、进度落库，可重试、可换片长重新切片。
- 检索：向量召回 + 可选 BM25 召回 → RRF 融合 → 截断 → 回表补全正文 → 可选重排；以 `searchKnowledgeBase` 工具暴露给 Agent，库范围只来自 Agent 绑定，模型无法越权指定。
- 失败语义与空结果分开：未绑定、无命中、向量库不可用三种情况各有固定话术，不用空数组冒充「没查到」。

**提示词三层**

- `L1` 平台全局约束（按语言、版本化、发布生效）→ `L2` 按用途的模板（段落分「锁定 Core / 开放 Soft」）→ `L3` Agent 实例覆写（只能改 Soft 段 + 追加区）。
- 统一 `PromptAssembler` 组装：一层链路、一次性变量替换、按预算尾部截断（L1 与锁定段永不裁剪）；缺 L1、缺模板、缺必选段一律阻断出站，不拼半截提示词。
- 缓存三级（进程内 → Redis → DB）+ 单飞 + 发布后失效，避免配置缺失打穿数据库。

**Skill**

可复用的 Markdown 正文，与模型、工具分离。常规按目录注入（标题 + 场景，预算内附全文），命中 `trigger_keywords` 时不经模型判断直接强制注入正文。

**记忆**

- 三层：短期记忆（Redis 热数据 + MySQL 持久化双写）、长期记忆（`fact_key` 归并）、Agent 工作记忆；上下文窗口按轮次 / 字符 / 空闲时间规划，触顶先滚动摘要再裁剪旧轮。
- 长期记忆只在**显式「记住」**、**会话结束**（空闲超时 / 懒结案 / 取消 / 删除进行中会话）、**滚动摘要成功后**三处抽取，每轮成功或失败都不抽；显式「记住」异步投递，不占本轮首字延迟。
- **合并口径**：写库前先做 `fact_key` 同义归一（居住 / 饮食 / 饮品偏好族各收敛到一个规范键），同键只留一条并保留更高的 `importance`；用户纠错（「不是评估是苹果」）与对助手更正值的确认会作为正常抽取输入覆盖旧值，助手单方面的纠错独白不入库。
- **LONG 语义召回（Milvus）**：`memory_item` 是唯一真源，Milvus 里的专用集合（`agenthub_memory_long`，与 RAG 的 `agenthub_kb_*` 物理隔离）只是可重建副本。新写入在事务内标记 `vector_sync_status=PENDING`，提交后异步双写；失败记 `FAILED` + 次数 + 脱敏原因，由有界补同步（手动接口 / 可选定时 / 重建后自动续补）收口。集合字段含 `expired_time`（毫秒时间戳，无过期写 `0`）、`importance`、`content`（截断副本）；过期清理与删除会同步删掉向量点。
- **降级**：没有平台级 Embedding、集合未就绪、出站超时或失败时，召回直接回落 MySQL Top N（`importance` 降序 + 最新更新），不空手也不编造；向量侧任何失败都不回滚业务库。
- **换维**：平台级 Embedding 换成不同维度时 Milvus 不支持原地改维，只能走**受控重建**（危险确认 + 目标集合名校验 → 删集合 → 按快照维重建 → 存量打回 `PENDING` → 有界续补）；代码不会静默 `DROP` 生产集合。

**Agent 自动评测**

- 用例集 → 复用现网运行入口跑批 → 规则断言**硬门禁**（`gate_passed` / `PASS|FAIL`）+ OpenJudge **软评分**（`scores.openjudge.*`），报告落 JSON 与 Markdown。
- 建跑时可**可选打分模型**：不选则本 run 关闭软评分只跑规则；选了才调本机 OpenJudge sidecar（`127.0.0.1:18765`，`POST /v1/aevaluate`）。**软分默认不翻转 `gate_passed`**。
- Judge 凭据由 Java 从模型中心解密后经本机环回透传（`model` / `base_url` / `api_key`），Key 不进日志 / 库 / 报告；解密失败记 `ERROR` + `JUDGE_CREDENTIAL_UNAVAILABLE`，不回退环境变量。
- sidecar 是独立 Python 进程（`scripts/openjudge-sidecar`，Windows 用 `start.ps1`）；未启动时软评分 `SKIPPED`，不影响规则结论。

**会话标题**

首轮对话落库后异步生成 ≤10 汉字的标题（`agent_run_session.session_title`），跑在独立的轻量线程池上、失败只记日志，不阻塞也不影响这一轮响应；用户手动改名会把 `title_source` 置 `MANUAL`，此后自动生成不再覆盖。左栏标题与首问预览分列，互不踩。

**可观测与计量**

- 调用追踪：一次提问一条 trace，每一步一条 span，自增 id + 父子关系成树（含路由并行扇出、计划模式串行步骤），按人可见范围裁剪。
- Token 用量：每次调用记流水、按业务日汇总；每小时 10/30/50 分自动汇总当日（可关），控制台也可按日期范围重算；单价规则与计价展示当前关闭，只做计量。
- 全链路脱敏：失败文案只给用户面话术（抹掉主机、端口、路径、异常类名与堆栈），模型 Key、Bearer、密文、密码不进日志与追踪。

**对话语音（Agent 管理 → 试跑）**

- 听写：输入框旁的麦克风图标录整段音频 → `POST /api/runtime/voice/transcribe` 识别 → 文字填入输入框，**不自动发送**。必须为 Agent 绑定 `ASR` 模型；未绑定时图标降透明并说明原因（仍可 hover，点击不生效），禁止回退浏览器 Web Speech、禁止拿 Chat 模型冒充识别。DashScope 兼容模式走 `/audio/transcriptions`，原生基址走 `qwen3-asr-flash` 同步 `multimodal-generation`。
- 朗读：麦克风前的喇叭图标是**自动朗读开关**——开启后只朗读**之后完成**的最终回答（不含思考过程 / 工具原文，也绝不补读会话历史），关闭立即停掉正在播放的那一条。绑定 `TTS` 模型时走 `POST /api/runtime/voice/speak` 合成音频；DashScope 兼容模式走 `/audio/speech`，原生基址走 `SpeechSynthesizer`（`audio.data` 或 `audio.url` 均可）。未绑定或合成失败（超时、凭证被拒等）自动降级浏览器 `speechSynthesis`，并给一条非打断轻提示。单条回答另有图标式播放 / 暂停 / 停止，便于手动重听，不排文字按钮。图片上传同为图标按钮。
- 模型取用：只认该 Agent 在「模型配置」Tab 的 `ASR` / `TTS` 槽，不做平台默认 Key 兜底；凭证仍走既有 AES-GCM 解密，不新建 Key 存储。
- 边界：音频只在内存流转，不落业务表、不进对象存储；单段录音 ≤ 10MB（webm / wav / mp3 / m4a / ogg，前端 60 秒自动结束），单次合成 ≤ 4000 字（超限改浏览器朗读）。上限见 `agenthub.runtime.voice.*`。

**平台基座**

Sa-Token 登录与 `Bearer` Token、菜单树与按钮级权限码、部门 `path` 前缀数据范围、登录日志与操作日志。调用方应用（`appId` + `apiKey`）只存哈希，明文仅在创建或重置时返回一次。

> 尚未落地的能力（不在上表内）：运行时自我反思 / 自动选模式、入站 apiKey 调 Agent、检索链路中的查询改写执行、第三方 Dataset 接入、评测坏案回流 / EvalScope 桥、OTel / 多实例实时增量。这些只在配置位或表结构上预留，不作为已交付能力对外描述。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 17、Spring Boot 3.2（单模块 Maven）、MyBatis-Plus、Sa-Token |
| Agent 引擎 | AgentScope-Java 2.0.4（core + OpenAI 兼容 / DashScope 模型扩展） |
| 评测 | 规则断言门禁 + OpenJudge（`py-openjudge` 本机 HTTP sidecar，软评分） |
| 数据库 | MySQL 5.7+（兼容 8.0，不使用 8.0 专有语法） |
| 缓存 | Redis（记忆窗口、短期记忆、提示词缓存）+ Caffeine（进程内） |
| 向量库 | Milvus 2.5+ standalone（dense + BM25；低版本自动降级纯向量） |
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + Pinia + Vue Router、ECharts |
| 交付 | Docker Compose（依赖 + 后端 + Nginx 前端）、单 jar 运行 |

## 仓库结构

```
AgentHub/
├── AgentHub-server/       后端（单模块 Spring Boot）
│   └── src/main/java/com/agenthub/
│       ├── auth/ admin/ audit/  登录、用户角色菜单部门、审计日志
│       ├── model/ tool/ skill/  模型中心、工具目录、Skill
│       ├── prompt/ agent/       提示词 L1/L2、Agent 定义与多智能体方案（ma_def）
│       ├── runtime/             AgentScope 编排、运行会话与 SSE
│       ├── eval/                评测用例 / 跑批 / 规则门禁 / OpenJudge 适配
│       ├── rag/                 知识库、入库、检索
│       ├── memory/ obs/ billing/ 记忆、调用追踪、Token 计量
│       ├── spi/ common/         扩展接口、Result / 错误码 / 通用能力
│       └── AgentHubApplication.java
├── AgentHub-web/          管理台（Vue3 + Vite + TS + Element Plus）
│   └── src/{api,views,components,stores,router,types,utils}
├── scripts/
│   └── openjudge-sidecar/ OpenJudge 本机 HTTP sidecar（Python，软评分）
├── sql/
│   ├── agenthub.sql       全量建表（含记忆向量同步列与平台级模型列）
│   ├── data-init.sql      菜单、权限、内置工具、L1/L2 提示词等种子
│   └── schema-delta-*.sql 当期结构增量（存量库升级，可重复执行）
├── docs/                  架构设计、各域设计定稿与编码规范
├── docker-compose.yml     依赖（MySQL / Redis / Milvus 依赖链）+ 业务容器
└── .env.example           compose 与环境变量模板
```

后端按域分包，域内统一 `api`（Controller + DTO）→ `application`（编排 / 事务）→ `domain`（模型 + 仓储接口）→ `infra`（Entity / Mapper / 外部适配）；Controller 不写业务，Entity 不出 `infra`。

## 快速开始

### 1. 环境要求

- JDK 17（`JAVA_HOME` 指向 17）
- Maven 3.8+
- Node.js 20+ 与 npm
- MySQL 5.7+（本机安装 或 使用 compose）
- Docker + Docker Compose（可选；RAG 需要 Milvus，建议用 compose 起）

### 2. 起依赖

```bash
# bash / zsh
cp .env.example .env          # 填写库口令、主密钥等（.env 不入库）
docker compose up -d mysql redis etcd minio milvus
```

```powershell
# PowerShell
Copy-Item .env.example .env
docker compose up -d mysql redis etcd minio milvus
```

- `milvus` 依赖 `etcd` 与 `minio` 的健康检查，首次启动需建元数据并预热（健康检查带 90s 预热期）。
- 后端**不**依赖 Milvus 启动：Milvus 未就绪时应用照常运行，只有建库 / 重建集合 / 检索返回「知识库不可用」。跑 RAG 前确认 `docker compose ps` 里 `milvus` 为 healthy。
- 只做非 RAG 功能时，`docker compose up -d mysql redis` 即可。
- compose 里的 `mysql` 默认不暴露端口、`redis` 映射到 `6380`。若后端**在宿主机直跑 jar**（默认连 `127.0.0.1:3306` 与 `127.0.0.1:6379`），二选一：用本机已装的 MySQL/Redis；或取消 `docker-compose.yml` 中 `3307:3306` 的注释并用 `AGENTHUB_DB_URL` 指向 `3307`，同时用 `SPRING_DATA_REDIS_PORT=6380` 指向 compose 的 Redis。也可整栈 `docker compose up -d --build` 让后端在容器内按服务名互连。

### 3. 导入数据库全量脚本
空库首次初始化（compose 首次创建数据卷时会以只读方式自动执行这两个脚本；已有库或自建库时手动导入）：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS agenthub DEFAULT CHARACTER SET utf8mb4"
mysql -u root -p agenthub < sql/agenthub.sql     # 全量建表
mysql -u root -p agenthub < sql/data-init.sql    # 菜单 / 权限 / 内置工具 / 提示词种子
```

`sql/agenthub.sql` 是完整结构导出，文件内含 `DROP TABLE IF EXISTS`，**只用于初始化新库**：对已有数据的库重复执行会清空表数据。后续结构变更以 `sql/` 根目录当期增量脚本为准，禁止 `DROP` 既有表。`sql/temp/` 是历史增量脚本归档，不提交。

**存量库升级**：不要重跑全量脚本。按 `sql/` 根目录当期增量执行，例如平台级 Embedding / Rerank 与维度快照用 `sql/schema-delta-model-platform-lock.sql`（含 `platform_locked` / `embedding_dim` / `platform_slot` 与唯一索引，可重复执行）；`memory_item` 的 `vector_sync_status` / `vector_sync_fail_count` / `vector_sync_error` 与 `idx_vector_sync` 已进全量 `agenthub.sql`，存量库需按当期增量补齐后，再对平台级 Embedding 做一次连通测试写入维度快照。

### 4. 配置本机参数（不进仓库）

复制模板并按注释填写，主密钥与库密码只留本机：

```bash
# 宿主机直跑 jar：放在后端目录下的 config/，Spring Boot 会自动加载
cp AgentHub-server/src/main/resources/application-local.yml.example AgentHub-server/config/application-local.yml

# 或不落文件，用环境变量注入：
#   AGENTHUB_SECRET_KEY / AGENTHUB_DB_USER / AGENTHUB_DB_PASSWORD / AGENTHUB_MILVUS_URI
#   AGENTHUB_WORKSPACE_ROOT_PATH（会话级文件工具的落盘根目录，可留空走系统临时目录）
```

```bash
openssl rand -hex 32    # 生成 32 字节主密钥，填入 agenthub.crypto.secret-key
```

用 compose 起后端时，外部配置挂载在 `./secrets/application-local.yml`（`secrets/` 已 gitignore）。

### 5. 启动后端与前端
```bash
# 后端（在 AgentHub-server 目录下执行，加载同目录 config/application-local.yml）
cd AgentHub-server
mvn -DskipTests package
java -jar target/agenthub-server.jar

# 前端（另开终端）
cd AgentHub-web
npm install
npm run dev
```

- 控制台：<http://localhost:5173>
- 接口：<http://localhost:8080>，前端 `VITE_API_BASE` 默认指向这里
- 健康检查：`GET /api/health`（匿名）
- 接口文档：<http://localhost:8080/swagger-ui.html>
- 评测软评分（可选）：先起 `scripts/openjudge-sidecar`（Windows 用 `start.ps1`），默认监听 `127.0.0.1:18765`；未启动不影响规则门禁。

开发环境默认账号 `admin`，初始口令见 `AGENTHUB_RESET_PASSWORD`（默认 `admin123`，仅开发用；生产必须改）。新建用户与重置密码都用该配置项，不写死在业务代码里。

首次登录后按顺序配置：模型供应商 → 凭证 → 模型实例（按用途）→ 工具源同步 / 启用 → 知识库（可选）→ Agent 定义与绑定 → 在 Agent 详情打开「对话」运行。

## 配置与安全

- **密钥一律不进仓库**：模型 / MCP 的 API Key 用 AES-GCM 加密存库、列表与详情只出掩码，创建或重置时明文只返回一次；调用方应用 `apiKey` 只存哈希。主密钥来自 `AGENTHUB_SECRET_KEY`，缺失时进程启动即失败。
- **不进 git 的内容**：`.env` 与 `.env.*`、`secrets/`、`**/application-local.yml`、`**/application-{dev,prod}.yml`、`*.pem` / `*.key`、日志与 `data/`；只保留 `.env.example` 与 `application-local.yml.example` 模板。
- **示例值不是凭据**：`.env.example`、`docker-compose.yml` 里的口令默认值是占位符，生产必须用真实环境变量覆盖；README 与文档不贴任何完整密钥样例。
- **主密钥轮换**：配置 `agenthub.crypto.previous-keys` 后启动，旧密文会被自动重写为新密钥，迁移完成再移除旧密钥。
- **运行工作区**：会话级文件工具（`workspaceWriteFile` / `ReadFile` / `ListFiles`）的落盘根目录由 `agenthub.workspace.root-path`（环境变量 `AGENTHUB_WORKSPACE_ROOT_PATH`）决定，默认 `./workspace-test`（相对进程工作目录，已 gitignore）；留空则回落到系统临时目录的 `agenthub_workspace`。该目录只放 Agent 运行产物，不参与备份。
- **会话标题开关**：`agenthub.runtime.trial.auto-title-enabled`（环境变量 `AGENTHUB_AUTO_TITLE_ENABLED`，默认 `true`）。关掉只是不再异步生成标题，左栏退回展示首问预览，不影响运行。
- **空闲批抽**：`agenthub.memory.long-extract-batch-enabled` 随仓默认 `true`（代码缺键时兜底 `false`）；`agenthub.memory.session-close-initial-delay-ms` 默认 `3600000`（1 小时），避免重启后立刻打库 / 出站。本机想快速验证空闲收口，用 `application-local.yml` 或 `AGENTHUB_MEMORY_SESSION_CLOSE_INITIAL_DELAY_MS=30000` 覆盖，不要把默认值改短后提交。
- **记忆向量**：集合名 `agenthub.memory.milvus-collection`（默认 `agenthub_memory_long`，必须落在 `agenthub_memory_` 命名空间）；就绪只看平台级 Embedding 与其 `embedding_dim` 快照，`agenthub.memory.embedding-dimension` 仅排障对照、不参与建集。补同步默认不自动跑（`vector-reconcile-schedule-enabled=false`），需要时调 `POST /api/memory/vector/reconcile` 或显式打开定时。
- **时间与鉴权约定**：时区 `Asia/Shanghai`，Token 走 `Authorization: Bearer <token>`，权限码统一 `{域}:{资源}:{动作}` 且菜单种子 / 后端注解 / 前端按钮同码。

## 常见端口

| 组件 | 宿主端口 | 说明 |
|---|---|---|
| 后端（直跑 jar） | `8080` | `server.port` 默认值，也是前端 `VITE_API_BASE` 默认指向 |
| 前端（Vite dev） | `5173` | 开发端口，已在后端 CORS 白名单 |
| compose `server` | `8081` → 容器 `8080` | 同时起本机 jar 时避免端口冲突 |
| compose `web` | `8088` → 容器 `80`、`8443` → 容器 `443` | Nginx 反代 `/api/` 到 `server:8080`；容器内 80 只做 HTTPS 跳转，容器部署请直接访问 `https://localhost:8443`（证书挂载在 `secrets/certs`） |
| `redis` | `6380` → 容器 `6379` | 记忆窗口、短期记忆、提示词缓存 |
| `milvus` | `19530`（gRPC）、`9091`（healthz / metrics） | 后端默认 `AGENTHUB_MILVUS_URI=http://127.0.0.1:19530` |
| `mysql` | 默认不暴露，需要时开 `3307:3306` | 库名 `agenthub` |
| `minio` | 默认不暴露，需要时开 `9000` / `9001` | 仅 Milvus 内部使用 |
| `openjudge-sidecar` | `18765` | 仅本机 `127.0.0.1`；评测软评分可选，未启动时 `SKIPPED` |

端口被占用时：后端换端口需同步改 `AgentHub-web/.env.development` 的 `VITE_API_BASE` 与后端 CORS 白名单，不要只改一侧。

## 文档

- `docs/架构设计.md`：分层总图、运行主链路、三种运行模式、模型防腐（含平台级实例）、提示词三层、RAG 双流水、工具闸门、记忆分层（含 LONG 向量旁路与受控重建）、可观测与部署拓扑（含 mermaid 图）。
- `scripts/openjudge-sidecar/README.md`：sidecar 契约、启动、排障与进 git 范围。

## 开源协议与致谢 (License & Acknowledgements)

**本项目以 MIT 许可发布**，Copyright (c) 2026 Desktop-code / AgentHub，完整条款见根目录 [`LICENSE`](LICENSE)。第三方依赖的许可证与版权声明见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)、[`AgentHub-web/THIRD-PARTY-NOTICES.md`](AgentHub-web/THIRD-PARTY-NOTICES.md) 与 [`LICENSES/`](LICENSES/) 目录。

本项目的画布编排功能依赖于开源项目 [Vue Flow](https://vueflow.dev/)，遵循 MIT 开源协议。感谢开源社区的贡献。

### 第三方组件清单

| 组件 | 版本 | 协议 | 用途 | 版权声明 |
|---|---|---|---|---|
| [@vue-flow/core](https://www.npmjs.com/package/@vue-flow/core) | `^1.48.2` | MIT | 工作流画布：节点渲染、连线、缩放平移 | `LICENSES/Vue-Flow-LICENSE.txt` |
| [@vue-flow/background](https://www.npmjs.com/package/@vue-flow/background) | `^1.3.2` | MIT | 画布网格背景 | 同上（同源版权，见该文件） |

Vue Flow 原始版权声明（MIT）：

```text
Copyright (c) 2019-2025 webkid GmbH
Copyright (c) 2021-2025 Burak Cakmakoglu
```

完整许可证正文见 [`LICENSES/Vue-Flow-LICENSE.txt`](LICENSES/Vue-Flow-LICENSE.txt)，发布或再分发时必须与产物一并保留。

### 使用方式声明

- 本项目**仅通过公开 API 引用** Vue Flow（`VueFlow` / `useVueFlow` / `Handle` / `Position` / `MarkerType` 等）及其发行版样式文件，**未复制、未修改其内部源码**，因此仓库内不存在需要单独加注版权头的衍生文件。
- 画布上的节点卡片（`WorkflowNodeCard.vue`）、悬浮工具栏、折叠面板等均为本项目自研实现，不属于 Vue Flow 衍生作品。
- 若后续为修 bug 或适配而 fork / 内联 Vue Flow 源码，必须在对应文件头部保留上述原始版权声明与本 MIT 声明全文，并在本表中登记。

### 新增第三方依赖的合规要求

1. 引入前确认协议与本项目（对外分发）兼容，优先 MIT / Apache-2.0 / BSD。
2. 在 `LICENSES/` 下新增 `{组件名}-LICENSE.txt`，内容为上游 `node_modules/<包>/LICENSE` 的**原样副本**，不得改写。
3. 同步登记到上面的「第三方组件清单」，写清版本、协议、用途与版权文件路径。
4. 若协议要求署名展示（如 Apache-2.0 的 NOTICE），一并保留并在本节引用。
