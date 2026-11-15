/**
 * 工作台输入区「线路徽标」的文案与悬停提示：纯函数。所属域：runtime（前端）。
 *
 * <p>为什么单独一个可测模块：这行文案直接决定用户以为「下一句会被钉死给谁」。
 * 它必须只反映**用户本次真的 @ 了谁**，不能因为会话里还留着上一位专家（sticky）
 * 就一直写「已指定××」——那样用户会以为新问题不再走入口。
 *
 * <p>不依赖 Vue、不读 store：入参就是要展示的事实，便于在 node 里直接测。
 */

/** 没有指定时的默认徽标：下一句按入口判定自动匹配专家 */
export const AUTO_ROUTE_BADGE = '自动匹配专家'

/** 线路失效时的徽标：如实反映，不假装还能按它执行 */
export const INVALID_ROUTE_BADGE = '线路已失效'

/**
 * 徽标文案。
 *
 * <p>三档，只看已有事实：
 * <ul>
 *   <li>用户本次 @ 了某位 → 「已指定××」（可点 × 取消）；</li>
 *   <li>没有 @、但沿用线路已失效 → 「线路已失效」（这是故障提示，不是指定）；</li>
 *   <li>其余（普通问答、点选项续作、上一轮沿用某位专家）→ 「自动匹配专家」。</li>
 * </ul>
 *
 * @param forcedName 用户本次 @ 的专家展示名；没 @ 时为空
 * @param invalid    当前沿用线路是否已失效（候选里找不到）
 * @return 徽标文案，永不为空
 */
export function workbenchRouteBadge(forcedName: string | undefined | null, invalid = false): string {
  const forced = (forcedName || '').trim()
  if (forced) {
    return `已指定${forced}`
  }
  return invalid ? INVALID_ROUTE_BADGE : AUTO_ROUTE_BADGE
}

/**
 * 徽标悬停提示：与文案同一口径，避免「写着自动匹配、提示却说已指定」。
 *
 * @param badge 已算好的徽标文案
 * @return 一句可读说明
 */
export function workbenchRouteBadgeTitle(badge: string): string {
  const text = (badge || '').trim()
  if (!text || text === AUTO_ROUTE_BADGE) {
    return '网关模式：由接待方案自动匹配专家'
  }
  if (text.includes('失效')) {
    return '当前线路已失效，请 @ 重选或直接发送由系统重配'
  }
  // 只有「已指定××」才走到这里：说明本轮会把问题交给这一位
  return `下一轮将按「${text.replace(/^已指定/, '')}」执行；也可继续 @ 更换`
}
