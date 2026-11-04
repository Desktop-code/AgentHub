<template>
  <!--
    执行链路列表区（递归）。所属域：obs。
    一级：并行块 → 虚线包裹容器 + 竖排 AGENT 大卡；串行 → 直接平铺。
    二级：AGENT 大卡展开后渲染子 LLM/TOOL 卡，通过自身递归继续往下；
          并行容器内的一组 AGENT 也复用同一个组件的 plain 模式。
    分组规则与顶部拓扑同源（traceGraphLayout.groupSiblingSpans），禁止各算一套。
  -->
  <div class="trace-list">
    <template v-for="(item, idx) in blocks" :key="keyOf(item, idx)">
      <!-- 并行容器：浅紫底 + 虚线框，内部竖排，不再用 2×2 网格 -->
      <section v-if="item.kind === 'parallel'" class="par">
        <header class="par__head">
          <span class="par__badge">⚡ 并行执行</span>
          <span class="par__count">（{{ item.spans.length }} 个任务）</span>
          <span class="par__wall">总耗时 {{ item.wallMs }}ms</span>
        </header>
        <div class="par__stack">
          <TraceListView
            :blocks="agentBlocks(item)"
            :index="index"
            :is-wide-mode="isWideMode"
            :gantt-of="ganttOf"
            :active-span-id="activeSpanId"
            :expand-ids="expandIds"
          />
        </div>
      </section>

      <!-- 单步卡：AGENT 默认只留 Header，其余（LLM/TOOL/ROUTE）默认展开 -->
      <TraceSpanCard
        v-else
        :span="item.span"
        :is-wide-mode="isWideMode"
        :hide-indent="true"
        :gantt="ganttOf ? ganttOf(item.span) : null"
        collapsible
        :default-open="item.span.spanType !== 'AGENT'"
        :active-span-id="activeSpanId"
        :force-open="expandIds.has(item.span.id)"
      >
        <TraceListView
          v-if="childBlocksOf(item.span).length"
          :blocks="childBlocksOf(item.span)"
          :index="index"
          :is-wide-mode="isWideMode"
          :gantt-of="ganttOf"
          :active-span-id="activeSpanId"
          :expand-ids="expandIds"
        />
      </TraceSpanCard>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 执行链路列表区（递归组件）。所属域：obs。
 *
 * 职责：把语义分组结果渲染成「并行容器 + 可折叠节点卡」。
 * 关键约束：
 * 1) 只有在并行模板下、同父多 AGENT 才出现并行容器（规则来自 groupSiblingSpans）；
 * 2) AGENT 卡默认折叠，失败步骤强制展开；同容器内可同时展开多张；
 * 3) Body 内不画树状折线，层级只用左侧淡虚线表示；
 * 4) 子块按 span.id 缓存，避免每次渲染重建数组导致子卡片反复挂载、展开态丢失；
 * 5) 上方拓扑点选节点时，本组件只负责把 activeSpanId / 祖先链往下传，
 *    真正「展开 + 滚动 + 高亮」由命中的 TraceSpanCard 自己完成（避免跨层找 DOM）。
 */
import { watch } from 'vue'
import type { ObsSpan } from '@/types/obs'
import type { SpanBar } from '@/utils/traceParallel'
import { treeBlocksOf, type TraceLayoutItem, type TraceTreeIndex } from '@/utils/traceGraphLayout'
import TraceSpanCard from '@/components/obs/TraceSpanCard.vue'

const props = withDefaults(
  defineProps<{
    /** 本层要渲染的块（串行单步 / 并行组） */
    blocks: TraceLayoutItem[]
    /** 整条 trace 的树索引：提供父→子表与模板上下文 */
    index: TraceTreeIndex
    /** 放大抽屉宽屏模式，下传子卡 */
    isWideMode?: boolean
    /** 单步的甘特条取值函数；不传则不画时间轴 */
    ganttOf?: ((span: ObsSpan) => SpanBar | null) | null
    /** 上方拓扑点中的节点 id：同一份值递归下传，命中卡自行展开并滚动到位 */
    activeSpanId?: number | null
    /** 被点节点的祖先链：这些卡必须自动展开，否则目标卡藏在折叠 Body 里 */
    expandIds?: Set<number>
  }>(),
  { isWideMode: false, ganttOf: null, activeSpanId: null, expandIds: () => new Set<number>() }
)

/**
 * 子块缓存：按父 id 存 groupSiblingSpans 的结果。
 * <p>不缓存的话模板里每次渲染都会生成新数组，递归子组件会被整体重建，
 * 用户刚展开的卡片会瞬间收回。
 */
const childCache = new Map<number, TraceLayoutItem[]>()

// 换一条 trace（index 变化）就清空缓存，避免串单
watch(
  () => props.index,
  () => childCache.clear()
)

/**
 * 取某个步骤的子块（带缓存）。
 *
 * @param span 父步骤
 * @return 子层块序列；叶子节点返回空数组
 */
function childBlocksOf(span: ObsSpan): TraceLayoutItem[] {
  const cached = childCache.get(span.id)
  if (cached) {
    return cached
  }
  const list = treeBlocksOf(span, props.index)
  childCache.set(span.id, list)
  return list
}

/**
 * 并行容器内的一组 AGENT 转成单步块列表（带缓存）。
 * <p>AGENT 之间是并列关系，容器内不做二次分组，逐个平铺为单步卡。
 */
const agentBlockCache = new WeakMap<TraceLayoutItem, TraceLayoutItem[]>()

function agentBlocks(item: TraceLayoutItem): TraceLayoutItem[] {
  if (item.kind !== 'parallel') {
    return []
  }
  const cached = agentBlockCache.get(item)
  if (cached) {
    return cached
  }
  const list: TraceLayoutItem[] = item.spans.map((span) => ({ kind: 'single', span }))
  agentBlockCache.set(item, list)
  return list
}

/** 列表 key：并行组用成员 id 拼，单步用主键 */
function keyOf(item: TraceLayoutItem, idx: number): string {
  if (item.kind === 'parallel') {
    return `p-${idx}-${item.spans.map((span) => span.id).join('_')}`
  }
  return `s-${item.span.id}`
}
</script>

<style scoped>
.trace-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

/* 卡片自带 margin-bottom，列表已用 gap 控距，避免双倍空隙 */
.trace-list :deep(.span-card) {
  margin-bottom: 0;
}

/* 并行容器：浅紫底 + 淡紫虚线，弱于内容本身，不抢 AGENT 卡 */
.par {
  padding: 10px 12px 12px;
  background: #faf9ff;
  border: 1px dashed #c7c2f5;
  border-radius: 12px;
}

.par__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: var(--ah-text);
  font-size: 12px;
}

.par__badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  background: #ede9fe;
  color: #5b21b6;
  font-size: 11px;
  font-weight: 600;
}

.par__count {
  color: var(--ah-text-secondary, #667085);
}

.par__wall {
  margin-left: auto;
  color: var(--ah-text-secondary, #667085);
  font-variant-numeric: tabular-nums;
}

/* 容器内竖排：一张 AGENT 一行，方便逐张展开对照 */
.par__stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
