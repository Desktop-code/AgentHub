<template>
  <!--
    执行链路水平拓扑。所属域：obs。
    布局：同父步骤复用 buildTraceFlow 成「串行块链 + 并行块扇出」。
    ROUTE 的内部 LLM（与 AGENT 等对外子步并存时）同列内嵌，不横在分叉前。
    边：只画 parent_id → id，禁止并对列笛卡尔全连接。
  -->
  <div v-if="layout.nodes.length" class="topo">
    <svg
      class="topo__svg"
      :width="layout.width"
      :height="layout.height"
      :viewBox="`0 0 ${layout.width} ${layout.height}`"
      :style="{ minWidth: `${layout.width}px` }"
      role="img"
      aria-label="执行拓扑"
    >
      <g fill="none" stroke="#d0d5dd" stroke-width="1.5">
        <path v-for="(d, i) in layout.edges" :key="`e-${i}`" :d="d" />
      </g>
      <g v-for="node in layout.nodes" :key="node.id" :transform="`translate(${node.x}, ${node.y})`">
        <rect
          :width="NODE_W"
          :height="NODE_H"
          rx="8"
          fill="#fff"
          :stroke="node.stroke"
          stroke-width="1.5"
        />
        <rect :width="4" :height="NODE_H" rx="2" :fill="node.stroke" />
        <text x="14" y="22" fill="#667085" font-size="11">#{{ node.stepLabel }} {{ node.typeLabel }}</text>
        <text
          v-if="node.latency"
          :x="NODE_W - 10"
          y="22"
          text-anchor="end"
          fill="#667085"
          font-size="11"
        >{{ node.latency }}</text>
        <text x="14" y="40" fill="#101828" font-size="13" font-weight="600">{{ node.name }}</text>
        <title>{{ node.tip }}</title>
      </g>
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ObsSpan } from '@/types/obs'
import { buildTraceFlow, type TraceFlowItem } from '@/utils/traceParallel'

const props = defineProps<{
  spans: ObsSpan[]
}>()

const NODE_W = 208
const NODE_H = 52
const GAP_X = 64
const GAP_Y = 16
/** 左右多留边，避免首尾节点贴边被裁 */
const PAD = 24

/** 拓扑节点：自身卡片 + 同列内嵌 + 右侧子块链 */
interface LayoutNode {
  span: ObsSpan
  childBlocks: LayoutBlock[]
  /** 同列内嵌（ROUTE 判路由 LLM 等），不参与右侧横向链 */
  internalNodes: LayoutNode[]
  /** 本节点子树占位高度 */
  subtree: number
  x: number
  y: number
}

/** 同父下的一步：单步块或并行块 */
type LayoutBlock =
  | { kind: 'single'; node: LayoutNode }
  | { kind: 'parallel'; nodes: LayoutNode[] }

interface DrawnNode {
  id: number
  x: number
  y: number
  stepLabel: string
  typeLabel: string
  name: string
  /** 第一行右上角耗时；没有则不画 */
  latency: string
  stroke: string
  tip: string
}

/** 放置一块后的最深列，供后续块向右接续 */
interface PlaceResult {
  maxDepth: number
}

const layout = computed(() => {
  const spans = [...props.spans].sort((a, b) => a.seq - b.seq || a.id - b.id)
  const byId = new Map(spans.map((item) => [item.id, item]))
  const childMap = new Map<number, ObsSpan[]>()
  const roots: ObsSpan[] = []
  for (const span of spans) {
    if (span.parentId != null && byId.has(span.parentId)) {
      const list = childMap.get(span.parentId) ?? []
      list.push(span)
      childMap.set(span.parentId, list)
    } else {
      roots.push(span)
    }
  }
  for (const list of childMap.values()) {
    // 同父竖序先按 agentId 对齐分支，再按 seq，避免两支交叉成 X
    list.sort(branchOrder)
  }
  roots.sort(branchOrder)

  // 根层也走同一套并组：串行链式、并行扇出（等价于虚拟根下的子块链）
  const rootBlocks = itemsToBlocks(buildTraceFlow(roots), childMap)
  for (const block of rootBlocks) {
    measureBlock(block)
  }

  const drawn: DrawnNode[] = []
  const positions = new Map<number, LayoutNode>()
  const chainH = rootBlocks.length
    ? Math.max(...rootBlocks.map(blockHeight))
    : NODE_H
  placeBlockChain(rootBlocks, 0, PAD, drawn, positions)

  // 边只来自父子树，不按布局块出口×入口全连接
  const edges = treeEdges(spans, positions)

  let maxRight = PAD + NODE_W
  let maxBottom = PAD + NODE_H
  for (const node of drawn) {
    maxRight = Math.max(maxRight, node.x + NODE_W)
    maxBottom = Math.max(maxBottom, node.y + NODE_H)
  }

  return {
    nodes: drawn,
    edges,
    width: maxRight + PAD,
    height: Math.max(PAD + chainH + PAD, maxBottom + PAD, NODE_H + PAD * 2)
  }
})

/**
 * 同层排序：同一 agent 的支路保持同一竖序槽，再按 seq → step_no → id。
 */
function branchOrder(a: ObsSpan, b: ObsSpan): number {
  const agentA = a.agentId ?? Number.MAX_SAFE_INTEGER
  const agentB = b.agentId ?? Number.MAX_SAFE_INTEGER
  if (agentA !== agentB) {
    return agentA - agentB
  }
  if (a.seq !== b.seq) {
    return a.seq - b.seq
  }
  const stepA = a.stepNo?.trim() ?? ''
  const stepB = b.stepNo?.trim() ?? ''
  if (stepA !== stepB) {
    return stepA < stepB ? -1 : 1
  }
  return a.id - b.id
}

/**
 * 将并组结果转为可递归布局的块；并行成员再按分支竖序排。
 */
function itemsToBlocks(items: TraceFlowItem[], childMap: Map<number, ObsSpan[]>): LayoutBlock[] {
  return items.map((item) => {
    if (item.kind === 'parallel') {
      const ordered = [...item.spans].sort(branchOrder)
      return {
        kind: 'parallel' as const,
        nodes: ordered.map((span) => buildNode(span, childMap))
      }
    }
    return { kind: 'single' as const, node: buildNode(item.span, childMap) }
  })
}

/**
 * 构建单节点及其子块。
 * ROUTE 若同时有 LLM 与对外子步：LLM 收成同列内嵌，其余走右侧链。
 */
function buildNode(span: ObsSpan, childMap: Map<number, ObsSpan[]>): LayoutNode {
  const kids = childMap.get(span.id) ?? []
  const { outbound, internal } = partitionRouteChildren(span, kids)
  const childBlocks = itemsToBlocks(buildTraceFlow(outbound), childMap)
  const internalNodes = internal.map((item) => buildNode(item, childMap))
  return { span, childBlocks, internalNodes, subtree: NODE_H, x: 0, y: 0 }
}

/**
 * ROUTE / PLAN 内部调用收纳：有 AGENT/TOOL 等对外子步时，同级 LLM 视为判定/规划内嵌，不横在分叉前。
 * 仅 LLM 子步时仍走右侧链，避免单节点被误收。
 */
function partitionRouteChildren(
  parent: ObsSpan,
  kids: ObsSpan[]
): { outbound: ObsSpan[]; internal: ObsSpan[] } {
  if ((parent.spanType !== 'ROUTE' && parent.spanType !== 'PLAN') || !kids.length) {
    return { outbound: kids, internal: [] }
  }
  const llms = kids.filter((k) => k.spanType === 'LLM')
  const rest = kids.filter((k) => k.spanType !== 'LLM')
  if (llms.length && rest.length) {
    return { outbound: rest, internal: llms }
  }
  return { outbound: kids, internal: [] }
}

/**
 * 将有序块沿主轴链式放置：并行块内部竖排扇出；不在此处画边。
 */
function placeBlockChain(
  blocks: LayoutBlock[],
  startDepth: number,
  top: number,
  drawn: DrawnNode[],
  positions: Map<number, LayoutNode>
): PlaceResult {
  if (!blocks.length) {
    return { maxDepth: startDepth }
  }
  const chainH = Math.max(...blocks.map(blockHeight))
  let nextDepth = startDepth
  let maxDepth = startDepth

  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i]
    const bh = blockHeight(block)
    // 串行块在整链高度内垂直居中；并行块内各支自有竖序
    const blockTop = top + (chainH - bh) / 2
    const result = placeBlock(block, nextDepth, blockTop, drawn, positions)
    nextDepth = result.maxDepth + 1
    maxDepth = result.maxDepth
  }
  return { maxDepth }
}

function measureBlock(block: LayoutBlock): number {
  if (block.kind === 'single') {
    return measure(block.node)
  }
  const sum = block.nodes.reduce((acc, node) => acc + measure(node), 0)
  return sum + Math.max(0, block.nodes.length - 1) * GAP_Y
}

function measure(node: LayoutNode): number {
  // 同列：父卡 + 内嵌节点竖排
  let stackH = NODE_H
  for (const inn of node.internalNodes) {
    stackH += GAP_Y + measure(inn)
  }
  if (!node.childBlocks.length) {
    node.subtree = stackH
    return stackH
  }
  // 右侧子块链式，高度取各块最大，再与同列堆叠取较大
  let chainH = 0
  for (const block of node.childBlocks) {
    chainH = Math.max(chainH, measureBlock(block))
  }
  node.subtree = Math.max(stackH, chainH)
  return node.subtree
}

function blockHeight(block: LayoutBlock): number {
  if (block.kind === 'single') {
    return block.node.subtree
  }
  const sum = block.nodes.reduce((acc, n) => acc + n.subtree, 0)
  return sum + Math.max(0, block.nodes.length - 1) * GAP_Y
}

/**
 * 放置一块：并行成员同列竖排；单步占一列，其嵌套子块再向右链式展开。
 */
function placeBlock(
  block: LayoutBlock,
  depth: number,
  top: number,
  drawn: DrawnNode[],
  positions: Map<number, LayoutNode>
): PlaceResult {
  if (block.kind === 'single') {
    return place(block.node, depth, top, drawn, positions)
  }
  let cursor = top
  let maxDepth = depth
  for (const node of block.nodes) {
    const inner = place(node, depth, cursor, drawn, positions)
    maxDepth = Math.max(maxDepth, inner.maxDepth)
    cursor += node.subtree + GAP_Y
  }
  return { maxDepth }
}

/**
 * 放置节点卡片：内嵌同列竖排，对外子块沿主轴（左→右）铺开。
 */
function place(
  node: LayoutNode,
  depth: number,
  top: number,
  drawn: DrawnNode[],
  positions: Map<number, LayoutNode>
): PlaceResult {
  node.x = PAD + depth * (NODE_W + GAP_X)

  // 同列堆叠高度（父 + 内嵌），在子树高度内垂直居中
  let stackH = NODE_H
  for (const inn of node.internalNodes) {
    stackH += GAP_Y + inn.subtree
  }
  let cursor = top + (node.subtree - stackH) / 2
  node.y = cursor
  positions.set(node.span.id, node)
  drawn.push(toDrawn(node))
  cursor += NODE_H + GAP_Y

  let maxDepth = depth
  for (const inn of node.internalNodes) {
    const inner = place(inn, depth, cursor, drawn, positions)
    maxDepth = Math.max(maxDepth, inner.maxDepth)
    cursor += inn.subtree + GAP_Y
  }

  if (!node.childBlocks.length) {
    return { maxDepth }
  }

  // 对外子块铺在右侧；边稍后按 parent_span_id 补
  const childChain = placeBlockChain(node.childBlocks, depth + 1, top, drawn, positions)
  return { maxDepth: Math.max(maxDepth, childChain.maxDepth) }
}

/**
 * 父子连线：同列内嵌走竖线；跨列走贝塞尔。
 */
function treeEdges(spans: ObsSpan[], positions: Map<number, LayoutNode>): string[] {
  const edges: string[] = []
  for (const span of spans) {
    const parentId = span.parentId
    if (parentId == null) {
      continue
    }
    const from = positions.get(parentId)
    const to = positions.get(span.id)
    if (!from || !to) {
      continue
    }
    // 同列：父底 → 子顶
    if (Math.abs(from.x - to.x) < 1) {
      const x = from.x + NODE_W / 2
      const y1 = from.y + NODE_H
      const y2 = to.y
      const mid = (y1 + y2) / 2
      edges.push(`M ${x} ${y1} C ${x} ${mid}, ${x} ${mid}, ${x} ${y2}`)
      continue
    }
    const x1 = from.x + NODE_W
    const y1 = from.y + NODE_H / 2
    const x2 = to.x
    const y2 = to.y + NODE_H / 2
    const mid = (x1 + x2) / 2
    edges.push(`M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`)
  }
  return edges
}

function toDrawn(node: LayoutNode): DrawnNode {
  const span = node.span
  const name = displayName(span)
  return {
    id: span.id,
    x: node.x,
    y: node.y,
    stepLabel: span.stepNo && span.stepNo.trim() ? span.stepNo : String(span.seq),
    typeLabel: typeLabel(span.spanType),
    latency: span.latencyMs == null ? '' : `${span.latencyMs}ms`,
    name: name.length > 14 ? `${name.slice(0, 13)}…` : name,
    stroke: strokeOf(span),
    tip: `#${span.stepNo && span.stepNo.trim() ? span.stepNo : span.seq} ${name} · ${span.latencyMs ?? '—'}ms`
  }
}

function displayName(span: ObsSpan): string {
  if (span.spanType === 'LLM') return span.modelName || span.name || '模型'
  if (span.spanType === 'TOOL') return span.toolName || span.name || '工具'
  return span.name || typeLabel(span.spanType)
}

function typeLabel(spanType: string | null | undefined): string {
  if (spanType === 'LLM') return '模型'
  if (spanType === 'TOOL') return '工具'
  if (spanType === 'VISION') return '图片识别'
  if (spanType === 'ROUTE') return '路由'
  if (spanType === 'PLAN') return '规划'
  if (spanType === 'RETRIEVE') return '检索'
  if (spanType === 'AGENT') return 'Agent'
  return spanType || '步骤'
}

/** 失败红、超时橙、成功绿、进行中紫 */
function strokeOf(span: ObsSpan): string {
  if (span.status === 'ERROR') return '#d92d20'
  const reason = (span.finishReason || '').toLowerCase()
  if ((span.latencyMs ?? 0) > 3000 || reason.includes('timeout')) return '#dc6803'
  if (span.status === 'OK') return '#039855'
  if (span.status === 'CANCELLED') return '#98a2b3'
  return '#635bff'
}
</script>

<style scoped>
.topo {
  margin-bottom: 16px;
  padding: 8px;
  /* 内容变宽时横向滚动，避免首尾节点被裁 */
  overflow-x: auto;
  overflow-y: auto;
  max-width: 100%;
  background: #f8fafc;
  border: 1px solid var(--ah-border);
  border-radius: 12px;
}

.topo__svg {
  display: block;
  /* 禁止被父级 flex/grid 压窄，否则会缩放裁切而非滚动 */
  flex-shrink: 0;
}

.topo__svg text {
  font-family: inherit;
}
</style>
