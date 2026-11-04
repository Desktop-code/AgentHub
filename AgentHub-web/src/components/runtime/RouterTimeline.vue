<template>
  <!--
    紧凑型 Router 试跑时间线。所属域：runtime（展示层）。
    数据仍来自 obs span 树归纳出的泳道模型，只读；渲染顺序固定为
    Router 分析 → 子 Agent（可并行分组 / 超量折叠）→ 最终汇总 → 详细甘特图入口。
  -->
  <section class="rt" aria-label="多智能体编排时间线">
    <!-- 数据加载失败：一行紧凑提示 + 重试，不塞整块 el-alert 把聊天流顶下去 -->
    <div v-if="error" class="rt-state is-error">
      <span class="rt-state__text">{{ error }}</span>
      <button type="button" class="rt-link" @click.stop="emit('refresh')">重试</button>
    </div>
    <!-- 没有追踪：进行中说明还在采集；结束后如实说没有，不伪造空卡 -->
    <p v-else-if="!hasTrace" class="rt-state">
      {{ status === 'running' || loading ? '编排追踪采集中…' : '本轮没有可展示的编排追踪' }}
    </p>

    <template v-else>
      <!-- Router 分析卡：紫色边框；状态原地从运行中变成功 -->
      <RouterTimelineCard
        v-if="routerCard"
        :title="routerCard.title"
        :kind="routerCard.kind"
        :status="routerCard.status"
        :summary="routerCard.summary"
      />

      <!-- 子 Agent：超过 2 个且模板为并行时，外层包一个浅色分组框 -->
      <section v-if="childCards.length" class="rt-children" :class="{ 'is-parallel': parallel }">
        <div v-if="parallel" class="rt-parallel-head">
          <span class="rt-parallel-head__icon" aria-hidden="true">⚡</span>
          <span>{{ parallelLabel }}</span>
        </div>

        <!-- 超过 3 个：旧卡默认隐藏；按钮在顶部，展开时高度平滑过渡 -->
        <div v-if="hiddenCount" class="rt-fold">
          <button
            type="button"
            class="rt-fold__btn"
            :aria-expanded="childrenExpanded"
            @click.stop="toggleChildren"
          >
            <el-icon class="rt-fold__icon" :class="{ 'is-open': childrenExpanded }">
              <ArrowDown />
            </el-icon>
            <span>{{ foldLabel }}</span>
          </button>
        </div>
        <ElCollapseTransition>
          <div v-show="childrenExpanded" class="rt-hidden">
            <RouterTimelineCard
              v-for="card in hiddenChildren"
              :key="`hidden-${card.key}`"
              :title="card.title"
              :kind="card.kind"
              :status="card.status"
              :summary="card.summary"
            />
          </div>
        </ElCollapseTransition>

        <!-- 默认只展示最新 3 个；展开后旧卡在上方补齐，顺序仍是执行顺序 -->
        <RouterTimelineCard
          v-for="card in visibleChildren"
          :key="card.key"
          :title="card.title"
          :kind="card.kind"
          :status="card.status"
          :summary="card.summary"
        />
      </section>

      <!-- 最终汇总卡：与 Router 分析同色系 -->
      <RouterTimelineCard
        v-if="aggregateCard"
        :title="aggregateCard.title"
        :kind="aggregateCard.kind"
        :status="aggregateCard.status"
        :summary="aggregateCard.summary"
      />

      <!-- 次要入口：未来右侧「甘特图 + 深色日志」面板由父组件接管 -->
      <div class="rt-foot">
        <button type="button" class="rt-gantt" @click.stop="emit('open-gantt')">
          <el-icon><DataLine /></el-icon>
          <span>查看详细甘特图</span>
        </button>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
/**
 * 紧凑型 Router 试跑时间线。所属域：runtime（展示层）。
 *
 * <p>职责：把 {@link OrchestrationLaneModel} 的泳道行归纳成「一张卡一个节点」的
 * 紧凑时间线。子 Agent 的「派发」与「返回」共用同一张卡，靠 props.status 原地流转，
 * 不再追加第二张卡；并行模板下把子卡包进浅色分组框；子卡超过 3 个时折叠旧卡。
 *
 * <p>关键约束：
 * - 只读展示，不发请求；刷新与甘特图入口都通过 emit 交回父组件；
 * - 不渲染时间戳；卡片高度由 12px padding + 13px 标题 + 3 行摘要封顶；
 * - 折叠状态是纯前端态，不写库、不影响 RouterTrialExecutor 的执行语义。
 */
import { computed, ref, watch } from 'vue'
import { ElCollapseTransition } from 'element-plus'
import { ArrowDown, DataLine } from '@element-plus/icons-vue'
import type {
  OrchestrationLaneModel,
  OrchestrationLaneRow
} from '@/utils/orchestrationLane'
import {
  LANE_TEMPLATE_FIXED_PARALLEL,
  LANE_TEMPLATE_ROUTER_PARALLEL
} from '@/utils/orchestrationLane'
import RouterTimelineCard from '@/components/runtime/RouterTimelineCard.vue'
import type { TimelineCardModel, TimelineCardStatus } from '@/components/runtime/routerTimeline'

/** 子任务默认可见条数；超过后折叠更早的卡片，防止长链路刷屏 */
const VISIBLE_LIMIT = 3

const props = withDefaults(
  defineProps<{
    /** 泳道数据（obs span 树归纳）；null 表示本轮追踪还没出现 */
    traceData?: OrchestrationLaneModel | null
    /** 本轮状态：只用于空态文案，卡片状态由各行 span 自己的状态决定 */
    status?: 'running' | 'done' | 'error'
    /** 数据加载失败说明 */
    error?: string
    /** 是否正在加载 */
    loading?: boolean
    /** trace 主键；变化时重置折叠，避免上一轮的「已展开」带到新一轮 */
    traceId?: string
  }>(),
  {
    traceData: null,
    status: 'done',
    error: '',
    loading: false,
    traceId: ''
  }
)

const emit = defineEmits<{
  /** 打开右侧「详细甘特图」面板；具体抽屉由父组件负责 */
  'open-gantt': []
  /** 手动重试编排数据；数据源在父组件，本组件不自己请求 */
  refresh: []
}>()

/** 折叠态：默认收起，只露最新 3 张；切 trace 时重置 */
const childrenExpanded = ref(false)

/** 仅当模型存在且找到 ROUTE 根时才渲染卡片流 */
const hasTrace = computed(() => props.traceData?.hasRouterTrace === true)

/** 泳道行：顺序固定为总控 → 子 Agent → 汇总 */
const rows = computed<OrchestrationLaneRow[]>(() => props.traceData?.rows ?? [])

/** Router / 总控分析行 */
const judgeRow = computed(() => rows.value.find((row) => row.kind === 'judge'))

/** 最终汇总行；CONCAT 时 spans 可能为空，仍有汇总文案 */
const aggregateRow = computed(() => rows.value.find((row) => row.kind === 'aggregate'))

/** 子 Agent 行；顺序即派发顺序，也是时间线阅读顺序 */
const childRows = computed(() => rows.value.filter((row) => row.kind === 'child'))

/**
 * 行状态归一。
 *
 * @param row 一条泳道
 * @return 卡片状态；全为 CANCELLED 时给「已取消」，其余结束态按成功处理
 */
function statusOf(row: OrchestrationLaneRow): TimelineCardStatus {
  if (row.failed) {
    return 'error'
  }
  if (row.running) {
    return 'running'
  }
  if (row.spans.length && row.spans.every((span) => span.status === 'CANCELLED')) {
    return 'cancelled'
  }
  return 'success'
}

/**
 * 从步骤里取一段可读摘要。
 *
 * <p>优先取最后一条 outputText（子 Agent 的返回文本 / 汇总终答），
 * 失败时优先 errorSummary；只有 brief 时也兜底展示，避免卡片只剩标题。
 *
 * @param row 泳道行
 * @return 压缩后的摘要；没有可展示文本时为空串
 */
function summaryOf(row: OrchestrationLaneRow): string {
  if (row.failed) {
    for (let i = row.spans.length - 1; i >= 0; i -= 1) {
      const error = (row.spans[i].errorSummary || '').trim()
      if (error) {
        return compact(error)
      }
    }
  }
  for (let i = row.spans.length - 1; i >= 0; i -= 1) {
    const span = row.spans[i]
    const text = (span.outputText || span.brief || span.errorSummary || '').trim()
    if (text) {
      return compact(text)
    }
  }
  return ''
}

/**
 * 摘要压缩：去掉首尾空白并把连续空行压成一行空行，避免 3 行额度被空白吃掉。
 *
 * @param text 原始文本
 * @return 可交给 CSS line-clamp 的文本
 */
function compact(text: string): string {
  return text.replace(/\r\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim()
}

/** Router 分析卡：标题带「分析」，摘要缺失时退回命中 route_key */
const routerCard = computed<TimelineCardModel | null>(() => {
  const row = judgeRow.value
  if (!row) {
    return null
  }
  const keys = props.traceData?.routeKeys ?? []
  return {
    key: row.key,
    kind: 'router',
    title: row.title ? `${row.title} · 分析` : 'Router 分析',
    status: statusOf(row),
    summary: summaryOf(row) || (keys.length ? `命中：${keys.join('、')}` : row.subtitle)
  }
})

/** 子 Agent 卡：每个子只有一张，派发与返回共用同一 key */
const childCards = computed<TimelineCardModel[]>(() =>
  childRows.value.map((row) => ({
    key: row.key,
    kind: 'child',
    title: row.title || '子 Agent',
    status: statusOf(row),
    summary: summaryOf(row)
  }))
)

/** 最终汇总卡：CONCAT 无模型调用时用固定说明兜底 */
const aggregateCard = computed<TimelineCardModel | null>(() => {
  const row = aggregateRow.value
  if (!row) {
    return null
  }
  return {
    key: row.key,
    kind: 'aggregate',
    title: row.title || '汇总',
    status: statusOf(row),
    summary: summaryOf(row) || props.traceData?.aggregateNote || ''
  }
})

/** 是否并行模板且子任务 ≥ 2：只有这两种模板才包并行分组框 */
const parallel = computed(() => {
  if (childCards.value.length < 2) {
    return false
  }
  const template = props.traceData?.template
  return template === LANE_TEMPLATE_ROUTER_PARALLEL || template === LANE_TEMPLATE_FIXED_PARALLEL
})

/** 并行分组标题：任一子仍在跑就是「执行中」，全结束改「执行完成」 */
const parallelLabel = computed(() => {
  const running = childCards.value.some((card) => card.status === 'running')
  return `${running ? '并行执行中' : '并行执行完成'} (${childCards.value.length} 个任务)`
})

/** 被折叠的更早子卡；默认只保留最新 {@link VISIBLE_LIMIT} 个 */
const hiddenChildren = computed(() => childCards.value.slice(0, Math.max(0, childCards.value.length - VISIBLE_LIMIT)))

/** 默认可见子卡；最后 3 个保持时间线原顺序 */
const visibleChildren = computed(() => childCards.value.slice(-VISIBLE_LIMIT))

/** 被折叠数量；为 0 时不渲染展开按钮 */
const hiddenCount = computed(() => hiddenChildren.value.length)

/** 折叠区里是否有仍在运行的卡：用于把「已完成」文案改成更真实的说明 */
const hiddenRunning = computed(() => hiddenChildren.value.some((card) => card.status === 'running'))

/** 折叠按钮文案：收起 / 展开，并如实标注是否含运行中任务 */
const foldLabel = computed(() => {
  if (childrenExpanded.value) {
    return `收起上方 ${hiddenCount.value} 个任务`
  }
  return hiddenRunning.value
    ? `展开上方 ${hiddenCount.value} 个任务（含运行中）`
    : `展开上方 ${hiddenCount.value} 个已完成任务`
})

/** 展开 / 收起旧卡；只改前端态，不触发数据请求 */
function toggleChildren() {
  childrenExpanded.value = !childrenExpanded.value
}

// 新一轮 trace：折叠状态回到默认，避免上一轮展开的旧卡挡住新一轮内容
watch(
  () => props.traceId,
  () => {
    childrenExpanded.value = false
  }
)
</script>

<style scoped>
/* 时间线整体：8px 间距即可，卡片自身留白已经压缩过 */
.rt {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 2px 0 6px;
}

/* 空态 / 错误态：一行小字，不制造第二块大卡 */
.rt-state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.5;
}

.rt-state.is-error {
  color: var(--el-color-danger, #d92d20);
}

.rt-state__text {
  min-width: 0;
}

.rt-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  cursor: pointer;
}

/* 子任务区：串行时就是普通纵向列表，并行时加浅紫底框 */
.rt-children {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rt-children.is-parallel {
  padding: 8px;
  border: 1px dashed #ddd9ff;
  border-radius: 12px;
  background: #faf9ff;
}

/* 并行分组头：只占一行，和卡片标题同字号 */
.rt-parallel-head {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #5b54d6;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.4;
}

.rt-parallel-head__icon {
  font-size: 12px;
}

/* 折叠按钮行：居中细胶囊，点击区域明确但不抢视觉 */
.rt-fold {
  display: flex;
  justify-content: center;
}

.rt-fold__btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px dashed #cfcaff;
  border-radius: 999px;
  background: #fff;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  transition: background-color 0.16s ease, border-color 0.16s ease;
}

.rt-fold__btn:hover {
  border-color: #b9b2ff;
  background: #f2f0ff;
}

/* 箭头只在展开/收起时旋转，高度变化交给 ElCollapseTransition */
.rt-fold__icon {
  transition: transform 0.18s ease;
}

.rt-fold__icon.is-open {
  transform: rotate(180deg);
}

/* 被折叠的旧卡容器：纵向排列，展开时由外层 transition 平滑撑高 */
.rt-hidden {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 底部入口右对齐，作为次要操作，不与卡片状态抢注意力 */
.rt-foot {
  display: flex;
  justify-content: flex-end;
  padding-top: 2px;
}

.rt-gantt {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.rt-gantt:hover {
  background: #f2f0ff;
  color: var(--ah-primary, #635bff);
}
</style>
