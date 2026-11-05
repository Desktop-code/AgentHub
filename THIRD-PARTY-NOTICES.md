# 第三方依赖许可声明（AgentHub 服务端 / 工具脚本）

本文件登记**非前端**（`AgentHub-server` 与 `scripts/` 下工具）引入的第三方组件。
前端运行时库的声明在 [`AgentHub-web/THIRD-PARTY-NOTICES.md`](AgentHub-web/THIRD-PARTY-NOTICES.md)。

约定：
- 许可证**全文**统一放根 [`LICENSES/`](LICENSES/) 目录，文件名 `<上游名>-LICENSE.txt`；
- 本文件只做一行式登记（组件 / 版本 / 许可证 / 用途 / 全文位置）；
- **不拷贝上游源码树**；依赖一律走包管理器（Maven / pip）安装。

---

## py-openjudge（评测 OpenJudge 软评分）

- 用途：`scripts/openjudge-sidecar/` 的 grader 实现（Relevance / Correctness / ToolSelection）。
  仅作为**本机 HTTP sidecar** 被 `OpenJudgeScorerAdapter` 调用，不参与 `gate_passed`。
- 版本：`py-openjudge==0.2.4`（钉死在 `scripts/openjudge-sidecar/requirements.txt`）
- 许可证：**Apache-2.0**
- 上游：https://github.com/agentscope-ai/OpenJudge
- 许可证全文：[`LICENSES/py-openjudge-LICENSE.txt`](LICENSES/py-openjudge-LICENSE.txt)

> 说明：仓库内只有**我们自己写的壳**（FastAPI 包装、grader 装配、启动脚本、窄测），
> 上游库通过 `pip install -r requirements.txt` 安装；`.venv/` 与上游源码树均不入库。
> Apache-2.0 要求保留声明与 NOTICE（上游未提供单独 NOTICE 文件），
> 因此在此登记声明并保留许可证全文。
