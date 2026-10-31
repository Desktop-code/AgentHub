<template>
  <!--
    执行链路主视图。所属域：obs。
    瀑布流始终展示；顶部拓扑仅在放大（showTopology）时出现。
  -->
  <div class="trace-flow">
    <!-- 顶图与瀑布同用原始 spans 做并组；包络只服务甘特条 -->
    <TraceTopology v-if="showTopology" :spans="spans" />

    <div class="trace-flow__list">
      <template v-for="(item, idx) in flowItems" :key="itemKey(item, idx)">
        <!-- 并行容器：虚线框 + 双列网格 -->
        <section v-if="item.kind === 'parallel'" class="parallel">
          <header class="parallel__head">
            <span class="parallel__badge">并行</span>
            <strong>
              并行执行（共 {{ item.spans.length }} 个任务）/ 总耗时 {{ item.wallMs }}ms
            </strong>
          </header>
          <div
            class="parallel__grid"
            :class="{ 'parallel__grid--single': item.spans.length === 1 }"
          >
            <TraceSpanCard
              v-for="span in item.spans"
              :key="span.id"
              :span="span"
              :is-wide-mode="isWideMode"
              :hide-indent="true"
              :gantt="ganttOf(span)"
            />
          </div>
        </section>

        <TraceSpanCard
          v-else
          :span="item.span"
          :is-wide-mode="isWideMode"
          :gantt="ganttOf(item.span)"
        />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ObsSpan } from '@/types/obs'
import {
  buildTraceFlow,
  envelopSpans,
  ganttAxis,
  spanBar,
  type TraceFlowItem
} from '@/utils/traceParallel'
import TraceTopology from '@/components/obs/TraceTopology.vue'
import TraceSpanCard from '@/components/obs/TraceSpanCard.vue'

const props = withDefaults(
  defineProps<{
    spans: ObsSpan[]
    /** 放大抽屉宽屏模式，下传给卡片 */
    isWideMode?: boolean
    /** 是否显示顶部拓扑；三栏窄栏为 false，仅放大抽屉为 true */
    showTopology?: boolean
  }>(),
  { isWideMode: false, showTopology: false }
)

/** 甘特用包络后的 latency；并组用原始 spans，避免父包络把先后步时间窗撑到相交 */
const envelope = computed(() => envelopSpans(props.spans))
const flowItems = computed(() => buildTraceFlow(props.spans))
const axis = computed(() => ganttAxis(envelope.value.spans))

function ganttOf(span: ObsSpan) {
  const enriched = envelope.value.byId.get(span.id) ?? span
  return axis.value ? spanBar(enriched, axis.value) : null
}

function itemKey(item: TraceFlowItem, idx: number): string {
  if (item.kind === 'parallel') {
    return `p-${idx}-${item.spans.map((s) => s.id).join('_')}`
  }
  return `s-${item.span.id}`
}
</script>

<style scoped>
.trace-flow {
  min-width: 0;
}

.trace-flow__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.parallel {
  margin: 4px 0 12px;
  padding: 10px 12px 12px;
  background: #f4f5f7;
  border: 1px dashed #98a2b3;
  border-radius: 12px;
}

.parallel__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--ah-text);
  font-size: 13px;
}

.parallel__badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  background: #635bff;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
}

.parallel__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
}

/* 仅一个任务时占满整行 */
.parallel__grid--single {
  grid-template-columns: 1fr;
}

@media (max-width: 720px) {
  .parallel__grid {
    grid-template-columns: 1fr;
  }
}
</style>
