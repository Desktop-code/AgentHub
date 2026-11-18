/**
 * 树表懒加载投影。所属域：前端共用（admin 菜单 / 部门树）。
 *
 * el-table 非 lazy 时，每个根都会同步为全部子孙创建行 vnode；
 * 再绑受控 expand-row-keys，每次点展开都会按整棵树重建 treeData。
 * 菜单树把按钮也挂在目录下，点一次「智能体中心」就会把主线程堵在这次全量渲染上。
 * 完整树仍一次收下，表格只挂当前层；子节点在展开回调里立刻给出，不加人为延迟。
 */
import { nextTick, ref, type Ref } from 'vue'

/** 带 id 与可选 children 的树节点。 */
export interface TreeLike {
  id: number
  children?: TreeLike[]
}

/** 交给 el-table 的一行：去掉 children，只用 hasChildren 表示还能展开。 */
export type LazyTreeRow<T> = Omit<T, 'children'> & { hasChildren: boolean }

/**
 * 把一个节点收成懒加载行。
 *
 * @param node 原始节点（children 仍留在源树上，不改源对象）
 * @return 不含 children 的行；有下级时 hasChildren 为 true
 */
export function toLazyTreeRow<T extends TreeLike>(node: T): LazyTreeRow<T> {
  const { children, ...rest } = node
  return {
    ...rest,
    hasChildren: Array.isArray(children) && children.length > 0
  }
}

/**
 * 建 id → 直接子节点索引，供展开时 O(1) 取下一层。
 *
 * @param nodes 完整树
 * @return 仅包含「有子节点」的 id
 */
export function indexTreeChildren<T extends TreeLike>(nodes: T[]): Map<number, T[]> {
  const map = new Map<number, T[]>()
  const walk = (list: T[]) => {
    for (const node of list) {
      const children = node.children as T[] | undefined
      if (children?.length) {
        map.set(node.id, children)
        walk(children)
      }
    }
  }
  walk(nodes)
  return map
}

/**
 * 取某一层的懒加载行。
 *
 * @param index 子节点索引
 * @param id    父节点 id
 * @return 直接子节点；没有则为空数组
 */
export function lazyChildrenOf<T extends TreeLike>(index: Map<number, T[]>, id: number): LazyTreeRow<T>[] {
  return (index.get(id) || []).map((node) => toLazyTreeRow(node))
}

/**
 * 按深度升序排展开 id，刷新后先展开祖先再展开子孙。
 *
 * @param nodes 完整树
 * @param ids   当前记下的展开 id
 * @return 浅层在前的副本
 */
export function sortIdsByTreeDepth<T extends TreeLike>(nodes: T[], ids: number[]): number[] {
  const depth = new Map<number, number>()
  const walk = (list: T[], level: number) => {
    for (const node of list) {
      depth.set(node.id, level)
      if (node.children?.length) {
        walk(node.children as T[], level + 1)
      }
    }
  }
  walk(nodes, 0)
  return [...ids].sort((a, b) => (depth.get(a) ?? 0) - (depth.get(b) ?? 0))
}

/**
 * 在源树上按 id 改字段。展开时会从源树重新投影，只改表格行副本会在收起再展开后丢。
 *
 * @param nodes 完整树
 * @param id    目标 id
 * @param patch 就地修改
 * @return 是否找到
 */
export function patchTreeNode<T extends TreeLike>(nodes: T[], id: number, patch: (node: T) => void): boolean {
  for (const node of nodes) {
    if (node.id === id) {
      patch(node)
      return true
    }
    const children = node.children
    if (children?.length && patchTreeNode(children as T[], id, patch)) {
      return true
    }
  }
  return false
}

/** el-table 暴露的展开方法；只依赖这一处，避免页面各自操作内部 store。 */
export interface TreeTableHandle {
  /** 与 el-table 的 DefaultRow 一致，页面可直接传入表格实例 */
  toggleRowExpansion: (row: Record<PropertyKey, any>, expanded?: boolean) => void
}

/**
 * 菜单 / 部门共用的懒加载树表状态。
 *
 * @return 源树、表格行、展开记录，以及加载 / 还原方法
 */
export function useLazyTreeTable<T extends TreeLike>() {
  /** 接口返回的完整树，下拉与回写都读它 */
  const source = ref([]) as Ref<T[]>
  /** 用户展开过的 id；刷新数据后按这个还原，不把受控键绑回表格 */
  const expandedIds = ref<number[]>([])
  /** 表格当前层。不含未展开的子孙 */
  const tableRows = ref([]) as Ref<LazyTreeRow<T>[]>
  const childIndex = new Map<number, T[]>()
  /** 已交给表格的行，还原展开时要拿同一引用 */
  const rowById = new Map<number, LazyTreeRow<T>>()

  /**
   * 记住已投影的行，后面按 id 找回表格里的那一份。
   *
   * @param rows 刚投影出来的行
   */
  function remember(rows: LazyTreeRow<T>[]) {
    for (const row of rows) {
      rowById.set(row.id, row)
    }
  }

  /**
   * 换一整棵树。展开 id 保留，行投影只到根层。
   *
   * @param nodes 新的完整树
   */
  function replace(nodes: T[]) {
    source.value = nodes
    childIndex.clear()
    indexTreeChildren(nodes).forEach((children, id) => childIndex.set(id, children))
    rowById.clear()
    const roots = nodes.map((node) => toLazyTreeRow(node))
    remember(roots)
    tableRows.value = roots
  }

  /**
   * el-table lazy load：子节点已经在内存里，回调里立刻给出，不再请求。
   *
   * @param row     被展开的行
   * @param _node   表格内部树节点，这里不用
   * @param resolve 表格要求的子行回调
   */
  function loadChildren(row: LazyTreeRow<T>, _node: unknown, resolve: (rows: LazyTreeRow<T>[]) => void) {
    const children = lazyChildrenOf(childIndex, row.id)
    remember(children)
    resolve(children)
  }

  /**
   * 记下展开 / 收起。不回写 expand-row-keys，避免表格每次点击整树重建。
   *
   * @param row      当前行
   * @param expanded 是否展开
   */
  function onExpandChange(row: { id: number }, expanded: boolean) {
    const id = row.id
    if (expanded) {
      if (!expandedIds.value.includes(id)) {
        expandedIds.value = [...expandedIds.value, id]
      }
      return
    }
    expandedIds.value = expandedIds.value.filter((item) => item !== id)
  }

  /**
   * 从展开记录里摘掉一个 id（删除节点后）。
   *
   * @param id 节点 id
   */
  function forgetExpanded(id: number) {
    expandedIds.value = expandedIds.value.filter((item) => item !== id)
  }

  /**
   * 数据刷新后按记录逐层展开。必须先祖先后子孙，否则子行还没进表格。
   *
   * @param table 表格实例；未挂上则跳过
   */
  async function restore(table: TreeTableHandle | null | undefined) {
    await nextTick()
    if (!table) {
      return
    }
    const ordered = sortIdsByTreeDepth(source.value, expandedIds.value)
    for (const id of ordered) {
      const row = rowById.get(id)
      if (!row?.hasChildren) {
        continue
      }
      table.toggleRowExpansion(row, true)
      // 等表格把这一层写进 treeData，下一层才能按同一行引用展开
      await nextTick()
    }
  }

  return {
    source,
    tableRows,
    expandedIds,
    replace,
    loadChildren,
    onExpandChange,
    forgetExpanded,
    restore
  }
}
