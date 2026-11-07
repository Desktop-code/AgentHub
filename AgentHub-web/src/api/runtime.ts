/**
 * 试跑接口。所属域：runtime。
 * 前端超时只做 UX；硬拦在后端 agenthub.runtime.limits.timeout-ms。
 */
import request, { TOKEN_KEY } from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  TrialAgent,
  TrialEvent,
  TrialMessageResult,
  TrialPlanView,
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
export function sendTrialMessage(id: number, content: string, images?: string[]) {
  return request.post<Result<TrialMessageResult>>(`/api/runtime/trial/sessions/${id}/messages`, { content, images }, {
    timeout: 310000
  })
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
}

/** SSE 回调。正文按事件追加，不在这里编造思考。 */
export interface TrialStreamHandlers {
  onPhase?: (phase: string) => void
  /** Router 判定摘要；进思考过程时间线 */
  onRoute?: (summary: string) => void
  onReasoning?: (text: string) => void
  onTool?: (tool: TrialToolChunk) => void
  onAssistant?: (text: string) => void
  /** 图片文字提取结果，整段下发一次；进时间线的「图片文字」块 */
  onVision?: (text: string) => void
  /** 计划生成或状态推进（结构化） */
  onPlan?: (plan: TrialPlanChunk) => void
  /** 计划单步进度 */
  onStep?: (step: TrialStepChunk) => void
  /** 编排根下某个 P&E 子计划待批准：根侧同时仅一个，面板据此出「批准 / 拒绝」 */
  onChildPlanPending?: (pending: TrialChildPlanPendingChunk) => void
  /** Agent 在工作区生成了文件，可下载 */
  onFileGenerated?: (file: TrialFileChunk) => void
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
 */
export async function streamTrialMessage(
  id: number,
  content: string,
  handlers: TrialStreamHandlers,
  signal?: AbortSignal,
  images?: string[]
) {
  const token = localStorage.getItem(TOKEN_KEY)
  const base = import.meta.env.VITE_API_BASE || ''
  const response = await fetch(`${base}/api/runtime/trial/sessions/${id}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    // images 不传时不带这个键：后端据此完全不走 OCR 分支
    body: JSON.stringify(images && images.length ? { content, images } : { content }),
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
  } else if (event === 'reasoning' && typeof data === 'string') {
    // 没有合批器时（理论上只有直连测试）保持原样：立即交付
    handlers.onReasoning?.(data)
  } else if (event === 'assistant' && typeof data === 'string') {
    handlers.onAssistant?.(data)
  } else if (event === 'vision' && typeof data === 'string') {
    // 识别结果是整段文本（非增量）：由调用方整块插入时间线
    handlers.onVision?.(data)
  } else if (event === 'tool' && data && typeof data === 'object') {
    const row = data as TrialToolChunk
    handlers.onTool?.(row)
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

/** 取消尚未完成的会话。执行中的一轮不会被打断。 */
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
 * <p>文件名优先用服务端 Content-Disposition 里给出的，取不到时退回调用方传入的名字，
 * 保证中文文件名不会退化成一段百分号编码。
 *
 * @param downloadUrl 后端在 file_generated 帧里给出的地址（已含编码后的 fileName）
 * @param fallbackName 兜底文件名，通常是卡片上展示的 fileName
 * @throws Error 未登录、无权限、文件不存在或网络异常
 */
export async function downloadWorkspaceFile(downloadUrl: string, fallbackName?: string) {
  const token = localStorage.getItem(TOKEN_KEY)
  const base = import.meta.env.VITE_API_BASE || ''
  // 后端给的是以 /api 开头的绝对路径，拼上 baseURL 才能命中 Vite 代理或生产网关
  const url = /^https?:\/\//i.test(downloadUrl) ? downloadUrl : `${base}${downloadUrl}`
  const response = await fetch(url, {
    method: 'GET',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    // 下载可能跨域代理，带上凭据与后端 CORS 白名单保持一致
    credentials: 'include'
  })
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
