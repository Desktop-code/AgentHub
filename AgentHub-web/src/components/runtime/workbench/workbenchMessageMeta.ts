/**
 * 工作台消息展示文案工具。所属域：runtime（前端）。
 *
 * <p>只放纯函数：Agent 名称缺省、思考区内部键脱敏。
 * 不依赖 Vue / 接口，便于在本地 tests/ 直接覆盖；改的是「对外怎么显示」，
 * 不改变思考过程的数据源与折叠语义。
 *
 * <p>思考面板的标题（正在深度思考 / 查看思考与路由轨迹）统一收在
 * `workbenchThinkTimeline`，这里不再保留第二套折叠条文案。
 */

/** 无可用展示名时的兜底称呼；不写死某个具体专家 */
export const WORKBENCH_AGENT_NAME_FALLBACK = '智能助手'

/**
 * 占位 / 状态文案：它们是会话状态而不是 Agent 展示名，
 * 不能出现在名称行（例如线路失效时 routeHint 会返回「线路已失效」）。
 */
const PLACEHOLDER_AGENT_NAMES = new Set(['助手', '当前线路', '线路已失效', '未命名线路'])

/**
 * 把内部解析出的候选名称收口成可展示的 Agent 名。
 *
 * @param raw 车道标题 / 候选 agentName / 当前线路展示名（可能为空或占位文案）
 * @return 可展示名称；空或占位时统一「智能助手」
 */
export function resolveWorkbenchAgentName(raw?: string | null): string {
  const name = (raw || '').trim()
  if (!name || PLACEHOLDER_AGENT_NAMES.has(name)) {
    return WORKBENCH_AGENT_NAME_FALLBACK
  }
  return name
}

/**
 * 工作台思考区对外文本脱敏：抹掉 route_key 等内部定位键。
 *
 * <p>路由决策块（多意图）正文是 `[{"route_key":"…","sub_query":"…"}]`，
 * 展开思考时会直接把业务键打给终端用户。这里只做展示层剥离，不动数据源；
 * 调试皮肤不调用本函数，仍保留原始排障信息。
 *
 * @param raw 思考正文 / 工具行 / 路由决策 JSON
 * @return 去掉内部键后的可展示文本
 */
export function stripWorkbenchInternalKeys(raw: string): string {
  if (!raw) {
    return ''
  }
  return raw
    // JSON 形态："route_key":"x" / "routeKey": "x"；连同后面的逗号一起吃掉，避免留下空字段
    .replace(/"?(?:route_key|routeKey)"?\s*[:=：]\s*"(?:[^"\\]|\\.)*"\s*,?/g, '')
    // 非 JSON 形态：route_key=x / route_key：x（如工具入参摘要）
    .replace(/\b(?:route_key|routeKey)\b\s*[:=：]\s*[^\s,;，；)\]}]+/g, '')
    // 兜底：无论何种写法，内部键名本身也不出现在工作台
    .replace(/\b(?:route_key|routeKey)\b/g, '')
    // 只剩空决策项时清掉 [{}] / {} / [] 噪音
    .replace(/\[\s*\{\s*\}\s*\]/g, '')
    .replace(/\{\s*\}\s*,?\s*/g, '')
    .replace(/\[\s*\]/g, '')
    // 去掉删字段后可能留下的悬空逗号
    .replace(/,\s*([\]}])/g, '$1')
    .trim()
}

/**
 * 这段文本是否是「机器可读的路由决策 JSON」。
 *
 * <p>`[{"route_key":…,"sub_query":…,"reason":…}]` 只用于分流与观测，不是给人看的正文。
 * 新数据后端已改成人话决策句；这里拦住<b>历史会话里已落库的旧 JSON</b>，
 * 避免它出现在思考依据、方案块正文或运行页摘要里。
 *
 * @param raw 一段文本（可整段、可单行）
 * @return true 表示是路由机器 JSON，展示层应整段丢弃
 */
export function looksLikeRouteMachineJson(raw: string): boolean {
  const text = (raw || '').trim()
  if (!text) {
    return false
  }
  const head = text[0]
  if (head !== '[' && head !== '{') {
    return false
  }
  try {
    const parsed = JSON.parse(text) as unknown
    const rows = Array.isArray(parsed) ? parsed : [parsed]
    return rows.some((row) => {
      if (!row || typeof row !== 'object') {
        return false
      }
      const obj = row as Record<string, unknown>
      return (
        typeof obj.route_key === 'string' ||
        typeof obj.routeKey === 'string' ||
        typeof obj.sub_query === 'string' ||
        typeof obj.subQuery === 'string'
      )
    })
  } catch {
    // 半截 JSON（流式中）/ 普通业务正文：按「不是」处理，交给其它清洗规则
    return false
  }
}

/**
 * 一行里是否夹着「判路由决策对象」（含 route_key + sub_query 的 JSON 片段）。
 *
 * <p>与 {@link looksLikeRouteMachineJson} 的分工：那个只认「整行就是一个 JSON」，
 * 这个认「JSON 被模型复述进正文中间」的情况——那同样是把内部分流数据打给了终端用户。
 * 只要同时出现 route_key / sub_query 两个决策字段就整行丢弃，普通业务文本不受影响。
 *
 * @param line 原始一行文本
 * @return true 表示含决策对象，展示层应丢弃整行
 */
export function looksLikeRouteDecisionPayload(line: string): boolean {
  const text = line || ''
  if (!text) {
    return false
  }
  const hasKey = /"?(?:route_key|routeKey)"?\s*[:=]/.test(text)
  const hasQuery = /"?(?:sub_query|subQuery)"?\s*[:=]/.test(text)
  return hasKey && hasQuery
}

/**
 * 正文脱敏：丢掉机器路由 JSON / 决策对象行，其余行只抹内部键后保留。
 *
 * <p>用在「气泡正文」这类最终对用户可见的位置：即使历史会话把决策 JSON 落进了
 * 助手正文块，或模型把 JSON 复述进回答里，也不会被打给用户。
 * 顺序很重要——先按原始行判定再脱敏，否则 `route_key` 被抹掉后更难识别整段 JSON。
 *
 * @param raw 正文原文
 * @return 可展示正文；全部被过滤时为空串
 */
export function stripRouteDecisionJson(raw: string): string {
  const source = raw || ''
  if (!source.trim()) {
    return ''
  }
  return source
    .split('\n')
    .filter((line) => !looksLikeRouteMachineJson(line) && !looksLikeRouteDecisionPayload(line))
    .map((line) => stripWorkbenchInternalKeys(line))
    .join('\n')
    .trim()
}
