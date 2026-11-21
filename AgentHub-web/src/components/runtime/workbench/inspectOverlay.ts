/**
 * 执行明细浮层的按钮规则、开关状态、卡片形态与锚点位置。所属域：runtime（前端展示）。
 *
 * <p>为什么单独抽成纯函数模块：执行明细在工作台是「贴着右上角按钮向左展开」的
 * 非模态浮层，没有任何遮罩；并且它有<b>两种形态</b>——运行中是紧凑状态卡，
 * 结束后才是完整泳道。这类交互最容易在后续改动里退化：加回暗罩、变回居中大卡、
 * 把刷新和 X 并排、运行中就弹出整张泳道。规则与锚点写在这一处，模板只读结果，
 * 单测也能直接断言，不必靠肉眼看页面。
 *
 * <p>约束：纯函数、零依赖，可被 node:test 直接执行；不涉及请求与权限。
 */

/** 顶栏实际渲染哪些动作 */
export interface InspectHeadActions {
  /** 手动刷新：只在「卡片内嵌」形态出现；浮层形态由自动轮询兜底，不占顶栏位置 */
  showRefresh: boolean
  /** X 关闭：只有浮层形态才有，点击即收起悬浮框 */
  showClose: boolean
  /** 完整链路入口：有 traceId 才显示，两种形态一致 */
  showTraceLink: boolean
}

/**
 * 顶栏动作组合。
 *
 * <p>关键规则：`closable`（浮层形态）与 `showRefresh` 互斥——浮层里刷新必须让位给 X，
 * 不允许两者并排，也不允许为了保留刷新而额外加一条标题栏。
 *
 * @param options.closable 是否浮层形态（顶栏用 X 收口）
 * @param options.hasTraceId 是否已有 trace 主键（决定「完整链路」是否可点）
 * @return 三个按钮的显示判定
 */
export function inspectHeadActions(options: {
  closable: boolean
  hasTraceId: boolean
}): InspectHeadActions {
  return {
    showRefresh: !options.closable,
    showClose: options.closable,
    showTraceLink: options.hasTraceId
  }
}

/**
 * 执行明细浮层的开关来源。三种入口语义不同，但结果只有「开 / 关」两态。
 *
 * <p>注意：没有「点遮罩关闭」——浮层不带遮罩，遮罩本身就不存在。
 *
 * - open：点右上角「执行明细」按钮；
 * - close：点顶栏 X / 按 Esc，两者等价；
 * - reset：切换会话，浮层必须回到关闭，避免把上一轮的明细挂在新会话上。
 */
export type InspectOverlayAction = 'open' | 'close' | 'reset'

/**
 * 浮层开关状态转移。
 *
 * @param current 当前是否打开
 * @param action  触发动作
 * @return 动作后的打开状态
 */
export function nextInspectOpen(current: boolean, action: InspectOverlayAction): boolean {
  if (action === 'open') {
    return true
  }
  // close 与 reset 都收敛到「关」：点 X、按 Esc、切会话，用户看到的结果必须一致
  return false
}

/* ===== 卡片形态：运行中紧凑状态卡，结束后才完整泳道 ===== */

/** 紧凑状态卡：运行中的小卡片，只看「现在跑到哪」 */
export const INSPECT_MODE_COMPACT = 'compact'
/** 完整泳道：结束态，或用户主动点按钮后才出现的完整明细 */
export const INSPECT_MODE_FULL = 'full'

export type InspectCardMode = typeof INSPECT_MODE_COMPACT | typeof INSPECT_MODE_FULL

/**
 * 本轮应展示哪种卡片。
 *
 * <p>判定只看「这一轮是否还在跑」，不看用户点没点：
 * <ul>
 *   <li>运行中 → 紧凑状态卡（不把整张泳道糊在对话上）；</li>
 *   <li>已结束 → 完整泳道。</li>
 * </ul>
 * 用户点 X 收起后再点按钮，仍按这个判定重新展开当前应显示的那张。
 *
 * @param running 本轮是否仍在执行（sending / resuming）
 * @return 卡片形态
 */
export function inspectCardMode(running: boolean): InspectCardMode {
  return running ? INSPECT_MODE_COMPACT : INSPECT_MODE_FULL
}

/**
 * 执行明细的 250ms 时钟是否该走。
 *
 * <p>只决定要不要推进 `inspectNow`。不改浮层开合：失活时浮层可以仍开着，
 * 但不再随后台 tick 重算紧凑卡与按钮耗时。
 *
 * <p>为什么运行中也必须走表：耗时已经上收到触发按钮，浮层关着时按钮上的
 * 「执行中 · 12.3s」仍要随时间增长；只在浮层打开时走表会让秒数停在打开那一刻。
 *
 * @param input.pageActive 页签是否在前台
 * @param input.running    本轮是否仍在执行（决定按钮耗时是否继续增长）
 * @param input.detailOpen 浮层当前是否打开（原样读，本函数不写）
 * @param input.compact    当前形态是否为紧凑卡
 * @return true 才允许挂 250ms 定时器
 */
export function inspectClockNeeded(input: {
  pageActive: boolean
  running: boolean
  detailOpen: boolean
  compact: boolean
}): boolean {
  if (!input.pageActive) {
    return false
  }
  // 运行中始终走表（按钮耗时）；空闲时只有紧凑卡打开才需要推进
  return input.running || (input.detailOpen && input.compact)
}

/* ===== 运行中紧凑卡的状态行 ===== */

/** 一个参与者在紧凑卡里的一行 */
export interface InspectRunningTask {
  /** 稳定 key：复用泳道行 key，状态原地更新时不重建 */
  key: string
  /** 参与者名（外壳显示方案 / 工作流名，内层显示专家名） */
  title: string
  /** 当前动作短句，如「检索中…」「生成中…」「流转中…」 */
  state: string
  /** 是否已结束（结束的行仍列出，但不再写「运行中」） */
  done: boolean
  /**
   * 该行结束时刻（epoch ms）；仍在跑时为 null。
   *
   * <p>为什么需要它：完成后要在列表里留观 2s（绿色底）再滑出，
   * 只凭 `done` 这个布尔判定不出「已经绿了多久」。
   */
  doneAtMs: number | null
}

/**
 * 紧凑卡标题：与泳道并行分组的既有文案同源。
 *
 * <p>「并行执行中（N 个任务）」只在真并行时用；单任务时写「执行中」，
 * 收尾后统一「执行完成」——不在结束态继续占着「运行中」的措辞。
 *
 * @param running 本轮是否仍在跑
 * @param total   参与者数量
 * @param parallel 是否为并行模板（多目标任务同时跑）
 * @return 标题文案
 */
export function inspectCompactTitle(running: boolean, total: number, parallel: boolean): string {
  const count = Math.max(0, total)
  if (!running) {
    return count > 0 ? `执行完成（${count} 个任务）` : '执行完成'
  }
  if (parallel && count > 1) {
    return `并行执行中（${count} 个任务）`
  }
  return count > 1 ? `执行中（${count} 个任务）` : '执行中'
}

/**
 * 紧凑卡副标题：运行中给已耗时，结束态交给调用方给总耗时。
 *
 * @param running  本轮是否仍在跑
 * @param elapsedMs 已耗时（运行中）或总耗时（结束态）
 * @return 如「12.3s」；拿不到时为空串
 */
export function inspectCompactMeta(running: boolean, elapsedMs: number): string {
  const text = formatInspectDuration(elapsedMs)
  if (!text) {
    return ''
  }
  return running ? `已 ${text}` : `耗时 ${text}`
}

/**
 * 耗时数字的统一写法：<1s 用毫秒，否则保留一位小数。
 *
 * <p>浮卡与触发按钮共用这一份格式化，避免两处一个写 12.3s、一个写 12s。
 *
 * @param elapsedMs 耗时（毫秒）
 * @return 如「320ms」「12.3s」；拿不到或非正数时为空串
 */
export function formatInspectDuration(elapsedMs: number): string {
  if (!Number.isFinite(elapsedMs) || elapsedMs <= 0) {
    return ''
  }
  return elapsedMs < 1000 ? `${Math.round(elapsedMs)}ms` : `${(elapsedMs / 1000).toFixed(1)}s`
}

/**
 * 「执行明细」触发按钮上的状态文案。
 *
 * <p>按钮必须自己说清「进行中还是已完成、花了多久」，不能只留一个含义不明的
 * 「执行明细」。耗时拿不到时退化为纯状态词，不写 0s 冒充已计时。
 *
 * @param running   本轮是否仍在执行
 * @param elapsedMs 耗时；null 表示还没拿到（new 一轮刚起、trace 未到）
 * @return 如「执行中 · 12.3s」「已完成 · 12.3s」「执行中」
 */
export function inspectTriggerLabel(running: boolean, elapsedMs: number | null): string {
  const head = running ? '执行中' : '已完成'
  const duration = elapsedMs == null ? '' : formatInspectDuration(elapsedMs)
  return duration ? `${head} · ${duration}` : head
}

/* ===== 收尾节奏：完成行留观 2s 再滑出（浮层本身不再自动关闭） ===== */

/**
 * 完成后停留时长（ms）。
 *
 * <p>为什么要有：某位参与者刚变成「已完成」时，用户需要一眼看到它是绿的；
 * 立刻消失等于「做完了却没有任何反馈」。停留 2s 让人看见结果，再滑出。
 */
export const INSPECT_DONE_HOLD_MS = 2000

/** 滑出动画时长（ms）：与 .wb-inspect-fade / slide 过渡保持一致 */
export const INSPECT_EXIT_MS = 180

/**
 * 单个任务行是否应当从列表里移除。
 *
 * <p>判定用「该任务结束时刻」而不是「当前是否 done」：只有结束满
 * {@link INSPECT_DONE_HOLD_MS} 才移除，这样绿色底能在列表里停留够久。
 *
 * @param doneAtMs 该任务结束时刻（epoch ms）；仍在跑或拿不到时为 null
 * @param nowMs    当前时刻
 * @return true 表示已过停留期，可以移除
 */
export function shouldDropDoneTask(doneAtMs: number | null, nowMs: number): boolean {
  if (doneAtMs == null) {
    return false
  }
  return nowMs - doneAtMs >= INSPECT_DONE_HOLD_MS
}

/**
 * 整轮结束后是否应当关闭浮层（保留的纯函数，供仍需「收尾留观」的调用方）。
 *
 * <p>口径：本轮已收尾，且最后一处变更发生满 {@link INSPECT_DONE_HOLD_MS}。
 * 用「收尾时刻 + 停留」判断，避免一结束就消失、用户没看见最终状态。
 *
 * <p>工作台当前<b>不再</b>用它做延时关闭：运行中自动展开的紧凑卡在收尾那一拍直接收起，
 * 用户手动打开的不收；结束后的耗时改由触发按钮承载（见 `inspectTriggerLabel`）。
 *
 * @param running    本轮是否仍在跑
 * @param settledAtMs 本轮收尾时刻（epoch ms）；仍在跑时为 null
 * @param nowMs      当前时刻
 * @return true 表示该关闭
 */
export function shouldCloseAfterSettle(
  running: boolean,
  settledAtMs: number | null,
  nowMs: number
): boolean {
  if (running || settledAtMs == null) {
    return false
  }
  return nowMs - settledAtMs >= INSPECT_DONE_HOLD_MS
}

/* ===== 锚点：按钮与浮层共用同一份数字，避免「贴不住按钮」 ===== */

/**
 * 执行明细浮层节点的 id。
 *
 * <p>按钮的 `aria-controls` 指向它，读屏据此知道「按这个按钮会展开哪一块」。
 * 只在浮层打开时出现在 DOM 里，关闭时按钮不带 `aria-controls`。
 */
export const INSPECT_FLOAT_ID = 'wb-inspect-float'

/** 按钮距主区顶部的距离（px） */
export const INSPECT_ANCHOR_TOP = 20
/** 按钮与浮层共用的右对齐距离（px）：右边缘对齐，浮层自然向左展开 */
export const INSPECT_ANCHOR_RIGHT = 24
/** 按钮固定高度（px）：浮层顶部由它推算，改高度只需改这一处 */
export const INSPECT_BUTTON_HEIGHT = 32
/** 按钮与浮层之间的间隙（px）：视觉上「贴着」但不粘连 */
export const INSPECT_POPOVER_GAP = 8
/**
 * 浮层最大宽度（px）。
 *
 * <p>为什么是 420：泳道由「标题列 + 时间轨 + 状态列」三段构成，再窄会把行名挤成省略号、
 * 时间轨短到读不出先后。420 是「不遮对话主区」与「泳道仍可读」的折中；
 * 窄视口下由 {@link inspectPopoverStyle} 的 `max-width` 与容器的 `calc(100% - …)` 兜住。
 */
export const INSPECT_POPOVER_MAX_WIDTH = 420

/**
 * 紧凑状态卡的最大宽度（px）。
 *
 * <p>比完整泳道窄得多：它只有「标题 + 几行任务状态」，不需要给时间轨留位置，
 * 因此按用户示意图那种小卡取 320，进一步减少对左侧对话的遮挡。
 */
export const INSPECT_COMPACT_MAX_WIDTH = 320

/**
 * 悬浮框宽度：按卡片形态取上限，主区更窄时随容器收。
 *
 * <p>用 `min()` 而不是固定像素：工作台主区可能被左栏历史会话挤窄，
 * 固定宽度会在窄屏溢出，出现横向滚动条。
 *
 * @param mode 卡片形态；缺省按完整泳道（较宽）
 * @return 可直接放进内联样式的宽度值
 */
export function inspectPopoverWidth(mode: InspectCardMode = INSPECT_MODE_FULL): string {
  const max = mode === INSPECT_MODE_COMPACT ? INSPECT_COMPACT_MAX_WIDTH : INSPECT_POPOVER_MAX_WIDTH
  return `min(${max}px, calc(100% - ${INSPECT_ANCHOR_RIGHT * 2}px))`
}

/**
 * 「执行明细」按钮的定位样式。
 *
 * @return 内联样式对象（top / right / height）
 */
export function inspectButtonStyle(): Record<string, string> {
  return {
    top: `${INSPECT_ANCHOR_TOP}px`,
    right: `${INSPECT_ANCHOR_RIGHT}px`,
    height: `${INSPECT_BUTTON_HEIGHT}px`
  }
}

/**
 * 悬浮框的定位样式：顶部接在按钮下沿，右边缘与按钮对齐（向左展开）。
 *
 * <p>刻意<b>不</b>输出 `inset` / `left` / `width: 100%`：那会把浮层铺成覆盖主区的层，
 * 正是「整页遮罩 + 居中宽卡」的成因。这里只有「右上角的一个盒子」。
 *
 * @param mode 卡片形态；紧凑卡更窄
 * @return 内联样式对象（top / right / width / max-height）
 */
export function inspectPopoverStyle(mode: InspectCardMode = INSPECT_MODE_FULL): Record<string, string> {
  const top = INSPECT_ANCHOR_TOP + INSPECT_BUTTON_HEIGHT + INSPECT_POPOVER_GAP
  return {
    top: `${top}px`,
    right: `${INSPECT_ANCHOR_RIGHT}px`,
    width: inspectPopoverWidth(mode),
    // 底部留出与按钮对称的边距，内容多时由卡片自身滚动，不撑破主区
    maxHeight: `calc(100% - ${top + INSPECT_ANCHOR_RIGHT}px)`
  }
}

/* ===== 运行中：从 span 树推导每行「现在在干什么」 ===== */

/** 紧凑卡推导只需要 span 的这几个字段，避免把整个 ObsSpan 拖进纯模块 */
export interface InspectSpanLike {
  id: number
  parentId: number | null
  spanType: string | null
  name: string | null
  agentName: string | null
  status: string | null
  latencyMs: number | null
  /** TOOL 步的工具名：用于区分「知识库检索」与普通工具 */
  toolName?: string | null
  /** 写入序：参与者按它排序，保证卡片行序稳定 */
  seq?: number | null
  /** attrs 原文：只用它读 ma.root，判定编排容器 */
  attrsJson?: string | null
  /** 结束时刻：完成后据此计算「已经绿了多久」，决定何时滑出 */
  endTime?: string | null
}

/** 运行中动作短句：工具在跑 / 刚开模型 / 已有 LLM 但在等下一步 */
export const INSPECT_STATE_RETRIEVING = '检索中…'
export const INSPECT_STATE_GENERATING = '生成中…'
export const INSPECT_STATE_FLOWING = '流转中…'
export const INSPECT_STATE_THINKING = '思考中…'
export const INSPECT_STATE_DONE = '已完成'
export const INSPECT_STATE_FAILED = '失败'

/** 检索类工具名关键词：命中即认为这是知识库检索，而不是普通工具 */
const RETRIEVE_TOOL_HINTS = ['search', 'retrieve', 'kb', 'knowledge', '知识库', '检索']

/**
 * 单个 Agent 子树当前的运行中动作。
 *
 * <p>判定顺序按「最靠内的活跃步」来，避免把一个已经跑完的检索说成「检索中」：
 * <ol>
 *   <li>子树里有 RUNNING 的 TOOL 且是检索类 → 检索中；</li>
 *   <li>有 RUNNING 的 TOOL（非检索）→ 流转中（工具在跑，但不是检索）；</li>
 *   <li>有 RUNNING 的 LLM → 生成中；</li>
 *   <li>自己还在跑但子树没有活跃步 → 流转中（等下游 / 刚开步）；</li>
 *   <li>自己已结束 → 已完成 / 失败。</li>
 * </ol>
 *
 * @param root  该参与者的 AGENT span
 * @param spans 整条 trace 的步骤
 * @return 一行状态
 */
export function inspectRunningStateOf(
  root: InspectSpanLike,
  spans: readonly InspectSpanLike[]
): InspectRunningTask {
  const done = root.status !== 'RUNNING'
  const key = `task-${root.id}`
  const title = (root.agentName || root.name || '').trim() || `#${root.id}`
  if (done) {
    return {
      key,
      title,
      state: root.status === 'ERROR' ? INSPECT_STATE_FAILED : INSPECT_STATE_DONE,
      done: true,
      // 结束时刻：优先用 span.end_time（真实收口时间），解析不出时交给调用方按「首次看到 done」兜底
      doneAtMs: parseSpanTime(root.endTime)
    }
  }

  // 子树：从自己往下所有 parentId 可达的步骤
  const byId = new Map<number, InspectSpanLike>()
  const childMap = new Map<number, InspectSpanLike[]>()
  for (const span of spans) {
    byId.set(span.id, span)
    if (span.parentId != null) {
      const list = childMap.get(span.parentId) ?? []
      list.push(span)
      childMap.set(span.parentId, list)
    }
  }
  const subtree: InspectSpanLike[] = []
  const walk = (id: number, guard = 0) => {
    if (guard > spans.length) {
      return
    }
    for (const child of childMap.get(id) ?? []) {
      subtree.push(child)
      walk(child.id, guard + 1)
    }
  }
  walk(root.id)

  const active = subtree.filter((span) => span.status === 'RUNNING')
  const runningTool = active.find((span) => span.spanType === 'TOOL')
  if (runningTool) {
    const label = `${runningTool.toolName || runningTool.name || ''}`.toLowerCase()
    const retrieving = RETRIEVE_TOOL_HINTS.some((hint) => label.includes(hint.toLowerCase()))
    return {
      key,
      title,
      state: retrieving ? INSPECT_STATE_RETRIEVING : INSPECT_STATE_FLOWING,
      done: false,
      doneAtMs: null
    }
  }
  if (active.some((span) => span.spanType === 'LLM')) {
    return { key, title, state: INSPECT_STATE_GENERATING, done: false, doneAtMs: null }
  }
  // 子树还没开出活跃步（刚 begin / 在等下游）：说「流转中」，不猜成思考
  return { key, title, state: INSPECT_STATE_FLOWING, done: false, doneAtMs: null }
}

/**
 * 解析 span 时间串为 epoch 毫秒；与全站口径一致（`yyyy-MM-dd HH:mm:ss[.SSS]` 或 ISO）。
 *
 * @param raw 时间串；可空
 * @return 毫秒；解析不出时 null
 */
function parseSpanTime(raw: string | null | undefined): number | null {
  if (!raw) {
    return null
  }
  const ms = Date.parse(raw.includes('T') ? raw : raw.replace(' ', 'T'))
  return Number.isNaN(ms) ? null : ms
}

/** 方案编排根标记，与后端 {@code ma.root} 同键 */
const MA_ROOT_KEY = 'ma.root'
/** {@link MA_ROOT_KEY} 的值：这一位是方案自己的编排根（容器步，不是参与者） */
const MA_ROOT_SCHEME = 'SCHEME'

/**
 * 判断一个 AGENT 是否是「编排容器」：它自己还装着别的 AGENT。
 *
 * <p>为什么不能只看 attrs：`ma.root=SCHEME`、`obs.role` 都是**收口时**才写的，
 * 运行中的 span 这几列为空。因此运行期只能靠结构判定——装着别的 AGENT 的那一位
 * 是容器（入口网关 / 方案外壳 / 方案编排根 / 工作流外壳），真正干活的是里层。
 *
 * @param span  待判定 AGENT
 * @param spans 整条 trace 的步骤（用于找后代）
 * @return true 表示这是容器，不是真正在执行的参与者
 */
export function isOrchestrationContainer(
  span: InspectSpanLike,
  spans: readonly InspectSpanLike[]
): boolean {
  if (span.spanType !== 'AGENT') {
    return false
  }
  // 收口后 attrs 齐了就直接认：方案编排根一定不是参与者
  const raw = span.attrsJson
  if (raw && raw.trim()) {
    try {
      const attrs = JSON.parse(raw) as Record<string, unknown>
      if (attrs[MA_ROOT_KEY] === MA_ROOT_SCHEME) {
        return true
      }
    } catch {
      // attrs 解析失败按「没有标记」处理：旁路信息不该让整张卡片报错
    }
  }
  // 运行期兜底：子树里还有 AGENT 的，就是容器
  return descendantAgentsOf(span, spans).length > 0
}

/**
 * 某个 span 子树里的全部 AGENT（不含自身）。
 *
 * @param root  子树根
 * @param spans 整条 trace 的步骤
 * @return 后代 AGENT，按 seq 升序
 */
function descendantAgentsOf(
  root: InspectSpanLike,
  spans: readonly InspectSpanLike[]
): InspectSpanLike[] {
  const childMap = new Map<number, InspectSpanLike[]>()
  for (const span of spans) {
    if (span.parentId == null) {
      continue
    }
    const list = childMap.get(span.parentId) ?? []
    list.push(span)
    childMap.set(span.parentId, list)
  }
  const found: InspectSpanLike[] = []
  const walk = (id: number, guard = 0) => {
    if (guard > spans.length) {
      return
    }
    for (const child of childMap.get(id) ?? []) {
      if (child.spanType === 'AGENT') {
        found.push(child)
      }
      walk(child.id, guard + 1)
    }
  }
  walk(root.id)
  return found.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0) || a.id - b.id)
}

/**
 * 运行中紧凑卡的参与者列表：把「真正在干活的 AGENT」平铺出来。
 *
 * <p>为什么这里不能复用完整泳道的分层行：泳道行按「外壳 ⊃ 编排根 ⊃ 专家」归拢，
 * 入口方案根（智能网关）是一个 parent 为空的 AGENT，于是整棵子树只剩它一行，
 * 专家全被收进 nested —— 这正是「紧凑卡只显示一行智能网关、不随节点变化」的根因。
 *
 * <p>判定口径：
 * <ol>
 *   <li>只认 AGENT；</li>
 *   <li>容器（子树里还有 AGENT 的）不算参与者，往里取真正的执行者；</li>
 *   <li>容器还在跑但里面一个 AGENT 都还没开出来时，保留这个容器自身一行，
 *       让卡片不至于在某一路完全空白（状态由它自己的子树推导）。</li>
 * </ol>
 *
 * @param spans 整条 trace 的步骤
 * @return 参与者 span，按 seq 升序
 */
export function inspectParticipantsOf(spans: readonly InspectSpanLike[]): InspectSpanLike[] {
  const agents = spans.filter((span) => span.spanType === 'AGENT')
  if (!agents.length) {
    return []
  }
  const picked: InspectSpanLike[] = []
  for (const agent of agents) {
    const inner = descendantAgentsOf(agent, spans)
    if (inner.length === 0) {
      // 叶子 AGENT：真正干活的那一位
      picked.push(agent)
      continue
    }
    // 容器：只有当它自己还在跑、且内部一个 AGENT 都还没开出来时，才用容器占位
    if (agent.status === 'RUNNING') {
      const hasOpenInner = inner.some((child) => child.status === 'RUNNING')
      if (!hasOpenInner) {
        picked.push(agent)
      }
    }
  }
  // 容器占位可能与叶子重复（容器被 push 后叶子也在 picked 里）：按 id 去重
  const seen = new Set<number>()
  const unique: InspectSpanLike[] = []
  for (const span of picked.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0) || a.id - b.id)) {
    if (seen.has(span.id)) {
      continue
    }
    seen.add(span.id)
    unique.push(span)
  }
  return unique
}

/** 紧凑卡任务行：按参与者平铺，一行一位，状态随各自子树实时推导 */
export function inspectLiveTasks(spans: readonly InspectSpanLike[]): InspectRunningTask[] {
  return inspectParticipantsOf(spans).map((span) => inspectRunningStateOf(span, spans))
}
