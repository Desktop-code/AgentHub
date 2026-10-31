/**
 * 试跑页契约。所属域：runtime。摘要已经在服务端截断。
 */
export interface TrialAgent {
  id: number
  name: string
  code: string
  runMode: string
}

export interface TrialSession {
  id: number
  agentId: number
  agentName?: string
  runMode: string
  userId: number
  status: string
  startTime?: string
  endTime?: string
  messageCount?: number
  lastMsgPreview?: string
  errorSummary?: string
  /** 最近变更。左栏相对时间优先用它 */
  updateTime?: string
}

export interface TrialEvent {
  id: number
  sessionId: number
  seq: number
  eventType: string
  summary?: string
  /** 展示正文。助手是 Markdown，推理是思考片段，工具是结果 */
  contentText?: string
  tokenPrompt?: number
  tokenCompletion?: number
  createTime?: string
}

export interface TrialMessageResult {
  sessionId: number
  status: string
  assistantPreview?: string
  errorSummary?: string
  events: TrialEvent[]
}

/** 助手卡片里的一块。没有引擎中间步时不会出现 reasoning。 */
export interface AssistantBlock {
  kind: 'reasoning' | 'tool' | 'assistant' | 'route' | 'plan' | 'step' | 'system' | 'error'
  text: string
  /** 工具结果。和调用行相同或为空时不展示 */
  detail?: string
  /** plan 块的结构化视图；正文另外存一份 JSON 供回放 */
  plan?: TrialPlanView
  /** step 块的单步视图 */
  step?: TrialStepView
}

/** 计划中的一步。seq 即执行顺序。 */
export interface TrialPlanStep {
  seq: number
  title: string
  detail: string
}

/** 计划视图：PENDING / EXECUTING / REJECTED / DONE / FAILED */
export interface TrialPlanView {
  planId: string
  state: string
  summary?: string
  steps: TrialPlanStep[]
}

/** 单步进度：RUNNING / RETRYING / DONE / FAILED */
export interface TrialStepView {
  seq: number
  title: string
  state: string
  text: string
  /** 时间线一行文案，服务端已拼好 */
  line?: string
}

/** 一轮对话。用户是右侧气泡，助手是左侧宽卡片。 */
export interface ChatTurn {
  key: string
  role: 'user' | 'assistant'
  text: string
  blocks: AssistantBlock[]
  phase: string
  status: 'streaming' | 'done' | 'error'
  startTime: number
  /**
   * 本轮停在「等待批准计划」：没有助手正文，只渲染计划卡与批准/取消按钮。
   * 批准或取消后该标记清掉，按钮随之消失。
   */
  planPending?: boolean
  /** 该轮对应计划的 planId；批准时用它定位要续跑的计划 */
  planId?: string
}
