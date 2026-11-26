<template>
  <!--
    调试悬浮框：叠在画布右侧，不与属性框 / 校验框共用组件。
    上、下留出空隙，露出画布顶部按钮组与底部缩放 / 调试入口；没有标题，只由右上角 X 关闭。
  -->
  <Transition name="wf-wake">
    <aside
      v-if="modelValue"
      class="wf-debug-panel"
      aria-label="调试"
      @dragover.stop.prevent
      @drop.stop.prevent
    >
      <div class="wf-debug-panel__head">
        <!-- 左上只有调试 / 记录两个页签 -->
        <div class="wf-debug-panel__tabs" role="tablist" aria-label="调试与记录">
          <button
            type="button"
            class="wf-debug-panel__tab"
            :class="{ 'is-active': pane === 'debug' }"
            role="tab"
            :aria-selected="pane === 'debug'"
            @click="pane = 'debug'"
          >
            调试
          </button>
          <button
            type="button"
            class="wf-debug-panel__tab"
            :class="{ 'is-active': pane === 'records' }"
            role="tab"
            :aria-selected="pane === 'records'"
            @click="pane = 'records'"
          >
            记录
          </button>
        </div>
        <el-tooltip content="关闭调试" placement="left">
          <button
            type="button"
            class="wf-debug-panel__close"
            aria-label="关闭调试"
            @click="close"
          >
            <el-icon><Close /></el-icon>
          </button>
        </el-tooltip>
      </div>

      <div class="wf-debug-panel__body" :class="{ 'is-records': pane === 'records' }">
        <template v-if="pane === 'debug'">
          <!-- 上面写问题、右边开始 / 续跑；不再挂工作台那种底部对话输入框 -->
          <div class="wf-debug-ask">
            <el-input
              ref="composerRef"
              v-model="draft"
              class="wf-debug-ask__input"
              :placeholder="debugPlaceholder"
              :disabled="debugComposerDisabled"
              @keydown.enter.exact.prevent="onDebugSend"
            />
            <div class="wf-debug-ask__actions">
              <!-- 输入行只有一个主按钮：开始「运行」，续跑 / 自己输入 / 等回复「发送」；运行中同一按钮变「停止」，不再另放取消 -->
              <el-button
                :type="debugStopMode ? 'danger' : 'primary'"
                class="wf-debug-ask__run"
                :loading="debugActionLoading"
                :disabled="!debugStopMode && debugComposerDisabled"
                @click="onDebugPrimary"
              >
                {{ debugActionLabelText }}
              </el-button>
            </div>
          </div>

          <div class="wf-debug-scroll">
            <!-- 失败原因放在过程区顶部：卡片再长、往下滚也仍在视野内，不再跟到卡片最底下 -->
            <WorkflowDebugErrorBar v-if="debugErrorNotice" :message="debugErrorNotice" />

            <WorkflowDebugTranscript
              :run="debugSession.run.value"
              :asked="debugSession.asked.value"
              :choice-enabled="debugChoiceEnabled"
              :page-active="pageActive !== false && pane === 'debug'"
              @choose="onDebugChoose"
              @other="focusComposer"
            />

            <WorkflowDebugHuman
              :tasks="debugSession.run.value?.humanTasks || []"
              :can-act="canAct"
              :acting="debugSession.acting.value"
              :user-id="currentUserId"
              :roles="currentRoles"
              @decide="onDebugDecide"
              @todo="goTodo"
            />
          </div>
        </template>

        <template v-else>
          <!-- 记录页签下直接列记录，不再加「调试记录」标题 -->
          <WorkflowDebugRecords
            :history="history"
            :history-loading="historyLoading"
            :selected-id="recordSession.run.value?.id"
            :detail-open="recordOpen"
            @open="openRecord"
            @back="closeRecord"
            @traces="openTraces"
          >
            <template #detail>
              <!-- 记录页同一条口径：失败原因在顶部悬浮条，不在卡片下再复述一遍 -->
              <WorkflowDebugErrorBar v-if="recordErrorNotice" :message="recordErrorNotice" />

              <WorkflowDebugTranscript
                :run="recordSession.run.value"
                :asked="recordSession.asked.value"
                :choice-enabled="recordChoiceEnabled"
                :page-active="pageActive !== false && pane === 'records'"
                @choose="onRecordChoose"
                @other="focusRecordComposer"
              />
              <WorkflowDebugHuman
                :tasks="recordSession.run.value?.humanTasks || []"
                :can-act="canAct"
                :acting="recordSession.acting.value"
                :user-id="currentUserId"
                :roles="currentRoles"
                @decide="onRecordDecide"
                @todo="goTodo"
              />
              <div v-if="recordMode === 'reply'" class="wf-debug__composer">
                <el-input
                  ref="recordComposerRef"
                  v-model="recordDraft"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  placeholder="接着说，或点上面的选项"
                  :disabled="recordComposerDisabled"
                  @keydown.enter.exact.prevent="onRecordSend"
                />
                <div class="wf-debug__composer-actions">
                  <el-button
                    v-if="recordCanCancel"
                    link
                    type="danger"
                    :loading="recordSession.cancelling.value"
                    @click="recordSession.cancel()"
                  >
                    取消
                  </el-button>
                  <el-button
                    type="primary"
                    :loading="recordSession.replying.value"
                    :disabled="recordComposerDisabled"
                    @click="onRecordSend"
                  >
                    发送
                  </el-button>
                </div>
              </div>
            </template>
          </WorkflowDebugRecords>
        </template>
      </div>
    </aside>
  </Transition>
</template>

<script setup lang="ts">
/**
 * 调试悬浮框的壳。所属域：workflow 的前端编辑页。
 *
 * <p>叠在画布右侧，分「调试」和「记录」两个页签。调试按画布此刻的草稿跑，不选版本。
 * 记录里只回看以往的调试，列表上方只有一个「调用追踪」外链；版本切换入口在画布顶栏「发布」旁。
 * 过程区仍用首页同一张卡，只有右上角 X 能关闭。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { listWorkflowRuns } from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import type { WorkflowRunDetail, WorkflowRunSummary } from '@/types/workflow'
import WorkflowDebugHuman from './WorkflowDebugHuman.vue'
import WorkflowDebugErrorBar from './WorkflowDebugErrorBar.vue'
import WorkflowDebugRecords from './WorkflowDebugRecords.vue'
import WorkflowDebugTranscript from './WorkflowDebugTranscript.vue'
import { canCancelStatus, debugActionLabel, debugQueryInput, debugRunErrorNotice, debugSendMode, type DebugPane } from './workflowDebugCard'
import { useWorkflowDebugSession } from './useWorkflowDebugSession'

const props = defineProps<{
  modelValue: boolean
  workflowId: number
  /** 定义展示名；只用于调用追踪页的 workflowName 查询，与原来顶栏入口一致 */
  workflowName?: string
  canAct: boolean
  /**
   * 宿主页面是否仍在前台。KeepAlive 只通知缓存根，调试框收不到失活钩子，
   * 所以由父页把这个状态传下来，用来停轮询。
   */
  pageActive?: boolean
  /**
   * 开始调试前由宿主再校一次当前画布，并把图落到草稿。
   * 返回 false 表示不通过或没保存成功，这次不发。
   */
  prepareStart?: () => Promise<boolean>
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'run-change', detail: WorkflowRunDetail | null): void
}>()

const router = useRouter()
const user = useUserStore()

const pane = ref<DebugPane>('debug')
const draft = ref('')
const recordDraft = ref('')
const history = ref<WorkflowRunSummary[]>([])
const historyLoading = ref(false)
const recordOpen = ref(false)
const composerRef = ref<{ focus: () => void } | null>(null)
const recordComposerRef = ref<{ focus: () => void } | null>(null)

const debugSession = useWorkflowDebugSession({
  onDetail: (detail) => {
    if (pane.value === 'debug') {
      emit('run-change', detail)
    }
  },
  isActive: () => props.modelValue && props.pageActive !== false && pane.value === 'debug'
})

const recordSession = useWorkflowDebugSession({
  onDetail: (detail) => {
    if (pane.value === 'records') {
      emit('run-change', detail)
    }
  },
  isActive: () => props.modelValue && props.pageActive !== false && pane.value === 'records'
})

const currentUserId = computed(() => user.profile?.userId ?? null)
const currentRoles = computed(() => user.profile?.roles ?? [])
const debugBusy = computed(() =>
  debugSession.starting.value || debugSession.replying.value || debugSession.cancelling.value || debugSession.acting.value
)
const recordBusy = computed(() =>
  recordSession.replying.value || recordSession.cancelling.value || recordSession.acting.value
)
const debugMode = computed(() => debugSendMode(debugSession.run.value?.status, debugBusy.value))
const recordMode = computed(() => debugSendMode(recordSession.run.value?.status, recordBusy.value))
const debugChoiceEnabled = computed(() => debugMode.value === 'reply')
const recordChoiceEnabled = computed(() => recordMode.value === 'reply')
/**
 * 调试页顶部错误条文案。
 *
 * <p>只在 run 失败时出条；等待回复、人工等待、用户取消都不算错误，
 * 节点上的等待态与标题右侧的终态已经说清，不再重复一条红色底栏。
 */
const debugErrorNotice = computed(() =>
  debugRunErrorNotice(debugSession.run.value?.status, debugSession.run.value?.errorSummary)
)
/** 记录页同一条口径：打开已失败的历史时，顶部也看到同一句原因 */
const recordErrorNotice = computed(() =>
  debugRunErrorNotice(recordSession.run.value?.status, recordSession.run.value?.errorSummary)
)
const recordCanCancel = computed(() => canCancelStatus(recordSession.run.value?.status) && !recordSession.cancelling.value)
const debugComposerDisabled = computed(() => debugMode.value === 'blocked' || !props.workflowId)
const recordComposerDisabled = computed(() => recordMode.value !== 'reply')
/** 当前这条还能停：运行中 / 等人工 / 等回复（不含已停在本条等待回复的续聊态） */
const debugStopMode = computed(
  () => canCancelStatus(debugSession.run.value?.status) && debugMode.value !== 'reply'
)
/** 输入行唯一主按钮的文案：开始「运行」，续跑「发送」，运行中同一按钮「停止」 */
const debugActionLabelText = computed(() => debugActionLabel(debugMode.value, debugStopMode.value))
/**
 * 按钮自己的 loading：开始 / 停止请求在飞。
 *
 * <p>续跑请求不再算进 loading：续跑会在后台继续跑，运行中的「停止」必须马上可点，
 * 不能因为续跑 HTTP 还没返回就锁住取消。
 */
const debugActionLoading = computed(
  () => debugSession.starting.value || (debugStopMode.value && debugSession.cancelling.value)
)
const debugPlaceholder = computed(() =>
  debugMode.value === 'reply' ? '接着说，或点卡片上的选项' : '请输入问题'
)

/** 只由框右上角 X 关闭；点画布、拖动画布都不触发 */
function close() {
  emit('update:modelValue', false)
}

/**
 * 打开时停在调试页。换工作流先清空，避免上一条过程留在框里。
 * 列表页第一次挂载时调试框已经是打开的，所以要 immediate。
 */
watch(
  () => [props.modelValue, props.workflowId] as const,
  ([open, id], prev) => {
    if (!open) {
      debugSession.pause()
      recordSession.pause()
      if (prev && id !== prev[1]) {
        resetForWorkflow()
      }
      return
    }
    if (prev && id !== prev[1]) {
      resetForWorkflow()
    }
    if (pane.value === 'records') {
      void loadHistory()
      recordSession.resume()
    } else {
      debugSession.resume()
    }
  },
  { immediate: true }
)

/** 切到记录才拉历史；回到调试页继续看当前这条。 */
watch(pane, (name) => {
  if (!props.modelValue) {
    return
  }
  if (name === 'records') {
    debugSession.pause()
    void loadHistory()
    recordSession.resume()
    emit('run-change', recordSession.run.value)
    return
  }
  recordSession.pause()
  debugSession.resume()
  emit('run-change', debugSession.run.value)
})

/** 宿主页切走时停轮询；回来后当前页签上的调试继续拉详情。 */
watch(
  () => props.pageActive !== false && props.modelValue,
  (active) => {
    if (!active) {
      debugSession.pause()
      recordSession.pause()
      return
    }
    if (pane.value === 'records') {
      recordSession.resume()
    } else {
      debugSession.resume()
    }
  }
)

/** 换工作流时丢掉输入、记录和两条过程，并清掉画布高亮。 */
function resetForWorkflow() {
  debugSession.reset()
  recordSession.reset()
  pane.value = 'debug'
  draft.value = ''
  recordDraft.value = ''
  history.value = []
  recordOpen.value = false
}

/** 调试记录。点一行回看那条 run，不新开。 */
async function loadHistory() {
  const id = props.workflowId
  if (!id) {
    return
  }
  historyLoading.value = true
  try {
    const { data } = await listWorkflowRuns(id)
    if (props.workflowId === id) {
      history.value = data.data || []
    }
  } catch {
    if (props.workflowId === id) {
      history.value = []
    }
  } finally {
    if (props.workflowId === id) {
      historyLoading.value = false
    }
  }
}

/**
 * 右侧主按钮：能停时点它停这条；否则按「运行 / 发送」发送。
 */
function onDebugPrimary() {
  if (debugStopMode.value) {
    void debugSession.cancel()
    return
  }
  void onDebugSend()
}

/**
 * 调试页发送：没开始或已结束就按当前草稿新开一条；停在等待回复就续同一条。
 * 新开前让宿主再校一次画布并落草稿。
 */
async function onDebugSend() {
  if (debugMode.value === 'blocked' || !props.workflowId) {
    return
  }
  if (debugMode.value === 'reply') {
    const text = draft.value.trim()
    if (!text) {
      return
    }
    const sent = draft.value
    draft.value = ''
    const ok = await debugSession.reply(text)
    if (!ok) {
      draft.value = sent
    }
    return
  }
  const merged = debugQueryInput(draft.value)
  if (!merged.ok) {
    ElMessage.warning(merged.error)
    return
  }
  if (props.prepareStart) {
    const ready = await props.prepareStart()
    if (!ready) {
      return
    }
  }
  const sent = draft.value
  draft.value = ''
  const ok = await debugSession.start(props.workflowId, merged.input)
  if (!ok) {
    draft.value = sent
  } else {
    void loadHistory()
  }
}

/**
 * 调试页点选项：把选项文案送回同一条。
 *
 * @param label 选项上的文字
 */
function onDebugChoose(label: string) {
  if (debugMode.value !== 'reply' || !label.trim()) {
    return
  }
  void debugSession.reply(label)
}

/**
 * 点卡片上的「自己输入」：焦点回到顶部同一个输入框。
 * 输入框本身已在这个位置，续跑回复仍由右侧「发送」送回同一条 run。
 */
async function focusComposer() {
  await nextTick()
  composerRef.value?.focus()
}

/**
 * 调试页的人工通过或驳回。
 *
 * @param taskId 待办主键
 * @param approved true 表示通过
 * @param note 备注
 */
function onDebugDecide(taskId: number, approved: boolean, note: string) {
  void debugSession.decide(taskId, approved, note).then(() => loadHistory())
}

/**
 * 打开一条历史调试。未发布的记录只能回看或续跑，不能切换版本。
 *
 * @param id run 主键
 */
function openRecord(id: number) {
  recordOpen.value = true
  void recordSession.openRun(id)
}

/** 从某条过程回到记录列表，画布高亮跟着清掉。 */
function closeRecord() {
  recordOpen.value = false
  recordSession.reset()
}

/** 记录里停在等待回复时，下一句续同一条。 */
async function onRecordSend() {
  if (recordMode.value !== 'reply') {
    return
  }
  const text = recordDraft.value.trim()
  if (!text) {
    return
  }
  const sent = recordDraft.value
  recordDraft.value = ''
  const ok = await recordSession.reply(text)
  if (!ok) {
    recordDraft.value = sent
  } else {
    void loadHistory()
  }
}

/**
 * 记录里点选项，续同一条。
 *
 * @param label 选项上的文字
 */
function onRecordChoose(label: string) {
  if (recordMode.value !== 'reply' || !label.trim()) {
    return
  }
  void recordSession.reply(label)
}

/** 记录里点「其它」只聚焦这条的输入框。 */
async function focusRecordComposer() {
  await nextTick()
  recordComposerRef.value?.focus()
}

/**
 * 记录里的人工通过或驳回。
 *
 * @param taskId 待办主键
 * @param approved true 表示通过
 * @param note 备注
 */
function onRecordDecide(taskId: number, approved: boolean, note: string) {
  void recordSession.decide(taskId, approved, note).then(() => loadHistory())
}

/** 去待办中心前先关上调试框，避免两处同时改同一条。 */
function goTodo() {
  emit('update:modelValue', false)
  void router.push('/orchestration/human-tasks')
}

/**
 * 从记录页最上方打开这条工作流的调用追踪页。
 *
 * <p>落点和查询口径与原来顶栏「运行历史」完全一致：type=workflow 让追踪页直接进
 * 「工作流」类型，workflowId 供后端反查该定义的 run，workflowName 只做展示。
 */
function openTraces() {
  const id = props.workflowId
  if (!id) {
    return
  }
  void router.push({
    path: '/ops/traces',
    query: {
      type: 'workflow',
      workflowId: String(id),
      workflowName: props.workflowName || ''
    }
  })
}
</script>

<style scoped>
/*
 * 调试悬浮框：比属性框 / 校验框更宽的另一块。
 * 按整个窗口定位（fixed），高度基本到顶到底，只留一点空隙，不再受画布高度限制。
 */
.wf-debug-panel {
  position: fixed;
  top: var(--wf-debug-top, 48px);
  right: 8px;
  bottom: var(--wf-debug-bottom, 12px);
  z-index: 8;
  display: flex;
  flex-direction: column;
  width: 680px;
  max-width: calc(100vw - 16px);
  overflow: hidden;
  border: 1px solid rgb(15 23 42 / 8%);
  border-radius: 10px;
  background: var(--ah-surface, #fff);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.12);
}

.wf-debug-panel__head {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--ah-border, #ebeef5);
}

.wf-debug-panel__tabs {
  display: flex;
  gap: 4px;
  min-width: 0;
}

.wf-debug-panel__tab {
  padding: 4px 12px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--ah-text-regular, #4b5563);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-debug-panel__tab:hover {
  background: var(--ah-neutral-tint, #f3f4f6);
}

.wf-debug-panel__tab.is-active {
  background: var(--ah-primary-tint, rgb(99 91 255 / 12%));
  color: var(--ah-primary, #635bff);
}

/* 右上角唯一关闭入口：低干扰，hover 才显形 */
.wf-debug-panel__close {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-faint, #9ca3af);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-debug-panel__close:hover {
  background: var(--ah-neutral-tint, #f3f4f6);
  color: var(--ah-text-regular, #4b5563);
}

.wf-debug-panel__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  padding: 10px 12px;
}

/* 记录页签整块可滚；调试页只让过程区滚，顶部输入常驻 */
.wf-debug-panel__body.is-records {
  overflow-y: auto;
}

.wf-debug-ask {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 8px;
}

.wf-debug-ask__input {
  flex: 1;
  min-width: 0;
}

.wf-debug-ask__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.wf-debug-ask__run {
  min-width: 64px;
}

.wf-debug-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  padding-right: 2px;
  overflow-y: auto;
}

.wf-debug__composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
  padding-top: 8px;
}

.wf-debug__composer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 与画布其它悬浮框一致的淡入淡出 */
.wf-wake-enter-active,
.wf-wake-leave-active {
  transition: opacity 0.15s ease;
}

.wf-wake-enter-from,
.wf-wake-leave-to {
  opacity: 0;
}
</style>
