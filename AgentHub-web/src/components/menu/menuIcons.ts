/**
 * 侧栏 / 工作台的菜单图标解析入口（首屏热路径）。所属域：前端壳。
 *
 * <p>为什么不再一张表全量 import：`@element-plus/icons-vue` 是单文件 barrel，
 * 谁 import 谁就把用到的图标带进自己的 chunk。旧实现把 46 个候选图标全部 import，
 * 而侧栏 `AdminLayout` 与工作台是首屏必经路径，等于每次首屏都白拉 39 个当前菜单没用到的图标（刀3 未做项）。
 *
 * <p>现在分两层：
 * 1. {@code HOT_MENU_ICONS}：默认菜单种子（`sql/data-init.sql` 的 sys_menu.icon）实际用到的名字，
 *    同步 import、同步渲染，首屏零额外请求、无闪烁；
 * 2. 其它名字（用户自己在菜单管理里挑的图标）：动态加载 `./menuIconCatalog`（那 46 个全量），
 *    按名字缓存成异步组件。加载期间先画 Setting，绝不出现空白。
 *
 * <p>约束：
 * 1. 本文件**不得**再静态 import 全量候选集，否则本节优化失效；
 * 2. {@code resolveMenuIcon} 必须在同一名字上返回**同一个组件引用**（模板每次渲染都会调它，
 *    返回新对象会让图标反复重挂），故异步分支也要走缓存。
 */
import { defineAsyncComponent } from 'vue'
import type { Component } from 'vue'
import {
  Aim,
  Coin,
  Collection,
  Connection,
  Cpu,
  DataAnalysis,
  Document,
  Finished,
  Guide,
  Link,
  MagicStick,
  Management,
  Memo,
  Menu as MenuIcon,
  Monitor,
  Notebook,
  OfficeBuilding,
  Search,
  Setting,
  Share,
  Tickets,
  User,
  UserFilled,
  VideoPlay,
  Warning
} from '@element-plus/icons-vue'

/**
 * 首屏同步图标表：覆盖默认菜单种子里出现过的全部 icon 名。
 *
 * <p>口径来自 `sql/data-init.sql` 的 `sys_menu.icon`；若后端种子新增了图标名，
 * 同步加到这里（不加也能显示，只是会走一次动态加载）。
 */
const HOT_MENU_ICONS: Record<string, Component> = {
  Aim,
  Coin,
  Collection,
  Connection,
  Cpu,
  DataAnalysis,
  Document,
  Finished,
  Guide,
  Link,
  MagicStick,
  Management,
  Memo,
  Menu: MenuIcon,
  Monitor,
  Notebook,
  OfficeBuilding,
  Search,
  Setting,
  Share,
  Tickets,
  User,
  UserFilled,
  VideoPlay,
  Warning
}

/**
 * 「名称 → 上一次解析结果」的缓存。
 *
 * <p>模板里每次渲染都会调 {@link resolveMenuIcon}；不缓存的话同一图标每帧都会被当成新组件，
 * 视图会被反复卸载重建。命中后返回同一个引用，Vue 才认得出「没变」。
 */
const resolved = new Map<string, Component>()
/** 由全量候选集懒加载出来的图标；键为图标名 */
const lazyIcons = new Map<string, Component>()

/**
 * 按名字懒加载全量候选集里的图标。
 *
 * <p>加载期间先渲染 {@code Setting} 占位（与旧实现的兜底图标一致，不会出现空白），
 * 模块到位后自动换成真正的图标；同一名字只创建一次异步组件。
 *
 * @param name 图标名
 * @return 可渲染的组件（首次可能是占位态）
 */
function lazyMenuIcon(name: string): Component {
  const cached = lazyIcons.get(name)
  if (cached) {
    return cached
  }
  const component = defineAsyncComponent({
    // 候选集是独立 chunk，只有真的用到罕见图标时才下载
    loader: async () => {
      try {
        const catalog = await import('./menuIconCatalog')
        return catalog.MENU_ICON_MAP[name] ?? Setting
      } catch {
        // 网络失败 / 分包缺失：退回兜底图标，绝不把图标位画成空白
        return Setting
      }
    },
    // 占位与兜底同图：加载中、失败都画 Setting，避免图标位突然空一块
    loadingComponent: Setting,
    delay: 0,
    suspensible: false
  })
  lazyIcons.set(name, component)
  return component
}

/**
 * 按名称取菜单图标组件。
 *
 * @param name 菜单表 icon 字段
 * @return 命中首屏表时返回同步组件；命中候选集时返回（缓存过的）异步组件；
 *         空值或两边都没有时返回 Setting
 */
export function resolveMenuIcon(name?: string): Component {
  const key = (name || '').trim()
  if (!key) {
    return Setting
  }
  const cached = resolved.get(key)
  if (cached) {
    return cached
  }
  const component = HOT_MENU_ICONS[key] ?? lazyMenuIcon(key)
  resolved.set(key, component)
  return component
}

/** 全量候选集 chunk 的预载句柄；重复调用复用同一个 promise，不重复发请求。 */
let catalogPreload: Promise<unknown> | undefined

/**
 * 预载「非常驻图标」所在的全量候选集。
 *
 * <p>为什么不靠首次渲染触发：非常驻图标走异步组件，渲染时才开始下载，
 * 会先画一帧兜底图标再换成真的。菜单数据到手时就把这批名字过一遍、提前发起下载，
 * 侧栏真正绘制时通常已就位，视觉与旧实现一致。
 *
 * <p>全部名字都在首屏表里时**不发请求**——默认菜单种子的情况即如此。
 *
 * @param names 当前菜单树里出现过的图标名（可含重复与空值）
 * @return 无需预载时返回 undefined
 */
export function preloadMenuIcons(names: string[]): Promise<unknown> | undefined {
  const missing = names.some((name) => {
    const key = (name || '').trim()
    return key !== '' && !HOT_MENU_ICONS[key] && !resolved.has(key)
  })
  if (!missing) {
    return undefined
  }
  // 预载失败只影响「首帧就位」，渲染侧还有兜底图标与重试，不能把异常抛给调用方
  catalogPreload ??= import('./menuIconCatalog').catch(() => {
    catalogPreload = undefined
    return undefined
  })
  return catalogPreload
}

/**
 * 判断名字是否需要走懒加载（不在首屏同步表里）。
 *
 * @param name 图标名
 * @return true 表示会触发全量候选集下载
 */
export function isLazyMenuIcon(name?: string): boolean {
  const key = (name || '').trim()
  return key !== '' && !HOT_MENU_ICONS[key]
}
