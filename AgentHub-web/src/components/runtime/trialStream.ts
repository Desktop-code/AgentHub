/**
 * 试跑单轮流式的模块级持有者。所属域：runtime（前端）。
 *
 * 为什么要有这一层：后端的 reasoning / assistant / tool 增量只走 SSE 出站，
 * 不写入 agent_run_event；事件是整轮跑完后由 TrialRunAppService 批量落库。
 * 所以关掉面板再重进时，靠轮询 events 在结构上只能拿到「整轮结果」，
 * 表现为「只有思考中、结束才一次性刷出」。
 *
 * 分三个层次，覆盖三种打断：
 * 1. 关面板（组件卸载、页面还在）：{@link streams} 里存着本轮的响应式时间线，
 *    SSE 读流不中断，重开直接接上同一份数据 —— 真渐进。
 * 2. 刷新页面：JS 全部重来，SSE 连接随之消失。用 sessionStorage 快照
 *    保住刷新前已收到的内容，先立刻显示，再轮询 events 等整轮落库接管。
 *    快照不压在增量回调里：流式期只登记「有变化」，序列化与写盘推迟到空闲帧
 *    （见 {@link saveSnapshot}），页面卸载前再同步补写一次。
 * 3. 换标签页 / 换设备：什么都不共享，只能等 events 落库（后端不落中间增量，前端无法弥补）。
 *
 * 约束：存的是 ChatTurn 的响应式代理，禁止深拷贝（拷贝会断开 SSE 回调的写入目标）。
 */
import { ref } from 'vue'
import type { ChatTurn } from '@/types/runtime'
import { ensureBlockIds, snapshotSignature } from '@/components/runtime/trialTimeline'

/** 一轮进行中（或刚结束、等落库事件接管）的流式状态。 */
export interface LiveTurn {
  sessionId: number
  /** 本轮完整时间线（含此前历史）。与组件的 turns 是同一个响应式数组 */
  turns: ChatTurn[]
  /** 本轮助手卡，必须是 turns 里的响应式代理，直接改它才驱动界面 */
  live: ChatTurn
  /** 本轮等待起点，用于「已等待 Ns」 */
  startTime: number
  /** 是否已收尾。收尾后内容仍可读，但不再增长，下次打开应改用落库事件回放 */
  finished: boolean
}

/** 会话 → 本轮流式。普通 Map：值里只有响应式数组与卡片，不依赖 Map 自身触发更新。 */
const streams = new Map<number, LiveTurn>()

/**
 * 仍在执行中的会话集合，是左栏转圈的唯一依据。
 * 不能读 agent_run_session.status：试跑会话多轮期间恒为 RUNNING，拿它判断会让历史行全在转。
 * 放模块级而非组件内，才能「切走再切回」「关面板再开」都不丢。
 */
const running = ref<Set<number>>(new Set())

/** 最多保留几轮流式状态，超出淘汰已收尾的最旧项。 */
const MAX_LIVE = 8
/**
 * 快照写盘的最小间隔。
 * <p>真正落盘在空闲帧做（见 {@link scheduleFlush}），这里只做「别写太勤」的兜底：
 * 会话越长，单次 stringify 越贵，即便不阻塞渲染也没必要每秒重复写多遍。
 */
const SNAPSHOT_THROTTLE_MS = 1200
/** 空闲回调兜底超时：主线程持续忙时也不能无限期拖着不落盘 */
const SNAPSHOT_IDLE_TIMEOUT_MS = 800
/** 快照键前缀。只存会话主键与时间线，不含密钥：正文与思考在入库前已脱敏。 */
const SNAPSHOT_PREFIX = 'agenthub_trial_live_'

/** 各会话上次写快照的时间戳，用于节流。 */
const lastSavedTime = new Map<number, number>()
/** 各会话已落盘内容的浅签名：签名没变就不再重复 stringify（见 {@link snapshotSignature}）。 */
const savedSignature = new Map<number, string>()
/** 有变化但还没落盘的会话；序列化统一推迟到空闲帧，避免压在 SSE 增量/渲染帧上。 */
const pendingSessions = new Set<number>()
/** 已排队的写盘回调，同一时刻只排一个；0 也视为有效句柄，故用 null 表示「没排队」 */
let flushHandle: number | null = null
/** pagehide 兜底是否已注册 */
let pageHideBound = false

/**
 * 登记一轮新的流式。必须在组件把「用户气泡 + 助手卡」并入 turns 之后调用。
 * @param sessionId 落库会话主键
 * @param turns     组件当前的响应式时间线数组（末尾必须是本轮助手卡）
 * @return 登记项；末尾不是助手卡时返回 undefined（调用方逻辑本不该发生）
 */
export function beginLive(sessionId: number, turns: ChatTurn[]): LiveTurn | undefined {
  // 前端临时「新对话」用负 id：既没落库也不该有转圈/快照，直接不登记
  if (sessionId == null || sessionId < 0) {
    return undefined
  }
  const live = turns[turns.length - 1]
  if (!live || live.role !== 'assistant') {
    return undefined
  }
  const entry: LiveTurn = {
    sessionId,
    turns,
    live,
    startTime: live.startTime || Date.now(),
    finished: false
  }
  streams.set(sessionId, entry)
  markTurnRunning(sessionId, true)
  prune()
  return entry
}

/**
 * 取该会话本页正在消费的流式状态。
 * @param sessionId 会话主键
 * @return 没有登记过时为 undefined
 */
export function liveOf(sessionId?: number): LiveTurn | undefined {
  if (sessionId == null) {
    return undefined
  }
  return streams.get(sessionId)
}

/**
 * 把登记项的时间线换成新的数组，并重挂末尾助手卡。
 * 续订轮询与事件回放都会整体替换数组（`turns.value = [...]`），
 * 若不回写，切走再切回拿到的就是旧数组、旧卡片，内容会停住。
 * @param sessionId 会话主键
 * @param turns     新的时间线数组
 * @return 新的助手卡；没有登记项或末尾不是助手卡时为 undefined
 */
export function bindLiveTurns(sessionId: number, turns: ChatTurn[]): ChatTurn | undefined {
  const entry = streams.get(sessionId)
  if (!entry) {
    return undefined
  }
  const tail = turns[turns.length - 1]
  entry.turns = turns
  if (tail && tail.role === 'assistant') {
    entry.live = tail
    return tail
  }
  return undefined
}

/**
 * 该会话是否仍有未结束的一轮在跑。关掉面板后依然为 true —— 左栏转圈的来源。
 *
 * 注意：整页刷新后本模块的内存表已清空，这里返回 false —— 这是正确的，
 * 因为刷新意味着 SSE 连接已死、前端无法再收到任何增量；此时不能装作还在流。
 * @param sessionId 会话主键
 */
export function isTurnRunning(sessionId?: number): boolean {
  return sessionId != null && running.value.has(sessionId)
}

/**
 * 是否存在「本页真正持有 SSE」的一轮。
 * 与 {@link isTurnRunning} 的区别：刷新后 running 里可能因续订轮询被标成 true，
 * 但本页并没有任何流在收增量，只是等落库。UI 要据此区分「真流式」与「断线等待」。
 * @param sessionId 会话主键
 * @return true 表示本页确有一条未收尾的 SSE 登记项
 */
export function hasLiveStream(sessionId?: number): boolean {
  const entry = liveOf(sessionId)
  return !!entry && !entry.finished
}

/**
 * 登记 / 注销「执行中」。换新 Set 触发视图更新（Set 原地增删不触发 ref 追踪）。
 * @param sessionId 会话主键
 * @param active    true 登记，false 注销
 */
export function markTurnRunning(sessionId: number, active: boolean) {
  if (sessionId == null || sessionId < 0) {
    return
  }
  if (running.value.has(sessionId) === active) {
    return
  }
  const next = new Set(running.value)
  if (active) {
    next.add(sessionId)
  } else {
    next.delete(sessionId)
  }
  running.value = next
}

/**
 * 本轮收尾：摘转圈并落最后一次快照，但保留内容供短暂回看。
 * 落库事件接管后由调用方 {@link dropLive}。
 * @param sessionId 会话主键
 */
export function finishLive(sessionId: number) {
  markTurnRunning(sessionId, false)
  const entry = streams.get(sessionId)
  if (!entry) {
    return
  }
  entry.finished = true
  // 收尾是一次性成本：同步落最后一次，保证刷新后能看到完整回答
  saveSnapshot(sessionId, true)
}

/**
 * 丢弃登记项与快照。整轮事件已落库并重放后调用，避免下次重进读到旧数组。
 * @param sessionId 会话主键
 */
export function dropLive(sessionId: number) {
  streams.delete(sessionId)
  lastSavedTime.delete(sessionId)
  // 三类簿记一起清：否则排队中的空闲回调会把已删的快照又写回去
  savedSignature.delete(sessionId)
  pendingSessions.delete(sessionId)
  markTurnRunning(sessionId, false)
  clearSnapshot(sessionId)
}

/**
 * 登记「本轮内容有变化，需要落快照」。在 SSE 增量回调里调用。
 *
 * <p>热路径约束：这里**不做 JSON 序列化**。只算一个结构浅签名（轮数 + 块数 + 末尾轮文本长度），
 * 与上次落盘比一比；有变化就登记到待写集合，真正的 {@code JSON.stringify} + {@code setItem}
 * 推迟到浏览器空闲帧。整棵历史时间线只在「真的写盘」那一刻序列化一次。
 *
 * @param sessionId 会话主键
 * @param force     true 表示跳过空闲帧与节流立即同步写（收尾用）
 */
export function saveSnapshot(sessionId: number, force = false) {
  if (sessionId == null || sessionId < 0) {
    return
  }
  const entry = streams.get(sessionId)
  if (!entry) {
    return
  }
  const signature = snapshotSignature(entry.turns)
  if (signature === savedSignature.get(sessionId)) {
    // 内容一字未变（例如只推进了 phase 之外的字段）：不排队、不序列化
    return
  }
  bindPageHideFlush()
  if (force) {
    writeSnapshot(sessionId)
    return
  }
  pendingSessions.add(sessionId)
  scheduleFlush()
}

/**
 * 真正写盘：取 streams 里的当前数组，序列化后写 sessionStorage。
 *
 * @param sessionId 会话主键
 * @return true 表示确实写入了（内容没变或写失败都返回 false）
 */
function writeSnapshot(sessionId: number): boolean {
  const entry = streams.get(sessionId)
  if (!entry) {
    // 已 dropLive：绝不能把删掉的快照又写回去
    pendingSessions.delete(sessionId)
    return false
  }
  const signature = snapshotSignature(entry.turns)
  if (signature === savedSignature.get(sessionId)) {
    pendingSessions.delete(sessionId)
    return false
  }
  try {
    sessionStorage.setItem(`${SNAPSHOT_PREFIX}${sessionId}`, JSON.stringify(entry.turns))
  } catch {
    // 配额满或隐私模式：退化成「刷新后只能等 events」，不抛给页面
    pendingSessions.delete(sessionId)
    return false
  }
  savedSignature.set(sessionId, signature)
  lastSavedTime.set(sessionId, Date.now())
  pendingSessions.delete(sessionId)
  return true
}

/**
 * 排队一次空闲写盘。已有排队时不重复排。
 *
 * @param delayMs 大于 0 表示还在节流窗口内，用定时器等到窗口结束再写
 */
function scheduleFlush(delayMs = 0) {
  if (flushHandle != null) {
    return
  }
  if (delayMs > 0) {
    flushHandle = window.setTimeout(() => {
      flushHandle = null
      flushPending()
    }, delayMs)
    return
  }
  // 优先空闲帧：此时主线程不在渲染/网络回调里，序列化不会挤占一帧
  const idle = idleCallback()
  if (idle) {
    flushHandle = idle(() => {
      flushHandle = null
      flushPending()
    }, { timeout: SNAPSHOT_IDLE_TIMEOUT_MS })
    return
  }
  // 老浏览器没有 requestIdleCallback：退回下一个宏任务，同样不与当前帧抢时间
  flushHandle = window.setTimeout(() => {
    flushHandle = null
    flushPending()
  }, 0)
}

/** 逐个处理待写会话；仍在节流窗口内的留到窗口结束再写。 */
function flushPending() {
  const now = Date.now()
  let wait = 0
  for (const sessionId of [...pendingSessions]) {
    const remaining = SNAPSHOT_THROTTLE_MS - (now - (lastSavedTime.get(sessionId) ?? 0))
    if (remaining > 0) {
      wait = wait ? Math.min(wait, remaining) : remaining
      continue
    }
    writeSnapshot(sessionId)
  }
  if (pendingSessions.size) {
    scheduleFlush(wait)
  }
}

/**
 * 取 requestIdleCallback；不存在时返回 undefined，由调用方退回宏任务。
 * @return 已绑定 window 的空闲回调
 */
function idleCallback(): ((run: () => void, options: { timeout: number }) => number) | undefined {
  if (typeof window === 'undefined') {
    return undefined
  }
  const win = window as Window & {
    requestIdleCallback?: (run: () => void, options?: { timeout: number }) => number
  }
  return typeof win.requestIdleCallback === 'function'
    ? win.requestIdleCallback.bind(win)
    : undefined
}

/**
 * 刷新 / 关闭页面前把待写快照同步落盘。
 * <p>空闲回调在卸载过程中不保证执行，缺了这一步就等于丢掉最后 ≤1.2s 已收到的内容，
 * 而「刷新后先看到刷新前的内容」正是本快照存在的理由。
 */
function bindPageHideFlush() {
  if (pageHideBound || typeof window === 'undefined') {
    return
  }
  pageHideBound = true
  window.addEventListener('pagehide', () => {
    for (const sessionId of [...pendingSessions]) {
      writeSnapshot(sessionId)
    }
  })
}

/**
 * 读刷新前留在 sessionStorage 的快照。
 * <p>老快照没有块级 id：这里补齐，避免渲染退回下标 key（插入视觉块时会整表重挂）。
 *
 * @param sessionId 会话主键
 * @return 时间线；没有或已损坏时为 undefined
 */
export function loadSnapshot(sessionId?: number): ChatTurn[] | undefined {
  if (sessionId == null || sessionId < 0) {
    return undefined
  }
  try {
    const raw = sessionStorage.getItem(`${SNAPSHOT_PREFIX}${sessionId}`)
    if (!raw) {
      return undefined
    }
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed) || !parsed.length) {
      return undefined
    }
    return ensureBlockIds(parsed as ChatTurn[])
  } catch {
    return undefined
  }
}

/**
 * 清快照。整轮已落库、或会话被删除时调用。
 * @param sessionId 会话主键
 */
export function clearSnapshot(sessionId: number) {
  try {
    sessionStorage.removeItem(`${SNAPSHOT_PREFIX}${sessionId}`)
  } catch {
    // 忽略：清不掉只会让下次刷新多显示一次旧内容
  }
}

/** 超量时淘汰已收尾的最旧项；仍在跑的永不淘汰。 */
function prune() {
  if (streams.size <= MAX_LIVE) {
    return
  }
  for (const item of [...streams.values()]) {
    if (streams.size <= MAX_LIVE) {
      return
    }
    if (item.finished) {
      streams.delete(item.sessionId)
    }
  }
}
