/**
 * 对话区跟滚的判定。所属域：runtime（前端展示）。
 *
 * <p>为什么需要它：跟滚靠「滚动位置是否在底部附近」开门。但 `scroll` 事件不只来自用户——
 * 我们自己把 `scrollTop` 写到底也会触发一次滚动事件，而浏览器把这次事件推迟到下一帧才派发。
 * 流式期正文增长很快，等事件派发时内容可能已经长了上百像素，此时算出的「距底部」早已超标；
 * 若照旧当成「用户上翻」，跟滚会被自己关掉，且没有人再把它打开（表现就是流式停在半截）。
 *
 * <p>因此判定分三种情况：
 * 1. 距底部在阈值内：无论谁滚的，都算「在底部」，重新武装跟随；
 * 2. 远离底部，但 `scrollTop` 与我们上次写入后读回的值一致：这是我们自己写的，保持原状态；
 * 3. 其余：真正的用户上翻，关掉跟随。
 *
 * 纯函数，不读 DOM，调用方（ChatPanel.onScroll）负责传入当前测量值。
 */

/** 一次滚动判定需要的测量值。 */
export interface FollowScrollInput {
  /** 当前 scrollTop */
  scrollTop: number
  /** 当前 scrollHeight */
  scrollHeight: number
  /** 当前 clientHeight */
  clientHeight: number
  /** 上一次程序写入后读回的实际 scrollTop；-1 表示本轮还没写过 */
  selfScrollTop: number
  /** 判定前的跟随状态 */
  previous: boolean
  /** 距底部多少像素内仍算「在底部」 */
  threshold: number
}

/** 判定为「自己写的滚动」时 scrollTop 允许的误差（浏览器可能做亚像素取整）。 */
const SELF_SCROLL_TOLERANCE_PX = 2

/**
 * 滚动事件发生后，是否继续跟随底部。
 *
 * @param input 当前测量值与上一次程序写入位置
 * @return true 表示继续跟滚
 */
export function resolveFollowAfterScroll(input: FollowScrollInput): boolean {
  const distance = input.scrollHeight - input.scrollTop - input.clientHeight
  // 情况 1：已在底部附近。这也是「重新武装」的唯一入口：用户手动滚回底部后恢复跟随
  if (distance <= input.threshold) {
    return true
  }
  // 情况 2：远离底部但 scrollTop 就是我们写下去的值 → 自己触发的事件，不改状态
  if (
    input.selfScrollTop >= 0 &&
    Math.abs(input.scrollTop - input.selfScrollTop) <= SELF_SCROLL_TOLERANCE_PX
  ) {
    return input.previous
  }
  // 情况 3：用户主动上翻，停止跟滚（不抢他正在读的位置）
  return false
}
