/**
 * 按 begin 身份认父方案。所属域：runtime（前端投影）。
 *
 * <p>直播和回放共用这一条，不另写一套「先挂最后一个方案再纠正」。
 * begin id 的形状是 {@code p-<depth>-<父方案键|root>-<叶子键>}，父方案键在开跑时就已经写进身份。
 * 并行时多个方案的 begin 会先全部到达，专家排在当时最后一个方案后面；
 * 若只取「往前最近的、depth 更小的方案」，这些专家会全部进最后一个车间。
 * 身份里父键对得上哪张方案，就进哪张。对不上时不猜。
 * id 不是这套形状（旧回放、单测夹具）时，仍取最近的更浅方案。
 */

/** 认父时只需要的 begin 形状 */
export type BeginParentNode = {
  /** begin 帧上的参与者 id；没有时按旧规则认父 */
  participantId?: string
  /** begin 帧上的 depth；没有数字就不认父 */
  depth?: number
  /** begin 帧上的 kind；只有 scheme 能当父 */
  kind: string
}

/**
 * 这一张卡的父方案在数组里的下标。
 *
 * <p>父必须已经 begin，并且排在这张卡前面：方案先开跑，专家后开跑。
 * 身份带父键时，父是 depth 正好浅一层、叶子键与父键一致的那张方案。
 * 多个叶子都能当父键前缀时取最长的那个，避免 {@code ws} 把 {@code ws-doc} 的专家抢走。
 * 父键是 {@code root} 的是入口直接派出的，不进任何方案。
 *
 * @param nodes 按 begin 先后排好的卡；调用方不要在这里重排
 * @param index 要认父的那一张
 * @return 父方案下标；没有父时为空
 */
export function parentIndexByBegin(nodes: readonly BeginParentNode[], index: number): number | undefined {
  const node = nodes[index]
  if (!node || typeof node.depth !== 'number') {
    return undefined
  }
  const structured = structuredRest(node.participantId, node.depth)
  if (structured == null) {
    return nearestShallowerScheme(nodes, index, node.depth)
  }
  // 入口直接派出的人：父键固定是 root，不能因为前面已经有方案就把他收进去
  if (structured.startsWith('root-') || structured === 'root') {
    return undefined
  }
  let parent: number | undefined
  let parentLeafLength = -1
  for (let earlier = index - 1; earlier >= 0; earlier -= 1) {
    const scheme = nodes[earlier]
    if ((scheme.kind || '').trim() !== 'scheme' || scheme.depth !== node.depth - 1) {
      continue
    }
    const leaf = schemeLeaf(nodes, earlier)
    if (!leaf) {
      continue
    }
    const matched = structured === leaf || structured.startsWith(`${leaf}-`)
    // 往前扫，先遇到的更长叶子就是更近的那张真父；短前缀（ws 对 ws-doc）让位
    if (matched && leaf.length > parentLeafLength) {
      parent = earlier
      parentLeafLength = leaf.length
    }
  }
  return parent
}

/**
 * 最近一张更浅的方案。只给没有标准 begin id 的旧数据用。
 *
 * @param nodes 按 begin 先后
 * @param index 当前卡
 * @param depth 当前 depth
 * @return 父方案下标；前面没有更浅方案时为空
 */
function nearestShallowerScheme(
  nodes: readonly BeginParentNode[],
  index: number,
  depth: number
): number | undefined {
  for (let earlier = index - 1; earlier >= 0; earlier -= 1) {
    const scheme = nodes[earlier]
    if ((scheme.kind || '').trim() === 'scheme' && typeof scheme.depth === 'number' && scheme.depth < depth) {
      return earlier
    }
  }
  return undefined
}

/**
 * 标准 begin id 在 depth 之后的那一段（父键-叶子）。不是这套形状时为空。
 *
 * @param participantId begin id
 * @param depth         begin depth，必须和 id 里的数字是同一层
 * @return 父键起的剩余部分；对不上时为空
 */
function structuredRest(participantId: string | undefined, depth: number): string | undefined {
  const id = (participantId || '').trim()
  const head = `p-${depth}-`
  if (!id.startsWith(head)) {
    return undefined
  }
  const rest = id.slice(head.length)
  // 至少要有「父键-叶子」两段，否则只是碰巧以 p- 开头的旧 id
  if (!rest.includes('-')) {
    return undefined
  }
  return rest
}

/**
 * 方案自己的叶子键。从根往下剥：根层父键固定是 root，更深的一层用父方案叶子做前缀。
 *
 * @param nodes 按 begin 先后
 * @param index 方案卡下标
 * @param seen  防止同一张卡反复往前找
 * @return 叶子键；这张卡不是带标准 id 的方案时为空
 */
function schemeLeaf(
  nodes: readonly BeginParentNode[],
  index: number,
  seen: Set<number> = new Set()
): string | undefined {
  if (seen.has(index)) {
    return undefined
  }
  seen.add(index)
  const node = nodes[index]
  if (!node || (node.kind || '').trim() !== 'scheme' || typeof node.depth !== 'number') {
    return undefined
  }
  const rest = structuredRest(node.participantId, node.depth)
  if (rest == null) {
    return undefined
  }
  if (rest.startsWith('root-')) {
    const leaf = rest.slice('root-'.length)
    return leaf || undefined
  }
  let leaf: string | undefined
  let parentLeafLength = -1
  for (let earlier = index - 1; earlier >= 0; earlier -= 1) {
    const scheme = nodes[earlier]
    if ((scheme.kind || '').trim() !== 'scheme' || scheme.depth !== node.depth - 1) {
      continue
    }
    const parentLeaf = schemeLeaf(nodes, earlier, seen)
    if (!parentLeaf) {
      continue
    }
    const prefix = `${parentLeaf}-`
    if (rest.startsWith(prefix) && parentLeaf.length > parentLeafLength) {
      parentLeafLength = parentLeaf.length
      leaf = rest.slice(prefix.length)
    }
  }
  return leaf || undefined
}
