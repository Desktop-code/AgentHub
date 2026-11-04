<template>
  <!--
    执行链路树形拓扑（自左而右）。所属域：obs。
    布局：同父步骤按「模板 + span_type + 执行序」分组（traceGraphLayout），
    串行步骤横向成链、只有并行模板的多 AGENT 才纵向并排；每个 AGENT 子树单独布局。
    同轮多个 TOOL 一律按 seq 横串（用户 2026-11-04 拍板，取消短并）。
    配色：按 span_type 淡色描边（边框为主、底色近白），禁高饱和大色块。
    汇总 LLM 排在全部 AGENT 之后：有分叉由各支子树末端 N 路汇合（折线：横→竖→横），
    无分叉沿链尾单线接续。
    异常态：ERROR 只在框右下外侧补小红字，不改类型色、不整卡变红；重试只认 retryCount>0。
    边：串行链连上一块末端；本层第一个 TOOL 同挂 AGENT 时连最近前序 LLM（仅影响画）。
  -->
  <div v-if="layout.nodes.length" class="topo">
    <!-- 图例标题 + 导出：导出只读当前 SVG，不影响布局与数据 -->
    <div class="topo__head">
      <span class="topo__title">执行拓扑</span>
      <el-tooltip content="导出图片" placement="top" :show-after="200">
        <el-button
          class="topo__export"
          link
          type="primary"
          size="small"
          :loading="exporting"
          aria-label="导出图片"
          @click="exportImage"
        >
          <el-icon v-if="!exporting" :size="16"><Download /></el-icon>
        </el-button>
      </el-tooltip>
    </div>
    <div class="topo__scroll">
      <svg
        ref="svgRef"
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
        <g
          v-for="node in layout.nodes"
          :key="node.id"
          class="topo__node"
          :class="{ 'is-active': activeId != null && node.id === activeId }"
          :transform="`translate(${node.x}, ${node.y})`"
          @click="emit('select', node.id)"
        >
          <rect
            :width="NODE_W"
            :height="NODE_H"
            rx="8"
            :fill="node.fill"
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
          <!-- 角标贴在框外：框内空间已给序号/耗时/名称，塞进去必然重叠 -->
          <text
            v-if="node.summaryLabel"
            x="2"
            :y="NODE_H + 12"
            fill="#6366F1"
            font-size="10"
            font-weight="600"
          >{{ node.summaryLabel }}</text>
          <text
            v-if="node.retryLabel"
            x="2"
            :y="NODE_H + 12"
            fill="#DC6803"
            font-size="10"
            font-weight="600"
          >{{ node.retryLabel }}</text>
          <!-- ERROR 只在右下补小红字：类型描边保留，不做整卡红 -->
          <text
            v-if="node.failed"
            :x="NODE_W"
            :y="NODE_H + 12"
            text-anchor="end"
            fill="#DC2626"
            font-size="10"
            font-weight="600"
          >ERROR</text>
          <title>{{ node.tip }}</title>
        </g>
      </svg>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import type { ObsSpan } from '@/types/obs'
import { compareSpanExecOrder } from '@/utils/executionOrder'
import { traceTypePalette } from '@/utils/tracePalette'
import {
  groupSiblingSpans,
  resolveTraceLayoutContext,
  type TraceLayoutContext,
  type TraceLayoutItem
} from '@/utils/traceGraphLayout'

const props = defineProps<{
  spans: ObsSpan[]
  /** 当前选中的节点 id：用于框住刚点过的节点，点完滚走也能回看 */
  activeId?: number | null
}>()

const emit = defineEmits<{
  /** 点击节点：把 span.id 抛给列表区做定位 */
  select: [spanId: number]
}>()

const NODE_W = 208
const NODE_H = 52
/** 串行层与层之间的横向间距，留给连线 */
const GAP_X = 56
/** 并行分支之间的纵向间距 */
const GAP_Y = 32
/** 四周留边，避免首尾节点贴边被裁 */
const PAD = 24

/**
 * 拓扑节点：自身卡片 + 右侧子块链；subtree 是子树纵向占位高度 */
interface LayoutNode {
  span: ObsSpan
  childBlocks: LayoutBlock[]
  /** 本节点子树占位高度（像素）；卡片在其中垂直居中 */
  subtree: number
  /**
   * 串行链上一块的末端节点 id。
   * <p>串行块被排到上一层子树右侧，若仍从原始父节点拉边会穿过中间节点；
   * 改连上一块末端，画成真正的「横链」。仅影响画线，不改库边。
   */
  chainFromId?: number
  /**
   * 汇总 / 收束步标记。
   * <p>只有「同层存在分叉（并行多 AGENT）」时才转成 N 路汇合；
   * 纯串行链上它仍按链尾接续，避免无分叉场景画出一堆交叉线。
   */
  aggregate?: boolean
  /** 汇合来源步骤主键：由 placeBlockChain 在摆放时填充（仅画线用） */
  convergeFromIds?: number[]
  /** 本节点子树的末端节点 id：无子块时是自己，用于父级汇合收线 */
  tailId?: number
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
  /** 类型描边色：边框与左侧色条同色，只做淡色区分 */
  stroke: string
  /** 卡片底色：按类型近白浅底，禁止高饱和大色块 */
  fill: string
  /** 是否失败（status=ERROR 或工具 toolSuccess=0）：只在框右下补小红字 */
  failed: boolean
  /** 汇总 / 收束角标文案；非汇总为空 */
  summaryLabel: string
  /** 重试角标文案；无重试数据为空（现网 retryCount 恒为 0） */
  retryLabel: string
  tip: string
}

/** 放置一块后的最深层，供后续串行块向下接续 */
interface PlaceResult {
  maxDepth: number
  /** 本块子树最右侧节点的 id；汇总汇合用它，保证线从各支末端收进来 */
  tailId?: number
  /**
   * 本块子树内部出现过的「分叉末端」。
   * <p>关键：分叉往往发生在子树深处（ROUTE → 判路由 LLM → N 个并行 AGENT），
   * 汇总 LLM 却是上一层 ROUTE 的兄弟。必须把分叉末端沿子树**向上冒泡**，
   * 上层汇总才能收齐每一路；否则只能连到子树最后一个叶节点（用户手点到的「只接 1～2 支」）。
   */
  branchTails?: number[]
}

const layout = computed(() => {
  // 执行序：step_no → seq → id；与右栏瀑布同源，禁止按类型 / 名称排
  const spans = [...props.spans].sort(compareSpanExecOrder)
  const byId = new Map(spans.map((item) => [item.id, item]))
  // 模板只读 ROUTE attrs / 名称，缺失时多 AGENT 默认串行，不靠墙钟猜并行
  const ctx = resolveTraceLayoutContext(spans)
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
    list.sort(compareSpanExecOrder)
  }
  roots.sort(compareSpanExecOrder)

  // 根层同样走语义分组：串行成横链、只有并行模板的多 AGENT 才纵向并排
  const rootBlocks = itemsToBlocks(groupSiblingSpans(roots, null, ctx), childMap, ctx)
  for (const block of rootBlocks) {
    measureBlock(block)
  }

  const drawn: DrawnNode[] = []
  const positions = new Map<number, LayoutNode>()
  const placed = placeBlockChain(rootBlocks, 0, PAD, drawn, positions)

  // 边来自父子树；TOOL 同挂 AGENT 时改连最近前序 LLM，仅影响画线
  const edges = treeEdges(spans, positions, childMap, byId)

  // 宽度按实际最深一层算；高度取根链最高子树，避免右侧留白
  const chainH = rootBlocks.length ? Math.max(...rootBlocks.map(blockSize)) : NODE_H
  const depthCount = placed.maxDepth + 1
  return {
    nodes: drawn,
    edges,
    width: Math.max(
      PAD + depthCount * NODE_W + Math.max(0, depthCount - 1) * GAP_X + PAD,
      NODE_W + PAD * 2
    ),
    height: Math.max(PAD + chainH + PAD, NODE_H + PAD * 2)
  }
})

/** 导出用的 SVG 元素；为空说明图还没渲染 */
const svgRef = ref<SVGSVGElement | null>(null)
/** 导出进行中：防重复点击，也用于按钮 loading */
const exporting = ref(false)
/** 导出像素倍率：2 倍足够打印清晰，同时限制总边长避免 canvas 超限 */
const EXPORT_SCALE = 2
/** 单边上限；超限自动降倍率，避免某些浏览器 canvas 尺寸上限导致空白 */
const EXPORT_MAX_SIDE = 8000

/**
 * 右上角「导出图片」：把当前拓扑 SVG 序列化为 2 倍 PNG 并触发下载。
 * <p>只读 DOM，不改布局数据、不发请求；失败仅提示，不抛到页面。
 */
async function exportImage() {
  const svg = svgRef.value
  if (!svg || exporting.value) {
    return
  }
  exporting.value = true
  try {
    const { width, height } = layout.value
    const blob = await svgToPngBlob(svg, width, height)
    downloadBlob(blob, exportFileName())
    ElMessage.success('图片已导出')
  } catch {
    // 浏览器不支持 canvas 转图 / 内存不足时兜底提示，避免静默失败
    ElMessage.error('导出图片失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

/**
 * SVG → PNG Blob。
 *
 * @param svg    页面上已渲染的拓扑 SVG
 * @param width  逻辑宽度（像素）
 * @param height 逻辑高度（像素）
 * @return PNG 二进制
 */
async function svgToPngBlob(svg: SVGSVGElement, width: number, height: number): Promise<Blob> {
  // 倍率按最长边收敛，避免超大 trace 触发 canvas 尺寸上限
  const longest = Math.max(width, height, 1)
  const scale = Math.min(EXPORT_SCALE, EXPORT_MAX_SIDE / longest)
  const clone = svg.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  clone.setAttribute('width', String(width))
  clone.setAttribute('height', String(height))
  // 页面里的字体靠 CSS 继承，序列化后读不到：显式写到克隆节点上
  clone.style.fontFamily = getComputedStyle(svg).fontFamily || 'sans-serif'
  // 原图背景透明，PNG 里会变黑，先垫一层白底
  clone.insertBefore(createBackgroundRect(width, height), clone.firstChild)

  const xml = new XMLSerializer().serializeToString(clone)
  const svgUrl = URL.createObjectURL(
    new Blob([`<?xml version="1.0" encoding="UTF-8"?>${xml}`], {
      type: 'image/svg+xml;charset=utf-8'
    })
  )
  try {
    const image = await loadImage(svgUrl)
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(width * scale))
    canvas.height = Math.max(1, Math.round(height * scale))
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      throw new Error('canvas 2d context unavailable')
    }
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(image, 0, 0, canvas.width, canvas.height)
    return await canvasToBlob(canvas)
  } finally {
    URL.revokeObjectURL(svgUrl)
  }
}

/** 白底矩形：与卡片背景一致，避免透明 PNG 在深色阅读器上文字看不清 */
function createBackgroundRect(width: number, height: number): SVGRectElement {
  const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect')
  rect.setAttribute('x', '0')
  rect.setAttribute('y', '0')
  rect.setAttribute('width', String(width))
  rect.setAttribute('height', String(height))
  rect.setAttribute('fill', '#ffffff')
  return rect
}

/** 把 SVG 的 blob URL 读进 Image；失败时 reject 给上层统一提示 */
function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('svg image decode failed'))
    image.src = url
  })
}

/** canvas → PNG；不支持 toBlob 的旧 WebKit 退回 dataURL */
function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) {
        resolve(blob)
        return
      }
      try {
        const dataUrl = canvas.toDataURL('image/png')
        const base64 = dataUrl.split(',')[1] ?? ''
        const binary = atob(base64)
        const bytes = new Uint8Array(binary.length)
        for (let i = 0; i < binary.length; i += 1) {
          bytes[i] = binary.charCodeAt(i)
        }
        resolve(new Blob([bytes], { type: 'image/png' }))
      } catch (error) {
        reject(error instanceof Error ? error : new Error('canvas to blob failed'))
      }
    }, 'image/png')
  })
}

/** 触发浏览器下载；对象 URL 用完即释放，避免长会话泄漏 */
function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

/** 文件名带时间戳，连续导出多次不会互相覆盖 */
function exportFileName(): string {
  const now = new Date()
  const pad = (v: number) => String(v).padStart(2, '0')
  const stamp =
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `trace-topology-${layout.value.nodes.length}steps-${stamp}.png`
}

/**
 * 将语义分组结果转为可递归布局的块；并行成员按执行序自上而下排。
 */
function itemsToBlocks(
  items: TraceLayoutItem[],
  childMap: Map<number, ObsSpan[]>,
  ctx: TraceLayoutContext
): LayoutBlock[] {
  return items.map((item) => {
    if (item.kind === 'parallel') {
      const ordered = [...item.spans].sort(compareSpanExecOrder)
      return {
        kind: 'parallel' as const,
        nodes: ordered.map((span) => buildNode(span, childMap, ctx))
      }
    }
    return {
      kind: 'single' as const,
      node: buildNode(item.span, childMap, ctx, item.aggregate === true)
    }
  })
}

/**
 * 构建单节点及其右侧子块。
 * <p>每个节点只按自己的直接子节点分组：AGENT 子树单独布局，不拿根层算法扫叶。
 * 模板 / 类型规则统一在 {@link groupSiblingSpans} 内，组件只负责摆放。
 *
 * @param span      当前步骤
 * @param childMap  同父兄弟表
 * @param ctx       整条 trace 的布局上下文
 * @param aggregate 是否汇总 / 收束步：true 时改由同层全部 AGENT 根汇入
 */
function buildNode(
  span: ObsSpan,
  childMap: Map<number, ObsSpan[]>,
  ctx: TraceLayoutContext,
  aggregate = false
): LayoutNode {
  const kids = childMap.get(span.id) ?? []
  const childBlocks = itemsToBlocks(groupSiblingSpans(kids, span, ctx), childMap, ctx)
  return { span, childBlocks, subtree: NODE_H, aggregate, x: 0, y: 0 }
}

/**
 * 将有序块沿主轴（左→右）链式放置：串行块各占一层，并行块在同一层纵向并排。
 * <p>串行块在整条链的高度内垂直居中，避免窄子树把整棵图拽偏。
 * 汇总块：链上出现过并行多 AGENT 分叉，就由**各支子树末端**共同汇入；
 * 只有串行（无分叉）时才沿链尾单线接续。
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
  const chainH = Math.max(...blocks.map(blockSize))
  let nextDepth = startDepth
  let maxDepth = startDepth
  /**
   * 本链已出现过的分叉末端，按出现顺序累积。
   * <p>不再「中间插一个串行步就清空」——那样汇总只剩链尾一条线，
   * 正是用户手点到的「只接 1～2 支」。这里保留全部分支，汇总一次性全收。
   */
  const fanOutTails: number[] = []
  let tailId: number | undefined

  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i]
    const bh = blockSize(block)
    const aggregate = block.kind === 'single' && block.node.aggregate === true
    /*
     * 汇总卡必须先站到「全部汇合来源右侧一列」再摆放。
     * 否则它可能落在某支末端的左边，汇合线只能先向右再折回来——画出来就是
     * 一条竖直折线压进卡片、横线从卡上穿过去（用户手点到的「拐弯」）。
     */
    if (aggregate && fanOutTails.length > 1) {
      nextDepth = Math.max(nextDepth, depthRightOfAll(fanOutTails, positions, nextDepth))
    }
    // 记录上一块末端：本块不是首个时，边改连它，避免从父节点拉长边穿子树
    const tailBefore = i > 0 && drawn.length ? drawn[drawn.length - 1].id : null
    // 串行块在整链高度内居中；并行块内部各支纵向并排
    const blockTop = top + (chainH - bh) / 2
    const result = placeBlock(block, nextDepth, blockTop, drawn, positions)
    // 先收分支末端再判汇总：汇总块自己不是分叉，不能影响自己的汇合来源
    for (const id of blockBranchTails(block, result)) {
      if (!fanOutTails.includes(id)) {
        fanOutTails.push(id)
      }
    }
    if (aggregate) {
      // 有分叉：每一路末端都拉一条进汇总卡，禁止只接其中一两支
      if (fanOutTails.length > 1) {
        block.node.convergeFromIds = [...fanOutTails]
      } else if (tailBefore != null) {
        block.node.chainFromId = tailBefore
      }
    } else if (tailBefore != null) {
      markChainFrom(block, tailBefore)
    }
    tailId = result.tailId
    nextDepth = result.maxDepth + 1
    maxDepth = result.maxDepth
  }
  return { maxDepth, tailId, branchTails: [...fanOutTails] }
}

/**
 * 求「排在全部来源右侧一列」所需的层号。
 * <p>深蓝层号由 x 反算：x = PAD + depth * (NODE_W + GAP_X)，四舍五入即可还原。
 *
 * @param sourceIds 汇合来源节点 id
 * @param positions 已放置节点
 * @param fallback  取不到来源时的回退层号
 * @return 应使用的层号
 */
function depthRightOfAll(
  sourceIds: number[],
  positions: Map<number, LayoutNode>,
  fallback: number
): number {
  let deepest = -1
  for (const id of sourceIds) {
    const node = positions.get(id)
    if (!node) {
      continue
    }
    const depth = Math.round((node.x - PAD) / (NODE_W + GAP_X))
    deepest = Math.max(deepest, depth)
  }
  return deepest >= 0 ? deepest + 1 : fallback
}

/**
 * 取一块的「分支末端」。
 * <p>两种情况都算分叉来源：
 * 1) 本块是并行多 AGENT → 各支子树末端；
 * 2) 本块是单步，但它的子树内部已出现过分叉 → 把子树冒泡上来的末端继续往上带。
 * 串行且子树无分叉时返回空，避免把单线画成多路汇合。
 *
 * @param block  刚放好的块
 * @param result 该块放置结果（含子树冒泡上来的 branchTails）
 * @return 分叉末端 id 列表
 */
function blockBranchTails(block: LayoutBlock, result: PlaceResult): number[] {
  if (block.kind === 'parallel') {
    const agentTails = block.nodes
      .filter((node) => node.span.spanType === 'AGENT' && node.tailId != null)
      .map((node) => node.tailId as number)
    // 多 AGENT 并排本身就是分叉；不足两支时退回子树内部的分叉
    return agentTails.length > 1 ? agentTails : result.branchTails ?? []
  }
  return result.branchTails ?? []
}

/** 给一块内的所有节点记下链式画线起点 */
function markChainFrom(block: LayoutBlock, fromId: number) {
  if (block.kind === 'single') {
    block.node.chainFromId = fromId
    return
  }
  for (const node of block.nodes) {
    node.chainFromId = fromId
  }
}

/** 测量一块：单步取其子树高度，并排取各支之和加间距 */
function measureBlock(block: LayoutBlock): number {
  if (block.kind === 'single') {
    return measure(block.node)
  }
  const sum = block.nodes.reduce((acc, node) => acc + measure(node), 0)
  return sum + Math.max(0, block.nodes.length - 1) * GAP_Y
}

/**
 * 测量节点子树高度。
 * <p>卡片固定高；子块链取最高一块，父子取较大者，使父卡能在子树内居中。
 */
function measure(node: LayoutNode): number {
  if (!node.childBlocks.length) {
    node.subtree = NODE_H
    return NODE_H
  }
  let chainH = 0
  for (const block of node.childBlocks) {
    chainH = Math.max(chainH, measureBlock(block))
  }
  node.subtree = Math.max(NODE_H, chainH)
  return node.subtree
}

/** 块高度：单步取子树高度，并排取各支子树高度之和加纵向间距 */
function blockSize(block: LayoutBlock): number {
  if (block.kind === 'single') {
    return block.node.subtree
  }
  const sum = block.nodes.reduce((acc, node) => acc + node.subtree, 0)
  return sum + Math.max(0, block.nodes.length - 1) * GAP_Y
}

/**
 * 放置一块：单步向右展开；并行成员同层纵向并排，各自子树独立向右铺。
 * <p>返回值带上本块最深列与最右末端，供上层汇合收线。
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
  let tailId: number | undefined
  // 并行各支内部分叉的并集：用于再往上一层冒泡（如 AGENT 里还有嵌套编排）
  const innerBranch: number[] = []
  for (const node of block.nodes) {
    const inner = place(node, depth, cursor, drawn, positions)
    maxDepth = Math.max(maxDepth, inner.maxDepth)
    tailId = inner.tailId ?? tailId
    for (const id of inner.branchTails ?? []) {
      if (!innerBranch.includes(id)) {
        innerBranch.push(id)
      }
    }
    cursor += node.subtree + GAP_Y
  }
  return { maxDepth, tailId, branchTails: innerBranch }
}

/**
 * 放置节点卡片：卡片在自身子树高度内垂直居中，子块链从下一列继续向右。
 * <p>同时记下本子树的末端 id 与内部分叉末端，供上层汇总收线。
 */
function place(
  node: LayoutNode,
  depth: number,
  top: number,
  drawn: DrawnNode[],
  positions: Map<number, LayoutNode>
): PlaceResult {
  node.x = PAD + depth * (NODE_W + GAP_X)
  node.y = top + (node.subtree - NODE_H) / 2
  positions.set(node.span.id, node)
  drawn.push(toDrawn(node))

  if (!node.childBlocks.length) {
    node.tailId = node.span.id
    return { maxDepth: depth, tailId: node.span.id }
  }

  // 子块链在下一列向右铺开；窄子树也要在父卡高度内居中，避免整棵图被拽偏
  const chainH = Math.max(...node.childBlocks.map(blockSize))
  const childTop = top + (node.subtree - chainH) / 2
  const childChain = placeBlockChain(node.childBlocks, depth + 1, childTop, drawn, positions)
  // 子树末端决定汇合线从哪张卡出去：取子链最右，缺省退回自己
  node.tailId = childChain.tailId ?? node.span.id
  return {
    maxDepth: childChain.maxDepth,
    tailId: node.tailId,
    branchTails: childChain.branchTails
  }
}

/**
 * 父子连线：父卡右边中点 → 子卡左边中点。
 * <p>串行链用贝塞尔平滑过渡（同 y 即一条横直线）；汇总 / 收束的多路汇合改用折线，
 * 让每一路「横 → 竖 → 横」都看得清。三条改连规则只影响画线，不改库边：
 * 1) 汇总 / 收束步在同层有分叉时由各支末端共同汇入，画成 N 路汇合再进总结；
 * 2) 串行链上的后续块连上一块末端（含同轮多个 TOOL），避免长边穿过中间卡；
 * 3) 本层第一个 TOOL 与 LLM 同挂 AGENT 时连最近前序 LLM，避免从 Agent 顶点斜拉成两翼。
 *
 * @param spans    全部步骤
 * @param positions 已放置节点
 * @param childMap 同父兄弟表（已按执行序排）
 * @param byId     主键索引
 */
function treeEdges(
  spans: ObsSpan[],
  positions: Map<number, LayoutNode>,
  childMap: Map<number, ObsSpan[]>,
  byId: Map<number, ObsSpan>
): string[] {
  const edges: string[] = []
  for (const span of spans) {
    const parentId = span.parentId
    if (parentId == null) {
      continue
    }
    const to = positions.get(span.id)
    if (!to) {
      continue
    }
    // 汇总步：每个分叉末端各拉一条进同一张卡。
    // 所有竖折共用同一条「汇合竖杆」，且竖杆固定落在目标卡左侧的空隙里，
    // 这样每一路都是「向右 → 竖对齐 → 向右进卡」，不会回头穿卡。
    if (to.convergeFromIds?.length) {
      const sources = to.convergeFromIds
        .map((id) => positions.get(id))
        .filter((node): node is LayoutNode => node != null)
      const busX = convergeBusX(sources, to)
      for (const source of sources) {
        edges.push(elbowLink(source, to, busX))
      }
      continue
    }
    const fromId = resolveEdgeFromId(span, childMap, byId, to)
    const from = positions.get(fromId)
    if (!from) {
      continue
    }
    edges.push(hLink(from, to))
  }
  return edges
}

/**
 * 横向连线路径：父卡右边中点 → 子卡左边中点，控制点取水平折中。
 * <p>同列串行时 y 相同即为一条横线；跨列汇合时呈扇形收束。
 *
 * @param from 起点节点
 * @param to   终点节点
 * @return SVG path 的 d
 */
function hLink(from: LayoutNode, to: LayoutNode): string {
  const x1 = from.x + NODE_W
  const y1 = from.y + NODE_H / 2
  const x2 = to.x
  const y2 = to.y + NODE_H / 2
  const midX = (x1 + x2) / 2
  return `M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`
}

/**
 * 汇合竖杆的 x：所有汇合线共用一条，且必须落在「来源最右边缘」与「目标卡左边缘」之间。
 * <p>取两者中靠右一点的位置（来源右侧 + 24px），这样竖杆紧贴分支列、远离目标卡，
 * 不会压在卡片上；间距过窄时收敛到中点，保证仍是正向、不回头。
 *
 * @param sources 全部汇合来源
 * @param to      汇总 / 收束卡
 * @return 竖杆 x 坐标
 */
function convergeBusX(sources: LayoutNode[], to: LayoutNode): number {
  const x2 = to.x
  if (!sources.length) {
    return x2
  }
  const maxRight = Math.max(...sources.map((node) => node.x + NODE_W))
  const gap = x2 - maxRight
  // 间距充足：竖杆贴着来源列右边一点，留出清晰的横入段
  if (gap > 48) {
    return maxRight + 24
  }
  // 间距紧张：取中间，仍保证在来源右侧、目标左侧
  return maxRight + Math.max(8, gap / 2)
}

/**
 * 汇合折线：横出 → 竖向对齐 → 横入。
 * <p>汇总卡居中、各支末端高低不齐，用弧线会糊成一团；折线能让每一路都看得清。
 * 竖杆位置由 {@link convergeBusX} 统一给定，保证只向右走、不回头穿卡。
 *
 * @param from 起点（某支子树末端）
 * @param to   终点（汇总 / 收束卡）
 * @param busX 共用竖杆 x；省略时取两点中点
 * @return SVG path 的 d
 */
function elbowLink(from: LayoutNode, to: LayoutNode, busX?: number): string {
  const x1 = from.x + NODE_W
  const y1 = from.y + NODE_H / 2
  const x2 = to.x
  const y2 = to.y + NODE_H / 2
  // 同一水平：直接横线，避免中间多出一段零长竖线
  if (Math.abs(y1 - y2) < 0.5) {
    return `M ${x1} ${y1} H ${x2}`
  }
  const bar = busX ?? x1 + Math.max(0, x2 - x1) / 2
  // 竖杆必须落在两点之间，否则会出现回头段
  const safeBar = Math.min(Math.max(bar, x1), x2)
  return `M ${x1} ${y1} H ${safeBar} V ${y2} H ${x2}`
}

/**
 * 解析画线起点，只在展示层改连，不改库边。
 * <p>两条规则按优先级：
 * 1) 串行链上的后续块：连上一块末端，画成横链，避免从父节点拉长边穿过中间子树；
 * 2) 本层第一个工具 + 父是 AGENT：连同父里最近的前序 LLM（没有则保持原父）。
 *
 * @param span     当前步骤
 * @param childMap 同父兄弟表
 * @param byId     主键索引
 * @param to       当前步骤已放置的布局节点；可空
 * @return 画线起点步骤主键
 */
function resolveEdgeFromId(
  span: ObsSpan,
  childMap: Map<number, ObsSpan[]>,
  byId: Map<number, ObsSpan>,
  to?: LayoutNode
): number {
  const parentId = span.parentId as number
  const parent = byId.get(parentId)
  // 1) 串行链跨层：连上一块末端，画成一条横链。
  //    必须排在「连最近前序 LLM」之前：否则同轮第 2、3 个 TOOL 会各自从同一个 LLM 拉长线，
  //    横穿中间的工具卡（串行化后这是常见形态）。
  if (to?.chainFromId != null) {
    return to.chainFromId
  }
  // 2) 工具 + 父是 AGENT（Recorder 把同轮工具挂 Agent 的旧树形态）：
  //    链上没有前序（本层第一个）时才退回最近前序 LLM，避免从 Agent 顶斜拉成两翼
  if (parent && parent.spanType === 'AGENT' && span.spanType === 'TOOL') {
    const siblings = childMap.get(parentId) ?? []
    const index = siblings.findIndex((item) => item.id === span.id)
    for (let i = index - 1; i >= 0; i -= 1) {
      if (siblings[i].spanType === 'LLM') {
        return siblings[i].id
      }
    }
  }
  return parentId
}

/**
 * 布局节点 → 绘图节点。
 * <p>配色只按 span_type 定淡色描边与近白底；ERROR 不再改描边/底色，
 * 只在框右下补小红字，保证「类型可辨」优先于「异常告警」。
 *
 * @param node 已放好的布局节点
 * @return 供模板渲染的节点
 */
function toDrawn(node: LayoutNode): DrawnNode {
  const span = node.span
  const name = displayName(span)
  // 显式 ERROR 或工具自报失败（toolSuccess=0）都按失败画；超时/慢不混进失败口径
  const failed =
    span.status === 'ERROR' || (span.spanType === 'TOOL' && span.toolSuccess === 0)
  const stepLabel = span.stepNo && span.stepNo.trim() ? span.stepNo : String(span.seq)
  const latency = span.latencyMs == null ? '—' : `${span.latencyMs}ms`
  // 失败说明放进 tooltip：卡片上只留小红字，避免小卡塞满长文案
  const failNote = failed ? (span.errorSummary ? ` · ${span.errorSummary}` : ' · 执行失败') : ''
  // 重试只认落库字段：现网 TrialSpanSink 恒写 0，改这里即可点亮，不做「同工具名连续」猜测
  const retry = span.spanType === 'TOOL' ? Math.max(0, span.retryCount ?? 0) : 0
  const palette = traceTypePalette(span.spanType)
  return {
    id: span.id,
    x: node.x,
    y: node.y,
    stepLabel,
    typeLabel: typeLabel(span.spanType),
    latency: span.latencyMs == null ? '' : `${span.latencyMs}ms`,
    name: name.length > 14 ? `${name.slice(0, 13)}…` : name,
    stroke: palette.border,
    fill: palette.fill,
    failed,
    summaryLabel: node.aggregate ? '汇总' : '',
    retryLabel: retry > 0 ? `重试 ${retry}` : '',
    tip: `#${stepLabel} ${name} · ${latency}${retry > 0 ? ` · 重试 ${retry} 次` : ''}${failNote}`
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
</script>

<style scoped>
.topo {
  margin-bottom: 16px;
  padding: 8px;
  max-width: 100%;
  background: #f8fafc;
  border: 1px solid var(--ah-border);
  border-radius: 12px;
}

/* 标题行固定在卡片顶部，图横向滚动时导出按钮仍停在右上角 */
.topo__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 2px 6px;
}

.topo__title {
  color: var(--ah-text-secondary, #667085);
  font-size: 12px;
}

/* 纯图标按钮：收紧内边距，让下载图标贴着右上角 */
.topo__export {
  padding: 2px;
  color: var(--ah-text-secondary, #667085);
}

.topo__export:hover {
  color: var(--el-color-primary, #635bff);
}

.topo__scroll {
  /* 横向布局内容很宽时横向滚动，避免首尾节点被裁 */
  overflow-x: auto;
  overflow-y: auto;
  max-width: 100%;
}

.topo__svg {
  display: block;
  /* 禁止被父级 flex/grid 压窄，否则会缩放裁切而非滚动 */
  flex-shrink: 0;
}

.topo__svg text {
  font-family: inherit;
}

/* 节点可点：整块缩放式悬停提示，便于发现「能联动到下面列表」 */
.topo__node {
  cursor: pointer;
}

.topo__node:hover rect:first-of-type {
  stroke-width: 2.5;
}

/* 刚点过的节点：描边加粗，滚回来看图时仍能对上号 */
.topo__node.is-active rect:first-of-type {
  stroke-width: 2.5;
}
</style>
