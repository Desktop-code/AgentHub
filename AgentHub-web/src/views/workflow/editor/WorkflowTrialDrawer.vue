<template>
  <el-drawer
    :model-value="modelValue"
    title="试跑工作流"
    size="480px"
    :close-on-click-modal="false"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="wf-trial">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="试跑只使用已发布版本快照，当前草稿未发布的改动不会生效。"
      />

      <div class="wf-trial__row">
        <span class="wf-trial__label">发布版本</span>
        <el-select
          v-model="releaseId"
          class="wf-trial__select"
          placeholder="选择版本"
          :disabled="running || !releases.length"
        >
          <el-option
            v-for="item in releases"
            :key="item.id"
            :label="releaseLabel(item)"
            :value="item.id"
          />
        </el-select>
        <el-button link type="primary" :loading="loading" @click="loadReleases">刷新版本</el-button>
      </div>

      <el-alert
        v-if="!loading && !releases.length"
        type="warning"
        :closable="false"
        show-icon
        title="该工作流还没有发布版本，请先点顶栏「发布」后再试跑。"
      />

      <div class="wf-trial__field">
        <span class="wf-trial__label">输入样例（JSON 对象）</span>
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="6"
          placeholder='{"query": "帮我查一下订单 A1001"}'
          :disabled="running"
        />
        <div v-if="jsonError" class="wf-trial__error">{{ jsonError }}</div>
      </div>

      <div class="wf-trial__actions">
        <el-button
          type="primary"
          :loading="running"
          :disabled="!releaseId || running"
          @click="onStart"
        >
          开始试跑
        </el-button>
        <el-button
          :disabled="!canCancel || running"
          :loading="cancelling"
          @click="onCancel"
        >
          取消本次运行
        </el-button>
        <el-button v-if="runDetail" link :loading="refreshing" @click="onRefresh">刷新状态</el-button>
      </div>

      <el-divider content-position="left">最近试跑</el-divider>
      <div v-loading="historyLoading" class="wf-trial__history">
        <div v-if="!historyLoading && !history.length" class="wf-trial__empty">还没有试跑记录</div>
        <button
          v-for="row in history"
          :key="row.id"
          type="button"
          class="wf-trial__history-row"
          :class="{ 'is-active': runDetail?.id === row.id }"
          @click="onOpenRun(row.id)"
        >
          <span class="wf-trial__history-id">#{{ row.id }}</span>
          <el-tag size="small" :type="runTagType(row.status)" effect="plain">
            {{ runStatusLabel(row.status) }}
          </el-tag>
          <!-- 应用触发（生产语义）与试跑混在同一列表：加标记避免误读为控制台发起 -->
          <el-tag v-if="row.triggerType === 'APP'" size="small" type="warning" effect="plain">应用</el-tag>
          <span class="wf-trial__history-version">v{{ row.releaseVersion }}</span>
          <span class="wf-trial__history-time">{{ formatTime(row.createTime) }}</span>
        </button>
      </div>

      <template v-if="runDetail">
        <el-divider content-position="left">运行详情</el-divider>
        <div class="wf-trial__status">
          <el-tag :type="runTagType(runDetail.status)" effect="dark">
            {{ runStatusLabel(runDetail.status) }}
          </el-tag>
          <span class="wf-trial__meta">
            run #{{ runDetail.id }} · 版本 v{{ runDetail.releaseVersion }}
          </span>
        </div>
        <el-alert
          v-if="runDetail.errorSummary"
          type="error"
          :closable="false"
          show-icon
          :title="runDetail.errorSummary"
        />

        <el-collapse v-if="runDetail.output" v-model="outputOpen">
          <el-collapse-item title="运行输出（JSON）" name="output">
            <pre class="wf-trial__json">{{ prettyOutput }}</pre>
          </el-collapse-item>
        </el-collapse>

        <div class="wf-trial__nodes">
          <div v-for="node in runDetail.nodeRuns" :key="node.id" class="wf-trial__node">
            <span class="wf-trial__node-code">{{ node.nodeCode }}</span>
            <span class="wf-trial__node-type">{{ node.nodeType }}</span>
            <el-tag size="small" :type="nodeTagType(node.status)" effect="plain">
              {{ nodeStatusLabel(node.status) }}
            </el-tag>
          </div>
        </div>

        <template v-if="runDetail.humanTasks.length">
          <el-divider content-position="left">人工待办</el-divider>
          <el-alert
            v-if="!canAct"
            type="warning"
            :closable="false"
            show-icon
            title="没有 workflow:human:act 权限，只能查看不能处理。"
          />
          <el-alert
            v-else-if="myTasks.length"
            type="warning"
            :closable="false"
            show-icon
            :title="`有 ${myTasks.length} 条待我处理；处理后会同步续跑或按错误边收口。`"
          >
            <template #default>
              <el-button link type="primary" @click="goTodo">去待办中心</el-button>
            </template>
          </el-alert>
          <div v-for="task in runDetail.humanTasks" :key="task.id" class="wf-trial__human">
            <div class="wf-trial__human-head">
              <b>节点 {{ task.nodeCode }}</b>
              <el-tag v-if="canActOn(task)" size="small" type="warning" effect="plain">待我处理</el-tag>
              <el-tag v-else-if="canAct" size="small" effect="plain">非处理人</el-tag>
              <span>规则：{{ describeAssigneeRule(task.assigneeRule) }}</span>
              <span>超时策略：{{ timeoutLabel(task.timeoutPolicy) }}</span>
              <span v-if="task.expireTime">到期：{{ formatTime(task.expireTime) }}</span>
            </div>
            <el-input
              v-model="note"
              type="textarea"
              :rows="2"
              maxlength="512"
              placeholder="处理备注（可空，最多 512 字）"
              :disabled="acting || !canActOn(task)"
            />
            <div class="wf-trial__human-actions">
              <el-button
                type="success"
                size="small"
                :loading="acting"
                :disabled="!canActOn(task) || acting"
                @click="onDecide(task.id, true)"
              >
                通过并续跑
              </el-button>
              <el-button
                type="danger"
                size="small"
                plain
                :loading="acting"
                :disabled="!canActOn(task) || acting"
                @click="onDecide(task.id, false)"
              >
                驳回
              </el-button>
            </div>
            <div v-if="canAct && !canActOn(task)" class="wf-trial__human-tip">
              你不是该节点处理人（规则：{{ describeAssigneeRule(task.assigneeRule) }}），无法通过或驳回。
            </div>
          </div>
        </template>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 工作流试跑抽屉。所属域：workflow 的前端编辑页。
 * <p>职责：选已发布版本、填输入样例、发起试跑、取消、处理人工待办，
 * 并把 run 详情抛给编辑页做画布高亮。
 * <p>边界：不直接调模型 / 工具；不读写草稿；不猜并行（节点状态全部来自后端 run 表）。
 * <p>实时性：本刀用「开始 / 刷新」拉取详情，不接 SSE。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  approveHumanTask,
  cancelWorkflowRun,
  getWorkflowRun,
  listWorkflowReleases,
  listWorkflowRuns,
  rejectHumanTask,
  startWorkflowTrial
} from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import { describeAssigneeRule, matchesAssigneeRule } from '@/utils/workflowAssignee'
import type { WorkflowHumanTaskRow, WorkflowReleaseRow, WorkflowRunDetail, WorkflowRunSummary } from '@/types/workflow'

const props = defineProps<{
  modelValue: boolean
  workflowId: number
  defaultReleaseId?: number | null
  canAct: boolean
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'run-change', detail: WorkflowRunDetail | null): void
}>()

const releases = ref<WorkflowReleaseRow[]>([])
const releaseId = ref<number | undefined>(undefined)
const inputText = ref('{\n  "query": "你好，介绍一下你自己"\n}')
const runDetail = ref<WorkflowRunDetail | null>(null)
const history = ref<WorkflowRunSummary[]>([])
const note = ref('')
/** 输出折叠面板的展开项；默认折叠，避免长 JSON 把待办挤下去 */
const outputOpen = ref<string[]>([])

const loading = ref(false)
const historyLoading = ref(false)
const running = ref(false)
const refreshing = ref(false)
const cancelling = ref(false)
const acting = ref(false)
const jsonError = ref('')

const user = useUserStore()
const router = useRouter()

/** 当前登录人；用于前端判断「是否待我处理」，真正放行仍以后端为准 */
const currentUserId = computed(() => user.profile?.userId ?? null)
const currentRoles = computed(() => user.profile?.roles ?? [])

/** OPEN 待办里命中当前用户且具备 human:act 的那些（用于「待我处理」提示与按钮可用性） */
const myTasks = computed<WorkflowHumanTaskRow[]>(() =>
  (runDetail.value?.humanTasks ?? []).filter((task) => canActOn(task))
)

/**
 * 当前用户能否处理某条待办：有 human:act + 规则命中。
 * <p>仅用于 UI 可用性提示；越权判定仍由后端 human:act + assignee 兜底。
 *
 * @param task 待办行
 */
function canActOn(task: WorkflowHumanTaskRow): boolean {
  if (!props.canAct) {
    return false
  }
  return matchesAssigneeRule(task.assigneeRule, currentUserId.value, currentRoles.value)
}

/** 去人工待办中心（与试跑抽屉互补：一个按 run 看，一个按人看） */
function goTodo() {
  emit('update:modelValue', false)
  void router.push('/orchestration/human-tasks')
}

/** 超时策略中文名 */
function timeoutLabel(value?: string | null): string {
  const map: Record<string, string> = {
    AUTO_REJECT: '超时自动拒绝',
    AUTO_APPROVE: '超时自动通过',
    ESCALATE: '超时升级（本刀按拒绝收口）'
  }
  return value ? map[value] || value : '—'
}

const canCancel = computed(() => {
  const status = runDetail.value?.status
  return status === 'RUNNING' || status === 'PAUSED_HUMAN'
})

/** run 输出按 JSON 缩进展示；无输出不渲染折叠块 */
const prettyOutput = computed(() =>
  runDetail.value?.output ? JSON.stringify(runDetail.value.output, null, 2) : ''
)

/** 打开抽屉时按默认发布指针选版本；已经加载过就不重复打接口 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    if (!releases.value.length) {
      void loadReleases()
    } else {
      pickDefaultRelease()
    }
    void loadHistory()
  }
)

/** 读版本列表，默认选中定义的默认发布指针 */
async function loadReleases() {
  if (!props.workflowId) {
    return
  }
  loading.value = true
  try {
    const { data } = await listWorkflowReleases(props.workflowId)
    releases.value = data.data || []
    pickDefaultRelease()
  } catch {
    releases.value = []
  } finally {
    loading.value = false
  }
}

/** 最近试跑列表：点一行即可回看该次 run 的节点状态 */
async function loadHistory() {
  if (!props.workflowId) {
    return
  }
  historyLoading.value = true
  try {
    const { data } = await listWorkflowRuns(props.workflowId)
    history.value = data.data || []
  } catch {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

/** 默认版本：先匹配 defaultReleaseId，其次最新版本 */
function pickDefaultRelease() {
  if (!releases.value.length) {
    releaseId.value = undefined
    return
  }
  const preferred = props.defaultReleaseId
    ? releases.value.find((item) => item.id === props.defaultReleaseId)
    : undefined
  releaseId.value = preferred ? preferred.id : releases.value[0].id
}

/** 版本下拉文案：标出默认发布指针，提醒用户「不选版本时后端会走它」 */
function releaseLabel(item: WorkflowReleaseRow): string {
  const base = `v${item.version}${item.publishNote ? ` · ${item.publishNote}` : ''}`
  return item.id === props.defaultReleaseId ? `${base}（默认）` : base
}

/** 开始试跑：先校验输入 JSON，成功后回填详情并通知画布 */
async function onStart() {
  if (!releaseId.value) {
    ElMessage.warning('请选择发布版本')
    return
  }
  jsonError.value = ''
  let input: unknown
  try {
    input = JSON.parse(inputText.value)
  } catch {
    jsonError.value = '输入不是合法 JSON'
    return
  }
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    jsonError.value = '输入必须是 JSON 对象，例如 {"query": "..."}'
    return
  }
  running.value = true
  try {
    const { data } = await startWorkflowTrial({
      releaseId: releaseId.value,
      workflowId: props.workflowId,
      input
    })
    applyDetail(data.data)
    void loadHistory()
    if (data.data.status === 'SUCCESS') {
      ElMessage.success('试跑成功')
    } else if (data.data.status === 'PAUSED_HUMAN') {
      ElMessage.warning('已停在人工节点，请在下方处理待办')
    } else if (data.data.status === 'FAILED') {
      ElMessage.error(data.data.errorSummary || '试跑失败')
    }
  } catch {
    // 拦截器已弹出中文原因
  } finally {
    running.value = false
  }
}

/** 取消 run：协作式，引擎会在下一个节点前退出 */
async function onCancel() {
  if (!runDetail.value) {
    return
  }
  cancelling.value = true
  try {
    const { data } = await cancelWorkflowRun(runDetail.value.id)
    applyDetail(data.data)
    void loadHistory()
    ElMessage.success('已取消')
  } catch {
    // 拦截器已提示
  } finally {
    cancelling.value = false
  }
}

/** 主动刷新：顺带触发后端「人工待办懒超时」结算 */
async function onRefresh() {
  if (!runDetail.value) {
    return
  }
  refreshing.value = true
  try {
    const { data } = await getWorkflowRun(runDetail.value.id)
    applyDetail(data.data)
    void loadHistory()
  } catch {
    // 拦截器已提示
  } finally {
    refreshing.value = false
  }
}

/** 点历史行回看某次 run：只读拉详情，不改变当前选中版本 */
async function onOpenRun(id: number) {
  refreshing.value = true
  try {
    const { data } = await getWorkflowRun(id)
    applyDetail(data.data)
  } catch {
    // 拦截器已提示
  } finally {
    refreshing.value = false
  }
}

/** 通过 / 驳回人工待办；后端会同步续跑并返回最新详情 */
async function onDecide(taskId: number, approved: boolean) {
  acting.value = true
  try {
    const { data } = approved
      ? await approveHumanTask(taskId, note.value || undefined)
      : await rejectHumanTask(taskId, note.value || undefined)
    applyDetail(data.data)
    note.value = ''
    void loadHistory()
    ElMessage.success(approved ? '已通过并续跑' : '已驳回')
  } catch {
    // 拦截器已提示
  } finally {
    acting.value = false
  }
}

/** 统一回填 + 通知父组件 */
function applyDetail(detail: WorkflowRunDetail) {
  runDetail.value = detail
  emit('run-change', detail)
}

/** run 状态中文名 */
function runStatusLabel(status: string): string {
  const map: Record<string, string> = {
    CREATED: '已创建',
    RUNNING: '运行中',
    PAUSED_HUMAN: '等待人工',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return map[status] || status
}

/** 节点状态中文名 */
function nodeStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待运行',
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    SKIPPED: '已跳过',
    WAITING_HUMAN: '等待人工',
    CANCELLED: '已取消'
  }
  return map[status] || status
}

/** run 状态 → Element Plus tag 类型 */
function runTagType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'PAUSED_HUMAN') return 'warning'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

/** 节点状态 → Element Plus tag 类型 */
function nodeTagType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'WAITING_HUMAN' || status === 'RUNNING') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

/** 时间展示：后端给 yyyy-MM-dd HH:mm:ss，直接截断毫秒；空值给占位 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.wf-trial {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.wf-trial__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.wf-trial__label {
  color: #374151;
  font-size: 13px;
  font-weight: 600;
}

.wf-trial__select {
  width: 220px;
}

.wf-trial__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-trial__error {
  color: #dc2626;
  font-size: 12px;
}

.wf-trial__actions {
  display: flex;
  gap: 8px;
}

.wf-trial__status {
  display: flex;
  align-items: center;
  gap: 10px;
}

.wf-trial__meta {
  color: #6b7280;
  font-size: 12px;
}

.wf-trial__nodes {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 240px;
  overflow: auto;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.wf-trial__node {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.wf-trial__node-code {
  min-width: 110px;
  color: #111827;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.wf-trial__node-type {
  flex: 1;
  color: #6b7280;
}

.wf-trial__human {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
}

.wf-trial__human-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #92400e;
  font-size: 12px;
}

.wf-trial__human-actions {
  display: flex;
  gap: 8px;
}

.wf-trial__human-tip {
  color: #b45309;
  font-size: 12px;
  line-height: 1.5;
}

.wf-trial__history {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 200px;
  overflow: auto;
}

.wf-trial__empty {
  color: #9ca3af;
  font-size: 12px;
}

.wf-trial__history-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #374151;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.wf-trial__history-row:hover,
.wf-trial__history-row.is-active {
  border-color: #635bff;
  background: #f5f3ff;
}

.wf-trial__history-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.wf-trial__history-version {
  color: #6b7280;
}

.wf-trial__history-time {
  margin-left: auto;
  color: #9ca3af;
}

.wf-trial__json {
  max-height: 220px;
  margin: 0;
  overflow: auto;
  padding: 8px;
  border-radius: 8px;
  background: #f9fafb;
  color: #111827;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
