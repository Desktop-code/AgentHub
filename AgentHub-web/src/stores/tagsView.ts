/**
 * 多页签状态。所属域：前端布局（layout）。
 *
 * 职责：维护「已打开页签」列表与 KeepAlive 缓存名单，供 TagsView 组件与 Layout 共用。
 *
 * 关键约束：
 * 1. 页签唯一键用 `key`（= meta.cacheName，缺省退回 path），不用 fullPath：
 *    - 同一页面换 query（如 Agent 列表的 ?agentId=）只更新已有页签，不新开一个；
 *    - Agent 的 /agent/list 与 /agent/list/1 共用同一组件，按 path 会开出两个同名页签。
 * 2. `cachedViews` 由 `visitedViews` 派生（computed），关页签即自动移出缓存名单，
 *    不额外维护一份数组，从根上避免「缓存名单与页签不同步导致组件被一直留在内存」。
 * 3. KeepAlive 的 include 比对的是**组件名**，不是路由名。本项目页面文件全是 index.vue，
 *    不显式声明 name 时 Vue 会把组件名推导成 "index"，多页面会撞名。
 *    因此缓存名一律取 route.meta.cacheName，并在页面里用 defineOptions 显式声明同名。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { LocationQuery, RouteLocationNormalized } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { TRIAL_MENU_PATH } from '@/composables/agentTrial'
import type { MenuNode } from '@/types'

/** 一个页签。 */
export interface TagView {
  /** 唯一键：优先 meta.cacheName（同组件的不同 URL 合并为一个页签），缺省退回 path */
  key: string
  /** 该页签代表的路径（最近一次访问的路径） */
  path: string
  /** 最近一次的完整地址，用于「点击页签回到原状态」 */
  fullPath: string
  /** 标题，优先 meta.title，其次菜单名，最后取路径末段 */
  title: string
  /** 打开时的查询参数，点击页签时复原 */
  query: LocationQuery
  /** 缓存用组件名；为空表示不缓存 */
  cacheName?: string
  /** 固定页签：不可关闭，「关闭其他 / 关闭所有」都会保留 */
  affix?: boolean
}

/** 关闭页签后需要执行的动作，交由组件决定是否跳转。 */
export interface DelViewResult {
  /** 被关闭的页签是否正是当前激活页；false 时只需重渲染标签条 */
  closedActive: boolean
  /** 需要跳转到的路径；空串表示不需要跳转 */
  nextPath: string
}

/** 固定页签：工作台是本项目的默认落地页，常驻不可关，避免「关闭所有」后主区空白。 */
const AFFIX_TAGS: TagView[] = [
  {
    key: 'Workspace',
    path: '/workspace',
    fullPath: '/workspace',
    title: '对话',
    query: {},
    cacheName: 'Workspace',
    affix: true
  }
]

/**
 * 登录页等不该出现在页签里的路径。
 *
 * @param path 路由路径
 * @return true 表示忽略该路由
 */
function isIgnoredPath(path: string) {
  return !path || path === '/login'
}

/**
 * 从菜单树里找与当前路径最匹配的菜单名。
 * 详情页（如 /agent/list/1）走前缀匹配，取最长的那个前缀，保证落到「Agent 管理」而不是父目录。
 *
 * @param path 当前路由路径
 * @return 菜单名；找不到返回空串
 */
function titleFromMenus(path: string): string {
  const user = useUserStore()
  let title = ''
  let matchedLength = -1

  /** 深度遍历菜单树，只比较可见且带 path 的节点 */
  const walk = (nodes: MenuNode[] | undefined) => {
    for (const node of nodes ?? []) {
      if (node.visible === 0) {
        continue
      }
      const nodePath = node.path ?? ''
      const hit =
        nodePath &&
        nodePath !== TRIAL_MENU_PATH &&
        (path === nodePath || path.startsWith(`${nodePath}/`))
      if (hit && nodePath.length > matchedLength) {
        matchedLength = nodePath.length
        title = node.name ?? ''
      }
      if (node.children && node.children.length > 0) {
        walk(node.children)
      }
    }
  }

  walk(user.profile?.menus)
  return title
}

/**
 * 解析页签标题。
 *
 * @param route 当前路由
 * @return 非空标题字符串
 */
function resolveTitle(route: RouteLocationNormalized): string {
  const metaTitle = route.meta?.title
  if (typeof metaTitle === 'string' && metaTitle.trim()) {
    return metaTitle.trim()
  }
  const fromMenu = titleFromMenus(route.path)
  if (fromMenu) {
    return fromMenu
  }
  // 兜底：取路径最后一段，至少保证页签可辨识，不出现空白标签
  const segments = route.path.split('/').filter(Boolean)
  return segments[segments.length - 1] || route.path
}

/**
 * 解析 KeepAlive 缓存名，同时作为页签唯一键。
 *
 * @param route 当前路由
 * @return 组件名；返回 undefined 表示该页不缓存
 */
function resolveCacheName(route: RouteLocationNormalized): string | undefined {
  const metaName = route.meta?.cacheName
  if (typeof metaName === 'string' && metaName.trim()) {
    return metaName.trim()
  }
  return undefined
}

export const useTagsViewStore = defineStore('tagsView', () => {
  /** 已打开页签，初始带上固定页签 */
  const visitedViews = ref<TagView[]>([...AFFIX_TAGS])

  /** KeepAlive include 名单：由页签派生并去重，关页签即移出 */
  const cachedViews = computed<string[]>(() => {
    const names: string[] = []
    for (const view of visitedViews.value) {
      if (view.cacheName && !names.includes(view.cacheName)) {
        names.push(view.cacheName)
      }
    }
    return names
  })

  /**
   * 新增或更新页签（按 path 去重）。
   * 重复打开同一页面时只刷新 query / fullPath / 标题，不产生第二个标签。
   *
   * @param route 刚进入的路由
   */
  function addView(route: RouteLocationNormalized) {
    if (isIgnoredPath(route.path)) {
      return
    }
    const cacheName = resolveCacheName(route)
    const next: TagView = {
      // 有 cacheName 就按组件归并，否则退化为按路径，保证至少能开标签
      key: cacheName ?? `path:${route.path}`,
      path: route.path,
      fullPath: route.fullPath,
      title: resolveTitle(route),
      query: { ...route.query },
      cacheName
    }

    const existing = visitedViews.value.find((view) => view.key === next.key)
    if (existing) {
      // 已存在：保留 affix 标记，仅就地更新动态部分，避免标签闪烁重排
      existing.path = next.path
      existing.fullPath = next.fullPath
      existing.query = next.query
      existing.title = next.title
      existing.cacheName = next.cacheName
      return
    }

    /*
     * 固定页签在初始化时已排在最前且永不移除，这里直接追加到末尾。
     * 不可用 splice(affixCount, 0, …)：那样每次都会插到索引 1，
     * 导致标签顺序与新打开顺序相反。
     */
    visitedViews.value.push(next)
  }

  /**
   * 关闭单个页签。
   *
   * @param view       要关闭的页签
   * @param activeKey  当前激活页签的 key，用于判断关闭后是否需要跳转
   * @return 关闭结果：是否关掉了激活页、以及应该跳去的备选路径
   */
  function delView(view: TagView, activeKey: string): DelViewResult {
    // 固定页签不可关闭，直接忽略
    if (view.affix) {
      return { closedActive: false, nextPath: '' }
    }

    const index = visitedViews.value.findIndex((item) => item.key === view.key)
    if (index === -1) {
      return { closedActive: false, nextPath: '' }
    }

    const closedActive = view.key === activeKey
    visitedViews.value.splice(index, 1)
    if (!closedActive) {
      return { closedActive: false, nextPath: '' }
    }

    // 优先落到右侧邻居（原位置即后一个），没有则落左侧，最后兜底固定页签
    const next = visitedViews.value[index] ?? visitedViews.value[index - 1]
    return { closedActive: true, nextPath: next?.fullPath ?? AFFIX_TAGS[0].path }
  }

  /**
   * 关闭除指定页签外的所有页签（固定页签保留）。
   *
   * @param view       要保留的页签
   * @param activeKey  当前激活页签的 key
   * @return 关闭后的跳转结果
   */
  function delOthersViews(view: TagView, activeKey: string): DelViewResult {
    visitedViews.value = visitedViews.value.filter((item) => item.affix || item.key === view.key)
    // 保留的是否为激活页：不是则需要跳到它，避免主区停在已关闭的页面
    const closedActive = view.key !== activeKey
    return { closedActive, nextPath: closedActive ? view.fullPath : '' }
  }

  /**
   * 关闭全部页签，仅留固定页签。
   *
   * @return 关闭后的跳转结果（总会跳到固定页签）
   */
  function delAllViews(): DelViewResult {
    visitedViews.value = visitedViews.value.filter((item) => item.affix)
    return { closedActive: true, nextPath: visitedViews.value[0]?.path ?? AFFIX_TAGS[0].path }
  }

  return { visitedViews, cachedViews, addView, delView, delOthersViews, delAllViews }
})
