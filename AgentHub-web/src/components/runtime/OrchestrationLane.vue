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
          :type="model.aggregate === LANE_AGGREGATE_SUMMARIZE || model.aggregate === LANE_AGGREGATE_VOTE
            ? (model.voteDegraded ? 'warning' : 'success')
            : 'info'"
        >
          {{ model.aggregate === LANE_AGGREGATE_NONE
            ? '终答 · 执行稿'
            : model.aggregate === LANE_AGGREGATE_PICK
              ? '选优 · PICK'
              : model.aggregate === LANE_AGGREGATE_VOTE
                ? `投票 · VOTE${model.voteDegraded ? ' · 降级' : ''}`
                : `汇总 · ${model.aggregate}` }}
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
      <p v-if="!session" class="lane__empty">运行后可查看编排泳道</p>
      <!-- 权限不足：给出中文说明，不弹全局错误 -->
      <p v-else-if="!canView" class="lane__empty">没有调用追踪权限，无法查看编排泳道</p>
      <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" class="lane__alert">
        <el-button link type="primary" @click="load(true)">重试</el-button>
      </el-alert>
      <p v-else-if="!loading && !model.hasRouterTrace" class="lane__empty">
        本轮还没有编排追踪数据{{ active ? '，执行中会自动出现' : '' }}
      </p>

      <!-- 泳道 + 事件正文与对话流内的编排卡共用，避免两套渲染各写一遍 -->
      <RouterLaneBody
        v-else-if="model.hasRouterTrace"
        :model="model"
        :spans="spans"
        @pick="drawerOpen = true"
      />
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
import {
  LANE_AGGREGATE_NONE,
  LANE_AGGREGATE_PICK,
  LANE_AGGREGATE_SUMMARIZE,
  LANE_AGGREGATE_VOTE,
  LANE_CONTEXT_SHARED_SCRATCH,
  LANE_TEMPLATE_COMPETE,
  LANE_TEMPLATE_CRITIC_LOOP,
  LANE_TEMPLATE_FIXED_PARALLEL,
  LANE_TEMPLATE_FIXED_SEQUENCE,
  LANE_TEMPLATE_ROUTER_THEN_SEQUENCE,
  buildOrchestrationLane
} from '@/utils/orchestrationLane'
import RouterLaneBody from '@/components/runtime/RouterLaneBody.vue'
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
  if (model.value.template === LANE_TEMPLATE_COMPETE) {
    return '竞争选优'
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
</style>
