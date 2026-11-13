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
      <!-- 右上角悬浮执行明细：不占排版高度 -->
      <button
        v-if="canInspect"
        type="button"
        class="wb-inspect"
        title="执行明细"
        @click="detailOpen = true"
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
        />
      </ChatRenderBoundary>
    </section>

    <el-drawer v-model="detailOpen" title="执行明细" size="620px" append-to-body>
      <RouterExecutionCard
        v-if="routerTrace?.model?.hasRouterTrace"
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
        @refresh="reloadRouterTrace"
      />
      <el-empty v-else description="本轮还没有可展示的执行明细" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作台对话页。所属域：runtime（前端）。
 *
 * 右栏无顶栏：方案名 / 线路下拉已收掉；手动指定改走输入框 @（同 PUT 换线语义）。
 * 执行明细仅悬浮；当前线路由输入区徽标承担，聊天流不再重复一行「当前：xxx」。
 */
import { computed, ref, watch } from 'vue'
import { Expand, Plus } from '@element-plus/icons-vue'
import ChatPanel from '@/components/runtime/ChatPanel.vue'
import ChatRenderBoundary from '@/components/runtime/workbench/ChatRenderBoundary.vue'
import RouterExecutionCard from '@/components/runtime/RouterExecutionCard.vue'
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

const displayError = computed(() => friendlyWorkbenchError(pageError.value || ''))
const isSoftUnavailable = computed(() => {
  const raw = pageError.value || ''
  return isWorkbenchHardUnavailable(raw) || displayError.value === WORKBENCH_RESTING_HINT
})

const canConfigReception = computed(() => user.hasPerm('ma:def:reception'))
const canConfigModel = computed(() => user.hasPerm('model:provider:list'))

watch(traceSessionId, () => {
  detailOpen.value = false
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
  top: 20px;
  right: 24px;
  z-index: 8;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
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
}

.wb-inspect:hover {
  color: var(--ah-primary);
  border-color: var(--el-color-primary-light-7, #d5d3ff);
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
