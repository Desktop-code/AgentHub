# runtime

控制台试跑。默认引擎是 AgentScope-Java 2.0.4 的 ReActAgent。Router 只做一层分发，子 Agent 再走 ReAct。

不在这里保存模型 Key。出站解密走 model 域的 SecretStore。
已绑定且启用的 MCP 工具与 BUILTIN 同一套 schema 预算；本轮暴露后注册可调用句柄，模型可发起真实 tools/call（鉴权/远端失败返回明确错误，不假装成功）。

**停用不进 schema**：工具源停用、工具项停用、Agent 绑定关闭，任一成立即不进提示词工具目录、不进 LLM tools/schema、不注册可执行句柄。判据统一在 `domain/service/ToolEnablement`，装配（`TrialPromptFactory`）与执行（`McpToolkitAssembler`）共用，禁止「schema 全量、执行再 skip」，否则模型会伪造文本 tool_call 并中止本轮。Router 子 Agent 复用同一 `TrialPromptFactory`，自动同规则。

## SSE 出站（统一事件 + done 兜底）

依据 `docs/架构定稿-模型防腐层与统一SSE.md` §4。

- 出站写出类：`api/sse/UnifiedSseWriter`（done 兜底与新旧映射）、`api/sse/ServletSseFrameSink`（Servlet 写出与断连判定）；现网入口仍是 `api/TrialChunkWriter`，由它转调写入器。
- 映射表：`api/sse/UnifiedSseMapping`。`assistant`↔`message.delta`、`reasoning`↔`thinking.delta` 各发一帧兼容；
  `done` 与 `error` 新旧同名，**合并为单帧**（`done` 载荷是字段并集：`status`/`sessionId`/`errorSummary` + `finishReason`/`usage`）。
- done 兜底只在出站层用 `AtomicBoolean` CAS 一次，覆盖五条路径：上游已 done、流截断、超时、上游错误、客户端取消。
  **客户端取消必须由出站层补帧**——Adapter 取消后下游已无接收方，只有出站层还持有连接。
- `tool_call.delta` / `tool_call.completed` 只发新名，无旧等价物；工具执行结果仍是旧 `tool` 事件（语义不同，不可混用）。
- 传输仍是 Servlet 同步 SSE，禁止引入第二套响应式 Web 栈。

