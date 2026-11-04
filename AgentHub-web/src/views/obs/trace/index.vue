<template>
  <div class="page">
    <PageHeader
      title="调用追踪"
      :description="pageDescription"
    >
      <!-- 入口类型：Agent 保持会话三栏；工作流复用既有按 workflowId 查询，不新造表 -->
      <template #extra>
        <el-segmented
          :model-value="traceType"
          :options="traceTypeOptions"
          class="type-switch"
          @change="onTypeChange"
        />
      </template>
    </PageHeader>

    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <!-- 工作流入口：与「标题」同属筛选条件，放在前面；未选择定义时按全部运行记录查询 -->
        <el-form-item v-if="workflowMode" label="工作流">
          <el-select
            v-if="workflowOptions.length > 0 || workflowLoading"
            :model-value="workflowId ?? undefined"
            class="wf-select"
            filterable
            clearable
            placeholder="全部"
            :loading="workflowLoading"
            @change="onWorkflowSelect"
          >
            <el-option v-for="item in workflowOptions" :key="item.id" :label="item.label" :value="item.id" />
          </el-select>
          <!-- 无 workflow:def:view 时下拉拿不到选项：至少回显跳转带来的定义名，避免只看到裸 id -->
          <el-tag v-else-if="workflowId != null" effect="plain">{{ workflowName || `#${workflowId}` }}</el-tag>
          <el-tag v-else effect="plain">全部</el-tag>
        </el-form-item>
        <el-form-item v-if="!workflowMode" label="智能体">
          <el-select
            v-model="agentId"
            clearable
            filterable
            placeholder="全部"
            class="filter-select"
          >
            <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!workflowMode" label="会话">
          <el-input
            v-model="sessionQ"
            class="filter-input"
            clearable
            placeholder="预览或会话编号"
          />
        </el-form-item>
        <el-form-item :label="workflowMode ? '标题' : '问题'">
          <el-input
            v-model="traceQ"
            class="filter-input"
            clearable
            :placeholder="workflowMode ? '搜索运行标题' : '搜索问题'"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="onSearch">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" @click="onReset">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
      <!-- 工作流入口说明：未选择定义时提示当前查询范围是全部工作流运行记录 -->
      <span v-if="workflowMode" class="wf-hint">{{ workflowHint }}</span>
    </div>

    <!-- 三栏：会话 25% / 问题 25% / 执行链路 50%；工作流入口下会话列无意义，退化为两栏 -->
    <div class="board" :class="{ 'board--workflow': workflowMode }">
      <section v-if="!workflowMode" class="col">
        <div class="col-head">
          <h2>会话</h2>
        </div>
        <div v-loading="sessionLoading" class="col-body col-body--scroll">
          <button
            v-for="row in sessions"
            :key="row.id"
            type="button"
            class="item"
            :class="{ active: row.id === selectedSessionId }"
            @click="pickSession(row)"
          >
            <span class="item-title">{{ row.sessionTitle || row.lastMsgPreview || '未命名会话' }}</span>
            <span class="item-meta">{{ agentLabel(row.agentName, row.agentId) }} · {{ row.startTime || '—' }}</span>
          </button>
          <p v-if="!sessionLoading && sessions.length === 0" class="empty">没有会话</p>
        </div>
        <div class="col-pager">
          <span class="col-pager-meta">共 {{ sessionTotal }} 条 · 第 {{ sessionQuery.current }}/{{ sessionPages }} 页</span>
          <div class="col-pager-nav">
            <el-button
              size="small"
              text
              :disabled="sessionQuery.current <= 1 || sessionLoading"
              @click="goSessionPage(sessionQuery.current - 1)"
            >
              ‹
            </el-button>
            <el-button
              size="small"
              text
              :disabled="sessionQuery.current >= sessionPages || sessionLoading"
              @click="goSessionPage(sessionQuery.current + 1)"
            >
              ›
            </el-button>
            <label class="col-pager-jump">
              跳至
              <input
                v-model="sessionJump"
                type="number"
                min="1"
                :max="sessionPages"
                :disabled="sessionLoading"
                @keyup.enter="commitSessionJump"
                @blur="commitSessionJump"
              />
              页
            </label>
          </div>
        </div>
      </section>

      <section class="col">
        <div class="col-head">
          <h2>{{ workflowMode ? '运行记录' : '问题' }}</h2>
        </div>
        <div v-loading="traceLoading" class="col-body col-body--scroll">
          <button
            v-for="row in traces"
            :key="row.traceId"
            type="button"
            class="item"
            :class="{ active: row.traceId === selectedTraceId }"
            @click="pickTrace(row)"
          >
            <!-- 问题标题：只露 2 行，超长截断；悬浮弹深色框看全文（限宽限高） -->
            <ClampText
              :text="row.title"
              placeholder="（无问题文本）"
              :lines="2"
              placement="top"
            />
            <span class="item-meta">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              {{ row.startTime || '—' }}
              <template v-if="row.latencyMs != null">
                · <span :class="latencyClass(row.latencyMs)">{{ latency(row.latencyMs) }}</span>
              </template>
              <template v-if="row.tokenTotal != null"> · {{ row.tokenTotal }} tok</template>
              <template v-if="row.workflowRunId"> · run #{{ row.workflowRunId }}</template>
            </span>
          </button>
          <p v-if="!canQueryTraces" class="empty">先选择左侧会话</p>
          <p v-else-if="!traceLoading && traces.length === 0" class="empty">
            {{
              workflowMode
                ? workflowActive
                  ? '该工作流还没有运行记录'
                  : '还没有工作流运行记录'
                : '没有问题'
            }}
          </p>
        </div>
        <div class="col-pager">
          <span class="col-pager-meta">共 {{ traceTotal }} 条 · 第 {{ traceQuery.current }}/{{ tracePages }} 页</span>
          <div class="col-pager-nav">
            <el-button
              size="small"
              text
              :disabled="!canQueryTraces || traceQuery.current <= 1 || traceLoading"
              @click="goTracePage(traceQuery.current - 1)"
            >
              ‹
            </el-button>
            <el-button
              size="small"
              text
              :disabled="!canQueryTraces || traceQuery.current >= tracePages || traceLoading"
              @click="goTracePage(traceQuery.current + 1)"
            >
              ›
            </el-button>
            <label class="col-pager-jump">
              跳至
              <input
                v-model="traceJump"
                type="number"
                min="1"
                :max="tracePages"
                :disabled="!canQueryTraces || traceLoading"
                @keyup.enter="commitTraceJump"
                @blur="commitTraceJump"
              />
              页
            </label>
          </div>
        </div>
      </section>

      <section class="col col--span">
        <div class="col-head col-head--row">
          <h2>
            执行链路
            <span v-if="selectedTraceId" class="trace-id">({{ selectedTraceId }})</span>
          </h2>
          <div class="col-head-actions">
            <el-button
              v-if="selectedTraceId && user.hasPerm('eval:suite:edit')"
              size="small"
              @click="openPromoteDialog"
            >
              加入评测集
            </el-button>
            <el-tooltip content="放大查看拓扑与宽屏对照" placement="top">
              <el-button
                class="expand-btn"
                text
                :disabled="!canExpandSpans"
                @click="spanDrawerOpen = true"
              >
                <el-icon :size="18"><FullScreen /></el-icon>
              </el-button>
            </el-tooltip>
          </div>
        </div>
        <div v-loading="spanLoading" class="col-body col-body--scroll">
          <p v-if="!user.hasPerm('obs:span:list')" class="empty">没有步骤查询权限</p>
          <p v-else-if="selectedTraceId == null" class="empty">
            {{ workflowMode ? '选择一条运行记录后查看步骤' : '选择一条问题后查看步骤' }}
          </p>
          <div v-else class="span-tree">
            <!-- 三栏只渲染瀑布流；拓扑图仅放大后展示 -->
            <TraceSpanFlow :spans="spans" />
          </div>
          <p v-if="selectedTraceId && user.hasPerm('obs:span:list') && !spanLoading && spans.length === 0" class="empty">
            没有步骤
          </p>
        </div>
      </section>
    </div>

    <!-- 放大：复用执行链路右拉（观测内已持 spans，宽屏对照） -->
    <TraceLinkDrawer
      v-model="spanDrawerOpen"
      :trace-id="selectedTraceId"
      :spans="spans"
      size="85%"
    />

    <!-- 最小回流：选套件 + 预填输入/终答摘要 → from-trace；无赞踩账本 -->
    <el-dialog v-model="promoteOpen" title="加入评测集" width="520px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="目标套件" required>
          <el-select v-model="promoteForm.suiteId" filterable placeholder="选择套件" class="promote-full">
            <el-option
              v-for="s in promoteSuites"
              :key="s.id"
              :label="`${s.name} (${s.suiteKey})`"
              :value="s.id"
              :disabled="!!s.readOnly"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="用户输入" required>
          <el-input v-model="promoteForm.message" type="textarea" :rows="3" placeholder="问题原文或摘要" />
        </el-form-item>
        <el-form-item label="终答摘要">
          <el-input v-model="promoteForm.finalAnswer" type="textarea" :rows="3" placeholder="可粘贴终答；可空" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="promoteForm.note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="promoteOpen = false">取消</el-button>
        <el-button type="primary" :loading="promoteSaving" @click="submitPromote">写入并升版</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'CallTrace' })
/**
 * 调用追踪。所属域：obs。
 * 顶部 Agent / 工作流切换：Agent 保持会话三栏；工作流按定义看 Run / 节点 TASK（复用既有 workflowId 查询），
 * 未选定义时默认分页查询全部工作流运行记录。
 * 右栏瀑布流常驻；顶部拓扑仅在放大抽屉中展示。
 */
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { FullScreen, RefreshRight, Search } from '@element-plus/icons-vue'
import { pageAgents } from '@/api/agent'
import { pageEvalSuites, promoteFromTrace } from '@/api/eval'
import { listObsSpans, pageObsSessions, pageObsTraces, pageObsTracesByWorkflow } from '@/api/obs'
import { pageWorkflowDefs } from '@/api/workflow'
import type { AgentCard } from '@/types/agent'
import type { EvalSuiteRow } from '@/types/eval'
import type { ObsSession, ObsSpan, ObsTrace } from '@/types/obs'
import type { WorkflowDefRow } from '@/types/workflow'
import { useUserStore } from '@/stores/user'
import PageHeader from '@/components/PageHeader.vue'
import ClampText from '@/components/common/ClampText.vue'
import TraceLinkDrawer from '@/components/obs/TraceLinkDrawer.vue'
import TraceSpanFlow from '@/components/obs/TraceSpanFlow.vue'
import {
  buildTraceQuery,
  firstQueryValue,
  parseWorkflowId,
  resolveTraceType,
  TRACE_TYPE_AGENT,
  TRACE_TYPE_WORKFLOW,
  type TraceType
} from './traceQuery'

const user = useUserStore()
const route = useRoute()
const router = useRouter()
/** 入口类型；以路由 query.type 为准，缺省按有无 workflowId 推断 */
const traceType = ref<TraceType>(TRACE_TYPE_AGENT)
/** 工作流定义 id；工作流类型下非空才发起查询 */
const workflowId = ref<number | null>(null)
const workflowName = ref('')
/** 工作流下拉选项：从工作流定义分页接口懒加载，无权限时退化为「从列表进入」提示 */
const workflows = ref<WorkflowDefRow[]>([])
const workflowLoading = ref(false)
/** 已应用的路由条件快照：KeepAlive 下再次从列表跳转时据此判断是否需要重拉 */
let appliedQueryKey: string | null = null

const agents = ref<AgentCard[]>([])
const agentId = ref<number | undefined>()

const sessions = ref<ObsSession[]>([])
const sessionTotal = ref(0)
const sessionLoading = ref(false)
const sessionQ = ref('')
const sessionQuery = reactive({ current: 1, size: 20 })
const sessionJump = ref('1')
const selectedSessionId = ref<number | null>(null)

const traces = ref<ObsTrace[]>([])
const traceTotal = ref(0)
const traceLoading = ref(false)
const traceQ = ref('')
const traceQuery = reactive({ current: 1, size: 20 })
const traceJump = ref('1')
const selectedTraceId = ref<string | null>(null)

const spans = ref<ObsSpan[]>([])
const spanLoading = ref(false)
/** 执行链路放大抽屉（宽屏对照入参/出参） */
const spanDrawerOpen = ref(false)

/** 加入评测集：最小弹层；无持久赞踩 */
const promoteOpen = ref(false)
const promoteSaving = ref(false)
const promoteSuites = ref<EvalSuiteRow[]>([])
const promoteForm = reactive({
  suiteId: undefined as number | undefined,
  message: '',
  finalAnswer: '',
  note: ''
})

/** 有选中问题且已有步骤时才允许放大 */
const canExpandSpans = computed(
  () => !!selectedTraceId.value && user.hasPerm('obs:span:list') && spans.value.length > 0
)

/** 顶部类型切换选项 */
const traceTypeOptions = [
  { label: 'Agent', value: TRACE_TYPE_AGENT },
  { label: '工作流', value: TRACE_TYPE_WORKFLOW }
]

/** 工作流入口：会话列无意义，退化为「运行记录 + 链路」两栏 */
const workflowMode = computed(() => traceType.value === TRACE_TYPE_WORKFLOW)
/** 工作流类型下是否已选中具体定义；未选中时查询全部工作流运行记录 */
const workflowActive = computed(() => workflowMode.value && workflowId.value != null)
/** 工作流入口未选定义也查全部；Agent 入口仍需先选会话 */
const canQueryTraces = computed(() => workflowMode.value || selectedSessionId.value != null)

/** 下拉选项：当前 def 不在前 100 条（如已删除的历史定义）时补一个占位，避免只显示裸 id */
const workflowOptions = computed(() => {
  const options = workflows.value.map((item) => ({ id: item.id, label: item.name }))
  if (workflowId.value != null && !options.some((item) => item.id === workflowId.value)) {
    options.unshift({ id: workflowId.value, label: workflowName.value || `#${workflowId.value}` })
  }
  return options
})

/** 工作流侧的说明文案：有定义看该定义的 Run，无定义时明确当前是全部运行记录 */
const workflowHint = computed(() => {
  if (workflowActive.value) {
    return '按工作流定义查看 Run / 节点 TASK'
  }
  // 无 workflow:def:view 时下拉不可用，这里说明「全部」范围，避免误以为查询条件未生效
  if (workflowOptions.value.length === 0) {
    return '全部工作流运行记录（无定义查看权限）'
  }
  return '未选择定义时查看全部工作流运行记录'
})

const pageDescription = computed(() =>
  workflowMode.value
    ? '按工作流定义查看 Run / 节点 TASK：一次 run 一条记录，右栏按节点展开步骤树。'
    : '按会话查看每次问题和执行步骤。一次问题一条记录，路由按父子步骤展开，不计入 Token 用量。'
)

/** 总页数至少为 1，便于跳转输入框 max 与按钮禁用态。 */
const sessionPages = computed(() => Math.max(1, Math.ceil(sessionTotal.value / sessionQuery.size) || 1))
const tracePages = computed(() => Math.max(1, Math.ceil(traceTotal.value / traceQuery.size) || 1))

watch(
  () => sessionQuery.current,
  (page) => {
    sessionJump.value = String(page)
  }
)
watch(
  () => traceQuery.current,
  (page) => {
    traceJump.value = String(page)
  }
)

/**
 * 读取路由上的入口类型与工作流条件。返回 true 表示条件变化、需要重新拉取列表。
 * <p>type 优先；缺省时有 workflowId 进工作流，否则维持 Agent 三栏。
 * workflowId 非法（空串 / NaN / ≤0）按「无过滤」处理；type=agent 时忽略 workflowId。
 */
function applyRouteQuery(): boolean {
  const id = parseWorkflowId(route.query.workflowId)
  const type = resolveTraceType(route.query.type, id)
  const effectiveId = type === TRACE_TYPE_WORKFLOW ? id : null
  const name = firstQueryValue(route.query.workflowName)
  const key = `${type}|${effectiveId == null ? '' : effectiveId}`
  if (appliedQueryKey === key) {
    return false
  }
  appliedQueryKey = key
  traceType.value = type
  workflowId.value = effectiveId
  workflowName.value = effectiveId != null ? name : ''
  return true
}

/** 条件变化后的统一收口：清空选中与步骤，按当前类型重新查 */
function reloadForRouteChange() {
  selectedSessionId.value = null
  traceQ.value = ''
  traceQuery.current = 1
  resetDetail()
  if (workflowMode.value) {
    // 定义下拉与运行记录并行加载：未选定义时照样查全部，下拉失败也不影响列表
    void ensureWorkflowOptions()
    loadTraces()
    return
  }
  reloadSessions()
}

/**
 * 切换入口类型：写回路由 query，由 watch 统一触发重查。
 * <p>进入工作流时清掉 Agent 侧不兼容筛选；回到 Agent 时丢弃 workflowId / workflowName。
 *
 * @param value el-segmented 回传的选项值
 */
function onTypeChange(value: string | number | boolean) {
  const next: TraceType = value === TRACE_TYPE_WORKFLOW ? TRACE_TYPE_WORKFLOW : TRACE_TYPE_AGENT
  if (next === TRACE_TYPE_AGENT) {
    agentId.value = undefined
    sessionQ.value = ''
    void router.replace({ path: route.path, query: buildTraceQuery({ type: TRACE_TYPE_AGENT }) })
    return
  }
  agentId.value = undefined
  sessionQ.value = ''
  // 从 Agent 切进来时 workflowId 已被清空：工作流侧默认查全部运行记录，用户可再选定义收窄
  void router.replace({ path: route.path, query: buildTraceQuery({ type: TRACE_TYPE_WORKFLOW }) })
}

/**
 * 下拉选择工作流定义：写回 workflowId / workflowName，另存定义名便于刷新后回显。
 *
 * @param value 定义 id；清空下拉时为 undefined
 */
function onWorkflowSelect(value: number | string | undefined) {
  const id = parseWorkflowId(value)
  if (id == null) {
    void router.replace({ path: route.path, query: buildTraceQuery({ type: TRACE_TYPE_WORKFLOW }) })
    return
  }
  const picked = workflows.value.find((item) => item.id === id)
  void router.replace({
    path: route.path,
    query: buildTraceQuery({
      type: TRACE_TYPE_WORKFLOW,
      workflowId: id,
      workflowName: picked?.name ?? (id === workflowId.value ? workflowName.value : '')
    })
  })
}

/**
 * 懒加载工作流定义下拉（最多 100 条）。
 * <p>调用追踪权限与 workflow:def:view 可能不同；失败时不阻塞页面，只保留「从列表进入」提示。
 */
async function ensureWorkflowOptions() {
  // 没有定义查看权限时直接跳过：避免必然 403 的提示，页面保留「从工作流列表进入」引导
  if (!user.hasPerm('workflow:def:view')) {
    return
  }
  if (workflows.value.length > 0 || workflowLoading.value) {
    return
  }
  workflowLoading.value = true
  try {
    const { data } = await pageWorkflowDefs({ current: 1, size: 100 })
    workflows.value = data.data.records
  } catch {
    workflows.value = []
  } finally {
    workflowLoading.value = false
  }
}

/** 有名称用名称，解析失败或缺失时用 #id。 */
function agentLabel(name: string | null | undefined, id: number | null | undefined) {
  if (name && name.trim()) {
    return name
  }
  if (id == null) {
    return '—'
  }
  return `#${id}`
}

function statusLabel(status: string | null) {
  if (status === 'RUNNING') return '进行中'
  if (status === 'OK') return '成功'
  if (status === 'ERROR') return '失败'
  if (status === 'CANCELLED') return '已取消'
  return status || '—'
}

function statusType(status: string | null): 'success' | 'danger' | 'info' | 'warning' {
  if (status === 'OK') return 'success'
  if (status === 'ERROR') return 'danger'
  if (status === 'CANCELLED') return 'info'
  return 'warning'
}

function latency(ms: number | null | undefined) {
  return ms == null ? '—' : `${ms} ms`
}

/** 中栏问题列表耗时预警：与右栏同一阈值。 */
function latencyClass(ms: number | null | undefined) {
  if (ms == null) return ''
  if (ms > 3000) return 'lat-danger'
  if (ms > 1000) return 'lat-warn'
  return 'lat-ok'
}

function reloadSessions() {
  sessionQuery.current = 1
  loadSessions()
}

/** 点查询：会话与问题条件同时生效；有选中会话时刷新问题列表。工作流模式只重查运行记录。 */
function onSearch() {
  if (workflowMode.value) {
    reloadTraces()
    return
  }
  reloadSessions()
  if (selectedSessionId.value != null) {
    reloadTraces()
  }
}

/** 清空智能体 / 会话 / 问题筛选后重新拉取。工作流模式保留定义条件，只清关键字。 */
function onReset() {
  agentId.value = undefined
  sessionQ.value = ''
  traceQ.value = ''
  if (workflowMode.value) {
    reloadTraces()
    return
  }
  selectedSessionId.value = null
  resetDetail()
  reloadSessions()
}

/**
 * 会话翻页。页码钳在 [1, 总页数]，与当前页相同则不请求。
 */
function goSessionPage(page: number) {
  const next = Math.min(Math.max(1, page), sessionPages.value)
  if (next === sessionQuery.current) {
    sessionJump.value = String(sessionQuery.current)
    return
  }
  sessionQuery.current = next
  loadSessions()
}

/** 跳转输入：回车或失焦时落到合法页。 */
function commitSessionJump() {
  const parsed = Number.parseInt(String(sessionJump.value), 10)
  if (Number.isNaN(parsed)) {
    sessionJump.value = String(sessionQuery.current)
    return
  }
  goSessionPage(parsed)
}

async function loadSessions() {
  sessionLoading.value = true
  try {
    // 筛条件变严后旧页码可能越界：先按当前页拉，再钳到末页重拉一次
    for (let attempt = 0; attempt < 2; attempt++) {
      const { data } = await pageObsSessions({
        current: sessionQuery.current,
        size: sessionQuery.size,
        q: sessionQ.value.trim() || undefined,
        agentId: agentId.value
      })
      sessions.value = data.data.records
      sessionTotal.value = data.data.total
      if (sessionQuery.current <= sessionPages.value) {
        break
      }
      sessionQuery.current = sessionPages.value
    }
    sessionJump.value = String(sessionQuery.current)
    if (selectedSessionId.value != null && !sessions.value.some((row) => row.id === selectedSessionId.value)) {
      selectedSessionId.value = null
      resetDetail()
    }
  } finally {
    sessionLoading.value = false
  }
}

function pickSession(row: ObsSession) {
  selectedSessionId.value = row.id
  // 保留顶栏「问题」筛选，点会话后按当前关键字拉问题列表
  traceQuery.current = 1
  traceJump.value = '1'
  resetDetail()
  loadTraces()
}

function reloadTraces() {
  traceQuery.current = 1
  loadTraces()
}

/**
 * 提问翻页。未选会话且非工作流模式时不请求。
 */
function goTracePage(page: number) {
  if (!canQueryTraces.value) {
    return
  }
  const next = Math.min(Math.max(1, page), tracePages.value)
  if (next === traceQuery.current) {
    traceJump.value = String(traceQuery.current)
    return
  }
  traceQuery.current = next
  loadTraces()
}

function commitTraceJump() {
  if (!canQueryTraces.value) {
    return
  }
  const parsed = Number.parseInt(String(traceJump.value), 10)
  if (Number.isNaN(parsed)) {
    traceJump.value = String(traceQuery.current)
    return
  }
  goTracePage(parsed)
}

async function loadTraces() {
  if (!canQueryTraces.value) {
    traces.value = []
    traceTotal.value = 0
    traceJump.value = '1'
    return
  }
  traceLoading.value = true
  try {
    // 筛条件变严后旧页码可能越界：先按当前页拉，再钳到末页重拉一次
    for (let attempt = 0; attempt < 2; attempt++) {
      const params = {
        current: traceQuery.current,
        size: traceQuery.size,
        q: traceQ.value.trim() || undefined
      }
      // 工作流模式：选了定义按 defId 过滤，未选则查全部工作流运行记录；对话模式仍按会话
      const { data } = workflowMode.value
        ? await pageObsTracesByWorkflow({ ...params, workflowId: workflowId.value ?? undefined })
        : await pageObsTraces(selectedSessionId.value as number, params)
      traces.value = data.data.records
      traceTotal.value = data.data.total
      if (traceQuery.current <= tracePages.value) {
        break
      }
      traceQuery.current = tracePages.value
    }
    traceJump.value = String(traceQuery.current)
    if (selectedTraceId.value != null && !traces.value.some((row) => row.traceId === selectedTraceId.value)) {
      selectedTraceId.value = null
      spans.value = []
    }
  } finally {
    traceLoading.value = false
  }
}

function pickTrace(row: ObsTrace) {
  selectedTraceId.value = row.traceId
  loadSpans()
}

async function loadSpans() {
  if (!selectedTraceId.value || !user.hasPerm('obs:span:list')) {
    spans.value = []
    return
  }
  spanLoading.value = true
  try {
    const { data } = await listObsSpans(selectedTraceId.value)
    spans.value = data.data
  } finally {
    spanLoading.value = false
  }
}

function resetDetail() {
  selectedTraceId.value = null
  traces.value = []
  traceTotal.value = 0
  traceJump.value = '1'
  spans.value = []
  spanDrawerOpen.value = false
}

/**
 * 打开「加入评测集」：预填问题标题与末步输出摘要；缺字段由用户粘贴。
 */
async function openPromoteDialog() {
  if (!selectedTraceId.value) return
  const trace = traces.value.find((t) => t.traceId === selectedTraceId.value)
  promoteForm.suiteId = undefined
  promoteForm.message = (trace?.title || '').trim()
  const lastOut = [...spans.value].reverse().find((s) => (s.outputText || '').trim())
  promoteForm.finalAnswer = (lastOut?.outputText || '').trim().slice(0, 4000)
  promoteForm.note = ''
  try {
    const { data } = await pageEvalSuites({ current: 1, size: 100 })
    promoteSuites.value = data.data.records
  } catch {
    promoteSuites.value = []
  }
  promoteOpen.value = true
}

/** 调 from-trace 升版写入；不读 obs 表业务义，摘要由前端传入 */
async function submitPromote() {
  if (!promoteForm.suiteId) {
    ElMessage.warning('请选择目标套件')
    return
  }
  if (!promoteForm.message.trim()) {
    ElMessage.warning('请填写用户输入')
    return
  }
  promoteSaving.value = true
  try {
    const { data } = await promoteFromTrace(promoteForm.suiteId, {
      message: promoteForm.message.trim(),
      finalAnswer: promoteForm.finalAnswer.trim() || undefined,
      note: promoteForm.note.trim() || undefined,
      sessionId: selectedSessionId.value != null ? String(selectedSessionId.value) : undefined,
      traceId: selectedTraceId.value || undefined
    })
    ElMessage.success(`已写入评测集，新版本 v${data.data.currentVersion}`)
    promoteOpen.value = false
  } finally {
    promoteSaving.value = false
  }
}

/** Agent 字典只用于会话列名称兜底；失败不影响列表 */
async function loadAgents() {
  try {
    const { data } = await pageAgents({ current: 1, size: 100 })
    agents.value = data.data.records
  } catch {
    agents.value = []
  }
}

onMounted(async () => {
  await loadAgents()
  // 首次进入先读 query：带 workflowId 或缺省 type 的旧链接直接进工作流，否则维持三栏会话视角
  // onActivated 在 KeepAlive 首挂载时也会先跑一次；已按同一条件处理过就不重复拉列表
  if (!applyRouteQuery()) {
    return
  }
  if (workflowMode.value) {
    // 工作流入口默认查全部运行记录，不等用户先选定义
    void ensureWorkflowOptions()
    await loadTraces()
  } else {
    await loadSessions()
  }
})

// KeepAlive 下再次从工作流列表跳转可能复用本组件，条件变化才重拉
onActivated(() => {
  if (applyRouteQuery()) {
    reloadForRouteChange()
  }
})

watch(
  () => [route.query.type, route.query.workflowId, route.query.workflowName],
  () => {
    // 离开本页时 query 被清空也会触发，跳过避免后台空拉一次列表
    if (route.path !== '/ops/traces') {
      return
    }
    if (applyRouteQuery()) {
      reloadForRouteChange()
    }
  }
)
</script>

<style scoped>
/*
 * 主区已扣顶栏与内边距：本页用 height:100% 吃满剩余高度。
 * 列表溢出只在 .col-body 内滚。
 */
.page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

/* 页头与筛选条不参与弹性收缩：列表区独占剩余高度并在内部滚动 */
.page :deep(.ph-toolbar),
.page :deep(.filter-bar) {
  flex: none;
}

.board {
  display: grid;
  flex: 1;
  grid-template-columns: 25% 25% 50%;
  gap: 16px;
  align-items: stretch;
  min-height: 0;
}

/* 工作流入口没有会话列：两栏，给运行记录与链路更多宽度 */
.board--workflow {
  grid-template-columns: 35% 65%;
}

.wf-select {
  width: 220px;
}

.wf-hint {
  flex: none;
  margin-left: auto;
  color: var(--ah-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.type-switch {
  flex: none;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  padding: 16px;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: var(--ah-radius-lg);
  box-shadow: var(--ah-shadow);
}

.col-head {
  flex: none;
}

.col-head--row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  /* 给放大按钮留出内边，避免被 .col 的 overflow:hidden 裁半 */
  padding-right: 12px;
  box-sizing: border-box;
}

.col-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-id {
  margin-left: 6px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.col-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.promote-full {
  width: 100%;
}

.expand-btn {
  margin: 0 12px 0 0;
  padding: 4px 8px;
  flex: none;
  color: var(--ah-text-secondary);
}

.expand-btn:hover:not(:disabled) {
  color: var(--ah-primary);
}

.col-head--row :deep(.el-tooltip__trigger) {
  display: inline-flex;
  flex: none;
  margin-right: 8px;
}

.col-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.span-tree {
  padding-left: 4px;
}

.item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  margin: 0 0 8px;
  padding: 10px 12px;
  text-align: left;
  background: #fff;
  border: 1px solid var(--ah-border);
  border-radius: 10px;
  cursor: pointer;
}

.item.active {
  border-color: var(--ah-primary);
  background: var(--el-color-primary-light-9);
}

.item-title {
  width: 100%;
  color: var(--ah-text);
  font-size: 13px;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
}

.item-meta {
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 400;
}

.lat-ok {
  color: #039855;
  font-weight: 600;
}

.lat-warn {
  color: #dc6803;
  font-weight: 600;
}

.lat-danger {
  color: #d92d20;
  font-weight: 600;
}

.empty {
  margin: 24px 0;
  color: var(--ah-text-secondary);
  font-size: 13px;
  text-align: center;
}

.col-pager {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 4px;
  padding-top: 2px;
  border-top: 1px solid var(--ah-border);
  min-width: 0;
}

.col-pager-meta {
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-pager-nav {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 6px;
  min-width: 0;
}

.col-pager-nav :deep(.el-button) {
  margin: 0;
  padding: 0 6px;
  min-height: 24px;
  font-size: 16px;
  line-height: 1;
}

.col-pager-jump {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  color: var(--ah-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.col-pager-jump input {
  width: 40px;
  height: 24px;
  padding: 0 4px;
  border: 1px solid var(--ah-border);
  border-radius: 6px;
  background: #fff;
  color: var(--ah-text);
  font-size: 12px;
  text-align: center;
  outline: none;
}

.col-pager-jump input:focus {
  border-color: var(--ah-primary);
}

.col-pager-jump input:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.col-pager-jump input[type='number']::-webkit-outer-spin-button,
.col-pager-jump input[type='number']::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.col-pager-jump input[type='number'] {
  -moz-appearance: textfield;
  appearance: textfield;
}

@media (max-width: 1100px) {
  .board {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .col {
    height: auto;
    min-height: 280px;
  }
}
</style>

