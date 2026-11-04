/**
 * Trace 类型配色。所属域：obs（前端展示）。
 *
 * <p>拓扑图与下方列表区**共用这一套常量**：同一 span_type 在图上什么色，
 * 列表左侧色条与卡片底就什么色。禁止任一视图另起一套色值，
 * 否则同一条链路在图上是蓝的、列表里变成紫的，排查时会误以为串了 trace。
 *
 * <p>刻意压低饱和度：边框承担类型识别，底色只到「隐约有色」的程度，
 * 避免整屏花掉，也给以后叠加告警色留余地。
 */

/** 单类型配色：左侧色条 / 描边色 + 卡片底色 */
export interface TraceTypePalette {
  /** 色条与描边 */
  border: string
  /** 卡片底色（近白，只带一点类型倾向） */
  fill: string
}

/** 兜底中性色：未单独定义的类型（ SYSTEM 等）不抢主色 */
const NEUTRAL: TraceTypePalette = { border: '#CBD5E1', fill: '#FFFFFF' }

/**
 * 列表左侧色条的透明度。
 * <p>色相与拓扑完全同源，只在列表里调成半透明，让白卡本身更干净；
 * 拓扑描边仍用 {@link TraceTypePalette.border} 的原色，互不影响。
 */
export const TRACE_ACCENT_ALPHA = 0.6

/**
 * 把 6 位 hex 转成带透明度的 rgba。
 *
 * @param hex   形如 `#A5B4FC` 的颜色
 * @param alpha 0～1 透明度
 * @return rgba 字符串；hex 不合法时原样返回，避免样式整条失效
 */
export function withAlpha(hex: string, alpha: number): string {
  const match = /^#([0-9a-fA-F]{6})$/.exec(hex)
  if (!match) {
    return hex
  }
  const int = Number.parseInt(match[1], 16)
  const r = (int >> 16) & 0xff
  const g = (int >> 8) & 0xff
  const b = int & 0xff
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/**
 * 按 span_type 取配色。
 *
 * @param spanType 步骤类型（ROUTE / AGENT / LLM / TOOL / …）
 * @return 该类型的色条色与底色；未收录类型返回中性灰
 */
export function traceTypePalette(spanType: string | null | undefined): TraceTypePalette {
  if (spanType === 'ROUTE') return { border: '#A5B4FC', fill: '#F8FAFF' }
  if (spanType === 'AGENT') return { border: '#93C5FD', fill: '#F8FBFF' }
  if (spanType === 'LLM') return { border: '#FCD34D', fill: '#FFFCF5' }
  if (spanType === 'TOOL') return { border: '#6EE7B7', fill: '#F6FDF9' }
  // 其余类型（VISION/RETRIEVE/PLAN/SYSTEM）统一中性灰，避免类型色失控
  return NEUTRAL
}

/**
 * 列表卡左侧色条色：与拓扑同色相，但半透明。
 *
 * @param spanType 步骤类型
 * @return rgba 色条色
 */
export function traceTypeAccent(spanType: string | null | undefined): string {
  return withAlpha(traceTypePalette(spanType).border, TRACE_ACCENT_ALPHA)
}
