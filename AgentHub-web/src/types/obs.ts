/** 调用追踪。所属域：obs。字段与 ObsSpanResp / obs_span 对齐。 */

export interface ObsSession {
  id: number
  agentId: number | null
  agentName: string | null
  runMode: string | null
  userId: number | null
  status: string | null
  /** 会话表仍是 started_at，左栏保持旧字段名 */
  startTime: string | null
  endTime: string | null
  /** 会话标题（自动生成或手改）。为空时左栏退回展示 lastMsgPreview */
  sessionTitle: string | null
  lastMsgPreview: string | null
}

export interface ObsTrace {
  traceId: string
  /** 对话场景为会话主键；工作流场景为占位 0，前端按 workflowId 判断是否展示 */
  sessionId: number | null
  /** 编排场景：obs_trace.workflow_run_id（工作流 run 主键的字符串） */
  workflowRunId?: string | null
  /** 编排场景：工作流定义 id */
  workflowId?: number | null
  /** 编排场景：工作流名称（后端从 attrs 解出，无需再查） */
  workflowName?: string | null
  agentId: number | null
  agentName: string | null
  userId: number | null
  channel: string | null
  title: string | null
  status: string | null
  startTime: string | null
  endTime: string | null
  latencyMs: number | null
  errorSummary: string | null
  llmCallCount?: number | null
  toolCallCount?: number | null
  tokenPrompt?: number | null
  tokenCompletion?: number | null
  tokenTotal?: number | null
  primaryModelName?: string | null
  finishReason?: string | null
  /** trace 级扩展 JSON（已脱敏） */
  attrsJson?: string | null
  /** 从 attrs 解出的脱敏失败原因，工作流场景可直接展示 */
  cause?: string | null
}

/**
 * 右栏一步。树键 id + parentId；depth 由后端按 parentId 计算，不入库。
 * 展示优先 stepNo；seq 为写入序。LLM / TOOL / ROUTE / RETRIEVE 专属字段按需出现。
 */
export interface ObsSpan {
  id: number
  parentId: number | null
  stepNo: string | null
  seq: number
  spanType: string | null
  name: string | null
  agentId: number | null
  agentName: string | null
  status: string | null
  startTime: string | null
  endTime: string | null
  latencyMs: number | null
  inputText: string | null
  outputText: string | null
  errorSummary: string | null
  modelInstanceId?: number | null
  modelName?: string | null
  provider?: string | null
  tokenPrompt?: number | null
  tokenCompletion?: number | null
  tokenTotal?: number | null
  finishReason?: string | null
  /** LLM：采样温度 */
  temperature?: number | string | null
  /** LLM：max_tokens 快照 */
  maxTokens?: number | null
  toolId?: number | null
  toolName?: string | null
  toolType?: string | null
  toolCallId?: string | null
  retryCount?: number | null
  toolSuccess?: number | null
  routeDecision?: string | null
  childAgentId?: number | null
  retrieveQuery?: string | null
  retrieveHitCount?: number | null
  retrieveTopK?: number | null
  retrieveKbId?: number | null
  /** 扩展 JSON 原文 */
  attrsJson?: string | null
  /** 人话摘要；无则不强行编 */
  brief?: string | null
  toolsInjected?: number | null
  skillsInjected?: number | null
  /** 系统提示 + Skill + 目录 + schema + 用户消息的字符合计 */
  promptChars?: number | null
  /** 上述合计的出站前估算 token（与 usage 精确值不同，用于看占比） */
  promptTokensEst?: number | null
  /** 受保护前缀字符数（L1 + 平台追加句 + 锁定段），红线，永不被裁 */
  protectedChars?: number | null
  /** 可压区字符数（开放段 + L3 追加 + 历史业务段） */
  softChars?: number | null
  /** 记忆段字符数；被预算让出时为 0 */
  memoryChars?: number | null
  /** Skill 段字符数（强制 + 按需） */
  skillChars?: number | null
  /** 工具目录字符数 */
  catalogChars?: number | null
  /** 可调用工具 schema 字符数（走 tools 字段） */
  schemaChars?: number | null
  /** 本轮用户侧消息字符数 */
  userChars?: number | null
  /** 其中【会话摘要】段字符数 */
  userSummaryChars?: number | null
  /** 其中【最近对话】段字符数 */
  userRecentChars?: number | null
  /** 其中【短期记忆】段字符数 */
  userShortChars?: number | null
  /** 其中本轮用户原文段字符数 */
  userCurrentChars?: number | null
  /** 本轮被落盘成文件的工具结果条数 */
  toolResultsOffloaded?: number | null
  /** 被落盘工具结果的原始正文合计字符数 */
  toolResultsOffloadedChars?: number | null
  /** 被硬上限截断正文的强制 Skill 条数 */
  forcedSkillClipped?: number | null
  /** 分段占比人话摘要，直接展示即可 */
  promptBreakdown?: string | null
  truncated?: boolean | null
  /** 旧正文兼容；仅当 input/output 皆空时可能出现 */
  content?: string | null
  depth: number
}
