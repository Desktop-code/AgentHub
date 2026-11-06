/**
 * 试跑链路的 obs trace 轮询引擎。所属域：runtime（前端）。
 *
 * <p>背景：`useRouterLane`（只取最近一轮，供试跑抽屉的编排卡/甘特图）与
 * `useMaTurnTraces`（取会话内全部轮次，供 MA 运行页三栏）此前各自实现了一遍
 * 「运行中短轮询 + 结束后补一拍 + 切会话作废在途请求」的骨架，
 * 常量与判定若分别演进，很容易出现一个页能停、另一个页停不下来的情况。
 * 本文件把这套骨架收口成一份，两个数据源只负责「怎么取数、怎么落地」。
 *
 * <p>约束：
 * - 纯前端调度：只调用调用方给的 `load`，不直接依赖任何 API / store；
 * - 时序与拆分前逐条对齐：运行中 `pollMs` 轮询；本轮结束立刻补一拍，
 *   再等 `settleMs` 补一拍（trace 收尾略晚于 SSE done），然后停轮询；
 * - `sessionId` 变化或无权限：先停定时器、清空视图，再决定是否重新拉取；
 * - 每次请求带自增令牌，切会话 / 卸载后迟到的响应被丢弃，不会串会话。
 */

import { onBeforeUnmount, watch, type Ref } from 'vue'

/** 运行中默认刷新间隔：与试跑续订同一量级，够看出子一开跑就出现条 */
const DEFAULT_POLL_MS = 2000
/** 本轮结束后默认补一拍的延迟：trace 收尾略晚于 SSE done */
const DEFAULT_SETTLE_MS = 900

/** 轮询引擎的接入参数。数据落地（loading / error / 缓存）全部由调用方在 `load` 内负责。 */
export interface ObsTracePollingOptions {
  /** 会话主键；null 表示临时草稿或无会话，此时清空并停轮询 */
  sessionId: Ref<number | null>
  /** 本轮是否还在跑（sending / resuming）：决定是否开轮询与结束后补拍 */
  active: Ref<boolean>
  /** 是否可用（页面启用 + 追踪权限）；false 时清空并停轮询 */
  usable: Ref<boolean>
  /**
   * 拉取一次数据。
   *
   * @param id          会话主键（已保证非空）
   * @param isCurrent   是否仍是当前这一轮请求；返回 false 表示已被切会话/重拉取代，应丢弃结果
   * @param showLoading 是否展示首次/手动刷新的 loading
   */
  load: (id: number, isCurrent: () => boolean, showLoading: boolean) => Promise<void>
  /** 会话切换 / 无权限时清空视图（调用方实现，通常是重置 records 与缓存） */
  clear: () => void
  /** 运行中刷新间隔，默认 2000ms */
  pollMs?: number
  /** 结束后补拍延迟，默认 900ms */
  settleMs?: number
}

/** 引擎对外能力。 */
export interface ObsTracePollingHandle {
  /** 手动重拉（卡片/右栏的「刷新」按钮），带 loading */
  reload: () => void
  /** 主动停轮询（极少用；卸载时引擎已自动处理） */
  stop: () => void
}

/**
 * 装配一套「按会话轮询 trace」的调度。
 *
 * @param options 见 {@link ObsTracePollingOptions}
 * @return reload / stop 两个手动入口
 */
export function useObsTracePolling(options: ObsTracePollingOptions): ObsTracePollingHandle {
  const pollMs = options.pollMs ?? DEFAULT_POLL_MS
  const settleMs = options.settleMs ?? DEFAULT_SETTLE_MS

  /** 请求令牌：切会话 / 卸载后自增，迟到的响应据此丢弃，避免串会话 */
  let token = 0
  let timer: number | undefined
  let settleTimer: number | undefined

  /** 停轮询与收尾补拍。切会话、本轮结束、组件卸载都要调 */
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

  /** 清空视图并作废在途请求（切会话 / 无权限）。先自增令牌，迟到的响应一律丢弃 */
  function invalidate() {
    token += 1
    options.clear()
  }

  /**
   * 执行一次拉取。
   *
   * @param showLoading 是否展示 loading（首次 / 手动刷新）
   */
  async function run(showLoading: boolean) {
    const id = options.sessionId.value
    // 无会话或无权限：等价于拆分前的「load 内部先 reset」，集中在这里做，避免各数据源各写一遍
    if (id == null || !options.usable.value) {
      invalidate()
      return
    }
    const mine = ++token
    await options.load(id, () => mine === token, showLoading)
  }

  /** 本轮在跑时开轮询：子一开跑就能看到条，不必等整轮结束 */
  function startPolling() {
    if (!options.active.value || !options.usable.value || timer != null) {
      return
    }
    timer = window.setInterval(() => void run(false), pollMs)
  }

  // 切会话 / 切权限：停表 → 清空 → 有会话且可用才重拉并起轮询
  watch(
    () => [options.sessionId.value, options.usable.value] as const,
    ([id, ok]) => {
      stopTimer()
      invalidate()
      if (id == null || !ok) {
        return
      }
      void run(true)
      startPolling()
    },
    { immediate: true }
  )

  // 本轮结束的那一刻补一拍，等 trace 收尾落库；再停轮询
  watch(options.active, (running) => {
    stopTimer()
    if (options.sessionId.value == null || !options.usable.value) {
      invalidate()
      return
    }
    void run(false)
    if (running) {
      startPolling()
      return
    }
    settleTimer = window.setTimeout(() => void run(false), settleMs)
  })

  onBeforeUnmount(stopTimer)

  return {
    reload: () => void run(true),
    stop: stopTimer
  }
}
