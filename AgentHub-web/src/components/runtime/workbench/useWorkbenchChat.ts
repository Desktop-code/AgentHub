/**
 * 工作台对话逻辑。所属域：runtime（前端）。
 *
 * <p>职责：把「左栏会话 + 右侧对话」所需的全部状态与动作收在一处，页面组件只负责排版。
 * 与调试面板（TrialPanel）分开的原因：工作台没有 Agent / 方案归属，也不允许前端指定路由目标，
 * 会话一律由后端默认接待方案创建，因此列表、创建、SSE 都走 `/api/runtime/workbench/**`。
 *
 * <p>关键约束：
 * <ul>
 *   <li><b>草稿不落库</b>：「+ 新会话」只在内存里清空右栏并把 id 记为 {@link TEMP_SESSION_ID}，
 *       首条消息发送时才调创建接口；未发言就刷新不会留下任何记录。</li>
 *   <li><b>列表隔离</b>：只读工作台接口，服务端已按 {@code entry_channel=WORKBENCH} 过滤；
 *       不请求、也不复用 `/api/runtime/trial/**` 的列表。</li>
 *   <li><b>就绪优先</b>：{@code ready=false} 时禁发（输入禁用），历史仍可查看。</li>
 *   <li><b>@ 换线</b>：候选只读本会话快照方案启用项；选定走 PUT sticky + 一次性 force；
 *       未 @ 仍自动匹配；草稿首条 create→PUT→发，不先建空会话。</li>
 *   <li>续订与快照复用 trialStream / trialMessages 的既有能力，不另写一套时间线解析。</li>
 * </ul>
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveWorkbenchPlan,
  clearWorkbenchSession,
  createWorkbenchSession,
  deleteWorkbenchSession,
  getPendingWorkbenchChildPlan,
  getPendingWorkbenchPlan,
  getWorkbenchConfig,
  getWorkbenchRoutes,
  getWorkbenchSession,
  listWorkbenchEvents,
  listWorkbenchLiveEvents,
  pageWorkbenchSessions,
  rejectWorkbenchPlan,
  renameWorkbenchSession,
  streamWorkbenchMessage,
  switchWorkbenchRoute
} from '@/api/workbench'
import { newTrialIdempotencyKey, type TrialChildPlanPendingChunk } from '@/api/runtime'
import {
  advancePlanState,
  appendFile,
  appendRoute,
  appendText,
  appendTool,
  appendVision,
  assistantTurn,
  formatRelativeTime,
  hasOpenTurn,
  markLiveTail,
  openTurnStale,
  parseFile,
  parsePlan,
  parseStep,
  turnsFromEvents,
  upsertPlan,
  upsertStep
} from '@/components/runtime/trialMessages'
import { forgetTrialSession, recallTrialSession, rememberTrialSession } from '@/components/runtime/trialResume'
import {
  beginLive,
  bindLiveTurns,
  clearSnapshot,
  dropLive,
  finishLive,
  hasLiveStream,
  isTurnRunning,
  liveOf,
  loadSnapshot,
  markTurnRunning,
  saveSnapshot
} from '@/components/runtime/trialStream'
import { groupSessionsForWorkbench, parseSessionTime } from '@/components/runtime/trial/sessionGroup'
import { rowShowsSpinner, sessionStillRunning, shouldResumeTurn } from '@/components/runtime/sessionProgress'
import { friendlyWorkbenchError } from '@/utils/workbenchFriendlyError'
import type { SessionGroup } from '@/components/runtime/trial/sessionGroup'
import type { ChatTurn, TrialEvent, TrialPlanView, TrialSession } from '@/types/runtime'
import type { WorkbenchConfig, WorkbenchRouteOption, WorkbenchRouteOptions } from '@/types/workbench'

/** 仅前端的临时草稿 id：禁止拿去调删除/清空/事件接口。 */
const TEMP_SESSION_ID = -1
/** 工作台没有归属主键：trialResume 用它拼固定存储键 `workbench`。 */
const WORKBENCH_OWNER = 1
/** 左栏页大小：与服务端上限一致，超出靠触底追加。 */
const PAGE_SIZE = 50
/** 续订轮询间隔：后端整轮结束才落库，只能短轮询等收尾。 */
const RESUME_POLL_MS = 2000
/** 续订硬顶：对齐后端 limits.timeout-ms（默认 300s）再留缓冲。 */
const RESUME_MAX_MS = 330_000
/**
 * 自动续订的静默上限。末尾事件超过这个时间还没更新，就认为不是本轮（历史僵尸 RUNNING），
 * 只渲染已落库内容、不自动轮询。
 */
const RESUME_FRESH_MS = 10 * 60_000
/** 「刚建会话、USER 事件还没落库」的极短窗口，期内 events 为空也按进行中处理。 */
const PENDING_FIRST_EVENT_MS = 2 * 60_000
/** 续订期间事件一直为空时的等待上限，避免这一轮没起来还空转满整轮。 */
const RESUME_EMPTY_MS = 45_000
/** 首轮标题补拉的延迟点（毫秒）；命中即停，不做常驻轮询。 */
const TITLE_REFRESH_DELAYS = [3000, 8000] as const

/** 工作台对话的完整状态与动作。 */
export function useWorkbenchChat() {
  const config = ref<WorkbenchConfig>()
  const configLoading = ref(false)
  const session = ref<TrialSession>()
  const turns = ref<ChatTurn[]>([])
  const history = ref<TrialSession[]>([])
  /**
   * 本地已知会话（id → 会话）。服务端列表取不到当前行时用它补行：
   * 本轮进行中时后端还没回写轮次/预览，列表接口看起来像空壳。
   */
  const knownSessions = ref<Map<number, TrialSession>>(new Map())
  const pageError = ref('')
  const sending = ref(false)
  const historyLoading = ref(false)
  /** 已加载到的页码；首屏重置为 1，追加成功才前进。 */
  const historyCurrent = ref(1)
  /** 服务端全量条数（COUNT），用于判断还有没有下一页。 */
  const historyTotal = ref(0)
  /** 已从服务端取回的原始行数（含被前端筛掉的行）。 */
  const historyLoadedRows = ref(0)
  const historyLoadingMore = ref(false)
  /** 是否还有下一页。 */
  const historyHasMore = computed(() => historyLoadedRows.value < historyTotal.value)
  /** 前端临时「新对话」：未发首问前可输入；不占库。 */
  const draftReady = ref(false)
  /** 计划批准 / 取消请求进行中。避免连点消费同一份计划。 */
  const approving = ref(false)
  /** 编排根下当前待批的子专家计划；同一根同时只允许一个。 */
  const childPending = ref<TrialChildPlanPendingChunk | null>(null)
  /** 子计划批准 / 拒绝请求进行中。 */
  const childDeciding = ref(false)
  /** 当前会话「连接已断、只能等落库」：如实提示，不假装还在思考。 */
  const detached = ref(false)

  /** 列表请求令牌：首屏 / 重新加载时作废在途请求，避免串列表。 */
  let historyToken = 0
  /** 打开会话的令牌：连点两个会话时丢弃迟到响应。 */
  let openToken = 0
  /** 续订轮询定时器。 */
  let pollTimer: number | undefined
  /** 标题补拉定时器。 */
  let titleTimer: number | undefined
  /** 续订令牌：每次开/停都自增，迟到的异步结果直接丢弃。 */
  let pollToken = 0
  /** 当前正在续订的会话 id。 */
  let resumingId: number | undefined
  /** 上一拍已渲染的事件签名（条数 + 末尾 seq + 末尾正文长度）。 */
  let resumeSignature = ''

  /** 就绪：只有 ready=true 才允许新建与发送。 */
  const ready = computed(() => config.value?.ready === true)
  /** 未就绪原因；就绪时为空串。 */
  const reason = computed(() => (config.value?.reason || '').trim())
  /** 默认接待方案名，仅用于页头条展示。 */
  const schemeName = computed(() => (config.value?.schemeName || '').trim())

  /** 当前会话是否正在续订（只绑当前会话）。 */
  const resuming = computed(() => {
    const id = session.value && !isTempSession(session.value) ? session.value.id : undefined
    return id != null && resumingId === id && isTurnRunning(id)
  })

  /** 可发送：就绪，且当前是草稿或未取消的会话。 */
  const canSend = computed(
    () => ready.value && (draftReady.value || (!!session.value && session.value.status !== 'CANCELLED'))
  )

  /** 不能发送时的说明。未就绪优先于「会话已取消」。 */
  const blockedHint = computed(() => {
    if (!ready.value) {
      return reason.value || '默认接待尚未就绪，暂时不能开始对话'
    }
    if (session.value && session.value.status === 'CANCELLED') {
      return '会话已取消，请新建会话'
    }
    return ''
  })

  /** 页头主标题：当前会话标题，草稿与空态统一显示「新对话」。 */
  const title = computed(() => (session.value ? sessionTitle(session.value) : '新对话'))

  /** 左栏分组：临时草稿钉最前，仍在执行的本地会话补行，其余按日期分组。 */
  const historyGroups = computed<SessionGroup[]>(() => {
    const current = session.value
    const seen = new Set<number>()
    const rows = history.value.filter((row) => {
      if (isTempSession(row) || seen.has(row.id)) {
        return false
      }
      seen.add(row.id)
      return true
    })
    const have = new Set(rows.map((row) => row.id))
    const extra: TrialSession[] = []
    if (current) {
      if (isTempSession(current) || !have.has(current.id)) {
        extra.push(current)
        have.add(current.id)
      }
    }
    // 本地登记过、仍在执行但服务端列表还没回写的会话：切走再切回也不能丢行
    for (const [id, row] of knownSessions.value) {
      if (!have.has(id) && isTurnRunning(id)) {
        extra.push(row)
        have.add(id)
      }
    }
    if (!extra.length) {
      return groupSessionsForWorkbench(rows)
    }
    return groupSessionsForWorkbench([...extra, ...rows])
  })

  /**
   * 是否有可查看的执行明细：已落库会话且用户有 Trace 权限。
   * 权限判定由 useRouterLane 在页面侧再兜一层（无权限时整卡不渲染）。
   */
  const traceSessionId = computed(() => {
    const current = session.value
    return current && !isTempSession(current) && current.id > 0 ? current.id : null
  })

  /** 当前会话的可选线路；空表示尚未取到（或不是可换线的方案） */
  const routes = ref<WorkbenchRouteOptions>()
  /** 换线请求进行中：期间禁用选取，避免连点写两次强制标记 */
  const routeSwitching = ref(false)
  /**
   * 输入框 @ 选定的下一轮强制线路（展示片）。
   * 已落库会话：选定即 PUT 换线；草稿：仅本地记住，首条发出时 create→PUT→发消息，不先建空会话。
   */
  const pendingForceRoute = ref<WorkbenchRouteOption | null>(null)
  /** 线路请求令牌：切会话时作废在途响应 */
  let routesToken = 0
  /** 本次请求对应的会话 id；迟到的响应不能改到别的会话上 */
  let routesSessionId: number | undefined

  /** 可换线的候选（服务端已过滤停用 / 删除）；已落库会话用 */
  const routeCandidates = computed(() => routes.value?.candidates ?? [])

  /**
   * @ 面板候选：已落库会话用 routes；草稿用默认接待配置里的启用项（同范围，不扩全平台）。
   */
  const atRouteCandidates = computed<WorkbenchRouteOption[]>(() => {
    if (session.value && !isTempSession(session.value)) {
      return routeCandidates.value
    }
    return (config.value?.candidates ?? [])
      .filter((item) => !!(item.routeKey && item.routeKey.trim()))
      .map((item) => ({
        routeKey: String(item.routeKey).trim(),
        targetType: item.targetType,
        agentId: item.agentId,
        maDefId: item.maDefId,
        workflowDefId: item.workflowDefId,
        agentName: item.agentName,
        intentDesc: item.intentDesc,
        sortNo: item.sortNo
      }))
  })

  /**
   * 当前线路是否已失效：有沿用键，但候选里已找不到它。
   *
   * <p>服务端换线列表只含启用候选，所以命中不到就等于「已停用 / 已删除」。
   */
  const routeInvalid = computed(() => {
    const key = (routes.value?.currentRouteKey || '').trim()
    return !!key && !routeCandidates.value.some((item) => item.routeKey === key)
  })

  /** 思考折叠行 / 状态条用的线路展示名：只出展示名，不暴露 route_key */
  const routeName = computed(() => {
    if (pendingForceRoute.value?.agentName?.trim()) {
      return pendingForceRoute.value.agentName.trim()
    }
    if (routeInvalid.value) {
      return '线路已失效'
    }
    const name = (routes.value?.currentRouteName || '').trim()
    if (name) {
      return name
    }
    return routes.value?.currentRouteKey ? '当前线路' : ''
  })

  /** 当前沿用的 route_key；只用于 @ 列表标记选中项，不渲染成文本 */
  const currentRouteKey = computed(() => (routes.value?.currentRouteKey || '').trim())

  /**
   * 底部徽标：已 @ / 已有 sticky 时不要假装「未指定的自动匹配」。
   */
  const gatewayBadgeText = computed(() => {
    if (pendingForceRoute.value?.agentName?.trim()) {
      return `已指定${pendingForceRoute.value.agentName.trim()}`
    }
    if (routeInvalid.value) {
      return '线路已失效'
    }
    const name = (routes.value?.currentRouteName || '').trim()
    if (name) {
      return `已指定${name}`
    }
    return '自动匹配专家'
  })

  /** 是否允许打开 @ 面板：就绪、不在发送中、有候选 */
  const canPickRoute = computed(
    () =>
      ready.value &&
      !routeSwitching.value &&
      !sending.value &&
      !resuming.value &&
      atRouteCandidates.value.length > 0
  )

  /** @deprecated 顶栏已收掉；保留给旧调用方时恒为 false */
  const showRouteChip = computed(() => false)

  /** 已落库会话上的即时换线（@ 选定后立刻 PUT） */
  const canSwitchRoute = computed(
    () =>
      ready.value &&
      !routeSwitching.value &&
      !sending.value &&
      !resuming.value &&
      routeCandidates.value.length > 0 &&
      !!session.value &&
      !isTempSession(session.value)
  )

  /**
   * 读当前会话的可选线路。
   *
   * @param id 会话主键；不传则取当前会话（草稿直接清空）
   */
  async function loadRoutes(id?: number) {
    const target = id ?? (session.value && !isTempSession(session.value) ? session.value.id : undefined)
    if (target == null) {
      routesSessionId = undefined
      routes.value = undefined
      return
    }
    const token = ++routesToken
    routesSessionId = target
    try {
      const { data } = await getWorkbenchRoutes(target)
      // 令牌 + 会话双校验：连点两个会话时迟到的响应不能盖掉当前会话的线路
      if (token !== routesToken || routesSessionId !== target) {
        return
      }
      routes.value = data.data
    } catch {
      // 线路是辅助信息：取不到只隐藏 chip，不影响主对话
      if (token === routesToken && routesSessionId === target) {
        routes.value = undefined
      }
    }
  }

  /**
   * 对已落库会话执行换线 PUT（与顶栏下拉同语义）。
   *
   * @param sessionId 会话主键
   * @param routeKey  目标 route_key
   * @param target    候选展示信息（用于本地立刻更新）
   * @return 是否成功
   */
  async function applyRouteSwitch(
    sessionId: number,
    routeKey: string,
    target: WorkbenchRouteOption
  ): Promise<boolean> {
    routeSwitching.value = true
    try {
      const { data } = await switchWorkbenchRoute(sessionId, routeKey)
      if (data.data) {
        session.value = mergeSessionMeta(data.data, session.value)
        rememberKnown(session.value)
      }
      routes.value = {
        currentRouteKey: routeKey,
        currentRouteName: target.agentName || '',
        candidates: routes.value?.candidates?.length ? routes.value.candidates : atRouteCandidates.value
      }
      return true
    } catch (error) {
      // 400：目标已停用等 —— 不改 sticky，重读对齐
      const text = messageOf(error, '换线失败')
      ElMessage.error(text)
      void loadRoutes(sessionId)
      return false
    } finally {
      routeSwitching.value = false
    }
  }

  /**
   * 手动换线（只作用于工作台）。已落库会话立即 PUT；成功后写 sticky + 一次性 force。
   *
   * @param routeKey 目标候选的 route_key
   */
  async function switchRoute(routeKey: string) {
    const current = session.value
    if (!current || isTempSession(current) || routeSwitching.value) {
      return
    }
    const target =
      routeCandidates.value.find((item) => item.routeKey === routeKey) ||
      atRouteCandidates.value.find((item) => item.routeKey === routeKey)
    if (!target) {
      ElMessage.warning('该线路已不在可选范围内，请刷新后重试')
      return
    }
    await applyRouteSwitch(current.id, routeKey, target)
  }

  /**
   * 输入框 @ 选定线路。
   * <ul>
   *   <li>已落库：立刻 PUT 换线，提及片保留到发送后清除；</li>
   *   <li>草稿：只记 pending，首条发出时再 create→PUT→发消息，不建空会话。</li>
   * </ul>
   * 成功不弹 Toast（提及片 / 只读状态已反馈）；失败仍提示。
   *
   * @param routeKey 目标 route_key
   */
  async function pickRoute(routeKey: string) {
    const target = atRouteCandidates.value.find((item) => item.routeKey === routeKey)
    if (!target) {
      ElMessage.warning('该线路已不在可选范围内，请刷新后重试')
      return
    }
    // 草稿：只记片；已落库：先记片再 PUT，失败则撤回片（不改 sticky）
    pendingForceRoute.value = target
    if (session.value && !isTempSession(session.value)) {
      const current = session.value
      const ok = await applyRouteSwitch(current.id, routeKey, target)
      if (!ok) {
        pendingForceRoute.value = null
      }
    }
  }

  /** 清除 @ 提及片（发送成功后 / 用户手动关掉） */
  function clearPendingRoute() {
    pendingForceRoute.value = null
  }

  /** 读就绪状态；失败时保持未就绪（宁可不给发，也不假装可用）。 */
  async function loadConfig() {
    configLoading.value = true
    try {
      const { data } = await getWorkbenchConfig()
      config.value = data.data
    } catch (error) {
      // 403 与「配置读不到」要分开说：前者是账号权限问题，重试也不会变好
      const status = (error as { response?: { status?: number } })?.response?.status
      config.value = {
        ready: false,
        reason:
          status === 403
            ? '当前账号没有对话权限，请联系管理员开通'
            : '无法读取默认接待配置，请稍后重试'
      }
    } finally {
      configLoading.value = false
    }
  }

  /**
   * 首屏：先取就绪状态，再拉历史并恢复到上次会话（没有历史则直接给草稿）。
   * 未就绪时不建草稿：输入禁用，但历史照常列出可点开查看。
   */
  async function init() {
    await loadConfig()
    await loadHistory(true)
  }

  /**
   * 首屏 / 重新加载第 1 页。
   *
   * @param selectLatest 首次挂载时恢复上次会话或最近一条；无历史则直接出草稿
   */
  async function loadHistory(selectLatest = false) {
    const token = ++historyToken
    historyLoading.value = true
    historyLoadingMore.value = false
    try {
      const { data } = await pageWorkbenchSessions({ current: 1, size: PAGE_SIZE })
      if (token !== historyToken) {
        return
      }
      const page = data.data
      const records = page?.records || []
      history.value = records
      historyCurrent.value = 1
      historyTotal.value = page?.total ?? records.length
      historyLoadedRows.value = records.length
      for (const item of records) {
        rememberKnown(item)
      }
      // 服务端结果同步当前行的元数据，但不冲掉本轮已按事件补齐的值
      const currentId = session.value?.id
      if (currentId != null) {
        const fresh = records.find((item) => item.id === currentId)
        if (fresh) {
          session.value = mergeSessionMeta(fresh, session.value)
        }
      }
      if (selectLatest && !session.value && !draftReady.value) {
        const resumedId = recallTrialSession(WORKBENCH_OWNER, 'workbench')
        const remembered = resumedId != null ? records.find((item) => item.id === resumedId) : undefined
        if (resumedId != null && !remembered) {
          forgetTrialSession(resumedId)
        }
        // 上次会话仍在列表就回去；否则落到最近一条；都没有就直接出草稿，无需先选 Agent
        if (remembered) {
          await openHistory(remembered.id)
        } else if (records.length) {
          await openHistory(records[0].id)
        } else {
          startDraft()
        }
      }
    } catch {
      if (token === historyToken) {
        history.value = []
        historyTotal.value = 0
        historyLoadedRows.value = 0
        historyCurrent.value = 1
        // 列表失败不影响「打开就能说」：就绪时直接给草稿，发送失败另有提示
        if (ready.value && !session.value && !draftReady.value) {
          startDraft()
        }
      }
    } finally {
      if (token === historyToken) {
        historyLoading.value = false
      }
    }
  }

  /** 追加下一页：只追加、不替换，不重置选中会话。 */
  async function loadMoreHistory() {
    if (historyLoading.value || historyLoadingMore.value || !historyHasMore.value) {
      return
    }
    const token = historyToken
    const next = historyCurrent.value + 1
    historyLoadingMore.value = true
    try {
      const { data } = await pageWorkbenchSessions({ current: next, size: PAGE_SIZE })
      if (token !== historyToken) {
        return
      }
      const page = data.data
      const records = page?.records || []
      historyTotal.value = page?.total ?? historyTotal.value
      historyCurrent.value = next
      historyLoadedRows.value += records.length
      // 空页但仍声称有下一页：把已加载量对齐 total，避免触底无限重试
      if (!records.length) {
        historyLoadedRows.value = historyTotal.value
      }
      const have = new Set(history.value.map((row) => row.id))
      const appended: TrialSession[] = []
      for (const item of records) {
        if (have.has(item.id) || isTempSession(item)) {
          continue
        }
        have.add(item.id)
        appended.push(item)
        rememberKnown(item)
      }
      if (appended.length) {
        history.value = [...history.value, ...appended]
      }
    } catch {
      // 追加失败保留已加载内容，用户可再次触底重试
    } finally {
      if (token === historyToken) {
        historyLoadingMore.value = false
      }
    }
  }

  /**
   * 只刷新第 1 页并按 id 合并，不重置已加载的后续页。
   * 用途：首轮标题落库晚于 SSE 结束，按列表补拉一次兜底。
   */
  async function refreshHistoryHead() {
    const token = historyToken
    try {
      const { data } = await pageWorkbenchSessions({ current: 1, size: PAGE_SIZE })
      if (token !== historyToken) {
        return
      }
      const page = data.data
      const records = page?.records || []
      historyTotal.value = page?.total ?? historyTotal.value
      const fresh = new Map(records.map((item) => [item.id, item]))
      const merged = history.value.map((row) => {
        const hit = fresh.get(row.id)
        return hit ? mergeSessionMeta(hit, row) : row
      })
      const have = new Set(merged.map((row) => row.id))
      const extra = records.filter((item) => !have.has(item.id) && !isTempSession(item))
      if (extra.length) {
        history.value = [...extra, ...merged]
        for (const item of extra) {
          rememberKnown(item)
        }
      } else {
        history.value = merged
      }
    } catch {
      // 标题补拉是锦上添花，失败静默
    }
  }

  /** 新会话：只清右栏 + 左栏顶插临时条；不调创建接口、不落库。 */
  async function newSession() {
    if (!ready.value || sending.value || resuming.value) {
      return
    }
    pageError.value = ''
    await discardUnaskedCurrent()
    startDraft()
  }

  /** 把 right panel 切成一张仅前端的草稿。 */
  function startDraft() {
    if (!ready.value) {
      return
    }
    stopResume()
    session.value = sessionStub(TEMP_SESSION_ID, Date.now())
    turns.value = []
    childPending.value = null
    draftReady.value = true
    pendingForceRoute.value = null
    // 草稿没有主键：清掉上一条会话的候选，避免 @ 列表张冠李戴；草稿候选改走 config
    routesToken += 1
    routesSessionId = undefined
    routes.value = undefined
  }

  /**
   * 点左栏会话：载入事件时间线，之后发送仍打这个会话。
   *
   * <p>三种来源按优先级：本页仍在消费的流 → 刷新前快照 → 已落库事件。
   *
   * @param id 会话主键；{@link TEMP_SESSION_ID} 表示草稿
   */
  async function openHistory(id: number) {
    pageError.value = ''
    pendingForceRoute.value = null
    if (id === TEMP_SESSION_ID) {
      if (!isTempSession(session.value)) {
        startDraft()
      }
      return
    }
    stopResume()
    draftReady.value = false
    const token = ++openToken
    const live = liveOf(id)
    const found =
      history.value.find((item) => item.id === id) ??
      knownSessions.value.get(id) ??
      (live ? sessionStub(id, live.startTime) : undefined)
    if (found) {
      session.value = mergeSessionMeta(found, session.value)
    } else if (session.value?.id !== id) {
      // 列表里还没这条（本轮刚发、尚未回写预览）：先留空，下面按事件补元数据
      session.value = undefined
    }
    rememberKnown(session.value)
    rememberTrialSession(WORKBENCH_OWNER, id, 'workbench')
    // 刷新后 SSE 已断：子计划待批只能靠专用接口恢复
    childPending.value = null
    void refreshChildPending(id)
    // 线路 chip 与事件并行取：这条只决定页头展示，不阻塞正文加载
    void loadRoutes(id)

    // 1) 本页还在消费这一轮：直接接上，禁止再用 events 拍平
    if (live && !live.finished) {
      turns.value = live.turns
      syncSessionFromTurns(id, live.turns)
      return
    }

    // 2) 刷新前留下的快照：先铺开，避免空白
    const snapshot = loadSnapshot(id)
    if (snapshot) {
      turns.value = snapshot
    }

    try {
      const fetched = await fetchResumeEvents(id)
      if (token !== openToken) {
        return
      }
      const events = fetched.events
      if (!found && (!session.value || session.value.id !== id)) {
        await attachSessionMeta(id)
        if (token !== openToken) {
          return
        }
      }
      syncSessionFromEvents(events)
      // 有内存增量 = 本轮确实在跑且能看到过程：直接进续订。
      // 会话已被收成终态时不再续：重启后的取消行要能看到已有消息，不能一直「正在作答」
      if (fetched.live && events.length && sessionStillRunning(progressStatus(id))) {
        beginResume(id, events, false, true)
        return
      }
      if (shouldResume(id, events)) {
        beginResume(id, events, !!snapshot, fetched.live)
        return
      }
      turns.value = turnsFromEvents(events)
      clearSnapshot(id)
      dropLive(id)
      await restorePendingPlan(id, token)
    } catch {
      if (token === openToken) {
        pageError.value = '会话事件加载失败'
      }
    }
  }

  /**
   * 发送一轮。草稿状态先创建会话（不传 agentId / maId），再走工作台 SSE。
   *
   * @param content 用户输入
   * @param images  图片 data URI 数组，可空
   */
  async function send(content: string, images?: string[]) {
    if (sending.value || !canSend.value) {
      return
    }
    sending.value = true
    pageError.value = ''
    // 一次点击生成一次幂等键：网络重试 / 浏览器重放同一请求只真正跑一轮
    const idempotencyKey = newTrialIdempotencyKey()
    childPending.value = null
    // 手动发问接管展示：只停轮询，本轮改由 SSE 驱动
    stopPolling()
    turns.value = [
      ...turns.value,
      { key: `wb-u-${Date.now()}`, role: 'user', text: content, blocks: [], phase: 'done', status: 'done', startTime: 0 }
    ]
    turns.value = [...turns.value, assistantTurn(`wb-a-${Date.now()}`, 'streaming')]
    const live = turns.value[turns.value.length - 1]
    const controller = new AbortController()
    let persisted = false
    let sessionId: number | undefined
    const touchSnapshot = () => {
      if (sessionId != null) {
        saveSnapshot(sessionId)
      }
    }
    try {
      sessionId = session.value && !isTempSession(session.value) ? session.value.id : undefined
      /** 草稿首条若带了 @：create 后立刻 PUT，再发消息；不把 @ 文案写入 query */
      const forceRoute = pendingForceRoute.value
      if (sessionId == null) {
        try {
          const { data } = await createWorkbenchSession()
          const created = data.data
          if (!created) {
            throw new Error('新建会话失败')
          }
          const preview = content.trim().slice(0, 80)
          session.value = {
            ...created,
            messageCount: 1,
            lastMsgPreview: (created.lastMsgPreview || '').trim() || preview
          }
          sessionId = created.id
          draftReady.value = false
          // 立刻记住：这之后刷新也要能回到本轮（此时库里还没有预览和轮次）
          rememberTrialSession(WORKBENCH_OWNER, created.id, 'workbench')
          history.value = [
            session.value,
            ...history.value.filter((row) => row.id !== created.id && !isTempSession(row))
          ]
          historyLoadedRows.value += 1
          historyTotal.value += 1
          rememberKnown(session.value)
          // 首条 @：建会话后立刻换线，再 SSE；换线失败则删掉刚建的空会话并回草稿态，保留本轮气泡错误提示
          if (forceRoute) {
            const switched = await applyRouteSwitch(created.id, forceRoute.routeKey, forceRoute)
            if (!switched) {
              try {
                await deleteWorkbenchSession(created.id)
              } catch {
                // 删除失败不挡回草稿：避免用户卡在「有 id 但未发言」的空会话
              }
              history.value = history.value.filter((row) => row.id !== created.id)
              historyLoadedRows.value = Math.max(0, historyLoadedRows.value - 1)
              historyTotal.value = Math.max(0, historyTotal.value - 1)
              session.value = sessionStub(TEMP_SESSION_ID, Date.now())
              draftReady.value = true
              pendingForceRoute.value = null
              routesToken += 1
              routesSessionId = undefined
              routes.value = undefined
              live.status = 'error'
              live.phase = 'error'
              pageError.value = '指定线路失败，请 @ 重选或直接发送由系统匹配'
              return
            }
          } else {
            void loadRoutes(created.id)
          }
        } catch (error) {
          live.status = 'error'
          live.phase = 'error'
          pageError.value = messageOf(error, '新建会话失败')
          // 新建失败常见原因是默认接待被停用：重读一次就绪状态，让横幅与输入禁用及时生效
          void loadConfig()
          return
        }
      }
      // 发送即清提及片：已落库会话在 @ 选定时已 PUT；草稿在上面 create→PUT。query 仍是纯正文
      pendingForceRoute.value = null
      // 登记到模块级：切走再回来能接上同一份时间线
      beginLive(sessionId, turns.value)
      await streamWorkbenchMessage(
        sessionId,
        content,
        {
          onPhase: (phase) => {
            live.phase = phase
          },
          onRoute: (summary) => {
            // 路由判定进思考时间线；改派/换线后的 chip 在整轮收尾时由 loadRoutes 对齐
            appendRoute(live, summary)
            touchSnapshot()
          },
          onReasoning: (text) => {
            appendText(live, 'reasoning', text)
            touchSnapshot()
          },
          onVision: (text) => {
            live.phase = 'vision'
            appendVision(live, text)
            touchSnapshot()
          },
          onTool: (tool) => {
            live.phase = 'tool'
            appendTool(live, tool.line, tool.result)
            touchSnapshot()
          },
          onPlan: (plan) => {
            upsertPlan(live, {
              planId: plan.planId,
              state: plan.state,
              summary: plan.summary,
              steps: (plan.steps || []).map((step) => ({
                seq: step.seq,
                title: step.title,
                detail: step.detail || ''
              }))
            })
            touchSnapshot()
          },
          onStep: (step) => {
            live.phase = 'tool'
            upsertStep(live, {
              seq: step.seq,
              title: step.title,
              state: step.state,
              text: step.text || '',
              line: step.line
            })
            touchSnapshot()
          },
          onChildPlanPending: (pending) => {
            // 子专家计划待批准：后端单批准槽保证同时只有一条
            childPending.value = pending
          },
          onFileGenerated: (file) => {
            appendFile(live, {
              fileName: file.fileName,
              size: file.size,
              downloadUrl: file.downloadUrl
            })
            touchSnapshot()
          },
          onAssistant: (text) => {
            if (live.phase !== 'done') {
              live.phase = 'answering'
            }
            appendText(live, 'assistant', text)
            touchSnapshot()
          },
          onDone: (payload) => {
            persisted = true
            live.status = payload.status === 'FAILED' ? 'error' : 'done'
            live.phase = payload.status === 'FAILED' ? 'error' : 'done'
            if (payload.planPending) {
              live.planPending = true
              live.status = 'done'
              live.phase = 'done'
            }
            // 根本轮已出终答（或整轮失败）：子计划不可能还在待批
            childPending.value = null
          },
          onError: (message) => {
            live.status = 'error'
            live.phase = 'error'
            // SSE 硬核不可用也走友好映射，避免红条打出「没有可用的对话模型」原文
            pageError.value = friendlyWorkbenchError(message || '')
            childPending.value = null
          }
        },
        controller.signal,
        images,
        idempotencyKey
      )
      // 落库后再用事件覆盖，避免把未入库的失败卡片刷掉
      if (persisted && session.value && session.value.id === sessionId) {
        const asked = session.value.messageCount ?? 0
        session.value = {
          ...session.value,
          messageCount: asked > 0 ? asked : 1,
          lastMsgPreview: (session.value.lastMsgPreview || '').trim() || content
        }
      }
      if (persisted) {
        finishLive(sessionId)
        if (session.value?.id === sessionId) {
          await openHistory(sessionId)
        } else {
          dropLive(sessionId)
        }
        // 首轮标题由后端在本次请求内同步写库：先单查补进左栏
        if ((session.value?.messageCount ?? 0) <= 1) {
          await refreshSessionTitle(sessionId)
          scheduleTitleRefresh(sessionId)
        }
      } else if (live.status === 'streaming') {
        live.status = 'error'
        live.phase = 'error'
        if (!pageError.value) {
          pageError.value = '连接已结束'
        }
      }
      await refreshHistoryHead()
    } catch (error) {
      live.status = 'error'
      live.phase = 'error'
      pageError.value = messageOf(error, '发送失败')
    } finally {
      if (sessionId != null) {
        finishLive(sessionId)
      }
      sending.value = false
    }
  }

  /** 清空当前会话事件，保留会话与标题。 */
  async function clearCurrent() {
    const current = session.value
    if (!current || isTempSession(current) || sending.value) {
      return
    }
    try {
      await ElMessageBox.confirm('清空当前会话的对话记录？会话会保留，可以继续发送。', '清空当前', {
        type: 'warning'
      })
    } catch {
      return
    }
    try {
      const { data } = await clearWorkbenchSession(current.id)
      session.value = data.data
      turns.value = []
      pageError.value = ''
      childPending.value = null
      pendingForceRoute.value = null
      // 清空不影响沿用线路，但一次手动换线的强制标记会被后端清掉：重读以对齐
      void loadRoutes(current.id)
      await refreshHistoryHead()
      ElMessage.success('已清空当前会话')
    } catch {
      pageError.value = '清空失败'
    }
  }

  /** 左栏三点菜单：重命名 / 删除。 */
  function onSessionCommand(command: string, item: TrialSession) {
    if (command === 'rename') {
      void renameSession(item)
      return
    }
    if (command === 'remove') {
      void removeSession(item)
    }
  }

  /** 重命名会话。取消输入不提示；接口失败才写错误。 */
  async function renameSession(item: TrialSession) {
    if (sending.value) {
      return
    }
    let title = ''
    try {
      const answered = await ElMessageBox.prompt('请输入会话名称', '重命名', {
        inputValue: (item.sessionTitle || '').trim(),
        inputPattern: /\S+/,
        inputErrorMessage: '名称不能为空'
      })
      title = answered.value.trim()
    } catch {
      return
    }
    try {
      const { data } = await renameWorkbenchSession(item.id, title)
      if (data.data) {
        const index = history.value.findIndex((row) => row.id === item.id)
        if (index >= 0) {
          history.value[index] = data.data
        }
        if (session.value?.id === item.id) {
          session.value = data.data
        }
      }
    } catch {
      pageError.value = '重命名失败'
    }
  }

  /** 逻辑删除；删的是当前条时清空右栏。 */
  async function removeSession(item: TrialSession) {
    if (sending.value) {
      return
    }
    try {
      await ElMessageBox.confirm('删除后无法恢复，确定删除该会话？', '删除会话', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      })
    } catch {
      return
    }
    try {
      await deleteWorkbenchSession(item.id)
      forgetTrialSession(item.id)
      history.value = history.value.filter((row) => row.id !== item.id)
      historyLoadedRows.value = Math.max(0, historyLoadedRows.value - 1)
      historyTotal.value = Math.max(0, historyTotal.value - 1)
      if (session.value?.id === item.id) {
        resetConversation()
        if (ready.value) {
          startDraft()
        }
      }
      await refreshHistoryHead()
    } catch {
      pageError.value = '删除失败'
    }
  }

  /**
   * 批准待执行计划。批准接口要跑完整轮才返回，期间先本地切到「执行中」。
   *
   * @param planId 该轮计划标识；用于本地定位
   */
  async function approvePlan(planId?: string) {
    const current = session.value
    if (!current || isTempSession(current) || approving.value) {
      return
    }
    approving.value = true
    pageError.value = ''
    const id = current.id
    markTurnRunning(id, true)
    beginApprovedRun(planId)
    try {
      await approveWorkbenchPlan(id)
      clearPendingPlanFlag(planId)
      dropLive(id)
      finishLive(id)
      await openHistory(id)
      await refreshHistoryHead()
    } catch (error) {
      pageError.value = messageOf(error, '批准失败')
      endApprovedRun(planId)
      await openHistory(id)
    } finally {
      approving.value = false
      finishLive(id)
    }
  }

  /**
   * 取消待执行计划：后端只落一条「计划已取消」，不执行任何步骤。
   *
   * @param planId 该轮计划标识
   */
  async function rejectPlan(planId?: string) {
    const current = session.value
    if (!current || isTempSession(current) || approving.value) {
      return
    }
    try {
      await ElMessageBox.confirm('取消后不会执行任何步骤，确定取消这份计划？', '取消计划', {
        type: 'warning',
        confirmButtonText: '取消计划',
        cancelButtonText: '返回'
      })
    } catch {
      return
    }
    const id = current.id
    approving.value = true
    pageError.value = ''
    try {
      await rejectWorkbenchPlan(id)
      advancePlanStateFor(planId, 'REJECTED')
      clearPendingPlanFlag(planId)
      dropLive(id)
      finishLive(id)
      await openHistory(id)
      await refreshHistoryHead()
    } catch (error) {
      pageError.value = messageOf(error, '取消失败')
    } finally {
      approving.value = false
    }
  }

  /** 批准 / 拒绝编排根下的子专家计划。 */
  async function decideChildPlan(approved: boolean) {
    const target = childPending.value
    const id = session.value?.id
    if (!target || id == null || childDeciding.value) {
      return
    }
    childDeciding.value = true
    pageError.value = ''
    try {
      if (approved) {
        await approveWorkbenchPlan(id, target.routeKey)
      } else {
        await rejectWorkbenchPlan(id, target.routeKey)
      }
      childPending.value = null
      if (!approved) {
        ElMessage.info('已拒绝该子计划，该子会记为失败，其它子继续')
      }
    } catch (error) {
      pageError.value = messageOf(error, approved ? '批准子计划失败' : '拒绝子计划失败')
      await refreshChildPending(id)
    } finally {
      childDeciding.value = false
    }
  }

  /** 读待批准计划并写回时间线；失败不影响已渲染内容。 */
  async function restorePendingPlan(id: number, token: number) {
    let pending: TrialPlanView | null = null
    try {
      const { data } = await getPendingWorkbenchPlan(id)
      pending = data.data || null
    } catch {
      pending = null
    }
    if (token !== openToken) {
      return
    }
    if (session.value?.id !== id) {
      return
    }
    for (const turn of turns.value) {
      if (turn.role !== 'assistant') {
        continue
      }
      turn.planPending = !!pending && turn.planId === pending.planId
    }
  }

  /** 刷新「子计划待批准」卡片；响应回来时已切会话则丢弃。 */
  async function refreshChildPending(id: number) {
    let pending: TrialChildPlanPendingChunk | null = null
    try {
      const { data } = await getPendingWorkbenchChildPlan(id)
      pending = data.data || null
    } catch {
      pending = null
    }
    if (session.value?.id !== id) {
      return
    }
    childPending.value = pending
  }

  /** 首轮结束后按会话单查补标题；成功返回 true。 */
  async function refreshSessionTitle(id: number) {
    try {
      const { data } = await getWorkbenchSession(id)
      const fresh = data.data
      const freshTitle = (fresh?.sessionTitle || '').trim()
      if (!freshTitle) {
        return false
      }
      const row = history.value.find((item) => item.id === id)
      if (row) {
        row.sessionTitle = freshTitle
        row.titleSource = fresh?.titleSource
      }
      if (session.value?.id === id) {
        session.value = { ...session.value, sessionTitle: freshTitle, titleSource: fresh?.titleSource }
      }
      return true
    } catch {
      return false
    }
  }

  /** 首轮后按两次延迟补拉列表标题；已有标题/已切换会话即停。 */
  function scheduleTitleRefresh(id: number) {
    stopTitleRefresh()
    const row = history.value.find((item) => item.id === id)
    if (row && (row.sessionTitle || '').trim()) {
      return
    }
    const delays = [...TITLE_REFRESH_DELAYS]
    const tick = () => {
      titleTimer = window.setTimeout(async () => {
        await refreshHistoryHead()
        const fresh = history.value.find((item) => item.id === id)
        if (!fresh || (fresh.sessionTitle || '').trim()) {
          return
        }
        if (delays.length) {
          tick()
        }
      }, delays.shift() ?? 0)
    }
    tick()
  }

  /** 停掉标题补拉。 */
  function stopTitleRefresh() {
    if (titleTimer) {
      window.clearTimeout(titleTimer)
      titleTimer = undefined
    }
  }

  /** 读这一轮的最新内容：内存快照优先，落库事件补历史。 */
  async function fetchResumeEvents(id: number): Promise<{ live: boolean; events: TrialEvent[] }> {
    const [persisted, liveResp] = await Promise.all([listWorkbenchEvents(id), fetchLiveSafe(id)])
    const stored = persisted.data.data || []
    const live = liveResp
    if (live.length) {
      return { live: true, events: mergeBySeq(stored, live) }
    }
    return { live: false, events: stored }
  }

  /** 读内存增量；失败按空处理（老后端 / 多实例读不到都算正常）。 */
  async function fetchLiveSafe(id: number): Promise<TrialEvent[]> {
    try {
      const { data } = await listWorkbenchLiveEvents(id)
      return data.data || []
    } catch {
      return []
    }
  }

  /** 按 seq 合并两份事件，后者覆盖同序号旧值。 */
  function mergeBySeq(stored: TrialEvent[], live: TrialEvent[]): TrialEvent[] {
    const bySeq = new Map<number, TrialEvent>()
    for (const item of stored) {
      bySeq.set(item.seq ?? 0, item)
    }
    for (const item of live) {
      bySeq.set(item.seq ?? 0, item)
    }
    return [...bySeq.values()].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  }

  /**
   * 续订判定用的状态。列表行是服务端权威；当前会话其次；本地补行最后。
   * 这样启动收口写成取消之后，不会被内存里的旧 RUNNING 壳盖回去。
   */
  function progressStatus(id: number): string | undefined {
    const listed = history.value.find((item) => item.id === id)
    if (listed?.status) {
      return listed.status
    }
    if (session.value?.id === id && session.value.status) {
      return session.value.status
    }
    return knownSessions.value.get(id)?.status
  }

  /** 是否需要续订：终态不续；其余仍是末尾未收尾且不过旧，或「刚建会话、事件还没落库」。 */
  function shouldResume(id: number, events: TrialEvent[]) {
    const current = session.value
    const same = !!current && current.id === id
    return shouldResumeTurn({
      status: progressStatus(id),
      open: hasOpenTurn(events),
      stale: openTurnStale(events, RESUME_FRESH_MS),
      hasEvents: events.length > 0,
      pendingMs: PENDING_FIRST_EVENT_MS,
      sessionStamp: same ? parseSessionTime(current?.startTime || current?.updateTime) : 0,
      sameSession: same
    })
  }

  /**
   * 重进续订：本页没有 SSE 时靠短轮询把这一轮「续」出来。
   *
   * @param id          会话主键
   * @param first       刚拉到的事件
   * @param keepCurrent true 表示右栏已有更完整内容（刷新前快照），首拍不要拍平
   * @param liveMode    true 表示 first 含内存增量，本轮仍在跑
   */
  function beginResume(id: number, first: TrialEvent[], keepCurrent = false, liveMode = false) {
    const token = ++pollToken
    const startTime = Date.now()
    resumingId = id
    markTurnRunning(id, true)
    detached.value = !liveMode && !hasLiveStream(id)
    resumeSignature = keepCurrent ? signOf(first) : ''
    if (!keepCurrent) {
      applyResumeEvents(id, first, liveMode)
    }
    scheduleResume(id, token, startTime)
  }

  /** 排下一次续订轮询；令牌失配直接退出。 */
  function scheduleResume(id: number, token: number, startTime: number) {
    pollTimer = window.setTimeout(async () => {
      if (token !== pollToken) {
        return
      }
      if (Date.now() - startTime > RESUME_MAX_MS) {
        finishResume()
        pageError.value = '本轮等待超时，请稍后重新打开会话查看结果'
        return
      }
      try {
        const { live, events } = await fetchResumeEvents(id)
        if (token !== pollToken) {
          return
        }
        if (live) {
          detached.value = false
          applyResumeEvents(id, events, true)
          scheduleResume(id, token, startTime)
          return
        }
        detached.value = true
        applyResumeEvents(id, events)
        if (!events.length && Date.now() - startTime > RESUME_EMPTY_MS) {
          finishResume()
          return
        }
        if (events.length && !hasOpenTurn(events)) {
          clearSnapshot(id)
          dropLive(id)
          finishResume()
          await refreshHistoryHead()
          return
        }
        scheduleResume(id, token, startTime)
      } catch {
        // 单次失败不放弃，下一拍继续；连续失败由超时兜底
        scheduleResume(id, token, startTime)
      }
    }, RESUME_POLL_MS)
  }

  /** 把轮询到的全量事件重放成时间线。 */
  function applyResumeEvents(id: number, events: TrialEvent[], live = false) {
    const signature = signOf(events)
    if (signature === resumeSignature) {
      return
    }
    resumeSignature = signature
    if (!events.length) {
      const placeholder = assistantTurn('wb-live-tail', 'streaming')
      placeholder.phase = 'planning'
      turns.value = [placeholder]
      bindLiveTurns(id, turns.value)
      return
    }
    turns.value = turnsFromEvents(events)
    if (live || hasOpenTurn(events)) {
      turns.value = markLiveTail(turns.value, events)
    }
    bindLiveTurns(id, turns.value)
    syncSessionFromEvents(events)
  }

  /** 事件指纹：条数 + 末尾 seq + 末尾正文长度（同一条事件会续写）。 */
  function signOf(events: TrialEvent[]) {
    if (!events.length) {
      return '0'
    }
    const last = events[events.length - 1]
    const text = last.contentText || last.summary || ''
    return `${events.length}:${last.seq ?? 0}:${text.length}`
  }

  /** 用本页时间线补当前会话的轮次与预览（服务端要整轮结束才回写）。 */
  function syncSessionFromTurns(id: number, list: ChatTurn[]) {
    const current = session.value
    if (!current || current.id !== id || isTempSession(current)) {
      return
    }
    const asked = list.filter((turn) => turn.role === 'user').length
    const firstUser = list.find((turn) => turn.role === 'user')
    const text = (firstUser?.text || '').trim()
    const hasPreview = (current.lastMsgPreview || '').trim().length > 0
    const count = current.messageCount ?? 0
    if (asked <= count && (hasPreview || !text)) {
      return
    }
    session.value = {
      ...current,
      messageCount: Math.max(count, asked),
      lastMsgPreview: hasPreview ? current.lastMsgPreview : text.slice(0, 80)
    }
    patchKnown(id, {
      messageCount: session.value.messageCount,
      lastMsgPreview: session.value.lastMsgPreview
    })
  }

  /** 用已落库事件补当前会话的轮次与预览。 */
  function syncSessionFromEvents(events: TrialEvent[]) {
    const current = session.value
    if (!current || isTempSession(current)) {
      return
    }
    const asked = events.filter((item) => item.eventType === 'user').length
    const firstUser = events.find((item) => item.eventType === 'user')
    const text = (firstUser?.contentText || firstUser?.summary || '').trim()
    const hasPreview = (current.lastMsgPreview || '').trim().length > 0
    const count = current.messageCount ?? 0
    if (asked <= count && (hasPreview || !text)) {
      return
    }
    session.value = {
      ...current,
      messageCount: Math.max(count, asked),
      lastMsgPreview: hasPreview ? current.lastMsgPreview : text.slice(0, 80)
    }
    patchKnown(current.id, {
      messageCount: session.value.messageCount,
      lastMsgPreview: session.value.lastMsgPreview
    })
  }

  /** 服务端行与本地已补齐的值合并：空标题不覆盖本地已显示的那条。 */
  function mergeSessionMeta(fresh: TrialSession, local?: TrialSession): TrialSession {
    if (!local || local.id !== fresh.id) {
      return fresh
    }
    return {
      ...fresh,
      messageCount: Math.max(fresh.messageCount ?? 0, local.messageCount ?? 0),
      lastMsgPreview: (fresh.lastMsgPreview || '').trim() || local.lastMsgPreview,
      sessionTitle: (fresh.sessionTitle || '').trim() || local.sessionTitle,
      titleSource: (fresh.sessionTitle || '').trim() ? fresh.titleSource : local.titleSource
    }
  }

  /** 记下本地会话，供列表暂时看不到时补行。 */
  function rememberKnown(item?: TrialSession | null) {
    if (!item || isTempSession(item) || item.id < 0) {
      return
    }
    const next = new Map(knownSessions.value)
    next.set(item.id, item)
    knownSessions.value = next
  }

  /** 更新本地会话的展示元数据。 */
  function patchKnown(id: number, patch: Partial<TrialSession>) {
    const existing = knownSessions.value.get(id)
    if (!existing) {
      return
    }
    const next = new Map(knownSessions.value)
    next.set(id, {
      ...existing,
      ...patch,
      lastMsgPreview: (patch.lastMsgPreview || '').trim() || existing.lastMsgPreview
    })
    knownSessions.value = next
  }

  /** 会话元数据兜底：列表还没回写时用它补齐状态与标题。 */
  async function attachSessionMeta(id: number) {
    try {
      const { data } = await getWorkbenchSession(id)
      if (data.data) {
        session.value = data.data
        rememberKnown(session.value)
      }
    } catch {
      // 取不到元数据不影响展示已落库的事件
    }
  }

  /**
   * 丢掉当前未提问的落库空壳（创建成功但首轮流式失败可能留下）。
   * 本地已有用户气泡、服务端已有轮次/预览、或本轮仍在跑 → 一律不删。
   */
  async function discardUnaskedCurrent() {
    const current = session.value
    if (!current || isTempSession(current)) {
      return
    }
    if (isTurnRunning(current.id) || resuming.value) {
      return
    }
    if (turns.value.some((turn) => turn.role === 'user')) {
      return
    }
    if ((current.messageCount ?? 0) > 0 || (current.lastMsgPreview || '').trim() || (current.sessionTitle || '').trim()) {
      return
    }
    try {
      await deleteWorkbenchSession(current.id)
    } catch {
      return
    }
    forgetTrialSession(current.id)
    clearSnapshot(current.id)
    dropLive(current.id)
    history.value = history.value.filter((row) => row.id !== current.id)
    historyLoadedRows.value = Math.max(0, historyLoadedRows.value - 1)
    historyTotal.value = Math.max(0, historyTotal.value - 1)
    const nextKnown = new Map(knownSessions.value)
    nextKnown.delete(current.id)
    knownSessions.value = nextKnown
  }

  /** 结束续订：停轮询并摘转圈；内容保留。 */
  function finishResume() {
    const id = resumingId
    stopPolling()
    if (id != null) {
      finishLive(id)
    }
    detached.value = false
  }

  /** 只停轮询，不动左栏转圈（发送接管时用）。 */
  function stopPolling() {
    if (pollTimer) {
      window.clearTimeout(pollTimer)
      pollTimer = undefined
    }
    pollToken += 1
    resumingId = undefined
    resumeSignature = ''
  }

  /** 主动中断续订（打开别的会话 / 切草稿 / 卸载）。 */
  function stopResume() {
    stopPolling()
    detached.value = false
  }

  /** 清空右栏，不触碰左栏。 */
  function resetConversation() {
    stopResume()
    stopTitleRefresh()
    session.value = undefined
    turns.value = []
    pageError.value = ''
    draftReady.value = false
    childPending.value = null
  }

  /** 造一个只用于左栏展示的会话壳（草稿 / 列表暂时看不到的进行中会话）。 */
  function sessionStub(id: number, startTimeMs: number): TrialSession {
    const stamp = formatLocalStamp(new Date(startTimeMs || Date.now()))
    return {
      id,
      agentId: null,
      runMode: 'ROUTER',
      userId: 0,
      status: 'RUNNING',
      messageCount: 0,
      sessionTitle: '',
      lastMsgPreview: '',
      startTime: stamp,
      updateTime: stamp
    }
  }

  /** 是否前端临时草稿（负 id，未落库）。 */
  function isTempSession(item?: TrialSession | null) {
    return !!item && item.id === TEMP_SESSION_ID
  }

  /**
   * 该行是否显示「执行中」转圈。
   * 正向信号仍是本页 running 集合（多轮 RUNNING 不能让历史行全转）；
   * 终态再挡一层，重启收口后的取消会话不再转。
   */
  function isRowRunning(item: TrialSession) {
    return rowShowsSpinner(item.status, isTurnRunning(item.id), isTempSession(item))
  }

  /** 本地时间戳，与列表相对时间解析兼容。 */
  function formatLocalStamp(date: Date) {
    const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`)
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  /** 左栏主标题：只认 session_title，空则「新对话」。 */
  function sessionTitle(item: TrialSession) {
    const value = (item.sessionTitle || '').trim()
    return value || '新对话'
  }

  /** 左栏副标题：预览 + 相对时间。 */
  function sessionSubtitle(item: TrialSession) {
    const preview = (item.lastMsgPreview || '').trim()
    const time = formatRelativeTime(item.updateTime || item.startTime)
    return preview ? `${preview} · ${time}` : time
  }

  /** 行悬停提示：标题 + 完整预览。 */
  function sessionTooltip(item: TrialSession) {
    const preview = (item.lastMsgPreview || '').trim()
    const title = sessionTitle(item)
    return preview ? `${title}\n${preview}` : title
  }

  /** 定位计划所属的助手轮次（优先 planId，缺失时退到最后一个带计划块的轮）。 */
  function findPlanTurn(planId?: string) {
    for (let i = turns.value.length - 1; i >= 0; i -= 1) {
      const turn = turns.value[i]
      if (turn.role !== 'assistant') {
        continue
      }
      if (!planId) {
        if (turn.blocks.some((block) => block.kind === 'plan')) {
          return turn
        }
        continue
      }
      if (turn.planId === planId) {
        return turn
      }
    }
    return undefined
  }

  /** 批准发出后立刻把本地时间线推成「执行中」。 */
  function beginApprovedRun(planId?: string) {
    const turn = findPlanTurn(planId)
    if (!turn) {
      return
    }
    advancePlanState(turn, planId, 'EXECUTING')
    turn.status = 'streaming'
    turn.phase = 'tool'
    turn.startTime = Date.now()
  }

  /** 批准失败时收回本地进行态。 */
  function endApprovedRun(planId?: string) {
    const turn = findPlanTurn(planId)
    if (!turn) {
      return
    }
    turn.status = 'done'
    turn.phase = 'done'
  }

  /** 只改计划卡状态，不碰进行态。 */
  function advancePlanStateFor(planId: string | undefined, state: string) {
    const turn = findPlanTurn(planId)
    if (turn) {
      advancePlanState(turn, planId, state)
    }
  }

  /** 清掉本地「待批准」标记，按钮必须立即消失。 */
  function clearPendingPlanFlag(planId?: string) {
    for (const turn of turns.value) {
      if (turn.planPending && (!planId || turn.planId === planId)) {
        turn.planPending = false
      }
    }
  }

  /**
   * 统一收错误文案；非 Error 时用兜底。
   * 硬核不可用（如无对话模型）再走工作台友好映射，不改后端错误码。
   */
  function messageOf(error: unknown, fallback: string) {
    const raw = error instanceof Error && error.message ? error.message : fallback
    return friendlyWorkbenchError(raw)
  }

  onBeforeUnmount(() => {
    stopResume()
    stopTitleRefresh()
    // 作废在途列表请求：令牌一变，迟到的响应不再回写已卸载的列表
    historyToken += 1
  })

  return {
    config,
    configLoading,
    ready,
    reason,
    schemeName,
    session,
    turns,
    sending,
    resuming,
    detached,
    childPending,
    childDeciding,
    approving,
    pageError,
    canSend,
    blockedHint,
    title,
    historyGroups,
    historyLoading,
    historyHasMore,
    historyLoadingMore,
    traceSessionId,
    routeName,
    currentRouteKey,
    routeInvalid,
    routeCandidates,
    atRouteCandidates,
    routeSwitching,
    pendingForceRoute,
    gatewayBadgeText,
    canPickRoute,
    showRouteChip,
    canSwitchRoute,
    init,
    newSession,
    openHistory,
    loadMoreHistory,
    send,
    switchRoute,
    pickRoute,
    clearPendingRoute,
    clearCurrent,
    onSessionCommand,
    approvePlan,
    rejectPlan,
    decideChildPlan,
    isTempSession,
    isRowRunning,
    sessionTitle,
    sessionSubtitle,
    sessionTooltip
  }
}
