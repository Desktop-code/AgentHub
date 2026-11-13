/**
 * 路由事件正文的参与者解析。所属域：runtime（前端展示）。
 *
 * <p>为什么单独一个文件：这条解析同时被「思考分块」（`trialMessages`）和
 * 「工作台消息流投影」（`workbenchParticipantFeed`）使用。放在无别名、无第三方依赖的
 * 纯模块里，两条链路才能共用同一套口径，也不会给 Node 直跑的单测引入 `@/` 解析问题。
 *
 * <p>必须同时认两种真实写法：
 * <ul>
 *   <li>判路由 / 路由后串行：`路由到 名称（key），依据：…`；</li>
 *   <li>固定模板 / 竞争选优：`固定并行 1/2 · 名称（key）`。</li>
 * </ul>
 * 只认一种，就会让另一类参与者在消息流里彻底隐身。
 */

/** 固定 / 串行模板的子块标题前缀（含序号与作者） */
const CHILD_TITLE_PATTERN = /^(固定并行|固定串行|路由后串行|竞争选优)\s*\d+\s*\/\s*\d+\s*·\s*([\s\S]+)$/

/**
 * 解析「固定并行 1/2 · 名称（key）[ · 同题作答][ · ★ 选中]」。
 *
 * @param raw 路由事件正文
 * @return 作者名与 route_key；不是这类标题时为空
 */
export function parseChildTitle(raw: string): { name: string; key: string } | undefined {
  const matched = CHILD_TITLE_PATTERN.exec((raw || '').trim())
  if (!matched) {
    return undefined
  }
  // 尾部状态词是模板自己加的说明，不属于名字
  const rest = (matched[2] || '')
    .replace(/\s*·\s*★\s*选中\s*$/, '')
    .replace(/\s*·\s*同题作答\s*$/, '')
    .trim()
  const keyed = /^(.+?)[（(]([^）)]*)[）)]$/.exec(rest)
  if (keyed) {
    return { name: (keyed[1] || '').trim(), key: (keyed[2] || '').trim() }
  }
  return { name: rest, key: '' }
}

/**
 * 从一条路由事件正文里取参与者名与 route_key。
 *
 * <p>消息流按名字分块、正文与文件按名字归位都靠它。
 *
 * @param raw 路由事件正文
 * @return 参与者名与 route_key；决策块 / 方案结论 / 说明句返回空
 */
export function parseRouteParticipant(raw: string): { name: string; key: string } | undefined {
  const text = (raw || '').trim()
  if (!text || text.startsWith('路由决策') || text.startsWith('方案结论')) {
    return undefined
  }
  const child = parseChildTitle(text)
  if (child?.name) {
    return child
  }
  const matched = /^路由到\s*(.+?)(?:（([^）]*)）)?(?:，依据[：:]\s*(.*))?$/s.exec(text)
  if (!matched) {
    return undefined
  }
  const name = (matched[1] || '').trim().replace(/，$/, '')
  if (!name) {
    return undefined
  }
  return { name, key: (matched[2] || '').trim() }
}
