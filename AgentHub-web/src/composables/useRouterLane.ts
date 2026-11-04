/**
 * Router 试跑编排卡的数据源。所属域：runtime（前端）。
 *
 * 数据主路径与旧「顶部泳道」一致：obs span 树
 * （`/api/obs/sessions/{id}/traces` → `/api/obs/traces/{traceId}/spans`），
 * 归纳成泳道模型后交给对话流内的紧凑时间线（RouterTimeline）渲染；
 * 详细甘特图由试跑面板的右侧抽屉复用同一份泳道模型。
 * 只读：不写库、不改 RouterTrialExecutor 的隔离/并行/汇总语义。
 */
import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { listObsSpans, pageObsTraces } from '@/api/obs'
import { useUserStore } from '@/stores/user'
import { buildOrchestrationLane, type OrchestrationLaneModel } from '@/utils/orchestrationLane'
import type { ObsSpan } from '@/types/obs'
import type { RouterTraceView } from '@/types/runtime'

/** 轮询间隔：与试跑续订同一量级，够看出「子开始即出现条」 */
const POLL_MS = 2000
/** 本轮结束后补一拍：trace 收尾略晚于 SSE done */
const SETTLE_MS = 900

/**
 * 按会话轮询编排追踪，产出对话流内卡片所需的数据。
 *
 * @param sessionId 试跑会话主键；临时草稿 / 非 Router 传 null，传 null 时自动清空
 * @param active    本轮是否还在跑（sending / resuming），决定是否开轮询
 * @return view 为 null 表示无权限，调用方据此整卡不渲染；reload 供「刷新」按钮手动重拉
 */
export function useRouterLane(sessionId: Ref<number | null>, active: Ref<boolean>) {
  const user = useUserStore()
  /** 两个权限都查同一条 trace 的步骤；缺一个就整卡不渲染，避免半截数据 */
  const canView = computed(() => user.hasPerm('obs:trace:list') && user.hasPerm('obs:span:list'))

  const spans = ref<ObsSpan[]>([])
  const traceId = ref('')
  const loading = ref(false)
  const error = ref('')

  /** 泳道模型：没有步骤时保持 null，卡片据此显示「还没有追踪数据」 */
  const model = computed<OrchestrationLaneModel | null>(() =>
    spans.value.length ? buildOrchestrationLane(spans.value) : null
  )

  /**
   * 卡片数据。无权限时返回 null：让调用方整卡不渲染，
   * 而不是在对话流里塞一块「没有权限」的灰块。
   */
  const view = computed<RouterTraceView | null>(() => {
    if (!canView.value) {
      return null
    }
    return {
      model: model.value,
      events: model.value?.events ?? [],
      spans: spans.value,
      traceId: traceId.value,
      sessionId: sessionId.value,
      error: error.value,
      loading: loading.value
    }
  })

  /** 请求令牌：切会话 / 关面板后丢弃迟到的响应，避免串会话 */
  let token = 0
  let timer: number | undefined
  let settleTimer: number | undefined

  /**
   * 拉最近一轮 trace 与其全部步骤。
   *
   * @param showLoading true 时显示 loading（首次 / 手动刷新）
   */
  async function load(showLoading = false) {
    const id = sessionId.value
    if (id == null || !canView.value) {
      reset()
      return
    }
    const mine = ++token
    if (showLoading) {
      loading.value = true
    }
    try {
      // 中栏接口按开始时间降序；第一条就是本轮（进行中的 trace 也会立刻出现）
      const { data } = await pageObsTraces(id, { current: 1, size: 20 })
      if (mine !== token) {
        return
      }
      const latest = data.data?.records?.[0] ?? null
      error.value = ''
      if (!latest) {
        traceId.value = ''
        spans.value = []
        return
      }
      if (latest.traceId !== traceId.value) {
        traceId.value = latest.traceId
        // 换轮次先清空，避免新 trace 只回来一半时把上一轮的行混进来
        spans.value = []
      }
      const spanRes = await listObsSpans(latest.traceId)
      if (mine !== token) {
        return
      }
      spans.value = spanRes.data.data || []
    } catch {
      if (mine === token) {
        error.value = '编排调度数据加载失败'
      }
    } finally {
      if (mine === token) {
        loading.value = false
      }
    }
  }

  /** 清空视图（切到临时草稿 / 无权限） */
  function reset() {
    token += 1
    loading.value = false
    error.value = ''
    traceId.value = ''
    spans.value = []
  }

  /** 停轮询。切会话、组件卸载、本轮结束都要调 */
  function stopTimer() {
    if (timer != null) {
      window.clearInterval(timer)
      timer = undefined
    }
    if (settleTimer != null) {
      window.clearTimeout(settleTimer)
      settleTimer = undefined
    }
  }

  /** 本轮在跑时开轮询：子一开跑就能看到条，不必等整轮结束 */
  function startPolling() {
    if (!active.value || timer != null) {
      return
    }
    timer = window.setInterval(() => void load(), POLL_MS)
  }

  // 本轮结束的那一刻补一拍，等 trace 收尾落库；再停轮询
  watch(
    () => active.value,
    (running) => {
      stopTimer()
      if (running) {
        void load()
        startPolling()
        return
      }
      void load()
      settleTimer = window.setTimeout(() => void load(), SETTLE_MS)
    }
  )

  // 切会话 / 权限装载完成：立即重拉；无会话或无权限时清空并停轮询
  watch(
    () => [sessionId.value, canView.value] as const,
    ([id, ok]) => {
      if (id == null || !ok) {
        stopTimer()
        reset()
        return
      }
      void load(true)
      startPolling()
    },
    { immediate: true }
  )

  onBeforeUnmount(stopTimer)

  return {
    view,
    canView,
    /** 手动刷新（卡片上的「刷新」按钮） */
    reload: () => load(true)
  }
}
