/**
 * 工作流画布走线拐点：拖拽几何、落库兼容与旧图回退。所属域：workflow（前端）。
 *
 * 用例只跑纯函数，不挂 VueFlow / DOM：走线渲染由 WorkflowEdge.vue 负责，
 * 但「拐点存在哪、旧图是否原样、拉直是否删点、保存再加载是否还在」这些契约必须在这里钉死。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  MAX_EDGE_WAYPOINTS,
  buildEdgeGeometry,
  commitEdgeDataWaypoints,
  findWaypointGrabTarget,
  normalizeWaypoints,
  readEdgeWaypoints,
  shouldRemoveStraightWaypoint,
  withEdgeWaypointConfig
} from '../src/views/workflow/editor/workflowEdgeWaypoints.ts'
import type { WorkflowEdgeWaypoint } from '../src/views/workflow/editor/workflowEdgeWaypoints.ts'

/** 默认一条从 (0,0) 底部锚点连到 (100,100) 顶部锚点的边 */
const EDGE = {
  sourceX: 0,
  sourceY: 0,
  targetX: 100,
  targetY: 100,
  sourcePosition: 'bottom',
  targetPosition: 'top'
}

test('旧图没有 waypoints：读为空且不改写 config', () => {
  const config = { condition: { '==': [1, 1] } }
  assert.deepEqual(readEdgeWaypoints(config), [])
  assert.deepEqual(readEdgeWaypoints(undefined), [])

  // 空拐点写回时只生成副本，不凭空加 waypoints 键
  const next = withEdgeWaypointConfig(config, [])
  assert.deepEqual(next, { condition: { '==': [1, 1] } })
  assert.notEqual(next, config)
  assert.equal('waypoints' in config, false)
})

test('无拐点走线与 VueFlow 默认贝塞尔边完全一致', () => {
  const geometry = buildEdgeGeometry({ ...EDGE, waypoints: [] })
  // 与 getBezierPath / getBezierEdgeCenter 的结果逐字符一致
  assert.equal(geometry.path, 'M0,0 C0,50 100,50 100,100')
  assert.equal(geometry.labelX, 50)
  assert.equal(geometry.labelY, 50)
  assert.equal(geometry.segments.length, 1)
})

test('有拐点时曲线穿过拐点，起点终点不变', () => {
  const waypoint: WorkflowEdgeWaypoint = { x: 50, y: -60 }
  const geometry = buildEdgeGeometry({ ...EDGE, waypoints: [waypoint] })
  assert.equal(geometry.segments.length, 2)
  assert.deepEqual(geometry.segments[0].start, { x: 0, y: 0 })
  assert.deepEqual(geometry.segments[0].end, waypoint)
  assert.deepEqual(geometry.segments[1].end, { x: 100, y: 100 })
  assert.match(geometry.path, /^M0,0 C/)
  assert.ok(geometry.path.includes('50,-60'))
})

test('拐点只写 config.waypoints，边类型 / 标签 / 条件原样保留', () => {
  const data = {
    edgeType: 'LOOP_BACK',
    label: '打回',
    condition: { '!': [true] },
    config: { condition: { '!': [true] } }
  }
  const next = commitEdgeDataWaypoints(data, [{ x: 100.4, y: 200.6 }])
  assert.equal(next.edgeType, 'LOOP_BACK')
  assert.equal(next.label, '打回')
  assert.deepEqual(next.condition, { '!': [true] })
  assert.deepEqual(next.config.condition, { '!': [true] })
  assert.deepEqual(next.config.waypoints, [{ x: 100, y: 201 }])
  // 除新增 waypoints 外没有多出别的键，保存校验读到的业务字段不变
  assert.deepEqual(Object.keys(next.config).sort(), ['condition', 'waypoints'])
})

test('保存再加载：拐点经 JSON 往返仍在', () => {
  const config = withEdgeWaypointConfig({ condition: { '>': [1, 0] } }, [
    { x: 10.6, y: 20.2 },
    { x: -30.2, y: 40.8 }
  ])
  const persisted = JSON.parse(JSON.stringify(config)) as Record<string, unknown>
  assert.deepEqual(readEdgeWaypoints(persisted), [
    { x: 11, y: 20 },
    { x: -30, y: 41 }
  ])
})

test('去掉拐点后走线恢复默认贝塞尔', () => {
  const base = buildEdgeGeometry({ ...EDGE, waypoints: [] })
  const bentConfig = withEdgeWaypointConfig({}, [{ x: 50, y: -60 }])
  const bent = buildEdgeGeometry({ ...EDGE, waypoints: readEdgeWaypoints(bentConfig) })
  assert.notEqual(bent.path, base.path)

  const clearedConfig = withEdgeWaypointConfig(bentConfig, [])
  assert.equal('waypoints' in clearedConfig, false)
  const restored = buildEdgeGeometry({ ...EDGE, waypoints: readEdgeWaypoints(clearedConfig) })
  assert.equal(restored.path, base.path)
  assert.equal(restored.labelX, base.labelX)
  assert.equal(restored.labelY, base.labelY)
})

test('拐点规范化：过滤脏值、四舍五入并限制上限', () => {
  assert.deepEqual(
    normalizeWaypoints([{ x: 1.4, y: 2.6 }, { x: 'a', y: 2 }, null, { x: Number.NaN, y: 3 }]),
    [{ x: 1, y: 3 }]
  )
  const many = Array.from({ length: MAX_EDGE_WAYPOINTS + 5 }, (_, index) => ({ x: index, y: 0 }))
  assert.equal(normalizeWaypoints(many).length, MAX_EDGE_WAYPOINTS)
})

test('拖近已有拐点移动该点，否则按最近线段插入', () => {
  const source = { x: 0, y: 0 }
  const target = { x: 100, y: 100 }
  const move = findWaypointGrabTarget({ x: 52, y: 48 }, [{ x: 50, y: 50 }], source, target)
  assert.equal(move.mode, 'move')
  assert.equal(move.index, 0)

  const insert = findWaypointGrabTarget({ x: 25, y: 5 }, [], source, target)
  assert.equal(insert.mode, 'insert')
  assert.equal(insert.index, 0)
  assert.deepEqual(insert.point, { x: 25, y: 5 })

  // 已有拐点时，靠近第二段则插在下标 1
  const second = findWaypointGrabTarget(
    { x: 75, y: 20 },
    [{ x: 50, y: -60 }],
    source,
    target
  )
  assert.equal(second.mode, 'insert')
  assert.equal(second.index, 1)
})

test('拖到几乎拉直时释放即删除拐点', () => {
  const source = { x: 0, y: 0 }
  const target = { x: 100, y: 100 }
  assert.equal(shouldRemoveStraightWaypoint(0, [{ x: 50, y: 50 }], source, target), true)
  assert.equal(shouldRemoveStraightWaypoint(0, [{ x: 50, y: 90 }], source, target), false)

  // 多拐点只看相邻两点，不误删绕行用的中间点
  const routed = [
    { x: 0, y: 100 },
    { x: 50, y: 100 },
    { x: 100, y: 100 }
  ]
  assert.equal(shouldRemoveStraightWaypoint(1, routed, source, target), true)
  const detour = [
    { x: 0, y: 100 },
    { x: 50, y: -40 },
    { x: 100, y: 100 }
  ]
  assert.equal(shouldRemoveStraightWaypoint(1, detour, source, target), false)
})
