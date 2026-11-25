<template>
  <!--
    工作流执行卡片：块内只有这一张卡，内部子专家只出现在时间轴节点上，不在卡下再拆 Agent 块。
    上方过程区（时间线：普通步骤 + 循环框）；下方结果区（正文四行 + 文件行）。
    数据全部来自投影好的 card.workflow，本组件不请求接口、不另拼时间线。
  -->
  <div class="wf-card" :class="{ completed: wfCompleted(card.workflow!, card.closed) }">
    <div class="wf-process">
      <div class="wf-card-header">
        <!-- 标题不加齿轮：左侧消息流头像已是固定黑齿轮，卡内再画会叠成两个 -->
        <div class="wf-title">{{ wfCardTitle(card) }}</div>
        <div class="wf-status" :class="wfStatusClass(card.workflow!, card.closed)">
          <!-- 运行中只画一个旋转沙漏；文案侧不再带 ⏳，避免两个沙漏叠在一起 -->
          <span v-if="wfStatusClass(card.workflow!, card.closed) === 'is-running'" class="wf-spin" aria-hidden="true">⏳</span>
          {{ wfStatusText(card.workflow!, card.closed) }}
        </div>
      </div>
      <!--
        运行时限：整段上限 + 剩余；当前节点配了单节点上限时再加一行。
        剩余按后端给的绝对截止现算，并跟随卡片时钟刷新；等待输入时以进入等待那一刻为准，
        因此等待多久都不会把剩余扣掉。
      -->
      <div v-if="limitLines.length" class="wf-limit-row">
        <span
          v-for="line in limitLines"
          :key="line.kind"
          class="wf-limit-chip"
          :class="'is-' + line.kind"
        >{{ line.text }}</span>
      </div>
      <!--
        回放兜底：历史事件正文在旧版本里被截断，节点过程已不可恢复。
        如实说明一句，不编造步骤，也不让卡片一直转圈。
      -->
      <div v-if="card.workflow!.degraded" class="wf-degraded-tip">
        执行过程数据不完整（历史快照已截断），步骤详情不可用。
      </div>
      <!--
        时间线：普通节点是步骤卡，循环把体节点圈进一层框（同一循环的后续轮次追加在同一个框里）。
        结构由 buildWorkflowTimeline 从同一份投影算出，直播与刷新回放走同一条路径。
      -->
      <WorkflowTimeline
        v-if="timeline.length"
        :entries="timeline"
        :turn="turn"
        :card="card"
        :peers="card.workflow!.nodes"
        :choice-enabled="choiceEnabled"
        :workbench="workbench"
        @choose="emit('choose', $event)"
      />
    </div>
    <!--
      结果区：正文固定四行，框内滚动；被截断时底部常驻「展开全文」。
      文件在正文下面单独一行，不进正文框。
    -->
    <div
      v-if="legacyResultVisible"
      class="wf-result"
      :class="{ 'is-text': !wfFilesOf(card).length }"
    >
      <div class="wf-result-title">{{ wfResultTitle(card) }}</div>
      <p v-if="wfOutputTip(card)" class="wf-result-tip">{{ wfOutputTip(card) }}</p>
      <ResultTextBox
        v-if="wfAnswerOf(card)"
        :action="legacyAction"
        :copy-text="wfAnswerOf(card)"
        @copy="copy(wfAnswerOf(card))"
        @toggle="toggleLegacyExpand"
      >
        <div
          :ref="setLegacyBodyEl"
          class="md answer wf-output-text"
          :class="legacyBodyClass"
          :data-wf-running="legacyLive ? '1' : undefined"
          v-html="
            markdownText(
              turn.key + ':wf:' + card.key,
              displayAnswerText(wfAnswerOf(card), workbench),
              turn.status === 'streaming' && card.pending
            )
          "
        />
      </ResultTextBox>
      <!--
        工作流结果附近的引用：只读这张卡上已经投影好的工具结果。
        没有出处时不挂，避免在结果下面空摆一块。
      -->
      <WorkbenchCitations
        v-if="hasWorkflowCitations(card)"
        attached
        :items="citationsOf(card)"
      />
      <div v-if="wfFilesOf(card).length" class="wb-file-row">
        <FileCard v-for="file in wfFilesOf(card)" :key="file.fileName" :file="file" />
      </div>
    </div>
    <!--
      停等询问的可点选项：与单专家同一套芯片（字母序号 + 文案 + 推荐标记）。
      只有「等待你的回复」的卡片才会带 choice；点选走与输入框提交相同的续跑写入，
      因此这里复用同一个 choice 事件。
    -->
    <ChoiceChips
      v-if="wfLegacyChoice(card)"
      :question="card.workflow?.choice?.question"
      :options="card.workflow?.choice?.options || []"
      :disabled="!choiceEnabled"
      @choose="emit('choose', $event)"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 工作流执行卡。所属域：runtime（前端展示）。
 *
 * <p>职责：画一张投影好的工作流卡——卡片头（名称 / 状态 / 时限）、时间线（步骤卡与循环框）、
 * 停等选项、结果与文件。不请求接口、不改投影、不另起时间线。
 *
 * <p>节点级过程、循环框与四行窗口分别由 `WorkflowStepItem` / `WorkflowTimeline` 负责，
 * 本组件只保留卡片壳层与卡级结果区，避免把时间线细节又堆回这里。
 *
 * <p>数据口径：直播、收尾、回放都读同一个 {@link ParticipantCard}；这里的展开状态只影响
 * 显示范围，不会清空已流出的文字。
 */
import { computed, onBeforeUnmount, onMounted, onUpdated, ref, watch } from 'vue'
import type { ChatTurn } from '@/types/runtime'
import type { ParticipantCard, ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'
import FileCard from '@/components/runtime/FileCard.vue'
import WorkbenchCitations from '@/components/runtime/workbench/WorkbenchCitations.vue'
import ChoiceChips from '@/components/runtime/workbench/chat/ChoiceChips.vue'
import ResultTextBox from '@/components/runtime/workbench/chat/ResultTextBox.vue'
import WorkflowTimeline from '@/components/runtime/workbench/chat/WorkflowTimeline.vue'
import { displayAnswerText, markdownText } from '@/components/runtime/workbench/chat/chatText'
import { citationsOf, hasWorkflowCitations } from '@/components/runtime/workbench/chat/citationView'
import { copyAnswerText } from '@/components/runtime/workbench/chat/clipboard'
import {
  wfAnswerOf,
  wfCardTitle,
  wfCompleted,
  wfFilesOf,
  wfLegacyChoice,
  wfLegacyResultVisible,
  wfOutputTip,
  wfResultTitle,
  wfStatusClass,
  wfStatusText,
  wfLimitLines
} from '@/components/runtime/workbench/chat/workflowCardView'
import { buildWorkflowTimeline } from '@/components/runtime/workbench/chat/workflowLoopFrame'
import {
  BOX_MAX_LINES,
  answerClampOf,
  bodyExpandAction,
  isUnlimitedAnswerBox,
  tableFoldedByDefault
} from '@/components/runtime/workbench/workbenchContentBox'

const props = withDefaults(
  defineProps<{
    /** 本轮助手消息（工作流卡挂在它下面） */
    turn: ChatTurn
    /** 参与者卡片（含投影好的 workflow 快照） */
    card: ParticipantCard
    /** 所属方案是否还没全部结束：未结束时卡级正文先按四行展示 */
    hold: boolean
    /** 当前选项是否可点（最后一条助手消息且不在流式中） */
    choiceEnabled: boolean
    /** 页签是否在前台：失活时停时限秒表，与壳层同一口径 */
    pageActive?: boolean
    /** 是否工作台皮肤：调试皮肤原样展示，不剥内部键 */
    workbench?: boolean
  }>(),
  { pageActive: true, workbench: true }
)

const emit = defineEmits<{
  /** 用户点了停等选项；回传原始 option */
  choose: [option: ParticipantChoiceOption]
}>()

/** 正文单行高度（px）：与 `.md.answer` 的 14px × 1.6 一致，改字号时同步改这里 */
const LINE_HEIGHT_PX = 14 * 1.6
/** 两次实测之间的最小间隔（毫秒）：流式期降频，与对话壳层同一口径 */
const MEASURE_MIN_GAP_MS = 80

/** 卡级旧结果正文是否展开 / 是否被截断 */
const legacyExpanded = ref(false)
const legacyOverflow = ref(false)

let legacyBodyEl: HTMLElement | null = null

/** 每个窗口上次测量时的内容长度：长度没变就不可能溢出，直接跳过读布局 */
let legacyMeasuredLen = -1
let measuredAt = 0
let measureFrame = 0

/** 时限刷新时钟：只在本卡仍在运行且页签在前台时走 */
const now = ref(Date.now())
let timer = 0

/**
 * 时间线条目树：普通步骤与循环框。
 *
 * <p>只从投影好的节点列表算出结构与框状态，不另造节点、不推算进度；
 * 循环节点自己的轮次行只用来算框的「第 N/M 轮」，不再作为独立步骤出现。
 */
const timeline = computed(() =>
  buildWorkflowTimeline(props.card.workflow?.nodes, props.card.closed, props.card.workflow?.status)
)

/** 运行时限制行：读投影给的绝对截止，不重新计时 */
const limitLines = computed(() => wfLimitLines(props.card.workflow!, props.card.closed, now.value))

/** 卡级旧结果是否出现：没有节点内细节时才兜底 */
const legacyResultVisible = computed(() => wfLegacyResultVisible(props.card, props.hold))

/** 卡级正文是否按四行截断：与对话壳层同一个纯函数判定 */
function legacyLimited(): boolean {
  const text = wfAnswerOf(props.card)
  if (!text.trim()) {
    return false
  }
  // 综答随流式全文往下长；其它结果仍停在四行里滚
  if (isUnlimitedAnswerBox(props.card)) {
    return false
  }
  // 有表时只放开本轮最后一份；中间过程仍限四行
  if (answerClampOf(text) === 'table' && !tableFoldedByDefault(props.card, props.turn.status === 'streaming')) {
    return false
  }
  return true
}

/** 卡级正文是否还在流式落字：运行中跟到最新一行 */
const legacyLive = computed(() => props.turn.status === 'streaming' && props.card.pending === true)

/** 卡级正文的限高类名 */
const legacyBodyClass = computed<Record<string, boolean>>(() => {
  const text = wfAnswerOf(props.card)
  const limited = legacyLimited() && !legacyExpanded.value
  return {
    'is-clamped': limited,
    'is-table-scroll': answerClampOf(text) === 'table' && !limited
  }
})

/** 卡级正文展开按钮状态 */
const legacyAction = computed(() =>
  bodyExpandAction(legacyLimited(), legacyExpanded.value, legacyOverflow.value)
)

/** 展开 / 收起卡级正文：只改显示范围 */
function toggleLegacyExpand(): void {
  legacyExpanded.value = !legacyExpanded.value
}

/** 复制结果框里用户看见的正文 */
function copy(raw: string): void {
  void copyAnswerText(raw, true)
}

/** 可滚动元素跟到底，但只在用户仍停在底部附近时执行 */
function followScrollable(el: HTMLElement): void {
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  // 离开底部超过一行的距离，视为用户正在回看历史，不再拽回底部
  if (distance > 48) {
    return
  }
  el.scrollTop = el.scrollHeight
}

/** 卡级结果正文的 ref：量四行截断 */
function setLegacyBodyEl(el: unknown): void {
  legacyBodyEl = el instanceof HTMLElement ? el : null
}

/**
 * 量一次卡级正文是否超过四行，决定要不要给「展开全文」。
 *
 * <p>节点内正文由各自的步骤卡自己测量：这里只看卡级旧结果区。
 */
function measure(): void {
  const stamp = Date.now()
  const streaming = props.turn.status === 'streaming'
  const throttled = streaming && stamp - measuredAt < MEASURE_MIN_GAP_MS
  const limit = BOX_MAX_LINES * LINE_HEIGHT_PX
  const legacyEl = legacyBodyEl
  if (!legacyEl || !legacyLimited()) {
    if (legacyOverflow.value) {
      legacyOverflow.value = false
    }
  } else if (!legacyExpanded.value && !throttled) {
    const contentLength = wfAnswerOf(props.card).length
    if (legacyMeasuredLen !== contentLength) {
      const overflowing = legacyEl.scrollHeight - limit > 2
      legacyMeasuredLen = contentLength
      if (legacyOverflow.value !== overflowing) {
        legacyOverflow.value = overflowing
      }
    }
  }
  if (!throttled) {
    measuredAt = stamp
  }
}

/** 卡级正文在框内跟到最新一行（用户上翻后不拽回） */
function follow(): void {
  if (legacyBodyEl && legacyBodyEl.dataset.wfRunning === '1' && legacyBodyEl.classList.contains('is-clamped')) {
    followScrollable(legacyBodyEl)
  }
}

/** 下一帧统一测量 + 跟滚：一帧最多读一次布局 */
function scheduleMeasure(): void {
  if (measureFrame) {
    return
  }
  measureFrame = requestAnimationFrame(() => {
    measureFrame = 0
    measure()
    follow()
  })
}

// 每次组件更新（流式增量 / 展开 / 排版）后安排一次测量；回放挂载也补一次
onUpdated(scheduleMeasure)
onMounted(scheduleMeasure)

/**
 * 时限秒表：只要本卡仍在 RUNNING 且页签在前台就每秒推一次。
 *
 * <p>等待态由 `waitingAt` 冻结，不随这只表变化；全部收口 / 页签失活立即停表。
 */
watch(
  () => props.pageActive !== false && (props.card.workflow?.status || '').toUpperCase() === 'RUNNING',
  (live) => {
    if (live && !timer) {
      now.value = Date.now()
      timer = window.setInterval(() => {
        now.value = Date.now()
      }, 1000)
      return
    }
    if (!live && timer) {
      window.clearInterval(timer)
      timer = 0
      if (props.pageActive !== false) {
        now.value = Date.now()
      }
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (timer) {
    window.clearInterval(timer)
    timer = 0
  }
  if (measureFrame) {
    cancelAnimationFrame(measureFrame)
    measureFrame = 0
  }
})
</script>

<style scoped src="../../../../styles/chat-markdown.css"></style>
<style scoped src="../../../../styles/workbench-chat.css"></style>

<style scoped>
/*
 * 工作流执行卡片（过程 / 结果上下分栏）。
 * 只作用于工作流块：专家 / 方案块仍走气泡那套，样式互不影响。
 * 最大宽度与入口/方案/专家气泡对齐（640），不拉满整行。
 */
.wf-card {
  width: 100%;
  margin-top: 2px;
  overflow: hidden;
  border: 1px solid #d1fae5;
  border-radius: 8px;
  background: #fff;
  box-shadow: none;
}

/* 只有标题栏是淡绿，标题以下保持白底 */
.wf-process {
  background: #fff;
}

.wf-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid #d1fae5;
  background: #f0fdf4;
}

.wf-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #166534;
  font-size: 15px;
  font-weight: 600;
}

.wf-status {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 13px;
  white-space: nowrap;
  transition: all 0.3s;
}

.wf-status.is-running {
  background: #eff6ff;
  color: #1d4ed8;
}

.wf-status.is-success {
  background: #fff;
  color: #059669;
  border: 1px solid #a7f3d0;
}

.wf-status.is-failed {
  background: #fee2e2;
  color: #991b1b;
}

.wf-status.is-cancelled {
  background: #f1f5f9;
  color: #475569;
}

.wf-status.is-waiting {
  background: #fff7ed;
  color: #c2410c;
}

/* 运行时限行：紧贴标题栏下方，一行可换行；不抢标题与状态的注意力 */
.wf-limit-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 16px;
  border-bottom: 1px dashed #d1fae5;
  background: #fafffd;
}

.wf-limit-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 12px;
  background: #f1f5f9;
  color: #475569;
  font-size: 12px;
  line-height: 1.6;
  font-variant-numeric: tabular-nums;
}

.wf-limit-chip.is-node {
  background: #eef2ff;
  color: #4338ca;
}

/* 回放兜底提示：历史快照残缺，只说事实，不画重试按钮也不转圈 */
.wf-degraded-tip {
  padding: 6px 16px;
  border-bottom: 1px dashed #e2e8f0;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

/* 结果区：虚线与过程区隔开，白底。正文四行在框内滚，不另套灰框。 */
.wf-result {
  padding: 16px;
  border-top: 1px dashed #d1fae5;
  background: #fff;
}

.wf-card.completed .wf-result {
  border-top-color: #d1fae5;
}

.wf-result-title {
  margin: 0 0 10px;
  color: #1e293b;
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.wf-result-tip {
  margin: 0 0 10px;
  color: #64748b;
  font-size: 13px;
}

.wf-output-text {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.wf-spin {
  display: inline-block;
  animation: wf-spin 1s linear infinite;
}

@keyframes wf-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 工作台：工作流卡是过程，边框与标题栏回到中性灰，状态徽标仍走原有语义色 */
.chat.is-workbench .wf-card {
  border-color: var(--ah-border, #ebeef5);
}

.chat.is-workbench .wf-card-header {
  background: var(--ah-input-fill, #f5f7fa);
  border-bottom-color: var(--ah-border-soft, #f2f6fc);
}

.chat.is-workbench .wf-title {
  color: var(--ah-text, #1f2937);
}

.chat.is-workbench .wf-result {
  border-top-color: var(--ah-border-soft, #f2f6fc);
}
</style>
