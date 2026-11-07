/**
 * 试跑页契约。所属域：runtime。摘要已经在服务端截断。
 */
import type { ObsSpan } from '@/types/obs'
import type { OrchestrationLaneEvent, OrchestrationLaneModel } from '@/utils/orchestrationLane'

export interface TrialAgent {
  id: number
  name: string
  code: string
  runMode: string
  /** 是否已绑定可用的 VISION 模型。false 时上传图片按钮置灰 */
  visionEnabled?: boolean
  /** 是否已绑定可用的 ASR（语音识别）模型。false 时「说话」按钮置灰并说明原因 */
  asrEnabled?: boolean
  /** 是否已绑定可用的 TTS（语音合成）模型。false 时朗读降级浏览器，不禁用 */
  ttsEnabled?: boolean
}

export interface TrialSession {
  id: number
  /** 绑根会话的 Agent；虚拟根会话为空（没有 root Agent） */
  agentId: number | null
  agentName?: string
  /** 虚拟根会话归属的方案；绑根可空 */
  maId?: number
  /** 方案编码 / 名称：左栏与页头在虚拟根下用它兜底 */
  maCode?: string
  maName?: string
  runMode: string
  userId: number
  status: string
  startTime?: string
  endTime?: string
  messageCount?: number
  /** 会话标题：后端在首轮成功后写入（首句截断 ≤24 字符），或用户手改。空表示尚未生成 */
  sessionTitle?: string
  /** 标题来源：AUTO 自动生成 / MANUAL 用户手改 */
  titleSource?: string
  /** 首轮提问的短预览，作为副标题；不参与标题覆盖 */
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
  /**
   * 块级稳定 id（前端态，随会话快照一起持久化）。
   *
   * <p>只用于渲染身份，不参与任何落库/回放语义。为什么必须要有：
   * 视觉块与编排调度卡是 {@code unshift} 插到块列表队首的，若用数组下标当 {@code v-for} 的 key，
   * 一插队就让后面所有块的 key 整体位移，Vue 只能整块卸装重建——DOM 重挂、Markdown 槽缓存全失效、滚动跳动。
   */
  id?: string
  kind:
    | 'reasoning'
    | 'tool'
    | 'assistant'
    | 'route'
    | 'plan'
    | 'step'
    | 'system'
    | 'error'
    | 'vision'
    | 'file'
    /**
     * 前端态：Router 试跑的编排调度卡。
     * 只存在于本地时间线与刷新快照，禁止当业务消息落库、禁止从 agent_run_event 回放。
     */
    | 'ROUTER_TRACE'
  text: string
  /** 工具结果。和调用行相同或为空时不展示 */
  detail?: string
  /** plan 块的结构化视图；正文另外存一份 JSON 供回放 */
  plan?: TrialPlanView
  /** step 块的单步视图 */
  step?: TrialStepView
  /** file 块的产物视图；下载卡片据此渲染 */
  file?: TrialFileView
}

/**
 * Agent 产出的可下载文件。
 * fileName 是相对工作区根的名字（可带子目录），与后端 GeneratedFileMarker 的字段一致。
 */
export interface TrialFileView {
  fileName: string
  /** 字节数；< 0 表示服务端取不到体积，展示为「大小未知」 */
  size: number
  /** 下载地址。后端拼好，前端不再自己编码文件名 */
  downloadUrl: string
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
  /**
   * 块 id 的自增序号（前端态）。每分配一块 +1，随快照持久化。
   *
   * <p>为什么不放到模块级全局计数器：刷新恢复、切换会话、同页多轮并存时，
   * 只有「每轮各记各的」才能保证 id 不撞、也不因别的会话推进而跳号。
   */
  blockSeq?: number
}

/**
 * 对话流内 Router 编排卡的数据。
 * 来源是 obs span 树（只读），不是试跑业务消息；不写库、不回放进 agent_run_event。
 */
export interface RouterTraceView {
  /** 泳道模型；null 表示本轮追踪数据还没出现 */
  model: OrchestrationLaneModel | null
  /** 中文事件列表（与泳道联动高亮）；无数据时为空数组 */
  events: OrchestrationLaneEvent[]
  /** 原始步骤：仅用于包络修正父区间，不参与泳道行 */
  spans: ObsSpan[]
  /** 最近一轮 trace 主键；用于「完整链路」 */
  traceId: string
  /** 试跑会话主键；链路抽屉的兜底展示 */
  sessionId: number | null
  /** 加载失败说明；空串表示正常 */
  error: string
  /** 是否正在加载 */
  loading: boolean
}
