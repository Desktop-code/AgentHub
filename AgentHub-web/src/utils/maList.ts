/**
 * 多智能体列表的展示口径。所属域：前端 agent。
 *
 * <p>只做「后端码 → 中文/标签样式」的纯映射，不依赖 Vue / DOM，
 * 便于 node:test 直接校验，也保证列表页与详情编排 Tab 的中文叫法一致。
 *
 * <p>方案协作模式只留两个可写值：意图路由分发、路由后串行。
 * 历史模板码仍能读出来，展示时收成这两种。单兵 Agent 编排不走这里。
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

/**
 * 工作台入口专用类型（服务端 {@code AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER} 同口径）。
 *
 * <p>平台只有一条，由增量 SQL 从默认接待那一行迁过来；<b>不在</b> {@link MA_LIST_TEMPLATES} 里，
 * 因此「新建方案」下拉选不到它（新建只能选两种协作模式）。
 * 列表<b>照常展示</b>这一条，名称固定为「Router」（见 {@link MA_ENTRY_ROUTER_NAME}），
 * 借类型标签与普通 Router 方案区分；身份字段（编码 / 名称 / 备注）在页面只读、后端同样拒绝修改。
 */
export const MA_LIST_TEMPLATE_ENTRY_ROUTER = 'ENTRY_ROUTER'

/**
 * 入口行的固定展示名（与服务端 {@code AgentRuntimeConfig.ENTRY_ROUTER_NAME} 同口径）。
 *
 * <p>名称由平台锁定：列表、详情、抽屉标题都叫「Router」，不给运营改成别的叫法。
 */
export const MA_ENTRY_ROUTER_NAME = 'Router'

/**
 * 入口行的固定备注（与服务端 {@code AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION} 同口径）。
 *
 * <p>写清它是什么、负责什么、哪些动作被禁止；页面只读展示，保存时原样回传。
 */
export const MA_ENTRY_ROUTER_DESCRIPTION =
  '对话入口：负责规划、选择专家或普通方案并决定是否收尾；不可删除、不可改名称/类型、不可加入路由池、不可作为子专家。'


/** 新建 / 编辑普通方案只能选这两个；入口类型刻意不在其中 */
export const MA_LIST_TEMPLATES = [
  MA_LIST_TEMPLATE_ROUTER_PARALLEL,
  MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE
] as const

/** 串行模式旁的提示：复杂依赖归工作流，这里只做简单接力 */
export const MA_SERIAL_WORKFLOW_HINT =
  '如需 3 步以上复杂依赖或条件判断，请前往工作流；多智能体只做简单串接。'

/**
 * 筛选下拉的可选值：六个可写模板 + 入口类型。
 *
 * <p>筛选与「新建」不是一回事：入口已经出现在同一张列表里，运营要能按类型把它筛出来，
 * 但新建时不能选它（新建仍只遍历 {@link MA_LIST_TEMPLATES}）。
 * 历史模板不单列：筛选「意图路由分发 / 路由后串行」时由服务端把旧码一并算进去。
 */
export const MA_LIST_FILTER_TEMPLATES = [
  MA_LIST_TEMPLATE_ENTRY_ROUTER,
  ...MA_LIST_TEMPLATES
] as const

/** 协作模式中文名；历史模板展示成收口后的两种模式，避免列表还出现旧名字 */
export const MA_TEMPLATE_LABELS: Record<string, string> = {
  [MA_LIST_TEMPLATE_ENTRY_ROUTER]: '对话路由',
  [MA_LIST_TEMPLATE_ROUTER_PARALLEL]: '意图路由分发',
  [MA_LIST_TEMPLATE_FIXED_PARALLEL]: '意图路由分发',
  [MA_LIST_TEMPLATE_COMPETE]: '意图路由分发',
  [MA_LIST_TEMPLATE_CRITIC_LOOP]: '意图路由分发',
  [MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE]: '路由后串行',
  [MA_LIST_TEMPLATE_FIXED_SEQUENCE]: '路由后串行'
}

/** 模板对应的说明，作为鼠标悬浮提示，减少用户为选模板回详情页对照 */
export const MA_TEMPLATE_HINTS: Record<string, string> = {
  [MA_LIST_TEMPLATE_ENTRY_ROUTER]: '平台对话入口（固定名 Router）：先判意图，再分发给路由池里的专家 / 方案 / 工作流',
  [MA_LIST_TEMPLATE_ROUTER_PARALLEL]: '按候选简介选出一个或多个，并行执行，彼此不传结论',
  [MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE]:
    '先按简介选出多个，再按执行顺序接力；下一步能看到前面节点的成文结论'
}

/**
 * 把历史模板收成两种协作模式。入口原样返回，不洗成意图路由分发。
 *
 * @param value 后端返回或表单里的模板值
 * @return 入口码，或两种协作模式之一
 */
export function canonicalMaTemplate(value?: string | null): string {
  const key = String(value ?? '')
  if (key === MA_LIST_TEMPLATE_ENTRY_ROUTER) return key
  if (key === MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE || key === MA_LIST_TEMPLATE_FIXED_SEQUENCE) {
    return MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE
  }
  return MA_LIST_TEMPLATE_ROUTER_PARALLEL
}

/**
 * 模板归一：两种模式之外（含空、历史码）回落意图路由分发。入口也会落到默认模式，
 * 调用方要先用 {@link isEntryRouterTemplate} 把入口摘出去。
 *
 * @param value 后端返回或表单里的模板值
 * @return 合法协作模式码
 */
export function normalizeMaListTemplate(value?: string | null): string {
  const canonical = canonicalMaTemplate(value)
  return canonical === MA_LIST_TEMPLATE_ENTRY_ROUTER ? MA_LIST_TEMPLATE_ROUTER_PARALLEL : canonical
}

/**
 * 模板码 → 中文名。
 *
 * <p>先认入口和两种模式（含已收口的历史码），再按默认模式兜底。
 *
 * @param raw 后端返回的 maTemplate，可空或在白名单外
 * @return 中文名；非法值按意图路由分发展示
 */
export function maTemplateLabel(raw?: string | null): string {
  const key = String(raw ?? '')
  if (MA_TEMPLATE_LABELS[key]) return MA_TEMPLATE_LABELS[key]
  return MA_TEMPLATE_LABELS[normalizeMaListTemplate(key)] || '意图路由分发'
}

/**
 * 是否工作台入口类型（与后端 AgentRuntimeConfig.isEntryRouterTemplate 同口径）。
 *
 * <p>页面据此锁定模板 / 编码 / 名称 / 备注、隐藏删除按钮，
 * 并在候选里拒绝把它当成子专家（服务端同样会拦）。
 *
 * @param raw 模板码；可空
 * @return true 表示这是平台对话入口（固定名 Router）
 */
export function isEntryRouterTemplate(raw?: string | null): boolean {
  return String(raw ?? '') === MA_LIST_TEMPLATE_ENTRY_ROUTER
}

/**
 * 该模板是否「先判路由」——入口与两种 Router 模板都算。
 *
 * <p>两种协作模式和入口都会先判路由。历史模板收口之后同样会判路由。
 *
 * @param raw 模板码；可空
 * @return true 表示会判路由
 */
export function routesByJudgeTemplate(raw?: string | null): boolean {
  if (isEntryRouterTemplate(raw)) return true
  const mode = canonicalMaTemplate(raw)
  return mode === MA_LIST_TEMPLATE_ROUTER_PARALLEL || mode === MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE
}

/**
 * 模板码 → Element Plus tag 类型，让各模板在列表里一眼可分。
 *
 * <p>意图路由分发用 primary，路由后串行用 success，入口按分发展示。
 *
 * @param raw 模板码
 * @return primary | success
 */
export function maTemplateTagType(raw?: string | null): 'primary' | 'success' | 'warning' | 'info' {
  return canonicalMaTemplate(raw) === MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE ? 'success' : 'primary'
}

/**
 * 模板悬浮说明。
 *
 * @param raw 模板码
 * @return 中文说明；未知值按默认模板
 */
export function maTemplateHint(raw?: string | null): string {
  const key = canonicalMaTemplate(raw)
  return MA_TEMPLATE_HINTS[key] || MA_TEMPLATE_HINTS[MA_LIST_TEMPLATE_ROUTER_PARALLEL]
}
