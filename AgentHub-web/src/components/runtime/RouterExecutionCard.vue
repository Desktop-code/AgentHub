<template>
  <!--
    对话流内的 Router 编排调度卡。所属域：runtime（展示）。
    收起态只给摘要（调度状态 / 耗时 / 子 Agent 数），展开后是泳道 + 事件。
    数据全部来自 props（obs span 树归纳结果）；本组件只读，不发请求、不落库，
    也不把任何内容写回 agent_run_event。
  -->
  <section class="router-card" :class="`is-${status}`">
    <header class="router-card__head">
      <button
        type="button"
        class="router-card__toggle"
        :aria-expanded="expanded"
        :aria-label="expanded ? '收起编排调度' : '展开编排调度'"
        @click="expanded = !expanded"
      >
        <el-icon class="router-card__chevron" :class="{ 'is-open': expanded }"><ArrowRight /></el-icon>
        <span class="router-card__title">{{ summaryLabel }}</span>
      </button>
      <span v-if="metaText" class="router-card__meta">{{ metaText }}</span>
      <span class="router-card__spacer" />
      <el-tag v-if="model?.hasRouterTrace" size="small" effect="plain">{{ templateLabel }}</el-tag>
      <el-button v-if="traceId" link size="small" @click="drawerOpen = true">完整链路</el-button>
      <el-button link size="small" :loading="loading" @click="emit('refresh')">刷新</el-button>
    </header>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="router-card__alert"
    />

    <!-- 展收用官方过渡：高度由内容决定，不写死 max-height -->
    <ElCollapseTransition>
      <div v-show="expanded" class="router-card__body">
        <p v-if="!model?.hasRouterTrace" class="router-card__empty">{{ emptyText }}</p>
        <RouterLaneBody
          v-else
          :model="model"
          :spans="spans"
          :events="events"
          @pick="drawerOpen = true"
        />
      </div>
    </ElCollapseTransition>

    <TraceLinkDrawer
      v-model="drawerOpen"
      :trace-id="traceId || null"
      :session-id="sessionLabel"
    />
  </section>
</template>

<script setup lang="ts">
/**
 * Router 试跑「编排调度卡」。所属域：runtime（展示）。
 *
 * 与旧顶部泳道的区别：数据仍来自 obs span 树，但渲染位置进对话流，
 * 由 ChatPanel 挂在指定助手轮的 ROUTER_TRACE 块上。
 * 约束：
 * - 只读展示，不发请求；刷新通过 emit('refresh') 交回数据源；
 * - 收起摘要固定为「调度状态 · 耗时 · 子 Agent 数」，展开才是泳道与事件；
 * - 本轮结束自动收起，进行中自动展开，避免终答被一张大图压到很下面。
 */
import { computed, ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import { ElCollapseTransition } from 'element-plus'
import type { ObsSpan } from '@/types/obs'
import {
  LANE_TEMPLATE_CRITIC_LOOP,
  LANE_TEMPLATE_FIXED_PARALLEL,
  LANE_TEMPLATE_FIXED_SEQUENCE,
  LANE_TEMPLATE_ROUTER_THEN_SEQUENCE,
  type OrchestrationLaneEvent,
  type OrchestrationLaneModel
} from '@/utils/orchestrationLane'
import { envelopSpans, ganttAxis } from '@/utils/traceParallel'
import RouterLaneBody from '@/components/runtime/RouterLaneBody.vue'
import TraceLinkDrawer from '@/components/obs/TraceLinkDrawer.vue'

const props = withDefaults(
  defineProps<{
    /** 泳道数据（obs span 树归纳）；null 表示本轮追踪还没出现 */
    traceData?: OrchestrationLaneModel | null
    /** 中文事件列表；与泳道块联动高亮 */
    events?: OrchestrationLaneEvent[]
    /** 本轮状态：决定摘要文案与默认展收 */
    status?: 'running' | 'done' | 'error'
    /** 原始步骤；用于包络修正父区间，不参与泳道行 */
    spans?: ObsSpan[]
    /** 数据加载失败说明 */
    error?: string
    /** 是否正在加载 */
    loading?: boolean
    /** 最近一轮 trace 主键；用于「完整链路」 */
    traceId?: string
    /** 试跑会话主键；链路抽屉的兜底展示 */
    sessionId?: string | null
    /** 本轮开始时间；调度中用来显示已调度秒数 */
    startTime?: number
    /** 每秒刷新的当前时间戳 */
    now?: number
    /**
     * 首次渲染是否展开。
     * 缺省按 status 推断（进行中展开、结束收起）；右侧「详细甘特图」抽屉固定传 true。
     */
    defaultExpanded?: boolean
    /** 状态从 running 变 done 时是否自动收起；详细抽屉传 false，避免看到一半被收起 */
    autoCollapse?: boolean
  }>(),
  {
    traceData: null,
    events: () => [],
    status: 'done',
    spans: () => [],
    error: '',
    loading: false,
    traceId: '',
    sessionId: null,
    startTime: 0,
    now: 0,
    autoCollapse: true
  }
)

const emit = defineEmits<{
  /** 手动刷新编排数据；数据源在 TrialPanel，本组件不自己请求 */
  refresh: []
}>()

/** 展开态。对话流里进行中默认展开、结束自动收起；详细抽屉用 defaultExpanded 固定展开 */
const expanded = ref(props.defaultExpanded ?? props.status === 'running')
const drawerOpen = ref(false)

const model = computed(() => props.traceData)
const sessionLabel = computed(() => (props.sessionId ? String(props.sessionId) : null))

/** 摘要主文案：一眼看出调度阶段 */
const summaryLabel = computed(() => {
  if (props.status === 'running') {
    return '正在调度…'
  }
  if (props.status === 'error') {
    return '调度失败'
  }
  return '调度完成'
})

/** 摘要副文案：耗时 + 子 Agent 数；两者都拿不到时整段不渲染 */
const metaText = computed(() => {
  const parts: string[] = []
  if (props.status === 'running') {
    // 进行中不给总耗时（还没结束，数字会误导），改给已调度秒数
    if (props.startTime && props.now > props.startTime) {
      parts.push(`已调度 ${Math.max(0, Math.floor((props.now - props.startTime) / 1000))}s`)
    }
  } else if (totalMs.value > 0) {
    parts.push(`耗时 ${formatMs(totalMs.value)}`)
  }
  if (model.value?.hasRouterTrace) {
    parts.push(`${model.value.childCount} 个子 Agent`)
  }
  return parts.join(' · ')
})

/** 模板中文名：span attrs 是英文码，页面统一展示中文 */
const templateLabel = computed(() => {
  const template = model.value?.template
  if (template === LANE_TEMPLATE_FIXED_SEQUENCE) {
    return '固定串行'
  }
  if (template === LANE_TEMPLATE_FIXED_PARALLEL) {
    return '固定并行'
  }
  if (template === LANE_TEMPLATE_CRITIC_LOOP) {
    return '评审环'
  }
  if (template === LANE_TEMPLATE_ROUTER_THEN_SEQUENCE) {
    return '路由后串行'
  }
  return '路由并行'
})

const emptyText = computed(() =>
  props.status === 'running' ? '调度数据还在采集，稍候自动出现' : '本轮没有可展示的编排追踪数据'
)

/** 包络基准：优先用完整步骤，缺省退回各行 spans 的并集 */
const allSpans = computed(() => {
  if (props.spans.length) {
    return props.spans
  }
  return model.value?.rows.flatMap((row) => row.spans) ?? []
})

/** 整轮总耗时：与泳道刻度同源，避免摘要和展开后的轴对不上 */
const totalMs = computed(() => {
  if (!allSpans.value.length) {
    return 0
  }
  return ganttAxis(envelopSpans(allSpans.value).spans)?.totalDuration ?? 0
})

/**
 * 毫秒时长 → 可读文本。
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

// 开始调度自动展开（能看到子开始出现），结束自动收起（终答回到视野）
watch(
  () => props.status,
  (next, prev) => {
    // 详细抽屉里用户正在看链路：状态结束也不自动收起
    if (!props.autoCollapse) {
      return
    }
    if (next === 'running') {
      expanded.value = true
      return
    }
    if (prev === 'running') {
      expanded.value = false
    }
  }
)
</script>

<style scoped>
/* 对话流内嵌卡：浅灰底 + 细框，与白色助手卡区分，又不抢终答 */
.router-card {
  margin: 2px 0 6px;
  border: 1px solid #e6e8ef;
  border-radius: 10px;
  background: #fafbff;
}

.router-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
}

.router-card__toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--ah-text, #1f2430);
  cursor: pointer;
}

.router-card__chevron {
  color: var(--ah-text-muted, #9ca3af);
  transition: transform 0.16s ease;
}

.router-card__chevron.is-open {
  transform: rotate(90deg);
}

.router-card__title {
  font-size: 13px;
  font-weight: 650;
}

.router-card__meta {
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

.router-card__spacer {
  flex: 1;
}

.router-card__alert {
  margin: 0 10px 8px;
}

.router-card__body {
  padding: 0 10px 10px;
}

.router-card__empty {
  margin: 4px 0 6px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

/* 进行中左侧加一道主色条，和普通折叠块区分开 */
.router-card.is-running {
  border-color: #d9d6ff;
  box-shadow: inset 3px 0 0 var(--ah-primary, #635bff);
}

.router-card.is-error {
  border-color: #f3c7c3;
}
</style>
