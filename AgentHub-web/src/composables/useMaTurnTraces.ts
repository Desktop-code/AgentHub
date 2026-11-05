/**
 * MA 运行页的「逐轮执行追踪」数据源。所属域：runtime（前端）。
 *
 * <p>背景：一条用户消息对应一条 obs trace，因此会话里每轮都能单独取链路。
 * 既有 `useRouterLane` 只取最近一条 trace，适合抽屉；本页三栏需要「中间所有轮次 + 右栏跟随选中轮」，
 * 所以这里按会话拉全部 trace，并缓存每条的 span 树。
 *
 * <p>约束：
 * - 只读 obs 接口，不写库、不改 RouterTrialExecutor 的隔离 / 并行 / 汇总语义；
 * - 只在 `enabled` 为真时请求，Agent 管理里的试跑抽屉仍走 `useRouterLane`，互不影响；
 * - 步骤按 traceId 缓存；轮询只刷新仍在 RUNNING 的 trace，避免整表重拉。
 */
import { computed, onBeforeUnmount, reactive, ref, watch, type Ref } from 'vue'
import { listObsSpans, pageObsTraces } from '@/api/obs'
import { useUserStore } from '@/stores/user'
import { buildOrchestrationLane } from '@/utils/orchestrationLane'
import type { ObsSpan, ObsTrace } from '@/types/obs'
import type { MaTurnTrace } from '@/components/runtime/ma/maRunView'

/** 运行中的刷新间隔；与试跑续订同一量级，够看出子一开跑就出现 */
const POLL_MS = 2000
/** 本轮结束后补一拍：trace 收尾略晚于 SSE done */
const SETTLE_MS = 900
/** 单会话最多取多少轮；试跑会话通常远小于这个数 */
const TRACE_PAGE_SIZE = 50

/**
 * 按会话轮询全部轮次 trace 与步骤。
 *
 * @param sessionId 试跑会话主键；草稿 / 非 Router 传 null
 * @param active    本轮是否还在跑（sending / resuming），决定是否开轮询
 * @param enabled   是否启用本数据源（仅 MA 三栏页为 true）
 * @return traces 按时间升序的逐轮追踪；error / loading 供右栏提示，reload 供手动重拉
 */
export function useMaTurnTraces(sessionId: Ref<number | null>, active: Ref<boolean>, enabled: Ref<boolean>) {
  const user = useUserStore()
  /** 两个权限都查同一条 trace 的步骤；缺一个就整块不渲染，避免半截空壳 */
  const canView = computed(() => user.hasPerm('obs:trace:list') && user.hasPerm('obs:span:list'))

  /** trace 列表（服务端时间降序原样保留，输出时再排） */
  const records = ref<ObsTrace[]>([])
  /** 步骤缓存：traceId → spans；并行写入用 reactive 对象，避免 Map 整体替换互相覆盖 */
  const spanCache = reactive<Record<string, ObsSpan[]>>({})
  const loading = ref(false)
  const error = ref('')

  /** 可用 = 页面启用 + 有追踪权限 */
  const usable = computed(() => enabled.value && canView.value)

  /**
   * 对外输出：按时间升序，附带泳道模型与加载态。
   * 没有任何步骤时 model 为 null，中栏自动退回「用事件块兜底」。
   */
  const traces = computed<MaTurnTrace[]>(() => {
    const list = [...records.value].sort((a, b) => {
      const ta = parseTime(a.startTime) ?? 0
      const tb = parseTime(b.startTime) ?? 0
      return ta - tb || String(a.traceId).localeCompare(String(b.traceId))
    })
    return list.map((record) => {
      const spans = spanCache[record.traceId] ?? []
      return {
        traceId: record.traceId,
        sessionId: record.sessionId ?? sessionId.value ?? 0,
        title: record.title || '',
        status: record.status || '',
        startTime: record.startTime,
        endTime: record.endTime,
        latencyMs: record.latencyMs ?? 0,
        spans,
        model: spans.length ? buildOrchestrationLane(spans) : null,
        // 已请求过（即使返回空数组）就不再显示加载中
        loading: !(record.traceId in spanCache),
        error: ''
      }
    })
  })

  /** 请求令牌：切会话 / 切权限后丢弃迟到的响应，避免串会话 */
  let token = 0
  let timer: number | undefined
  let settleTimer: number | undefined

  /** 服务端时间串转毫秒 */
  function parseTime(value?: string | null): number | null {
    if (!value) {
      return null
    }
    const ms = Date.parse(value.includes('T') ? value : value.replace(' ', 'T'))
    return Number.isNaN(ms) ? null : ms
  }

  /**
   * 拉会话下全部 trace，并按需补步骤。
   *
   * @param showLoading true 时显示首次 / 手动刷新的 loading
   */
  async function load(showLoading = false) {
    const id = sessionId.value
    if (id == null || !usable.value) {
      reset()
      return
    }
    const mine = ++token
    if (showLoading) {
      loading.value = true
    }
    try {
      const { data } = await pageObsTraces(id, { current: 1, size: TRACE_PAGE_SIZE })
      if (mine !== token) {
        return
      }
      const list = data.data?.records || []
      records.value = list
      error.value = ''
      // 只补缺失步骤与仍在跑的 trace；已完成的历史轮次不重复拉
      const need = list.filter((record) => !(record.traceId in spanCache) || record.status === 'RUNNING')
      await Promise.all(
        need.map(async (record) => {
          try {
            const spanRes = await listObsSpans(record.traceId)
            if (mine !== token) {
              return
            }
            spanCache[record.traceId] = spanRes.data.data || []
          } catch {
            // 单条失败不拖垮整页：标记成已加载（空），中栏走事件块兜底
            if (mine === token && !(record.traceId in spanCache)) {
              spanCache[record.traceId] = []
            }
          }
        })
      )
    } catch {
      if (mine === token) {
        error.value = '执行链路加载失败'
      }
    } finally {
      if (mine === token) {
        loading.value = false
      }
    }
  }

  /** 清空数据并作废在途请求（切会话 / 无权限） */
  function reset() {
    token += 1
    loading.value = false
    error.value = ''
    records.value = []
    for (const key of Object.keys(spanCache)) {
      delete spanCache[key]
    }
  }

  /** 停轮询与收尾补拍 */
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

  /** 本轮在跑时开轮询：子一开跑就能看到候选卡与链路节点 */
  function startPolling() {
    if (!active.value || timer != null) {
      return
    }
    timer = window.setInterval(() => void load(), POLL_MS)
  }

  // 切会话 / 切权限：清空后立即重拉；无会话或无权限时保持空态
  watch(
    () => [sessionId.value, usable.value] as const,
    ([id, ok]) => {
      stopTimer()
      reset()
      if (id == null || !ok) {
        return
      }
      void load(true)
      startPolling()
    },
    { immediate: true }
  )

  // 本轮结束的那一刻补一拍，等 trace 收尾落库；再停轮询
  watch(
    () => active.value,
    (running) => {
      if (sessionId.value == null || !usable.value) {
        return
      }
      stopTimer()
      void load()
      if (running) {
        startPolling()
        return
      }
      settleTimer = window.setTimeout(() => void load(), SETTLE_MS)
    }
  )

  onBeforeUnmount(stopTimer)

  return {
    traces,
    canView,
    loading,
    error,
    /** 手动刷新当前会话的全部轮次 */
    reload: () => load(true)
  }
}
