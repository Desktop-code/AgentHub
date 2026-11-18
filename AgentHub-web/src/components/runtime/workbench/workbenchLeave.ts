/**
 * 工作台页签失活时的暂停判定。所属域：runtime（前端工作台）。
 *
 * 纯函数：失活中断 SSE 不当成发送失败；重新进入时只有本轮还没结束才续订。
 * 空闲且本轮已结束时调用方只停表，这里返回 false，避免清消息、关页签。
 */

/**
 * 这次异常是不是主动 abort 的流。
 *
 * @param error 发送 / 续作抛出的异常
 * @return true 表示 fetch 被 AbortSignal 打断
 */
export function isStreamAbort(error: unknown): boolean {
  if (!error || typeof error !== 'object') {
    return false
  }
  return (error as { name?: string }).name === 'AbortError'
}

/**
 * 页签重新激活后要不要把这一轮续上。
 *
 * @param input.assistantStreaming 右栏是否还有流式中的助手轮
 * @param input.turnRunning        模块级登记是否仍认为这一轮在跑
 * @return true 才去拉事件并恢复续订；两边都没有则保持现状
 */
export function shouldResumeAfterReturn(input: {
  assistantStreaming: boolean
  turnRunning: boolean
}): boolean {
  return input.assistantStreaming || input.turnRunning
}
