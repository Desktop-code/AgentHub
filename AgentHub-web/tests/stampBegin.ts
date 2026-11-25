/**
 * 测试夹具：把旧用例里的 targetType / isTopLevel 写成 begin 的 kind 和 depth。
 *
 * 产品投影不再读候选池。这些用例原来用回调描述「谁是方案、谁在方案下面」，
 * 这里只把那个事实落到泳道上，不重排、不把工作流提前或沉底。
 * 已经带了 kind 和 depth 的泳道保持原样。
 */
import { routeAgentNameOf } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'

type StampLane = {
  kind?: string
  title?: string
  participantKind?: string
  participantDepth?: number
}

/**
 * 给还没有 begin 形状的泳道补上 kind / depth。
 *
 * @param lanes        用例里的泳道
 * @param targetTypeOf 用例用来标明 MA / WORKFLOW / AGENT 的回调；没有则不猜 kind
 * @param isTopLevelOf 用例用来标明是不是入口层；没有则全部当 depth 1
 * @return 补过形状的新数组；原数组不改
 */
export function stampBeginLanes<T extends StampLane>(
  lanes: T[] | undefined,
  targetTypeOf?: (name: string) => string | undefined,
  isTopLevelOf?: (name: string) => boolean
): T[] {
  if (!Array.isArray(lanes)) {
    return []
  }
  if (!targetTypeOf && !isTopLevelOf) {
    return lanes
  }
  let lastSchemeDepth = 0
  return lanes.map((lane) => {
    if (!lane || lane.kind !== 'agent') {
      return lane
    }
    if (lane.participantKind && typeof lane.participantDepth === 'number') {
      return lane
    }
    const name = routeAgentNameOf(lane.title || '')
    const type = name && targetTypeOf ? targetTypeOf(name) : undefined
    const top = !name || !isTopLevelOf ? true : isTopLevelOf(name) !== false
    const kind = lane.participantKind
      || (type === 'MA' ? 'scheme' : type === 'WORKFLOW' ? 'workflow' : type ? 'agent' : undefined)
    let depth = lane.participantDepth
    if (typeof depth !== 'number') {
      if (top || !name) {
        depth = 1
      } else {
        depth = lastSchemeDepth > 0 ? lastSchemeDepth + 1 : 2
      }
    }
    if ((lane.participantKind || kind) === 'scheme' && typeof depth === 'number') {
      lastSchemeDepth = depth
    }
    return { ...lane, participantKind: kind, participantDepth: depth }
  })
}
