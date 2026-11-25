/**
 * 试跑接口。所属域：runtime。
 * 前端超时只做 UX；硬拦在后端 agenthub.runtime.limits.timeout-ms。
 */
import { asParticipantChunk } from './trialParticipantFrame'
import request, { TOKEN_KEY } from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  TrialAgent,
  TrialEvent,
  TrialMessageResult,
  TrialPlanView,
  TrialPreprocessView,
  TrialSession
} from '@/types/runtime'

/** 启用中的 Agent，给下拉用。带 visionEnabled 供上传按钮联动。 */
export function listTrialAgents() {
  return request.get<Result<TrialAgent[]>>('/api/runtime/trial/agents')
}

/**
 * 单个 Agent 的试跑能力。抽屉按 agentId 直接打开时用它判断上传按钮是否可用。
 * @param agentId Agent 主键
 */
export function getTrialCapability(agentId: number) {
  return request.get<Result<TrialAgent>>(`/api/runtime/trial/agents/${agentId}/capability`)
}

/**
 * 历史会话（真分页）。服务端只返回当前用户在该 Agent 或该方案下的当前页。
 *
 * <p>agentId / maId 由服务端强制二选一：绑根与虚拟根的会话空间不混在一起。
 * size 是<b>页大小</b>（上限 100），不再是总量硬顶；total 为同过滤条件的全量条数，
 * 左栏据此判断还有没有下一页。
 */
export function pageTrialSessions(params: { current: number; size: number; agentId?: number; maId?: number }) {
  return request.get<Result<PageResult<TrialSession>>>('/api/runtime/trial/sessions', { params })
}

/** 会话元数据。 */
export function getTrialSession(id: number) {
  return request.get<Result<TrialSession>>(`/api/runtime/trial/sessions/${id}`)
}

/** 事件时间线。 */
export function listTrialEvents(id: number) {
  return request.get<Result<TrialEvent[]>>(`/api/runtime/trial/sessions/${id}/events`)
}

/**
 * 进行中一轮的实时快照。
 * 刷新页面后前端已失去 SSE，events 又要等整轮落库；这条接口从服务端内存读增量，
 * 用于恢复「边跑边看」。没有在跑的一轮时返回空数组，调用方应回退到 events。
 */
export function listTrialLiveEvents(id: number) {
  return request.get<Result<TrialEvent[]>>(`/api/runtime/trial/sessions/${id}/live-events`)
}

/** 按当前 Agent 新建会话。 */
export function createTrialSession(agentId: number) {
  return request.post<Result<TrialSession>>('/api/runtime/trial/sessions', { agentId })
}

/**
 * 从编排方案新建会话（虚拟根试跑）。
 *
 * <p>为什么不复用 createTrialSession：虚拟根没有 agent_def 行，服务端要求 agentId 必填，
 * 方案入口必须单独带 maId，且由服务端决定是否允许（绑根方案会被拒，仍走 Agent 入口）。
 */
export function createMaTrialSession(maId: number) {
  return request.post<Result<TrialSession>>('/api/runtime/trial/sessions/ma', { maId })
}

/** 同步跑一轮。超时略大于平台墙钟硬顶（300s）。页面发送走 SSE，这条留给需要整包结果的调用。 */
export function sendTrialMessage(
  id: number,
  content: string,
  images?: string[],
  idempotencyKey?: string
) {
  return request.post<Result<TrialMessageResult>>(`/api/runtime/trial/sessions/${id}/messages`, { content, images }, {
    timeout: 310000,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
  })
}

/**
 * 生成一次发消息的业务幂等键。
 *
 * <p>为什么在发送前生成、并在失败重试时复用：服务端按「会话 + 键 + 内容指纹」去重，
 * 同一轮重复提交（连点、网关重试、SSE 重连）只会真正跑一次模型、只写一条用户消息与一条计费流水。
 * 优先用 `crypto.randomUUID()`；老浏览器没有时退化为时间戳 + 随机串（仍满足服务端 8~64 位字母数字键格式）。
 */
export function newTrialIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }
  return `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}

/** 改左栏标题。服务端写入 session_title 并把来源标为 MANUAL（此后自动生成不再覆盖）。 */
export function renameTrialSession(id: number, title: string) {
  return request.post<Result<TrialSession>>(`/api/runtime/trial/sessions/${id}/title`, { title })
}

/**
 * 清空当前会话已落库事件，保留会话和标题。
 * 不用 cancel：cancel 会把会话标成不可再发。
 */
export function clearTrialSession(id: number) {
  return request.post<Result<TrialSession>>(`/api/runtime/trial/sessions/${id}/clear`)
}

/** 逻辑删除。列表不再返回。 */
export function deleteTrialSession(id: number) {
  return request.delete<Result<void>>(`/api/runtime/trial/sessions/${id}`)
}

export interface TrialToolChunk {
  line: string
  result?: string
  success?: boolean
  /** 工具名：前端据此把「正在调用」胶囊与结果配对 */
  name?: string
}

export interface TrialDoneChunk {
  status?: string
  sessionId?: number
  errorSummary?: string
  /** 计划执行模式：本轮停在待批准，没有助手正文 */
  planPending?: boolean
}

/**
 * 计划执行模式的 SSE 帧。plan 与 step 的 data 都是对象，
 * 形状与 PLAN / STEP 事件正文一致，前端复用同一套解析。
 */
export interface TrialPlanChunk {
  planId: string
  state: string
  summary?: string
  steps?: { seq: number; title: string; detail: string }[]
}

export interface TrialStepChunk {
  seq: number
  title: string
  state: string
  text?: string
  line?: string
}

/**
 * 编排根下的子专家计划待批准帧（事件名 ma_child_plan_pending）。
 * 载荷与刷新恢复接口 plan/child-pending 同构：前端一套解析两处复用。
 */
export interface TrialChildPlanPendingChunk {
  /** 该子在父编排里的路由键；批准/拒绝时回传 */
  routeKey: string
  childAgentId?: number
  childAgentName?: string
  planId: string
  /** 当前只有 PENDING */
  state: string
  /** 根侧运行态：PAUSED_CHILD_APPROVAL */
  runState?: string
  summary?: string
  steps?: { seq: number; title: string; detail: string }[]
}

/**
 * 文件生成 SSE 帧。
 * fileName 含子目录时形如 notes/report.md；downloadUrl 由后端拼好（已编码）。
 */
export interface TrialFileChunk {
  fileName: string
  /** 字节数；< 0 表示体积未知 */
  size: number
  downloadUrl: string
  /** 写出该文件的参与者 id；旧后端未下发时为空 */
  participantId?: string
  /** 写出该文件的参与者展示名；与 participantId 配套 */
  participantName?: string
}

/**
 * 结构化选项 SSE 帧（事件名 `choice`）。
 *
 * <p>与落库 CHOICE 事件正文、后端选项工具参数同形；前端只按它渲染 chips，
 * 正文里不再出现机器块，也不再对自然语言列表做启发式猜测。
 */
export interface TrialChoiceChunk {
  /** 一句话问题；可能为空串 */
  question: string
  /** 2~6 个选项，顺序即展示顺序；回复态可为空（选项已清掉） */
  options: { id: string; label: string }[]
  /** 用户已答复的展示文案；未答复为空 */
  reply?: string
  /** true 表示方案正停在这位子专家处等用户 */
  waiting?: boolean
}

/**
 * 参与者身份帧（SSE 事件名 `participant`）。
 *
 * <p>与后端 `ParticipantJson` 字段一一对应；`begin` 为 true 表示这一步真正开始。
 */
export interface TrialParticipantChunk {
  /** 参与者标识（同轮唯一，可复现） */
  id: string
  /** 展示名（读配置，不由前端拼） */
  name: string
  /** gateway / scheme / agent / workflow / conclusion */
  kind: string
  /** 该参与者在候选池里的 route_key；可空 */
  routeKey?: string
  /** 嵌套层级；入口为 0 */
  depth?: number
  /** true＝该步开始（建块）；false＝仅切换归属 */
  begin: boolean
  /**
   * 参与者终态：done / failed / aborted。
   *
   * <p>只有「这一步结束了」才带这个字段；它总是 `begin=false`，只收口已有块、不新建块。
   * 失败 / 中止会配合 {@link note} 说明原因，前端写进该参与者的内容框。
   */
  state?: string
  /** 终态说明（已脱敏）；done 且正文已流出时为空 */
  note?: string
}

/**
 * 工作流执行卡片 SSE 帧（事件名 `workflow`）。
 *
 * <p>与落库 `WORKFLOW` 事件正文、内存快照同形：字段是前后端契约，前端只渲染不改写。
 * 这一帧是**整份状态快照**（不是增量），同一轮重复到达时原位覆盖。
 */
export interface TrialWorkflowChunk {
  /** 工作流展示名（配置名） */
  name?: string
  /** 工作流编码 */
  code?: string
  /** CREATED / RUNNING / PAUSED_HUMAN / WAITING_REPLY / SUCCESS / FAILED / CANCELLED */
  status?: string
  /** 终答文本；运行中为空 */
  answer?: string
  /** 真实节点（已剔除 START / END） */
  nodes?: {
    code?: string
    name?: string
    type?: string
    status?: string
    note?: string
  }[]
}

/** SSE 回调。正文按事件追加，不在这里编造思考。 */
export interface TrialStreamHandlers {
  onPhase?: (phase: string) => void
  /** Router 判定摘要；进思考过程时间线 */
  onRoute?: (summary: string) => void
  /**
   * 参与者身份帧（`participant`）。
   *
   * <p>`begin=true` 表示该步真正开始——前端据此立刻建块，**先于**该步任何思考 / 工具；
   * `begin=false` 只切换「后续增量属于谁」，不新建块。
   */
  onParticipant?: (participant: TrialParticipantChunk) => void
  onReasoning?: (text: string) => void
  onTool?: (tool: TrialToolChunk) => void
  /**
   * 工具**开始**执行（结果还没回来）。
   *
   * <p>用于把「正在调用 xxx…」显示成蓝色胶囊；随后到达的 {@link #onTool} 会把同一条补成
   * 成功 / 失败态。后端可能不发这条（历史流 / 不支持进度的实现），此时前端不画 running 胶囊。
   */
  onToolStart?: (name: string) => void
  onAssistant?: (text: string) => void
  /** 图片文字提取结果，整段下发一次；进时间线的「图片文字」块 */
  onVision?: (text: string) => void
  /** 入口预处理步骤；进行中 / 完成 / 失败原位更新同一块 */
  onPreprocess?: (view: TrialPreprocessView) => void
  /** 计划生成或状态推进（结构化） */
  onPlan?: (plan: TrialPlanChunk) => void
  /** 计划单步进度 */
  onStep?: (step: TrialStepChunk) => void
  /** 编排根下某个 P&E 子计划待批准：根侧同时仅一个，面板据此出「批准 / 拒绝」 */
  onChildPlanPending?: (pending: TrialChildPlanPendingChunk) => void
  /** Agent 在工作区生成了文件，可下载 */
  onFileGenerated?: (file: TrialFileChunk) => void
  /**
   * 本轮需要用户拍板的结构化选项。
   *
   * <p>它总是晚于助手正文到达（模型先说完、后端再收口），因此前端只需在收到时
   * 给当前轮补一条选项块；没有这条帧就说明本轮不出 chips，不猜、不补空栏。
   */
  onChoice?: (choice: TrialChoiceChunk) => void
  /**
   * 工作流执行卡片状态（`workflow`）。
   *
   * <p>整份快照下发：运行中推「按图铺好的时间轴」，完成后推终态（含终答）。
   * 前端把它当成工作流那一块的唯一可见载体——工作流本身没有 token 输出。
   */
  onWorkflow?: (workflow: TrialWorkflowChunk) => void
  onDone?: (payload: TrialDoneChunk) => void
  onError?: (message: string) => void
}

/**
 * 边跑边收 SSE。前端超时只 UX；硬拦在后端 limits.timeout-ms。
 * @param id 会话
 * @param content 用户输入
 * @param handlers 增量
 * @param signal 关闭抽屉时可中止读取
 * @param images 图片 data URI 数组，可空。与 content 一起提交；后端先做 OCR 再喂主模型
 * @param idempotencyKey 业务幂等键，可空。带上后同会话同键重复提交只真正跑一次
 * @param files 非图片文件，可空。不进视觉模型
 * @param attachmentOrder 图和文件的添加顺序，仅在有文件时需要
 */
export async function streamTrialMessage(
  id: number,
  content: string,
  handlers: TrialStreamHandlers,
  signal?: AbortSignal,
  images?: string[],
  idempotencyKey?: string,
  files?: { name: string; dataUrl: string }[],
  attachmentOrder?: Array<'image' | 'file'>
) {
  return streamSessionMessage(
    '/api/runtime/trial',
    id,
    content,
    handlers,
    signal,
    images,
    idempotencyKey,
    files,
    attachmentOrder
  )
}

/**
 * 按会话边跑边收 SSE 的通用实现。所属域：runtime（前端）。
 *
 * <p>试跑与工作台只是路径前缀不同，帧协议、正文合批、鉴权与 401 处理完全一致；
 * 抽出来避免第二份解析器（两套解析器一旦分叉，计划卡 / 子计划待批就会出现行为差异）。
 *
 * @param prefix         接口前缀，如 `/api/runtime/trial` 或 `/api/runtime/workbench`
 * @param id             会话主键
 * @param content        用户输入
 * @param handlers       增量回调
 * @param signal         中止信号，可空
 * @param images         图片 data URI 数组，可空
 * @param idempotencyKey 业务幂等键，可空
 * @param files          非图片文件，可空。不传这个键时后端不落文件
 * @param attachmentOrder 添加顺序，仅在有文件时带上
 */
export async function streamSessionMessage(
  prefix: string,
  id: number,
  content: string,
  handlers: TrialStreamHandlers,
  signal?: AbortSignal,
  images?: string[],
  idempotencyKey?: string,
  files?: { name: string; dataUrl: string }[],
  attachmentOrder?: Array<'image' | 'file'>
) {
  // 图片、文件都按需出现：空数组不带键，避免纯文字被误判成带附件
  const body: Record<string, unknown> = { content }
  if (images && images.length) {
    body.images = images
  }
  if (files && files.length) {
    body.files = files.map((file) => ({ name: file.name, dataUrl: file.dataUrl }))
    if (attachmentOrder && attachmentOrder.length) {
      body.attachmentOrder = attachmentOrder
    }
  }
  return streamSessionEndpoint(
    `${prefix}/sessions/${id}/messages/stream`,
    body,
    handlers,
    signal,
    idempotencyKey
  )
}

/**
 * 点选项后的**断点续作**（SSE）。所属域：runtime（前端）。
 *
 * <p>为什么不复用 {@link streamSessionMessage} 发一条普通消息：那样会重新进入口判词，
 * 选项可能被分流到别的 Agent 身上，续作就断线了。这个端点由后端直接派回同一位 Agent，
 * 前端只负责把 optionId 原样带上、帧协议照旧解析。
 *
 * <p>平台追加的「其它，我自己说」没有模型 optionId：此时把用户写下的原文放进
 * {@code text}，由后端按「本次答复」回到同一位专家；不带 {@code text} 的调用与改前完全一致。
 *
 * @param prefix  接口前缀，如 `/api/runtime/workbench`
 * @param id      会话主键
 * @param optionId 被点选项的短标识（后端会对照最后一条助手消息上的选项块校验）；自填时可为空串
 * @param handlers 增量回调，与普通发送完全一致
 * @param signal   中止信号，可空
 * @param text     自填答复原文；普通点选不传
 */
export async function streamSessionChoice(
  prefix: string,
  id: number,
  optionId: string,
  handlers: TrialStreamHandlers,
  signal?: AbortSignal,
  text?: string
) {
  // text 只在实际自填时进请求体：普通点选保持原载荷，避免后端把选项文案误当自填答复
  const payload = text ? { optionId, text } : { optionId }
  return streamSessionEndpoint(`${prefix}/sessions/${id}/choice/stream`, payload, handlers, signal)
}

/**
 * 会话类 SSE 端点的通用实现：POST 一个 JSON 体，按帧回调增量。
 *
 * <p>抽成一份的原因：发送消息与断点续作只是路径与请求体不同，帧协议、正文合批、
 * 鉴权与 401 处理必须完全一致；两套解析器一旦分叉，选项续作就会出现「字对不上」的行为差异。
 *
 * @param path           以 `/` 开头的接口路径（不含 API base）
 * @param payload        请求体
 * @param handlers       增量回调
 * @param signal         中止信号，可空
 * @param idempotencyKey 业务幂等键，可空；只有发送消息会带
 */
async function streamSessionEndpoint(
  path: string,
  payload: unknown,
  handlers: TrialStreamHandlers,
  signal?: AbortSignal,
  idempotencyKey?: string
) {
  const token = localStorage.getItem(TOKEN_KEY)
  const base = import.meta.env.VITE_API_BASE || ''
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload),
    signal
  })
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    if (!location.pathname.endsWith('/login')) {
      location.href = '/login'
    }
    throw new Error('未登录')
  }
  if (!response.ok || !response.body) {
    let message = '发送失败'
    try {
      const body = (await response.json()) as { msg?: string }
      if (body?.msg) {
        message = body.msg
      }
    } catch {
      // 非 JSON 时用默认文案
    }
    throw new Error(message)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  // 正文增量按帧合批：同帧内多个 delta 只写一次响应式，渲染次数从「chunk 率」降到「帧率」
  const batcher = createTextBatcher(handlers)
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      buffer = takeFrames(buffer, handlers, batcher)
    }
    if (buffer.trim()) {
      takeFrames(`${buffer}\n\n`, handlers, batcher)
    }
  } finally {
    // 正常结束、异常、中止都要把缓冲里的正文落地，否则最后半帧的字会丢
    batcher.flush()
  }
}

/** 正文增量事件类型：只有这两种是「可以合批的纯文本」 */
type TrialTextKind = 'reasoning' | 'assistant'

/**
 * 正文增量合批器。
 *
 * <p>为什么合批：模型 chunk 有 20~60/s，每个 delta 直接写响应式会让整棵对话树按 chunk 率重渲；
 * 合成到一帧后每次写入都会带上本帧全部新字，渲染次数降到帧率级，且不改变最终文本。
 *
 * <p>顺序保证：用**单条有序队列**按到达顺序堆放，只把「连续同类型」的片段拼起来
 * （例如一串 reasoning 后接一串 assistant 就产生两段）。不能按类型各开一个缓冲再分别回放，
 * 那样 reasoning 与 assistant 交替到达时会把块顺序打乱。
 *
 * <p>不丢字：任何非文本事件（tool / plan / step / done / error 等）派发前先 flush，
 * 流结束在 finally 里再 flush 一次；后台标签页 rAF 被暂停时直接同步落地。
 */
interface TrialTextBatcher {
  /** 收下一个 delta；同类型连续片段会被合并 */
  push: (kind: TrialTextKind, text: string) => void
  /** 立刻把缓冲按顺序交付给 handlers，并清掉已排队的帧 */
  flush: () => void
}

/**
 * 创建正文合批器。
 *
 * @param handlers 下游回调；这里只负责延后交付，不改任何语义
 * @return 合批器句柄
 */
function createTextBatcher(handlers: TrialStreamHandlers): TrialTextBatcher {
  /** 有序队列：元素是「连续的同类正文」，同帧同类型只产生一段 */
  const queue: { kind: TrialTextKind; text: string }[] = []
  let frame = 0

  /** 按到达顺序把队列交付出去；交付中即使新增也只会在下一轮 */
  const deliver = () => {
    if (!queue.length) {
      return
    }
    const pending = queue.splice(0, queue.length)
    for (const item of pending) {
      if (item.kind === 'assistant') {
        handlers.onAssistant?.(item.text)
      } else {
        handlers.onReasoning?.(item.text)
      }
    }
  }

  /** 下一帧交付；环境没有 rAF（或标签页在后台）时同步交付，避免正文长时间停在缓冲里 */
  const schedule = () => {
    if (frame) {
      return
    }
    if (typeof requestAnimationFrame !== 'function' || document.hidden) {
      deliver()
      return
    }
    frame = requestAnimationFrame(() => {
      frame = 0
      deliver()
    })
  }

  return {
    push(kind, text) {
      if (!text) {
        return
      }
      const last = queue[queue.length - 1]
      // 连续同类型才合并：中间夹了别的类型就必须另起一段，否则块顺序会错
      if (last && last.kind === kind) {
        last.text += text
      } else {
        queue.push({ kind, text })
      }
      schedule()
    },
    flush() {
      if (frame) {
        cancelAnimationFrame(frame)
        frame = 0
      }
      deliver()
    }
  }
}

/** 拆完整的 SSE 帧，最后一段可能还没结束。 */
function takeFrames(buffer: string, handlers: TrialStreamHandlers, batcher?: TrialTextBatcher) {
  const parts = buffer.split('\n\n')
  const rest = parts.pop() ?? ''
  for (const frame of parts) {
    dispatchFrame(frame, handlers, batcher)
  }
  return rest
}

/**
 * 派发单帧。
 *
 * @param frame   完整 SSE 帧
 * @param handlers 增量回调
 * @param batcher 正文合批器；正文事件进缓冲，其它事件先 flush 再派发以保证顺序
 */
function dispatchFrame(frame: string, handlers: TrialStreamHandlers, batcher?: TrialTextBatcher) {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }
  if (!dataLines.length) {
    return
  }
  const raw = dataLines.join('\n')
  let data: unknown = raw
  try {
    data = JSON.parse(raw)
  } catch {
    data = raw
  }
  // 正文增量：进合批缓冲，本帧不立刻写响应式；其余事件一律先 flush 再派发，保证与正文的相对顺序
  if (batcher) {
    if (event === 'reasoning' && typeof data === 'string') {
      batcher.push('reasoning', data)
      return
    }
    if (event === 'assistant' && typeof data === 'string') {
      batcher.push('assistant', data)
      return
    }
    batcher.flush()
  }

  if (event === 'phase' && typeof data === 'string') {
    handlers.onPhase?.(data)
  } else if (event === 'route' && typeof data === 'string') {
    handlers.onRoute?.(data)
  } else if (event === 'participant') {
    // 只认一次解析后的对象。字符串是双重编码，不再解析，begin 不到就不建卡
    const participant = asParticipantChunk(data)
    if (participant) {
      handlers.onParticipant?.(participant)
    }
  } else if (event === 'reasoning' && typeof data === 'string') {
    // 没有合批器时（理论上只有直连测试）保持原样：立即交付
    handlers.onReasoning?.(data)
  } else if (event === 'assistant' && typeof data === 'string') {
    handlers.onAssistant?.(data)
  } else if (event === 'vision' && typeof data === 'string') {
    // 识别结果是整段文本（非增量）：由调用方整块插入时间线
    handlers.onVision?.(data)
  } else if (event === 'preprocess' && data && typeof data === 'object') {
    handlers.onPreprocess?.(data as TrialPreprocessView)
  } else if (event === 'tool' && data && typeof data === 'object') {
    const row = data as TrialToolChunk
    handlers.onTool?.(row)
  } else if (event === 'tool_start' && typeof data === 'string') {
    // 工具开始执行：只带工具名，前端先画「正在调用」胶囊，等 tool 帧补全
    handlers.onToolStart?.(data)
  } else if (event === 'plan' && data && typeof data === 'object') {
    handlers.onPlan?.(data as TrialPlanChunk)
  } else if (event === 'step' && data && typeof data === 'object') {
    handlers.onStep?.(data as TrialStepChunk)
  } else if (event === 'ma_child_plan_pending' && data && typeof data === 'object') {
    // 子计划待批：不并进单 Agent 的 plan 卡，单独交给根试跑面板渲染批准按钮
    handlers.onChildPlanPending?.(data as TrialChildPlanPendingChunk)
  } else if (event === 'file_generated' && data && typeof data === 'object') {
    // 产物卡片：fileName / size / downloadUrl 都由后端给齐，前端不再拼地址
    handlers.onFileGenerated?.(data as TrialFileChunk)
  } else if (event === 'choice' && data && typeof data === 'object') {
    // 结构化选项：整份一次下发（不是增量），前端只渲染，不解析自然语言
    handlers.onChoice?.(data as TrialChoiceChunk)
  } else if (event === 'workflow' && data && typeof data === 'object') {
    // 工作流卡片：整份状态快照，前端按 name/code 原位覆盖同一块
    handlers.onWorkflow?.(data as TrialWorkflowChunk)
  } else if (event === 'done' && data && typeof data === 'object') {
    handlers.onDone?.(data as TrialDoneChunk)
  } else if (event === 'error') {
    handlers.onError?.(typeof data === 'string' ? data : '运行失败')
  }
}

/**
 * 批准待执行计划。后端接着跑剩余步骤并返回本轮事件；
 * 没有待批准计划（已批准、已取消或已过期）时返回业务错误。
 * @param id 会话
 * @param routeKey 编排根下的子路由键；传了表示批准的是「子专家计划」，不传是单 Agent 计划
 */
export function approveTrialPlan(id: number, routeKey?: string) {
  return request.post<Result<TrialMessageResult>>(
    `/api/runtime/trial/sessions/${id}/plan/approve${routeKey ? `?routeKey=${encodeURIComponent(routeKey)}` : ''}`,
    {},
    { timeout: 310000 }
  )
}

/**
 * 取消待执行计划。不执行任何步骤，只落一条「计划已取消」。
 * @param id 会话
 * @param routeKey 编排根下的子路由键；传了表示拒绝的是「子专家计划」
 */
export function rejectTrialPlan(id: number, routeKey?: string) {
  return request.post<Result<TrialMessageResult>>(
    `/api/runtime/trial/sessions/${id}/plan/reject${routeKey ? `?routeKey=${encodeURIComponent(routeKey)}` : ''}`
  )
}

/**
 * 编排根下当前待批的子计划。刷新页面后用它恢复「批准 / 拒绝」按钮；没有时 data 为 null。
 * @param id 会话
 */
export function getPendingChildPlan(id: number) {
  return request.get<Result<TrialChildPlanPendingChunk | null>>(
    `/api/runtime/trial/sessions/${id}/plan/child-pending`
  )
}

/**
 * 待批准计划（单 Agent P&E）。刷新页面后用它恢复「批准 / 取消」按钮；没有时 data 为 null。
 * @param id 会话
 */
export function getPendingTrialPlan(id: number) {
  return request.get<Result<TrialPlanView | null>>(`/api/runtime/trial/sessions/${id}/plan/pending`)
}

/** 取消尚未完成的会话。这一轮还在执行时改为中断运行，会话可继续发送。 */
export function cancelTrialSession(id: number) {
  return request.post<Result<TrialSession>>(`/api/runtime/trial/sessions/${id}/cancel`)
}

/**
 * 下载 Agent 产出的工作区文件。
 *
 * <p>为什么不用 `window.open(downloadUrl)`：本项目 Sa-Token 配置是
 * `is-read-header: true` / `is-read-cookie: false`，下载接口只认 `Authorization` 头。
 * `window.open` 与新开标签的导航都不会带这个头，必然被拦成 401。
 * 因此这里用 fetch 带上 Bearer，取回 blob 后再用临时 `<a download>` 触发保存。
 *
 * <p><b>为什么不能带 `credentials: 'include'`</b>：后端 `CorsConfig` 是
 * `allowCredentials(false)`（与 axios 的 `withCredentials` 默认 false 一致），
 * 响应里只回 `Access-Control-Allow-Origin`，不会回 `Access-Control-Allow-Credentials`。
 * 一旦这里声明携带凭据，浏览器会判定「凭据请求没拿到 ACAC」而**阻断整个响应**，
 * fetch 直接抛 `TypeError: Failed to fetch`——页面上的表现就是「下载报 Failed to fetch」。
 * 登录态只靠请求头里的 Bearer，不存在任何需要携带的 Cookie。
 *
 * <p>文件名优先用服务端 Content-Disposition 里给出的，取不到时退回调用方传入的名字，
 * 保证中文文件名不会退化成一段百分号编码。
 *
 * @param downloadUrl 后端在 file_generated 帧里给出的地址（已含编码后的 fileName）
 * @param fallbackName 兜底文件名，通常是卡片上展示的 fileName
 * @throws Error 未登录、无权限、文件不存在或网络异常
 */
export async function downloadWorkspaceFile(downloadUrl: string, fallbackName?: string) {
  const path = (downloadUrl || '').trim()
  if (!path) {
    throw new Error('该文件没有可用的下载地址')
  }
  const token = localStorage.getItem(TOKEN_KEY)
  const base = import.meta.env.VITE_API_BASE || ''
  // 后端给的是以 /api 开头的绝对路径，拼上 baseURL 才能命中 Vite 代理或生产网关
  const url = /^https?:\/\//i.test(path) ? path : `${base}${path}`
  let response: Response
  try {
    response = await fetch(url, {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })
  } catch (error) {
    /*
     * fetch 只在「请求根本没拿到可读响应」时抛异常：跨域被拦、服务未启动、
     * 页面是 https 而接口是 http（混合内容）都属于这一类。原文案是英文的
     * `Failed to fetch`，对用户毫无指向性，这里换成可执行的提示。
     */
    const raw = error instanceof Error ? error.message : ''
    throw new Error(
      /failed to fetch|networkerror|load failed/i.test(raw)
        ? '下载请求未能到达服务：请确认后端已启动、地址端口一致，且页面与接口同为 http 或同为 https'
        : '下载失败，请稍后重试'
    )
  }
  if (response.status === 401) {
    localStorage.removeItem(TOKEN_KEY)
    if (!location.pathname.endsWith('/login')) {
      location.href = '/login'
    }
    throw new Error('未登录')
  }
  if (!response.ok) {
    let message = '下载失败'
    try {
      // 失败时后端返回的是标准 Result JSON，取出 msg 比抛裸状态码有用
      const body = (await response.json()) as { msg?: string }
      if (body?.msg) {
        message = body.msg
      }
    } catch {
      // 非 JSON（如网关直接返回 HTML）：保留默认文案
    }
    throw new Error(message)
  }
  const blob = await response.blob()
  // 服务端 Content-Disposition 里的文件名更权威（已按 RFC 5987 编码，需 unquote）
  const name = fileNameFromDisposition(response.headers.get('Content-Disposition')) || fallbackName || 'download'
  saveBlob(blob, name)
}

/**
 * 从 Content-Disposition 解析文件名。
 * 优先 `filename*=UTF-8''...`（RFC 5987，中文靠它），其次 `filename="..."`。
 *
 * @param header 响应头值，可空
 * @return 解码后的文件名；解析不到时返回空串
 */
function fileNameFromDisposition(header: string | null): string {
  if (!header) {
    return ''
  }
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // 编码异常时退回普通 filename
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  if (plain?.[1]) {
    const raw = plain[1].trim()
    try {
      return decodeURIComponent(raw)
    } catch {
      return raw
    }
  }
  return ''
}

/**
 * 用临时 `<a download>` 保存 blob。
 * 必须 append 到文档再点击：Firefox 对游离节点不触发下载。
 *
 * @param blob 文件内容
 * @param fileName 保存用的文件名
 */
function saveBlob(blob: Blob, fileName: string) {
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = fileName
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  // 立即 revoke 会让部分浏览器的下载被取消，留一拍再释放
  window.setTimeout(() => URL.revokeObjectURL(href), 1000)
}
