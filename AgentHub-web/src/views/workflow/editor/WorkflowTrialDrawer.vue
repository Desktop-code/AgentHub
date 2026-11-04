<template>
  <el-drawer
    class="wf-exec-drawer"
    :model-value="modelValue"
    title="执行工作流"
    size="560px"
    :close-on-click-modal="false"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="wf-exec">
      <!-- ==================== 配置区：展开 / 48px 摘要栏 ==================== -->
      <section class="wf-exec__config" :class="{ 'is-collapsed': configCollapsed }">
        <!-- 可折叠表单：用 grid-template-rows 1fr → 0fr 过渡，内容高度自适应 -->
        <div class="wf-exec__config-clip">
          <div class="wf-exec__config-inner">
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="执行只使用已发布版本快照，当前草稿未发布的改动不会生效。"
            />

            <div class="wf-exec__row">
              <span class="wf-exec__label">发布版本</span>
              <el-select
                v-model="releaseId"
                class="wf-exec__select"
                placeholder="选择版本"
                :disabled="isRunning || !releases.length"
              >
                <el-option
                  v-for="item in releases"
                  :key="item.id"
                  :label="releaseLabel(item)"
                  :value="item.id"
                />
              </el-select>
              <el-button link type="primary" :loading="loading" :disabled="isRunning" @click="loadReleases">
                刷新版本
              </el-button>
            </div>

            <el-alert
              v-if="!loading && !releases.length"
              type="warning"
              :closable="false"
              show-icon
              title="该工作流还没有发布版本，请先点顶栏「发布」后再执行。"
            />

            <div class="wf-exec__field">
              <span class="wf-exec__label">输入样例（JSON 对象）</span>
              <el-input
                v-model="inputText"
                type="textarea"
                :rows="5"
                placeholder='{"query": "帮我查一下订单 A1001"}'
                :disabled="isRunning"
                resize="vertical"
              />
              <div v-if="jsonError" class="wf-exec__error">{{ jsonError }}</div>
            </div>

            <div class="wf-exec__actions">
              <el-button
                type="primary"
                :loading="starting"
                :disabled="!releaseId || isRunning"
                @click="onStart"
              >
                开始执行
              </el-button>
              <el-button v-if="canCancel" link type="danger" :loading="cancelling" :disabled="cancelling" @click="onCancel">
                取消本次运行
              </el-button>
              <el-button v-else-if="runDetail" link :loading="refreshing" @click="onRefresh">刷新状态</el-button>
            </div>

            <div class="wf-exec__history-head">
              <span class="wf-exec__label">最近执行</span>
              <button type="button" class="wf-exec__link" @click="collapseConfig">收起配置</button>
            </div>
            <div v-loading="historyLoading" class="wf-exec__history">
              <div v-if="!historyLoading && !history.length" class="wf-exec__muted">还没有执行记录</div>
              <button
                v-for="row in history"
                :key="row.id"
                type="button"
                class="wf-exec__history-row"
                :class="{ 'is-active': runDetail?.id === row.id }"
                @click="onOpenRun(row.id)"
              >
                <span class="wf-exec__history-id">#{{ row.id }}</span>
                <el-tag size="small" :type="runTagType(row.status)" effect="plain">
                  {{ runStatusLabel(row.status) }}
                </el-tag>
                <!-- 应用触发（生产语义）与控制台执行混在同一列表：加标记避免误读为控制台发起 -->
                <el-tag v-if="row.triggerType === 'APP'" size="small" type="warning" effect="plain">应用</el-tag>
                <span class="wf-exec__history-version">v{{ row.releaseVersion }}</span>
                <span class="wf-exec__history-time">{{ formatTime(row.createTime) }}</span>
              </button>
            </div>
          </div>
        </div>

        <!-- 折叠后的 48px 摘要栏：版本 | 入参预览 | 展开修改 -->
        <button
          type="button"
          class="wf-exec__brief"
          :aria-hidden="!configCollapsed"
          :tabindex="configCollapsed ? 0 : -1"
          title="展开配置区"
          @click="expandConfig"
        >
          <span class="wf-exec__brief-version">v{{ summaryVersion }}</span>
          <span class="wf-exec__brief-sep">|</span>
          <code class="wf-exec__brief-input">{{ summaryInput }}</code>
          <span class="wf-exec__brief-toggle">展开修改</span>
        </button>
      </section>

      <!-- ==================== 结果区：状态条 + 纵向时间轴 ==================== -->
      <section class="wf-exec__result">
        <div class="wf-exec__statusbar" :class="`is-${statusTone}`">
          <span class="wf-exec__status-dot" aria-hidden="true" />
          <span class="wf-exec__status-text">{{ statusText }}</span>
          <span v-if="runDetail" class="wf-exec__status-meta">
            run #{{ runDetail.id }} · v{{ runDetail.releaseVersion }}
          </span>
          <el-button v-if="canCancel" link type="danger" size="small" :loading="cancelling" @click="onCancel">
            取消
          </el-button>
        </div>

        <el-alert
          v-if="runDetail?.errorSummary"
          class="wf-exec__result-alert"
          type="error"
          :closable="false"
          show-icon
          :title="runDetail.errorSummary"
        />

        <div ref="timelineRef" class="wf-exec__timeline">
          <!-- 默认空态：还没跑过任何一次 -->
          <div v-if="!runDetail && !isRunning" class="wf-exec__blank">
            <div class="wf-exec__blank-title">还没有执行结果</div>
            <div class="wf-exec__blank-desc">选好发布版本并填写入参后，点「开始执行」，节点会按时间轴陆续出现在这里。</div>
          </div>

          <!-- 已点开始、但后端是同步执行还没返回详情：先给一条「执行中」骨架，避免空白等待 -->
          <div v-else-if="isRunning && !timelineNodes.length" class="wf-exec__pending">
            <span class="wf-exec__dot is-pulse" aria-hidden="true" />
            <span>正在执行…已用时 {{ elapsed }}s</span>
          </div>

          <ol v-if="timelineNodes.length" class="wf-exec__steps">
            <li
              v-for="(node, index) in timelineNodes"
              :key="node.id"
              class="wf-exec__step"
              :class="[`is-${node.tone}`, { 'is-flash': flashNodeId === node.id }]"
              :data-node-id="node.id"
              :style="{ '--step-index': index }"
            >
              <div class="wf-exec__rail" aria-hidden="true">
                <span class="wf-exec__dot" />
                <span class="wf-exec__line" />
              </div>
              <div class="wf-exec__card">
                <button type="button" class="wf-exec__card-head" @click="toggleNode(node.id)">
                  <span class="wf-exec__card-type">{{ nodeTypeLabel(node.nodeType) }}</span>
                  <span class="wf-exec__card-name">{{ node.nodeCode }}</span>
                  <el-tag size="small" :type="nodeTagType(node.status)" effect="plain">
                    {{ nodeStatusLabel(node.status) }}
                  </el-tag>
                  <span class="wf-exec__card-time">{{ node.timeText }}</span>
                  <el-icon class="wf-exec__card-chevron" :class="{ 'is-open': isNodeOpen(node.id) }">
                    <ArrowDown />
                  </el-icon>
                </button>

                <el-collapse-transition>
                  <div v-show="isNodeOpen(node.id)" class="wf-exec__card-body">
                    <div class="wf-exec__block">
                      <div class="wf-exec__block-title">入参快照</div>
                      <pre v-if="node.input" class="wf-exec__json">{{ node.input }}</pre>
                      <div v-else class="wf-exec__muted">该节点没有可展示的入参快照</div>
                    </div>

                    <div class="wf-exec__block">
                      <div class="wf-exec__block-title">
                        {{ node.nodeType === 'AGENT' ? '模型输出' : '出参' }}
                      </div>
                      <!-- Agent 节点必须走 Markdown（renderSimpleMarkdown 已做 HTML 转义） -->
                      <div v-if="node.markdown" class="md wf-exec__md" v-html="node.markdown" />
                      <pre v-else-if="node.output" class="wf-exec__json">{{ node.output }}</pre>
                      <div v-else class="wf-exec__muted">
                        {{ node.nodeType === 'AGENT' ? '模型输出为空' : '该节点没有可展示的出参' }}
                      </div>
                    </div>

                    <div v-if="node.errorSummary" class="wf-exec__card-error">{{ node.errorSummary }}</div>
                  </div>
                </el-collapse-transition>
              </div>
            </li>
          </ol>

          <!-- 失败时追加 Trace 入口：弹出原始 run 数据，不把长 JSON 塞进主轴 -->
          <div v-if="runDetail?.status === 'FAILED'" class="wf-exec__trace">
            <button type="button" class="wf-exec__trace-link" @click="traceVisible = true">
              [查看 Trace 详情]
            </button>
          </div>

          <!-- 人工待办：与执行结果同区，避免用户来回切换 -->
          <template v-if="runDetail?.humanTasks.length">
            <div class="wf-exec__human-zone">
              <div class="wf-exec__history-head">
                <span class="wf-exec__label">人工待办</span>
              </div>
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
              <div v-for="task in runDetail.humanTasks" :key="task.id" class="wf-exec__human">
                <div class="wf-exec__human-head">
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
                <div class="wf-exec__human-actions">
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
                <div v-if="canAct && !canActOn(task)" class="wf-exec__human-tip">
                  你不是该节点处理人（规则：{{ describeAssigneeRule(task.assigneeRule) }}），无法通过或驳回。
                </div>
              </div>
            </div>
          </template>
        </div>
      </section>
    </div>

    <!-- Trace 弹窗：只读展示 run 原始数据，方便排查失败原因 -->
    <el-dialog v-model="traceVisible" title="Trace 详情（原始 run 数据）" width="760px" append-to-body>
      <pre class="wf-exec__json wf-exec__trace-json">{{ traceJson }}</pre>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 工作流「执行」抽屉。所属域：workflow 的前端编辑页。
 * <p>职责：选已发布版本、填输入样例、发起执行、取消、处理人工待办，
 * 并把 run 详情抛给编辑页做画布高亮。
 * <p>布局：上「配置区」（可折叠成 48px 摘要栏）+ 下「结果区」（状态条 + 纵向时间轴）。
 * <p>边界：不直接调模型 / 工具；不读写草稿；不猜并行（节点状态全部来自后端 run 表）。
 * <p>实时性：后端 trial 是同步执行、且当前没有 workflow SSE 端点，因此用
 * 「执行期间轮询 getWorkflowRun + 节点状态跃迁 diff」模拟 node_start / node_success，
 * 跃迁时给卡片荧光高亮并自动滚动；后续接真 SSE 只需替换数据源，视图层不用动。
 */
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
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
import { compareNodeRunExecOrder } from '@/utils/executionOrder'
import { describeAssigneeRule, matchesAssigneeRule } from '@/utils/workflowAssignee'
import { renderSimpleMarkdown } from '@/utils/simpleMarkdown'
import type {
  WorkflowHumanTaskRow,
  WorkflowNodeRunRow,
  WorkflowReleaseRow,
  WorkflowRunDetail,
  WorkflowRunSummary
} from '@/types/workflow'

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

/**
 * 节点运行行 + 可选的节点级入参/出参。
 * <p>后端 {@code WorkflowNodeRunResp} 目前只下发状态与时间，节点级 input/output 是
 * 「未来字段」；这里按可选字段读取，一旦后端补上即可直接渲染，不用再改本组件。
 */
type NodeRunRow = WorkflowNodeRunRow & {
  inputJson?: unknown
  outputJson?: unknown
  input?: unknown
  output?: unknown
}

/** 时间轴节点（视图模型，和接口 DTO 解耦，便于做降级文案与 Markdown 渲染） */
interface TimelineNode {
  id: number
  nodeId: string
  nodeCode: string
  nodeType: string
  status: string
  /** 圆点 / 卡片配色语义：running | success | failed | idle */
  tone: 'running' | 'success' | 'failed' | 'idle'
  /** 卡片头右侧的耗时 / 开始时间 */
  timeText: string
  /** 格式化后的入参 JSON 文本 */
  input: string
  /** 格式化后的出参 JSON 文本 */
  output: string
  /** Agent 节点的 Markdown HTML（非 Agent 或无可读文本时为空串） */
  markdown: string
  errorSummary?: string | null
}

/** 轮询间隔：后端同步执行拿不到推送，1.5s 足够贴近「准实时」又不打爆接口 */
const POLL_INTERVAL = 1500

const releases = ref<WorkflowReleaseRow[]>([])
const releaseId = ref<number | undefined>(undefined)
const inputText = ref('{\n  "query": "你好，介绍一下你自己"\n}')
const runDetail = ref<WorkflowRunDetail | null>(null)
const history = ref<WorkflowRunSummary[]>([])
const note = ref('')

const loading = ref(false)
const historyLoading = ref(false)
/** 正在等待 trial 接口返回（后端同步跑完才回详情，期间没有 runId 可轮询） */
const starting = ref(false)
const refreshing = ref(false)
const cancelling = ref(false)
const acting = ref(false)
const jsonError = ref('')

/** 配置区是否折叠成 48px 摘要栏；点「开始执行」立即折叠，结束后保持折叠 */
const configCollapsed = ref(false)
/** 已点开始、接口未返回期间的计时（秒），让用户知道没卡死 */
const elapsed = ref(0)
/** 当前高亮的节点 id（状态跃迁时闪一下） */
const flashNodeId = ref<number | null>(null)
/** 自动展开的节点（默认只留最近变化的那个，手动展开另行叠加） */
const autoOpenNodeId = ref<number | null>(null)
/** 用户手动展开过的节点 id 集合 */
const manualOpen = reactive(new Set<number>())
/** Trace 弹窗 */
const traceVisible = ref(false)
const timelineRef = ref<HTMLElement | null>(null)

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
 * 是否处于「执行中」：既包括刚点开始等接口，也包括打开的 run 本身还是 RUNNING。
 * <p>该状态统一驱动三件事：配置区折叠、输入控件禁用、轮询开关。
 */
const isRunning = computed(() => starting.value || runDetail.value?.status === 'RUNNING')

/** 取消只对 RUNNING / PAUSED_HUMAN 有意义 */
const canCancel = computed(() => {
  const status = runDetail.value?.status
  return status === 'RUNNING' || status === 'PAUSED_HUMAN'
})

/** 摘要栏版本号：跟着当前选中的发布版本走 */
const summaryVersion = computed(() => {
  const item = releases.value.find((row) => row.id === releaseId.value)
  return item ? item.version : '—'
})

/**
 * 摘要栏入参预览：压成单行并截断，保证 48px 高度里一眼能认出这次跑的是什么。
 */
const summaryInput = computed(() => {
  const compact = inputText.value.replace(/\s+/g, ' ').trim()
  if (!compact) {
    return '{}'
  }
  return compact.length > 56 ? `${compact.slice(0, 56)}…` : compact
})

/** 状态条语气：沿用节点配色语义，运行中紫色、成功绿色、失败红色 */
const statusTone = computed<'running' | 'success' | 'failed' | 'muted'>(() => {
  if (isRunning.value) {
    return 'running'
  }
  const status = runDetail.value?.status
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    return 'failed'
  }
  if (status === 'PAUSED_HUMAN') {
    return 'running'
  }
  return 'muted'
})

/** 状态条文案：优先说「正在执行哪个节点」，否则说整体终态 */
const statusText = computed(() => {
  if (starting.value) {
    return `正在提交执行…已用时 ${elapsed.value}s`
  }
  if (runDetail.value?.status === 'RUNNING') {
    const active = (runDetail.value.nodeRuns ?? []).find((node) => node.status === 'RUNNING')
    return active
      ? `正在执行 ${active.nodeCode}`
      : `执行中…已用时 ${elapsed.value}s`
  }
  if (!runDetail.value) {
    return '等待执行'
  }
  return `执行${runStatusLabel(runDetail.value.status)}`
})

/** run 原始数据（Trace 弹窗用），只挑排查必需的字段，避免把页面拖慢 */
const traceJson = computed(() => {
  const detail = runDetail.value
  if (!detail) {
    return ''
  }
  return JSON.stringify(
    {
      runId: detail.id,
      status: detail.status,
      triggerType: detail.triggerType ?? null,
      workflowId: detail.workflowId,
      releaseId: detail.releaseId,
      releaseVersion: detail.releaseVersion,
      errorSummary: detail.errorSummary ?? null,
      startTime: detail.startTime,
      endTime: detail.endTime,
      input: detail.input ?? null,
      output: detail.output ?? null,
      nodeRuns: detail.nodeRuns ?? []
    },
    null,
    2
  )
})

/**
 * 节点运行行按真实执行序排好。
 * <p>后端 `workflow_node_run` 在开跑前按「图数组顺序」批量预建，主键 id 是图顺序而非执行顺序；
 * 画布若把 END 排在 AGENT 前，直接渲染就会得到「开始 → 结束 → Agent」。
 * 这里统一按 start_time → end_time → id 排，未开工节点（PENDING / SKIPPED）排最后。
 */
const orderedNodeRuns = computed<NodeRunRow[]>(() =>
  ((runDetail.value?.nodeRuns ?? []) as NodeRunRow[]).slice().sort(compareNodeRunExecOrder)
)

/**
 * 把后端 nodeRuns 映射成时间轴视图模型。
 * <p>入参/出参优先取节点级可选字段；拿不到时降级：
 * START 用 run 级 input，END 用 run 级 output（后端目前只回这两处），其余给空态文案。
 */
const timelineNodes = computed<TimelineNode[]>(() => {
  const detail = runDetail.value
  if (!detail) {
    return []
  }
  const rows = orderedNodeRuns.value
  return rows.map((row) => {
    const nodeInput =
      prettyJson(row.inputJson ?? row.input) ||
      (row.nodeType === 'START' ? prettyJson(detail.input) : '')
    const rawOutput = row.outputJson ?? row.output
    const fallbackOutput = row.nodeType === 'END' ? endOutputOf(detail.output, row.nodeCode) : undefined
    const outputNode = rawOutput ?? fallbackOutput
    return {
      id: row.id,
      nodeId: row.nodeId,
      nodeCode: row.nodeCode,
      nodeType: row.nodeType,
      status: row.status,
      tone: nodeTone(row.status),
      timeText: durationText(row),
      input: nodeInput,
      output: prettyJson(outputNode),
      markdown: agentMarkdown(row.nodeType, outputNode),
      errorSummary: row.errorSummary
    }
  })
})

/* ============================ 视图辅助 ============================ */

/**
 * 节点卡片是否展开：用户手动展开的 + 自动展开的最近一个。
 * @param id 节点运行行 id
 */
function isNodeOpen(id: number): boolean {
  return manualOpen.has(id) || autoOpenNodeId.value === id
}

/**
 * 切换节点卡片展开态；关闭时同时清掉自动展开指针，避免点不动。
 * @param id 节点运行行 id
 */
function toggleNode(id: number) {
  if (isNodeOpen(id)) {
    manualOpen.delete(id)
    if (autoOpenNodeId.value === id) {
      autoOpenNodeId.value = null
    }
    return
  }
  manualOpen.add(id)
}

/** 折叠配置区（摘要栏保留版本 + 入参预览） */
function collapseConfig() {
  configCollapsed.value = true
}

/** 展开配置区，恢复版本 / 入参 / 最近执行列表 */
function expandConfig() {
  configCollapsed.value = false
}

/** 节点类型中文名（与画布保持一致） */
function nodeTypeLabel(type: string): string {
  const map: Record<string, string> = {
    START: '开始',
    END: '结束',
    AGENT: 'Agent',
    TOOL: '工具',
    CONDITION: '条件',
    PARALLEL_FORK: '并行分叉',
    PARALLEL_JOIN: '并行汇合',
    HUMAN: '人工审批',
    KB_RETRIEVE: '知识检索',
    SKILL_INJECT: '技能注入'
  }
  return map[type] || type || '未知'
}

/** 节点状态 → 圆点/卡片语气 */
function nodeTone(status: string): TimelineNode['tone'] {
  if (status === 'RUNNING') return 'running'
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'failed'
  return 'idle'
}

/** 节点耗时文案：有起止给「耗时」，只有开始给开始时间，都没有给占位 */
function durationText(row: WorkflowNodeRunRow): string {
  if (row.startTime && row.endTime) {
    const ms = new Date(row.endTime).getTime() - new Date(row.startTime).getTime()
    if (Number.isFinite(ms) && ms >= 0) {
      return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
    }
  }
  if (row.startTime) {
    return row.startTime.replace('T', ' ').slice(11, 19)
  }
  return '—'
}

/**
 * 任意值转缩进 JSON 文本；字符串先尝试 parse，失败则原样展示。
 * @param value 节点入参/出参
 * @return 空值返回空串，调用方据此走空态文案
 */
function prettyJson(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/**
 * 从 run 级 output 里取 END 节点那一段。
 * <p>后端 settle 写入形如 {@code {"end_1": {...}}}，取不到就整体返回，至少能看到结果。
 */
function endOutputOf(output: unknown, nodeCode: string): unknown {
  if (!output || typeof output !== 'object') {
    return output
  }
  const record = output as Record<string, unknown>
  return record[nodeCode] ?? output
}

/**
 * Agent 节点出参 → Markdown HTML。
 * <p>兼容 {@code {"text": "..."}} 与纯字符串两种形态；非 Agent 一律不渲染 Markdown，
 * 避免把工具 JSON 当正文排版。
 */
function agentMarkdown(nodeType: string, output: unknown): string {
  if (nodeType !== 'AGENT' || output === null || output === undefined) {
    return ''
  }
  let text = ''
  if (typeof output === 'string') {
    text = output
  } else if (typeof output === 'object') {
    const record = output as Record<string, unknown>
    const candidate = record.text ?? record.answer ?? record.content
    if (typeof candidate === 'string') {
      text = candidate
    }
  }
  return text ? renderSimpleMarkdown(text) : ''
}

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

/** 去人工待办中心（与执行抽屉互补：一个按 run 看，一个按人看） */
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

/* ============================ 数据加载 ============================ */

/** 打开抽屉时按默认发布指针选版本；已经加载过就不重复打接口 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      stopPolling()
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

/** 最近执行列表：点一行即可回看该次 run 的节点状态 */
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

/* ============================ 执行动作 ============================ */

/** 开始执行：先校验输入 JSON，成功后回填详情并通知画布 */
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

  // 先清掉上一次的结果与节点记忆，避免新 run 的时间轴混入旧节点展开状态
  resetTimelineMemory()
  runDetail.value = null
  emit('run-change', null)

  starting.value = true
  elapsed.value = 0
  // 点击即折叠配置区，让结果区立刻拿到全部剩余高度
  configCollapsed.value = true

  try {
    const { data } = await startWorkflowTrial({
      releaseId: releaseId.value,
      workflowId: props.workflowId,
      input
    })
    applyDetail(data.data)
    void loadHistory()
    if (data.data.status === 'SUCCESS') {
      ElMessage.success('执行成功')
    } else if (data.data.status === 'PAUSED_HUMAN') {
      ElMessage.warning('已停在人工节点，请在下方处理待办')
    } else if (data.data.status === 'FAILED') {
      ElMessage.error(data.data.errorSummary || '执行失败')
    }
  } catch {
    // 拦截器已弹出中文原因
  } finally {
    starting.value = false
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
  resetTimelineMemory()
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

/** 统一回填 + 通知父组件；顺带按最新状态决定是否继续轮询 */
function applyDetail(detail: WorkflowRunDetail) {
  runDetail.value = detail
  emit('run-change', detail)
  // 后端若把 run 留在 RUNNING（例如由应用触发），继续轮询直到终态
  if (detail.status === 'RUNNING') {
    startPolling()
  } else {
    stopPolling()
  }
}

/* ============================ 实时联动（伪 SSE） ============================ */

/** runId → 上一次看到的节点状态，用于 diff 出 node_start / node_success */
const statusSnapshot = new Map<string, string>()
let snapshotRunId: number | null = null
let pollTimer: number | undefined
let elapsedTimer: number | undefined

/** 清空节点状态记忆与展开态（换 run 时调用，避免串台） */
function resetTimelineMemory() {
  statusSnapshot.clear()
  snapshotRunId = null
  autoOpenNodeId.value = null
  manualOpen.clear()
  flashNodeId.value = null
}

/**
 * 节点状态签名：状态一变就触发 diff。
 * <p>比 deep watch 整个 detail 便宜，也不会被无关字段（如刷新时间）带跑。
 */
const nodeSignature = computed(() =>
  (runDetail.value?.nodeRuns ?? []).map((node) => `${node.nodeId}:${node.status}`).join('|')
)

watch(nodeSignature, () => {
  diffNodes()
})

/** isRunning 变化：开轮询 + 计时；结束时停表但保持配置区折叠 */
watch(
  isRunning,
  (running) => {
    if (running) {
      configCollapsed.value = true
      startElapsedTimer()
      startPolling()
      return
    }
    stopElapsedTimer()
    stopPolling()
  }
)

/** 等接口期间也要有「已用时」，否则用户看不出是慢还是卡死 */
function startElapsedTimer() {
  stopElapsedTimer()
  elapsed.value = 0
  elapsedTimer = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
}

function stopElapsedTimer() {
  if (elapsedTimer !== undefined) {
    window.clearInterval(elapsedTimer)
    elapsedTimer = undefined
  }
}

/** 轮询 run 详情：只在 RUNNING 时开，终态 / 关闭抽屉立刻停 */
function startPolling() {
  if (pollTimer !== undefined) {
    return
  }
  pollTimer = window.setInterval(() => {
    void pollOnce()
  }, POLL_INTERVAL)
}

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

/** 单次拉取；任何失败都停止轮询，避免后台无限空转打接口 */
async function pollOnce() {
  const detail = runDetail.value
  if (!detail || detail.status !== 'RUNNING') {
    stopPolling()
    return
  }
  try {
    const { data } = await getWorkflowRun(detail.id)
    applyDetail(data.data)
  } catch {
    stopPolling()
  }
}

/**
 * 对比节点状态快照，找出刚变化的节点：自动展开 + 荧光高亮 + 滚动到可见。
 * <p>首次拿到整份结果时不做逐个高亮（否则会同时闪很多张卡），
 * 改为按顺序滑入并把视野留在时间轴顶部。
 */
function diffNodes() {
  const detail = runDetail.value
  if (!detail) {
    resetTimelineMemory()
    return
  }
  if (snapshotRunId !== detail.id) {
    statusSnapshot.clear()
    snapshotRunId = detail.id
  }
  // 与时间轴同源：按真实执行序找「最后一个」节点，避免图上 END 靠后导致自动展开错位
  const rows = orderedNodeRuns.value
  if (!rows.length) {
    return
  }

  // 首次渲染：铺快照、自动展开「失败 / 运行中 / 最后一个」节点，视野回顶部
  if (!statusSnapshot.size) {
    rows.forEach((row) => statusSnapshot.set(row.nodeId, row.status))
    const focus =
      rows.find((row) => row.status === 'FAILED') ||
      rows.find((row) => row.status === 'RUNNING') ||
      rows[rows.length - 1]
    autoOpenNodeId.value = focus ? focus.id : null
    void scrollTimelineTo('top')
    return
  }

  let changed: WorkflowNodeRunRow | null = null
  rows.forEach((row) => {
    if (statusSnapshot.get(row.nodeId) !== row.status) {
      statusSnapshot.set(row.nodeId, row.status)
      changed = row
    }
  })
  if (!changed) {
    return
  }
  const changedId = (changed as WorkflowNodeRunRow).id
  autoOpenNodeId.value = changedId
  flashNodeId.value = changedId
  window.setTimeout(() => {
    if (flashNodeId.value === changedId) {
      flashNodeId.value = null
    }
  }, 1200)
  void scrollToNode(changedId)
}

/**
 * 滚动时间轴容器（只滚内部，不动整页抽屉）。
 * @param position top = 回到顶部；其余按节点 id 定位
 */
async function scrollTimelineTo(position: 'top' | number) {
  await nextTick()
  const container = timelineRef.value
  if (!container) {
    return
  }
  if (position === 'top') {
    container.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  const target = container.querySelector<HTMLElement>(`[data-node-id="${position}"]`)
  target?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

/** 滚动到指定节点卡片 */
function scrollToNode(id: number) {
  void scrollTimelineTo(id)
}

onBeforeUnmount(() => {
  stopPolling()
  stopElapsedTimer()
})

/* ============================ 文案映射 ============================ */

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
/* 抽屉正文不滚动：滚动交给结果区内部，标题与状态条保持常驻 */
.wf-exec-drawer :deep(.el-drawer__body) {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.wf-exec {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  box-sizing: border-box;
  min-height: 0;
  padding: 0 16px 16px;
}

/* ==================== 配置区 ==================== */
.wf-exec__config {
  flex: none;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
  transition: border-color 0.2s ease;
}

.wf-exec__config.is-collapsed {
  border-color: #ddd9ff;
  background: #fbfaff;
}

/* 关键折叠动画：1fr → 0fr，内容高度自适应，无需写死 max-height */
.wf-exec__config-clip {
  display: grid;
  grid-template-rows: 1fr;
  transition: grid-template-rows 0.3s ease;
}

.wf-exec__config.is-collapsed .wf-exec__config-clip {
  grid-template-rows: 0fr;
}

.wf-exec__config-inner {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  max-height: 46vh;
  overflow: hidden auto;
  padding: 12px;
  box-sizing: border-box;
}

.wf-exec__config.is-collapsed .wf-exec__config-inner {
  padding-top: 0;
  padding-bottom: 0;
}

.wf-exec__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.wf-exec__label {
  color: #374151;
  font-size: 13px;
  font-weight: 600;
}

.wf-exec__select {
  width: 220px;
}

.wf-exec__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-exec__error {
  color: #dc2626;
  font-size: 12px;
}

.wf-exec__actions {
  display: flex;
  gap: 8px;
}

.wf-exec__history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.wf-exec__link {
  padding: 0;
  border: 0;
  background: none;
  color: #635bff;
  font-size: 12px;
  cursor: pointer;
}

.wf-exec__link:hover {
  text-decoration: underline;
}

/* ---------- 折叠后的 48px 摘要栏 ---------- */
.wf-exec__brief {
  display: flex;
  align-items: center;
  gap: 8px;
  box-sizing: border-box;
  height: 0;
  padding: 0 12px;
  overflow: hidden;
  border: 0;
  background: transparent;
  opacity: 0;
  text-align: left;
  cursor: pointer;
  transition:
    height 0.3s ease,
    opacity 0.18s ease;
}

.wf-exec__config.is-collapsed .wf-exec__brief {
  height: 48px;
  opacity: 1;
}

.wf-exec__brief-version {
  flex: none;
  color: #4c1d95;
  font-size: 12px;
  font-weight: 700;
}

.wf-exec__brief-sep {
  flex: none;
  color: #c4b5fd;
}

.wf-exec__brief-input {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #6b7280;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-exec__brief-toggle {
  flex: none;
  color: #635bff;
  font-size: 12px;
  font-weight: 600;
}

.wf-exec__brief:hover .wf-exec__brief-toggle {
  text-decoration: underline;
}

/* ==================== 结果区 ==================== */
.wf-exec__result {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.wf-exec__statusbar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafafb;
}

.wf-exec__status-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9ca3af;
}

.wf-exec__statusbar.is-running {
  border-color: #ddd9ff;
  background: #f6f4ff;
}

.wf-exec__statusbar.is-running .wf-exec__status-dot {
  background: #635bff;
  animation: wf-exec-pulse 1.4s ease-out infinite;
}

.wf-exec__statusbar.is-success .wf-exec__status-dot {
  background: #10b981;
}

.wf-exec__statusbar.is-failed .wf-exec__status-dot {
  background: #ef4444;
}

.wf-exec__status-text {
  color: #111827;
  font-size: 13px;
  font-weight: 600;
}

.wf-exec__statusbar.is-running .wf-exec__status-text {
  color: #4c1d95;
}

.wf-exec__status-meta {
  margin-left: auto;
  color: #9ca3af;
  font-size: 12px;
}

.wf-exec__result-alert {
  flex: none;
}

/* 纵向滚动容器：新节点滑入后自动滚到底部可见 */
.wf-exec__timeline {
  flex: 1;
  min-height: 0;
  overflow: hidden auto;
  padding-right: 4px;
}

.wf-exec__blank {
  padding: 40px 8px;
  color: #9ca3af;
  text-align: center;
}

.wf-exec__blank-title {
  margin-bottom: 6px;
  color: #6b7280;
  font-size: 13px;
  font-weight: 600;
}

.wf-exec__blank-desc {
  font-size: 12px;
  line-height: 1.6;
}

.wf-exec__pending {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 12px;
  border: 1px dashed #ddd9ff;
  border-radius: 10px;
  color: #4c1d95;
  font-size: 13px;
}

.wf-exec__pending .wf-exec__dot {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #635bff;
}

/* ---------- 纵向时间轴 ---------- */
.wf-exec__steps {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 4px 0 0;
  list-style: none;
}

.wf-exec__step {
  display: flex;
  gap: 10px;
  align-items: stretch;
  /* 首次渲染逐条滑入；--step-index 由模板按顺序写入 */
  animation: wf-exec-slide-in 0.3s ease both;
  animation-delay: calc(var(--step-index, 0) * 45ms);
}

.wf-exec__rail {
  position: relative;
  flex: none;
  width: 12px;
}

.wf-exec__dot {
  position: absolute;
  top: 12px;
  left: 1px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #d1d5db;
  box-shadow: 0 0 0 2px #f3f4f6;
  transition: background 0.2s ease;
}

.wf-exec__line {
  position: absolute;
  top: 25px;
  bottom: 0;
  left: 5px;
  width: 2px;
  background: #e4e7ef;
}

.wf-exec__step:last-child .wf-exec__line {
  display: none;
}

.wf-exec__step.is-success .wf-exec__dot {
  background: #10b981;
  box-shadow: 0 0 0 2px #d1fae5;
}

.wf-exec__step.is-failed .wf-exec__dot {
  background: #ef4444;
  box-shadow: 0 0 0 2px #fee2e2;
}

/* 运行中的紫色呼吸灯 */
.wf-exec__step.is-running .wf-exec__dot {
  background: #635bff;
  box-shadow: 0 0 0 2px #e9e6ff;
  animation: wf-exec-pulse 1.4s ease-out infinite;
}

.wf-exec__card {
  flex: 1;
  min-width: 0;
  margin-bottom: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.wf-exec__step.is-failed .wf-exec__card {
  border-color: #fecaca;
  background: #fffbfb;
}

.wf-exec__step.is-running .wf-exec__card {
  border-color: #ddd9ff;
}

/* 状态跃迁时闪一下，替代 SSE 推送的「新事件」提示 */
.wf-exec__step.is-flash .wf-exec__card {
  animation: wf-exec-flash 1.2s ease;
}

.wf-exec__card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 11px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.wf-exec__card-type {
  flex: none;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f3f4f6;
  color: #6b7280;
  font-size: 11px;
  font-weight: 600;
}

.wf-exec__card-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #111827;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-exec__card-time {
  flex: none;
  color: #9ca3af;
  font-size: 11px;
}

.wf-exec__card-chevron {
  flex: none;
  color: #9ca3af;
  font-size: 12px;
  transition: transform 0.2s ease;
}

.wf-exec__card-chevron.is-open {
  transform: rotate(180deg);
}

.wf-exec__card-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 11px 11px;
}

.wf-exec__block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.wf-exec__block-title {
  color: #6b7280;
  font-size: 11px;
  font-weight: 600;
}

.wf-exec__card-error {
  padding: 6px 8px;
  border-radius: 6px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.5;
}

.wf-exec__muted {
  color: #9ca3af;
  font-size: 12px;
}

.wf-exec__json {
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

.wf-exec__trace {
  padding: 4px 0 10px 22px;
}

.wf-exec__trace-link {
  padding: 0;
  border: 0;
  background: none;
  color: #dc2626;
  font-size: 12px;
  cursor: pointer;
}

.wf-exec__trace-link:hover {
  text-decoration: underline;
}

.wf-exec__trace-json {
  max-height: 60vh;
}

/* ---------- 历史执行列表 ---------- */
.wf-exec__history {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 180px;
  overflow: auto;
}

.wf-exec__history-row {
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

.wf-exec__history-row:hover,
.wf-exec__history-row.is-active {
  border-color: #635bff;
  background: #f5f3ff;
}

.wf-exec__history-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.wf-exec__history-version {
  color: #6b7280;
}

.wf-exec__history-time {
  margin-left: auto;
  color: #9ca3af;
}

/* ---------- 人工待办 ---------- */
.wf-exec__human-zone {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 0 4px;
  border-top: 1px dashed #e5e7eb;
  margin-top: 4px;
}

.wf-exec__human {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border: 1px solid #fde68a;
  border-radius: 8px;
  background: #fffbeb;
}

.wf-exec__human-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #92400e;
  font-size: 12px;
}

.wf-exec__human-actions {
  display: flex;
  gap: 8px;
}

.wf-exec__human-tip {
  color: #b45309;
  font-size: 12px;
  line-height: 1.5;
}

/* ---------- Markdown 正文（Agent 输出） ---------- */
.wf-exec__md {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f9fafb;
  color: #374151;
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.wf-exec__md :deep(p) {
  margin: 0.35em 0;
}

.wf-exec__md :deep(h1),
.wf-exec__md :deep(h2),
.wf-exec__md :deep(h3) {
  margin: 0.45em 0 0.25em;
  font-size: 1.05em;
  font-weight: 650;
}

.wf-exec__md :deep(ul),
.wf-exec__md :deep(ol) {
  margin: 0.35em 0;
  padding-left: 1.2em;
}

.wf-exec__md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.wf-exec__md :deep(pre) {
  margin: 0.4em 0;
  padding: 8px 10px;
  overflow: auto;
  border-radius: 8px;
  background: #eef0f6;
}

.wf-exec__md :deep(.md-table-wrap) {
  display: block;
  max-width: 100%;
  margin: 0.45em 0;
  overflow-x: auto;
}

.wf-exec__md :deep(.md-table) {
  display: table;
  width: auto;
  max-width: 100%;
  border-collapse: collapse;
  border: 1px solid #d0d5dd;
  background: #fff;
}

.wf-exec__md :deep(.md-table th),
.wf-exec__md :deep(.md-table td) {
  padding: 4px 8px;
  border: 1px solid #d0d5dd;
  text-align: left;
  vertical-align: top;
}

.wf-exec__md :deep(.md-table th) {
  background: #f3f4f8;
  font-weight: 600;
}

/* ==================== 动画 ==================== */
/* 紫色呼吸灯：运行中的节点圆点 / 状态条 */
@keyframes wf-exec-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(99, 91, 255, 0.55);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(99, 91, 255, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(99, 91, 255, 0);
  }
}

/* 新节点卡片滑入 */
@keyframes wf-exec-slide-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 状态跃迁高亮：卡片边框由紫回到常态 */
@keyframes wf-exec-flash {
  0% {
    border-color: #635bff;
    box-shadow: 0 0 0 3px rgba(99, 91, 255, 0.18);
  }
  60% {
    border-color: #635bff;
    box-shadow: 0 0 0 3px rgba(99, 91, 255, 0.12);
  }
  100% {
    border-color: #e5e7eb;
    box-shadow: none;
  }
}

/* 无障碍：用户开启「减少动态效果」时关掉呼吸灯与滑入 */
@media (prefers-reduced-motion: reduce) {
  .wf-exec__step,
  .wf-exec__step.is-flash .wf-exec__card,
  .wf-exec__statusbar.is-running .wf-exec__status-dot,
  .wf-exec__step.is-running .wf-exec__dot {
    animation: none;
  }

  .wf-exec__config-clip,
  .wf-exec__brief {
    transition: none;
  }
}
</style>
