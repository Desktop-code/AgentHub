/**
 * 下拉用「全量 Agent」的分页累加。所属域：前端 common（agent 选择器工具）。
 *
 * <p>为什么单独一层：全站分页 {@code size} 上限是 100（后端 PageQuery 有 @Max(100)），
 * 但绑根 / 候选下拉需要「尽量全」。旧实现直接写 {@code size: 200} 会被分页校验驳回，
 * 用户只看到「Agent 列表加载失败」。把「每页 100、逐页累加、有硬顶」抽成纯函数后：
 * <ul>
 *   <li>视图层只负责把请求包成 fetcher；</li>
 *   <li>终止条件（空页 / 不满一页 / 已取满 total / 触顶）可被 node:test 直接覆盖。</li>
 * </ul>
 *
 * <p>线程 / 副作用：无。每次都从第 1 页开始，不做跨调用缓存（缓存由调用方按「是否已加载」自行决定）。
 */

/** 单页条数；必须 ≤ 后端分页上限 100 */
export const AGENT_PAGE_SIZE = 100

/** 累计硬顶：覆盖中小租户即可，防止异常数据把抽屉拖死 */
export const AGENT_FETCH_CAP = 500

/** 一页结果的最小形状：只依赖 records / total，便于测试注入假数据 */
export interface AgentPageSlice<T> {
  records?: T[] | null
  total?: number | null
}

/**
 * 按页取数的函数签名。
 *
 * @param current 页码，从 1 开始
 * @param size    每页条数
 * @return 该页结果；缺省按空页处理
 */
export type AgentPageFetcher<T> = (current: number, size: number) => Promise<AgentPageSlice<T> | undefined>

/**
 * 逐页累加，直到取完或触顶。
 *
 * <p>终止条件按顺序判断：
 * <ol>
 *   <li>本页为空 → 到底；</li>
 *   <li>本页不足 {@code pageSize} → 到底（含最后一页正好差一条的情形）；</li>
 *   <li>已知 total 且已累计 ≥ total → 到底；</li>
 *   <li>累计 ≥ cap → 触顶截断。</li>
 * </ol>
 *
 * @param fetchPage 取页函数；任一页抛错直接向上抛，由调用方统一给中文提示
 * @param pageSize  每页条数，默认 {@link AGENT_PAGE_SIZE}；调用方不应超过 100
 * @param cap       累计硬顶，默认 {@link AGENT_FETCH_CAP}
 * @return 累加结果（保持服务端返回顺序，不重排、不去重）
 */
export async function collectAgentPages<T>(
  fetchPage: AgentPageFetcher<T>,
  pageSize: number = AGENT_PAGE_SIZE,
  cap: number = AGENT_FETCH_CAP
): Promise<T[]> {
  // 非法入参收敛到安全值：页大小至少 1，硬顶至少 1，避免死循环
  const size = Math.max(1, Math.floor(pageSize))
  const limit = Math.max(1, Math.floor(cap))
  const collected: T[] = []
  for (let current = 1; collected.length < limit; current += 1) {
    const page = await fetchPage(current, size)
    const batch = page?.records ?? []
    collected.push(...batch)
    // 空页或不满一页：服务端已没有更多数据，不必再打一次
    if (batch.length === 0 || batch.length < size) {
      break
    }
    // total 已知时提前收手；total 为 0 但仍有记录属异常数据，不能据此判断结束
    if (page?.total != null && page.total > 0 && collected.length >= page.total) {
      break
    }
  }
  // 硬顶只截尾部：超出部分丢弃，不改变前面的顺序
  return collected.length > limit ? collected.slice(0, limit) : collected
}
