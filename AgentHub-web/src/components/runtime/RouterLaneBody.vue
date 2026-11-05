<template>
  <!--
    编排泳道正文（只读）。所属域：runtime（展示）。
    总控 / 子 Agent / 汇总三行泳道 + 中文事件列表。
    不含标题栏与数据请求，由详细甘特图抽屉复用。
  -->
  <div class="lane-body">
    <!-- 时间轴刻度：与泳道区同宽，左侧让出标题列 -->
    <div class="ruler">
      <span class="ruler-pad" />
      <div class="ruler-track">
        <span>0</span>
        <span>{{ midLabel }}</span>
        <span>{{ totalLabel }}</span>
      </div>
    </div>

    <div class="grid">
      <div v-for="row in decoratedRows" :key="row.key" class="row" :class="`is-${row.kind}`">
        <div class="label">
          <span class="name" :title="row.title">{{ row.title }}</span>
          <span class="sub" :title="row.subtitle">{{ row.subtitle }}</span>
        </div>
        <div class="track">
          <!-- 总控基线：ROUTE 覆盖整轮，用细线表示「编排根一直在」，避免被误读成判路由占满全程 -->
          <span
            v-if="row.routeBar"
            class="rail"
            :style="{ left: `${row.routeBar.left}%`, width: `${row.routeBar.width}%` }"
            :title="row.routeBar.tip"
          />
          <button
            v-for="bar in row.bars"
            :key="bar.id"
            type="button"
            class="bar"
            :class="[`is-${bar.tone}`, { 'is-highlight': highlightIds.includes(bar.id) }]"
            :style="{ left: `${bar.left}%`, width: `${bar.width}%` }"
            :title="bar.tip"
            @click="emit('pick')"
          >
            <span class="bar-text">{{ bar.label }}</span>
          </button>
          <!-- CONCAT：没有模型调用，只在轨道末端给一个灰字，代表「拼接」这一步 -->
          <span v-if="row.kind === 'aggregate' && !row.spans.length" class="chip">
            {{ model.aggregate === LANE_AGGREGATE_SUMMARIZE
              ? '未找到汇总调用'
              : model.aggregate === LANE_AGGREGATE_NONE
                ? '无汇总步骤'
                : model.aggregate === LANE_AGGREGATE_PICK
                  ? '选优'
                  : model.aggregate === LANE_AGGREGATE_VOTE ? '投票' : '拼接' }}
          </span>
        </div>
        <div class="state">
          <el-tag v-if="row.running" size="small" type="primary" effect="plain">进行中</el-tag>
          <el-tag v-else-if="row.failed" size="small" type="danger" effect="plain">失败</el-tag>
          <el-tag v-else-if="row.kind === 'aggregate' && !row.spans.length" size="small" type="info" effect="plain">
            {{ model.aggregate === LANE_AGGREGATE_SUMMARIZE
              ? '降级'
              : model.aggregate === LANE_AGGREGATE_NONE
                ? '评审环'
                : model.aggregate === LANE_AGGREGATE_PICK
                  ? '选优'
                  : model.aggregate === LANE_AGGREGATE_VOTE ? '投票' : '无调用' }}
          </el-tag>
          <el-tag v-else size="small" type="success" effect="plain">完成</el-tag>
        </div>
      </div>
    </div>

    <!-- 中文事件列表：与泳道联动高亮；不改思考分块的既有展示 -->
    <div class="events">
      <div class="events-head">
        <strong>事件</strong>
        <span class="hint">悬停高亮对应泳道块；点击「完整链路」看步骤入出参</span>
      </div>
      <ul class="event-list">
        <li
          v-for="event in eventList"
          :key="event.key"
          class="event"
          :class="`is-${event.tone}`"
          @mouseenter="highlightIds = event.spanIds"
          @mouseleave="highlightIds = []"
        >
          <span class="event-time">{{ clockOf(event.atMs) }}</span>
          <span class="event-text">{{ event.text }}</span>
        </li>
      </ul>
      <p class="source">数据来自调用追踪 span 树 · 汇总：{{ model.aggregateNote }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 编排泳道正文。所属域：runtime（展示层）。
 *
 * 职责：把 {@link OrchestrationLaneModel} 画成「总控 / 子 Agent / 汇总」三行泳道
 * 加一份中文事件列表，并用 traceParallel 的甘特实现算条的位置。
 * 约束：纯展示，不发请求、不读权限；空态与错误由调用方负责。
 */
import { computed, ref } from 'vue'
import type { ObsSpan } from '@/types/obs'
import { envelopSpans, ganttAxis, spanBar } from '@/utils/traceParallel'
import {
  LANE_AGGREGATE_NONE,
  LANE_AGGREGATE_PICK,
  LANE_AGGREGATE_SUMMARIZE,
  LANE_AGGREGATE_VOTE,
  LANE_TEMPLATE_COMPETE,
  LANE_TEMPLATE_ROUTER_PARALLEL,
  laneToneOf,
  type OrchestrationLaneEvent,
  type OrchestrationLaneModel
} from '@/utils/orchestrationLane'

const props = withDefaults(
  defineProps<{
    /** 泳道模型；调用方保证 hasRouterTrace=true */
    model: OrchestrationLaneModel
    /** 原始步骤；用于包络修正父区间。缺省时退回各行 spans 的并集 */
    spans?: ObsSpan[]
    /** 事件列表；缺省时用 model.events，便于旧调用方少传一个 prop */
    events?: OrchestrationLaneEvent[]
  }>(),
  { spans: () => [], events: () => [] }
)

const emit = defineEmits<{
  /** 点击任意泳道块：调用方打开「完整链路」抽屉 */
  pick: []
}>()

/** 悬停事件时高亮的步骤 id；与泳道块联动 */
const highlightIds = ref<number[]>([])

const eventList = computed(() => (props.events.length ? props.events : props.model.events))

/** 包络基准优先用调用方给的完整步骤：工具子步也要能把父区间撑到真实结束 */
const allSpans = computed(() => {
  if (props.spans.length) {
    return props.spans
  }
  return props.model.rows.flatMap((row) => row.spans)
})

/** 与 TraceSpanFlow 同源：包络后的 latency + 统一时间轴，保证两条视图不打架 */
const envelope = computed(() => envelopSpans(allSpans.value))
const axis = computed(() => ganttAxis(envelope.value.spans))

/** 泳道块在时间轴上的位置；缺时间时退回整宽，至少能看出有这一步 */
interface LaneBar {
  id: number
  left: number
  width: number
  tone: ReturnType<typeof laneToneOf>
  label: string
  tip: string
}

/**
 * 单步骤的甘特位置与颜色。
 *
 * @param span 原始步骤
 * @param rowKind 所属泳道：总控里的 LLM 是判路由，汇总行里的 LLM 才是 SUM
 * @param template 编排模板：固定模板没有判路由，ROUTE 根标成「总控」而不是 JUDGE
 * @return 无时间基准时仍给整宽，避免这一步在泳道里凭空消失
 */
function barOf(span: ObsSpan, rowKind: string, template: string): LaneBar | null {
  const enriched = envelope.value.byId.get(span.id) ?? span
  const gantt = axis.value ? spanBar(enriched, axis.value) : null
  const tone = laneToneOf(span)
  const fixed = template !== LANE_TEMPLATE_ROUTER_PARALLEL
  const label = span.spanType === 'ROUTE'
    ? (fixed ? '总控' : 'JUDGE')
    : span.spanType === 'AGENT'
      ? (span.agentName || span.name || 'Agent')
      : span.spanType === 'LLM'
        ? (rowKind === 'aggregate'
          ? (props.model.aggregate === LANE_AGGREGATE_VOTE
            ? '投票 LLM'
            : template === LANE_TEMPLATE_COMPETE ? '选优 LLM' : 'SUM')
          : 'JUDGE LLM')
        : (span.name || span.spanType || '步骤')
  if (!gantt) {
    return {
      id: span.id,
      left: 0,
      width: 100,
      tone,
      label,
      tip: `${label} · 无起止时间`
    }
  }
  const left = Math.max(0, Math.min(100, gantt.leftPct))
  const width = Math.max(0.8, Math.min(100 - left, gantt.widthPct))
  return { id: span.id, left, width, tone, label, tip: `${label} · ${gantt.tip}` }
}

/**
 * 带 bars 的行；bar 计算放在这里，复用 traceParallel 的甘特实现。
 *
 * 总控行的 ROUTE span 在服务端被包络成「覆盖整轮」，直接画块会看起来像判路由跑满全程；
 * 因此把它降级成细基线（routeBar），JUDGE 块只取判路由 LLM 的真实区间。
 */
const decoratedRows = computed(() =>
  props.model.rows.map((row) => {
    const routeSpan = props.model.routeSpan
    if (row.kind !== 'judge' || routeSpan == null) {
      return {
        ...row,
        bars: row.spans
          .map((span) => barOf(span, row.kind, props.model.template))
          .filter((bar): bar is LaneBar => bar != null),
        routeBar: null
      }
    }
    // 去掉 ROUTE 基线本身，只把判路由 LLM 画成 JUDGE 块
    const bars = row.spans
      .filter((span) => span.id !== routeSpan.id)
      .map((span) => barOf(span, row.kind, props.model.template))
      .filter((bar): bar is LaneBar => bar != null)
    const routeBar = barOf(routeSpan, 'judge', props.model.template)
    // 判路由回退到无 LLM 时只剩基线：把基线当唯一块，总控行不留空
    if (!bars.length) {
      return { ...row, bars: routeBar ? [routeBar] : [], routeBar: null }
    }
    return { ...row, bars, routeBar }
  })
)

/** 轴总时长文字 */
const totalLabel = computed(() => formatMs(axis.value?.totalDuration ?? 0))
const midLabel = computed(() => formatMs((axis.value?.totalDuration ?? 0) / 2))

/**
 * 毫秒时长 → 可读文本。
 *
 * @param ms 毫秒
 */
function formatMs(ms: number) {
  if (!ms || ms < 1) {
    return '0ms'
  }
  if (ms < 1000) {
    return `${Math.round(ms)}ms`
  }
  return `${(ms / 1000).toFixed(1)}s`
}

/**
 * 墙钟 → HH:mm:ss；事件列表只到秒，避免宽度抖动。
 *
 * @param ms 毫秒；空返回 --
 */
function clockOf(ms: number | null) {
  if (ms == null) {
    return '--:--:--'
  }
  const date = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`
}
</script>

<style scoped>
.ruler {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0 4px;
}

.ruler-pad {
  flex: none;
  width: 156px;
}

.ruler-track {
  display: flex;
  flex: 1;
  justify-content: space-between;
  color: var(--ah-text-muted, #b0b4c0);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 30px;
}

.label {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 156px;
  min-width: 0;
}

.name {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  overflow: hidden;
  color: var(--ah-text-muted, #9ca3af);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 轨道画虚线基线，空轨道也能看出时间轴存在 */
.track {
  position: relative;
  flex: 1;
  height: 26px;
  background: repeating-linear-gradient(90deg, #f4f5f9 0 1px, transparent 1px 25%);
  border-radius: 6px;
}

/* 总控基线：ROUTE 根覆盖整轮，只做背景参考，不可点 */
.rail {
  position: absolute;
  bottom: 3px;
  height: 3px;
  border-radius: 2px;
  background: #d9d6ff;
  pointer-events: none;
}

.bar {
  position: absolute;
  top: 4px;
  display: flex;
  align-items: center;
  min-width: 8px;
  height: 18px;
  padding: 0 6px;
  overflow: hidden;
  border: none;
  border-radius: 5px;
  color: #fff;
  cursor: pointer;
  transition: box-shadow 0.14s ease;
}

.bar-text {
  overflow: hidden;
  font-size: 11px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态色与 Trace 页一致：进行中紫、成功绿、失败红、取消灰 */
.bar.is-running {
  background: #8b84ff;
  animation: lane-pulse 1.4s ease-in-out infinite;
}

.bar.is-ok {
  background: #12b76a;
}

.bar.is-error {
  background: #d92d20;
}

.bar.is-cancelled {
  background: #98a2b3;
}

.bar.is-highlight {
  box-shadow: 0 0 0 2px #fff, 0 0 0 4px #635bff;
}

@keyframes lane-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.62; }
}

/* 汇总泳道的「拼接」灰字：没有模型调用时占住轨道末端 */
.chip {
  position: absolute;
  top: 5px;
  right: 0;
  padding: 1px 8px;
  border: 1px dashed #c7cbd6;
  border-radius: 999px;
  background: #f7f8fa;
  color: #8b90a0;
  font-size: 11px;
}

.state {
  flex: none;
  width: 58px;
  text-align: right;
}

.events {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ah-border, #e8e9ef);
}

.events-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
}

.hint {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
}

.event-list {
  max-height: 132px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.event {
  display: flex;
  gap: 8px;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  cursor: default;
}

.event:hover {
  background: #f6f5ff;
}

.event-time {
  flex: none;
  color: var(--ah-text-muted, #b0b4c0);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.event-text {
  min-width: 0;
}

.event.is-ok .event-text {
  color: #0e9f6e;
}

.event.is-warn .event-text {
  color: #b54708;
}

.event.is-danger .event-text {
  color: #d92d20;
}

.source {
  margin: 6px 0 0;
  color: var(--ah-text-muted, #b0b4c0);
  font-size: 11px;
}

@media (max-width: 860px) {
  .ruler-pad,
  .label {
    width: 112px;
  }
}
</style>
