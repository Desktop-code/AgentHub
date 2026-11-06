/**
 * Router 试跑编排卡的数据源。所属域：runtime（前端）。
 *
 * 数据主路径与旧「顶部泳道」一致：obs span 树
 * （`/api/obs/sessions/{id}/traces` → `/api/obs/traces/{traceId}/spans`），
 * 归纳成泳道模型后交给对话流内的紧凑时间线（RouterTimeline）渲染；
 * 详细甘特图由试跑面板的右侧抽屉复用同一份泳道模型。
 * 只读：不写库、不改 RouterTrialExecutor 的隔离/并行/汇总语义。
 *
 * 轮询骨架（运行中短轮询、结束后补一拍、切会话作废在途请求）已收口到
 * `useObsTracePolling`，本文件只负责「取哪个 trace、怎么落地」。
 */
import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import { listObsSpans, pageObsTraces } from '@/api/obs'
import { useObsTracePolling } from '@/composables/useObsTracePolling'
import { useUserStore } from '@/stores/user'
import { buildOrchestrationLane, type OrchestrationLaneModel } from '@/utils/orchestrationLane'
import type { ObsSpan } from '@/types/obs'
import type { RouterTraceView } from '@/types/runtime'

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

  /** 清空视图（切到临时草稿 / 无权限） */
  function reset() {
    loading.value = false
    error.value = ''
    traceId.value = ''
    spans.value = []
  }

  /**
   * 拉最近一轮 trace 与其全部步骤。
   *
   * @param id          会话主键（引擎已保证非空且已鉴权）
   * @param isCurrent   是否仍是当前请求；false 表示已被切会话/重拉取代，结果直接丢弃
   * @param showLoading true 时显示 loading（首次 / 手动刷新）
   */
  async function load(id: number, isCurrent: () => boolean, showLoading: boolean) {
    if (showLoading) {
      loading.value = true
    }
    try {
      // 中栏接口按开始时间降序；第一条就是本轮（进行中的 trace 也会立刻出现）
      const { data } = await pageObsTraces(id, { current: 1, size: 20 })
      if (!isCurrent()) {
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
      if (!isCurrent()) {
        return
      }
      spans.value = spanRes.data.data || []
    } catch {
      if (isCurrent()) {
        error.value = '编排调度数据加载失败'
      }
    } finally {
      if (isCurrent()) {
        loading.value = false
      }
    }
  }

  const polling = useObsTracePolling({
    sessionId,
    active,
    usable: canView,
    load,
    clear: reset
  })

  return {
    view,
    canView,
    /** 手动刷新（卡片上的「刷新」按钮） */
    reload: polling.reload
  }
}
