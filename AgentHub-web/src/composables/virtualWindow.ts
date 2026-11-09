/**
 * 定高窗口化（虚拟列表）纯计算。所属域：前端壳（列表复用层）。
 *
 * <p>只做一件事：给定「滚动位置 / 视口高度 / 行距 / 行数」，算出当前该渲染哪一段、
 * 以及这段在长列表里的偏移量与整表总高度。**不碰 DOM、不碰响应式**，方便单测。
 *
 * <p>设计取舍：
 * 1. 只支持**定高行**（行高允许在运行时实测后改变，但同一时刻所有行同高）；
 *    动态行高会把偏移量算成前缀和，复杂度与回归面都上一个量级，本次明确不做。
 * 2. `rowSize` 含行间距（卡片高度 + 间隙）。渲染容器总高 = `count * rowSize - gap`，
 *    减掉最后一行的尾隙，避免列表底部凭空多出一段空白。
 * 3. 上下各留 `overscan` 行缓冲：快速滚动时行还没进视口就已渲染好，
 *    不会出现「白条追着滚动条跑」；代价是多挂载几行 DOM。
 */

/** 默认上下缓冲行数（快速拖动滚动条时不露出空白）。 */
export const VIRTUAL_OVERSCAN = 6

/** 窗口化输入。 */
export interface VirtualWindowInput {
  /** 滚动容器当前 scrollTop */
  scrollTop: number
  /** 滚动容器可视高度 */
  viewport: number
  /** 单行占位（行高 + 行间距） */
  rowSize: number
  /** 行总数 */
  count: number
  /** 行间距，仅用于算总高度时扣掉尾隙 */
  gap?: number
  /** 上下缓冲行数，缺省 {@link VIRTUAL_OVERSCAN} */
  overscan?: number
}

/** 窗口化结果。 */
export interface VirtualWindow {
  /** 需要渲染的起始下标（含） */
  start: number
  /** 需要渲染的结束下标（不含） */
  end: number
  /** 该段相对列表顶部的偏移量（px），用于 translateY */
  offset: number
  /** 列表总高度（px），用于撑开占位容器 */
  total: number
}

/**
 * 计算当前可视窗口。
 *
 * @param input 见 {@link VirtualWindowInput}
 * @return 渲染区间与占位尺寸；参数非法（行距 ≤ 0、行数 ≤ 0）时返回空窗口，不抛异常
 */
export function computeVirtualWindow(input: VirtualWindowInput): VirtualWindow {
  const count = Math.max(0, Math.floor(input.count) || 0)
  const rowSize = input.rowSize
  // 行距未就绪（首帧尚未实测）或行数为 0：返回空窗口，调用方按「不渲染」处理
  if (count === 0 || !(rowSize > 0)) {
    return { start: 0, end: 0, offset: 0, total: 0 }
  }
  const gap = Math.max(0, input.gap ?? 0)
  const overscan = Math.max(0, Math.floor(input.overscan ?? VIRTUAL_OVERSCAN))
  // 滚动位置可能为负（macOS 回弹）或超出内容高度（数据被裁短），先夹紧再算
  const maxScroll = Math.max(0, count * rowSize - gap - Math.max(0, input.viewport))
  const scrollTop = Math.min(Math.max(0, input.scrollTop || 0), maxScroll)
  const first = Math.floor(scrollTop / rowSize)
  const visible = Math.ceil(Math.max(0, input.viewport) / rowSize) + 1
  // 上下各留 overscan 行；start 不小于 0，end 不超过 count
  const start = Math.max(0, Math.min(count - 1, first - overscan))
  const end = Math.min(count, first + visible + overscan)
  // 至少渲染一行：视口为 0（图层隐藏）时也要有内容，切层后不会先空一拍
  const safeEnd = Math.max(end, Math.min(count, start + 1))
  return {
    start,
    end: safeEnd,
    offset: start * rowSize,
    // 尾隙不占布局：count 行之间只有 count-1 个 gap
    total: Math.max(0, count * rowSize - gap)
  }
}

/**
 * 滚动到指定下标时该写的 scrollTop（让该行落在视口中部）。
 *
 * @param index 目标行下标
 * @param input 与 {@link computeVirtualWindow} 同源的尺寸参数
 * @return 夹紧后的 scrollTop；参数非法时返回 0
 */
export function scrollTopForIndex(index: number, input: Omit<VirtualWindowInput, 'scrollTop'>): number {
  const count = Math.max(0, Math.floor(input.count) || 0)
  const rowSize = input.rowSize
  if (count === 0 || !(rowSize > 0)) {
    return 0
  }
  const gap = Math.max(0, input.gap ?? 0)
  const viewport = Math.max(0, input.viewport)
  const maxScroll = Math.max(0, count * rowSize - gap - viewport)
  // 目标行顶部减去半个视口，让它在中间；再夹到合法滚动范围
  const centered = Math.max(0, Math.floor(index) || 0) * rowSize - (viewport - rowSize) / 2
  return Math.min(Math.max(0, centered), maxScroll)
}
