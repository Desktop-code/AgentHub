<template>
  <!--
    工作流执行卡片：块内只有这一张卡，内部子专家只出现在时间轴节点上，不在卡下再拆 Agent 块。
    上方过程区；下方结果区（正文四行 + 文件行）。数据全部来自投影好的 card.workflow，
    本组件不请求接口、不另拼时间线。
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
      <div v-if="card.workflow!.nodes.length" class="wf-steps">
        <div
          v-for="(node, nodeIndex) in card.workflow!.nodes"
          :key="node.code || ('wf-' + nodeIndex)"
          class="wf-step-item"
          :class="'is-' + wfStepState(node, card.closed)"
        >
          <div class="wf-step-icon" :class="'is-' + wfStepState(node, card.closed)">
            <span v-if="wfStepState(node, card.closed) === 'active'" class="wf-spin" aria-hidden="true">⏳</span>
            <template v-else-if="wfStepState(node, card.closed) === 'done'">✓</template>
            <template v-else-if="wfStepState(node, card.closed) === 'failed'">✕</template>
            <template v-else-if="wfStepState(node, card.closed) === 'waiting'">⏸</template>
            <template v-else>{{ nodeIndex + 1 }}</template>
          </div>
          <div class="wf-step-main">
            <div class="wf-step-name">
              {{ node.name || node.code }}
              <span v-if="node.code && node.code !== node.name" class="wf-step-code">{{ node.code }}</span>
            </div>
            <div class="wf-step-note">{{ wfStepNote(node, card.closed) }}</div>
            <!--
              询问节点的问句与选项画在节点内部：等待时是表单，回复后随 choice 清空，
              节点按完成态收起。单专家选项仍走卡外那套。
            -->
            <ChoiceChips
              v-if="wfNodeChoice(node, card)"
              extra-class="wf-node-choice"
              :question="wfNodeQuestion(node, card)"
              :options="wfNodeChoice(node, card)?.options || []"
              :disabled="!choiceEnabled"
              @choose="emit('choose', $event)"
            />
            <!--
              节点内分三层，各占各的显示范围：
              1) 过程（思考）单独一块折叠区——运行中且尚未出现工具步时自动展开，
                 思考这一拍结束（工具出现）或节点结束后自动收起，
                 用户点过就以点击为准，与单 Agent / MA 的思考区同一口径；
              2) 工具胶囊放在折叠外，与方案参与者卡同一套接法，折叠里只留思考正文；
              3) 正文单独进四行窗口，超出才出「展开全文」。
              两处状态都只由「用户点击 / 节点真实运行态」决定，直播增量与重挂不会掰回去。
            -->
            <div v-if="wfNodeHasBody(node, card)" class="wf-node-body">
              <FoldSection
                v-if="wfNodeHasThinking(node)"
                :open="nodeThinkOpen(node)"
                :label="WF_NODE_THINK_LABEL"
                :streaming="wfNodeRunning(node, card)"
                head-class="wf-thought-head"
                @toggle="toggleNodeThink(node)"
              >
                <!-- 展开后的思考：限四行内滚，不把整段思考铺满卡片；工具不在这里 -->
                <div
                  :ref="nodeThinkRef(node)"
                  class="wf-node-thought-body"
                  :data-wf-running="wfNodeRunning(node, card) ? '1' : undefined"
                >
                  <div class="wf-node-think">{{ wfNodeThinkingText(node) }}</div>
                </div>
              </FoldSection>
              <!--
                工具胶囊在折叠外：文案仍走工作流自己的 workflowNodeToolLabel，
                胶囊样式与点击 / 详情展开复用方案参与者卡同一份 ToolPillList。
              -->
              <ToolPillList
                v-if="nodePills(node).length"
                class="wf-step-tools"
                :items="nodePills(node)"
                :open="toolDetailOpen"
                @toggle="toggleToolDetail"
              />
              <!--
                正文：只有真的产出了正文才占位置；等待询问时问句在表单里，这里不出框。
                与卡级结果框同一形态——正文、复制、展开全文都收在同一个框里。
              -->
              <ResultTextBox
                v-if="wfNodeOutputText(node, card)"
                class="wf-node-result"
                :action="nodeExpandAction(node)"
                :copy-text="wfNodeOutputText(node, card)"
                @copy="copy(wfNodeOutputText(node, card))"
                @toggle="toggleNodeExpand(node)"
              >
                <div
                  :ref="nodeBodyRef(node)"
                  class="wf-node-clamp"
                  :class="nodeBodyClass(node)"
                  :data-wf-running="wfNodeRunning(node, card) ? '1' : undefined"
                >
                  <div
                    class="md answer wf-node-text"
                    :class="{ 'is-table-scroll': nodeTableScroll(node) }"
                    v-html="
                      markdownText(
                        turn.key + ':wfnode:' + (node.code || nodeIndex),
                        displayAnswerText(wfNodeOutputText(node, card), workbench),
                        wfNodeRunning(node, card)
                      )
                    "
                  />
                </div>
              </ResultTextBox>
            </div>
            <!--
              用户对询问的回复：续跑后选项清掉，节点里记下这次选择；
              它不落成新的用户消息，右侧不再冒一条内容相同的用户气泡。
            -->
            <div v-if="wfNodeReply(node)" class="wf-node-reply">
              <span class="wf-node-reply-label">您的选择</span>
              <span class="wf-node-reply-text">{{ wfNodeReply(node) }}</span>
            </div>
            <!-- 文件产物单独一行，不进四行窗口；没有文件就不占位置 -->
            <div v-if="wfNodeFiles(node).length" class="wb-file-row wf-node-files">
              <FileCard v-for="file in wfNodeFiles(node)" :key="file.fileName" :file="file" />
            </div>
          </div>
        </div>
      </div>
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
 * <p>职责：只画一张投影好的工作流卡（状态、时限、节点过程 / 正文、停等选项、结果与文件），
 * 不请求接口、不改投影、不另起时间线。单专家卡遇到 `card.workflow` 时才挂载本组件，
 * 因此对话壳层不再内联工作流的模板、样式与展开状态。
 *
 * <p>数据口径：直播、收尾、回放都读同一个 {@link ParticipantCard}，节点过程 / 选项 /
 * 正文只在「新快照确实带了内容」时替换（由投影保证），这里的展开状态只影响显示范围，
 * 不会清空已流出的文字。
 */
import { computed, onBeforeUnmount, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import type { ChatTurn, TrialWorkflowNodeView } from '@/types/runtime'
import type { ParticipantCard, ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'
import FileCard from '@/components/runtime/FileCard.vue'
import WorkbenchCitations from '@/components/runtime/workbench/WorkbenchCitations.vue'
import ChoiceChips from '@/components/runtime/workbench/chat/ChoiceChips.vue'
import ToolPillList from '@/components/runtime/workbench/chat/ToolPillList.vue'
import ResultTextBox from '@/components/runtime/workbench/chat/ResultTextBox.vue'
import FoldSection from '@/components/runtime/workbench/chat/FoldSection.vue'
import { displayAnswerText, markdownText } from '@/components/runtime/workbench/chat/chatText'
import { citationsOf, hasWorkflowCitations } from '@/components/runtime/workbench/chat/citationView'
import { copyAnswerText } from '@/components/runtime/workbench/chat/clipboard'
import { toolTitleByName } from '@/components/runtime/workbench/chat/toolTitles'
import {
  WF_NODE_THINK_LABEL,
  wfAnswerOf,
  wfCardTitle,
  wfCompleted,
  wfFilesOf,
  wfLegacyChoice,
  wfLegacyResultVisible,
  wfNodeChoice,
  wfNodeFiles,
  wfNodeHasBody,
  wfNodeHasThinking,
  wfNodeLabelStatus,
  wfNodeOutputText,
  wfNodeQuestion,
  wfNodeReply,
  wfNodeRunning,
  wfNodeStepToolPills,
  wfNodeThinkingText,
  wfNodeToolPills,
  wfOutputTip,
  wfResultTitle,
  wfStatusClass,
  wfStatusText,
  wfStepNote,
  wfStepState,
  wfLimitLines
} from '@/components/runtime/workbench/chat/workflowCardView'
import {
  BOX_MAX_LINES,
  answerClampOf,
  bodyExpandAction,
  foldOpenOf,
  isUnlimitedAnswerBox,
  nextFoldValue,
  tableFoldedByDefault
} from '@/components/runtime/workbench/workbenchContentBox'
import { workflowNodeToolLabel } from '@/components/runtime/workbench/workbenchWorkflowNode'
import { presentToolDetail, toolFaceName } from '@/components/runtime/workbench/workbenchToolPill'

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

/** 节点过程区的展开覆盖（键 = 轮:参与者:node:编码）；用户点过就以点击为准 */
const thinkOverride = reactive<Record<string, boolean>>({})
/** 工具胶囊详情是否展开（键 = 轮:参与者:node:编码 + 胶囊）；与参与者卡同一套点开 / 收起 */
const toolDetailOpen = reactive<Record<string, boolean>>({})
/** 节点正文四行窗口是否由用户展开 */
const nodeExpanded = reactive<Record<string, boolean>>({})
/** 节点正文是否被四行截断的实测缓存 */
const nodeOverflow = reactive<Record<string, boolean>>({})
/** 卡级旧结果正文是否展开 / 是否被截断 */
const legacyExpanded = ref(false)
const legacyOverflow = ref(false)

/** 节点正文 / 过程区的 DOM 句柄：只量这一层，跟滚也只读这一层 */
const nodeBodyEls = new Map<string, HTMLElement>()
const nodeThinkEls = new Map<string, HTMLElement>()
let legacyBodyEl: HTMLElement | null = null

/** 每个窗口上次测量时的内容长度：长度没变就不可能溢出，直接跳过读布局 */
const measuredLen = new Map<string, number>()
let measuredAt = 0
let measureFrame = 0

/** 时限刷新时钟：只在本卡仍在运行且页签在前台时走 */
const now = ref(Date.now())
let timer = 0

/** 节点四行窗口 / 过程区的稳定键 */
function nodeKey(node: TrialWorkflowNodeView): string {
  return `${props.turn.key}:${props.card.key}:node:${node.code || ''}`
}

/**
 * 这个节点是否已经出现工具步。
 *
 * <p>与方案参与者卡的 {@code actorHasTool} 同口径：只看过程事件本身，
 * 与该次工具成功 / 失败无关——失败也说明思考这一拍已经结束。
 *
 * @param node 节点视图
 * @return true 表示已有 TOOL 步
 */
function nodeHasToolStep(node: TrialWorkflowNodeView): boolean {
  const steps = Array.isArray(node.steps) ? node.steps : []
  return steps.some((step) => step.type === 'TOOL')
}

/**
 * 节点过程区未操作时的默认态：节点仍在跑、且还没出现工具步才展开。
 *
 * <p>与方案参与者卡的「三拍」同口径：思考写完（工具出现）或节点结束就收起，
 * 让位给折叠外的工具胶囊与四行正文。用户点过的覆盖值仍优于此默认态。
 *
 * @param node 节点视图
 * @return true 表示默认展开思考
 */
function nodeThinkOpenByDefault(node: TrialWorkflowNodeView): boolean {
  if (!wfNodeRunning(node, props.card)) {
    return false
  }
  return !nodeHasToolStep(node)
}

/** 节点过程区当前是否展开 */
function nodeThinkOpen(node: TrialWorkflowNodeView): boolean {
  return foldOpenOf(thinkOverride, nodeKey(node), nodeThinkOpenByDefault(node))
}

/** 展开 / 收起单个节点的过程区：只改显示范围，不动已产出的文字 */
function toggleNodeThink(node: TrialWorkflowNodeView): void {
  const key = nodeKey(node)
  thinkOverride[key] = nextFoldValue(thinkOverride, key, nodeThinkOpenByDefault(node))
}

/** 节点四行窗口的类名：运行中 / 已结束都限四行，展开后放开 */
function nodeBodyClass(node: TrialWorkflowNodeView): Record<string, boolean> {
  const expanded = nodeExpanded[nodeKey(node)] === true
  return {
    'is-clamped': !!wfNodeOutputText(node, props.card) && !expanded,
    'is-expanded': expanded
  }
}

/** 节点正文「展开全文 / 收起」按钮状态：正文存在且被四行裁过才出现 */
function nodeExpandAction(node: TrialWorkflowNodeView) {
  if (!wfNodeOutputText(node, props.card)) {
    return 'none' as const
  }
  const key = nodeKey(node)
  return bodyExpandAction(true, nodeExpanded[key] === true, nodeOverflow[key] === true)
}

/** 展开 / 收起单个节点：只改显示范围，不清空也不替换已流出的正文 */
function toggleNodeExpand(node: TrialWorkflowNodeView): void {
  const key = nodeKey(node)
  nodeExpanded[key] = nodeExpanded[key] !== true
}

/** 含表正文只在展开后允许横向滚动：收起时仍是四行窗口 */
function nodeTableScroll(node: TrialWorkflowNodeView): boolean {
  const expanded = nodeExpanded[nodeKey(node)] === true
  return answerClampOf(wfNodeOutputText(node, props.card)) === 'table' && expanded
}

/** 节点正文长度：判断「文字有没有变」，不是展示值 */
function nodeContentLength(node: TrialWorkflowNodeView): number {
  return (node.output || '').length
}

/**
 * 节点正文节点 ref。
 *
 * @param node 节点视图
 * @return Vue 的 ref 回调
 */
function nodeBodyRef(node: TrialWorkflowNodeView) {
  const key = nodeKey(node)
  return (el: unknown) => {
    if (el instanceof HTMLElement) {
      nodeBodyEls.set(key, el)
    } else {
      nodeBodyEls.delete(key)
    }
  }
}

/**
 * 节点过程区 ref：只在用户展开且节点仍在跑时跟滚。
 *
 * @param node 节点视图
 * @return Vue 的 ref 回调
 */
function nodeThinkRef(node: TrialWorkflowNodeView) {
  const key = nodeKey(node)
  return (el: unknown) => {
    if (el instanceof HTMLElement) {
      nodeThinkEls.set(key, el)
    } else {
      nodeThinkEls.delete(key)
    }
  }
}

/** 卡级结果正文的 ref：量四行截断 */
function setLegacyBodyEl(el: unknown): void {
  legacyBodyEl = el instanceof HTMLElement ? el : null
}

/**
 * 节点胶囊的短路缓存：模板同一帧会取两次（`v-if` 与 `:items`），
 * 不重复做同名合并与详情拼接。
 */
const nodePillMemo = new WeakMap<
  TrialWorkflowNodeView,
  { closed?: string; items: ReturnType<typeof buildNodePills> }
>()

/**
 * 节点工具胶囊：图上 TOOL 节点 + AGENT 节点过程里的工具调用，统一画在折叠外。
 *
 * <p>文案仍走工作流自己的 {@link workflowNodeToolLabel}（与参与者卡的 `toolPhrase` 不混用），
 * 次数与详情结构对齐方案参与者卡：同名工具的多次调用合成一条并带「（N 次）」，
 * 详情按次列出每次的结果。样式与详情展开复用参与者卡同一份 `ToolPillList`。
 * 胶囊 key 拼上节点键，同一工具出现在多个节点时详情状态互不串。
 *
 * @param node 节点视图
 * @return 可直接交给 ToolPillList 的条目
 */
function nodePills(node: TrialWorkflowNodeView) {
  const hit = nodePillMemo.get(node)
  if (hit && hit.closed === props.card.closed) {
    return hit.items
  }
  const items = buildNodePills(node)
  nodePillMemo.set(node, { closed: props.card.closed, items })
  return items
}

/**
 * 真正装配节点胶囊（缓存命中时不走这里）。
 *
 * @param node 节点视图
 * @return 节点级 + 步级胶囊
 */
function buildNodePills(node: TrialWorkflowNodeView) {
  const prefix = nodeKey(node)
  // 节点级胶囊（TOOL 节点）：状态读节点本身；快照没有入参出参，不给详情
  const nodeItems = wfNodeToolPills(node, props.card.closed).map((pill) => ({
    key: prefix + ':pill:' + pill.key,
    state: pill.state,
    label: workflowNodeToolLabel(
      pill,
      wfNodeLabelStatus(node, props.card.closed),
      toolFaceName(pill.name, toolTitleByName)
    ),
    detail: ''
  }))
  // 步级胶囊（AGENT 节点内部工具）：同名已合并并带真实次数，详情是逐次结果摘录
  const stepItems = wfNodeStepToolPills(node, props.card.closed).map((pill) => ({
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
}

/**
 * 工具步三态 → 胶囊文案用的状态码。
 *
 * <p>步级胶囊没有节点状态可读，只能按自身三态落到「已调用 / 调用失败 / 正在调用」；
 * 与节点级胶囊共用 `workflowNodeToolLabel`，不另写一句文案。
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

/** 量一次节点 / 卡级正文是否超过四行，决定要不要给「展开全文」 */
function measure(): void {
  const stamp = Date.now()
  const streaming = props.turn.status === 'streaming'
  const throttled = streaming && stamp - measuredAt < MEASURE_MIN_GAP_MS
  const limit = BOX_MAX_LINES * LINE_HEIGHT_PX
  for (const node of props.card.workflow?.nodes || []) {
    const key = nodeKey(node)
    const el = nodeBodyEls.get(key)
    if (!el || !wfNodeOutputText(node, props.card)) {
      if (nodeOverflow[key]) {
        nodeOverflow[key] = false
      }
      continue
    }
    // 已展开时高度是全文，量不出「有没有超四行」；保留展开前的结论
    if (nodeExpanded[key] === true) {
      continue
    }
    // 流式期降频：节点多时也不在主线程上逐帧量所有窗口
    if (throttled) {
      continue
    }
    const contentLength = nodeContentLength(node)
    if (measuredLen.get(key) === contentLength) {
      continue
    }
    // 描边 / 内边距已挪到外框，这一层就是四行窗口本身
    const overflowing = el.scrollHeight - el.clientHeight > 2
    measuredLen.set(key, contentLength)
    if (nodeOverflow[key] !== overflowing) {
      nodeOverflow[key] = overflowing
    }
  }
  // 卡级旧结果正文：四行按文字节点的 scrollHeight 与四行行高比
  const legacyEl = legacyBodyEl
  if (!legacyEl || !legacyLimited()) {
    if (legacyOverflow.value) {
      legacyOverflow.value = false
    }
  } else if (!legacyExpanded.value && !throttled) {
    const contentLength = wfAnswerOf(props.card).length
    const key = `${props.turn.key}:${props.card.key}:wf`
    if (measuredLen.get(key) !== contentLength) {
      const overflowing = legacyEl.scrollHeight - limit > 2
      measuredLen.set(key, contentLength)
      if (legacyOverflow.value !== overflowing) {
        legacyOverflow.value = overflowing
      }
    }
  }
  if (!throttled) {
    measuredAt = stamp
  }
}

/** 节点过程 / 正文在框内跟到最新一行（用户上翻后不拽回） */
function follow(): void {
  for (const el of nodeBodyEls.values()) {
    if (el.dataset.wfRunning === '1' && !el.classList.contains('is-expanded')) {
      followScrollable(el)
    }
  }
  for (const el of nodeThinkEls.values()) {
    // 收起时 grid 行高为 0（clientHeight 为 0），直接跳过
    if (el.dataset.wfRunning === '1' && el.clientHeight > 0) {
      followScrollable(el)
    }
  }
  // 旧卡兜底的卡级正文：仍在流式且收在四行里时跟到最新一行
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

/* 垂直时间轴：连接线在左，节点圆点压在线上面；一行一项 */
.wf-steps {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
}

.wf-steps::before {
  position: absolute;
  top: 28px;
  bottom: 28px;
  left: 28px;
  width: 2px;
  background: #e2e8f0;
  content: '';
  z-index: 0;
}

.wf-step-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 16px;
  color: #94a3b8;
  font-size: 14px;
  z-index: 1;
  transition: color 0.3s;
}

.wf-step-item.is-done {
  color: #1e293b;
  font-weight: 500;
}

.wf-step-item.is-active {
  color: #2563eb;
  font-weight: 500;
}

.wf-step-item.is-failed {
  color: #b91c1c;
  font-weight: 500;
}

/* 等待人工 / 等待回复：停在原地，不转圈也不画完成勾 */
.wf-step-item.is-waiting {
  color: #b45309;
  font-weight: 500;
}

.wf-step-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #f1f5f9;
  color: #cbd5e1;
  font-size: 12px;
  transition: all 0.3s;
}

.wf-step-icon.is-done {
  background: #10b981;
  color: #fff;
}

.wf-step-icon.is-active {
  background: #3b82f6;
  color: #fff;
  box-shadow: 0 0 0 4px #dbeafe;
}

.wf-step-icon.is-failed {
  background: #ef4444;
  color: #fff;
}

.wf-step-icon.is-waiting {
  background: #f59e0b;
  color: #fff;
}

.wf-step-main {
  /* 节点内部现在要放思考 / 工具 / 四行正文：占满时间轴剩余宽度，正文不被挤成窄条 */
  flex: 1;
  min-width: 0;
}

.wf-step-name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.wf-step-code {
  color: #9ca3af;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  font-weight: 400;
}

.wf-step-note {
  margin-top: 2px;
  color: #64748b;
  font-size: 12px;
  font-weight: 400;
}

.wf-step-tools {
  /* 折叠外紧跟在思考下面；胶囊与方案参与者卡同一份，底部留出与结果框的间距 */
  margin: 8px 0 10px;
}

/* 没有详情可开的胶囊（TOOL 节点快照没有入参出参）保持静态，不像可点 */
.wf-step-tools :deep(span.wb-tool-pill) {
  cursor: default;
}

/* ===== 节点内聚：过程折叠成一行，正文单独进四行窗口 ===== */
.wf-node-body {
  margin-top: 8px;
}

/*
 * 展开后的过程内容：固定四行，超出在这四行里面滚动，不把整段思考铺满卡片。
 * 字号 / 行高与单 Agent 思考区同一把尺子（13px × 1.6）；
 * 这一层自带 12px 下内边距，height 按 border-box 算，所以四行之外再加回 12px。
 */
.wf-node-thought-body {
  padding: 0 12px 12px;
  max-height: calc(4 * 1.6em + 12px);
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  font-size: 13px;
  line-height: 1.6;
}

/*
 * 节点正文框：与卡级结果框同一形态——外框承担描边 / 圆角 / 内边距，
 * 正文、复制按钮、展开全文都收在框内，展开条不再落到框外。
 * position: relative 由共用结果框提供。
 */
.wf-node-result {
  padding: 10px 12px;
  border: 1px solid var(--ah-border-soft, #eef2f7);
  border-radius: 6px;
  background: #f9fafb;
}

/* 复制按钮与卡级同一枚：绝对定位到框内右上角，移入框才出现 */
.wf-node-result :deep(.wb-copy) {
  top: 6px;
  right: 6px;
}

/*
 * 四行正文窗口：与卡级正文同一行高口径（14px × 1.6）。
 * 运行中在框内跟到最新一行；结束后默认仍停在这个高度，点「展开全文」才放开。
 */
.wf-node-clamp {
  max-height: calc(4 * 1.6em);
  overflow-x: hidden;
  overflow-y: auto;
  color: #475569;
  /* 与卡级正文同一字号 / 行高：四行高度才是同一把尺子 */
  font-size: 14px;
  line-height: 1.6;
  scrollbar-gutter: stable;
}

.wf-node-clamp.is-expanded {
  max-height: none;
  overflow-y: visible;
}

/*
 * 展开后的表：只在展开态允许横向滚动，不挤压卡片宽度。
 * 收起态一律保持四行——含表的节点也必须先折起来。
 */
.wf-node-clamp.is-expanded .wf-node-text {
  overflow-x: auto;
}

/* 折叠里只剩一段思考正文：工具已挪到折叠外的胶囊，不再有工具行与相邻间距 */
.wf-node-think {
  color: #64748b;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 节点正文走 Markdown，但去掉结果框的内边距 / 边框，避免灰框里再套一层框 */
.wf-node-text {
  padding: 0;
  border: 0;
  background: transparent;
  font-size: inherit;
  line-height: 1.6;
}

/* 询问节点里的表单：结构沿用工作台 chips，只把外框换成节点内可读的浅色块 */
.chat.is-workbench .wb-choice.wf-node-choice {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
}

.chat.is-workbench .wf-node-choice .wb-choice-question {
  color: #92400e;
  font-weight: 600;
}

/* 询问节点里的用户回复：选项清掉后留在这条节点内，不进四行窗口 */
.wf-node-reply {
  margin-top: 8px;
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.wf-node-reply-label {
  flex: none;
  color: #64748b;
}

.wf-node-reply-text {
  color: #334155;
  font-weight: 500;
  word-break: break-word;
}

/* 节点文件的单独一行：不进四行窗口，节点折叠后仍在 */
.wf-node-files {
  margin-top: 8px;
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

/* 生成中标题：只在文字上跑一道浅浅的高光，与单专家思考区同一口径 */
.chat.is-workbench :deep(.wf-thought-head .section-label.is-streaming) {
  background: linear-gradient(90deg, #9ca3af 0%, #635bff 45%, #9ca3af 90%);
  background-size: 220% 100%;
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
  animation: wf-thought-shimmer 1.8s linear infinite;
}

@keyframes wf-thought-shimmer {
  0% {
    background-position: 120% 0;
  }

  100% {
    background-position: -120% 0;
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
