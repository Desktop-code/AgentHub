/**
 * 多智能体列表的展示口径。所属域：前端 agent。
 *
 * <p>只做「后端码 → 中文/标签样式」的纯映射，不依赖 Vue / DOM，
 * 便于 node:test 直接校验，也保证列表页与详情编排 Tab 的中文叫法一致。
 *
 * <p>注意：六个模板码必须与服务端 {@code AgentRuntimeConfig} 及
 * {@code utils/orchestrationValidation.ts} 的六值白名单保持一致；
 * 本文件刻意不引入运行时依赖，避免 node:test 需要解析路径别名。
 */

/** 模板：判路由 → 命中子并行 → 汇总（现网基线） */
export const MA_LIST_TEMPLATE_ROUTER_PARALLEL = 'ROUTER_PARALLEL'
/** 模板：跳过判路由，按 sort_order 串行执行全部启用候选 */
export const MA_LIST_TEMPLATE_FIXED_SEQUENCE = 'FIXED_SEQUENCE'
/** 模板：跳过判路由，全部启用候选并行执行同一份用户原问 */
export const MA_LIST_TEMPLATE_FIXED_PARALLEL = 'FIXED_PARALLEL'
/** 模板：跳过判路由，1 个执行者出稿 → 评审专家判定 → 有限回修 */
export const MA_LIST_TEMPLATE_CRITIC_LOOP = 'CRITIC_LOOP'
/** 模板：判路由选出有序子集 → 按判定序串行 → 汇总 */
export const MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE = 'ROUTER_THEN_SEQUENCE'
/** 模板：同题并行竞争 → 选优取 1 个子终答 */
export const MA_LIST_TEMPLATE_COMPETE = 'COMPETE'

/** 六值白名单 */
export const MA_LIST_TEMPLATES = [
  MA_LIST_TEMPLATE_ROUTER_PARALLEL,
  MA_LIST_TEMPLATE_FIXED_SEQUENCE,
  MA_LIST_TEMPLATE_FIXED_PARALLEL,
  MA_LIST_TEMPLATE_CRITIC_LOOP,
  MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE,
  MA_LIST_TEMPLATE_COMPETE
] as const

/** 协作模式中文名；未知值按默认模板展示，避免列表出现空标签 */
export const MA_TEMPLATE_LABELS: Record<string, string> = {
  [MA_LIST_TEMPLATE_ROUTER_PARALLEL]: '路由并行',
  [MA_LIST_TEMPLATE_FIXED_SEQUENCE]: '固定串行',
  [MA_LIST_TEMPLATE_FIXED_PARALLEL]: '固定并行',
  [MA_LIST_TEMPLATE_CRITIC_LOOP]: '评审环',
  [MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE]: '路由后串行',
  [MA_LIST_TEMPLATE_COMPETE]: '竞争选优'
}

/** 模板对应的说明，作为鼠标悬浮提示，减少用户为选模板回详情页对照 */
export const MA_TEMPLATE_HINTS: Record<string, string> = {
  [MA_LIST_TEMPLATE_ROUTER_PARALLEL]: '先判路由，命中的子 Agent 并行执行后汇总',
  [MA_LIST_TEMPLATE_FIXED_SEQUENCE]: '跳过判路由，按顺序串行执行全部启用候选',
  [MA_LIST_TEMPLATE_FIXED_PARALLEL]: '跳过判路由，全部启用候选并行执行同一份用户原问',
  [MA_LIST_TEMPLATE_CRITIC_LOOP]: '跳过判路由，恰好 1 个执行者出稿，评审专家判定后有限回修（默认 1 轮）',
  [MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE]: '先判路由选出专家与子问，再按判定顺序串行；后步只看前置摘要',
  [MA_LIST_TEMPLATE_COMPETE]: '同一问题并行交给多个候选，评选模型选出最优终答（至少 2 个启用候选）'
}

/**
 * 模板归一：六值白名单外（含空）回落 ROUTER_PARALLEL，与服务端/执行器同口径。
 *
 * @param value 后端返回或表单里的模板值
 * @return 合法模板码
 */
export function normalizeMaListTemplate(value?: string | null): string {
  return MA_LIST_TEMPLATES.includes((value || '') as (typeof MA_LIST_TEMPLATES)[number])
    ? String(value)
    : MA_LIST_TEMPLATE_ROUTER_PARALLEL
}

/**
 * 模板码 → 中文名。
 *
 * @param raw 后端返回的 maTemplate，可空或在白名单外
 * @return 中文名；非法值按 ROUTER_PARALLEL 展示
 */
export function maTemplateLabel(raw?: string | null): string {
  return MA_TEMPLATE_LABELS[normalizeMaListTemplate(raw)] || '路由并行'
}

/**
 * 模板码 → Element Plus tag 类型，让各模板在列表里一眼可分。
 *
 * <p>两种判路由模板（路由并行 / 路由后串行）共用 primary——它们同属「有 JUDGE」一族，
 * 区别由中文标签与悬浮说明表达；固定 / 评审环保持原有配色，避免视觉回归。
 *
 * @param raw 模板码
 * @return primary | success | warning | info
 */
export function maTemplateTagType(raw?: string | null): 'primary' | 'success' | 'warning' | 'info' {
  const template = normalizeMaListTemplate(raw)
  if (template === MA_LIST_TEMPLATE_FIXED_SEQUENCE) {
    return 'success'
  }
  if (template === MA_LIST_TEMPLATE_FIXED_PARALLEL) {
    return 'warning'
  }
  if (template === MA_LIST_TEMPLATE_CRITIC_LOOP) {
    return 'info'
  }
  if (template === MA_LIST_TEMPLATE_COMPETE) {
    // 竞争选优是「正向选优」：成功色与固定串行区分开，又不落进判路由的 primary
    return 'success'
  }
  return 'primary'
}

/**
 * 模板悬浮说明。
 *
 * @param raw 模板码
 * @return 中文说明；未知值按默认模板
 */
export function maTemplateHint(raw?: string | null): string {
  return MA_TEMPLATE_HINTS[normalizeMaListTemplate(raw)] || MA_TEMPLATE_HINTS[MA_LIST_TEMPLATE_ROUTER_PARALLEL]
}
