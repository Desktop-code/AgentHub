<template>
  <!--
    工作台三栏通顶：中栏历史会话 + 右栏沉浸聊天（无顶栏横幅）。
    手动指定线路改走输入框 @，与 PUT .../route 同语义。
  -->
  <div class="workbench">
    <TrialSessionList
      v-show="sessionsVisible"
      class="wb-sessions"
      variant="workbench"
      :groups="historyGroups"
      :reveal-token="historyRevealToken"
      :active-id="session?.id"
      :can-create="ready"
      :can-run="true"
      :session="session"
      :sending="sending"
      :resuming="resuming"
      :history-loading="historyLoading"
      :has-more="historyHasMore"
      :loading-more="historyLoadingMore"
      :is-temp="isTempSession"
      :is-running="isRowRunning"
      :title-of="sessionTitle"
      :subtitle-of="sessionSubtitle"
      :tooltip-of="sessionTooltip"
      @create="newSession"
      @clear="clearCurrent"
      @open="openHistory"
      @command="onSessionCommand"
      @load-more="loadMoreHistory"
      @toggle-collapse="sessionsVisible = false"
    />

    <!-- 收起后：左上角两个悬浮图标（上=展开历史栏，下=新建对话），不占布局宽度 -->
    <div v-if="!sessionsVisible" class="wb-rail-actions">
      <button
        type="button"
        class="wb-rail-btn"
        title="展开历史会话"
        aria-label="展开历史会话"
        @click="sessionsVisible = true"
      >
        <el-icon><Expand /></el-icon>
      </button>
      <button
        type="button"
        class="wb-rail-btn"
        title="新建对话"
        aria-label="新建对话"
        :disabled="!ready || sending || resuming"
        @click="newSession"
      >
        <el-icon><Plus /></el-icon>
      </button>
    </div>

    <section class="wb-main">
      <!--
        主列页标题行：会话身份，不是第二条顶栏。
        左栏展开 / 收起都保留；收起后仍能一眼看出当前在哪条会话里。
        右侧菜单承接原先只挂在左栏的「清空当前 / 重命名 / 删除」，收起后仍有入口；
        左栏展开时列表行内菜单保持原样，不重复摆成一套醒目按钮。
      -->
      <header class="wb-head" :class="{ 'is-collapsed': !sessionsVisible, 'has-inspect': canInspect }">
        <h1 class="wb-head-title" :title="title">{{ title }}</h1>
        <el-dropdown
          v-if="hasCurrentSession"
          class="wb-head-actions"
          trigger="click"
          :disabled="sessionBusy"
          @command="onHeadingCommand"
        >
          <button
            type="button"
            class="wb-head-more"
            title="当前会话操作"
            aria-label="当前会话操作"
            :disabled="sessionBusy"
          >
            <el-icon><MoreFilled /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="rename">重命名</el-dropdown-item>
              <el-dropdown-item command="clear">清空当前</el-dropdown-item>
              <el-dropdown-item command="remove" divided class="wb-head-remove">删除会话</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <!--
        右上角执行明细触发按钮：位置与浮层共用同一套锚点常量。
        状态与耗时直接写在按钮上，不必打开浮卡也能看出「这轮在跑 / 跑完了、花了多久」。
      -->
      <button
        v-if="canInspect"
        ref="inspectButtonEl"
        type="button"
        class="wb-inspect"
        :class="{ 'is-running': inspectRunning }"
        :style="inspectButtonStyle()"
        :aria-expanded="detailOpen"
        :aria-controls="detailOpen ? INSPECT_FLOAT_ID : undefined"
        :aria-label="`执行明细：${inspectButtonText}`"
        title="执行明细"
        @click="toggleInspect"
      >
        <span class="wb-inspect-dot" aria-hidden="true" />
        <span class="wb-inspect-text">{{ inspectButtonText }}</span>
      </button>

      <!-- 未就绪：说明横幅保留，不被空状态盖住；输入仍禁用 -->
      <el-alert
        v-if="!ready && !configLoading"
        class="wb-alert"
        type="warning"
        show-icon
        :closable="false"
      >
        <template #title>{{ reason || '对话路由尚未就绪，暂时不能开始新对话' }}</template>
        <div class="wb-alert-body">
          <span>对话路由就绪前输入区已禁用；左侧历史会话仍可查看。</span>
          <router-link v-if="canConfigReception" :to="receptionConfigPath">去配置对话路由</router-link>
          <router-link v-if="canConfigModel" to="/access/models">去模型中心</router-link>
        </div>
      </el-alert>

      <p v-if="displayError && isSoftUnavailable" class="wb-soft-banner">{{ displayError }}</p>
      <el-alert
        v-else-if="displayError"
        class="wb-alert"
        type="warning"
        show-icon
        :closable="false"
      >
        <template #title>{{ displayError }}</template>
        <div
          v-if="(errorKind === 'model' && canConfigModel) || (errorKind === 'route' && canConfigReception)"
          class="wb-alert-body"
        >
          <router-link v-if="errorKind === 'model' && canConfigModel" to="/access/models">去绑定模型</router-link>
          <router-link v-if="errorKind === 'route' && canConfigReception" :to="receptionConfigPath">
            去配置对话路由
          </router-link>
        </div>
      </el-alert>

      <TrialChildPlanBar
        v-if="childPending"
        class="wb-child-plan"
        :pending="childPending"
        :deciding="childDeciding"
        @decide="decideChildPlan"
      />

      <ChatRenderBoundary>
        <ChatPanel
          class="wb-chat"
          skin="workbench"
          :thread-loading="threadLoading"
          :draft-key="draftKey"
          :route-hint="routeName"
          :entry-name="schemeName"
          :route-candidates="atRouteCandidates"
          :selected-route="pendingForceRoute"
          :can-pick-route="canPickRoute"
          :quick-prompts="QUICK_PROMPTS"
          :turns="turns"
          :sending="sending"
          :detached="detached"
          :can-send="canSend"
          :can-stop="canStopRun"
          :resuming="resuming"
          :card-running="cardRunning"
          :waiting-reply="waitingReply"
          :session-ready="canSend"
          :blocked-hint="blockedHint"
          :can-approve="canSend"
          :approving="approving"
          :can-config-model="canConfigModel"
          :can-config-reception="canConfigReception"
          :reception-config-to="receptionConfigPath"
          :page-active="pageActive"
          entry-composer
          :vision-enabled="false"
          :asr-enabled="false"
          :tts-enabled="false"
          @send="send"
          @stop="stopRun"
          @approve="approvePlan"
          @reject="rejectPlan"
          @pick-route="onPickRoute"
          @clear-route="clearPendingRoute"
          @choice-selected="chooseOption"
        />
      </ChatRenderBoundary>

      <!--
        执行明细浮层：贴着右上角「执行明细」按钮向左展开的**非模态**浮卡，有两种形态。

        三件事刻意不做：
        1) 不铺遮罩——没有全屏 / 全区半透明暗层，也就没有「点遮罩关闭」；
           对话框、会话列表、输入框等未被卡片挡住的区域照常可点可输入；
        2) 不居中、不铺满——浮层只是右上角的一个盒子（紧凑卡更窄）；
        3) 不另做标题栏——两种形态都用各自的顶栏收口（X / Esc）。

        形态由 {@link inspectCardMode} 决定：
        - 运行中 → 紧凑状态卡（标题 + 已耗时 + 每位参与者的当前动作），不弹整张泳道；
        - 已结束 → 完整泳道（泳道 + 事件列表）。

        **运行中自动展开紧凑卡**：让用户不点按钮也能看到谁在跑、跑到哪一步；
        点 X / 按 Esc 收起后，本轮与下一轮都不再自动弹回，只有再点右上角按钮才重新展开。

        半透明落在卡片本身（见 .wb-inspect-float 的 background），底下的工作台能被隐约看见。
      -->
      <transition name="wb-inspect-fade">
        <div
          v-if="detailOpen && canInspect"
          :id="INSPECT_FLOAT_ID"
          ref="inspectFloatEl"
          class="wb-inspect-float"
          :class="[`is-${inspectMode}`]"
          :style="inspectPopoverStyle(inspectMode)"
          role="dialog"
          tabindex="-1"
          :aria-label="inspectMode === 'compact' ? '执行明细（运行中）' : '执行明细'"
          @keydown.esc.prevent.stop="closeInspect"
        >
          <!-- 运行中：紧凑状态卡，状态随直播轮询原地更新 -->
          <InspectCompactCard
            v-if="inspectMode === 'compact'"
            :title="compactTitle"
            :meta="compactMeta"
            :tasks="visibleTasks"
            @close="closeInspect"
          />
          <!-- 已结束：完整泳道 + 事件列表 -->
          <RouterExecutionCard
            v-else-if="routerTrace?.model?.hasRouterTrace"
            :trace-data="routerTrace.model"
            :events="routerTrace.events"
            :spans="routerTrace.spans"
            :status="traceActive ? 'running' : 'done'"
            :error="routerTrace?.error ?? ''"
            :loading="routerTrace?.loading === true"
            :trace-id="routerTrace?.traceId ?? ''"
            :session-id="traceSessionId != null ? String(traceSessionId) : null"
            :default-expanded="true"
            :auto-collapse="false"
            closable
            @refresh="reloadRouterTrace"
            @close="closeInspect"
          />
          <p v-else class="wb-inspect-empty">本轮还没有可展示的执行明细</p>
        </div>
      </transition>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作台对话页。所属域：runtime（前端）。
 *
 * 右栏无顶栏：方案名 / 线路下拉已收掉；手动指定改走输入框 @（同 PUT 换线语义）。
 * 执行明细仅悬浮；当前线路由输入区徽标承担，聊天流不再重复一行「当前：xxx」。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Expand, Plus, MoreFilled } from '@element-plus/icons-vue'
import ChatPanel from '@/components/runtime/ChatPanel.vue'
import ChatRenderBoundary from '@/components/runtime/workbench/ChatRenderBoundary.vue'
import RouterExecutionCard from '@/components/runtime/RouterExecutionCard.vue'
import InspectCompactCard from '@/components/runtime/workbench/InspectCompactCard.vue'
import {
  INSPECT_FLOAT_ID,
  INSPECT_MODE_COMPACT,
  inspectButtonStyle,
  inspectCardMode,
  inspectClockNeeded,
  inspectCompactMeta,
  inspectCompactTitle,
  inspectLiveTasks,
  inspectPopoverStyle,
  inspectTriggerLabel,
  nextInspectOpen,
  shouldDropDoneTask,
  type InspectOverlayAction,
  type InspectRunningTask
} from '@/components/runtime/workbench/inspectOverlay'
import TrialChildPlanBar from '@/components/runtime/trial/TrialChildPlanBar.vue'
import TrialSessionList from '@/components/runtime/trial/TrialSessionList.vue'
import { useWorkbenchChat } from '@/components/runtime/workbench/useWorkbenchChat'
import { useRouterLane } from '@/composables/useRouterLane'
import { useUserStore } from '@/stores/user'
import { classifyWorkbenchError } from '@/utils/workbenchFriendlyError'

const QUICK_PROMPTS = [
  '我想咨询一个业务问题，请帮我匹配合适的专家',
  '请介绍一下你能协助处理哪些类型的请求',
  '帮我看看当前有什么需要优先处理的事项'
]

const props = withDefaults(
  defineProps<{
    /**
     * 页签是否在前台。由工作台缓存根传入。
     * false：停思考时钟、续订、追踪轮询并中断 SSE；不关执行明细、不清消息。
     */
    pageActive?: boolean
  }>(),
  { pageActive: true }
)

/** 给子树和 composable 的稳定引用；缺省或未传都视为在前台。 */
const pageActive = computed(() => props.pageActive !== false)

const user = useUserStore()

const {
  config,
  ready,
  configLoading,
  reason,
  /** 默认接待方案名（现网 Router）：入口块标题直接读它，不在前端写别名 */
  schemeName,
  /** 主列页标题：当前会话名；无会话 / 草稿时为「对话」 */
  title,
  session,
  turns,
  sending,
  resuming,
  detached,
  childPending,
  childDeciding,
  approving,
  pageError,
  canSend,
  blockedHint,
  threadLoading,
  historyGroups,
  historyRevealToken,
  historyLoading,
  historyHasMore,
  historyLoadingMore,
  traceSessionId,
  routeName,
  atRouteCandidates,
  pendingForceRoute,
  canPickRoute,
  canStopRun,
  waitingReply,
  cardRunning,
  draftToken,
  init,
  newSession,
  openHistory,
  loadMoreHistory,
  send,
  stopRun,
  chooseOption,
  pickRoute,
  clearPendingRoute,
  clearCurrent,
  onSessionCommand,
  approvePlan,
  rejectPlan,
  decideChildPlan,
  isTempSession,
  isRowRunning,
  sessionTitle,
  sessionSubtitle,
  sessionTooltip
} = useWorkbenchChat(pageActive)

const detailOpen = ref(false)
/**
 * 用户是否手动关过执行明细。
 *
 * <p>紧凑卡在运行中默认自动出现；一旦用户点 X / Esc 收起，本轮与下一轮都不能再自动弹出，
 * 只有用户再点右上角按钮才重新打开。切到另一条会话时重置，避免上一条会话的收起
 * 影响下一条会话的默认展示。
 */
let inspectDismissed = false
/**
 * 当前打开是「运行中自动展开」还是用户手动打开。
 *
 * <p>只影响收尾动作：自动展开的紧凑卡在本轮结束时自动收起，
 * 完整泳道因此仍必须由用户点按钮打开；用户手动打开的卡片不被收走。
 */
let inspectAutoOpened = false
/** 触发按钮与浮层节点：焦点在打开时交给浮层，关闭时还回按钮 */
const inspectButtonEl = ref<HTMLButtonElement | null>(null)
const inspectFloatEl = ref<HTMLElement | null>(null)
/** 左栏历史会话是否展开；收起后主区自动占满，状态与页面同生命周期（KeepAlive 下不丢） */
const sessionsVisible = ref(true)

/** 当前是否为已落库会话：草稿 / 空态没有可重命名、清空、删除的对象 */
const hasCurrentSession = computed(() => !!session.value && !isTempSession(session.value))

/**
 * 输入草稿键：真实会话按主键，临时草稿按「新建代次」区分。
 *
 * <p>草稿 id 恒为 -1，只用 id 会让两次「+ 新建对话」共用同一份未发送内容；
 * 代次由 useWorkbenchChat 在 startDraft 时自增，保证每次新建都是空输入。
 * 空字符串表示还没有任何会话（首屏加载中），此时不启用分槽。
 */
const draftKey = computed(() => {
  const current = session.value
  if (!current) {
    return ''
  }
  return isTempSession(current) ? `draft:${draftToken.value}` : `session:${current.id}`
})

/**
 * 当前会话操作是否暂不可用。
 *
 * <p>与左栏行内菜单同一道闸门（发送 / 续订中不改会话），避免前后端状态打架；
 * 只置灰标题旁的菜单按钮，不隐藏它——收起左栏后这里是唯一入口。
 */
const sessionBusy = computed(() => sending.value || resuming.value)

/**
 * 主列标题旁的当前会话菜单。
 *
 * <p>三个命令直接复用左栏同一套动作（`onSessionCommand` / `clearCurrent`），
 * 不在页面层另写一份重命名、删除或清空逻辑。
 *
 * @param command el-dropdown 命令名：rename / clear / remove
 */
function onHeadingCommand(command: string) {
  const current = session.value
  if (!current || isTempSession(current) || sessionBusy.value) {
    return
  }
  // 三个命令都作用在「点开菜单时的那条当前会话」上：把对象显式带进去，
  // 清空与左栏「清空当前」走同一个 clearCurrent(target)，回写同一行结果；
  // 重命名 / 删除与左栏共用 onSessionCommand，不另写一套。
  if (command === 'clear') {
    void clearCurrent(current)
    return
  }
  onSessionCommand(command, current)
}

const traceActive = computed(() => sending.value || resuming.value)
const { view: routerTrace, reload: reloadRouterTrace } = useRouterLane(traceSessionId, traceActive, pageActive)
const canInspect = computed(() => routerTrace.value != null)

/**
 * 本轮是否仍在跑（决定右上角按钮文案与卡片形态）。
 *
 * <p>以 {@link traceActive} 为主：它是「这一轮还在发 / 还在续订」的事实来源，
 * 而不是 trace.status —— 收尾时 trace 落库可能晚于 SSE done，用 status 会让
 * 结束态多挂一会儿「运行中」。
 *
 * <p>再或上 {@link cardRunning}：刷新那一刻本页对上一轮的接管必然归零
 * （{@code sending} / {@code resuming} 都为假），若续订没命中，这里就会写「已完成」，
 * 而执行卡仍在动（工作流节点在跑，或 MA / 单 Agent 的专家卡还在思考 / 跑工具）。
 * 卡片自己从不看这两个信号，所以必须把它自己的判断并进来，右上角才不会与卡片互相矛盾。
 *
 * <p>只并进展示判定、不并进 {@code traceActive}：追踪轮询仍按「本页是否真的在跑」开表，
 * 不为一轮已经收口的 chat trace 空转请求；展示时钟也只看 {@code traceActive}
 * （见 {@code inspectClockNeeded} 的入参），不因卡片信号单独把 250ms 表一直挂着。
 */
const inspectRunning = computed(() => traceActive.value || cardRunning.value)

/**
 * 展示用时钟：按钮上的耗时与紧凑卡的「已耗时」共用这一个 now。
 *
 * <p>间隔取 250ms：留观、滑出都以「毫秒级时刻比较」判定，1s 一拍会让 2s 停留
 * 变成 2~3s 的抖动；250ms 既够准又不至于让 tick 太重。
 */
const inspectNow = ref(Date.now())
let inspectTicker: number | undefined
function stopInspectTicker() {
  if (inspectTicker != null) {
    window.clearInterval(inspectTicker)
    inspectTicker = undefined
  }
}

/**
 * 本轮收尾时刻（epoch ms）；运行中为 null，收尾那一拍记下。
 *
 * <p>只用于把按钮上的耗时冻结在「这一轮结束」那一刻，不参与浮层开合。
 * 收尾后按钮停在「已完成 · 12.3s」，等下一轮开始再清零重算。
 */
const roundEndedAt = ref<number | null>(null)
/**
 * 由本页发起新一轮时的「发送时刻」。
 *
 * <p>只在本轮 trace 尚未回来时兜底：此时 `routerTrace.spans` 可能还是上一轮的，
 * 直接拿它的起点相减会闪出一个巨大的累计耗时。等新 trace 的 traceId 到了，
 * 仍然回到 trace 起点计时（现网口径）。
 */
const roundStartedAt = ref<number | null>(null)
/** 发送那一刻的 traceId：用于判定当前 spans 是否仍属于上一轮 */
let staleTraceId = ''
/** 上一次的 running 值：区分「收尾跳变」与「挂载时本来就没跑」 */
let wasRunning = false

/**
 * 当前应展示的卡片形态：运行中紧凑卡，其余完整泳道（打开与否由自动展开规则与用户手动开关共同决定）。
 *
 * <p>判据用 {@link traceActive} 而不是 {@link inspectRunning}：紧凑卡的任务行来自本轮 live 的
 * trace 树，只有本页真的在发 / 在续订时才有内容。刷新后仅由 {@link cardRunning} 撑起的
 * 「进行中」并没有新的 trace，弹一张空的紧凑卡只会显示「正在准备编排…」；
 * 此时按完整泳道展示已落库的明细更诚实。对改动前可达的所有状态，两者取值完全一致。
 */
const inspectMode = computed(() => inspectCardMode(traceActive.value))

/**
 * 时钟只在「本轮仍在跑」或「紧凑卡打开」时走。
 *
 * <p>为什么要带上 running：紧凑卡可能被用户收起，按钮上的耗时仍要随秒增长；
 * 若只按「浮层打开才走表」判断，收起后耗时就停在那一刻。
 *
 * <p>这里刻意传 {@link traceActive} 而不是 {@link inspectRunning}：后者可能只由
 * {@link cardRunning} 撑起，那一拍没有可增长的耗时，也不需要 250ms 一拍；
 * 若跟着它走表，一张卡在库里卡住的会话会让定时器无限空转。
 */
watch(
  () =>
    inspectClockNeeded({
      pageActive: pageActive.value !== false,
      running: traceActive.value,
      detailOpen: detailOpen.value,
      compact: inspectMode.value === INSPECT_MODE_COMPACT
    }),
  (need) => {
    stopInspectTicker()
    if (!need) {
      return
    }
    inspectNow.value = Date.now()
    inspectTicker = window.setInterval(() => {
      inspectNow.value = Date.now()
    }, 250)
  },
  { immediate: true }
)
onBeforeUnmount(stopInspectTicker)

/**
 * 紧凑卡的任务行：从 span 树平铺「真正在执行的参与者」。
 *
 * <p>不读泳道分层行：分层视图把专家收进外壳的 nested，只有最外层一行，
 * 会导致卡片停在「智能网关」不动。这里取叶子执行者，并行时有几位就列几行。
 */
const compactTasks = computed(() => inspectLiveTasks(routerTrace.value?.spans ?? []))

/**
 * 紧凑卡实际展示的行：已完成的行留观 2s（{@link InspectCompactCard} 的绿底）再滑出。
 *
 * <p>为什么在这里过滤而不是在纯函数里：留观是「展示节奏」，需要当前时刻；
 * 推导本身（谁、在干什么）保持纯粹。时钟 250ms 一拍，因此绿底能稳定停留约 2s。
 *
 * <p>兜底两条，缺一都会误导：
 * <ol>
 *   <li>span 没带 `end_time` 时，用「首次看到该行完成」的时刻当结束时刻，
 *       否则这一行永远留在列表里；</li>
 *   <li>全部行都滑出后<b>不再回退成空列表</b>——那会让卡片显示「正在准备编排…」，
 *       与「刚跑完」语义冲突。此时保留最后一份可见行，等留观期满整体收起。</li>
 * </ol>
 */
/**
 * 最后一帧有内容的行：全部滑出时用它兜底（避免「跑完了却显示正在准备」）。
 *
 * <p>用普通变量而不是 ref：它只是 computed 内部的记忆，不该参与响应式依赖，
 * 否则「读取后再写入」会让同一个 computed 反复失效。
 */
let lastVisibleTasks: InspectRunningTask[] = []
const visibleTasks = computed(() => {
  const now = inspectNow.value
  const kept = compactTasks.value.filter((task) => {
    if (!task.done) {
      return true
    }
    const doneAt = task.doneAtMs ?? firstSeenDoneAt(task.key)
    return !shouldDropDoneTask(doneAt, now)
  })
  if (kept.length) {
    lastVisibleTasks = kept
    return kept
  }
  // 全滑出：沿用最后一帧，避免「跑完了却显示正在准备」
  return lastVisibleTasks
})

/**
 * 首次看到某行「已完成」的时刻：span 缺 `end_time` 时的兜底锚点。
 *
 * <p>不清理：一轮内参与者数量有限（个位数），整轮结束后随卡片卸载一起丢掉。
 */
const firstDoneAt = new Map<string, number>()
function firstSeenDoneAt(key: string): number | null {
  const known = firstDoneAt.get(key)
  if (known != null) {
    return known
  }
  return null
}
/** 每拍记录新出现的完成行，给缺 end_time 的 span 一个起点 */
watch(
  compactTasks,
  (tasks) => {
    const now = Date.now()
    for (const task of tasks) {
      if (task.done && task.doneAtMs == null && !firstDoneAt.has(task.key)) {
        firstDoneAt.set(task.key, now)
      }
    }
  },
  { immediate: true }
)

/**
 * 紧凑卡标题：按「当前同时在跑的任务数」决定是否写「并行」。
 *
 * <p>为什么不看模板：`ROUTER_PARALLEL` 也可能只派了一位，串行模板却可能已开两位。
 * 用户要的是「现在有几路在跑」这个事实，所以并行与否只看运行中的行数。
 */
const compactTitle = computed(() => {
  const tasks = compactTasks.value
  const parallel = tasks.filter((task) => !task.done).length > 1
  return inspectCompactTitle(inspectRunning.value, tasks.length, parallel)
})

/** 本轮 trace 的最早开始时刻：运行中与结束态共用同一个计时起点 */
const inspectStartedAt = computed(() => parseTraceStart(routerTrace.value?.spans ?? []))

/**
 * 本轮耗时（毫秒）。三种来源，口径只有一个：
 *
 * <ul>
 *   <li>运行中：展示时钟 {@link inspectNow} − trace 起点（现网紧凑卡原本就这么算）；</li>
 *   <li>新一轮刚起、trace 还是上一轮：改用本轮发送时刻兜底，不闪出旧累计；</li>
 *   <li>刚收尾：收尾时刻 {@link roundEndedAt} − trace 起点，冻结成总耗时；</li>
 *   <li>历史会话（挂载时已结束）、或刷新后仅卡片信号在跑：trace 自身的结束时刻 − 起点。</li>
 * </ul>
 *
 * <p>没有另起一套秒表：运行期仍然复用 250ms 的 `inspectNow`，结束态只是把同一份
 * 起点与收尾时刻相减，避免「浮卡关掉后耗时归零」。
 */
const inspectElapsedMs = computed<number | null>(() => {
  const startedAt = inspectStartedAt.value
  /*
   * 只有本页真的在发 / 在续订时才按「运行中」口径往上算。
   * 刷新后仅由卡片信号撑起的那一拍没有可增长的本轮耗时（工作流自己的 trace 是
   * session_id=0，按会话取不到），落到下面按 trace 自身起止给出已落库的耗时。
   */
  if (traceActive.value) {
    // 新 trace 还没回来（traceId 仍是发送那一刻的旧值）：用本轮发送时刻兜底，
    // 避免把上一轮的累计耗时当成这一轮，也不让秒数先跳到 0 再跳回去
    const traceId = routerTrace.value?.traceId ?? ''
    if (roundStartedAt.value != null && traceId === staleTraceId) {
      return Math.max(0, inspectNow.value - roundStartedAt.value)
    }
    if (startedAt == null) {
      return roundStartedAt.value == null
        ? null
        : Math.max(0, inspectNow.value - roundStartedAt.value)
    }
    return Math.max(0, inspectNow.value - startedAt)
  }
  if (startedAt == null) {
    return null
  }
  const endedAt = roundEndedAt.value ?? parseTraceEnd(routerTrace.value?.spans ?? [])
  if (endedAt == null) {
    return null
  }
  return Math.max(0, endedAt - startedAt)
})

/**
 * 按钮文案：进行中 / 已完成 + 耗时，不必打开浮卡就能读到。
 *
 * <p>耗时口径：本页在跑时按运行中往上走，收尾后冻结 / 按 trace 起止给出总耗时。
 * 仅由 {@link cardRunning} 撑起的「进行中」**不给数字**：那一拍 trace 早已收口，
 * 拿它的起点相减只会得到一个越涨越大的假耗时（工作流自己的 trace 是
 * `session_id=0`，按会话取不到）。宁可不写数字，也不撒谎。
 *
 * <p>不能在这里把 {@code elapsedMs} 传 null：结束态那句「已完成 · 4.5s」正是靠
 * {@link inspectElapsedMs} 的 trace 起止算出来的。
 */
const inspectButtonText = computed(() =>
  inspectTriggerLabel(
    inspectRunning.value,
    inspectElapsedMs.value,
    cardRunning.value && !traceActive.value
  )
)

/** 紧凑卡已耗时：直接复用本轮耗时，不另算一份 */
const compactMeta = computed(() =>
  inspectCompactMeta(inspectRunning.value, inspectElapsedMs.value ?? 0)
)

/**
 * 本轮 trace 的最早开始时刻（毫秒）。
 *
 * @param spans 本轮步骤
 * @return epoch 毫秒；都解析不出时 null
 */
function parseTraceStart(spans: readonly { startTime: string | null }[]): number | null {
  let earliest: number | null = null
  for (const span of spans) {
    const ms = parseSpanTime(span.startTime)
    if (ms != null && (earliest == null || ms < earliest)) {
      earliest = ms
    }
  }
  return earliest
}

/**
 * 本轮 trace 的最晚结束时刻（毫秒）。历史会话没有 `roundEndedAt` 时用它算总耗时。
 *
 * <p>优先读 `endTime`；span 还没写结束时刻时退化为「开始 + latency」，
 * 两者都拿不到就跳过，不拿当前时间冒充结束。
 *
 * @param spans 本轮步骤
 * @return epoch 毫秒；没有任何结束信息时 null
 */
function parseTraceEnd(
  spans: readonly { startTime: string | null; endTime: string | null; latencyMs: number | null }[]
): number | null {
  let latest: number | null = null
  for (const span of spans) {
    let ms = parseSpanTime(span.endTime)
    if (ms == null && span.latencyMs != null) {
      const startedAt = parseSpanTime(span.startTime)
      if (startedAt != null) {
        ms = startedAt + span.latencyMs
      }
    }
    if (ms != null && (latest == null || ms > latest)) {
      latest = ms
    }
  }
  return latest
}

/**
 * 解析 span 时间串为 epoch 毫秒；与全站口径一致（ISO 或 `yyyy-MM-dd HH:mm:ss[.SSS]`）。
 *
 * @param raw 时间串；可空
 * @return 毫秒；解析不出时 null
 */
function parseSpanTime(raw: string | null): number | null {
  if (!raw) {
    return null
  }
  const ms = Date.parse(raw.includes('T') ? raw : raw.replace(' ', 'T'))
  return Number.isNaN(ms) ? null : ms
}

/**
 * 执行明细浮层开关：open / close / reset 三种入口都收敛到一处，
 * 避免「点 X 收起」和「自动收起」各写一段、行为走样。
 */
function setInspectOpen(action: InspectOverlayAction) {
  // 手动打开视为「重新接受自动展示」；手动关闭记住本轮 / 下一轮都不再自动弹
  if (action === 'open') {
    inspectDismissed = false
    inspectAutoOpened = false
  } else if (action === 'close') {
    inspectDismissed = true
    inspectAutoOpened = false
  } else {
    inspectDismissed = false
    inspectAutoOpened = false
  }
  detailOpen.value = nextInspectOpen(detailOpen.value, action)
}

/**
 * 本轮起止的副作用：只在 `traceActive` 的跳变上动作。
 *
 * <p><b>运行中自动展开紧凑卡</b>：一轮开始时按默认展示「谁在跑、跑到哪一步」；
 * 用户手动收起后记在 {@link inspectDismissed}，本轮不再自动弹，下一轮也不弹，
 * 只有再点右上角按钮才恢复。没有 trace 时先记为打开，trace 到达后即可见。
 *
 * <p>收尾那一拍：记下 {@link roundEndedAt} 把按钮耗时冻结成总耗时；
 * 自动展开的紧凑卡直接收起（完整泳道仍须用户点按钮）；
 * 用户手动打开的浮层保持原位，形态随 `inspectRunning` 切到完整泳道，不整张滑走。
 */
watch(
  traceActive,
  (running) => {
    // 先对齐展示时钟，避免冻结时取到上一拍的旧 now
    inspectNow.value = Date.now()
    if (running) {
      wasRunning = true
      roundEndedAt.value = null
      // 运行中默认展开紧凑执行卡（用户手动关过则保持关闭，本轮与下一轮都不再自动弹）
      if (!inspectDismissed) {
        // 用户本来就把浮层开着（手动打开的全泳道）：不记成自动展开，收尾时也不替他收起
        if (!detailOpen.value) {
          detailOpen.value = true
          inspectAutoOpened = true
        }
      }
      // 只有「本页发起」的一轮才记发送时刻：重连 / 挂载进已在跑的会话时，
      // 应以 trace 自己的起点为准，不能把计时从进页面那一刻重新开始
      if (sending.value) {
        roundStartedAt.value = inspectNow.value
        staleTraceId = routerTrace.value?.traceId ?? ''
      } else {
        roundStartedAt.value = null
        staleTraceId = ''
      }
      return
    }
    // 挂载时本来就没在跑：不记收尾时刻，历史会话按 trace 自身结束时间算耗时
    if (!wasRunning) {
      return
    }
    wasRunning = false
    roundEndedAt.value = inspectNow.value
    roundStartedAt.value = null
    staleTraceId = ''
    /*
     * 自动展开的紧凑卡在本轮结束时收起：完整泳道仍必须由用户点按钮打开，
     * 不把整张大卡在收尾时自动铺开。用户手动打开的卡片保持原样，可由其自行关闭。
     */
    if (inspectAutoOpened) {
      detailOpen.value = false
      inspectAutoOpened = false
    }
  },
  { immediate: true }
)

/** 关闭浮层：点顶栏 X、按 Esc、再点一次按钮共用这一个入口（没有遮罩，也就没有点遮罩关闭） */
function closeInspect() {
  // 键盘用户按 Esc / 点 X 后，焦点回到触发按钮，不在页面里丢失
  const shouldRestoreFocus = inspectFloatEl.value?.contains(document.activeElement) ?? false
  setInspectOpen('close')
  if (shouldRestoreFocus) {
    void nextTick(() => inspectButtonEl.value?.focus())
  }
}

/**
 * 点按钮：关着就打开并聚焦浮卡（Esc 才有一个明确的落点），开着就收起。
 *
 * <p>浮层是非模态的：只有用户主动点按钮时才把焦点交给它，不抢输入框的焦点。
 * Esc 监听挂在浮卡自身（模板上的 `@keydown.esc`），不再常驻 window，
 * 因此焦点不在浮卡里时不会误关别的界面。
 */
function toggleInspect() {
  if (detailOpen.value) {
    closeInspect()
    return
  }
  setInspectOpen('open')
  void nextTick(() => inspectFloatEl.value?.focus())
}

// pageError 在写入处已经做过友好映射（messageOf / friendlyWorkbenchError），这里不再二次改写，
// 否则「停止失败：请求超时」会被二次映射成「小憩」，把停止失败的原因吃掉
const displayError = computed(() => (pageError.value || '').trim())
/** 顶部横幅按同一套分类决定「小憩」还是保留原话 + 深链 */
const errorKind = computed(() => classifyWorkbenchError(pageError.value || ''))
const isSoftUnavailable = computed(
  () => errorKind.value === 'resting' && !displayError.value.startsWith('停止失败')
)

const canConfigReception = computed(() => user.hasPerm('ma:def:reception'))
const canConfigModel = computed(() => user.hasPerm('model:provider:list'))

/**
 * 「去配置对话路由」深链：直接打开当前入口方案的编辑抽屉，并落到模型配置 Tab。
 *
 * <p>只到 `/orchestration/multi-agent` 列表等于让用户自己再找一次；配置项里的
 * 判路由模型与 ROUTER 提示词都在现网方案抽屉的「模型配置」Tab（`tab=model`），
 * 因此带上 `schemeId` 与 Tab，不另造一套设置页。没有 schemeId 时才退回列表。
 */
const receptionConfigPath = computed(() => {
  const id = config.value?.schemeId
  return id != null && id > 0
    ? `/orchestration/multi-agent?edit=${id}&tab=model`
    : '/orchestration/multi-agent'
})

watch(traceSessionId, () => {
  // 切会话时浮层必须回到关闭：上一轮的明细不能挂在新会话上
  setInspectOpen('reset')
  // 新一轮的耗时从自己的 trace 起点重算；不在切会话时自动展开浮层
  inspectNow.value = Date.now()
  roundEndedAt.value = null
  roundStartedAt.value = null
  staleTraceId = ''
  wasRunning = traceActive.value
})

/**
 * @ 选定线路：交给逻辑层（已落库立刻 PUT；草稿只记 pending）。
 *
 * @param routeKey 候选 route_key
 */
function onPickRoute(routeKey: string) {
  void pickRoute(routeKey)
}

void init()
</script>

<style scoped>
.workbench {
  position: relative;
  display: flex;
  flex: 1;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: #fff;
}

.wb-sessions {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 260px;
  min-height: 0;
  padding: 16px 12px 12px;
  background: #fbfbfe;
  border-right: 1px solid var(--ah-border-soft, #f2f6fc);
}

/* 收起态：左上角两个悬浮图标，不挤占主区宽度；主区自然铺满 */
.wb-rail-actions {
  position: absolute;
  top: 16px;
  left: 12px;
  z-index: 9;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 悬浮小方钮：白底轻描边 + 投影，hover 变主题色；禁用态只降透明度 */
.wb-rail-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid var(--ah-border-soft, #f2f6fc);
  border-radius: 10px;
  background: #fff;
  color: var(--ah-text-secondary);
  font: inherit;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
  transition: color 0.15s, border-color 0.15s;
}

.wb-rail-btn:hover:not(:disabled) {
  color: var(--ah-primary);
  border-color: var(--el-color-primary-light-7, #d5d3ff);
}

.wb-rail-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.wb-main {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #fff;
}

/*
 * 主列页标题行：纯身份信息，不是第二条顶栏。
 * 无底色、无描边、无阴影，只靠 16px 标题建立层级；不参与消息流滚动。
 */
.wb-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
  padding: 16px 24px 0;
}

/* 收起左栏后，左上角悬浮「展开 / 新建」按钮占位 12 + 32 + 间距，标题让开，避免被图标压住 */
.wb-head.is-collapsed {
  padding-left: 56px;
}

/* 有「执行明细」按钮时右侧让位：按钮锚在 top:20 / right:24（见 inspectOverlay） */
.wb-head.has-inspect {
  padding-right: 168px;
}

.wb-head-title {
  margin: 0;
  overflow: hidden;
  color: var(--ah-text, #1f2937);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 当前会话菜单：24px 方钮，只在 hover / 聚焦时出浅底，不与标题抢视觉 */
.wb-head-more {
  display: grid;
  flex: none;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary, #6b7280);
  cursor: pointer;
}

.wb-head-more:hover:not(:disabled) {
  background: #f3f4f6;
  color: var(--ah-text, #1f2937);
}

.wb-head-more:focus-visible {
  outline: 2px solid var(--ah-primary, #635bff);
  outline-offset: 2px;
}

.wb-head-more:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.wb-inspect {
  position: absolute;
  /* top / right / height 由 inspectButtonStyle() 内联给出，与浮层共用同一套锚点 */
  z-index: 8;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid var(--ah-border-soft, #f2f6fc);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  color: var(--ah-text-secondary, #6b7280);
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  backdrop-filter: blur(4px);
  box-sizing: border-box;
}

.wb-inspect:hover {
  color: var(--ah-primary);
  border-color: var(--el-color-primary-light-7, #d5d3ff);
}

.wb-inspect:focus-visible {
  outline: 2px solid var(--ah-primary, #635bff);
  outline-offset: 2px;
}

/* 运行中：按钮文字转主色，圆点轻微呼吸；只动透明度与缩放，不位移整颗按钮 */
.wb-inspect.is-running {
  color: var(--ah-primary, #635bff);
  border-color: var(--el-color-primary-light-7, #d5d3ff);
}

.wb-inspect-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.wb-inspect.is-running .wb-inspect-dot {
  animation: wb-inspect-breathe 1.4s ease-in-out infinite;
}

/* 耗时数字等宽：秒数跳动时按钮宽度不跟着抖 */
.wb-inspect-text {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

@keyframes wb-inspect-breathe {
  0%,
  100% {
    opacity: 0.3;
    transform: scale(0.9);
  }

  50% {
    opacity: 1;
    transform: scale(1.05);
  }
}

/*
 * 执行明细悬浮卡片：右上角一个盒子，向左展开，不铺遮罩。
 *
 * 定位完全由 inspectPopoverStyle() 内联给出（top / right / width / max-height），
 * 这里只负责外观。刻意不写 position:absolute 之外的覆盖性尺寸：
 * 一旦出现 inset:0 或 width:100%，就会退回「整页浮层」的老样子。
 */
.wb-inspect-float {
  position: absolute;
  z-index: 20;
  display: flex;
  flex-direction: column;
  /* 内边距压到最小：这条宽度要留给泳道的时间轨，不留给装饰性留白 */
  padding: 8px 10px 10px;
  overflow: auto;
  /*
   * 半透明在卡片本身：底下的对话内容能隐约透出来（用户要的「隐约看见底下」）。
   * 不叠暗罩、不加整屏 blur——那会把工作台整体压暗，正是上一版的问题。
   */
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(226, 228, 239, 0.9);
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(16, 24, 40, 0.16);
  backdrop-filter: blur(10px);
  box-sizing: border-box;
}

/* 紧凑卡更矮：内容只有标题与几行状态，不需要长滚动区 */
.wb-inspect-float.is-compact {
  padding: 8px 10px;
}

.wb-inspect-empty {
  margin: 8px 0 12px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 13px;
  text-align: center;
}

/* 淡入淡出：只动透明度，不做位移与缩放，浮层出现时不会把视线拽走 */
/*
 * 进出场：进场淡入；离场「向右滑出」——浮层是从右上角按钮向左展开的，
 * 收起来时沿原路往按钮方向滑走，比单纯淡出更符合「从哪儿来回哪儿去」。
 */
.wb-inspect-fade-enter-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.wb-inspect-fade-leave-active {
  /* 与 INSPECT_EXIT_MS（180ms）同量级：用户收起时沿原路滑走 */
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.wb-inspect-fade-enter-from {
  opacity: 0;
  transform: translateX(8px);
}

.wb-inspect-fade-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

/* 浮层被程序聚焦时不画整圈默认 outline；键盘聚焦仍保留明确的紫色描边 */
.wb-inspect-float:focus {
  outline: none;
}

.wb-inspect-float:focus-visible {
  outline: 2px solid var(--ah-primary, #635bff);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .wb-inspect-fade-enter-active,
  .wb-inspect-fade-leave-active {
    transition: none;
  }

  /* 减少动效时呼吸与滑入一起停：不允许只关一种、留下另一种硬闪 */
  .wb-inspect.is-running .wb-inspect-dot {
    animation: none;
    opacity: 1;
    transform: none;
  }
}

.wb-alert {
  flex: none;
  margin: 12px 24px 0;
  max-width: 800px;
  align-self: center;
  width: calc(100% - 48px);
}

.wb-soft-banner {
  flex: none;
  align-self: center;
  width: calc(100% - 48px);
  max-width: 800px;
  margin: 12px 24px 0;
  color: #9ca3af;
  font-size: 13px;
  line-height: 1.5;
  text-align: left;
}

.wb-alert-body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
}

.wb-alert-body a {
  color: var(--ah-primary);
  text-decoration: none;
}

.wb-alert-body a:hover {
  text-decoration: underline;
}

.wb-child-plan {
  flex: none;
  margin: 8px 24px 0;
  max-width: 800px;
  align-self: center;
  width: calc(100% - 48px);
}

.wb-chat {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  min-height: 0;
  width: 100%;
  padding: 12px 24px 20px;
  box-sizing: border-box;
}
</style>

<style>
/*
 * 当前会话菜单的删除项：下拉挂到 body，需非 scoped 才能把文字染成危险色，
 * 与左栏行内菜单的删除项同一语义。
 */
.wb-head-remove {
  color: var(--el-color-danger) !important;
}

.wb-head-remove:hover,
.wb-head-remove:focus {
  color: var(--el-color-danger) !important;
  background-color: var(--el-color-danger-light-9) !important;
}
</style>
