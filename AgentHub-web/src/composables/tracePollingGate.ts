/**
 * 追踪轮询能否开表。所属域：runtime（前端调度）。
 *
 * 纯函数，供 `useObsTracePolling` 与本地单测共用。
 * 页签不在前台，或本轮已经结束时，都不再挂 `setInterval`。
 *
 * @param pageActive 页签是否在前台
 * @param running    本轮是否仍在执行
 * @return true 才允许开轮询
 */
export function tracePollingAllowed(pageActive: boolean, running: boolean): boolean {
  return pageActive && running
}
