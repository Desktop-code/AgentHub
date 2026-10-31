/**
 * 分页底栏的布局推导（纯函数）。所属域：前端壳。
 *
 * 两种模式，互不渗透：
 * - `table`（表格 / 列表视图）：完整分页 —— 总数 + 每页条数 + 页码 + 跳页，
 *   与本次改动前一致；pageSize 走调用方的 10/20/50/100 默认选项。
 * - `grid`（卡片墙）：精简底栏 —— 业务总数文案常显（如「共 12 个智能体」），
 *   页码仅在多页时出现，**不出现** sizes / jumper；pageSize 由一屏容量实测得出。
 *
 * 把推导抽成纯函数，便于 node:test 直接验证，不依赖 DOM。
 */

/** 分页底栏模式。 */
export type PagerMode = 'table' | 'grid'

/** 表格模式的完整布局：与精简改动前一致。 */
export const TABLE_PAGER_LAYOUT = 'total, sizes, prev, pager, next, jumper'

/** 网格模式的翻页部分（总数由插槽文案接管）。 */
const GRID_PAGER_PARTS = ['prev', 'pager', 'next']

/** 精简底栏必须剔除的控件：每页条数与跳页。 */
const GRID_FORBIDDEN_PARTS = ['sizes', 'jumper']

/** 参与布局推导的最小入参。 */
export interface PagerLayoutInput {
  /** grid=卡片墙精简底栏；table=表格完整分页 */
  mode: PagerMode
  /** 调用方给的布局串（table 模式生效；grid 模式只取其翻页控件顺序） */
  layout: string
  /** 业务总数文案；grid 模式为空时退回原生「共 x 条」 */
  totalText: string
  /** 总页数（已按 size 收敛，至少 1） */
  pageCount: number
}

/**
 * 计算总页数。
 *
 * @param total 总条数
 * @param size 每页条数
 * @return 总页数，至少 1
 */
export function pagerPageCount(total: number, size: number): number {
  const safeSize = Math.max(1, Number.isFinite(size) ? size : 1)
  const safeTotal = Number.isFinite(total) ? Math.max(0, total) : 0
  return Math.max(1, Math.ceil(safeTotal / safeSize))
}

/** 把布局串切成去空的片段数组。 */
function splitLayout(layout: string): string[] {
  return layout
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
}

/**
 * 推导实际传给 Element Plus 的布局串。
 *
 * table：原样透传（只做去空规整），并保证含有 sizes / jumper —— 表格页不允许被精简规则误伤。
 * grid：总数换成 slot（无文案时退回 total），剔除 sizes / jumper；
 *       单页时只留总数文案（页码藏起，总数仍常显）。
 *
 * @param input 推导入参
 * @return Element Plus layout 串
 */
export function resolvePagerLayout(input: PagerLayoutInput): string {
  const parts = splitLayout(input.layout)

  // 表格模式：完整分页。缺项时补齐，避免调用方漏传导致「条/页」「前往」消失
  if (input.mode === 'table') {
    const merged = [...new Set([...parts, 'total', 'sizes', 'prev', 'pager', 'next', 'jumper'])]
    // 保持「总数 → 条/页 → 翻页 → 跳页」的固定阅读顺序，不受入参顺序影响
    const order = ['total', 'sizes', 'prev', 'pager', 'next', 'jumper']
    return order.filter((part) => merged.includes(part)).join(', ')
  }

  // 网格模式：只保留总数文案与翻页控件
  const totalSlot = input.totalText ? 'slot' : 'total'
  const pagerParts = parts.filter(
    (part) => GRID_PAGER_PARTS.includes(part) && !GRID_FORBIDDEN_PARTS.includes(part)
  )
  const pageParts = pagerParts.length ? pagerParts : [...GRID_PAGER_PARTS]
  if (input.pageCount <= 1) {
    // 单页：页码没有意义，但总数文案必须常显
    return totalSlot
  }
  return [totalSlot, ...pageParts].join(', ')
}
