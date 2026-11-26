/**
 * 工作流画布走线拐点：几何计算与落库兼容。所属域：workflow 的前端画布层。
 *
 * <p>职责：把「用户手工拉出的拐点」转换成 SVG 路径，并提供拖拽命中 / 拉直删除等纯函数。
 * <p>边界：不依赖 Vue、不碰 DOM、不发请求，方便 node 测试直接引用；
 * 拐点存在边 data 的 `config.waypoints`（flow 坐标，与节点 ui 同一坐标系），
 * 随既有 graph_json 一起落库，不新增后端字段、不改运行时边语义。
 * <p>兼容：没有 `waypoints` 的旧图走与 VueFlow `getBezierPath` 完全相同的算法，
 * 保证加载后的走线、标签位置和改造前一致。
 */

/** 画布锚点方向：与 VueFlow Position 的字符串值一致，避免测试运行时引入 VueFlow */
export type EdgeHandlePosition = 'left' | 'right' | 'top' | 'bottom'

/** 走线拐点（flow 坐标，与节点 ui 同一坐标系） */
export interface WorkflowEdgeWaypoint {
  x: number
  y: number
}

/** 一段三次贝塞尔：首尾点 + 两个控制点 + t=0.5 中点 */
export interface EdgeSegmentGeometry {
  start: WorkflowEdgeWaypoint
  control1: WorkflowEdgeWaypoint
  control2: WorkflowEdgeWaypoint
  end: WorkflowEdgeWaypoint
  /** 段中点：用于「中段抓手」的落点，也用于判断拖到哪一段 */
  mid: WorkflowEdgeWaypoint
}

/** 一条边的完整走线几何 */
export interface EdgeGeometry {
  /** SVG path 字符串 */
  path: string
  /** 标签中心 X（flow 坐标） */
  labelX: number
  /** 标签中心 Y（flow 坐标） */
  labelY: number
  /** 组成走线的贝塞尔分段；无拐点时只有 1 段 */
  segments: EdgeSegmentGeometry[]
}

/** 拖拽命中已有拐点的半径（flow 单位）：落在该半径内视为移动该点 */
export const WAYPOINT_GRAB_RADIUS = 18
/** 拖到与相邻两点的连线几乎重合时，释放即删除该拐点 */
export const STRAIGHTEN_TOLERANCE = 8
/** 单条边最多保留的拐点数量：够绕开交叉即可，避免误操作无限加点 */
export const MAX_EDGE_WAYPOINTS = 12
/** 无拐点时与 VueFlow 默认贝塞尔边保持一致的曲率 */
const BEZIER_CURVATURE = 0.25

/** 生成路径时最多保留的小数位，避免坐标字符串无限长 */
function roundPath(value: number): number {
  return Math.round(value * 100) / 100
}

/** 两点距离 */
export function pointDistance(a: WorkflowEdgeWaypoint, b: WorkflowEdgeWaypoint): number {
  return Math.hypot(b.x - a.x, b.y - a.y)
}

/**
 * 把任意输入规范成合法拐点列表。
 *
 * <p>只保留 x / y 都是有限数字的项；坐标四舍五入到整数（画布本身按 10 网格拖动），
 * 超过 {@link MAX_EDGE_WAYPOINTS} 的尾部直接丢弃，避免脏 JSON 拖垮渲染。
 *
 * @param input 任意输入，通常是边 config.waypoints 或拖拽过程中的本地数组
 * @return 规范化后的新数组，不修改入参
 */
export function normalizeWaypoints(input: unknown): WorkflowEdgeWaypoint[] {
  if (!Array.isArray(input)) {
    return []
  }
  const result: WorkflowEdgeWaypoint[] = []
  for (const item of input) {
    if (result.length >= MAX_EDGE_WAYPOINTS) {
      break
    }
    if (!item || typeof item !== 'object') {
      continue
    }
    const raw = item as Record<string, unknown>
    const x = Number(raw.x)
    const y = Number(raw.y)
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      continue
    }
    result.push({ x: Math.round(x), y: Math.round(y) })
  }
  return result
}

/**
 * 从边 config 读取拐点；旧图没有该字段时返回空数组。
 *
 * @param config 边的自由配置对象（落库 graph_json 的 edges[].config）
 * @return 规范化后的拐点列表
 */
export function readEdgeWaypoints(
  config: Record<string, unknown> | null | undefined
): WorkflowEdgeWaypoint[] {
  if (!config || typeof config !== 'object') {
    return []
  }
  return normalizeWaypoints(config.waypoints)
}

/**
 * 把拐点写进边 config 的副本。
 *
 * <p>空列表会删除 waypoints 键：旧图保存后不会被塞进一个空数组。
 * 其它业务字段（如 condition）原样保留。
 *
 * @param config 原边配置，可为空
 * @param waypoints 目标拐点列表
 * @return 新的 config 对象，不修改入参
 */
export function withEdgeWaypointConfig(
  config: Record<string, unknown> | null | undefined,
  waypoints: unknown
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(config ?? {}) }
  const normalized = normalizeWaypoints(waypoints)
  if (normalized.length) {
    next.waypoints = normalized
  } else {
    delete next.waypoints
  }
  return next
}

/**
 * 提交拖动结果到边 data。
 *
 * <p>只替换 config，data 上其余字段（edgeType / label / condition 等）原样保留；
 * 边的 source / target 根本不在 data 里，拖线不会碰它们。
 *
 * @param data 边 data
 * @param waypoints 目标拐点列表
 * @return 新的 data 对象，可直接赋回 edge.data
 */
export function commitEdgeDataWaypoints<T extends { config?: Record<string, unknown> }>(
  data: T,
  waypoints: unknown
): T {
  return {
    ...data,
    config: withEdgeWaypointConfig(data.config, waypoints)
  }
}

/** 点到线段的距离；线段退化成点时按点距处理 */
export function distanceToSegment(
  point: WorkflowEdgeWaypoint,
  start: WorkflowEdgeWaypoint,
  end: WorkflowEdgeWaypoint
): number {
  const dx = end.x - start.x
  const dy = end.y - start.y
  const lengthSquared = dx * dx + dy * dy
  if (lengthSquared === 0) {
    return pointDistance(point, start)
  }
  // 投影参数夹到 [0,1]，保证最近点落在线段上而不是其延长线
  const t = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
  return pointDistance(point, { x: start.x + dx * t, y: start.y + dy * t })
}

/**
 * 把点投影到线段上（夹到 [0,1]）。
 *
 * @param point 原始点
 * @param start 线段起点
 * @param end 线段终点
 * @return 线段上的最近点
 */
export function projectOnSegment(
  point: WorkflowEdgeWaypoint,
  start: WorkflowEdgeWaypoint,
  end: WorkflowEdgeWaypoint
): WorkflowEdgeWaypoint {
  const dx = end.x - start.x
  const dy = end.y - start.y
  const lengthSquared = dx * dx + dy * dy
  if (lengthSquared === 0) {
    return { x: start.x, y: start.y }
  }
  const t = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
  return { x: start.x + dx * t, y: start.y + dy * t }
}

/** 三次贝塞尔 t=0.5 的中点 */
function cubicMidpoint(
  start: WorkflowEdgeWaypoint,
  control1: WorkflowEdgeWaypoint,
  control2: WorkflowEdgeWaypoint,
  end: WorkflowEdgeWaypoint
): WorkflowEdgeWaypoint {
  return {
    x: (start.x + 3 * control1.x + 3 * control2.x + end.x) / 8,
    y: (start.y + 3 * control1.y + 3 * control2.y + end.y) / 8
  }
}

/**
 * VueFlow 曲率控制点算法（与 @vue-flow/core 的 getControlWithCurvature 等价；
 * VueFlow 以 MIT 许可随依赖使用，版权与许可声明保留在 node_modules 原包中）。
 */
function controlOffset(delta: number, curvature: number): number {
  return delta >= 0 ? 0.5 * delta : curvature * 25 * Math.sqrt(-delta)
}

/** 按锚点方向算控制点；与 VueFlow 默认贝塞尔边一致 */
function controlWithCurvature(
  position: EdgeHandlePosition,
  x1: number,
  y1: number,
  x2: number,
  y2: number
): WorkflowEdgeWaypoint {
  switch (position) {
    case 'left':
      return { x: x1 - controlOffset(x1 - x2, BEZIER_CURVATURE), y: y1 }
    case 'right':
      return { x: x1 + controlOffset(x2 - x1, BEZIER_CURVATURE), y: y1 }
    case 'top':
      return { x: x1, y: y1 - controlOffset(y1 - y2, BEZIER_CURVATURE) }
    default:
      return { x: x1, y: y1 + controlOffset(y2 - y1, BEZIER_CURVATURE) }
  }
}

/** 把锚点方向字符串收敛到已知枚举，未知按 bottom / top 兜底 */
function normalizePosition(raw: string | undefined, fallback: EdgeHandlePosition): EdgeHandlePosition {
  if (raw === 'left' || raw === 'right' || raw === 'top' || raw === 'bottom') {
    return raw
  }
  return fallback
}

/**
 * 生成走线几何。
 *
 * <p>没有拐点时严格复用 VueFlow 默认贝塞尔公式：路径字符串与改造前逐字符一致，
 * 老图打开后的观感、标签落点都不变。
 * <p>有拐点时按 Catmull-Rom -> 三次贝塞尔穿过「起点 → 各拐点 → 终点」，
 * 只在用户手工拉出的点上折弯，不做自动避障 / 自动正交。
 *
 * @param input 锚点坐标、方向与拐点
 * @return path + 标签中心 + 分段信息
 */
export function buildEdgeGeometry(input: {
  sourceX: number
  sourceY: number
  targetX: number
  targetY: number
  sourcePosition?: string
  targetPosition?: string
  waypoints?: unknown
}): EdgeGeometry {
  const source: WorkflowEdgeWaypoint = { x: input.sourceX, y: input.sourceY }
  const target: WorkflowEdgeWaypoint = { x: input.targetX, y: input.targetY }
  const sourcePosition = normalizePosition(input.sourcePosition, 'bottom')
  const targetPosition = normalizePosition(input.targetPosition, 'top')
  const waypoints = normalizeWaypoints(input.waypoints)

  // 旧图 / 未拉弯：完全按 VueFlow 默认贝塞尔边出路径，保证与改造前一致
  if (!waypoints.length) {
    const control1 = controlWithCurvature(
      sourcePosition,
      source.x,
      source.y,
      target.x,
      target.y
    )
    const control2 = controlWithCurvature(
      targetPosition,
      target.x,
      target.y,
      source.x,
      source.y
    )
    const path = `M${source.x},${source.y} C${control1.x},${control1.y} ${control2.x},${control2.y} ${target.x},${target.y}`
    // 标签中心沿用 VueFlow getBezierEdgeCenter 的加权公式
    const labelX = source.x * 0.125 + control1.x * 0.375 + control2.x * 0.375 + target.x * 0.125
    const labelY = source.y * 0.125 + control1.y * 0.375 + control2.y * 0.375 + target.y * 0.125
    return {
      path,
      labelX,
      labelY,
      segments: [
        {
          start: source,
          control1,
          control2,
          end: target,
          mid: cubicMidpoint(source, control1, control2, target)
        }
      ]
    }
  }

  // 有拐点：Catmull-Rom 转贝塞尔，曲线穿过每个拐点
  const points = [source, ...waypoints, target]
  const segments: EdgeSegmentGeometry[] = []
  const commands: string[] = [`M${source.x},${source.y}`]
  for (let index = 0; index < points.length - 1; index += 1) {
    const start = points[index]
    const end = points[index + 1]
    // 首尾用自身占位，避免端点处出现额外折角
    const previous = index > 0 ? points[index - 1] : start
    const next = index + 2 < points.length ? points[index + 2] : end
    const control1: WorkflowEdgeWaypoint = {
      x: roundPath(start.x + (end.x - previous.x) / 6),
      y: roundPath(start.y + (end.y - previous.y) / 6)
    }
    const control2: WorkflowEdgeWaypoint = {
      x: roundPath(end.x - (next.x - start.x) / 6),
      y: roundPath(end.y - (next.y - start.y) / 6)
    }
    commands.push(`C${control1.x},${control1.y} ${control2.x},${control2.y} ${end.x},${end.y}`)
    segments.push({
      start,
      control1,
      control2,
      end,
      mid: cubicMidpoint(start, control1, control2, end)
    })
  }
  // 标签放在中间那段的中点，避免落在最外侧拐点上
  const labelSegment = segments[Math.floor((segments.length - 1) / 2)] || segments[0]
  return {
    path: commands.join(' '),
    labelX: roundPath(labelSegment.mid.x),
    labelY: roundPath(labelSegment.mid.y),
    segments
  }
}

/** 拖拽命中的结果：移动已有拐点，或在某段中间插入新拐点 */
export interface WaypointGrabTarget {
  mode: 'move' | 'insert'
  /** move = 已有拐点下标；insert = 插入后的拐点下标 */
  index: number
  /** 实际落点：move 是拐点原坐标，insert 是投影到骨架线段上的点 */
  point: WorkflowEdgeWaypoint
}

/**
 * 判断按下位置应该移动哪个拐点，或插到哪一段。
 *
 * <p>先看附近是否已有拐点：落在 {@link WAYPOINT_GRAB_RADIUS} 内就移动它；
 * 否则把坐标投影到最近的「起点 → 拐点 → 终点」骨架线段，在该段之后插入新拐点。
 *
 * @param pointer 按下的 flow 坐标
 * @param waypoints 当前拐点
 * @param source 起点锚点
 * @param target 终点锚点
 * @return 命中结果
 */
export function findWaypointGrabTarget(
  pointer: WorkflowEdgeWaypoint,
  waypoints: WorkflowEdgeWaypoint[],
  source: WorkflowEdgeWaypoint,
  target: WorkflowEdgeWaypoint
): WaypointGrabTarget {
  let nearestIndex = -1
  let nearestDistance = Number.POSITIVE_INFINITY
  waypoints.forEach((point, index) => {
    const distance = pointDistance(pointer, point)
    if (distance < nearestDistance) {
      nearestDistance = distance
      nearestIndex = index
    }
  })
  if (nearestIndex >= 0 && nearestDistance <= WAYPOINT_GRAB_RADIUS) {
    return { mode: 'move', index: nearestIndex, point: { ...waypoints[nearestIndex] } }
  }

  const skeleton = [source, ...waypoints, target]
  let bestSegment = 0
  let bestDistance = Number.POSITIVE_INFINITY
  for (let index = 0; index < skeleton.length - 1; index += 1) {
    const projected = projectOnSegment(pointer, skeleton[index], skeleton[index + 1])
    const distance = pointDistance(pointer, projected)
    if (distance < bestDistance) {
      bestDistance = distance
      bestSegment = index
    }
  }
  // 骨架第 i 段（source -> wp0 ...）之后插入，下标正好是 i；
  // 落点直接用按下坐标，拐点从用户抓到的位置长出来，不会先跳到骨架上
  return { mode: 'insert', index: bestSegment, point: { ...pointer } }
}

/**
 * 释放时是否删除刚拖过的拐点：与相邻两点连线几乎重合即视为「拉直」。
 *
 * <p>多拐点时只看前后两点，不拿整条线的首尾判断，避免误删绕行用的中间点。
 *
 * @param index 被拖动的拐点下标
 * @param waypoints 当前拐点
 * @param source 起点锚点
 * @param target 终点锚点
 * @param tolerance 允许的垂直偏移，默认 {@link STRAIGHTEN_TOLERANCE}
 * @return true 表示该点可删除
 */
export function shouldRemoveStraightWaypoint(
  index: number,
  waypoints: WorkflowEdgeWaypoint[],
  source: WorkflowEdgeWaypoint,
  target: WorkflowEdgeWaypoint,
  tolerance: number = STRAIGHTEN_TOLERANCE
): boolean {
  const point = waypoints[index]
  if (!point) {
    return false
  }
  const previous = index > 0 ? waypoints[index - 1] : source
  const next = index < waypoints.length - 1 ? waypoints[index + 1] : target
  return distanceToSegment(point, previous, next) <= tolerance
}
