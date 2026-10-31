/**
 * 卡片墙「一屏容量」分页。所属域：前端壳。
 *
 * 目标：pageSize 跟随当前栅格一屏能摆下的卡片数（列 × 行），
 * 避免「一屏还有空位，却因为写死 10 条凭空多出一页」。
 *
 * 关键约束：
 * 1. 只量本机 DOM 尺寸，不改任何后端分页协议；结果上限 100，与后端 PageQuery.size 的 @Max(100) 对齐。
 * 2. 列数与 grid-template-columns: repeat(auto-fill, minmax(minColumnWidth, 1fr)) 用同一公式推算；
 *    行数按滚动容器剩余可视高度 ÷（卡片高 + 行间距）推算，并扣掉底部精简分页条的预留高度。
 * 3. 尺寸变化（window resize / 侧栏折叠导致的容器宽度变化）防抖后重算；
 *    算出的 pageSize 未变时不触发任何回调，避免同值反复重新取数。
 * 4. 量不到容器（首帧未渲染、被 display:none 藏起）时保留上一次结果，禁止把 pageSize 归零。
 */
import { onActivated, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/** 卡片墙分页量算参数。 */
export interface GridPageSizeOptions {
  /** 卡片高度（含 1px 边框），与 ConfigCard normal=160 / small=120 一致 */
  cardHeight: number
  /** 行/列间距，必须与 .grid 的 gap 一致 */
  gap?: number
  /** 单列最小宽度，必须与 grid-template-columns 的 minmax 下限一致 */
  minColumnWidth?: number
  /** 一屏最多按几行算，防御超高视口把 pageSize 顶到后端上限 */
  maxRows?: number
  /** 每页条数上限，默认 100（后端 PageQuery 的 @Max） */
  maxPageSize?: number
}

/** 量出来的栅格可用空间。 */
export interface GridContainerMetrics {
  /** 栅格可用宽度（px） */
  width: number
  /** 栅格可用高度（px，已扣底部预留） */
  height: number
}

/** ConfigCard normal 档卡片高度（含 1px 边框），各卡片墙直接引用，勿另写字面量 */
export const CARD_HEIGHT_NORMAL = 160
/** ConfigCard small 档卡片高度（含 1px 边框） */
export const CARD_HEIGHT_SMALL = 120

/** 与 .grid 的 gap: 16px 保持一致 */
const DEFAULT_GAP = 16
/** 与 .grid 的 minmax(280px, 1fr) 保持一致 */
const DEFAULT_MIN_COLUMN_WIDTH = 280
/** 一屏行数上限；再多也只是把 pageSize 撑大，不影响观感 */
const DEFAULT_MAX_ROWS = 6
/** 后端 PageQuery.size 的上限，前端不得越过 */
const MAX_PAGE_SIZE = 100
/** resize 防抖：连续拖动窗口只在停下后量一次 */
const RESIZE_DEBOUNCE_MS = 150
/** 底部预留：精简分页条（32px 高 + 12px 上间距）连同少量余量，避免最后一行被压到折页线以下 */
const DEFAULT_BOTTOM_RESERVE = 56

/**
 * 推算栅格列数。
 * 与 CSS auto-fill 同公式：n 列需要 n*minColumnWidth + (n-1)*gap ≤ width。
 *
 * @param width 栅格可用宽度
 * @param options 量算参数
 * @return 列数，最少 1
 */
export function calcGridColumns(width: number, options: GridPageSizeOptions): number {
  const gap = options.gap ?? DEFAULT_GAP
  const minWidth = Math.max(1, options.minColumnWidth ?? DEFAULT_MIN_COLUMN_WIDTH)
  if (!Number.isFinite(width) || width <= 0) {
    return 1
  }
  return Math.max(1, Math.floor((width + gap) / (minWidth + gap)))
}

/**
 * 推算一屏能摆几行卡片。
 *
 * @param height 栅格可用高度（已扣底部预留）
 * @param options 量算参数
 * @return 行数，区间 [1, maxRows]
 */
export function calcGridRows(height: number, options: GridPageSizeOptions): number {
  const gap = options.gap ?? DEFAULT_GAP
  const cardHeight = Math.max(1, options.cardHeight)
  const maxRows = Math.max(1, options.maxRows ?? DEFAULT_MAX_ROWS)
  if (!Number.isFinite(height) || height <= 0) {
    return 1
  }
  const rows = Math.floor((height + gap) / (cardHeight + gap))
  return Math.min(Math.max(1, rows), maxRows)
}

/**
 * 一屏容量 = 列 × 行，并按后端上限收敛。
 *
 * @param container 量出来的栅格可用空间
 * @param options 量算参数
 * @return 每页条数，区间 [1, 100]
 */
export function calcGridPageSize(container: GridContainerMetrics, options: GridPageSizeOptions): number {
  const columns = calcGridColumns(container.width, options)
  const rows = calcGridRows(container.height, options)
  const maxPageSize = Math.min(options.maxPageSize ?? MAX_PAGE_SIZE, MAX_PAGE_SIZE)
  return Math.max(1, Math.min(columns * rows, maxPageSize))
}

/**
 * 末页页码；总数或每页条数非法时返回 1。
 * 供「pageSize 变大导致当前页越界 → 落到合法末页」使用。
 *
 * @param total 总条数
 * @param size 每页条数
 * @return 末页页码（≥ 1）
 */
export function lastPage(total: number, size: number): number {
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(size) || size <= 0) {
    return 1
  }
  return Math.max(1, Math.ceil(total / size))
}

/**
 * 找最近的纵向滚动容器，用于把「卡片区在滚动内容里的位置」换算成剩余可视高度。
 *
 * @param el 栅格容器
 * @return 滚动祖先；没有则返回 null（退化为整窗高度）
 */
function findScrollParent(el: HTMLElement): HTMLElement | null {
  let node = el.parentElement
  while (node) {
    const style = window.getComputedStyle(node)
    if (/(auto|scroll)/.test(style.overflowY) && node.clientHeight > 0) {
      return node
    }
    node = node.parentElement
  }
  return null
}

/**
 * 量栅格容器的可用宽高。
 * 高度用「滚动内容内偏移」换算，且把 scrollTop 加回去，
 * 这样页面已经滚动时也不会把整屏当成空白区而算出过多行。
 *
 * @param el 栅格容器（.grid-wrap）
 * @param bottomReserve 底部预留高度
 * @return 量算结果；容器宽为 0（未渲染 / 被 display:none 藏起）时返回 null
 */
export function measureGridContainer(
  el: HTMLElement | null,
  bottomReserve = DEFAULT_BOTTOM_RESERVE
): GridContainerMetrics | null {
  if (!el) {
    return null
  }
  const width = el.clientWidth
  if (!width) {
    return null
  }
  const scroller = findScrollParent(el)
  if (!scroller) {
    const top = el.getBoundingClientRect().top
    return { width, height: Math.max(0, window.innerHeight - top - bottomReserve) }
  }
  const box = scroller.getBoundingClientRect()
  // + scrollTop：换算成「滚动内容坐标系」，与当前滚动位置无关
  const offsetInScroller = el.getBoundingClientRect().top - box.top + scroller.scrollTop
  return { width, height: Math.max(0, scroller.clientHeight - offsetInScroller - bottomReserve) }
}

/** useGridPageSize 入参。 */
export interface UseGridPageSizeConfig {
  /** 取栅格容器（.grid-wrap）；返回 null 表示当前量不到 */
  container: () => HTMLElement | null
  /** 量算参数 */
  options: GridPageSizeOptions
  /** 底部预留高度，默认 56 */
  bottomReserve?: number
}

/** useGridPageSize 返回值。 */
export interface UseGridPageSizeResult {
  /** 当前算出的每页条数；0 表示尚未测量 */
  pageSize: Ref<number>
  /** 首次测量完成的 Promise（首屏取数用，避免先按默认条数取一次再补取） */
  whenReady: () => Promise<number>
  /** 立即重算一次（不防抖） */
  remeasure: () => number
}

/**
 * 组件内使用的一屏容量测量器。
 * 挂载后立即量一次，并在 window resize / 容器宽度变化 / KeepAlive 重新激活时防抖重算。
 * 只把结果放进 pageSize ref，是否重新取数由调用方 watch 决定。
 *
 * @param config 容器与量算参数
 * @return 容量状态与测量入口
 */
export function useGridPageSize(config: UseGridPageSizeConfig): UseGridPageSizeResult {
  const pageSize = ref(0)
  let resolveReady: ((size: number) => void) | null = null
  let readySettled = false
  let timer = 0
  let observer: ResizeObserver | null = null
  /** 上一次观察到的容器宽度；只关心宽度变化，行数变化引起的高度变化不重算 */
  let lastWidth = 0

  const ready = new Promise<number>((resolve) => {
    resolveReady = resolve
  })

  /** 首次测量完成后兑现 whenReady，后续调用拿到的是已兑现的 Promise */
  function settleReady(): void {
    if (readySettled) {
      return
    }
    readySettled = true
    resolveReady?.(pageSize.value)
    resolveReady = null
  }

  /** 量一次并写回 pageSize；量不到则保持原值 */
  function remeasure(): number {
    const metrics = measureGridContainer(config.container(), config.bottomReserve)
    if (metrics) {
      const next = calcGridPageSize(metrics, config.options)
      if (next !== pageSize.value) {
        pageSize.value = next
      }
    }
    settleReady()
    return pageSize.value
  }

  /** 防抖重算：连续 resize 只在停下后量一次 */
  function schedule(): void {
    if (timer) {
      return
    }
    timer = window.setTimeout(() => {
      timer = 0
      remeasure()
    }, RESIZE_DEBOUNCE_MS)
  }

  onMounted(() => {
    remeasure()
    /*
     * 侧栏折叠会改容器宽度但不触发 window resize，补一层 ResizeObserver。
     * 只认宽度变化：pageSize 变化导致的容器高度变化不应再触发重算。
     */
    const el = config.container()
    if (el && typeof ResizeObserver !== 'undefined') {
      lastWidth = el.clientWidth
      observer = new ResizeObserver(() => {
        const width = config.container()?.clientWidth ?? 0
        if (width === lastWidth) {
          return
        }
        lastWidth = width
        schedule()
      })
      observer.observe(el)
    }
    window.addEventListener('resize', schedule)
  })

  /*
   * KeepAlive 页面重新激活时主区尺寸可能已变（侧栏折叠、窗口变化在后台发生），
   * 再量一次；量到的值与上次相同时下游 watch 自然不会重新取数。
   */
  onActivated(() => {
    remeasure()
  })

  onBeforeUnmount(() => {
    window.clearTimeout(timer)
    timer = 0
    observer?.disconnect()
    observer = null
    window.removeEventListener('resize', schedule)
    settleReady()
  })

  return { pageSize, whenReady: () => ready, remeasure }
}
