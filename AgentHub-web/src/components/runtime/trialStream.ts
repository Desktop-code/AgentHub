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
 * 2. 刷新页面：JS 全部重来，SSE 连接随之消失。用 sessionStorage 节流快照
 *    保住刷新前已收到的内容，先立刻显示，再轮询 events 等整轮落库接管。
 * 3. 换标签页 / 换设备：什么都不共享，只能等 events 落库（后端不落中间增量，前端无法弥补）。
 *
 * 约束：存的是 ChatTurn 的响应式代理，禁止深拷贝（拷贝会断开 SSE 回调的写入目标）。
 */
import { ref } from 'vue'
import type { ChatTurn } from '@/types/runtime'

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
/** 快照写盘节流间隔。增量可能每秒几十次，逐次写 sessionStorage 会拖慢渲染。 */
const SNAPSHOT_THROTTLE_MS = 1200
/** 快照键前缀。只存会话主键与时间线，不含密钥：正文与思考在入库前已脱敏。 */
const SNAPSHOT_PREFIX = 'agenthub_trial_live_'

/** 各会话上次写快照的时间戳，用于节流。 */
const lastSavedTime = new Map<number, number>()

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
  saveSnapshotNow(sessionId)
}

/**
 * 丢弃登记项与快照。整轮事件已落库并重放后调用，避免下次重进读到旧数组。
 * @param sessionId 会话主键
 */
export function dropLive(sessionId: number) {
  streams.delete(sessionId)
  lastSavedTime.delete(sessionId)
  markTurnRunning(sessionId, false)
  clearSnapshot(sessionId)
}

/**
 * 节流写快照。在 SSE 增量回调里调用：同一次渲染周期内多次调用只落一次盘。
 * @param sessionId 会话主键
 * @param force     true 表示跳过节流立即写（收尾时用）
 */
export function saveSnapshot(sessionId: number, force = false) {
  if (sessionId == null || sessionId < 0) {
    return
  }
  const now = Date.now()
  const last = lastSavedTime.get(sessionId) ?? 0
  if (!force && now - last < SNAPSHOT_THROTTLE_MS) {
    return
  }
  lastSavedTime.set(sessionId, now)
  saveSnapshotNow(sessionId)
}

    /** 真正写盘。取 streams 里的当前数组，序列化失败只损失刷新后的首屏，不影响页面运行。 */
function saveSnapshotNow(sessionId: number) {
  const entry = streams.get(sessionId)
  if (!entry) {
    return
  }
  try {
    sessionStorage.setItem(`${SNAPSHOT_PREFIX}${sessionId}`, JSON.stringify(entry.turns))
  } catch {
    // 配额满或隐私模式：退化成「刷新后只能等 events」，不抛给页面
  }
}

/**
 * 读刷新前留在 sessionStorage 的快照。
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
    return parsed as ChatTurn[]
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
