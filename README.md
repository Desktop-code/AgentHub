# AgentHub

Agent 中台：以「配置驱动」的方式管理模型、工具、知识库、提示词与记忆，向下屏蔽厂商差异，向上提供统一的 Agent 试跑 / 对话与可观测能力。单模块 Spring Boot 后端 + Vue3 管理台，可独立部署为单 jar 或 Docker 服务。

## 能力亮点

**多模式编排（AgentScope-Java 引擎）**

| 模式 | 语义 |
|---|---|
| `REACT` | 单 Agent 边推理边调工具，多轮工具循环 |
| `ROUTER` | 一层路由父节点：先判意图，再把子问题分发给多个 `REACT` 子 Agent；多意图并行、上下文隔离，终答可拼接（`CONCAT`）或由父模型汇总（`SUMMARIZE`） |
| `PLAN_AND_EXECUTE` | 先规划出有序步骤，再按序串行执行，最后汇总终答；可要求「规划后停住、人工批准再执行」 |

运行限制（轮次 / 墙钟 / 工具与 LLM 调用次数 / 重试 / 工具结果裁剪）平台给硬顶，Agent 可在此之下逐项收紧。

**模型中心**

- 供应商 → 凭证 → 实例三段式管理，按**用途**（对话 / 路由 / 改写 / 摘要 / 记忆 / 嵌入 / 重排 / 视觉 / 语音识别 / 语音合成 / 护栏）建实例与绑定，按用途套用温度策略。
- 出站走统一防腐层：`OPENAI_COMPAT`、`DASHSCOPE`（兼容模式与灵积原生自动分流）、`ANTHROPIC` 三协议适配为同一套请求 / 响应 / 流式事件模型，配统一 SSE 写出与 `done` 兜底，客户端断开可取消。
- 连通测试按「协议 × 用途」矩阵分流，未适配的组合明确报错，不拿对话接口冒充嵌入 / 重排。

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

短期记忆（Redis 热数据 + MySQL 持久化双写）、长期记忆（`fact_key` 归并去重）、Agent 工作记忆三层；上下文窗口按轮次 / 字符 / 空闲时间规划，触顶先滚动摘要再裁剪旧轮。长期记忆只在显式「记住」、会话结束、滚动摘要三处抽取，不每轮都抽。

**可观测与计量**

- 调用追踪：一次提问一条 trace，每一步一条 span，自增 id + 父子关系成树（含路由并行扇出、计划模式串行步骤），按人可见范围裁剪。
- Token 用量：每次调用记流水、按业务日汇总，可重建汇总；单价规则与计价展示当前关闭，只做计量。
- 全链路脱敏：失败文案只给用户面话术（抹掉主机、端口、路径、异常类名与堆栈），模型 Key、Bearer、密文、密码不进日志与追踪。

**平台基座**

Sa-Token 登录与 `Bearer` Token、菜单树与按钮级权限码、部门 `path` 前缀数据范围、登录日志与操作日志。调用方应用（`appId` + `apiKey`）只存哈希，明文仅在创建或重置时返回一次。

> 尚未落地的能力（不在上表内）：运行时自我反思 / 自动选模式、入站 apiKey 调 Agent、检索链路中的查询改写执行、第三方 Dataset 接入、OTel / 多实例实时增量。这些只在配置位或表结构上预留，不作为已交付能力对外描述。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 17、Spring Boot 3.2（单模块 Maven）、MyBatis-Plus、Sa-Token |
| Agent 引擎 | AgentScope-Java 2.0.4（core + OpenAI 兼容 / DashScope 模型扩展） |
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
│       ├── prompt/ agent/       提示词 L1/L2、Agent 定义与绑定
│       ├── runtime/             AgentScope 编排、试跑会话与 SSE
│       ├── rag/                 知识库、入库、检索
│       ├── memory/ obs/ billing/ 记忆、调用追踪、Token 计量
│       ├── spi/ common/         扩展接口、Result / 错误码 / 通用能力
│       └── AgentHubApplication.java
├── AgentHub-web/          管理台（Vue3 + Vite + TS + Element Plus）
│   └── src/{api,views,components,stores,router,types,utils}
├── sql/
│   ├── agenthub.sql       全量建表（41 张表）
│   └── data-init.sql      菜单、权限、内置工具、L1/L2 提示词等种子
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
mysql -u root -p agenthub < sql/agenthub.sql     # 全量建表（41 张）
mysql -u root -p agenthub < sql/data-init.sql    # 菜单 / 权限 / 内置工具 / 提示词种子
```

`sql/agenthub.sql` 是完整结构导出，文件内含 `DROP TABLE IF EXISTS`，**只用于初始化新库**：对已有数据的库重复执行会清空表数据。后续结构变更以 `sql/` 根目录当期增量脚本为准，禁止 `DROP` 既有表。`sql/temp/` 是历史增量脚本归档，不提交。

### 4. 配置本机参数（不进仓库）

复制模板并按注释填写，主密钥与库密码只留本机：

```bash
# 宿主机直跑 jar：放在后端目录下的 config/，Spring Boot 会自动加载
cp AgentHub-server/src/main/resources/application-local.yml.example AgentHub-server/config/application-local.yml

# 或不落文件，用环境变量注入：
#   AGENTHUB_SECRET_KEY / AGENTHUB_DB_USER / AGENTHUB_DB_PASSWORD / AGENTHUB_MILVUS_URI
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

开发环境默认账号 `admin`，初始口令见 `AGENTHUB_RESET_PASSWORD`（默认 `admin123`，仅开发用；生产必须改）。新建用户与重置密码都用该配置项，不写死在业务代码里。

首次登录后按顺序配置：模型供应商 → 凭证 → 模型实例（按用途）→ 工具源同步 / 启用 → 知识库（可选）→ Agent 定义与绑定 → 在 Agent 详情打开「对话」试跑。

## 配置与安全

- **密钥一律不进仓库**：模型 / MCP 的 API Key 用 AES-GCM 加密存库、列表与详情只出掩码，创建或重置时明文只返回一次；调用方应用 `apiKey` 只存哈希。主密钥来自 `AGENTHUB_SECRET_KEY`，缺失时进程启动即失败。
- **不进 git 的内容**：`.env` 与 `.env.*`、`secrets/`、`**/application-local.yml`、`**/application-{dev,prod}.yml`、`*.pem` / `*.key`、日志与 `data/`；只保留 `.env.example` 与 `application-local.yml.example` 模板。
- **示例值不是凭据**：`.env.example`、`docker-compose.yml` 里的口令默认值是占位符，生产必须用真实环境变量覆盖；README 与文档不贴任何完整密钥样例。
- **主密钥轮换**：配置 `agenthub.crypto.previous-keys` 后启动，旧密文会被自动重写为新密钥，迁移完成再移除旧密钥。
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

端口被占用时：后端换端口需同步改 `AgentHub-web/.env.development` 的 `VITE_API_BASE` 与后端 CORS 白名单，不要只改一侧。

## 文档

- `docs/架构设计.md`：分层总图、试跑主链路、三种运行模式、模型防腐、提示词三层、RAG 双流水、工具闸门、记忆分层、可观测与部署拓扑（含 mermaid 图）
- `docs/AgentHub-架构设计.md`：域包划分、分层约束与各域职责
- `docs/架构定稿-企业级知识库RAG.md`、`docs/架构定稿-模型防腐层与统一SSE.md`：RAG 与模型出站链路的定稿口径
- `docs/时间字段硬规范.md`：库列与接口时间字段命名约定
- `docs/UI-布局与风格-AgentOne参考.md`：管理台布局与视觉规范
