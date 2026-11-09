# runtime

控制台试跑。默认引擎是 AgentScope-Java 2.0.4 的 ReActAgent。Router 只做一层分发，子 Agent 再走 ReAct。

不在这里保存模型 Key。出站解密走 model 域的 SecretStore。
已绑定且启用的 MCP 工具与 BUILTIN 同一套 schema 预算；本轮暴露后注册可调用句柄，模型可发起真实 tools/call（鉴权/远端失败返回明确错误，不假装成功）。

**停用不进 schema**：工具源停用、工具项停用、Agent 绑定关闭，任一成立即不进提示词工具目录、不进 LLM tools/schema、不注册可执行句柄。判据统一在 `domain/service/ToolEnablement`，装配（`TrialPromptFactory`）与执行（`McpToolkitAssembler`）共用，禁止「schema 全量、执行再 skip」，否则模型会伪造文本 tool_call 并中止本轮。Router 子 Agent 复用同一 `TrialPromptFactory`，自动同规则。

## 系统提示段落顺序与截断保护

权威口径见 `docs/提示词工程.md` 第六节。前 3 段是**受保护 head**，任何截断都不得触碰：

```
1 L1 平台约束          ┐
2 平台追加句（ROUTER） ├ 受保护 head（PromptLayout.headChars 即其长度）
3 L2 全部锁定段        ┘
4 L2 全部开放段（含 L3 覆写）   ┐
5 L3 追加区【补充要求】         ├ 可压区（按字符从尾部裁）
6 历史业务段【业务提示词】      ┘（过渡层，紧贴 L3 收口）
7 记忆【工作记忆】/【用户偏好】  整段让出（最先）
8 Skill：强制块按硬上限裁 → 按需只给「标题 + 场景」（正文默认不进）
9 ## 工具目录（名称 + 一句；完整 schema 只走 tools 字段）
```

实现分工：`PromptAssembler` 负责 1～6 并产出 `PromptLayout`；`PromptInjectionComposer` 负责 7～9
并把各段整理成「充裕 / 最小」两套文本；**裁剪决策只有一处** `PromptBudgetTrimmer`；
`AssembledPromptGuard.require(prompt, layout)` 出站前校验「以 L1 开头 + 锁定段在场且排在开放段之前」。

三条硬规矩：

- **锁定段先于开放段**由代码保证（`orderedSegmentsByPhase`），不依赖库里 `sort_no` 碰巧填对；
- **裁剪以 `headChars` 为下界**，预算再小也只砍记忆 / 按需 Skill / 目录文案 / 可压区 / 强制 Skill，
  禁止砍 L1 与锁定段；
- **预算只有一套**：`agenthub.prompt.system-prompt-max-chars`（以及按需 Skill / 单条强制 Skill /
  工具 schema 三个分项）。组装层不再有自己的字符预算，历史上那第二把剪刀（`assembler.max-chars`）已删除。

分段计量写入 LLM span 的 `attrs_json`（`PromptInjectionAttrs`）：`protected_chars` / `soft_chars` /
`memory_chars` / `skill_chars` / `catalog_chars` / `schema_chars` / `user_chars` /
`user_summary_chars` / `user_recent_chars` / `user_short_chars` / `user_current_chars` /
`tool_results_offloaded` / `tool_results_offloaded_chars` / `prompt_tokens_est` /
`forced_skill_clipped` / `prompt_breakdown`，面板「查看扩展信息」直接可读。

## 会话窗与长工具结果（P1）

- **近轮窗口**：`agenthub.memory.ctx-max-turns`（默认 12）/ `ctx-max-chars`（默认 16000）。
  触顶走滚动摘要（`RollingSummaryService`），摘要成功才替换旧轮，失败不裁窗口。
- **摘要与近轮去重**：`TurnDeduplicator`，去空白后子串判断、24 字以下不判、
  逐条判定 + 整段兜底。同一段内容不会既在【会话摘要】又在【最近对话】。
- **长工具结果句柄化**：超 `agenthub.runtime.trial.tool-result-offload-chars`（默认 2000）的
  成功结果写入工作区 `tool-results/`，对话里只留摘要 + 虚拟路径，模型用 `workspaceReadFile` 续读。
  失败结果、`workspace*` 工具、缺 sessionId、磁盘失败一律不落盘（降级为原字符截断）。
- **安全边界唯一**：`WorkspaceSandbox` 同时服务 `workspaceWriteFile` 与自动落盘，
  路径校验 / 沙箱前缀 / 可执行后缀黑名单只有一份实现。

## SSE 出站（统一事件 + done 兜底）

依据 `docs/架构定稿-模型防腐层与统一SSE.md` §4。

- 出站写出类：`api/sse/UnifiedSseWriter`（done 兜底与新旧映射）、`api/sse/ServletSseFrameSink`（Servlet 写出与断连判定）；现网入口仍是 `api/TrialChunkWriter`，由它转调写入器。
- **正文合批**：`api/sse/SseTextBatcher` + `api/sse/SseBatchOptions`。`assistant` / `reasoning` 连续同类型增量按
  「时间窗（默认 50ms）+ 单帧字节上限（默认 2048）」合并成一帧，帧数降下来但**事件名、字段、文本顺序不变**；
  类型切换、任何控制帧（phase / route / tool / plan / step / file / vision / error / done）写出前先 flush，
  收尾 / 超时 / 取消路径也强制 flush，不丢尾字。参数见 `agenthub.runtime.sse.batch-window-ms` / `batch-max-bytes`。
- 映射表：`api/sse/UnifiedSseMapping`。`assistant`↔`message.delta`、`reasoning`↔`thinking.delta` 各发一帧兼容；
  `done` 与 `error` 新旧同名，**合并为单帧**（`done` 载荷是字段并集：`status`/`sessionId`/`errorSummary` + `finishReason`/`usage`）。
- done 兜底只在出站层用 `AtomicBoolean` CAS 一次，覆盖五条路径：上游已 done、流截断、超时、上游错误、客户端取消。
  **客户端取消必须由出站层补帧**——Adapter 取消后下游已无接收方，只有出站层还持有连接。
- `tool_call.delta` / `tool_call.completed` 只发新名，无旧等价物；工具执行结果仍是旧 `tool` 事件（语义不同，不可混用）。
- 传输仍是 Servlet 同步 SSE，禁止引入第二套响应式 Web 栈。

