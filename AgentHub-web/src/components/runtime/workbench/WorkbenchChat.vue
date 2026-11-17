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
      <!-- 右上角悬浮执行明细：位置与浮层共用同一套锚点常量 -->
      <button
        v-if="canInspect"
        type="button"
        class="wb-inspect"
        :style="inspectButtonStyle()"
        title="执行明细"
        @click="setInspectOpen('open')"
      >
        ⚡ 执行明细
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
          <router-link v-if="canConfigReception" to="/orchestration/multi-agent">去配置对话路由</router-link>
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
        :title="displayError"
      />

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
          :gateway-badge="gatewayBadgeText"
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
          :session-ready="canSend"
          :blocked-hint="blockedHint"
          :can-approve="canSend"
          :approving="approving"
          :vision-enabled="false"
          :asr-enabled="false"
          :tts-enabled="false"
          @send="send"
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
        用户点 X 收起后，再点按钮仍展开「当前应显示的那张」，不需要额外记忆。

        半透明落在卡片本身（见 .wb-inspect-float 的 background），底下的工作台能被隐约看见。
      -->
      <transition name="wb-inspect-fade">
        <div
          v-if="detailOpen && canInspect"
          class="wb-inspect-float"
          :class="[`is-${inspectMode}`]"
          :style="inspectPopoverStyle(inspectMode)"
          role="dialog"
          :aria-label="inspectMode === 'compact' ? '执行明细（运行中）' : '执行明细'"
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
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Expand, Plus } from '@element-plus/icons-vue'
import ChatPanel from '@/components/runtime/ChatPanel.vue'
import ChatRenderBoundary from '@/components/runtime/workbench/ChatRenderBoundary.vue'
import RouterExecutionCard from '@/components/runtime/RouterExecutionCard.vue'
import InspectCompactCard from '@/components/runtime/workbench/InspectCompactCard.vue'
import {
  INSPECT_MODE_COMPACT,
  inspectButtonStyle,
  inspectCardMode,
  inspectCompactMeta,
  inspectCompactTitle,
  inspectLiveTasks,
  inspectPopoverStyle,
  nextInspectOpen,
  shouldCloseAfterSettle,
  shouldDropDoneTask,
  type InspectOverlayAction,
  type InspectRunningTask
} from '@/components/runtime/workbench/inspectOverlay'
import TrialChildPlanBar from '@/components/runtime/trial/TrialChildPlanBar.vue'
import TrialSessionList from '@/components/runtime/trial/TrialSessionList.vue'
import { useWorkbenchChat } from '@/components/runtime/workbench/useWorkbenchChat'
import { useRouterLane } from '@/composables/useRouterLane'
import { useUserStore } from '@/stores/user'
import {
  WORKBENCH_RESTING_HINT,
  friendlyWorkbenchError,
  isWorkbenchHardUnavailable
} from '@/utils/workbenchFriendlyError'

const QUICK_PROMPTS = [
  '我想咨询一个业务问题，请帮我匹配合适的专家',
  '请介绍一下你能协助处理哪些类型的请求',
  '帮我看看当前有什么需要优先处理的事项'
]

const user = useUserStore()

const {
  ready,
  configLoading,
  reason,
  /** 默认接待方案名（现网 Router）：入口块标题直接读它，不在前端写别名 */
  schemeName,
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
  historyGroups,
  historyLoading,
  historyHasMore,
  historyLoadingMore,
  traceSessionId,
  routeName,
  atRouteCandidates,
  pendingForceRoute,
  gatewayBadgeText,
  canPickRoute,
  init,
  newSession,
  openHistory,
  loadMoreHistory,
  send,
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
} = useWorkbenchChat()

const detailOpen = ref(false)
/** 左栏历史会话是否展开；收起后主区自动占满，状态与页面同生命周期（KeepAlive 下不丢） */
const sessionsVisible = ref(true)
const traceActive = computed(() => sending.value || resuming.value)
const { view: routerTrace, reload: reloadRouterTrace } = useRouterLane(traceSessionId, traceActive)
const canInspect = computed(() => routerTrace.value != null)

/**
 * 本轮是否仍在跑（决定展示紧凑状态卡还是完整泳道）。
 *
 * <p>以 `traceActive` 为准：它是「这一轮还在发 / 还在续订」的事实来源，
 * 而不是 trace.status —— 收尾时 trace 落库可能晚于 SSE done，用 status 会让
 * 结束态多挂一会儿「运行中」。
 */
const inspectRunning = computed(() => traceActive.value)

/**
 * 展示用时钟：紧凑卡的「已耗时」随它涨，完成行的绿底停留与留观期满也按它判定。
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
 * 收尾留观：整轮跑完后仍短暂保持紧凑卡，让最后的「已完成」绿底被看见。
 *
 * <p>为什么需要它：收尾瞬间若立刻切成完整泳道，最后一位参与者的绿色停留
 * 就被泳道替换掉了，用户看到的仍是「跑完就变样」。留观期内继续显示紧凑卡，
 * 期满后浮层整体收掉（完整泳道只在用户点按钮时才看）。
 */
const settledAt = ref<number | null>(null)
/** 上一次的 running 值：区分「收尾跳变」与「挂载时本来就没跑」 */
let wasRunning = false
/** 留观期内保持紧凑卡：由 settledAt 与当前时刻算出，不另设定时器 */
const settledHold = computed(() => {
  if (settledAt.value == null) {
    return false
  }
  return !shouldCloseAfterSettle(false, settledAt.value, inspectNow.value)
})

/** 当前应展示的卡片形态：运行中（含收尾留观期）紧凑卡，其余完整泳道 */
const inspectMode = computed(() =>
  inspectCardMode(inspectRunning.value || settledHold.value)
)

/**
 * 时钟只在「紧凑卡打开」或「正在留观」时走。
 *
 * <p>为什么要带上 `settledAt != null`：收尾那一拍 `inspectMode` 可能瞬间变成完整泳道
 * （浮层仍开着时用户点了按钮），此时留观判定还需要时钟推进才能期满；
 * 只按形态判断会让 `settledHold` 永远停在 true，浮层永不收起。
 */
watch(
  () => (detailOpen.value && inspectMode.value === INSPECT_MODE_COMPACT) || settledAt.value != null,
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

/** 紧凑卡已耗时：从本轮 trace 起点算到「现在」 */
const compactMeta = computed(() => {
  const view = routerTrace.value
  const startedAt = parseTraceStart(view?.spans ?? [])
  if (startedAt == null) {
    return ''
  }
  return inspectCompactMeta(inspectRunning.value, Math.max(0, inspectNow.value - startedAt))
})

/**
 * 本轮 trace 的最早开始时刻（毫秒）。
 *
 * @param spans 本轮步骤
 * @return epoch 毫秒；都解析不出时 null
 */
function parseTraceStart(spans: readonly { startTime: string | null }[]): number | null {
  let earliest: number | null = null
  for (const span of spans) {
    const raw = span.startTime
    if (!raw) {
      continue
    }
    const ms = Date.parse(raw.includes('T') ? raw : raw.replace(' ', 'T'))
    if (!Number.isNaN(ms) && (earliest == null || ms < earliest)) {
      earliest = ms
    }
  }
  return earliest
}

/**
 * 执行明细浮层开关：open / close / reset 三种入口都收敛到一处，
 * 避免「点 X 收起」和「自动收起」各写一段、行为走样。
 */
function setInspectOpen(action: InspectOverlayAction) {
  detailOpen.value = nextInspectOpen(detailOpen.value, action)
}

/**
 * 运行中默认展开紧凑卡；整轮完成后**留观 2s**再关闭。
 *
 * <p>只在 `traceActive` 的**跳变**上动作，因此用户点 X 收起后不会被自动弹回
 * （收尾才切状态）。
 *
 * <p>为什么不在收尾那一拍立刻关：最后一位参与者刚变绿，用户需要一眼看到结果；
 * 立刻消失等于「跑完了却什么都没留住」。因此这里只标记 `settledAt`，
 * 留观是否期满由 {@link settledHold}（读 {@link inspectNow}）判定，
 * 关闭动作由下面那条 watch 在留观结束时触发 —— 判定与副作用分开，口径唯一。
 *
 * <p>`immediate` 覆盖「页面挂载时这一轮已经在跑」的场景：进页面就能看到紧凑卡。
 */
watch(
  traceActive,
  (running) => {
    if (running) {
      wasRunning = true
      settledAt.value = null
      setInspectOpen('open')
      return
    }
    // 挂载时本来就没在跑：不启动留观，也不动浮层（保持未打开）
    if (!wasRunning) {
      settledAt.value = null
      return
    }
    wasRunning = false
    // 收尾：先记时刻进入留观；保持紧凑卡，绿底可见
    settledAt.value = Date.now()
  },
  { immediate: true }
)

/**
 * 留观期满 → 收起浮层。
 *
 * <p>由 `settledHold` 从 true 变 false 触发：它本身就是「收尾后未满 2s」的判定，
 * 因此这里不需要另设 setTimeout，也不会与时钟各算一套时间。
 */
watch(settledHold, (holding, wasHolding) => {
  if (wasHolding && !holding) {
    setInspectOpen('close')
  }
})

/** 关闭浮层：点顶栏 X、按 Esc 共用这一个入口（没有遮罩，也就没有点遮罩关闭） */
function closeInspect() {
  setInspectOpen('close')
}

/**
 * Esc 关闭：浮层打开时才挂监听，关闭即摘。
 * <p>不用全局常驻监听：工作台在 KeepAlive 里，常驻会误吃其它页面的 Esc。
 */
function onInspectKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeInspect()
  }
}

watch(detailOpen, (open) => {
  if (open) {
    window.addEventListener('keydown', onInspectKeydown)
    return
  }
  window.removeEventListener('keydown', onInspectKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onInspectKeydown))

const displayError = computed(() => friendlyWorkbenchError(pageError.value || ''))
const isSoftUnavailable = computed(() => {
  const raw = pageError.value || ''
  return isWorkbenchHardUnavailable(raw) || displayError.value === WORKBENCH_RESTING_HINT
})

const canConfigReception = computed(() => user.hasPerm('ma:def:reception'))
const canConfigModel = computed(() => user.hasPerm('model:provider:list'))

watch(traceSessionId, () => {
  // 切会话时浮层必须回到关闭：上一轮的明细不能挂在新会话上
  setInspectOpen('reset')
  // 新会话若已经在跑，紧凑卡应当立刻出现（traceActive 未必跳变，单独补一次）
  if (traceActive.value) {
    setInspectOpen('open')
  }
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

.wb-inspect {
  position: absolute;
  /* top / right / height 由 inspectButtonStyle() 内联给出，与浮层共用同一套锚点 */
  z-index: 8;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  border: 1px solid var(--ah-border-soft, #f2f6fc);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  color: var(--ah-text-secondary);
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
  /* 与 INSPECT_EXIT_MS（180ms）同量级：先留观 2s，再花 0.18s 滑走 */
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

@media (prefers-reduced-motion: reduce) {
  .wb-inspect-fade-enter-active,
  .wb-inspect-fade-leave-active {
    transition: none;
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
