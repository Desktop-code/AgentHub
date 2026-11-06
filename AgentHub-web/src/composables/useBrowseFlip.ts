/**
 * 「网格 ↔ 主从」双形态浏览骨架。所属域：前端壳（browse 复用层）。
 *
 * 目标：把四个可浏览页（agent / skill / tool / model）里逐页复制的同一套机械逻辑收口成一份：
 * 视图态与图层显隐、一屏容量分页、网格筛选草稿/生效、飞卡进场与返回、左栏关键词过滤。
 *
 * 关键约束：
 * 1. **只收骨架、不收业务**：数据取数、卡片内容、右栏详情、弹窗全部留在页面；
 *    页面通过 `prepare` / `landed` / `resolveRailTarget` / `prepareLeave` / `left` /
 *    `beforeLeave` / `enterEmpty` / `onReturnToGrid` 钩子插入自己的清理、预取与确认，
 *    骨架内部没有任何针对具体页面的分支。
 * 2. 飞卡时序与拆分前逐字节一致：切层后等两个 nextTick 再找落点，落地成功才写数据；
 *    `runFlip` 返回 false 时保持网格态。
 * 3. 一屏容量只在「回到网格」后重申一次：主从态下网格层是 display:none，浏览器不给它算宽度。
 * 4. 视图模式**不做路由同步**（与现有四页一致）；需要 URL 同步的页面在 landed / left 里自己做。
 * 5. 两种分页模型的差异用 `takeOverGrid` 表达，而不是分支：
 *    - `true`（默认）：网格数据是前端全量，骨架负责筛选 + 前端切片 + 一屏容量测量；
 *    - `false`：网格数据已是服务端返回的**当前页**，骨架只做视图态与导航，不碰筛选/切片/测量。
 */
import { computed, nextTick, reactive, ref, watch, type Ref } from 'vue'
import { useViewFlip, type FlipCardSnap } from '@/composables/viewFlip'
import { CARD_HEIGHT_NORMAL, lastPage, useGridPageSize } from '@/composables/gridPageSize'

/** 落地/回网格时告知页面「这次是否真的走了飞卡动画」。 */
export interface BrowseFlipMeta {
  /** true=走过 FLIP 动画；false=找不到起飞/落点元素，直接切态 */
  animated: boolean
}

/** 回网格收尾的上下文。 */
export interface BrowseFlipLeaveResult extends BrowseFlipMeta {
  /** 飞卡是否成功（动画走完且落点命中）；false 时页面通常需要重取当前页 */
  ok: boolean
  /** 骨架清空前记录的原左栏关键词；页面可据此决定是否整份丢弃左栏分页 */
  prevRailKeyword: string
}

/** 骨架接入参数。 */
export interface BrowseFlipOptions<T, F extends Record<string, unknown>> {
  /** 网格数据源（`takeOverGrid=false` 时是服务端当前页） */
  rows: Ref<T[]>
  /** 取行主键（用于定位元素与选中态） */
  idOf: (row: T) => number
  /** 飞卡快照：标题 / 副行 / 标签 */
  snapOf: (row: T) => FlipCardSnap
  /** 筛选条件初始值；与 `matchFilter` 一起提供才启用前端筛选 + 切片 */
  createFilter?: () => F
  /** 网格卡是否命中生效筛选条件；缺省时不做前端筛选 */
  matchFilter?: (row: T, filter: F) => boolean
  /**
   * 左栏关键词是否命中该行。
   * 服务端搜索的页面（左栏自己按关键词取数）传 `() => true`，只借骨架的关键词框。
   */
  matchRail: (row: T, keyword: string) => boolean
  /** 左栏数据源；缺省与 `rows` 相同。服务端分页的页面传自己的 `railSource` */
  railSource?: Ref<T[]>
  /** 一屏容量量算用的卡片高度，默认 ConfigCard normal 档 160 */
  cardHeight?: number
  /** 网格卡选择器（按 `idAttr` 查落点），默认 `.layer-grid .card` */
  gridCardClass?: string
  /** 左栏卡选择器，默认 `.layer-master .rail-card` */
  railCardClass?: string
  /** 卡片上的主键属性名，默认 `data-browse-id`（agent 页用 `data-agent-id`） */
  idAttr?: string
  /**
   * 是否接管网格筛选/前端切片/一屏容量测量。
   * 缺省由 `createFilter` + `matchFilter` 是否同时提供决定；服务端分页页显式传 false。
   */
  takeOverGrid?: boolean
  /**
   * 起飞前页面准备：清空旧详情、回到第一页、发起预取。
   * 返回值原样传给 `landed`，用于「动画与请求并行」。
   */
  prepare?: (row: T) => unknown
  /** 落地成功后的写数据；`prepared` 为 `prepare` 的返回值 */
  landed?: (row: T, prepared: unknown, meta: BrowseFlipMeta) => void | Promise<void>
  /**
   * 去程切层后的落点解析，缺省按主键找左栏卡。
   * 左栏是懒加载/可滚动列表时，页面需要先滚到落点再返回节点（否则幽灵会飞出可视区）。
   */
  resolveRailTarget?: (row: T) => HTMLElement | null | Promise<HTMLElement | null>
  /** 回网格切换后、量落点前调用：网格缺该卡时先补数（页面自行判断是否需要发请求） */
  prepareLeave?: (row: T) => void | Promise<void>
  /** 回网格前否决钩子（未保存草稿确认）；返回 false 则不切换 */
  beforeLeave?: () => boolean | Promise<boolean>
  /** 回网格后的收尾（清详情、URL、必要时重取） */
  left?: (result: BrowseFlipLeaveResult) => void | Promise<void>
  /**
   * 回到网格、图层可见后的页面侧补量钩子。
   * 服务端分页页（`takeOverGrid=false`）用自己的 `useGridPageSize`，需要在这里重申一次容量。
   */
  onReturnToGrid?: () => void
  /** 网格为空时点「列表」的落点；缺省保持网格态（与原实现一致） */
  enterEmpty?: () => void | Promise<void>
}

/**
 * 装配一套网格↔主从浏览骨架。
 *
 * @param options 见 {@link BrowseFlipOptions}
 * @return 供模板直接使用的视图态、分页、筛选与导航入口
 */
export function useBrowseFlip<T, F extends Record<string, unknown> = Record<string, unknown>>(
  options: BrowseFlipOptions<T, F>
) {
  const cardHeight = options.cardHeight ?? CARD_HEIGHT_NORMAL
  const idAttr = options.idAttr ?? 'data-browse-id'
  const gridSelector = options.gridCardClass ?? '.layer-grid .card'
  const railSelector = options.railCardClass ?? '.layer-master .rail-card'
  /** 是否接管网格筛选/切片/容量测量；两个筛选配置都给了才算接管 */
  const takeOverGrid = options.takeOverGrid ?? (!!options.createFilter && !!options.matchFilter)

  const {
    busy: flipBusy,
    flyingId,
    ghostSnap,
    ghostFrame,
    ghostLanding,
    detailPhase,
    runFlip
  } = useViewFlip()

  /** 同页视图态；无路由同步。 */
  const viewMode = ref<'grid' | 'master'>('grid')
  /** 0=左栏「全部」跨行视图；>0=具体行（如某个供应商）。 */
  const activeId = ref(0)

  /** 网格筛选草稿（点查询才落到 gridFilter）。不接管网格时为占位空对象。 */
  const gridDraft = reactive(options.createFilter?.() ?? ({} as F)) as F
  /** 网格已生效筛选。 */
  const gridFilter = reactive(options.createFilter?.() ?? ({} as F)) as F
  /** 网格分页（前端切片）；size 由一屏容量实测得出，仅一页时只显示总数文案。 */
  const gridPage = reactive({ current: 1, size: 10 })

  /** 卡片墙容器（.grid-wrap）：一屏容量按它的宽高实测；不接管网格时页面自己持有 */
  const gridWrapRef = ref<HTMLElement | null>(null)
  const { pageSize: gridPageSize, remeasure: remeasureGridSize } = useGridPageSize({
    container: () => (takeOverGrid ? gridWrapRef.value : null),
    options: { cardHeight }
  })

  /** 飞卡时两层都留着；平时只显示当前态。 */
  const showGridLayer = computed(() => viewMode.value === 'grid' || flipBusy.value)
  const showMasterLayer = computed(() => viewMode.value === 'master' || flipBusy.value)

  /** 网格行：接管时按生效筛选过滤；不接管时视为服务端已筛好 */
  const filteredRows = computed(() => {
    if (!takeOverGrid || !options.matchFilter) {
      return options.rows.value
    }
    return options.rows.value.filter((row) => options.matchFilter!(row, gridFilter))
  })

  /** 当前页卡片；页码越界时回退到末页切片。不接管网格时等于 `rows` */
  const pagedRows = computed(() => {
    if (!takeOverGrid) {
      return options.rows.value
    }
    const list = filteredRows.value
    const size = gridPage.size
    const maxPage = Math.max(1, Math.ceil(list.length / size) || 1)
    const current = Math.min(gridPage.current, maxPage)
    const start = (current - 1) * size
    return list.slice(start, start + size)
  })

  /** 左栏关键词（服务端搜索页也用它驱动 watch） */
  const railKeyword = ref('')

  /** 左栏数据源：服务端分页页传自己的 railSource，否则与网格同源 */
  const railBase = computed<T[]>(() => (options.railSource ?? options.rows).value)

  /** 左栏行列表（本地关键词过滤；「全部」伪项不在此列，由页面自己渲染）。 */
  const railRows = computed(() => {
    const base = railBase.value
    const key = railKeyword.value.trim().toLowerCase()
    if (!key) {
      return base
    }
    return base.filter((row) => options.matchRail(row, key))
  })

  /*
   * 切回网格后补量一次：主从态下网格层是 display:none（.layer:not(.is-active)），
   * 浏览器不给它算宽度，容量会停在进主从前的值。等 DOM 真正可见再量；
   * pageSize 若变化，容量 watch 会把页码夹回合法范围。
   */
  watch(viewMode, (mode) => {
    if (mode !== 'grid') {
      return
    }
    void nextTick(() => {
      remeasureGridSize()
      // 服务端分页页自己持有的容量测量在这一刻补一次（骨架不接管网格时它才需要）
      options.onReturnToGrid?.()
    })
  })

  /**
   * 一屏容量变化 → 写回本地分页态（仅接管网格时）。
   * 网格是前端切片，无需重新请求；页码越界由 pagedRows 夹到末页，
   * 这里再把 current 落回去，避免底栏页码与实际切片不一致。
   */
  watch(gridPageSize, (next) => {
    if (!takeOverGrid || !next || next === gridPage.size) {
      return
    }
    gridPage.size = next
    gridPage.current = Math.min(gridPage.current, lastPage(filteredRows.value.length, next))
  })

  /** 查询：草稿整体写入生效条件并回到第一页。 */
  function applyGridFilter() {
    if (!takeOverGrid) {
      return
    }
    Object.assign(gridFilter, gridDraft)
    gridPage.current = 1
  }

  /** 重置：清空草稿与生效条件。 */
  function resetGridFilter() {
    if (!takeOverGrid || !options.createFilter) {
      return
    }
    Object.assign(gridDraft, options.createFilter())
    applyGridFilter()
  }

  /** 翻页时校正越界（删除后总数变少）。 */
  function onGridPageChange() {
    if (!takeOverGrid) {
      return
    }
    const maxPage = Math.max(1, Math.ceil(filteredRows.value.length / gridPage.size) || 1)
    if (gridPage.current > maxPage) {
      gridPage.current = maxPage
    }
  }

  /**
   * 按主键找网格卡或左栏卡。
   *
   * @param id 行主键
   * @param where 网格或左轨
   */
  function findRowEl(id: number, where: 'grid' | 'rail') {
    const root = where === 'grid' ? gridSelector : railSelector
    return document.querySelector<HTMLElement>(`${root}[${idAttr}="${id}"]`)
  }

  /** 在网格数据与左栏数据里找行（左栏可能是服务端分页的独立数据源） */
  function findRow(id: number) {
    return (
      railBase.value.find((item) => options.idOf(item) === id) ??
      options.rows.value.find((item) => options.idOf(item) === id)
    )
  }

  /**
   * 网格 → 主从：同页亮主从层、量落点、飞完再写详情数据。
   *
   * @param row 点中的行
   * @param event 点击事件（取起飞元素）
   */
  async function onGridCardClick(row: T, event: Event) {
    if (flipBusy.value) {
      return
    }
    const id = options.idOf(row)
    const fromEl =
      (event.currentTarget as HTMLElement | null) ||
      (event.target as HTMLElement | null)?.closest?.(gridSelector) ||
      findRowEl(id, 'grid')
    // 找不到起飞元素（例如网格层尚未渲染）：直接切态，不做动画，行为与拆分前一致
    if (!fromEl) {
      activeId.value = id
      viewMode.value = 'master'
      const prepared = options.prepare?.(row)
      await options.landed?.(row, prepared, { animated: false })
      return
    }

    activeId.value = id
    /*
     * 预取与动画并行：`prepare` 在起飞时就被调用，`landed` 才 await 结果。
     * 失败不影响动画，落地后仍有页面自己的 loading 兜底。
     */
    const prepared = options.prepare?.(row)
    const ok = await runFlip({
      snap: options.snapOf(row),
      fromEl,
      direction: 'to-master',
      detail: 'enter',
      prepare: async () => {
        viewMode.value = 'master'
        await nextTick()
        await nextTick()
        return options.resolveRailTarget ? await options.resolveRailTarget(row) : findRowEl(id, 'rail')
      }
    })
    if (ok) {
      await options.landed?.(row, prepared, { animated: true })
    }
  }

  /** 工具栏点「列表」：以当前页第一张飞入主从。 */
  async function enterListView() {
    // 用当前页第一张，保证网格 DOM 里有起飞元素
    const first = pagedRows.value[0]
    if (!first) {
      // 没有任何行：交给页面决定落点（如直接进「全部」汇总）
      await options.enterEmpty?.()
      return
    }
    const el = findRowEl(options.idOf(first), 'grid')
    const fakeEvent = { currentTarget: el } as unknown as Event
    await onGridCardClick(first, fakeEvent)
  }

  /**
   * 主从 → 网格。选中「全部」(activeId=0) 时无对应网格卡，直接切态。
   */
  async function backToGrid() {
    if (flipBusy.value) {
      return
    }
    // 草稿确认等否决：不通过就保持主从态，连清选中都不做
    if (options.beforeLeave) {
      const keep = await options.beforeLeave()
      if (!keep) {
        return
      }
    }
    const id = activeId.value
    const row = id > 0 ? findRow(id) : undefined
    const fromEl = id > 0 ? findRowEl(id, 'rail') : null
    const prevRailKeyword = railKeyword.value
    if (!id || !row || !fromEl) {
      viewMode.value = 'grid'
      activeId.value = 0
      railKeyword.value = ''
      await options.left?.({ ok: true, animated: false, prevRailKeyword })
      return
    }

    const ok = await runFlip({
      snap: options.snapOf(row),
      fromEl,
      direction: 'to-grid',
      detail: 'leave',
      prepare: async () => {
        viewMode.value = 'grid'
        await nextTick()
        await nextTick()
        // 网格页可能还没有这张卡（被全量覆盖过）：让页面决定是否先补数
        await options.prepareLeave?.(row)
        return findRowEl(id, 'grid')
      }
    })

    activeId.value = 0
    railKeyword.value = ''
    await options.left?.({ ok, animated: true, prevRailKeyword })
    if (!ok) {
      viewMode.value = 'grid'
    }
  }

  /**
   * ViewModeToggle：网格进主从 / 主从回网格。
   *
   * @param mode 目标视图
   */
  function onViewModeChange(mode: 'grid' | 'master') {
    if (mode === 'master') {
      void enterListView()
      return
    }
    void backToGrid()
  }

  return {
    // 视图态与飞卡
    viewMode,
    activeId,
    flipBusy,
    flyingId,
    ghostSnap,
    ghostFrame,
    ghostLanding,
    detailPhase,
    showGridLayer,
    showMasterLayer,
    // 网格筛选与分页
    gridDraft,
    gridFilter,
    gridPage,
    gridPageSize,
    gridWrapRef,
    filteredRows,
    pagedRows,
    applyGridFilter,
    resetGridFilter,
    onGridPageChange,
    // 左栏
    railKeyword,
    railRows,
    // 导航
    findRowEl,
    onGridCardClick,
    enterListView,
    backToGrid,
    onViewModeChange
  }
}
