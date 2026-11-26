<template>
  <g
    class="wf-edge"
    :class="{ 'is-dragging': isDragging }"
    @mouseenter="hovered = true"
    @mouseleave="hovered = false"
  >
    <!-- 可见走线：无拐点时与 VueFlow 默认贝塞尔边逐字符一致，旧图观感不变 -->
    <BaseEdge
      :id="id"
      :path="geometry.path"
      :label="label"
      :label-x="geometry.labelX"
      :label-y="geometry.labelY"
      :marker-start="markerStart"
      :marker-end="markerEnd"
      :label-style="labelStyle"
      :label-show-bg="labelShowBg"
      :label-bg-style="labelBgStyle"
      :label-bg-padding="labelBgPadding"
      :label-bg-border-radius="labelBgBorderRadius"
      :interaction-width="0"
    />

    <!-- 整条走线热区：落在线上任一点按住即可拉出拐点 / 就近拖动已有拐点 -->
    <path
      class="wf-edge__hit"
      :d="geometry.path"
      fill="none"
      stroke="#000"
      stroke-opacity="0"
      stroke-width="20"
      pointer-events="stroke"
      @pointerdown.stop="onLinePointerDown"
    />

    <!-- 悬停 / 选中才显示抓手，避免画布常态被圆点铺满 -->
    <template v-if="showHandles">
      <!-- 中段抓手：点住某段中点即可再折一个弯 -->
      <circle
        v-for="(mid, index) in midHandles"
        :key="`mid-${index}`"
        class="wf-edge__mid"
        :cx="mid.x"
        :cy="mid.y"
        r="3.5"
        @pointerdown.stop="onMidPointerDown(index, $event)"
      />
      <!-- 拐点抓手：拖近哪个就动哪个；双击删掉该点 -->
      <circle
        v-for="(point, index) in waypoints"
        :key="`wp-${index}`"
        class="wf-edge__waypoint"
        :cx="point.x"
        :cy="point.y"
        r="5"
        @pointerdown.stop="onWaypointPointerDown(index, $event)"
        @dblclick.stop="removeWaypoint(index)"
      />
    </template>
  </g>
</template>

<script setup lang="ts">
/**
 * 工作流画布自定义边：渲染走线并提供「拉弯走线」交互。所属域：workflow 的前端画布层。
 *
 * <p>职责：在 VueFlow 既有边体系内，把用户拖出的拐点渲染成平滑曲线；
 * 支持拖中段新增 / 拖动已有拐点 / 双击或拉直删除拐点。
 * <p>边界：只改走线，不碰 source / target / edgeType / label / condition；
 * 从节点锚点拉新线仍走 VueFlow 原生的 handle 连接，不在这里接管。
 * <p>存储：拐点写在边 data 的 `config.waypoints`，随 graph_json 一起保存，
 * 不新增后端字段；旧图没有该字段时走默认贝塞尔算法。
 * <p>手势分工：pointerdown 只拦在边的命中路径与拐点圆点上，
 * 画布平移 / 缩放与节点连线各走各的，互不抢占。
 */
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { BaseEdge, useVueFlow } from '@vue-flow/core'
import type { EdgeProps } from '@vue-flow/core'
import type { WorkflowFlowEdgeData } from '@/types/workflow'
import { WORKFLOW_EDGE_INTERACTION } from './workflowEdgeInteraction'
import {
  MAX_EDGE_WAYPOINTS,
  buildEdgeGeometry,
  commitEdgeDataWaypoints,
  findWaypointGrabTarget,
  pointDistance,
  readEdgeWaypoints,
  shouldRemoveStraightWaypoint
} from './workflowEdgeWaypoints'
import type { WorkflowEdgeWaypoint } from './workflowEdgeWaypoints'

const props = defineProps<EdgeProps<WorkflowFlowEdgeData>>()

/** 编辑页提供的选中回调：拖线时把右侧属性面板同步到这条边 */
const interaction = inject(WORKFLOW_EDGE_INTERACTION, null)

const { addSelectedEdges, findEdge, screenToFlowCoordinate } = useVueFlow()

/** 边 data；VueFlow 可能在极短时间内传空，读取时兜底成空对象 */
const edgeData = computed(() => (props.data || ({} as WorkflowFlowEdgeData)) as WorkflowFlowEdgeData)

/** 当前显示的拐点：拖拽期间用本地副本，避免每个 pointermove 都写 store */
const waypoints = ref<WorkflowEdgeWaypoint[]>([])

const hovered = ref(false)
const isDragging = ref(false)

/** 是否显示拖拽抓手：悬停、选中或正在拖拽 */
const showHandles = computed(() => hovered.value || Boolean(props.selected) || isDragging.value)

/** 走线几何：锚点来自 VueFlow 计算，拐点来自边配置 / 本地副本 */
const geometry = computed(() =>
  buildEdgeGeometry({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    targetX: props.targetX,
    targetY: props.targetY,
    sourcePosition: String(props.sourcePosition),
    targetPosition: String(props.targetPosition),
    waypoints: waypoints.value
  })
)

/** 每段中点：作为「再折一次」的可点击抓手 */
const midHandles = computed(() => geometry.value.segments.map((segment) => segment.mid))

// 外部（载入草稿 / 其它属性编辑）改动 data.config 时同步本地拐点；
// 拖拽过程中不覆盖，避免和用户手上的拖动打架
watch(
  () => edgeData.value.config,
  (config) => {
    if (!isDragging.value) {
      waypoints.value = readEdgeWaypoints(config)
    }
  },
  { immediate: true }
)

/** 一次拖拽的状态 */
interface DragState {
  index: number
  /** move = 拖动已有拐点；insert = 本次按下刚插入的拐点 */
  mode: 'move' | 'insert'
  startClientX: number
  startClientY: number
  /** 是否真的移动过；纯点击不留下新拐点 */
  moved: boolean
}

let drag: DragState | null = null

/** 锚点坐标：判断拉直删除与插入位置用 */
const sourcePoint = computed<WorkflowEdgeWaypoint>(() => ({ x: props.sourceX, y: props.sourceY }))
const targetPoint = computed<WorkflowEdgeWaypoint>(() => ({ x: props.targetX, y: props.targetY }))

/** 同步选中态：VueFlow 内部选中用于删除键 / 高亮，编辑页负责右侧面板 */
function selectThisEdge() {
  const edge = findEdge(props.id)
  if (edge) {
    addSelectedEdges([edge])
  }
  interaction?.selectEdge(props.id)
}

/** 屏幕坐标 → flow 坐标；VueFlow 已按画布 snap-grid 处理过 */
function toFlowPoint(event: PointerEvent): WorkflowEdgeWaypoint {
  const point = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  return { x: Math.round(point.x), y: Math.round(point.y) }
}

/** 挂 window 监听：拖动时指针可能移出边 / 画布，不能只靠元素自身事件 */
function bindWindowDrag() {
  window.addEventListener('pointermove', onWindowPointerMove)
  window.addEventListener('pointerup', onWindowPointerUp)
  window.addEventListener('pointercancel', onWindowPointerUp)
}

/** 解绑 window 监听，避免多次拖拽后累积 */
function unbindWindowDrag() {
  window.removeEventListener('pointermove', onWindowPointerMove)
  window.removeEventListener('pointerup', onWindowPointerUp)
  window.removeEventListener('pointercancel', onWindowPointerUp)
}

/**
 * 开始一次拖拽。
 *
 * @param index 拐点下标
 * @param mode 移动已有拐点或刚插入的新拐点
 * @param event 触发按下的指针事件
 */
function beginDrag(index: number, mode: 'move' | 'insert', event: PointerEvent) {
  if (event.button !== 0) {
    return
  }
  // 阻止默认行为与冒泡：画布平移 / 节点框选不能在这个手势里启动
  event.preventDefault()
  selectThisEdge()
  drag = {
    index,
    mode,
    startClientX: event.clientX,
    startClientY: event.clientY,
    moved: false
  }
  isDragging.value = true
  bindWindowDrag()
}

/** 按住整条走线：就近移动已有拐点，或按最近线段插入一个新拐点 */
function onLinePointerDown(event: PointerEvent) {
  if (event.button !== 0) {
    return
  }
  event.preventDefault()
  const pointer = toFlowPoint(event)
  const grab = findWaypointGrabTarget(
    pointer,
    waypoints.value,
    sourcePoint.value,
    targetPoint.value
  )
  if (grab.mode === 'move') {
    beginDrag(grab.index, 'move', event)
    return
  }
  // 拐点已到上限时只选中，不再无限加点；已有拐点仍可拖动 / 删除
  if (waypoints.value.length >= MAX_EDGE_WAYPOINTS) {
    selectThisEdge()
    return
  }
  const next = [...waypoints.value]
  next.splice(grab.index, 0, grab.point)
  waypoints.value = next
  beginDrag(grab.index, 'insert', event)
}

/** 按住某段中点：在这段中间插入拐点并立即进入拖动 */
function onMidPointerDown(segmentIndex: number, event: PointerEvent) {
  if (event.button !== 0) {
    return
  }
  event.preventDefault()
  const mid = midHandles.value[segmentIndex]
  if (!mid) {
    return
  }
  // 拐点已到上限时只选中，不再插入新点
  if (waypoints.value.length >= MAX_EDGE_WAYPOINTS) {
    selectThisEdge()
    return
  }
  const next = [...waypoints.value]
  next.splice(segmentIndex, 0, { x: Math.round(mid.x), y: Math.round(mid.y) })
  waypoints.value = next
  beginDrag(segmentIndex, 'insert', event)
}

/** 按住已有拐点：直接移动该点 */
function onWaypointPointerDown(index: number, event: PointerEvent) {
  beginDrag(index, 'move', event)
}

/** 拖动中：跟随指针更新本地拐点 */
function onWindowPointerMove(event: PointerEvent) {
  if (!drag) {
    return
  }
  const dx = event.clientX - drag.startClientX
  const dy = event.clientY - drag.startClientY
  if (!drag.moved && Math.hypot(dx, dy) >= 3) {
    drag.moved = true
  }
  if (!drag.moved) {
    return
  }
  const point = toFlowPoint(event)
  const next = [...waypoints.value]
  next[drag.index] = point
  waypoints.value = next
}

/**
 * 拖动结束：纯点击不留点；拖到几乎拉直则删点；其余提交到边配置。
 */
function onWindowPointerUp() {
  const state = drag
  drag = null
  unbindWindowDrag()
  isDragging.value = false
  if (!state) {
    return
  }

  if (!state.moved) {
    // 只在线上点了一下：把按下时插入的临时拐点撤回，不产生「点一下就多一个点」
    if (state.mode === 'insert') {
      const next = [...waypoints.value]
      next.splice(state.index, 1)
      waypoints.value = next
    }
    return
  }

  let next = [...waypoints.value]
  if (
    next.length &&
    shouldRemoveStraightWaypoint(
      state.index,
      next,
      sourcePoint.value,
      targetPoint.value
    )
  ) {
    // 拖到基本拉直：释放时删掉该拐点，保证「能加也能减」
    next.splice(state.index, 1)
  }
  commit(next)
}

/**
 * 把拐点写回边 data。
 *
 * <p>只替换 config.waypoints；source / target / edgeType / label / condition 原样保留。
 *
 * @param points 目标拐点
 */
function commit(points: WorkflowEdgeWaypoint[]) {
  const edge = findEdge(props.id)
  if (!edge) {
    return
  }
  const data = (edge.data || {}) as WorkflowFlowEdgeData
  const nextData = commitEdgeDataWaypoints(data, points)
  const before = readEdgeWaypoints(data.config)
  const after = readEdgeWaypoints(nextData.config)
  // 拐点没变化就不替换 data，避免无意义触发画布脏检查
  if (
    before.length === after.length &&
    before.every((point, index) => pointDistance(point, after[index]) < 0.01)
  ) {
    waypoints.value = after
    return
  }
  waypoints.value = after
  edge.data = nextData
}

/** 双击拐点：删除该点；删到空时走线自动回到默认贝塞尔 */
function removeWaypoint(index: number) {
  const next = waypoints.value.filter((_, current) => current !== index)
  commit(next)
}

onBeforeUnmount(() => {
  unbindWindowDrag()
  drag = null
})
</script>

<style scoped>
/* 整条走线的命中热区：落在线上就显示可拖手势 */
.wf-edge__hit {
  cursor: grab;
}

.wf-edge.is-dragging .wf-edge__hit {
  cursor: grabbing;
}

/* 拐点抓手：白色圆点 + 主题紫描边，悬停 / 选中才出现 */
.wf-edge__waypoint {
  fill: #ffffff;
  stroke: #635bff;
  stroke-width: 2;
  cursor: move;
  pointer-events: all;
  filter: drop-shadow(0 1px 2px rgba(16, 24, 40, 0.18));
}

.wf-edge__waypoint:hover {
  fill: #eef0ff;
  stroke-width: 2.5;
}

/* 中段抓手：默认半透明小点，提示「这里还能再折一次」 */
.wf-edge__mid {
  fill: #635bff;
  fill-opacity: 0.55;
  cursor: copy;
  pointer-events: all;
}

.wf-edge__mid:hover {
  fill-opacity: 1;
}

.wf-edge.is-dragging .wf-edge__mid {
  fill-opacity: 0.2;
}
</style>
