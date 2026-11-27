<template>
  <!--
    工作流时间线上的一个真实步骤：名称 / 状态 / 询问表单 / 节点内思考 / 工具胶囊 / 四行正文 / 文件。
    数据全部来自投影好的节点视图，本组件不请求接口、不另拼时间线。
  -->
  <div class="wf-step-item" :class="'is-' + state">
    <div class="wf-step-icon" :class="'is-' + state">
      <span v-if="state === 'active'" class="wf-spin" aria-hidden="true">⏳</span>
      <template v-else-if="state === 'done'">✓</template>
      <template v-else-if="state === 'failed'">✕</template>
      <template v-else-if="state === 'waiting'">⏸</template>
      <template v-else>{{ order }}</template>
    </div>
    <div class="wf-step-main">
      <div class="wf-step-name">
        {{ node.name || codeLabel }}
        <span v-if="roundLabel" class="wf-step-code">{{ roundLabel }}</span>
        <span v-if="codeLabel && codeLabel !== node.name" class="wf-step-code">{{ codeLabel }}</span>
      </div>
      <div class="wf-step-note">{{ note }}</div>
      <!--
        询问节点的问句与选项画在节点内部：等待时是表单，回复后随 choice 清空，
        节点按完成态收起。单专家选项仍走卡外那套。
      -->
      <ChoiceChips
        v-if="choice"
        extra-class="wf-node-choice"
        :question="question"
        :options="choice.options || []"
        :disabled="!choiceEnabled"
        @choose="emit('choose', $event)"
        @other="emit('other')"
      />
      <!--
        节点内分三层，各占各的显示范围：
        1) 过程（思考）单独一块折叠区——运行中且尚未出现工具步时自动展开，
           思考这一拍结束（工具出现）或节点结束后自动收起，用户点过就以点击为准；
        2) 工具胶囊放在折叠外，折叠里只留思考正文；
        3) 正文单独进四行窗口，超出才出「展开全文」。
      -->
      <div v-if="hasBody" class="wf-node-body">
        <FoldSection
          v-if="hasThinking"
          :open="thinkOpen"
          :label="WF_NODE_THINK_LABEL"
          :streaming="running"
          head-class="wf-thought-head"
          @toggle="toggleThink"
        >
          <!-- 展开后的思考：限四行内滚，不把整段思考铺满卡片；工具不在这里 -->
          <div ref="thinkEl" class="wf-node-thought-body" :data-wf-running="running ? '1' : undefined">
            <div class="wf-node-think">{{ thinkingText }}</div>
          </div>
        </FoldSection>
        <ToolPillList
          v-if="pills.length"
          class="wf-step-tools"
          :items="pills"
          :open="toolDetailOpen"
          @toggle="toggleToolDetail"
        />
        <!--
          正文：只有真的产出了正文才占位置；等待询问时问句在表单里，这里不出框。
          与卡级结果框同一形态——正文、复制、展开全文都收在同一个框里。
        -->
        <ResultTextBox
          v-if="outputText"
          class="wf-node-result"
          :action="expandAction"
          :copy-text="outputText"
          @copy="copy(outputText)"
          @toggle="toggleExpand"
        >
          <div ref="bodyEl" class="wf-node-clamp" :class="bodyClass" :data-wf-running="running ? '1' : undefined">
            <div
              class="md answer wf-node-text"
              :class="{ 'is-table-scroll': tableScroll }"
              v-html="markdownHtml"
            />
          </div>
        </ResultTextBox>
      </div>
      <!--
        用户对询问的回复：续跑后选项清掉，节点里记下这次选择；
        它不落成新的用户消息，右侧不再冒一条内容相同的用户气泡。
      -->
      <div v-if="reply" class="wf-node-reply">
        <span class="wf-node-reply-label">您的选择</span>
        <span class="wf-node-reply-text">{{ reply }}</span>
      </div>
      <!-- 文件产物单独一行，不进四行窗口；没有文件就不占位置 -->
      <div v-if="files.length" class="wb-file-row wf-node-files">
        <FileCard v-for="file in files" :key="file.fileName" :file="file" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作流时间线上的一个步骤卡。所属域：runtime（前端展示）。
 *
 * <p>职责：只画投影好的一个节点（含它自己的思考 / 工具 / 正文 / 文件 / 询问表单），
 * 节点级展开状态与四行窗口测量收在本组件内部，循环框与卡片壳层只决定它放在哪一层。
 *
 * <p>数据口径：直播、收尾、回放读同一个节点视图；本组件不改投影、不清已流出的正文，
 * 展开状态只影响显示范围。样式走共用的 `styles/workflow-timeline.css`，
 * 与循环框、卡片壳层同一把尺子。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import type { ChatTurn, TrialWorkflowNodeView } from '@/types/runtime'
import type { ParticipantCard, ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'
import FileCard from '@/components/runtime/FileCard.vue'
import ChoiceChips from '@/components/runtime/workbench/chat/ChoiceChips.vue'
import ToolPillList from '@/components/runtime/workbench/chat/ToolPillList.vue'
import ResultTextBox from '@/components/runtime/workbench/chat/ResultTextBox.vue'
import FoldSection from '@/components/runtime/workbench/chat/FoldSection.vue'
import { displayAnswerText, markdownText } from '@/components/runtime/workbench/chat/chatText'
import { copyAnswerText } from '@/components/runtime/workbench/chat/clipboard'
import { toolTitleByName } from '@/components/runtime/workbench/chat/toolTitles'
import {
  WF_NODE_THINK_LABEL,
  wfNodeChoice,
  wfNodeFiles,
  wfNodeHasBody,
  wfNodeHasThinking,
  wfNodeKey,
  wfNodeLabelStatus,
  wfNodeOutputText,
  wfNodeQuestion,
  wfNodeReply,
  wfNodeRoundLabel,
  wfNodeRunning,
  wfNodeStepToolPills,
  wfNodeThinkingText,
  wfNodeToolPills,
  wfStepNote,
  wfStepState
} from '@/components/runtime/workbench/chat/workflowCardView'
import {
  BOX_MAX_LINES,
  answerClampOf,
  bodyExpandAction
} from '@/components/runtime/workbench/workbenchContentBox'
import { workflowNodeToolLabel } from '@/components/runtime/workbench/workbenchWorkflowNode'
import { presentToolDetail, toolFaceName } from '@/components/runtime/workbench/workbenchToolPill'

const props = withDefaults(
  defineProps<{
    /** 本轮助手消息（工作流卡挂在它下面） */
    turn: ChatTurn
    /** 参与者卡片（含投影好的 workflow 快照，取终态与节点正文口径） */
    card: ParticipantCard
    /** 本步节点视图 */
    node: TrialWorkflowNodeView
    /** 同层全部节点（算「第 N 轮」用） */
    peers?: TrialWorkflowNodeView[]
    /** 本步在所在层里的序号（未开始节点画编号用） */
    order?: number
    /** 当前选项是否可点（最后一条助手消息且不在流式中） */
    choiceEnabled: boolean
    /** 是否工作台皮肤：调试皮肤原样展示，不剥内部键 */
    workbench?: boolean
  }>(),
  { peers: () => [], order: 1, workbench: true }
)

const emit = defineEmits<{
  /** 用户点了停等选项；回传原始 option */
  choose: [option: ParticipantChoiceOption]
  /** 用户点了平台追加的「其它 / 自填」行；壳层只聚焦输入，不提交 */
  other: []
}>()

/** 正文单行高度（px）：与 `.md.answer` 的 14px × 1.6 一致，改字号时同步改这里 */
const LINE_HEIGHT_PX = 14 * 1.6

/**
 * 过程区展开覆盖：`undefined` 表示没点过（按默认态），布尔值表示用户点的结果。
 *
 * <p>不用 `Record` + hasOwnProperty：单节点只有一个折叠区，直接用一个三态值即可，
 * 点过之后也不会被后续投影掰回去。
 */
const thinkOverride = ref<boolean | undefined>(undefined)
/** 工具胶囊详情是否展开（键 = 胶囊 key） */
const toolDetailOpen = reactive<Record<string, boolean>>({})
/** 正文四行窗口是否由用户展开 */
const expanded = ref(false)
/** 正文是否被四行截断（实测） */
const overflow = ref(false)
/** 上次测量时的内容长度：长度没变就不可能溢出，直接跳过读布局 */
let measuredLen = -1
let measureFrame = 0

const bodyEl = ref<HTMLElement | null>(null)
const thinkEl = ref<HTMLElement | null>(null)

/** 本步在时间线上的稳定键（同一编码的多轮不共用一个键） */
const nodeKey = computed(
  () => `${props.turn.key}:${props.card.key}:node:${wfNodeKey(props.node, props.order ?? 0)}`
)

/** 时间轴样式状态 */
const state = computed(() => wfStepState(props.node, props.card.closed))

/**
 * 展示用节点编码：子图内部节点的运行编码带范围前缀，展示仍用原始编码。
 */
const codeLabel = computed(() => (props.node.displayCode || props.node.code || '').trim())

/** 节点下方说明 */
const note = computed(() => wfStepNote(props.node, props.card.closed))

/** 同层里出现多轮时标「第 N 轮」 */
const roundLabel = computed(() => wfNodeRoundLabel(props.node, props.peers))

/** 询问表单 */
const choice = computed(() => wfNodeChoice(props.node, props.card))

/** 询问问句 */
const question = computed(() => wfNodeQuestion(props.node, props.card))

/** 节点正文（询问类节点为空） */
const outputText = computed(() => wfNodeOutputText(props.node, props.card))

/** 节点自己的思考正文 */
const thinkingText = computed(() => wfNodeThinkingText(props.node))

/** 是否还有可折叠的思考 */
const hasThinking = computed(() => wfNodeHasThinking(props.node))

/** 是否有过程 / 正文要画 */
const hasBody = computed(() => wfNodeHasBody(props.node, props.card))

/** 用户已答复的选择 */
const reply = computed(() => wfNodeReply(props.node))

/** 节点产出的文件 */
const files = computed(() => wfNodeFiles(props.node))

/** 是否仍在运行 */
const running = computed(() => wfNodeRunning(props.node, props.card))

/** 正文 Markdown（按节点键分槽缓存，同一节点多轮不互相覆盖） */
const markdownHtml = computed(() =>
  markdownText(
    `${props.turn.key}:wfnode:${wfNodeKey(props.node, props.order ?? 0)}`,
    displayAnswerText(outputText.value, props.workbench),
    running.value
  )
)

/** 这个节点是否已经出现工具步 */
const hasToolStep = computed(() => {
  const steps = Array.isArray(props.node.steps) ? props.node.steps : []
  return steps.some((step) => step.type === 'TOOL')
})

/** 未操作时的默认态：三拍口径——节点仍在跑、且还没出现工具步才展开思考 */
const thinkOpenByDefault = computed(() => running.value && !hasToolStep.value)

/** 过程区当前是否展开：用户点过就以点击为准 */
const thinkOpen = computed(() =>
  typeof thinkOverride.value === 'boolean' ? thinkOverride.value : thinkOpenByDefault.value
)

/** 展开 / 收起过程区：只改显示范围，不动已产出的文字 */
function toggleThink(): void {
  thinkOverride.value = !thinkOpen.value
}

/** 正文四行窗口的类名 */
const bodyClass = computed<Record<string, boolean>>(() => ({
  'is-clamped': !!outputText.value && !expanded.value,
  'is-expanded': expanded.value
}))

/** 正文「展开全文 / 收起」按钮状态 */
const expandAction = computed(() => bodyExpandAction(true, expanded.value, overflow.value))

/** 展开 / 收起正文：只改显示范围，不清空也不替换已流出的正文 */
function toggleExpand(): void {
  expanded.value = !expanded.value
}

/** 含表正文只在展开后允许横向滚动 */
const tableScroll = computed(() => answerClampOf(outputText.value) === 'table' && expanded.value)

/** 节点工具胶囊：图上 TOOL 节点 + AGENT 节点过程里的工具调用，统一画在折叠外 */
const pills = computed(() => {
  const prefix = nodeKey.value
  const nodeItems = wfNodeToolPills(props.node, props.card.closed).map((pill) => ({
    key: prefix + ':pill:' + pill.key,
    state: pill.state,
    label: workflowNodeToolLabel(
      pill,
      wfNodeLabelStatus(props.node, props.card.closed),
      toolFaceName(pill.name, toolTitleByName)
    ),
    detail: ''
  }))
  const stepItems = wfNodeStepToolPills(props.node, props.card.closed).map((pill) => ({
    key: prefix + ':steppill:' + pill.key,
    state: pill.state,
    label: workflowNodeToolLabel(
      pill,
      stepPillStatus(pill.state),
      toolFaceName(pill.name, toolTitleByName)
    ),
    detail: pill.detail ? presentToolDetail(pill.detail) : ''
  }))
  return [...nodeItems, ...stepItems]
})

/**
 * 工具步三态 → 胶囊文案用的状态码。
 *
 * @param state 工具步三态
 * @return 状态码
 */
function stepPillStatus(state: 'running' | 'done' | 'error'): string {
  if (state === 'running') {
    return 'RUNNING'
  }
  if (state === 'error') {
    return 'FAILED'
  }
  return 'SUCCESS'
}

/**
 * 切换某条工具胶囊的详情展开态。
 *
 * @param key 胶囊 key
 */
function toggleToolDetail(key: string): void {
  toolDetailOpen[key] = toolDetailOpen[key] !== true
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

/** 量一次正文是否超过四行，决定要不要给「展开全文」 */
function measure(): void {
  const el = bodyEl.value
  if (!el || !outputText.value) {
    if (overflow.value) {
      overflow.value = false
    }
    return
  }
  // 已展开时高度是全文，量不出「有没有超四行」；保留展开前的结论
  if (expanded.value) {
    return
  }
  const contentLength = (props.node.output || '').length
  if (measuredLen === contentLength) {
    return
  }
  measuredLen = contentLength
  const limit = BOX_MAX_LINES * LINE_HEIGHT_PX
  const overflowing = el.scrollHeight - limit > 2
  if (overflow.value !== overflowing) {
    overflow.value = overflowing
  }
}

/** 节点过程 / 正文在框内跟到最新一行（用户上翻后不拽回） */
function follow(): void {
  const body = bodyEl.value
  if (body && body.dataset.wfRunning === '1' && !expanded.value) {
    followScrollable(body)
  }
  const think = thinkEl.value
  // 收起时 grid 行高为 0（clientHeight 为 0），直接跳过
  if (think && think.dataset.wfRunning === '1' && think.clientHeight > 0) {
    followScrollable(think)
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

// 回放挂载 / 直播更新都补一次测量；流式增量只在这一帧统一读布局
onMounted(scheduleMeasure)
onUpdated(scheduleMeasure)

// 正文长度一变就补量一次，避免长正文一直不出「展开全文」
watch(
  () => (props.node.output || '').length,
  () => {
    void nextTick(scheduleMeasure)
  }
)

onBeforeUnmount(() => {
  if (measureFrame) {
    cancelAnimationFrame(measureFrame)
    measureFrame = 0
  }
})
</script>

<style scoped src="../../../../styles/chat-markdown.css"></style>
<style scoped src="../../../../styles/workflow-step.css"></style>
