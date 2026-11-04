<template>
  <!--
    编排泳道（只读）。所属域：runtime（展示层）。
    挂在 Router 试跑面板：总控 JUDGE / 各子 Agent / 汇总三行泳道 + 中文事件列表。
    数据来自调用追踪 span 树（与 /ops/traces 同一次 run），不新造观测引擎。
  -->
  <section class="lane">
    <header class="lane__head">
      <button type="button" class="lane__toggle" :aria-expanded="expanded" @click="expanded = !expanded">
        <el-icon class="lane__chevron" :class="{ 'is-open': expanded }"><ArrowRight /></el-icon>
        <span class="lane__title">编排泳道</span>
      </button>
      <template v-if="model.hasRouterTrace">
        <el-tag size="small" effect="plain">模板 · {{ templateLabel }}</el-tag>
        <el-tag
          size="small"
          effect="plain"
          :type="model.aggregate === LANE_AGGREGATE_SUMMARIZE ? 'warning' : 'info'"
        >
          {{ model.aggregate === LANE_AGGREGATE_NONE ? '终答 · 执行稿' : `汇总 · ${model.aggregate}` }}
        </el-tag>
        <el-tag size="small" effect="plain" type="info">{{ model.childCount }} 个子</el-tag>
        <!-- 共享草稿区：只标徽标与键数 / 冲突数；完整草稿看板属 P2，这里不展开 -->
        <el-tag
          v-if="model.contextPolicy === LANE_CONTEXT_SHARED_SCRATCH"
          size="small"
          effect="plain"
          type="warning"
          :title="scratchTip"
        >
          {{ scratchLabel }}
        </el-tag>
      </template>
      <span v-if="traceTimeLabel" class="lane__stamp">{{ traceTimeLabel }}</span>
      <span class="lane__spacer" />
      <el-button link size="small" :disabled="!session || loading" @click="load(true)">刷新</el-button>
      <el-button v-if="traceId" link size="small" @click="drawerOpen = true">完整链路</el-button>
    </header>

    <div v-if="expanded" class="lane__body">
      <!-- 无会话：还没试跑过（临时草稿也走这里） -->
      <p v-if="!session" class="lane__empty">试跑后可查看编排泳道</p>
      <!-- 权限不足：给出中文说明，不弹全局错误 -->
      <p v-else-if="!canView" class="lane__empty">没有调用追踪权限，无法查看编排泳道</p>
      <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" class="lane__alert">
        <el-button link type="primary" @click="load(true)">重试</el-button>
      </el-alert>
      <p v-else-if="!loading && !model.hasRouterTrace" class="lane__empty">
        本轮还没有编排追踪数据{{ active ? '，执行中会自动出现' : '' }}
      </p>

      <template v-else-if="model.hasRouterTrace">
        <!-- 时间轴刻度：与泳道区同宽，左侧让出标题列 -->
        <div class="lane__ruler">
          <span class="lane__ruler-pad" />
          <div class="lane__ruler-track">
            <span>0</span>
            <span>{{ midLabel }}</span>
            <span>{{ totalLabel }}</span>
          </div>
        </div>

        <div class="lane__grid">
          <div v-for="row in decoratedRows" :key="row.key" class="lane__row" :class="`is-${row.kind}`">
            <div class="lane__label">
              <span class="lane__name" :title="row.title">{{ row.title }}</span>
              <span class="lane__sub" :title="row.subtitle">{{ row.subtitle }}</span>
            </div>
            <div class="lane__track">
              <!-- 总控基线：ROUTE 覆盖整轮，用细线表示「编排根一直在」，避免被误读成判路由占满全程 -->
              <span
                v-if="row.routeBar"
                class="lane__rail"
                :style="{ left: `${row.routeBar.left}%`, width: `${row.routeBar.width}%` }"
                :title="row.routeBar.tip"
              />
              <button
                v-for="bar in row.bars"
                :key="bar.id"
                type="button"
                class="lane__bar"
                :class="[
                  `is-${bar.tone}`,
                  { 'is-highlight': highlightIds.includes(bar.id) }
                ]"
                :style="{ left: `${bar.left}%`, width: `${bar.width}%` }"
                :title="bar.tip"
                @click="drawerOpen = true"
              >
                <span class="lane__bar-text">{{ bar.label }}</span>
              </button>
              <!-- CONCAT：没有模型调用，只在轨道末端给一个灰字，代表「拼接」这一步 -->
              <span v-if="row.kind === 'aggregate' && !row.spans.length" class="lane__chip">
                {{ model.aggregate === LANE_AGGREGATE_SUMMARIZE
                  ? '未找到汇总调用'
                  : model.aggregate === LANE_AGGREGATE_NONE ? '无汇总步骤' : '拼接' }}
              </span>
            </div>
            <div class="lane__state">
              <el-tag v-if="row.running" size="small" type="primary" effect="plain">进行中</el-tag>
              <el-tag v-else-if="row.failed" size="small" type="danger" effect="plain">失败</el-tag>
              <el-tag v-else-if="row.kind === 'aggregate' && !row.spans.length" size="small" type="info" effect="plain">
                {{ model.aggregate === LANE_AGGREGATE_SUMMARIZE
                  ? '降级'
                  : model.aggregate === LANE_AGGREGATE_NONE ? '评审环' : '无调用' }}
              </el-tag>
              <el-tag v-else size="small" type="success" effect="plain">完成</el-tag>
            </div>
          </div>
        </div>

        <!-- 中文事件列表：与泳道联动高亮；不改思考分块的既有展示 -->
        <div class="lane__events">
          <div class="lane__events-head">
            <strong>事件</strong>
            <span class="lane__hint">悬停高亮对应泳道块；点击「完整链路」看步骤入出参</span>
          </div>
          <ul class="lane__event-list">
            <li
              v-for="event in model.events"
              :key="event.key"
              class="lane__event"
              :class="`is-${event.tone}`"
              @mouseenter="highlightIds = event.spanIds"
              @mouseleave="highlightIds = []"
            >
              <span class="lane__event-time">{{ clockOf(event.atMs) }}</span>
              <span class="lane__event-text">{{ event.text }}</span>
            </li>
          </ul>
          <p class="lane__source">
            数据来自调用追踪 span 树 · 汇总：{{ model.aggregateNote }}
          </p>
        </div>
      </template>
    </div>

    <TraceLinkDrawer v-model="drawerOpen" :trace-id="traceId || null" :session-id="sessionLabel" />
  </section>
</template>

<script setup lang="ts">
/**
 * Router 试跑「编排泳道」。所属域：runtime（展示）。
 *
 * 数据主路径：obs span 树（按会话取最近一轮 trace → 取该 trace 全部 spans），
 * 轮询 2s 增量更新；trial events 不参与泳道计时（它没有起止时间）。
 * 只读：不写库、不改 RouterTrialExecutor 的隔离/并行/汇总语义。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import { listObsSpans, pageObsTraces } from '@/api/obs'
import { useUserStore } from '@/stores/user'
import type { ObsSpan, ObsTrace } from '@/types/obs'
import { envelopSpans, ganttAxis, spanBar } from '@/utils/traceParallel'
import {
  LANE_AGGREGATE_NONE,
  LANE_AGGREGATE_SUMMARIZE,
  LANE_CONTEXT_SHARED_SCRATCH,
  LANE_TEMPLATE_CRITIC_LOOP,
  LANE_TEMPLATE_FIXED_PARALLEL,
  LANE_TEMPLATE_FIXED_SEQUENCE,
  LANE_TEMPLATE_ROUTER_PARALLEL,
  LANE_TEMPLATE_ROUTER_THEN_SEQUENCE,
  buildOrchestrationLane,
  laneToneOf
} from '@/utils/orchestrationLane'
import TraceLinkDrawer from '@/components/obs/TraceLinkDrawer.vue'

/** 轮询间隔：与试跑续订同一量级，够看出「子开始即出现条」 */
const POLL_MS = 2000
/** 本轮结束后补一次：trace 收尾略晚于 SSE done */
const SETTLE_MS = 900

const props = withDefaults(
  defineProps<{
    /** 试跑会话；临时草稿为负值 / 空，按「还没试跑」处理 */
    sessionId?: number | null
    /** 本轮是否还在跑（sending / resuming），决定是否轮询 */
    active?: boolean
    /** 首次进入时是否展开 */
    defaultExpanded?: boolean
    /** 当前待批准的子计划 route_key；用于在该子泳道标「待批准」 */
    pausedRouteKey?: string
    /** 当前待批准的子 Agent id；评审环无 route_key，用 id 命中 */
    pausedAgentId?: number | null
  }>(),
  { sessionId: null, active: false, defaultExpanded: true, pausedRouteKey: '', pausedAgentId: null }
)

const user = useUserStore()
/** 两个权限都查同一个 trace 的步骤；缺一个就整块提示，避免半截数据 */
const canView = computed(() => user.hasPerm('obs:trace:list') && user.hasPerm('obs:span:list'))

const expanded = ref(props.defaultExpanded)
const loading = ref(false)
const error = ref('')
const traceId = ref('')
const trace = ref<ObsTrace | null>(null)
const spans = ref<ObsSpan[]>([])
const drawerOpen = ref(false)
const highlightIds = ref<number[]>([])

/** 当前会话 id；负数是前端临时草稿 */
const session = computed(() => (props.sessionId != null && props.sessionId > 0 ? props.sessionId : null))
const sessionLabel = computed(() => (session.value != null ? String(session.value) : null))

const model = computed(() =>
  buildOrchestrationLane(spans.value, props.pausedRouteKey, props.pausedAgentId ?? null)
)

/** 模板中文名：span attrs 是英文码，页面统一展示中文 */
const templateLabel = computed(() => {
  if (model.value.template === LANE_TEMPLATE_FIXED_SEQUENCE) {
    return '固定串行'
  }
  if (model.value.template === LANE_TEMPLATE_FIXED_PARALLEL) {
    return '固定并行'
  }
  if (model.value.template === LANE_TEMPLATE_CRITIC_LOOP) {
    return '评审环'
  }
  if (model.value.template === LANE_TEMPLATE_ROUTER_THEN_SEQUENCE) {
    return '路由后串行'
  }
  return '路由并行'
})

/** 共享草稿徽标文案：有键数就带上，没有就只写「共享草稿」 */
const scratchLabel = computed(() => {
  const keys = model.value.scratchKeyCount
  return keys == null ? '共享草稿' : `共享草稿 · ${keys} 键`
})

/** 徽标悬浮说明：冲突次数有则显示，无则省略，不做完整草稿看板 */
const scratchTip = computed(() => {
  const conflicts = model.value.scratchConflictCount
  const base = '本轮子 Agent 经 ma_scratch_get / ma_scratch_put 读写键控草稿；同键第二次写入会被拒绝'
  return conflicts == null || conflicts === 0 ? base : `${base}（已拒绝 ${conflicts} 次同键写入）`
})

/** 与 TraceSpanFlow 同源：包络后的 latency + 统一时间轴，保证两条视图不打架 */
const envelope = computed(() => envelopSpans(spans.value))
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
        ? (rowKind === 'aggregate' ? 'SUM' : 'JUDGE LLM')
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
 * 带 bars 的行；bar 计算放在组件侧，复用 traceParallel 的甘特实现。
 *
 * 总控行的 ROUTE span 在服务端被包络成「覆盖整轮」，直接画块会看起来像判路由跑满全程；
 * 因此把它降级成细基线（routeBar），JUDGE 块只取判路由 LLM 的真实区间。
 */
const decoratedRows = computed(() =>
  model.value.rows.map((row) => {
    const routeSpan = model.value.routeSpan
    if (row.kind !== 'judge' || routeSpan == null) {
      return {
        ...row,
        bars: row.spans
          .map((span) => barOf(span, row.kind, model.value.template))
          .filter((bar): bar is LaneBar => bar != null),
        routeBar: null
      }
    }
    // 去掉 ROUTE 基线本身，只把判路由 LLM 画成 JUDGE 块
    const bars = row.spans
      .filter((span) => span.id !== routeSpan.id)
      .map((span) => barOf(span, row.kind, model.value.template))
      .filter((bar): bar is LaneBar => bar != null)
    const routeBar = barOf(routeSpan, 'judge', model.value.template)
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
/** 顶栏时间戳：最近一轮 trace 的开始时刻 */
const traceTimeLabel = computed(() => {
  const raw = trace.value?.startTime
  if (!raw) {
    return ''
  }
  const ms = Date.parse(raw.includes('T') ? raw : raw.replace(' ', 'T'))
  return Number.isNaN(ms) ? '' : `最近一轮 ${clockOf(ms)}`
})

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

/** 请求令牌：切会话 / 关面板后丢弃迟到的响应 */
let token = 0
let timer: number | undefined
let settleTimer: number | undefined

/**
 * 拉最近一轮 trace 与其全部步骤。
 *
 * @param showLoading true 时显示 loading（首次 / 手动刷新）
 */
async function load(showLoading = false) {
  const id = session.value
  if (id == null) {
    reset()
    return
  }
  if (!canView.value) {
    reset()
    return
  }
  const mine = ++token
  if (showLoading) {
    loading.value = true
  }
  try {
    // 中栏接口按开始时间降序；第一条就是本轮（进行中的 trace 也会立刻出现）
    const { data } = await pageObsTraces(id, { current: 1, size: 20 })
    if (mine !== token) {
      return
    }
    const rows = data.data?.records || []
    const latest = rows[0] ?? null
    trace.value = latest
    error.value = ''
    if (!latest) {
      traceId.value = ''
      spans.value = []
      return
    }
    if (latest.traceId !== traceId.value) {
      traceId.value = latest.traceId
      // 换轮次先清空，避免新 trace 只回来一半时把上一轮的行混进来
      spans.value = []
    }
    const spanRes = await listObsSpans(latest.traceId)
    if (mine !== token) {
      return
    }
    spans.value = spanRes.data.data || []
  } catch {
    if (mine === token) {
      error.value = '编排泳道加载失败'
    }
  } finally {
    if (mine === token) {
      loading.value = false
    }
  }
}

/** 清空视图（切到临时草稿 / 无权限） */
function reset() {
  token += 1
  loading.value = false
  error.value = ''
  traceId.value = ''
  trace.value = null
  spans.value = []
  highlightIds.value = []
}

/** 停轮询 */
function stopTimer() {
  if (timer != null) {
    window.clearInterval(timer)
    timer = undefined
  }
  if (settleTimer != null) {
    window.clearTimeout(settleTimer)
    settleTimer = undefined
  }
}

/** 本轮在跑时开轮询：子一开跑就能看到条，不必等整轮结束 */
function startPolling() {
  if (!props.active || timer != null) {
    return
  }
  timer = window.setInterval(() => void load(), POLL_MS)
}

/** 本轮在跑就轮询；停下来的那一刻补一拍，等 trace 收尾落库 */
watch(
  () => props.active,
  (active) => {
    stopTimer()
    if (active) {
      void load()
      startPolling()
      return
    }
    void load()
    settleTimer = window.setTimeout(() => void load(), SETTLE_MS)
  }
)

// 切会话：展开状态跟随是否有会话；换会话立即重拉；打开时本轮若在跑也接着轮询
watch(
  () => props.sessionId,
  (id) => {
    if (id != null && id > 0 && props.defaultExpanded) {
      expanded.value = true
    }
    void load(true)
    startPolling()
  },
  { immediate: true }
)

// 权限在登录后异步装载：装载完再补一次，避免首次渲染停在「无权限」
watch(
  () => canView.value,
  (ok) => {
    if (ok) {
      void load(true)
    }
  }
)

onBeforeUnmount(stopTimer)
</script>

<style scoped>
.lane {
  display: flex;
  flex: none;
  flex-direction: column;
  max-height: 48vh;
  margin-bottom: 10px;
  background: var(--ah-surface, #fff);
  border: 1px solid var(--ah-border, #e8e9ef);
  border-radius: 10px;
}

.lane__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
}

.lane__toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--ah-text, #1f2430);
  cursor: pointer;
}

.lane__chevron {
  color: var(--ah-text-muted, #9ca3af);
  transition: transform 0.16s ease;
}

.lane__chevron.is-open {
  transform: rotate(90deg);
}

.lane__title {
  font-size: 13px;
  font-weight: 650;
}

.lane__stamp {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.lane__spacer {
  flex: 1;
}

.lane__body {
  padding: 0 12px 10px;
  overflow: auto;
}

.lane__empty {
  margin: 8px 0 12px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

.lane__alert {
  margin: 4px 0 10px;
}

.lane__ruler {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0 4px;
}

.lane__ruler-pad {
  flex: none;
  width: 156px;
}

.lane__ruler-track {
  display: flex;
  flex: 1;
  justify-content: space-between;
  color: var(--ah-text-muted, #b0b4c0);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.lane__grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lane__row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 30px;
}

.lane__label {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 156px;
  min-width: 0;
}

.lane__name {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lane__sub {
  overflow: hidden;
  color: var(--ah-text-muted, #9ca3af);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 轨道画虚线基线，空轨道也能看出时间轴存在 */
.lane__track {
  position: relative;
  flex: 1;
  height: 26px;
  background: repeating-linear-gradient(90deg, #f4f5f9 0 1px, transparent 1px 25%);
  border-radius: 6px;
}

/* 总控基线：ROUTE 根覆盖整轮，只做背景参考，不可点 */
.lane__rail {
  position: absolute;
  bottom: 3px;
  height: 3px;
  border-radius: 2px;
  background: #d9d6ff;
  pointer-events: none;
}

.lane__bar {
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

.lane__bar-text {
  overflow: hidden;
  font-size: 11px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态色与 Trace 页一致：进行中紫、成功绿、失败红、取消灰 */
.lane__bar.is-running {
  background: #8b84ff;
  animation: lane-pulse 1.4s ease-in-out infinite;
}

.lane__bar.is-ok {
  background: #12b76a;
}

.lane__bar.is-error {
  background: #d92d20;
}

.lane__bar.is-cancelled {
  background: #98a2b3;
}

.lane__bar.is-highlight {
  box-shadow: 0 0 0 2px #fff, 0 0 0 4px #635bff;
}

@keyframes lane-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.62; }
}

/* 汇总泳道的「拼接」灰字：没有模型调用时占住轨道末端 */
.lane__chip {
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

.lane__state {
  flex: none;
  width: 58px;
  text-align: right;
}

.lane__events {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ah-border, #e8e9ef);
}

.lane__events-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
}

.lane__hint {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
}

.lane__event-list {
  max-height: 132px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.lane__event {
  display: flex;
  gap: 8px;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  cursor: default;
}

.lane__event:hover {
  background: #f6f5ff;
}

.lane__event-time {
  flex: none;
  color: var(--ah-text-muted, #b0b4c0);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.lane__event-text {
  min-width: 0;
}

.lane__event.is-ok .lane__event-text {
  color: #0e9f6e;
}

.lane__event.is-warn .lane__event-text {
  color: #b54708;
}

.lane__event.is-danger .lane__event-text {
  color: #d92d20;
}

.lane__source {
  margin: 6px 0 0;
  color: var(--ah-text-muted, #b0b4c0);
  font-size: 11px;
}

@media (max-width: 860px) {
  .lane__ruler-pad,
  .lane__label {
    width: 112px;
  }
}
</style>
