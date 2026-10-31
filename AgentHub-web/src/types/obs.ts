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
  lastMsgPreview: string | null
}

export interface ObsTrace {
  traceId: string
  sessionId: number
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
  promptChars?: number | null
  truncated?: boolean | null
  /** 旧正文兼容；仅当 input/output 皆空时可能出现 */
  content?: string | null
  depth: number
}
