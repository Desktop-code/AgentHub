/**
 * 调用追踪步骤的展示标签。所属域：obs（前端展示）。
 *
 * 外壳仍是 AGENT span，种类写在 attrs 的 obs.role 上：
 * workflow → 工作流，scheme → 方案。没有这个键的 AGENT（真专家、顶层方案根）仍显示 Agent。
 */

/** 与后端 RouterObs.OBS_ROLE 同一键，前端只读不另起名字 */
const OBS_ROLE = 'obs.role'

/** 与后端 RouterObs.OBS_SUMMARY 同一键：concat 表示拼接综答、没有模型调用 */
const OBS_SUMMARY = 'obs.summary'

/**
 * 综答标记。concat 表示这一步是拼接、无模型调用。
 *
 * @param attrsJson 步骤 attrs 原文，可空
 * @returns concat，或空串
 */
export function spanSummary(attrsJson: string | null | undefined): string {
  if (!attrsJson) return ''
  try {
    const parsed = JSON.parse(attrsJson) as Record<string, unknown>
    const summary = parsed[OBS_SUMMARY]
    return typeof summary === 'string' ? summary : ''
  } catch {
    return ''
  }
}

/**
 * 从 attrs JSON 取出外壳种类。解析失败或没有该键时返回空，调用方按 span_type 兜底。
 *
 * @param attrsJson 步骤 attrs 原文，可空
 * @returns workflow / scheme，或空串
 */
export function spanRole(attrsJson: string | null | undefined): string {
  if (!attrsJson) return ''
  try {
    const parsed = JSON.parse(attrsJson) as Record<string, unknown>
    const role = parsed[OBS_ROLE]
    return typeof role === 'string' ? role : ''
  } catch {
    return ''
  }
}

/**
 * 步骤类型的中文标签。AGENT 再看外壳种类；SYSTEM 带 obs.summary=concat 时显示「拼接」。
 *
 * @param spanType  span_type
 * @param attrsJson 仅 AGENT 外壳用来区分工作流 / 方案
 * @returns 展示用短标签
 */
export function spanTypeLabel(
  spanType: string | null | undefined,
  attrsJson?: string | null
): string {
  if (spanType === 'LLM') return '模型调用'
  if (spanType === 'TOOL') return '工具调用'
  if (spanType === 'VISION') return '图片识别'
  if (spanType === 'ROUTE') return '路由'
  if (spanType === 'PLAN') return '规划'
  if (spanType === 'RETRIEVE') return '检索'
  if (spanType === 'SYSTEM') {
    return spanSummary(attrsJson) === 'concat' ? '拼接' : '系统'
  }
  if (spanType === 'AGENT') {
    const role = spanRole(attrsJson)
    if (role === 'workflow') return '工作流'
    if (role === 'scheme') return '方案'
    return 'Agent'
  }
  return spanType || '步骤'
}

/**
 * 路由决策块标题。JSON 是数组时带上条数，避免预览只露出第一条却和「多意图路由 ×N」对不上。
 * 单对象或非 JSON 保持原来的「路由决策」，不改决策正文。
 *
 * @param routeDecision 已落库的 route_decision 原文
 * @returns 标题文案
 */
export function routeDecisionLabel(routeDecision: string | null | undefined): string {
  const raw = routeDecision ?? ''
  if (!raw) return '路由决策'
  try {
    const parsed = JSON.parse(raw) as unknown
    if (Array.isArray(parsed)) {
      return `路由决策 · ${parsed.length} 条`
    }
  } catch {
    // 不是 JSON 时标题不带条数，正文仍按原样展示
  }
  return '路由决策'
}
