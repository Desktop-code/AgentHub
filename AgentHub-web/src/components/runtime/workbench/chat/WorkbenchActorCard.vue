<template>
  <!--
    一位参与者怎么画：顶行（头像 + 名称 + 角标）+ 主体（预处理 / 方案框 / 思考 / 工具 /
    工作流卡 / 终态说明 / 结果气泡 / 引用 / 文件行）。所属域：runtime（前端展示）。

    只画投影好的 {@link ParticipantCard}，不请求接口、不重算投影、不决定「是不是最后一条」；
    hold / choiceEnabled / beforeAttachments 都由壳层算好传下来。三拍时序与四行限高
    仍读同一套纯函数，直播、收尾、回放共用同一条路径。
  -->
  <section class="wb-actor" :class="'is-' + card.kind">
    <!--
      头像与名称、角标同一行并垂直居中（对照 temp/test.html / test-l.html）。
      下方思考 / 工具 / 气泡 / 工作流卡缩进到名称列，不把头像拉高到整块高度。
    -->
    <div class="wb-actor-topline">
      <ActorAvatar
        :look="card.look"
        :workflow="isWorkflowCard(card)"
        :running="card.pending === true"
        :fallback-name="card.title"
        :style="participantAvatarStyle(card)"
        :title="card.title"
      />
      <div class="wb-actor-head">
        <span class="wb-actor-name">{{ card.title }}</span>
        <!--
          角标读配置与卡片类型：入口「对话入口」、方案「方案」、单兵专家「Agent」、工作流「工作流 WF」。
          is-wf 只给工作流块上紫色底，其余角标样式完全不变。
        -->
        <span
          v-if="actorRoleBadge(card)"
          class="wb-actor-role"
          :class="{ 'is-wf': !!card.workflow }"
        >{{ actorRoleBadge(card) }}</span>
      </div>
    </div>
    <div class="wb-actor-main">
      <!--
        入口预处理收在网关过程里、判定句之前。直播与回放都读卡片上的同一份投影，
        不再在网关卡片上方另顶一块。正文横排，默认只露前段。
      -->
      <PreprocessResultBox
        v-if="card.kind === 'gateway' && card.preprocess"
        :title="card.preprocess.title"
        :items="gatewayPreprocessItems()"
      />
      <!--
        有子卡或汇总时，外框就是这一张方案。思考、工具、正文留在框的上半，
        专家卡和汇总画在框内，不再跟方案并排，也不把专家名单再写一遍当正文。
        方案框与子卡顺序由 SchemeCard 负责，这里只传投影好的成员。
      -->
      <SchemeCard :members="schemeMembers(card)">
        <!-- 思考区：进行中先出这一行并保持可看；结束后默认收起，点左侧箭头再展开 -->
        <FoldSection
          v-if="showThoughtToggle()"
          :open="actorThinkOpen()"
          :label="thoughtToggleLabel()"
          :streaming="actorThinkOpenByDefault()"
          @toggle="toggleActorThink"
        >
          <!-- 滚动句柄挂在这段正文上：标题行不跟着滚，四行内跟到最新一行 -->
          <p v-if="actorThinkingText(card)" :ref="actorFoldRef" class="wb-actor-thinking">{{ actorThinkingText(card) }}</p>
        </FoldSection>
        <!--
          工具调用胶囊：同名调用聚合成一条；调用中蓝、有失败红、全成功绿，点开看逐次入参出参。
          三拍时序：只有思考这一拍结束（工具或结果已出现）后才允许出胶囊，失败也一样。
        -->
        <ToolPillList
          v-if="!actorThinkingBeat() && actorToolPills(card).length"
          :items="actorToolItems(card)"
          :open="toolDetailOpen"
          @toggle="toggleToolDetail"
        />
        <!--
          工作流执行卡片：这里只决定「这条运行用到工作流」，卡片本身的
          状态 / 时限 / 节点过程 / 停等选项 / 结果文件全部由 WorkflowRunCard 负责。
          投影仍是同一条：传入的就是直播、收尾、回放共用的 card.workflow。
        -->
        <WorkflowRunCard
          v-if="card.workflow"
          :turn="turn"
          :card="card"
          :hold="hold"
          :choice-enabled="choiceEnabled"
          :page-active="pageActive"
          :workbench="workbench"
          @choose="emit('choose', $event)"
          @other="emit('other')"
        />
        <!--
          空终态说明在正文框外面。它不是正文，也不占四行框。
        -->
        <p v-if="card.terminalNote && !card.workflow" class="wb-terminal-note">{{ card.terminalNote }}</p>
        <!--
          内容框（气泡）：只放判定句、分流句、报错、正文和选项。
          正文默认四行，框内可滚；被截断时底部常驻「展开全文」，再点收回。
          文件不进这个框，在框下面单独一行。
        -->
        <div
          v-if="actorHasBubble()"
          class="answer-block"
          :class="{ 'is-process-bubble': !card.answer }"
        >
          <p
            v-for="(line, resultIndex) in actorResultLines(card)"
            :key="'r-' + resultIndex"
            class="wb-actor-result"
          >{{ line }}</p>
          <p
            v-for="(err, errIndex) in card.errors"
            :key="'err-' + errIndex"
            class="wb-soft-error"
          >
            {{ err }}
            <router-link
              v-if="showModelConfigLink(err)"
              class="wb-error-action"
              to="/access/models"
            >去绑定模型</router-link>
            <router-link
              v-if="showReceptionConfigLink(err)"
              class="wb-error-action"
              :to="receptionConfigHref"
            >去配置对话路由</router-link>
          </p>
          <ResultTextBox
            v-if="card.answer"
            :action="bodyExpandActionOf()"
            :copy-text="card.answer"
            @copy="copyResult(card.answer)"
            @toggle="toggleMidBoxExpand"
          >
            <div
              :ref="answerTextRef"
              class="md answer"
              :class="bodyTextClass()"
              v-html="markdownText(turn.key + ':' + card.key, displayAnswerText(card.answer, workbench), turn.status === 'streaming' && card.pending)"
            />
          </ResultTextBox>
          <!--
            引用贴在结果框内部、正文下面。数据是卡片上已有的 tool_result，
            直播和回放同一份投影；这里不改正文，也不在没有出处时占位。
          -->
          <WorkbenchCitations
            v-if="showAnswerCitations(card)"
            attached
            :items="workbenchCitations(card)"
          />
          <!--
            已答复的方案询问：选项清掉，只在卡内留一行「您的选择」。
            这是停等续跑的回执，不再额外冒一条用户气泡，也不把问句整段清空。
            可点选项：模型需要用户拍板时给的 chips（机器块已从正文里摘掉）。
            是否可点由壳层按整会话规则算好传入，本组件只回传被点的 option。
          -->
          <ChoiceChips
            v-if="card.choice && (card.choice.reply || card.choice.options.length)"
            :extra-class="card.choice.reply ? 'wb-choice-replied' : ''"
            :question="card.choice.question"
            :options="card.choice.options"
            :reply="card.choice.reply"
            :waiting="card.choice.waiting && !card.choice.reply"
            :disabled="!choiceEnabled"
            @choose="emit('choose', $event)"
            @other="emit('other')"
          />
        </div>
        <!--
          这一步已经结束、但没有成文正文时，出处仍挂在原卡上。
          进行中且正文还没落地时不提前摆出来，免得插在过程和结果中间。
        -->
        <WorkbenchCitations
          v-if="showOrphanCitations(card)"
          :items="workbenchCitations(card)"
        />
        <div v-if="!card.workflow && card.files.length" class="wb-file-row">
          <FileCard v-for="file in card.files" :key="file.fileName" :file="file" />
        </div>
      </SchemeCard>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 参与者卡（单卡渲染）。所属域：runtime（前端展示）。
 *
 * <p>职责：只画「一位参与者」的顶行与主体，并自持该卡的折叠覆盖、工具详情、四行展开 /
 * 溢出实测、思考秒表、引用缓存、DOM 句柄、中间框跟滚与结束复位。
 *
 * <p>不负责（必须留在壳层）：投影与缓存、收口与过滤、路由链、整会话可点规则
 * （`choiceClickable` / `onChoice`）、`attachmentsBefore` 回溯、`onErrorCaptured`。
 * 这些是会话级规则，不是单卡局部状态。
 *
 * <p>点击口径保持拆前一致：
 * `ChoiceChips @choose → emit('choose') → 壳层 onChoice(turn, option) → choice-selected`。
 * 平台追加的「其它 / 自填」行走另一条：`@other → emit('other') → 壳层只聚焦输入`，
 * 不提交任何文案、也不在这里写回执。
 * 本组件不判断「是不是最后一条助手消息」，也不另发用户气泡。
 *
 * <p>直播、收尾、回放共用同一条投影：本组件只读传入的 `card`，不请求接口、不清空已流出文字，
 * 也不会把用户收起过的折叠重新打开。
 */
import { computed, onBeforeUnmount, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import type { ChatTurn } from '@/types/runtime'
import type { UserAttachmentView } from '@/components/runtime/userMessageImages'
import type {
  ParticipantCard,
  ParticipantChoiceOption
} from '@/components/runtime/workbench/workbenchParticipantFeed'
import { orderPreprocessItems } from '@/components/runtime/trialMessages'
import { WORKFLOW_ROLE_LABEL } from '@/components/runtime/workbench/workbenchThinkTimeline'
import { WORKFLOW_AVATAR_COLOR, isWorkflowAvatarCard } from '@/components/runtime/workbench/workbenchAvatar'
import { aggregateToolPills, presentToolDetail, toolFaceName } from '@/components/runtime/workbench/workbenchToolPill'
import {
  citationsFromToolSteps,
  type WorkbenchCitationView
} from '@/components/runtime/workbench/workbenchCitations'
import { isModelConfigActionable, isRouteConfigActionable } from '@/utils/workbenchFriendlyError'
import {
  BOX_MAX_LINES,
  answerClampOf,
  bodyExpandAction,
  foldOpenOf,
  formatThoughtElapsed,
  isMidProcessBox,
  isThinkOpenByDefault,
  isUnlimitedAnswerBox,
  nextFoldValue,
  tableFoldedByDefault
} from '@/components/runtime/workbench/workbenchContentBox'
import { displayAnswerText, markdownText } from '@/components/runtime/workbench/chat/chatText'
import { copyAnswerText } from '@/components/runtime/workbench/chat/clipboard'
import { toolTitleByName } from '@/components/runtime/workbench/chat/toolTitles'
import ActorAvatar from '@/components/runtime/workbench/chat/ActorAvatar.vue'
import SchemeCard from '@/components/runtime/workbench/chat/SchemeCard.vue'
import FoldSection from '@/components/runtime/workbench/chat/FoldSection.vue'
import ToolPillList from '@/components/runtime/workbench/chat/ToolPillList.vue'
import WorkflowRunCard from '@/components/runtime/workbench/chat/WorkflowRunCard.vue'
import ResultTextBox from '@/components/runtime/workbench/chat/ResultTextBox.vue'
import ChoiceChips from '@/components/runtime/workbench/chat/ChoiceChips.vue'
import type { ToolPillItem } from '@/components/runtime/workbench/chat/toolPill'
import WorkbenchCitations from '@/components/runtime/workbench/WorkbenchCitations.vue'
import FileCard from '@/components/runtime/FileCard.vue'
import PreprocessResultBox from '@/components/runtime/PreprocessResultBox.vue'

const props = withDefaults(
  defineProps<{
    /** 本轮助手消息（投影主体的宿主轮） */
    turn: ChatTurn
    /** 参与者卡片（已投影，直播 / 回放同一份） */
    card: ParticipantCard
    /** 是否工作台皮肤：决定错误深链、引用与展示文本口径 */
    workbench: boolean
    /** 所属方案是否还没全部结束（= 壳层 actorHoldOf）；为 true 时工作流正文先四行 */
    hold: boolean
    /** 当前选项是否可点（= 壳层 choiceClickable，整会话规则） */
    choiceEnabled: boolean
    /** 是否有模型配置权限：无权限时不渲染「去绑定模型」深链 */
    canConfigModel?: boolean
    /** 是否有对话路由配置权限：无权限时不渲染「去配置对话路由」深链 */
    canConfigReception?: boolean
    /** 「去配置对话路由」深链地址；由壳层按当前入口方案拼好 */
    receptionConfigTo?: string
    /** 页签是否在前台：失活时停思考秒表（只停表，不改轮次、不收起折叠） */
    pageActive?: boolean
    /** 这条助手轮前面最近的用户附件（壳层回溯好传入）；只用于入口预处理排序 */
    beforeAttachments?: UserAttachmentView[]
  }>(),
  {
    canConfigModel: false,
    canConfigReception: false,
    receptionConfigTo: '',
    pageActive: true,
    beforeAttachments: () => []
  }
)

const emit = defineEmits<{
  /** 用户点了停等 / 询问选项；回传原始 option，由壳层走断点续作 */
  choose: [option: ParticipantChoiceOption]
  /** 用户点了平台追加的「其它 / 自填」行；壳层只聚焦输入，不提交 */
  other: []
}>()

/** 正文单行高度（px）：与 `.chat.is-workbench .md.answer` 的 14px × 1.6 一致，改字号同步改这里 */
const LINE_HEIGHT_PX = 14 * 1.6
/** 两次实测之间的最小间隔（毫秒）：流式期降频，攒到下一个间隔再统一量 */
const MEASURE_MIN_GAP_MS = 80
/** 工作流头像齿轮的线稿色：固定近黑，与消息流里的名称文字同色系，不走 lookOf 的彩色 */
const WORKFLOW_GEAR_COLOR = WORKFLOW_AVATAR_COLOR

/** 用户点过的折叠覆盖（键 = 轮 key + 区段后缀）；未写入时用默认态 */
const foldOverride = reactive<Record<string, boolean>>({})
/** 工具详情是否展开（键 = 胶囊 key） */
const toolDetailOpen = reactive<Record<string, boolean>>({})
/** 正文是否被用户展开：中间过程正文默认四行，点「展开全文」去掉限高 */
const expanded = ref(false)
/** 正文是否被四行截断的实测结论；展开后仍记「本可截断」，收起入口才不会丢 */
const overflow = ref(false)

/** 这段正文上次实测时的内容长度；长度没变就不可能溢出，直接跳过读布局 */
let measuredLen = -1
/** 上一次真正测量的时间戳（毫秒） */
let measuredAt = 0
/** 已排队的实测帧号；0 表示当前没有排队 */
let measureFrame = 0

/** 思考正文 / 结果正文的 DOM 句柄：单卡各一份，滚动与实测都只读这一层 */
let thinkTextEl: HTMLElement | null = null
let answerTextEl: HTMLElement | null = null
/** 上一帧仍处于「思考这一拍」；用于识别刚收起的那一拍 */
let thinkWasOpen = false
/** 本次更新后是否要跟滚中间过程 */
let pendingScroll = false

/** 结束复位帧号；0 表示没有排队 */
let finishFrame = 0

/** 思考耗时的刷新时钟。只在本卡仍在生成时走表，回放不编造秒数 */
const thoughtNow = ref(Date.now())
/** 这一位思考开始的时刻（只记本次页面上看见它还在跑的时候） */
const thinkStartedAt = new Map<string, number>()
/** 这一位结束后冻住的毫秒，避免停表后还跟着时钟涨 */
const thinkFrozenMs = new Map<string, number>()
let thoughtTimer = 0

/** 卡片上的引用缓存：投影按内容签名重建卡片（签名变了就是新对象），缓存天然随投影失效 */
const citationMemo = new WeakMap<ParticipantCard, WorkbenchCitationView[]>()

/** 卡片派生视图：思考正文 + 工具胶囊（模板每块要问多次，只算一次） */
interface CardView {
  thinkingText: string
  pills: ToolPillView[]
}

/**
 * 卡片派生视图缓存。
 *
 * <p>为什么用 WeakMap 按卡片对象缓存：投影按内容签名重建卡片（签名变了就是新对象），
 * 因此缓存天然随投影失效；而同一帧内模板对同一块会问 4 次以上「思考文本 / 工具胶囊」，
 * 每次都要遍历全部步骤并拼接全文，长思考 + 多工具时是纯浪费。
 */
const cardViewMemo = new WeakMap<ParticipantCard, CardView>()

/** 同一个参与者的键：轮 + 卡，模板 ref / 秒表 / 测量都按它对齐 */
const cardKey = computed(() => `${props.turn.key}:${props.card.key}`)
/** 这位参与者的思考折叠键。默认收起，点开才看思考和工具 */
const actorThinkKey = computed(() => `${props.turn.key}:${actorThinkPart(props.card)}`)

/**
 * 是不是工作流块（执行卡片或它的占位块）。
 *
 * <p>口径集中在 `workbenchAvatar`：有卡片快照，或角色位就是「工作流 WF」。
 *
 * @param card 参与者卡片
 * @return true 表示这一块是工作流
 */
function isWorkflowCard(card: ParticipantCard): boolean {
  return isWorkflowAvatarCard(card)
}

/**
 * 参与者头像样式：圆圈中间白底，银圈由样式画；线稿色用该套形象色。
 *
 * <p>工作流是唯一例外：头像固定白底黑齿轮，不读 lookOf（有没有保存形象都不改）。
 *
 * @param card 参与者卡片
 * @return 内联样式
 */
function participantAvatarStyle(card: ParticipantCard) {
  // 圆圈中间固定白底，描边由样式画成银圈；形象色只留给字，不再铺成方座底色
  if (isWorkflowCard(card)) {
    return {
      background: '#fff',
      color: WORKFLOW_GEAR_COLOR
    }
  }
  return {
    background: '#fff',
    color: card.look?.initialColor || '#111827'
  }
}

/**
 * 参与者名称右侧的角标：入口「对话入口」、方案「方案」、单兵专家「Agent」。
 *
 * <p>口径读配置与卡片类型，不写死展示名：
 * <ul>
 *   <li>入口块（kind=gateway）：名称是配置里的入口方案名（如 Router），角标恒为「对话入口」；</li>
 *   <li>方案（roleLabel=方案）：分流与框内汇总都标「方案」；</li>
 *   <li>整轮收口（roleLabel=最终产物）：标「最终产物」，排在全部方案卡之后；</li>
 *   <li>其余参与者（单兵专家 / 方案内部子专家）：标「Agent」，不允许空着。</li>
 * </ul>
 *
 * @param card 参与者卡片
 * @return 角标文案；不会返回空串
 */
function actorRoleBadge(card: ParticipantCard): string {
  if (card.kind === 'gateway') {
    return '对话入口'
  }
  if (card.roundDeliverable || card.roleLabel === '最终产物') {
    return '最终产物'
  }
  if (card.roleLabel === '方案') {
    return '方案'
  }
  // 工作流块：角标固定「工作流 WF」，与卡片里的执行流是同一件事
  if (card.workflow || card.roleLabel === WORKFLOW_ROLE_LABEL) {
    return WORKFLOW_ROLE_LABEL
  }
  return 'Agent'
}

/**
 * 这位参与者的思考折叠键后缀（轮 key 由 {@link actorThinkKey} 再拼）。
 *
 * @param card 参与者卡片
 * @return 折叠区段名
 */
function actorThinkPart(card: ParticipantCard): string {
  return `actor-think:${card.key}`
}

/**
 * 折叠区里要展示的说明行：网关判定句 / 方案分流句已经在内容框里出现过，
 * 不再进折叠（否则同一句出现两次）。
 *
 * @param card 参与者卡片
 * @return 折叠区说明行
 */
function actorFoldLines(card: ParticipantCard): string[] {
  const lines = Array.isArray(card.lines) ? card.lines : []
  if (card.kind === 'gateway' || card.roleLabel === '方案') {
    return []
  }
  return lines
}

/**
 * 内容框里的本步结果句：网关写判定（分发给谁），方案写分给谁。
 * 专家用自己的正文 / 报错 / 文件卡，不走这里（返回空数组）。
 *
 * @param card 参与者卡片
 * @return 可见结果行；没有时为空数组
 */
function actorResultLines(card: ParticipantCard): string[] {
  const lines = Array.isArray(card.lines) ? card.lines : []
  if (card.kind !== 'gateway' && card.roleLabel !== '方案') {
    return []
  }
  return lines
}

/**
 * 读这张卡上已经投影好的工具结果，抽出出处。
 *
 * <p>只在工作台皮肤计算。管理端运行不走这条模板，这里再挡一次，避免共用组件时带出去。
 * 直播和回放都读 card.steps，不另拼一层、不改 turns。
 *
 * @param card 参与者卡片
 * @return 可点来源；没有时为空数组
 */
function workbenchCitations(card: ParticipantCard): WorkbenchCitationView[] {
  if (!props.workbench) {
    return []
  }
  const cached = citationMemo.get(card)
  if (cached) {
    return cached
  }
  const items = citationsFromToolSteps(card.steps)
  citationMemo.set(card, items)
  return items
}

/**
 * 正文已经落地时，引用贴在结果框里。
 *
 * @param card 参与者卡片
 * @return true 表示画在结果框内部
 */
function showAnswerCitations(card: ParticipantCard): boolean {
  return !!card.answer && workbenchCitations(card).length > 0
}

/**
 * 这一步已经结束，但没有成文正文。出处仍挂在原卡上，不插进还在生成的过程里。
 *
 * @param card 参与者卡片
 * @return true 表示在结果框外单独挂引用
 */
function showOrphanCitations(card: ParticipantCard): boolean {
  if (card.answer || (card.workflow?.answer || '') || card.pending === true) {
    return false
  }
  return workbenchCitations(card).length > 0
}

/**
 * 计算一张卡片的思考正文（只有推理，不含工具调用）。
 *
 * @param card 参与者卡片
 * @return 推理文本；没有时为空
 */
function buildThinkingText(card: ParticipantCard): string {
  const steps = Array.isArray(card.steps) ? card.steps : []
  const parts: string[] = []
  for (const step of steps) {
    if (step.type !== 'reasoning') {
      continue
    }
    const text = (step.text || '').trim()
    if (text) {
      parts.push(text)
    }
  }
  // 专家块还会把依据句放进 lines；网关 / 方案的话已经在内容框里，不重复
  return [...actorFoldLines(card), ...parts].join('\n').trim()
}

/**
 * 取（或首次计算）一张卡片的派生视图。
 *
 * @param card 参与者卡片
 * @return 思考正文与工具胶囊
 */
function cardViewOf(card: ParticipantCard): CardView {
  const hit = cardViewMemo.get(card)
  if (hit) {
    return hit
  }
  const view: CardView = {
    thinkingText: buildThinkingText(card),
    pills: aggregateToolPills(card.key, Array.isArray(card.steps) ? card.steps : [])
  }
  cardViewMemo.set(card, view)
  return view
}

/**
 * 思考区里要展示的内容：只有推理正文，工具调用单独做胶囊、不堆进思考正文。
 *
 * @param card 参与者卡片
 * @return 推理文本；没有时为空
 */
function actorThinkingText(card: ParticipantCard): string {
  return cardViewOf(card).thinkingText
}

/**
 * 从参与者块里取工具胶囊，按工具名聚合。
 *
 * @param card 参与者卡片
 * @return 胶囊列表（按工具首次出现顺序）
 */
function actorToolPills(card: ParticipantCard): ToolPillView[] {
  return cardViewOf(card).pills
}

/**
 * 胶囊上的一句。聚合仍按英文调用名，句子里只放中文名。
 *
 * @param pill 聚合后的胶囊
 * @return 如「已调用【知识库检索】」
 */
function toolPhrase(pill: ToolPillView): string {
  const face = `【${toolFaceName(pill.name, toolTitleByName)}】`
  if (pill.state === 'running') {
    const times = pill.total > 1 ? `（${pill.total}）` : ''
    return `正在调用${face}${times}…`
  }
  if (pill.state === 'done') {
    const times = pill.total > 1 ? `（${pill.total} 次）` : ''
    return `已调用${face}${times}`
  }
  const error = pill.firstError ? ` · ${pill.firstError}` : ''
  return `${face}：成功 ${pill.ok} / 失败 ${pill.failed}${error}`
}

/**
 * 工具胶囊组件的入参：展示句与详情先在这里算好，组件只负责画。
 *
 * @param card 参与者卡片
 * @return 可直接交给 ToolPillList 的条目
 */
function actorToolItems(card: ParticipantCard): ToolPillItem[] {
  return actorToolPills(card).map((pill) => ({
    key: pill.key,
    state: pill.state,
    label: toolPhrase(pill),
    detail: pill.detail ? presentToolDetail(pill.detail) : ''
  }))
}

/**
 * 切换某条工具胶囊的详情展开态。
 *
 * @param key 胶囊 key
 */
function toggleToolDetail(key: string) {
  toolDetailOpen[key] = toolDetailOpen[key] !== true
}

/** 聚合后的单条工具胶囊类型（纯函数返回值的元素类型，只在本组件内使用） */
type ToolPillView = ReturnType<typeof aggregateToolPills>[number]

/**
 * 思考行上的耗时。没在这次停留里看见它跑过，就不写秒数（回放没有逐步耗时）。
 *
 * @param turn 本轮
 * @param card 参与者
 * @return 毫秒；没有观察起点时为 null
 */
function observedThoughtMs(turn: ChatTurn, card: ParticipantCard): number | null {
  const key = `${turn.key}:${card.key}`
  const now = thoughtNow.value
  const running = turn.status === 'streaming' && card.pending === true
  if (running) {
    if (!thinkStartedAt.has(key)) {
      thinkStartedAt.set(key, now)
    }
    return Math.max(0, now - (thinkStartedAt.get(key) as number))
  }
  if (thinkFrozenMs.has(key)) {
    return thinkFrozenMs.get(key) as number
  }
  if (thinkStartedAt.has(key)) {
    const elapsed = Math.max(0, now - (thinkStartedAt.get(key) as number))
    thinkFrozenMs.set(key, elapsed)
    return elapsed
  }
  return null
}

/**
 * 折叠行文案。有真实耗时才带「(耗时 Xs)」。
 *
 * @return 一行标题
 */
function thoughtToggleLabel(): string {
  const elapsed = formatThoughtElapsed(observedThoughtMs(props.turn, props.card) ?? 0)
  return elapsed ? `思考与工具执行过程 (耗时 ${elapsed})` : '思考与工具执行过程'
}

/**
 * 思考区是否还在流式（＝这一步仍处在「三拍」的第一拍）。
 *
 * <p>三拍时序的唯一判定入口：思考框展开跟滚、工具胶囊是否出现、结果框是否出现，
 * 全部读这一个函数，避免三处各判各的、出现「思考还在滚，胶囊先冒出来」。
 *
 * @return true 表示仍在第一拍
 */
function actorThinkOpenByDefault(): boolean {
  return isThinkOpenByDefault(
    props.card,
    props.turn.status === 'streaming',
    props.card.pending,
    actorHasTool(props.card),
    actorHasResult(props.card)
  )
}

/** 思考区当前是否展开：用户点过就以覆盖为准，否则用默认态 */
function actorThinkOpen(): boolean {
  return foldOpenOf(foldOverride, actorThinkKey.value, actorThinkOpenByDefault())
}

/** 展开 / 收起这一位的思考区：只改显示范围，不动已产出的文字 */
function toggleActorThink(): void {
  foldOverride[actorThinkKey.value] = nextFoldValue(
    foldOverride,
    actorThinkKey.value,
    actorThinkOpenByDefault()
  )
}

/**
 * 是否有可折叠的思考。空壳和状态句已在投影里去掉，这里不再补「没有额外的思考记录」。
 *
 * <p>判定口径是「这一块**真的有推理文字**」：思考框里只画推理，
 * 工具调用在它下面单独做胶囊。因此只有工具、没有推理的步骤不画思考框
 * （否则点开是空框）；判定 / 分流这一步的判定句与分流句在内容框里，也不算思考。
 *
 * @param card 参与者卡片
 * @return true 表示画出「查看思考过程与工具调用」
 */
function actorHasThink(card: ParticipantCard): boolean {
  return actorThinkingText(card).length > 0
}

/**
 * 这一块是否已经出现工具调用 / 工具结果。
 *
 * <p>只看过程事件本身：有 tool_call 或 tool_result 就说明工具这一拍已经开始，
 * 与它的成功 / 失败无关——失败也要等思考结束后才允许出红色胶囊。
 *
 * @param card 参与者卡片
 * @return true 表示已有工具事件
 */
function actorHasTool(card: ParticipantCard): boolean {
  const steps = Array.isArray(card.steps) ? card.steps : []
  return steps.some((step) => step.type === 'tool_call' || step.type === 'tool_result')
}

/**
 * 这一块的**真实结果**是否已经出现。
 *
 * <p>网关 / 方案（分流那条）的结果是内容框里那句判定句 / 分流句，写在 lines 里；
 * 专家与汇总的结果是正文 / 报错 / 产物。专家折叠区的「依据」行不算结果——
 * 它一直在，若算结果，思考框就永远不会展开。
 *
 * @param card 参与者卡片
 * @return true 表示已有真实结果
 */
function actorHasResult(card: ParticipantCard): boolean {
  if (card.kind === 'gateway' || (card.kind === 'actor' && card.roleLabel === '方案')) {
    return actorResultLines(card).length > 0
  }
  const answer = (card.answer || '').trim()
  const errors = Array.isArray(card.errors) ? card.errors : []
  const files = Array.isArray(card.files) ? card.files : []
  return !!answer || errors.length > 0 || files.length > 0
}

/**
 * 这一步是否仍在思考（且真有可见推理）：只有此时才禁止出工具胶囊与结果框。
 *
 * @return true 表示思考框正在流式输出
 */
function actorThinkingBeat(): boolean {
  return actorHasThink(props.card) && actorThinkOpenByDefault()
}

/**
 * 内容框是否处于「中间过程」形态：最高四行、框内滚动。
 *
 * <p>口径收在 {@link isMidProcessBox} 里（纯函数，有单测）：
 * 流式过程中所有块都限高；本轮结束后只有整轮**最后一条**（`card.isFinal`）放开、
 * 完整展开，其余已经写完的块仍保持四行——不要把某位子专家放大成终局。
 *
 * @return true 表示需要限高
 */
function actorBoxMidProcess(): boolean {
  return isMidProcessBox(props.card, props.turn.status === 'streaming')
}

/**
 * 这段正文是否按四行截断。
 *
 * <p>入口、专家、分流的普通正文停在四行里滚。方案框里的中间过程即使带表也先收四行，
 * 点开后再整表放进结果框。本轮最后一份（投影 isFinal）和综答里的表不收四行。
 * 用户点了展开之后，限高类名去掉，这里对仍限高的块返回 true，好让收回箭头留在页面上。
 *
 * @param turn 本轮助手消息（签名与调用处对齐，限高不看整轮状态）
 * @param card 参与者卡片
 * @return true 表示这段文字处在四行口径下
 */
function bodyTextLimited(turn: ChatTurn, card: ParticipantCard): boolean {
  const text = card.answer || ''
  if (!text.trim()) {
    return false
  }
  // 综答随流式全文往下长。入口、专家、分流的普通正文仍停在四行里滚。
  if (isUnlimitedAnswerBox(card)) {
    return false
  }
  // 有表时只放开本轮最后一份。中间过程仍限四行，避免方案框里的子结果整表铺开。
  if (answerClampOf(text) === 'table' && !tableFoldedByDefault(card, turn.status === 'streaming')) {
    return false
  }
  return true
}

/**
 * 正文节点的限高类名。展开时去掉 is-clamped，高度立刻放开；收起时加回来。
 *
 * @return 类名表
 */
function bodyTextClass(): Record<string, boolean> {
  const text = props.card.answer || ''
  const limited = bodyTextLimited(props.turn, props.card) && !expanded.value
  // 中间过程的表先走四行；展开后，以及本轮最后一份，才进框内滚动，两套类名不要叠在一起。
  return {
    'is-clamped': limited,
    'is-table-scroll': answerClampOf(text) === 'table' && !limited
  }
}

/**
 * 正文长度：只用来判断「文字有没有变」，不是展示值。文件名不算，避免文件一到就误判成正文变了。
 *
 * @param card 参与者卡片
 * @return 正文字符数
 */
function boxContentLength(card: ParticipantCard): number {
  if (card.workflow) {
    return (card.workflow.answer || '').length
  }
  return (card.answer || '').length
}

/**
 * 这一段正文现在该出「展开全文」还是「收起」。
 *
 * <p>没超过四行时是 none：按钮不出现，避免点了高度不变。
 *
 * @return none / expand / collapse
 */
function bodyExpandActionOf() {
  return bodyExpandAction(
    bodyTextLimited(props.turn, props.card),
    expanded.value === true,
    overflow.value === true
  )
}

/**
 * 展开 / 收起这张卡的中间过程框：只影响这张卡，不改其它参与者。
 */
function toggleMidBoxExpand() {
  // 限高加在正文节点的 is-clamped 上：这里一翻转，四行和全文就会换过来
  expanded.value = expanded.value !== true
}

/**
 * 内容框是否要画：必须已有本步**真实结果**，且思考这一拍已经结束。
 *
 * <p>不再用 `pending` 兜底：进行中挂一块空结果框（还带闪烁光标），正是要修掉的
 * 「结果框提前出现」。网关与方案的话就是那句结果，必须有框；专家有正文 / 报错 / 产物才出框。
 *
 * @return true 表示画出内容框
 */
function actorHasBubble(): boolean {
  // 工作流块画的是执行卡片，不再套一层内容框（否则会出现「卡片 + 空框」两层）
  if (props.card.workflow) {
    return false
  }
  if (actorThinkingBeat()) {
    return false
  }
  const errors = Array.isArray(props.card.errors) ? props.card.errors : []
  return !!(
    props.card.answer ||
    actorResultLines(props.card).length ||
    errors.length ||
    // 文件在框外单独一行，不因为有文件就再套一只空正文框
    // 只有一组可点选项、没有正文的块也是「本步结果」：不把它判成空壳，否则用户看不到按钮
    (props.card.choice && props.card.choice.options.length)
  )
}

/**
 * 思考行什么时候出现。
 *
 * <p>已经有推理就一直在。还在跑、结果还没落地时也先把这一行摆上，
 * 不等整轮结束才出现。结束后如果从头到尾没有推理，就不留空行。
 *
 * @return true 表示画出折叠行
 */
function showThoughtToggle(): boolean {
  // 工作流卡的思考已经挂在各节点内部：卡外不再出一个空思考折叠行
  if (props.card.workflow) {
    return false
  }
  if (actorHasThink(props.card)) {
    return true
  }
  return props.card.pending === true && props.turn.status === 'streaming' && !actorHasResult(props.card)
}

/**
 * 方案框里要排的成员：直接子卡在前，汇总在最后。
 *
 * <p>子卡是投影按 begin 收好的，这里只决定框内顺序，不再重算谁属于哪个方案。
 *
 * @param card 当前这张卡
 * @return 框内成员；没有子卡也没有汇总时为空
 */
function schemeMembers(card: ParticipantCard): ParticipantCard[] {
  const children = Array.isArray(card.children) ? card.children : []
  return card.summary ? [...children, card.summary] : children
}

/**
 * 网关卡里的识别条目。按这一轮用户附件的发送顺序排，和回放同一份拆分。
 *
 * @return 识别框条目；没有预处理时为空
 */
function gatewayPreprocessItems() {
  const shown = props.card.preprocess
  if (!shown) {
    return []
  }
  return orderPreprocessItems(shown.items || [], props.beforeAttachments)
}

/** 「去配置对话路由」的实际地址：壳层传具体方案的抽屉，其它入口退回列表 */
const receptionConfigHref = computed(
  () => (props.receptionConfigTo || '').trim() || '/orchestration/multi-agent'
)

/**
 * 该条错误是否该给「去配置对话路由」。
 *
 * <p>只认分类为 route 的可行动错误；手动停止 / 中断留下的「判路由失败：已中断」
 * 不应再引导去改配置——那是本轮被打断，不是配置缺失。
 *
 * @param text 原始错误文案
 * @return true 表示渲染路由配置深链
 */
function showReceptionConfigLink(text: string): boolean {
  if (!props.workbench || !props.canConfigReception) {
    return false
  }
  return isRouteConfigActionable(text)
}

/**
 * 该条错误是否该给「去绑定模型」。
 *
 * <p>与路由同一口径：中断不是模型没配，保留原话即可，不再挂深链。
 *
 * @param text 原始错误文案
 * @return true 表示渲染模型中心深链
 */
function showModelConfigLink(text: string): boolean {
  if (!props.workbench || !props.canConfigModel) {
    return false
  }
  return isModelConfigActionable(text)
}

/**
 * 复制结果框里用户看见的正文（检索 JSON 已收成引用文本）。
 *
 * @param raw 卡片上的原文
 */
async function copyResult(raw: string) {
  await copyAnswerText(raw, props.workbench)
}

/**
 * 结果正文节点 ref：用来量这段文字有没有超过四行。文件行不进这个节点。
 *
 * @param el DOM 节点或卸载时的 null
 */
function answerTextRef(el: unknown) {
  answerTextEl = el instanceof HTMLElement ? el : null
}

/**
 * 思考正文 ref：滚动发生在这四行文字里，标题行不跟着滚。
 *
 * @param el DOM 节点或卸载时的 null
 */
function actorFoldRef(el: unknown) {
  thinkTextEl = el instanceof HTMLElement ? el : null
}

/**
 * 把可滚动元素滚到底，但只在用户仍停在底部附近时执行。
 *
 * @param el 可滚动容器
 */
function followScrollable(el: HTMLElement) {
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  // 离开底部超过一行的距离，视为用户正在回看历史，不再拽回底部
  if (distance > 48) {
    return
  }
  el.scrollTop = el.scrollHeight
}

/**
 * 中间过程在框内跟到最新一行（思考文字 / 收在四行里的正文）。
 *
 * <p>顺带识别「思考这一拍刚结束」（展开 → 收起）：折叠动画开始前先把内框滚到底，
 * 让收起时看到的是最后一行，而不是被裁在中段；也避免内外两层滚动互相拽位置。
 */
function followMidProcess() {
  if (!props.workbench) {
    return
  }
  // 只跟仍在流式中的轮次（与拆前壳层同一口径）：结束后由「结束复位」把思考滚回第一行，
  // 这里再跟到底会和它互相拽位置，用户展开思考时会先看到尾巴。
  if (props.turn.status !== 'streaming') {
    return
  }
  // 只跟中间过程：最终结果不限高，不需要跟滚
  if (!actorBoxMidProcess()) {
    return
  }
  if (thinkTextEl) {
    followScrollable(thinkTextEl)
  }
  // 展开后的全文不再被四行框拽到底；收在四行里时才跟最新一行
  if (answerTextEl && answerTextEl.classList.contains('is-clamped')) {
    followScrollable(answerTextEl)
  }
}

/**
 * 识别「思考这一拍刚结束」：折叠前把内框滚到底，一次布局读、每个块只发生一次。
 */
function settleJustClosedThink() {
  if (props.turn.status !== 'streaming') {
    thinkWasOpen = false
    return
  }
  if (actorThinkOpenByDefault()) {
    thinkWasOpen = true
    return
  }
  if (!thinkWasOpen) {
    return
  }
  thinkWasOpen = false
  if (thinkTextEl) {
    thinkTextEl.scrollTop = thinkTextEl.scrollHeight
  }
}

/**
 * 量一次这张卡的正文是否超过四行，决定要不要给「展开全文」。
 *
 * <p>只量正文节点，不把文件卡、终态说明算进去。展开之后 max-height 是 none，
 * 仍用「四行行高」和 scrollHeight 比，收起入口才不会丢。
 *
 * <p>降频与去重（与拆前同一口径）：行高用常量、内容长度没变就跳过读布局、
 * 流式期两次实测至少间隔 {@link MEASURE_MIN_GAP_MS}，结束态不节流。
 */
function measureMidBoxOverflow() {
  if (!props.workbench) {
    return
  }
  const stamp = Date.now()
  const throttled = stamp - measuredAt < MEASURE_MIN_GAP_MS
  // 四行正文：限高加在文字上，内边距在外层气泡，不计入这四行
  const limit = BOX_MAX_LINES * LINE_HEIGHT_PX
  if (!bodyTextLimited(props.turn, props.card)) {
    // 最终结果不限高；没有正文节点也不给展开按钮
    if (overflow.value) {
      overflow.value = false
    }
  } else if (answerTextEl) {
    // 内容长度没变：上一拍量过且结论没变，不必再读一次布局
    const contentLength = boxContentLength(props.card)
    if (measuredLen !== contentLength && !(throttled && props.turn.status === 'streaming')) {
      // 四行按文字节点的 scrollHeight 和四行行高比，不读整只气泡
      const overflowing = answerTextEl.scrollHeight - limit > 2
      measuredLen = contentLength
      // 判定值不变就不写回响应式，避免额外一次重渲染
      if (overflow.value !== overflowing) {
        overflow.value = overflowing
      }
    }
  }
  if (!throttled) {
    measuredAt = stamp
  }
}

/**
 * 下一帧统一跟滚 + 实测：一帧最多读一次布局。
 *
 * <p>由 {@link onUpdated} 在增量真正写进 DOM 之后、以及挂载时安排；
 * 不随每个 delta 同步读布局（长会话会明显掉帧）。
 */
function scheduleMeasure() {
  if (measureFrame) {
    return
  }
  measureFrame = requestAnimationFrame(() => {
    measureFrame = 0
    settleJustClosedThink()
    followMidProcess()
    measureMidBoxOverflow()
  })
}

/**
 * 流式结束时把思考文字滚回第一行。
 *
 * <p>正文不再在框内滚动，所以这里不碰正文节点。
 */
function tickFinishReset() {
  finishFrame = 0
  if (thinkTextEl) {
    thinkTextEl.scrollTop = 0
  }
}

/**
 * 识别「流式刚结束」：下一帧把这张卡的思考文字滚回第一行。
 * 不自动收起思考面板，用户收起过的卡也不会被这里重新打开。
 */
function scheduleFinishReset() {
  if (!props.workbench || finishFrame) {
    return
  }
  finishFrame = requestAnimationFrame(tickFinishReset)
}

/** 本轮由「流式中」变为结束：与本卡自己的投影无关，只做显示层复位 */
watch(
  () => props.turn.status,
  (now, before) => {
    if (before === 'streaming' && now !== 'streaming') {
      scheduleFinishReset()
    }
  }
)

/**
 * 思考秒表：只在本卡仍在生成且页签在前台时走表。
 * 页签失活只停表，不改轮次、不收起折叠；回放没有 pending 卡，不会启动。
 */
watch(
  () => props.pageActive !== false && props.turn.status === 'streaming' && props.card.pending === true,
  (live) => {
    if (live && !thoughtTimer) {
      thoughtNow.value = Date.now()
      thoughtTimer = window.setInterval(() => {
        thoughtNow.value = Date.now()
      }, 400)
      return
    }
    if (!live && thoughtTimer) {
      window.clearInterval(thoughtTimer)
      thoughtTimer = 0
      // 本轮真结束才补一次时刻。只是页签失活时不写，避免后台再触发一轮渲染
      if (props.pageActive !== false) {
        thoughtNow.value = Date.now()
      }
    }
  },
  { immediate: true }
)

// 每次组件更新（流式增量 / 展开 / 排版）后安排一次跟滚 + 实测；回放挂载也补一次
onUpdated(scheduleMeasure)
onMounted(scheduleMeasure)

// 卸载时取消未执行的帧，避免回调在已销毁组件上读 DOM
onBeforeUnmount(() => {
  if (measureFrame) {
    cancelAnimationFrame(measureFrame)
    measureFrame = 0
  }
  if (finishFrame) {
    cancelAnimationFrame(finishFrame)
    finishFrame = 0
  }
  if (thoughtTimer) {
    window.clearInterval(thoughtTimer)
    thoughtTimer = 0
  }
  thinkStartedAt.clear()
  thinkFrozenMs.clear()
  answerTextEl = null
  thinkTextEl = null
})
</script>

<style scoped src="../../../../styles/chat-markdown.css"></style>
<style scoped src="../../../../styles/workbench-chat.css"></style>

<style scoped>
/* ===== 参与者块：顶行（头像 + 名称 + 角标）+ 主体 ===== */
.wb-actor {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: stretch;
}

/* 头像 + 名称 + 角标同一行垂直居中；下方内容缩进到名称列 */
.wb-actor-topline {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.wb-actor-main {
  min-width: 0;
  /* 同一轮里思考框、结果框、工作流卡共用这一档宽度，不再按每张卡的字数各缩各的 */
  width: min(100%, 640px);
  /* 36px 头像 + 12px 间距，与名称列左缘对齐 */
  margin-left: 48px;
  max-width: min(100%, 640px);
}

.wb-actor-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.wb-actor-name {
  font-size: 14px;
  font-weight: 650;
  color: #1f2430;
}

.wb-actor-role {
  padding: 0 6px;
  border-radius: 999px;
  background: #f4f3ff;
  color: #635bff;
  font-size: 12px;
  line-height: 18px;
}

/* 工作流角标：演示稿的紫色标签底（只在工作流块上加，不影响专家 / 方案 / 入口） */
.wb-actor-role.is-wf {
  background: #f3e8ff;
  color: #7e22ce;
  border: 1px solid #d8b4fe;
}

/* 框内的本步结果句（网关判定 / 方案分流）：内容框里的第一段正文，
   不再自带边框 / 底色 / 缩进——它已经在气泡内，不能看起来像另一条提示 */
.wb-actor-result {
  margin: 0;
  color: #1f2937;
  font-size: 14px;
  line-height: 1.6;
}

/* 结果句后面还有正文（方案有真汇总）时留一点间距，读起来是同一块的「分流 + 结论」 */
.wb-actor-result + .md.answer,
.wb-actor-result + .wb-soft-error {
  margin-top: 10px;
}

/* 网关判定 / 方案分流的「过程气泡」：只有分流句、没有正文答案，降回过程档 */
.chat.is-workbench .answer-block.is-process-bubble {
  background: var(--ah-input-fill, #f5f7fa);
  border-color: var(--ah-border-soft, #f2f6fc);
}

/* 思考正文：固定四行，超出在这四行里面滚动，不把整段铺到页面上。
   折叠框本体（标题行 / 动画 / 底色）由 FoldSection 提供，这里只画插槽内容 */
.wb-actor-thinking {
  margin: 0;
  padding: 0 12px 12px;
  max-height: calc(4 * 1.6em + 12px);
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.6;
  font-family: ui-monospace, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 没有正文时的终态说明：框外一行，不装进四行正文框 */
.chat.is-workbench .wb-terminal-note {
  margin: 8px 0 0;
  max-width: min(100%, 640px);
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
}

/*
 * 结果正文：默认四行，框内滚动。展开后去掉 is-clamped，高度回到全文。
 * 四行 = 4 × 1.6em。14px / 1.6 与 LINE_HEIGHT_PX 是同一口径。
 */
.chat.is-workbench .md.answer.is-clamped {
  max-height: calc(4 * 1.6em);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}
</style>
