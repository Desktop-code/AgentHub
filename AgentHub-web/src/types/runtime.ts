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
     * 结构化选项块（后端 CHOICE 事件 / SSE `choice` 帧）。
     *
     * <p>它不参与正文拼接：正文里不出现任何 JSON，chips 只按这一块渲染。
     * 老会话里模型写在正文中的 ```agenthub-choice 围栏仍按正文解析，作为历史兼容。
     */
    | 'choice'
    /**
     * 工作流执行卡片块（SSE `workflow` 帧 / 落库 WORKFLOW 事件）。
     *
     * <p>它不是正文：`text` 留空，结构化视图在 {@link AssistantBlock.workflow}。
     * 消息流据此把工作流单独成一块（角标「工作流 WF」），不再拆成多个专家块。
     */
    | 'workflow'
    /**
     * 前端态：Router 试跑的编排调度卡。
     * 只存在于本地时间线与刷新快照，禁止当业务消息落库、禁止从 agent_run_event 回放。
     */
    | 'ROUTER_TRACE'
  text: string
  /** 工具结果。和调用行相同或为空时不展示 */
  detail?: string
  /**
   * 工具块状态（仅 kind='tool' 使用）。
   *
   * <p>`running`：引擎已开始执行、结果还没回来 → 前端画成蓝色「正在调用」胶囊；
   * `done` / `error`：结果已到 → 绿 / 红胶囊，点开看入参出参。
   * 缺省（无此字段）表示历史数据：直接当已完成处理。
   */
  toolState?: 'running' | 'done' | 'error'
  /** 工具名（仅 kind='tool'）：胶囊文案与匹配同一次调用用 */
  toolName?: string
  /** 工具入参展示行（仅 kind='tool'）：点开胶囊时的「入参」段 */
  toolArgsText?: string
  /** plan 块的结构化视图；正文另外存一份 JSON 供回放 */
  plan?: TrialPlanView
  /** step 块的单步视图 */
  step?: TrialStepView
  /** file 块的产物视图；下载卡片据此渲染 */
  file?: TrialFileView
  /**
   * choice 块的结构化选项；`text` 是一句话问题。
   *
   * <p>与后端 CHOICE 事件正文、SSE `choice` 帧字段完全一致，前端只做渲染，
   * 不再从自然语言里猜列表（猜法一轮一轮对不上，已经下线）。
   */
  choice?: TrialChoiceView
  /**
   * 工作流执行卡片的实时 / 终态视图（仅 kind='workflow'）。
   *
   * <p>同一轮收到多次帧时**原位覆盖**：卡片是状态快照不是增量，
   * 追加会让刷新时看到一串互相矛盾的旧状态。
   */
  workflow?: TrialWorkflowView
  /**
   * 该块属于哪一位参与者（SSE `participant` 身份帧给的 id）。
   *
   * <p>为什么必须带上：并行 / 交错时「最后一条 route 文案」不能可靠代表当前说话人；
   * 带着 id，时间线可以按身份归块，而不是靠文案猜归属。历史数据没有该字段，走旧口径。
   */
  participantId?: string
  /** 该块的参与者展示名（与 participantId 同时写入，便于正文 / 文件 / 报错归属） */
  participantName?: string
  /**
   * true＝这条 route 块只是「这一步开始」时补的占位行（只有名字与 route_key，没有依据）。
   *
   * <p>真实路由行随后到达时**原位补全**这条块，不另起一条，避免同一参与者出现两块。
   */
  begin?: boolean
}

/**
 * 一个可点选项。id 由后端给出（工具参数 / 整理结果），label 是服务端权威展示文案。
 */
export interface TrialChoiceOption {
  /** 稳定短标识：点击时回传后端定位（label 以服务端为准，不信客户端） */
  id: string
  /** 展示文案 */
  label: string
}

/**
 * 需要用户拍板的结构化选项。
 *
 * <p>三条来源同形（工具调用 / 后端整理 / SSE 与落库事件），前端只有一套渲染：
 * 一句话问题 + 2~6 个选项，渲染成 chips，正文里不出现裸 JSON。
 */
export interface TrialChoiceView {
  /** 一句话问题；模型没给时为空串（只出 chips，不硬凑问句） */
  question: string
  options: TrialChoiceOption[]
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

/**
 * 工作流执行卡片里的一个真实节点。
 *
 * <p>START / END 在投影时已剔除：它们是边界标记，不是用户需要看到的「一步」。
 * 字段与后端 `WorkflowRunView.Node` 一一对应，前端不补造状态。
 */
export interface TrialWorkflowNodeView {
  /** 节点编码（业务唯一）；可能为空 */
  code: string
  /** 节点展示名（取配置名；后端已按编码兜底） */
  name: string
  /** AGENT / TOOL / CONDITION / HUMAN / PARALLEL_FORK / PARALLEL_JOIN */
  type: string
  /** PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / WAITING_HUMAN / CANCELLED */
  status: string
  /** 一句状态说明（执行中 / 已完成 / 失败原因 / 等待前置节点）；后端已脱敏 */
  note?: string
}

/**
 * 工作流执行卡片视图。
 *
 * <p>来源只有现网真实运行事件（SSE `workflow` 帧 / 落库 WORKFLOW 事件），
 * 前端**不推算进度**：没有事件就没有节点，卡片也不会自己往前走。
 */
export interface TrialWorkflowView {
  /** 工作流展示名（配置名；可能为空，前端回落到编码） */
  name: string
  /** 工作流编码 */
  code: string
  /** CREATED / RUNNING / PAUSED_HUMAN / SUCCESS / FAILED / CANCELLED */
  status: string
  /** 终答文本；运行中为空。没有文件时产物区展示这段文本 */
  answer?: string
  /** 按图顺序的真实节点；可能为空表（事件还没到） */
  nodes: TrialWorkflowNodeView[]
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
   * 入口参与者的身份 id（SSE `participant` 帧 kind=gateway 时给的 `gw-…`）。
   *
   * <p>为什么记在轮上：入口判定模型的推理是入口块（网关块）的思考，
   * 并行 / 嵌套时它可能被决策行、说明行挡在中间。投影层按这个 id 把它找回来，
   * 避免「入口只有判定句、没有思考框」。历史回放没有该字段，走位置口径。
   */
  entryParticipantId?: string
  /**
   * 本轮是「点选项续作」，由同一位专家直接接着跑，**不走入口判定**。
   *
   * <p>为什么记在轮上：投影层要据此决定「先不出块、等入口报身份」这条拦截是否适用。
   * 续作轮本来就没有入口判定，若也去等身份，专家自己的思考会被误判成入口判定，
   * 或者在等不到身份时整块不画。历史回放没有该字段，按新问题（期待入口）处理。
   */
  continuation?: boolean
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
