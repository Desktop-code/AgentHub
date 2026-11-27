# AgentHub

**可独立部署的企业 Agent 中台。** 用配置把模型、工具、知识库、提示词、记忆、技能装成可运行的 Agent，再用统一的「对话」入口和运行、观测把它们用起来。工作流与串行方案可以在对话里停等并继续，不必另起一轮。

后端为单模块 Spring Boot，前端为 Vue 3 管理台；可以打成单个 jar 运行，也可以用 Docker Compose 拉起一套带依赖的环境。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green.svg)
![Vue](https://img.shields.io/badge/Vue-3-42b883.svg)

![对话（多问题）](docs/architecture/images/02-对话-多问题.png)

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | JDK 17、Spring Boot 3.2（单模块 Maven）、MyBatis-Plus、Sa-Token |
| Agent 引擎 | AgentScope-Java 2.0.5：ReAct、Plan-Execute 与分发执行 |
| 数据 | MySQL 5.7+（兼容 8.0）、Redis、Milvus 2.5+ |
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + Pinia + Vue Router（工作流画布用 Vue Flow） |
| 交付 | Docker Compose（依赖 + 后端 + Nginx 前端）、单 jar 运行 |

## 界面

> 截图为 2026-11-15 实拍；现网新增的对话内停等、运行时限、引用区、工作流循环与画布调试未重拍，行为以文档正文为准。

![对话（单问题）](docs/architecture/images/01-对话-单问题.png)
对话：入口判定后按参与者成块展示，检索出处贴结果旁。

![多智能体](docs/architecture/images/13-多智能体MA.png)
多智能体：入口只做判定与分发，路由池可放单兵、方案与已发布工作流。

![工作流](docs/architecture/images/12-工作流-WF.png)
工作流：可视化画布，草稿与不可变发布版本分开；支持显式循环节点；画布「调试」按当前草稿运行并跟随执行节点；运行有整段与单节点上限，执行卡可停等后继续。开始节点声明对外入参、子图节点绑定已发布工作流并按该版本声明出槽仍在开发中，尚未合入 main；子图执行也尚未接入引擎。

![Agent 管理](docs/architecture/images/10-AGENT管理-网格窗.png)
Agent 管理：只放单兵，ReAct 与 Plan-Execute 两种执行方式。

![模型管理](docs/architecture/images/03-模型管理-网格栏.png)
模型管理：供应商 → 凭证 → 实例，按用途建实例并绑定。

![链路追踪](docs/architecture/images/14-链路追踪-列表.png)
链路追踪：一次提问一条追踪，判定、分发、子专家、工具与模型调用各占一个 span。

## 快速开始

本机 / 单机推荐 **Docker Compose 一键**拉起 MySQL、Redis、Milvus 链路、后端与前端。需要本机已安装 Docker（含 Compose 插件）。

### 1. 克隆并配置 `.env`

```bash
git clone https://github.com/Desktop-code/AgentHub.git
cd AgentHub
cp .env.example .env
```

具体填写步骤见 **`.env.example` 顶部注释**（库口令、主密钥、重置口令、MinIO 等）。不要把真实 `.env` 提交进仓库。

### 2. 启动

按 `.env.example` 配好 `.env` 后执行：

```bash
docker compose up -d --build
```

### 3. 访问

- 地址：`https://localhost:8443`（自签证书需浏览器信任一次）
- 账号：`admin`（口令见 `.env.example` 对 `AGENTHUB_RESET_PASSWORD` 的说明）

空的 `mysql_data` 卷首次启动会按顺序自动导入 `sql/agenthub.sql` → `sql/data-init.sql` → `sql/data-init-agent.sql`。已有卷不会重跑入口脚本；若旧卷缺第三份，对已运行的库单独执行 `sql/data-init-agent.sql`，不要 `docker compose down -v`。

不必先准备 `secrets/application-local.yml` 或证书目录。

### 备选：本机 jar + 前端开发服务

需要本机 JDK 17、Maven 3.8+、Node.js 20+。可用 Compose 只起依赖：`docker compose up -d mysql redis`（知识库再加 `etcd minio milvus`），再按 `.env.example` 与 `application-local.yml.example` 配好本机参数后：

```bash
cd AgentHub-server && mvn -DskipTests package && java -jar target/agenthub-server.jar
cd AgentHub-web && npm install && npm run dev
```

控制台 `http://localhost:5173`，后端默认 `8080`。本机直跑时新库需自行按顺序导入上述三份 SQL。

## 文档

- [架构设计](docs/architecture/AgentHub-架构设计.md)：分层、信息架构、运行时主链路、各能力域边界、部署拓扑、已落地对照与接下来预期。
- [功能说明](docs/architecture/AgentHub-功能说明.md)：各模块行为说明、仓库结构、运行与配置细节。
- [评测软评分服务](scripts/openjudge-sidecar/README.md)：契约、启动与排障。

## 开源协议与致谢

本项目以 **MIT** 许可发布，Copyright (c) 2026 Desktop-code / AgentHub，完整条款见 [LICENSE](LICENSE)。

第三方依赖的许可证与版权声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[AgentHub-web/THIRD-PARTY-NOTICES.md](AgentHub-web/THIRD-PARTY-NOTICES.md) 与 `LICENSES/` 目录。

工作流画布依赖 Vue Flow（MIT，Copyright (c) 2019-2025 webkid GmbH、Copyright (c) 2021-2025 Burak Cakmakoglu）。本项目只通过公开 API 引用其发行版，未复制或修改其内部源码；再分发时必须保留 `LICENSES/` 下的许可证正文。
