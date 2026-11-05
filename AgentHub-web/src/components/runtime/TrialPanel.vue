<template>
  <div class="trial" :class="variant">
    <section v-if="variant === 'page'" class="picker-bar">
      <el-select v-model="pickedId" filterable placeholder="选择启用中的 Agent" class="agent-select">
        <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-tag
        v-if="shown.runMode"
        size="small"
        effect="plain"
        :type="shown.runMode === 'ROUTER' ? 'warning' : shown.runMode === 'PLAN_AND_EXECUTE' ? 'success' : 'primary'"
      >
        {{ runModeLabel(shown.runMode) }}
      </el-tag>
    </section>

    <el-empty v-if="variant === 'drawer' && !ownerId" description="请先选择一个 Agent" />

    <div v-else class="split">
      <aside class="sessions">
        <div class="sessions-title">历史会话</div>
        <div class="session-ops">
          <el-button
            v-if="canRun"
            size="small"
            type="primary"
            :disabled="!ownerId || sending || resuming"
            @click="createSession"
          >
            + 新会话
          </el-button>
          <el-button
            size="small"
            :disabled="!session || isTempSession(session) || sending || resuming"
            @click="clearCurrent"
          >
            清空当前
          </el-button>
        </div>
        <p v-if="!historyGroups.length" class="session-hint">提问后才会保存到历史会话</p>
        <div v-loading="historyLoading" class="session-list">
          <section v-for="group in historyGroups" :key="group.label" class="session-group">
            <div class="group-label">{{ group.label }}</div>
            <div
              v-for="item in group.items"
              :key="item.id"
              class="session-item"
              :class="{ 'is-active': item.id === session?.id, 'is-running': isRowRunning(item) }"
              :title="sessionTooltip(item)"
              @click="openHistory(item.id)"
            >
              <div class="session-main">
                <span class="session-name">{{ sessionTitle(item) }}</span>
                <span class="session-time">{{ sessionSubtitle(item) }}</span>
              </div>
              <el-dropdown
                v-if="canRun && !isTempSession(item)"
                trigger="click"
                @command="(cmd: string) => onSessionCommand(cmd, item)"
              >
                <button type="button" class="session-more" aria-label="会话操作" @click.stop>
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="remove" divided class="session-remove-item">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <!-- 本轮还在执行：贴在行最右侧，结束后自动消失 -->
              <span v-if="isRowRunning(item)" class="session-running" title="本轮执行中">
                <el-icon class="is-loading"><Loading /></el-icon>
              </span>
            </div>
          </section>
        </div>
      </aside>

      <section class="chat-col">
        <el-alert v-if="pageError" :title="pageError" type="error" show-icon :closable="false" class="block-alert" />
        <!--
          子专家计划待批准：根试跑暂停时由 SSE ma_child_plan_pending / 刷新恢复接口驱动。
          同时仅一个待批（后端单批准槽），因此这里只渲染一条，不排队多张卡片。
        -->
        <section v-if="childPending && canRun" class="child-approval">
          <div class="child-approval-head">
            <div class="child-approval-main">
              <div class="section-label">子专家计划待批准</div>
              <p class="child-approval-title">
                子专家「{{ childPending.childAgentName || '子 Agent' }}」计划待批准
              </p>
              <p class="child-approval-sub">
                根运行已暂停（等待子计划批准）；同时仅允许一个待批子。批准后该子继续执行，拒绝则该子记为失败、其它子继续。
              </p>
            </div>
            <div class="child-approval-actions">
              <el-button type="primary" size="small" :loading="childDeciding" @click="decideChildPlan(true)">
                批准
              </el-button>
              <el-button size="small" :disabled="childDeciding" @click="decideChildPlan(false)">拒绝</el-button>
            </div>
          </div>
          <ol v-if="childPending.steps?.length" class="child-plan-steps">
            <li v-for="step in childPending.steps" :key="step.seq">
              <span class="child-plan-seq">{{ step.seq }}</span>
              <span class="child-plan-text">
                <strong>{{ step.title }}</strong>
                <span v-if="step.detail" class="child-plan-detail">{{ step.detail }}</span>
              </span>
            </li>
          </ol>
        </section>
        <ChatPanel
          :turns="turns"
          :sending="sending || resuming"
          :detached="detached"
          :can-send="canRun"
          :session-ready="sessionReady"
          :blocked-hint="blockedHint"
          :now="now"
          :can-approve="canRun"
          :approving="approving"
          :vision-enabled="visionEnabled"
          :router-trace="routerTrace"
          @send="send"
          @approve="approvePlan"
          @reject="rejectPlan"
          @refresh-router="reloadRouterTrace"
          @open-gantt="ganttVisible = true"
        />
      </section>
    </div>

    <!--
      详细甘特图抽屉：由对话流内紧凑时间线底部的「查看详细甘特图」触发。
      复用既有泳道正文（RouterExecutionCard），数据仍是同一份 obs span 树；
      未来要接「深色日志」时，在这里加第二个 Tab 即可，不必再改时间线。
    -->
    <el-drawer v-model="ganttVisible" title="编排详细 · 甘特图" size="680px" append-to-body>
      <RouterExecutionCard
        v-if="routerTrace?.model?.hasRouterTrace"
        :trace-data="routerTrace.model"
        :events="routerTrace.events"
        :spans="routerTrace.spans"
        :status="routerTraceActive ? 'running' : 'done'"
        :error="routerTrace?.error ?? ''"
        :loading="routerTrace?.loading === true"
        :trace-id="routerTrace?.traceId ?? ''"
        :session-id="routerTrace?.sessionId != null ? String(routerTrace.sessionId) : null"
        :now="now"
        :default-expanded="true"
        :auto-collapse="false"
        @refresh="reloadRouterTrace"
      />
      <el-empty v-else description="本轮还没有可展示的编排追踪数据" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 试跑工作台。所属域：runtime。
 * 抽屉里左栏是当前用户、当前 Agent 的历史会话（按今日/昨日/本周/更早分组），右栏是对话。
 * 点「新会话」= 仅前端顶插「新对话」临时条并清空右栏；不调创建接口、不落库。
 * 首条用户消息发送时再 create + 流式；临时条用返回的 session 转正。切历史/关页丢掉临时。
 * 「清空当前」只对已落库会话：删事件并重置轮次，会话和标题保留。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Loading } from '@element-plus/icons-vue'
import {
  approveTrialPlan,
  clearTrialSession,
  createMaTrialSession,
  createTrialSession,
  deleteTrialSession,
  getPendingChildPlan,
  getPendingTrialPlan,
  getTrialCapability,
  getTrialSession,
  listTrialAgents,
  listTrialEvents,
  listTrialLiveEvents,
  pageTrialSessions,
  rejectTrialPlan,
  renameTrialSession,
  streamTrialMessage,
  type TrialChildPlanPendingChunk
} from '@/api/runtime'
import ChatPanel from '@/components/runtime/ChatPanel.vue'
import RouterExecutionCard from '@/components/runtime/RouterExecutionCard.vue'
import {
  advancePlanState,
  appendFile,
  appendRoute,
  appendText,
  appendTool,
  appendVision,
  assistantTurn,
  formatRelativeTime,
  hasOpenTurn,
  markLiveTail,
  markRouterTrace,
  openTurnStale,
  parseFile,
  parsePlan,
  parseStep,
  turnsFromEvents,
  upsertPlan,
  upsertStep
} from '@/components/runtime/trialMessages'
import { forgetTrialSession, recallTrialSession, rememberTrialSession } from '@/components/runtime/trialResume'
import {
  beginLive,
  bindLiveTurns,
  clearSnapshot,
  dropLive,
  finishLive,
  hasLiveStream,
  isTurnRunning,
  liveOf,
  loadSnapshot,
  markTurnRunning,
  saveSnapshot
} from '@/components/runtime/trialStream'
import { runModeLabel } from '@/composables/agentViewFlip'
import { useRouterLane } from '@/composables/useRouterLane'
import { useUserStore } from '@/stores/user'
import type { ChatTurn, TrialAgent, TrialEvent, TrialPlanView, TrialSession } from '@/types/runtime'

/** 左栏分组标题与会话列表。 */
interface SessionGroup {
  label: string
  items: TrialSession[]
}

const props = withDefaults(
  defineProps<{
    /** page：整页试跑；drawer：管理页右侧抽屉 */
    variant?: 'page' | 'drawer'
    /** 抽屉由外部指定当前 Agent；整页只当作初始选中 */
    agentId?: number
    runMode?: string
    /**
     * 虚拟根试跑归属的方案（MA-P2 刀2）。
     * 有它时不再按 agentId 建会话/拉列表，改走方案入口；与 agentId 互斥，优先 maId。
     */
    maId?: number
  }>(),
  { variant: 'drawer' }
)

const user = useUserStore()
const canRun = computed(() => user.hasPerm('runtime:trial:run'))
const canList = computed(() => user.hasPerm('runtime:trial:list'))

const agents = ref<TrialAgent[]>([])
const pickedId = ref<number>()
const session = ref<TrialSession>()
const turns = ref<ChatTurn[]>([])
const history = ref<TrialSession[]>([])
/**
 * 本地已知会话（id → 会话），用于兜住「服务端列表暂时看不到」的行。
 * 主要是本轮进行中的会话：后端整轮结束才回写轮次与预览，列表接口会把它当空壳过滤掉。
 * 切走再切回、关面板再开都要靠它保住列表项，不能只依赖当前 session 一个变量。
 */
const knownSessions = ref<Map<number, TrialSession>>(new Map())
const pageError = ref('')
const sending = ref(false)
const historyLoading = ref(false)
/** 前端临时「新对话」：未发首问前可输入；不占库。 */
const draftReady = ref(false)
const now = ref(Date.now())
let restoring = false
let clock: number | undefined
/** 重进续订时的轮询定时器；关窗、切会话、发送前都要停。 */
let pollTimer: number | undefined
/** 会话标题补拉的定时器（后端异步生成，落库晚于 SSE 结束）。 */
let titleTimer: number | undefined
/** 续订轮次令牌：每次开/停都自增，迟到的异步结果直接丢弃，避免串会话。 */
let pollToken = 0
/** 当前正在续订的会话 id。用于「续订只服务当前会话」的判断，切换会话即失效。 */
let resumingId: number | undefined
/** 上一拍已渲染的事件签名（条数 + 末尾 seq）。签名不变就不重放，避免每 2 秒整体重渲并强制滚到底。 */
let resumeSignature = ''
/** 当前会话是否正在续订。只绑当前会话，供按钮禁用与粉条状态。 */
const resuming = computed(() => {
  const id = session.value && !isTempSession(session.value) ? session.value.id : undefined
  return id != null && resumingId === id && isTurnRunning(id)
})

/** 计划批准/取消请求进行中。避免连点导致同一份计划被消费两次 */
const approving = ref(false)

/**
 * 编排根下当前待批的子专家计划。同一根同时只允许一个，因此只存一条；
 * 来源有两条：SSE {@code ma_child_plan_pending} 与刷新后的 child-pending 接口。
 */
const childPending = ref<TrialChildPlanPendingChunk | null>(null)
/** 子计划批准/拒绝请求进行中；只影响这两个按钮，不复用单 Agent 的 approving */
const childDeciding = ref(false)

/**
 * 当前会话是否处于「连接已断、只能等落库」的状态。
 * 整页刷新后本页已没有任何 SSE，后端又不落中间增量，
 * 此时轮询只能拿到旧事件；必须如实告诉用户，不能一直假装「思考中」。
 */
const detached = ref(false)

/** 仅前端临时会话 id，禁止拿去调删除/清空/事件接口。 */
const TEMP_SESSION_ID = -1

/** 重进续订的轮询间隔。后端思考/工具要等整轮结束才入库，故用短轮询而非 SSE。 */
const RESUME_POLL_MS = 2000
/**
 * 批准执行期间的轮询间隔。批准接口要跑完整轮才返回，
 * 期间只能靠「内存快照」把「第 N 步进行中」一点点取回来，所以比常规续订更勤。
 */
const APPROVE_POLL_MS = 1200
/** 续订上限。对齐后端 limits.timeout-ms（默认 300s）再留缓冲，防止库里僵尸 RUNNING 让前端一直转。 */
const RESUME_MAX_MS = 330_000
/**
 * 自动续订的静默上限。末尾事件超过这个时间还没更新，就认为不是本轮（历史僵尸 RUNNING），
 * 只渲染已落库内容、不自动轮询；用户重新发问才接管。
 */
const RESUME_FRESH_MS = 10 * 60_000
/**
 * 「刚建会话、USER 事件还没落库」这个极短窗口的长度。
 * 在这段时间内即使 events 为空也按进行中处理，避免关窗重进正好卡在中间时右栏全空。
 */
const PENDING_FIRST_EVENT_MS = 2 * 60_000
/** 续订期间事件一直为空时的等待上限。超过就静默停止，避免这一轮其实没起来还空转满整轮上限。 */
const RESUME_EMPTY_MS = 45_000
/**
 * 首轮标题补拉的延迟点（毫秒）。
 * 标题在本轮请求内同步写库，正常情况 done 之后立刻拉一次就能拿到；
 * 这里按两次延迟重拉兜底（服务端列表缓存 / 写读时序异常），命中即停，避免一直轮询。
 */
const TITLE_REFRESH_DELAYS = [3000, 8000] as const
/** 打开会话的请求令牌：连点两个会话时丢弃迟到的响应，避免串台。 */
let openToken = 0

const activeId = computed(() => (props.variant === 'drawer' ? props.agentId : pickedId.value))
/** 虚拟根模式：有 maId 就按方案归属会话，不再依赖 Agent */
const maMode = computed(() => (props.maId ?? 0) > 0)
/**
 * 会话归属键：虚拟根＝方案 id，否则＝Agent id。
 * 左栏记忆、草稿壳、创建会话都只认这一个键，避免两条入口各写一套分支。
 */
const ownerId = computed(() => (maMode.value ? props.maId : activeId.value))

const shown = computed(() => {
  // 虚拟根恒为 ROUTER：方案只承载一层编排，没有其它运行模式
  if (maMode.value) {
    return { runMode: 'ROUTER' }
  }
  if (props.variant === 'drawer') {
    return { runMode: props.runMode || '' }
  }
  const found = agents.value.find((item) => item.id === pickedId.value)
  return { runMode: found?.runMode || '' }
})

/**
 * 对话流内编排卡的数据源。
 * 只有 Router 且已落库的会话才取 trace：临时草稿没会话、P&E/ReAct 没有编排泳道。
 */
const routerSessionId = computed(() => {
  const current = session.value
  if (!current || current.id <= 0 || shown.value.runMode !== 'ROUTER') {
    return null
  }
  return current.id
})
/** 本轮是否还在跑：决定编排卡是否轮询 obs trace */
const routerTraceActive = computed(() => sending.value || resuming.value)
const { view: routerTrace, reload: reloadRouterTrace } = useRouterLane(routerSessionId, routerTraceActive)

/**
 * 右侧「详细甘特图」抽屉。
 * 时间线只 emit 一个打开意图，抽屉归属页面层，后续接深色日志或拖拽联动都不用改卡片。
 */
const ganttVisible = ref(false)

// 切会话即关抽屉：旧会话的甘特图不能挂在新会话上，避免用户以为在看当前轮
watch(routerSessionId, () => {
  ganttVisible.value = false
})

/** 本地草稿，或未取消的落库会话，均可续聊。仅 CANCELLED 禁发。 */
const sessionReady = computed(
  () => draftReady.value || (!!session.value && session.value.status !== 'CANCELLED')
)

/**
 * 当前 Agent 是否已绑定可用的 VISION 模型。
 * 决定上传图片按钮是否可用：未绑定时置灰并提示，而不是让用户传完图再被后端拒。
 * 未知（还没取到）时按 false 处理——宁可不给上传，也不要让用户白传一次。
 */
const visionEnabled = ref(false)

/** 已取消时说明原因；历史 SUCCESS 不挡发送。 */
const blockedHint = computed(() =>
  session.value && session.value.status === 'CANCELLED' ? '会话已取消' : '请先新建会话或从左侧选择'
)

/**
 * 左栏展示：当前若是临时「新对话」，钉在最前（今日组）；库内历史不改。
 *
 * 还需兜住两类「服务端列表里看不到、但必须有行」的会话：
 * - 当前会话：列表接口会因「未提问空壳」判据过滤掉它（后端整轮结束才回写轮次/预览）；
 * - 本地仍在执行的会话：切走后不能凭空消失，否则用户看不到转圈、也点不回去。
 * 两者都从 {@link knownSessions} 补行，保证「切走再切回」列表项始终在。
 */
const historyGroups = computed(() => {
  const current = session.value
  const rows = history.value.filter((row) => !isTempSession(row))
  const have = new Set(rows.map((row) => row.id))
  const extra: TrialSession[] = []
  if (current) {
    // 临时草稿用负 id，永远钉最前
    if (isTempSession(current) || !have.has(current.id)) {
      extra.push(current)
      have.add(current.id)
    }
  }
  // 本地登记过、仍在执行的会话：不在服务端结果里也要显示，否则切走就丢
  for (const [id, row] of knownSessions.value) {
    if (!have.has(id) && isTurnRunning(id)) {
      extra.push(row)
      have.add(id)
    }
  }
  if (!extra.length) {
    return groupSessionsByDay(rows)
  }
  return groupSessionsByDay([...extra, ...rows])
})

/**
 * 该行是否显示「执行中」转圈。
 * 依据模块级 running 集合（关面板、切会话都不丢），不看服务端 status：
 * 试跑会话多轮期间 status 一直是 RUNNING，拿它判断会让所有历史行都在转。
 * @param item 左栏会话
 */
function isRowRunning(item: TrialSession) {
  return !isTempSession(item) && isTurnRunning(item.id)
}

/**
 * 记下本地会话，供「服务端列表看不到」时补行。
 * @param item 会话；临时草稿不登记
 */
function rememberKnown(item?: TrialSession | null) {
  if (!item || isTempSession(item) || item.id < 0) {
    return
  }
  const next = new Map(knownSessions.value)
  next.set(item.id, item)
  knownSessions.value = next
}

/**
 * 更新本地会话的展示元数据（标题 / 轮次）。
 * 与服务端合并同一套规则：新值优先，但空标题不覆盖已有标题。
 * @param id     会话主键
 * @param patch  要覆盖的字段
 */
function patchKnown(id: number, patch: Partial<TrialSession>) {
  const existing = knownSessions.value.get(id)
  if (!existing) {
    return
  }
  const next = new Map(knownSessions.value)
  next.set(id, {
    ...existing,
    ...patch,
    lastMsgPreview: (patch.lastMsgPreview || '').trim() || existing.lastMsgPreview
  })
  knownSessions.value = next
}

watch(
  () => props.agentId,
  (id) => {
    if (props.variant !== 'page' || !id || pickedId.value === id) {
      return
    }
    pickedId.value = id
  },
  { immediate: true }
)

// 切 Agent / 切方案都要重来一轮：两条入口的会话空间互不相通
watch(ownerId, (id, prev) => {
  if (restoring) {
    restoring = false
    return
  }
  if (id === prev) {
    return
  }
  if (prev !== undefined) {
    resetConversation()
  }
  // 切 Agent 必须重新判定视觉能力：上一个 Agent 绑了 VISION，不代表这个也绑了
  void loadVisionCapability(id)
  loadHistory(true)
})

onMounted(async () => {
  await loadAgents()
  await loadVisionCapability(ownerId.value)
  await loadHistory(true)
})

onBeforeUnmount(() => {
  // 关面板不再 abort SSE：本轮的响应式时间线登记在模块级 trialStream，
  // 读流与增量累积继续，重开直接接上同一份数据 —— 这是「渐进显示」的前提。
  // 中间过程只走 SSE、不落库，断了就只能等整轮跑完才看到内容。
  // 组件内只收拾自己的定时器与轮询；SSE 由 trialStream 的登记项持有，直到收尾并落库接管。
  stopClock()
  stopResume()
  stopTitleRefresh()
})

/** 下拉只含启用 Agent。抽屉不请求这份列表。 */
async function loadAgents() {
  if (props.variant !== 'page') {
    return
  }
  try {
    const { data } = await listTrialAgents()
    agents.value = data.data || []
  } catch {
    pageError.value = 'Agent 列表加载失败'
  }
}

/**
 * 判定当前 Agent 是否配了 VISION 模型，用于置灰上传按钮。
 *
 * 整页模式优先复用下拉里已带回来的标记（列表接口顺带算好了）；
 * 抽屉模式没有下拉，单独请求一次能力接口。
 * 取不到时按「不可用」处理：宁可不给传，也不要让用户白传一次再被后端拒。
 *
 * @param agentId 当前 Agent；为空表示还没选
 */
async function loadVisionCapability(agentId?: number) {
  // 虚拟根没有 Agent，也就没有视觉槽：直接关掉上传，避免用户白传一次再被后端拒
  if (maMode.value || !agentId) {
    visionEnabled.value = false
    return
  }
  if (props.variant === 'page') {
    const found = agents.value.find((item) => item.id === agentId)
    if (found) {
      visionEnabled.value = found.visionEnabled === true
      return
    }
  }
  try {
    const { data } = await getTrialCapability(agentId)
    // 请求期间可能已切到别的 Agent，迟到的响应直接丢弃
    if (activeId.value !== agentId) {
      return
    }
    visionEnabled.value = data.data?.visionEnabled === true
  } catch {
    visionEnabled.value = false
  }
}

/**
 * 只拉当前 Agent、当前用户的近期会话；未提问的空壳不展示并后台清掉。
 * @param selectLatest 首次挂载时选中上次会话或最近一条，方便续聊
 */
async function loadHistory(selectLatest = false) {
  const owner = ownerId.value
  if (!canList.value || !owner) {
    history.value = []
    return
  }
  // 上次看的会话（重进 / 刷新后用来回到本轮，而不是落到空白草稿）
  const resumedId = recallTrialSession(owner, maMode.value ? 'ma' : 'agent')
  // 首次挂载且已有记忆：立刻并行拉 events、不等列表接口，右栏不留空屏。
  // 列表接口稍后只用来补左栏标题与分组，不参与「先渲染谁」。
  const eager = selectLatest && !!resumedId && !session.value && !draftReady.value
  const eagerTask = eager ? openHistory(resumedId as number) : undefined
  historyLoading.value = true
  try {
    // 两条入口参数互斥：虚拟根按 maId，绑根按 agentId；后端也拒绝同时携带
    const pageParams = maMode.value
      ? { current: 1, size: 100, maId: owner }
      : { current: 1, size: 100, agentId: owner }
    const { data } = await pageTrialSessions(pageParams)
    const records = data.data?.records || []
    const kept: TrialSession[] = []
    const empties: TrialSession[] = []
    const currentId = session.value?.id
    for (const item of records) {
      if (isUnaskedSession(item)) {
        // 正在执行的会话在列表里也长得像空壳（后端整轮结束才回写轮次/预览），绝不能当空壳清掉：
        // 它可能正由本页 SSE 消费、正被续订，或只是本页刚发出去还没回写
        if (item.id !== currentId && item.id !== resumedId && !isTurnRunning(item.id) && !isPossiblyInFlight(item)) {
          empties.push(item)
        }
      } else {
        kept.push(item)
      }
    }
    history.value = kept
    // 服务端结果同步进本地登记表，两边保持一致
    for (const item of kept) {
      rememberKnown(item)
    }
    // 列表是服务端结果。把当前条换成这份，避免创建接口留下的 0 轮次把已聊会话再判成空壳。
    // 但本轮还在跑时服务端仍是 0 轮次/空标题：已按事件补齐的本地值不能被它冲掉
    if (currentId != null) {
      const fresh = records.find((item) => item.id === currentId)
      if (fresh) {
        session.value = mergeSessionMeta(fresh, session.value)
      }
    }
    // 其它历史空壳静默删掉，避免反复点「新会话」堆未提问记录。已有预览或轮次的不进 empties
    void purgeUnaskedSessions(empties)
    if (eagerTask) {
      // 等即时打开完成（失败也不挡后续兜底）
      await eagerTask
    }
    if (selectLatest && !session.value && !draftReady.value) {
      // 上次看的会话仍在列表里就回它；本轮刚发出时它还是空壳，也在 records 中，照样能重进
      const remembered = resumedId != null ? records.find((item) => item.id === resumedId) : undefined
      if (resumedId != null && !remembered) {
        // 记忆里的会话已删除或不属于当前 Agent，清掉记忆再退回最近一条
        forgetTrialSession(resumedId)
      }
      if (remembered) {
        await openHistory(remembered.id)
      } else if (kept.length) {
        await openHistory(kept[0].id)
      } else if (canRun.value) {
        // 无历史：左侧顶插临时「新对话」，首问才落库
        beginLocalDraft()
      }
    }
  } catch {
    history.value = []
  } finally {
    historyLoading.value = false
  }
}

/**
 * 首轮结束后把服务端刚写入的标题补进左栏（只改这一行的标题列）。
 *
 * <p>为什么不用 loadHistory：那会整表替换左栏，接口抖一下就被清空；
 * 标题只涉及某一行的两列，按会话单查更稳，也不触发空壳清理之类的副作用。
 * 失败静默：标题是锦上添花，不能因为补标题把本轮成功的结果弄成报错。
 *
 * @param id 会话主键
 * @returns true 表示确实拿到了标题并已写回左栏
 */
async function refreshSessionTitle(id: number) {
  try {
    const { data } = await getTrialSession(id)
    const fresh = data.data
    const title = (fresh?.sessionTitle || '').trim()
    if (!title) {
      return false
    }
    const row = history.value.find((item) => item.id === id)
    if (row) {
      row.sessionTitle = title
      row.titleSource = fresh.titleSource
    }
    if (session.value?.id === id) {
      session.value = { ...session.value, sessionTitle: title, titleSource: fresh.titleSource }
    }
    return true
  } catch {
    return false
  }
}

/**
 * 首轮结束后补拉左栏标题。
 *
 * <p>为什么还需要：标题在本轮请求内同步写库，done 之后已按会话单查补过一次左栏；
 * 这里保留两次列表补拉兜底，防止单查与列表接口的可见性时序差让「新对话」一直挂着。
 * 命中即停，不做常驻轮询。
 *
 * <p>为什么只补首轮：标题只由首轮生成，后续轮次不会再变，补拉纯属浪费一次请求。
 *
 * @param id 会话主键
 */
function scheduleTitleRefresh(id: number) {
  stopTitleRefresh()
  const row = history.value.find((item) => item.id === id)
  // 已有标题（或不是首轮）就不必等：后端不会再改它
  if (row && (row.sessionTitle || '').trim()) {
    return
  }
  const delays = [...TITLE_REFRESH_DELAYS]
  const tick = () => {
    titleTimer = window.setTimeout(async () => {
      await loadHistory(false)
      const fresh = history.value.find((item) => item.id === id)
      // 标题已有值、或会话已不在列表（被删/切 Agent）时收手
      if (!fresh || (fresh.sessionTitle || '').trim()) {
        return
      }
      if (delays.length) {
        tick()
      }
    }, delays.shift() ?? 0)
  }
  tick()
}

/** 停掉标题补拉。切会话、卸载、重新发送时都要调，避免旧会话的补拉盖掉新状态。 */
function stopTitleRefresh() {
  if (titleTimer) {
    window.clearTimeout(titleTimer)
    titleTimer = undefined
  }
}

/**
 * 给最后一轮助手卡挂上「编排调度卡」前端标记（ROUTER_TRACE）。
 *
 * 只在 ROUTER 试跑里挂：ReAct / 计划执行没有编排泳道，多出一张卡只会误导。
 * 标记仅存在于本地时间线与刷新快照，不写库、不参与事件回放。
 *
 * @param list 时间线（就地修改）
 * @return 同一个数组，便于链式赋值
 */
function attachRouterTrace(list: ChatTurn[]) {
  if (shown.value.runMode !== 'ROUTER') {
    return list
  }
  for (let i = list.length - 1; i >= 0; i -= 1) {
    if (list[i].role === 'assistant') {
      markRouterTrace(list[i])
      break
    }
  }
  return list
}

function resetConversation() {
  // 切 Agent / 删当前条：停掉续订并清掉右栏，避免把上一会话的进行态带过去
  stopResume()
  stopTitleRefresh()
  session.value = undefined
  turns.value = []
  pageError.value = ''
  draftReady.value = false
  approveWatermark.clear()
}

/**
 * 新会话：只清右栏 + 左栏顶插一条前端临时「新对话」（选中）。
 * 不调创建接口、不落库；旧历史一律保留。再点新建则替换当前临时条。
 */
/**
 * 新会话：只清右栏 + 左栏顶插一条前端临时「新对话」（选中）。
 * 不调创建接口、不落库；旧历史一律保留。再点新建则替换当前临时条。
 * 「新建后不提问就刷新」不会留下任何记录：临时条只有负 id，从不写记忆、从不入库。
 */
async function createSession() {
  if (!ownerId.value || !canRun.value || sending.value || resuming.value) {
    return
  }
  pageError.value = ''
  // 若当前是历史误点留下的落库空壳才清；临时条无库记录
  await discardUnaskedCurrent()
  beginLocalDraft()
}

/**
 * 造一个只用于左栏展示的会话壳。
 * 两处在用：前端临时「新对话」（负 id），以及关面板重开后「服务端列表当空壳过滤、
 * 但本轮仍在跑」的会话——后者没有它就没有 session 值，左栏不出行、也不会被选中。
 * @param id          会话主键；临时草稿传 {@link TEMP_SESSION_ID}
 * @param startTimeMs 开始时间毫秒，决定它落在「今日」组
 * @return 左栏可直接渲染的会话
 */
function sessionStub(id: number, startTimeMs: number): TrialSession {
  const stamp = formatLocalStamp(new Date(startTimeMs || Date.now()))
  return {
    id,
    agentId: activeId.value ?? null,
    maId: maMode.value ? props.maId : undefined,
    runMode: shown.value.runMode || '',
    userId: 0,
    status: 'RUNNING',
    messageCount: 0,
    // 草稿还没有标题：左栏先显示「新对话」，首轮结束后由后端异步补上
    sessionTitle: '',
    lastMsgPreview: '',
    startTime: stamp,
    updateTime: stamp
  }
}

/** 挂上仅前端的临时会话；右栏可输入，左栏由 historyGroups 钉在最前。 */
function beginLocalDraft() {
  if (!ownerId.value) {
    return
  }
  // 新草稿不再代表上次那个落库会话，停掉续订免得把旧会话的进行态写进来
  stopResume()
  session.value = sessionStub(TEMP_SESSION_ID, Date.now())
  turns.value = []
  // 草稿是全新一轮：上一个会话遗留的子计划待批卡片不能带过来
  childPending.value = null
  draftReady.value = true
}

/** 前端临时条（负 id），未发首问、无库行。 */
function isTempSession(item?: TrialSession | null) {
  return !!item && item.id === TEMP_SESSION_ID
}

/** 本地展示用时间戳，与列表相对时间解析兼容。 */
function formatLocalStamp(date: Date) {
  const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 从未发过消息、也没有标题/预览的空壳（历史误点新建留下的落库行）。临时条不算。 */
function isUnaskedSession(item: TrialSession) {
  if (isTempSession(item)) {
    return false
  }
  // 标题也算「有内容」：用户可能先改名再提问，这种行不能被当成空壳清掉
  return (item.messageCount ?? 0) <= 0 && !(item.lastMsgPreview || '').trim() && !(item.sessionTitle || '').trim()
}

/**
 * 可能是「本轮正在执行」的会话。后端等整轮结束才回写 message_count 和预览，
 * 所以进行中的会话在列表里长得和空壳完全一样：不能删、不能隐藏，也不能判定为已结束。
 * @param item 会话
 * @return true 表示创建/更新得足够新，按进行中处理
 */
function isPossiblyInFlight(item: TrialSession) {
  const stamp = parseSessionTime(item.updateTime || item.startTime)
  return stamp > 0 && Date.now() - stamp <= RESUME_FRESH_MS
}

/**
 * 丢掉当前未提问的落库空壳，避免空壳进历史。
 * 临时条只清本地引用（由调用方 beginLocalDraft / openHistory 覆盖）。
 * 本地已有用户气泡、左栏已有该条、服务端已有轮次/预览、或本轮仍在跑 → 一律不删。
 */
async function discardUnaskedCurrent() {
  const current = session.value
  if (!current || isTempSession(current)) {
    return
  }
  // 仍在执行（本页 SSE 在跑，或重进后正在续订）：服务端会拒删，也绝不能删
  if (isTurnRunning(current.id) || resuming.value) {
    return
  }
  // 刚聊过但本地对象仍可能是创建时的 0 轮次；有用户气泡就证明已提问
  if (turns.value.some((turn) => turn.role === 'user')) {
    return
  }
  if (!isUnaskedSession(current)) {
    return
  }
  if (history.value.some((row) => row.id === current.id)) {
    return
  }
  try {
    await deleteTrialSession(current.id)
  } catch {
    // 删失败也不挡开新会话
  }
  forgetTrialSession(current.id)
  clearSnapshot(current.id)
  dropLive(current.id)
  history.value = history.value.filter((row) => row.id !== current.id)
}

/** 批量清历史空壳；失败忽略。 */
async function purgeUnaskedSessions(items: TrialSession[]) {
  for (const item of items) {
    try {
      await deleteTrialSession(item.id)
    } catch {
      // 忽略单条失败
    }
  }
}

/**
 * 主标题：只认 session_title（首轮后自动写入的首句截断，或用户手改）。
 *
 * <p>为什么不用 lastMsgPreview 兜底：那是用户第一句话的截断，直接当标题会让左栏变成
 * 一长串问句，和真标题混在一起分不清。没有标题就固定显示「新对话」，
 * 等后端写入后自然替换；预览一律交给副标题展示。
 *
 * @param item 会话
 */
function sessionTitle(item: TrialSession) {
  const title = (item.sessionTitle || '').trim()
  return title || '新对话'
}

/**
 * 副标题：预览与相对时间，用「 · 」连起来；预览为空（尚未提问）时只显示时间。
 * @param item 会话
 */
function sessionSubtitle(item: TrialSession) {
  const preview = (item.lastMsgPreview || '').trim()
  const time = formatRelativeTime(item.updateTime || item.startTime)
  if (!preview) {
    return time
  }
  return `${preview} · ${time}`
}

/**
 * 行悬停提示：标题 + 完整预览。
 * 主标题已经不再兜底预览，行内两行又被省略号截断，悬停时才有地方看全。
 * @param item 会话
 */
function sessionTooltip(item: TrialSession) {
  const preview = (item.lastMsgPreview || '').trim()
  const title = sessionTitle(item)
  return preview ? `${title}\n${preview}` : title
}

/**
 * 按今日 / 昨日 / 本周 / 更早拆分左栏。
 * 「本周」= 昨天之前、距今不足 7 个自然日；更早单独成组。
 */
function groupSessionsByDay(items: TrialSession[]): SessionGroup[] {
  const buckets: Record<string, TrialSession[]> = {
    今日: [],
    昨日: [],
    本周: [],
    更早: []
  }
  const now = new Date()
  const todayStart = startOfLocalDay(now)
  const yesterdayStart = new Date(todayStart)
  yesterdayStart.setDate(yesterdayStart.getDate() - 1)
  const weekStart = new Date(todayStart)
  weekStart.setDate(weekStart.getDate() - 6)

  for (const item of items) {
    const stamp = parseSessionTime(item.updateTime || item.startTime)
    if (stamp >= todayStart.getTime()) {
      buckets['今日'].push(item)
    } else if (stamp >= yesterdayStart.getTime()) {
      buckets['昨日'].push(item)
    } else if (stamp >= weekStart.getTime()) {
      buckets['本周'].push(item)
    } else {
      buckets['更早'].push(item)
    }
  }
  return (['今日', '昨日', '本周', '更早'] as const)
    .filter((label) => buckets[label].length > 0)
    .map((label) => ({ label, items: buckets[label] }))
}

/** 本地日零点，避免时区偏移把「今日」判错。 */
function startOfLocalDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

/** 服务端时间可能是「yyyy-MM-dd HH:mm:ss」，统一成毫秒。 */
function parseSessionTime(value?: string) {
  if (!value) {
    return 0
  }
  const time = new Date(value.replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? 0 : time
}

/**
 * 三点菜单：重命名 / 删除。
 *
 * @param command 菜单项
 * @param item 目标会话
 */
function onSessionCommand(command: string, item: TrialSession) {
  if (command === 'rename') {
    rename(item)
    return
  }
  if (command === 'remove') {
    remove(item)
  }
}

/** 清空事件和进程内记忆，不把会话标成已取消。 */
async function clearCurrent() {
  if (!session.value || sending.value) {
    return
  }
  try {
    await ElMessageBox.confirm('清空当前会话的对话记录？会话会保留，可以继续发送。', '清空当前', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await clearTrialSession(session.value.id)
    session.value = data.data
    turns.value = []
    pageError.value = ''
    // 清空后端同时拒绝了待批子计划，本地卡片一并收起
    childPending.value = null
    await loadHistory(false)
    ElMessage.success('已清空当前会话')
  } catch {
    pageError.value = '清空失败'
  }
}

/** 悬停后的改名。取消输入不提示；接口失败才写错误。 */
async function rename(item: TrialSession) {
  if (sending.value) {
    return
  }
  let title = ''
  try {
    const answered = await ElMessageBox.prompt('请输入会话名称', '重命名', {
      // 用库里真实的标题预填，而不是渲染兜底的「新对话」——否则用户得一键清空才能改名
      inputValue: (item.sessionTitle || '').trim(),
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    title = answered.value.trim()
  } catch {
    return
  }
  try {
    const { data } = await renameTrialSession(item.id, title)
    if (data.data) {
      const index = history.value.findIndex((row) => row.id === item.id)
      if (index >= 0) {
        history.value[index] = data.data
      }
      if (session.value?.id === item.id) {
        session.value = data.data
      }
    }
  } catch {
    pageError.value = '重命名失败'
  }
}

/** 逻辑删除。删的是当前条时清空右侧。 */
async function remove(item: TrialSession) {
  if (sending.value) {
    return
  }
  try {
    await ElMessageBox.confirm('删除后无法恢复，确定删除该会话？', '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await deleteTrialSession(item.id)
    forgetTrialSession(item.id)
    if (session.value?.id === item.id) {
      resetConversation()
    }
    await loadHistory(false)
  } catch {
    pageError.value = '删除失败'
  }
}

/**
 * 点左栏载入事件时间线，之后发送仍打这个 session。
 * 点临时条：已是当前草稿则不动。点其它历史：丢掉未发言临时（无接口）。
 *
 * 三种来源按优先级：
 * 1. 本页仍在消费的流（关面板再开 / 切走再切回）：直接接上模块级那份响应式时间线，
 *    不再拉 events 覆盖 —— 否则会把正在增长的内容拍回「整轮落库前」的旧状态。
 * 2. 刷新前留下的快照：先立刻铺开，让首屏不是空白，再轮询 events 等落库接管。
 * 3. 都没有：拉 events 回放；末尾未收尾则续订。
 *
 * 不拦发送中的点击：切会话只做视图换绑，模块级流继续跑，切回能接上同一条 live。
 * 发送中的那一路 SSE 回调写的是模块级数组，不依赖当前是否正在显示它。
 * @param force 发送刚结束时 sending 仍为 true，需要强制刷新落库结果
 */
async function openHistory(id: number, force = false) {
  pageError.value = ''
  // 临时条无事件可拉；再点自己等于保持草稿
  if (id === TEMP_SESSION_ID) {
    if (!isTempSession(session.value)) {
      beginLocalDraft()
    }
    return
  }
  // 切走：落库空壳才删；临时条随 session 被覆盖即消失
  if (session.value?.id !== id) {
    await discardUnaskedCurrent()
  }
  // 换会话先停旧续订，避免上一个会话的轮询结果写进新的右栏
  stopResume()
  draftReady.value = false
  const token = ++openToken
  // 会话行先切过去：右栏渲染用的就是 session，先挂上标题/时间，不必等 events 回来。
  // 依次查：服务端列表 → 本地登记表 → 本页仍在消费的流（关面板重开后 knownSessions 已随
  // 旧组件销毁，只有模块级 live 还在）→ 最后兜一个壳，保证左栏有行且被选中。
  const live = liveOf(id)
  const found =
    history.value.find((item) => item.id === id) ??
    knownSessions.value.get(id) ??
    (live ? sessionStub(id, live.startTime) : undefined)
  if (found) {
    if (props.variant === 'page' && found.agentId != null && pickedId.value !== found.agentId) {
      restoring = true
      pickedId.value = found.agentId
    }
    session.value = mergeSessionMeta(found, session.value)
  }
  // 先登记，后面 syncSessionFromEvents 才能把补齐后的标题写回这一行
  rememberKnown(session.value)
  rememberTrialSession(ownerId.value as number, id, maMode.value ? 'ma' : 'agent')
  // 刷新后可能正停在「子计划待批准」：SSE 已断，按钮只能靠专用接口恢复；不阻塞主时间线加载
  childPending.value = null
  void refreshChildPending(id)

  // 1) 本页还在消费这一轮：直接接上，内容已在增长，禁止再用 events 拍平。
  //    已收尾的登记项不再回放（内容停在收尾那一刻），交给下面的 events 走权威路径。
  if (live && !live.finished) {
    turns.value = live.turns
    // 左栏标题与轮次从已收到的时间线反推，避免刚发出时这一行还显示「新对话」
    syncSessionFromTurns(id, live.turns)
    startClock()
    // 仍在跑：转圈由 trialStream 的 running 集合驱动，这里只需保持计时
    return
  }

  // 2) 刷新前留下的快照：先铺开，避免空白
  const snapshot = loadSnapshot(id)
  if (snapshot) {
    turns.value = snapshot
    startClock()
  }

  try {
    // 先读内存增量：刷新后 SSE 已断，这里是能立刻看到过程内容的唯一来源。
    // 没有增量时拿到的是落库事件，走原来的回放/续订判定。
    const fetched = await fetchResumeEvents(id)
    // 连点两个会话时，迟到的响应直接丢弃
    if (token !== openToken) {
      return
    }
    const events = fetched.events
    if (!found && session.value?.id !== id) {
      // 列表里还没这条（本轮刚发、尚未回写预览）：单独取元数据，别让右栏挂在不存在的会话上
      await attachSessionMeta(id)
      if (token !== openToken) {
        return
      }
    }
    // 用已取到的事件补齐标题/轮次，避免续订期间左栏仍是「新对话」且被当成空壳
    syncSessionFromEvents(events)
    // 有内存增量 = 这一轮确实在跑且能看到过程：直接进续订，第一拍就是最新内容
    if (fetched.live && events.length) {
      // 增量比刷新前的快照新，必须用它首发，不能保留旧快照
      beginResume(id, events, false, true)
      return
    }
    // 末尾不是助手正文/失败说明，说明这一轮仍在执行：续订并保留进行中卡。
    // 过旧的「进行中」是历史僵尸行，只回放已落库内容、不自动轮询，避免每次重进都白转一圈
    if (shouldResume(id, events)) {
      // 有快照说明刷新前已收到过更多内容：首次渲染保留快照，避免先闪一下空回放
      beginResume(id, events, !!snapshot, fetched.live)
      return
    }
    // 本轮已落库收尾：以服务端事件为准，快照完成使命
    turns.value = attachRouterTrace(turnsFromEvents(events))
    clearSnapshot(id)
    dropLive(id)
    // 计划停在待批准时不会再有后续事件，按钮只能靠专用接口恢复
    await restorePendingPlan(id, token)
  } catch {
    if (token === openToken) {
      pageError.value = '会话事件加载失败'
    }
  }
}

/**
 * 有事件但末尾收在「待批准计划」时补一次待批准查询。
 * 事件里只有 plan(PENDING)，拿不到「这份计划是否还能批准」（可能已过期或已被消费），
 * 所以按钮的显隐必须以专用接口为准，不能只看事件。
 *
 * @param id    会话主键
 * @param token 打开令牌；响应回来时已切了会话就丢弃
 */
async function restorePendingPlan(id: number, token: number) {
  const pending = await fetchPendingPlanSafe(id)
  if (token !== openToken) {
    return
  }
  applyPendingPlan(id, pending)
}

/**
 * 读待批准计划；失败返回 null，不影响已渲染的时间线。
 * @param id 会话主键
 */
async function fetchPendingPlanSafe(id: number) {
  try {
    const { data } = await getPendingTrialPlan(id)
    return data.data || null
  } catch {
    return null
  }
}

/**
 * 读编排根下当前待批的子计划；失败返回 null，不影响已渲染的时间线。
 *
 * @param id 会话主键
 */
async function fetchChildPendingSafe(id: number) {
  try {
    const { data } = await getPendingChildPlan(id)
    return data.data || null
  } catch {
    return null
  }
}

/**
 * 刷新「子计划待批准」卡片。刷新页面后 SSE 已断，按钮只能靠这条接口恢复。
 *
 * @param id 会话主键
 */
async function refreshChildPending(id: number) {
  const pending = await fetchChildPendingSafe(id)
  // 响应回来时已切了会话就丢弃，避免把上一个会话的待批卡片画到新会话上
  if (session.value?.id !== id) {
    return
  }
  childPending.value = pending
}

/**
 * 批准 / 拒绝编排根下的子专家计划。
 *
 * <p>批准接口只负责唤醒等待中的子线程，不代替根跑完整轮，因此这里不需要轮询进度：
 * 根 SSE 仍在连接上，子继续执行后的 PLAN / STEP / 终答都会照常推送。
 *
 * @param approved true＝批准，false＝拒绝
 */
async function decideChildPlan(approved: boolean) {
  const target = childPending.value
  const id = session.value?.id
  if (!target || id == null || childDeciding.value) {
    return
  }
  childDeciding.value = true
  pageError.value = ''
  try {
    if (approved) {
      await approveTrialPlan(id, target.routeKey)
    } else {
      await rejectTrialPlan(id, target.routeKey)
    }
    // 先本地收起卡片：接口成功后同一 route_key 已不可再批
    childPending.value = null
    if (!approved) {
      ElMessage.info('已拒绝该子计划，该子会记为失败，其它子继续')
    }
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : approved ? '批准子计划失败' : '拒绝子计划失败'
    // 失败时按服务端真实状态回填（可能已超时/已被处理），别让前端硬留一张过期卡片
    await refreshChildPending(id)
  } finally {
    childDeciding.value = false
  }
}

/**
 * 把待批准状态写回时间线：只有服务端确认仍待批准的那一轮才显示按钮。
 * 计划已失效（过期 / 已消费）时清掉标记，避免点出「没有待批准的计划」错误。
 *
 * @param id      会话主键
 * @param pending 待批准计划；null 表示没有
 */
function applyPendingPlan(id: number, pending: TrialPlanView | null) {
  if (session.value?.id !== id) {
    return
  }
  for (const turn of turns.value) {
    if (turn.role !== 'assistant') {
      continue
    }
    turn.planPending = !!pending && turn.planId === pending.planId
  }
}

/** 会话元数据兜底：事件接口可用但列表还没回写时，用它补齐状态与标题。 */
async function attachSessionMeta(id: number) {
  try {
    const { data } = await getTrialSession(id)
    if (data.data) {
      session.value = data.data
    }
  } catch {
    // 取不到元数据不影响展示已落库的事件
  }
}

/**
 * 重进后是否要续订。两种情况都要：
 * 1. 已有事件且末尾不是助手正文/失败说明 —— 本轮还在跑；
 * 2. 事件为空但会话是刚建的 —— USER 事件可能还没入库，属于本轮刚开始的极短窗口。
 * 历史僵尸 RUNNING（末尾事件很久以前）不续订，避免每次重进都白转一圈。
 * @param id     会话主键
 * @param events 刚拉到的事件
 */
function shouldResume(id: number, events: TrialEvent[]) {
  if (hasOpenTurn(events)) {
    return !openTurnStale(events, RESUME_FRESH_MS)
  }
  if (events.length) {
    return false
  }
  const current = session.value
  if (!current || current.id !== id) {
    return false
  }
  const stamp = parseSessionTime(current.startTime || current.updateTime)
  return stamp > 0 && Date.now() - stamp <= PENDING_FIRST_EVENT_MS
}

/**
 * 重进续订：本页没有 SSE 时（整页刷新 / 从列表点开），靠轮询把这一轮「续」出来。
 *
 * 优先读 `/live-events`（服务端内存镜像）：那里有思考、工具、正文的实时增量，
 * 因此刷新后也能边跑边看，不必等整轮落库。
 * 该接口在整轮结束后立即为空（多实例部署时读不到本实例的轮次也一样），
 * 此时回退到 `/events`：它才是权威数据，末尾收尾即本轮结束。
 * 两者都拿不到内容时如实标成断线，不假装还在思考。
 * @param id          会话主键
 * @param first       刚拉到的事件，作为首次渲染与等待起点依据
 * @param keepCurrent true 表示右栏已有更完整的内容（刷新前快照），首次渲染不要用它拍平
 * @param liveMode    true 表示 first 里含内存增量，本轮仍在跑，末尾卡要标成 streaming
 */
function beginResume(id: number, first: TrialEvent[], keepCurrent = false, liveMode = false) {
  const token = ++pollToken
  const startTime = Date.now()
  resumingId = id
  // 重进时确认仍在执行：登记到模块级 running，左栏该行显示转圈直到收尾
  markTurnRunning(id, true)
  // 有内存增量说明确实在跑且能看到过程，不算断线
  detached.value = !liveMode && !hasLiveStream(id)
  // 首拍不重放时也要清掉签名，让下一次真有新内容时必然重放
  resumeSignature = keepCurrent ? signOf(first) : ''
  if (!keepCurrent) {
    applyResumeEvents(id, first, liveMode)
  }
  startClock()
  scheduleResume(id, token, startTime)
}

/**
 * 拉这一轮的最新可见内容，并与已落库的历史合并。
 *
 * 为什么必须合并：`/live-events` 只有**当前这一轮**（会话可能已经聊过多轮），
 * 直接用它渲染会把之前的对话全弄丢。落库事件按 seq 与 live 事件天然衔接
 * （live 的用户事件用的是真实 nextSeq），所以按 seq 去重合并即可。
 *
 * 顺序：先读内存快照（有增量就渐进），再读落库事件补历史；
 * 整轮结束后快照为空，落库事件成为唯一来源。
 * @param id 会话主键
 * @return live 为 true 表示这一轮仍在跑且有实时增量；events 为合并后的事件
 */
async function fetchResumeEvents(id: number): Promise<{ live: boolean; events: TrialEvent[] }> {
  // 两个接口并行：先出首屏比省一次请求更重要；live 失败也不影响落库事件
  const [persisted, liveResp] = await Promise.all([listTrialEvents(id), fetchLiveSafe(id)])
  const stored = persisted.data.data || []
  const live = liveResp
  if (live.length) {
    return { live: true, events: mergeBySeq(stored, live) }
  }
  return { live: false, events: stored }
}

/**
 * 读内存快照，失败时返回空数组。
 * 老后端没有这个接口、或多实例读不到本进程的轮次，都属于正常情况，不该让整次续订失败。
 * @param id 会话主键
 */
async function fetchLiveSafe(id: number): Promise<TrialEvent[]> {
  try {
    const { data } = await listTrialLiveEvents(id)
    return data.data || []
  } catch {
    return []
  }
}

/**
 * 按 seq 合并两份事件，后者覆盖同序号的旧值，结果按 seq 升序。
 * 同一轮里 live 的事件比落库的更新（落库要等整轮结束），所以 live 优先。
 * @param stored 已落库事件
 * @param live   内存快照事件
 * @return 合并后的事件列表
 */
function mergeBySeq(stored: TrialEvent[], live: TrialEvent[]): TrialEvent[] {
  const bySeq = new Map<number, TrialEvent>()
  for (const item of stored) {
    bySeq.set(item.seq ?? 0, item)
  }
  for (const item of live) {
    bySeq.set(item.seq ?? 0, item)
  }
  return [...bySeq.values()].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
}

/** 排下一次轮询。会话已切走 / 组件销毁时令牌失配，直接退出。 */
function scheduleResume(id: number, token: number, startTime: number) {
  pollTimer = window.setTimeout(async () => {
    if (token !== pollToken) {
      return
    }
    // 超过硬顶就停：库里僵尸 RUNNING 不该让前端一直转
    if (Date.now() - startTime > RESUME_MAX_MS) {
      finishResume()
      pageError.value = '本轮等待超时，请稍后重新打开会话查看结果'
      return
    }
    try {
      const { live, events } = await fetchResumeEvents(id)
      if (token !== pollToken) {
        return
      }
      // 拿到内存增量 = 真在跑且能看到过程，不再标断线
      if (live) {
        detached.value = false
        applyResumeEvents(id, events, true)
        scheduleResume(id, token, startTime)
        return
      }
      // 没有增量：要么本轮已结束（events 收尾），要么只能等落库
      detached.value = true
      applyResumeEvents(id, events)
      // 事件一直为空说明这一轮其实没起来（发问被拒等）：短等后静默停止，不空转满整轮上限
      if (!events.length && Date.now() - startTime > RESUME_EMPTY_MS) {
        finishResume()
        return
      }
      // 内存快照已空 = 这一轮结束了。此时 events 是权威数据，末尾收尾即收工。
      // 不能拿 live 增量来判收尾：live 的 assistant 是流式中间态，会被误判成已结束。
      if (events.length && !hasOpenTurn(events)) {
        // 本轮已落库收尾：以服务端事件为准，快照与流式登记完成使命
        clearSnapshot(id)
        dropLive(id)
        finishResume()
        // 刷新左栏取最新预览/轮次
        await loadHistory(false)
        return
      }
      scheduleResume(id, token, startTime)
    } catch {
      if (token !== pollToken) {
        return
      }
      // 单次失败不放弃，下一拍继续；连续失败由超时兜底
      scheduleResume(id, token, startTime)
    }
  }, RESUME_POLL_MS)
}

/**
 * 把轮询到的全量事件重放成时间线；仍进行中则把末尾卡标成 streaming。
 * 事件为空（USER 尚未入库）时也要挂一张进行中的卡片，让粘性状态条有落点。
 * @param id     会话主键
 * @param events 已合并的事件
 * @param live   true 表示这一轮仍在跑（来自内存快照），末尾卡一律标成 streaming。
 *               false 时按落库事件结尾推断，避免把已结束的历史轮次画成进行中。
 */
function applyResumeEvents(id: number, events: TrialEvent[], live = false) {
  // 签名不变说明这一拍没有新内容：不重放，省掉整表重渲和强制滚动
  const signature = signOf(events)
  if (signature === resumeSignature) {
    return
  }
  resumeSignature = signature
  if (!events.length) {
    const placeholder = assistantTurn('live-tail', 'streaming')
    placeholder.phase = 'planning'
    turns.value = attachRouterTrace([placeholder])
    bindLiveTurns(id, turns.value)
    return
  }
  turns.value = attachRouterTrace(turnsFromEvents(events))
  // live 的 assistant 是流式中间态，hasOpenTurn 会误判成已收尾，故用显式标志
  if (live || hasOpenTurn(events)) {
    turns.value = attachRouterTrace(markLiveTail(turns.value, events))
  }
  // 续订期间数组会被整体替换；同步回模块级登记项，
  // 否则「切走再切回」拿到的还是旧的、已废弃的数组
  bindLiveTurns(id, turns.value)
  syncSessionFromEvents(events)
}

/**
 * 事件集合的指纹，用于判断这一拍有没有新内容。
 *
 * 不能只看「条数 + 末尾 seq」：内存增量是向同一条事件续写（seq 与条数都不变），
 * 那样算出来的指纹恒定，界面就会卡住不动、直到整轮结束才一次刷出 —— 正是要修的现象。
 * 因此把末尾正文长度也算进去。
 * @param events 事件列表（按 seq 升序）
 */
function signOf(events: TrialEvent[]) {
  if (!events.length) {
    return '0'
  }
  const last = events[events.length - 1]
  const text = last.contentText || last.summary || ''
  return `${events.length}:${last.seq ?? 0}:${text.length}`
}

/**
 * 用本页已收到的时间线补当前会话的标题与轮次。
 * `openHistory` 接上模块级 live 时手里没有 events（events 要等整轮落库才有），
 * 若不从 turns 反推，「关面板再开」后左栏这一行会一直是「新对话」。
 * @param id    会话主键
 * @param turns 本轮时间线
 */
function syncSessionFromTurns(id: number, turns: ChatTurn[]) {
  const current = session.value
  if (!current || current.id !== id || isTempSession(current)) {
    return
  }
  const asked = turns.filter((turn) => turn.role === 'user').length
  const firstUser = turns.find((turn) => turn.role === 'user')
  const text = (firstUser?.text || '').trim()
  const hasTitle = (current.lastMsgPreview || '').trim().length > 0
  const count = current.messageCount ?? 0
  if (asked <= count && (hasTitle || !text)) {
    return
  }
  session.value = {
    ...current,
    messageCount: Math.max(count, asked),
    lastMsgPreview: hasTitle ? current.lastMsgPreview : text.slice(0, 80)
  }
  patchKnown(id, {
    messageCount: session.value.messageCount,
    lastMsgPreview: session.value.lastMsgPreview
  })
}

/**
 * 后端要等整轮结束才回写 message_count / last_msg_preview，续订期间列表接口里的当前条还是 0 轮次；
 * 本地先按事件补齐，左栏才不会一直显示「新对话」，也不会被当成未提问空壳。
 */
function syncSessionFromEvents(events: TrialEvent[]) {
  const current = session.value
  if (!current || isTempSession(current)) {
    return
  }
  const asked = events.filter((item) => item.eventType === 'user').length
  const preview = events.find((item) => item.eventType === 'user')
  const text = (preview?.contentText || preview?.summary || '').trim()
  const hasTitle = (current.lastMsgPreview || '').trim().length > 0
  const count = current.messageCount ?? 0
  if (asked <= count && (hasTitle || !text)) {
    return
  }
  session.value = {
    ...current,
    messageCount: Math.max(count, asked),
    lastMsgPreview: hasTitle ? current.lastMsgPreview : text.slice(0, 80)
  }
  // 同步进本地登记表：切走之后左栏补行要用这份已补齐的标题
  patchKnown(current.id, {
    messageCount: session.value.messageCount,
    lastMsgPreview: session.value.lastMsgPreview
  })
}

/**
 * 服务端会话行与本地已补齐的值合并。
 * 服务端是权威，但「本轮执行中」这两列还没回写（0 轮次 + 空标题），
 * 直接用会把已按事件补齐的标题清空、并让当前条再次被判成未提问空壳。
 * @param fresh  列表接口返回的该会话
 * @param local  本地当前对象（可能已按事件补齐）
 * @return 合并后的会话
 */
function mergeSessionMeta(fresh: TrialSession, local?: TrialSession): TrialSession {
  if (!local || local.id !== fresh.id) {
    return fresh
  }
  return {
    ...fresh,
    messageCount: Math.max(fresh.messageCount ?? 0, local.messageCount ?? 0),
    lastMsgPreview: (fresh.lastMsgPreview || '').trim() || local.lastMsgPreview,
    // 标题可能已由异步任务写入：「服务端有值就覆盖，没值不要清掉本地已显示的那条」
    sessionTitle: (fresh.sessionTitle || '').trim() || local.sessionTitle,
    titleSource: (fresh.sessionTitle || '').trim() ? fresh.titleSource : local.titleSource
  }
}

/** 结束续订：停轮询并摘掉转圈。内容保留在 turns 里，不闪白。 */
function finishResume() {
  const id = resumingId
  stopPolling()
  // 只清本次续订的会话；别的行若有状态各自负责
  if (id != null) {
    finishLive(id)
  }
  // 等待落库的提示随续订一起结束
  detached.value = false
}

/**
 * 只停轮询，不动左栏转圈。用于发送接管展示：这一轮马上由 SSE 驱动，转圈应继续。
 */
function stopPolling() {
  if (pollTimer) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
  pollToken += 1
  resumingId = undefined
  resumeSignature = ''
  stopClock()
}

/**
 * 主动中断续订（打开别的会话、切 Agent、关窗）。
 * 只停本组件的轮询；**不动 running 状态**——那一轮可能还在服务端跑，
 * 也可能正由模块级 SSE 消费。转圈该由 trialStream 的登记项决定，不由「是否切走」决定，
 * 否则「切走再切回」就会看到转圈凭空消失、再凭空出现。
 */
function stopResume() {
  stopPolling()
  // 断线提示只对这一路续订有效，切走/关窗即失效
  detached.value = false
}

/**
 * SSE 边跑边追加（route 进思考时间线；reasoning / assistant 分进思考与终答）。
 * 临时「新对话」或无 session 时：首问才 create，再用返回 id 转正左栏条目；结束后用落库事件再铺一遍。
 *
 * @param content 用户输入
 * @param images  图片 data URI 数组，可空。后端先 OCR 再喂主模型
 */
async function send(content: string, images?: string[]) {
  if (sending.value || (!session.value && !draftReady.value)) {
    return
  }
  if ((!session.value || isTempSession(session.value)) && !ownerId.value) {
    return
  }
  sending.value = true
  pageError.value = ''
  // 新一轮开始：上一轮遗留的子计划待批卡片必须清掉，避免误导
  childPending.value = null
  // 手动发问接管展示：只停轮询，保留本会话的转圈（这一轮改由 SSE 驱动）。
  // 续订期间发送按钮是禁用的（服务端也会拒「该会话正在执行」），正常不会走到这里
  stopPolling()
  turns.value = [
    ...turns.value,
    { key: `local-u-${Date.now()}`, role: 'user', text: content, blocks: [], phase: 'done', status: 'done', startTime: 0 }
  ]
  // 取数组里的响应式代理再改，裸对象上的增量不会驱动思考/终答边收边渲
  turns.value = [...turns.value, assistantTurn(`local-a-${Date.now()}`, 'streaming')]
  const live = turns.value[turns.value.length - 1]
  startClock()
  // 不再 abort：关面板后读流与增量累积继续，重开直接接上这份响应式数据。
  // 这是「渐进显示」的前提——中间过程只走 SSE、不落库，断了就只能等整轮落库。
  // controller 仍要传：页面卸载时浏览器会自行中断连接，这里只提供句柄便于将来显式中断。
  const controller = new AbortController()
  let persisted = false
  // 本次发问实际打到哪个会话，收尾时按它摘转圈、落快照，避免中途 session 被换掉而清错对象
  let sessionId: number | undefined
  // 增量回调里用来节流写快照的会话 id（闭包里 sessionId 可能还没赋值）
  const touchSnapshot = () => {
    if (sessionId != null) {
      saveSnapshot(sessionId)
    }
  }
  try {
    sessionId = session.value && !isTempSession(session.value) ? session.value.id : undefined
    // 临时条 / 纯草稿：首问才落库；失败仍留临时态可再发
    if (sessionId == null) {
      try {
        // 入口决定建会话的接口：虚拟根走方案，绑根仍按 agentId
        const { data } = maMode.value
          ? await createMaTrialSession(ownerId.value as number)
          : await createTrialSession(ownerId.value as number)
        const created = data.data
        const preview = content.trim().slice(0, 80)
        session.value = {
          ...created,
          messageCount: 1,
          lastMsgPreview: (created.lastMsgPreview || '').trim() || preview
        }
        sessionId = created.id
        draftReady.value = false
        // 立刻记住：这之后关掉面板 / 刷新，重进也要能回到本轮（此时库里还没有预览和轮次）
        rememberTrialSession(ownerId.value as number, created.id, maMode.value ? 'ma' : 'agent')
        // 临时转正：立刻出现在左栏（loadHistory 稍后对齐服务端）
        history.value = [session.value, ...history.value.filter((row) => row.id !== created.id && !isTempSession(row))]
        rememberKnown(session.value)
      } catch {
        live.status = 'error'
        live.phase = 'error'
        pageError.value = '新建会话失败'
        return
      }
    }
    // 登记到模块级：关面板后读流继续、转圈不丢、重开能直接接上这份时间线
    beginLive(sessionId, turns.value)
    await streamTrialMessage(sessionId, content, {
      onPhase: (phase) => {
        live.phase = phase
      },
      onRoute: (summary) => {
        // judge 首个路由事件到达：先插编排调度卡（前端态），再记路由正文
        markRouterTrace(live)
        appendRoute(live, summary)
        touchSnapshot()
      },
      onReasoning: (text) => {
        appendText(live, 'reasoning', text)
        touchSnapshot()
      },
      onVision: (text) => {
        // 识别结果是本轮素材：紧跟用户气泡出现，先于任何思考与终答
        live.phase = 'vision'
        appendVision(live, text)
        touchSnapshot()
      },
      onTool: (tool) => {
        live.phase = 'tool'
        appendTool(live, tool.line, tool.result)
        touchSnapshot()
      },
      onPlan: (plan) => {
        // 结构化计划直接进计划卡；状态推进沿用同一 planId，不新插卡
        upsertPlan(live, {
          planId: plan.planId,
          state: plan.state,
          summary: plan.summary,
          steps: (plan.steps || []).map((step) => ({
            seq: step.seq,
            title: step.title,
            detail: step.detail || ''
          }))
        })
        touchSnapshot()
      },
      onStep: (step) => {
        live.phase = 'tool'
        upsertStep(live, {
          seq: step.seq,
          title: step.title,
          state: step.state,
          text: step.text || '',
          line: step.line
        })
        touchSnapshot()
      },
      onChildPlanPending: (pending) => {
        // 子专家计划待批准：后端单批准槽保证同时只有一条，这里直接覆盖本地
        childPending.value = pending
      },
      onFileGenerated: (file) => {
        // 产物是结果而不是过程：不改 phase，避免状态条从「回答生成中」退回「工具执行中」
        appendFile(live, {
          fileName: file.fileName,
          size: file.size,
          downloadUrl: file.downloadUrl
        })
        touchSnapshot()
      },
      onAssistant: (text) => {
        if (live.phase !== 'done') {
          live.phase = 'answering'
        }
        appendText(live, 'assistant', text)
        touchSnapshot()
      },
      onDone: (payload) => {
        persisted = true
        live.status = payload.status === 'FAILED' ? 'error' : 'done'
        live.phase = payload.status === 'FAILED' ? 'error' : 'done'
        // 停在待批准：不是失败，也不该显示「思考中」，等用户点批准或取消
        if (payload.planPending) {
          live.planPending = true
          live.status = 'done'
          live.phase = 'done'
        }
        // 根本轮已出终答（或整轮失败）：子计划不可能还在待批，本地卡片必须收起
        childPending.value = null
      },
      onError: (message) => {
        live.status = 'error'
        live.phase = 'error'
        pageError.value = message
        // 连接断了就再也收不到批准结果，留着按钮只会点出「已失效」
        childPending.value = null
      }
    }, controller.signal, images)
    // 落库成功后再用事件覆盖，避免把未入库的失败卡片刷掉。
    // done 到达时服务端已经写了轮次；先改掉创建接口带回的 0，防止紧接着的「新会话」把本条当空壳删掉
    if (persisted && session.value && session.value.id === sessionId) {
      const asked = session.value.messageCount ?? 0
      session.value = {
        ...session.value,
        messageCount: asked > 0 ? asked : 1,
        lastMsgPreview: (session.value.lastMsgPreview || '').trim() || content
      }
    }
    if (persisted) {
      // 落库完成：改用服务端事件重放，本轮流式登记与快照都完成使命
      finishLive(sessionId)
      // 只有用户还停在这个会话时才重放；发送中切到别处的话不能硬拉回来
      if (session.value?.id === sessionId) {
        await openHistory(sessionId, true)
      } else {
        dropLive(sessionId)
      }
      // 首轮标题由后端在本次请求内同步写库（首句截断）：先按会话单查补进左栏，
      // 「新对话」立刻变成首句摘要；延迟补拉保留为「服务端暂时不可见」时的兜底
      if ((session.value?.messageCount ?? 0) <= 1) {
        await refreshSessionTitle(sessionId)
        scheduleTitleRefresh(sessionId)
      }
    } else if (live.status === 'streaming') {
      live.status = 'error'
      live.phase = 'error'
      if (!pageError.value) {
        pageError.value = '连接已结束'
      }
    }
    await loadHistory(false)
  } catch (error) {
    live.status = 'error'
    live.phase = 'error'
    pageError.value = error instanceof Error ? error.message : '发送失败'
  } finally {
    // 本轮结束（含失败 / 中断 / 连接断开）：转圈必须停，不能靠下一次拉列表才清。
    // 登记项保留内容，重开还能看到已收到的部分；下次打开时若已落库由 openHistory 丢弃。
    if (sessionId != null) {
      finishLive(sessionId)
    }
    sending.value = false
    // 用户可能已在发送中切到别的会话并在那儿续订：那不是本轮的计时，别一起停掉
    if (!resumingId) {
      stopClock()
    }
  }
}

function startClock() {
  stopClock()
  now.value = Date.now()
  clock = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

/**
 * 批准待执行计划。后端接着跑剩余步骤，返回本轮事件；
 * 成功后按落库事件重放，让「待批准」那张计划卡换成执行中/已完成。
 *
 * <p>关键点：批准接口要跑完整轮才返回（可能几十秒到几分钟）。
 * 若等响应再更新，计划卡会一直停在「待批准」、按钮只原地转圈，界面与真实状态矛盾。
 * 因此这里做两件事：
 * 1. 发请求**之前**就把本地卡切到「执行中」并挂上进行态；
 * 2. 期间轮询内存快照，把「第 N 步进行中 → 完成」逐步补进时间线，而不是干等。
 * 服务端事件回来后仍以整段时间线为准（openHistory 重放会再对齐一次）。
 *
 * @param planId 该轮计划标识。仅用于本地定位，服务端按会话消费登记项
 */
async function approvePlan(planId?: string) {
  if (!session.value || isTempSession(session.value) || approving.value) {
    return
  }
  const id = session.value.id
  approving.value = true
  pageError.value = ''
  markTurnRunning(id, true)
  startClock()
  beginApprovedRun(planId)
  // 批准执行时的进展只能从内存快照拿到：先开一路轮询，让步骤边跑边出现
  const watcher = watchApproveProgress(id, planId)
  try {
    const { data } = await approveTrialPlan(id)
    const resp = data.data
    if (resp && resp.status === 'FAILED') {
      pageError.value = resp.errorSummary || '计划执行失败'
      advancePlanStateFor(planId, 'FAILED')
    }
    clearPendingPlanFlag(planId)
    // 接口只回本轮新增事件；必须重拉整段时间线，否则前面的用户气泡和「待批准」计划会丢。
    // 先丢掉本轮的本地流与快照，否则 openHistory 会去接那份即将过期的本地数组而不是服务端事件
    dropLive(id)
    finishLive(id)
    await openHistory(id, true)
    await loadHistory(false)
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '批准失败'
    // 失败时先收回本地进行态，再让服务端事件重新决定卡态：
    // 若计划其实还在待批准（网络抖动等），待批准查询会把按钮放回来；
    // 若已失效，卡片就停在实际状态上，不能由前端硬掰成「待批准」骗用户再点一次。
    endApprovedRun(planId)
    await openHistory(id, true)
  } finally {
    watcher.stop()
    approving.value = false
    finishLive(id)
    stopClock()
  }
}

/**
 * 批准执行期间增量轮询内存快照，把计划/步骤状态补进当前时间线。
 *
 * 与 {@link beginResume} 的区别：那条路径会整体替换 turns（历史 + 本轮全量），
 * 这里只做「就地合并」——把新到的 plan/step/思考/工具块按 upsert 规则并进最后一轮，
 * 因此不会打断已经在屏幕上的用户气泡与计划卡，也不会把终答插到计划之前。
 *
 * 轮询在调用方 `finally` 里停，且每次回调都核对当前会话是否仍是被批准的那个。
 *
 * @param id     被批准的会话主键
 * @param planId 该轮计划标识
 * @return 句柄，调用方负责 stop()
 */
function watchApproveProgress(id: number, planId?: string) {
  let timer: number | undefined
  let stopped = false
  const tick = async () => {
    if (stopped) {
      return
    }
    try {
      const { data } = await listTrialLiveEvents(id)
      const events = data.data || []
      if (session.value?.id === id) {
        mergeApproveEvents(events, planId)
      }
    } catch {
      // 单次失败不打断：批准接口本身才是权威，这里只是让过程早点可见
    }
    if (!stopped) {
      timer = window.setTimeout(tick, APPROVE_POLL_MS)
    }
  }
  timer = window.setTimeout(tick, APPROVE_POLL_MS)
  return {
    stop() {
      stopped = true
      if (timer) {
        window.clearTimeout(timer)
        timer = undefined
      }
    }
  }
}

/**
 * 批准执行期间已并入时间线的增量水位：键为「轮 key : seq : 类型」，值为已追加的字符数。
 *
 * 为什么需要它：内存快照里同一条 reasoning / assistant 事件是「续写」而非新增
 * （seq 不变、正文变长）。若每拍都把整段正文再 appendText 一次，
 * 思考与终答会被复制成好几份。这里只补新出现的那段后缀。
 */
const approveWatermark = new Map<string, number>()

/**
 * 把快照事件就地并入当前时间线。
 *
 * 只取计划类与中间过程事件（plan / step / reasoning / tool / assistant），
 * 忽略 user：批准不是新的用户输入，重复落一条用户气泡会与历史重复。
 * 续写型事件（reasoning / assistant）按 {@link approveWatermark} 只追加新增后缀。
 *
 * @param events 内存快照事件，按 seq 升序
 * @param planId 目标计划标识
 */
function mergeApproveEvents(events: TrialEvent[], planId?: string) {
  const turn = findPlanTurn(planId)
  if (!turn || !events.length) {
    return
  }
  for (const item of events) {
    if (item.eventType === 'plan') {
      // 计划卡已存在时按 planId 原位推进状态，不回退步骤列表
      const plan = parsePlan(item.contentText)
      if (plan) {
        upsertPlan(turn, plan)
      }
      continue
    }
    if (item.eventType === 'step') {
      const step = parseStep(item.contentText)
      if (step) {
        upsertStep(turn, step)
      }
      continue
    }
    if (item.eventType === 'reasoning') {
      appendApproveDelta(turn, item, 'reasoning', displayEventText(item))
      continue
    }
    if (item.eventType === 'tool') {
      // 工具是一条调用一条事件，不会续写；按下标只处理一次
      const key = `${turn.key}:${item.seq}:tool`
      if (approveWatermark.has(key)) {
        continue
      }
      approveWatermark.set(key, 0)
      const raw = displayEventText(item)
      const split = raw.indexOf('\n\n')
      if (split < 0) {
        appendTool(turn, raw)
      } else {
        appendTool(turn, raw.slice(0, split).trim(), raw.slice(split + 2).trim())
      }
      continue
    }
    if (item.eventType === 'assistant') {
      appendApproveDelta(turn, item, 'assistant', displayEventText(item))
      continue
    }
    if (item.eventType === 'file') {
      // 批准执行期间生成的文件也要出现在时间线上；按文件名覆盖，与实时推送同口径
      const file = parseFile(item.contentText, item.sessionId)
      if (file) {
        appendFile(turn, file)
      }
    }
  }
}

/**
 * 续写型事件只追加新增后缀。
 *
 * @param turn 目标轮
 * @param item 快照事件
 * @param kind 思考或终答
 * @param full 服务端给出的事件全文
 */
function appendApproveDelta(turn: ChatTurn, item: TrialEvent, kind: 'reasoning' | 'assistant', full: string) {
  const key = `${turn.key}:${item.seq}:${kind}`
  const applied = approveWatermark.get(key) ?? 0
  if (full.length <= applied) {
    return
  }
  approveWatermark.set(key, full.length)
  appendText(turn, kind, full.slice(applied))
}

/**
 * 事件展示正文：优先 content_text，旧数据退回 summary。
 * @param item 事件
 */
function displayEventText(item: TrialEvent) {
  return (item.contentText || item.summary || '').trim()
}

/**
 * 批准发出后立刻把本地时间线推成「执行中」：
 * 1. 计划卡状态改 EXECUTING，批准/取消按钮立即消失；
 * 2. 该轮改成 streaming，让顶部粘性状态条显示进行态并开始计时。
 *
 * 只动本地态，不改任何服务端数据；服务端事件回来后由 openHistory 整体覆盖对齐。
 *
 * @param planId 目标计划标识
 */
function beginApprovedRun(planId?: string) {
  // 新一轮的增量水位从零开始：上一轮的水位键不会命中，留着只会无谓增长
  approveWatermark.clear()
  const turn = findPlanTurn(planId)
  if (!turn) {
    return
  }
  advancePlanState(turn, planId, 'EXECUTING')
  turn.status = 'streaming'
  turn.phase = 'tool'
  turn.startTime = Date.now()
}

/**
 * 批准请求失败时收回本地进行态：状态条必须停，否则界面会一直转，与真实状态矛盾。
 * @param planId 目标计划标识
 */
function endApprovedRun(planId?: string) {
  const turn = findPlanTurn(planId)
  if (!turn) {
    return
  }
  turn.status = 'done'
  turn.phase = 'done'
}

/**
 * 只把计划卡状态改到目标值，不碰进行态。
 * 用于「批准接口回报失败」与「用户取消」两条路径。
 * @param planId 目标计划标识
 * @param state  目标状态
 */
function advancePlanStateFor(planId: string | undefined, state: string) {
  const turn = findPlanTurn(planId)
  if (turn) {
    advancePlanState(turn, planId, state)
  }
}

/**
 * 定位计划所属的那一轮助手卡。
 *
 * 优先按 planId 精确命中；planId 缺失时退到「最后一个带计划块的轮次」——
 * 不能退到 planPending，因为批准的一瞬间本地就已把待批准标记清掉了，
 * 再用它定位会找不到目标、后续状态推进全部落空。
 *
 * @param planId 计划标识，可空
 * @return 命中轮次；找不到时为 undefined
 */
function findPlanTurn(planId?: string) {
  for (let i = turns.value.length - 1; i >= 0; i -= 1) {
    const turn = turns.value[i]
    if (turn.role !== 'assistant') {
      continue
    }
    if (!planId) {
      if (turn.blocks.some((block) => block.kind === 'plan')) {
        return turn
      }
      continue
    }
    if (turn.planId === planId) {
      return turn
    }
  }
  return undefined
}

/**
 * 取消待执行计划：后端只落一条「计划已取消」，不执行任何步骤。
 *
 * @param planId 该轮计划标识
 */
async function rejectPlan(planId?: string) {
  if (!session.value || isTempSession(session.value) || approving.value) {
    return
  }
  try {
    await ElMessageBox.confirm('取消后不会执行任何步骤，确定取消这份计划？', '取消计划', {
      type: 'warning',
      confirmButtonText: '取消计划',
      cancelButtonText: '返回'
    })
  } catch {
    return
  }
  const id = session.value.id
  approving.value = true
  pageError.value = ''
  try {
    await rejectTrialPlan(id)
    advancePlanStateFor(planId, 'REJECTED')
    clearPendingPlanFlag(planId)
    // 同上：以服务端整段事件为准，不用局部响应拼时间线
    dropLive(id)
    finishLive(id)
    await openHistory(id, true)
    await loadHistory(false)
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '取消失败'
  } finally {
    approving.value = false
  }
}

/**
 * 清掉本地「待批准」标记。批准或取消后按钮必须立即消失，
 * 否则用户还能对一份已消费的计划再点一次（服务端会拒，但界面不该给这个机会）。
 *
 * @param planId 该轮计划标识；为空时清掉所有待批准轮次
 */
function clearPendingPlanFlag(planId?: string) {
  for (const turn of turns.value) {
    if (turn.planPending && (!planId || turn.planId === planId)) {
      turn.planPending = false
    }
  }
}

function stopClock() {
  if (clock) {
    window.clearInterval(clock)
    clock = undefined
  }
}
</script>

<style scoped>
.trial.drawer,
.trial.page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.picker-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.agent-select {
  width: 320px;
}

.split {
  display: flex;
  flex: 1;
  min-height: 0;
  gap: 12px;
}

.sessions {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 240px;
  min-height: 0;
  padding-right: 10px;
  border-right: 1px solid var(--ah-border);
}

.sessions-title {
  margin-bottom: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ah-border);
  font-size: 14px;
  font-weight: 650;
}

.session-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.session-hint {
  margin: 0 0 8px;
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 1.4;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.session-group + .session-group {
  margin-top: 12px;
}

.group-label {
  margin: 0 0 4px 8px;
  color: var(--ah-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
}

/* 悬停比选中浅一档，避免「鼠标扫过」和「已选中」长得一样 */
.session-item:hover {
  background: #f7f6ff;
}

/* 选中态：比悬停明显加深一档，仍克制（浅紫底 + 主色描边 + 左侧色条） */
.session-item.is-active {
  background: #e8e4ff;
  border-color: #cdc6ff;
  box-shadow: inset 3px 0 0 var(--ah-primary, #635bff);
}

.session-item.is-active .session-name {
  color: #3d34c9;
  font-weight: 650;
}

.session-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.session-name {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 副标题：预览 + 时间。预览可能很长，同样单行省略，不能把行高撑开 */
.session-time {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 本轮执行中：贴在该行最右侧，小号转圈；行内操作按钮的显隐优先级更低 */
.session-running {
  display: inline-flex;
  flex: none;
  align-items: center;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

.session-more {
  display: none;
  flex: none;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary);
  cursor: pointer;
}

.session-item:hover .session-more,
.session-item.is-active .session-more {
  display: grid;
}

/* 执行中优先显示转圈，不显示三点菜单，避免右侧两个图标挤在一起 */
.session-item.is-running .session-more,
.session-item.is-running:hover .session-more,
.session-item.is-running.is-active .session-more {
  display: none;
}

.session-more:hover {
  background: #ebe9ff;
  color: var(--ah-text);
}

.chat-col {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.block-alert {
  margin-bottom: 8px;
}

/* 子专家计划待批准：一条窄卡片，压在泳道与对话之间，不占聊天气泡位 */
.child-approval {
  margin-bottom: 10px;
  padding: 12px 14px;
  background: #fffdf6;
  border: 1px solid #f2d9a0;
  border-radius: 10px;
}

.child-approval-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.child-approval-main {
  min-width: 0;
}

.child-approval-title {
  margin: 4px 0 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ah-text, #1f2430);
}

.child-approval-sub {
  margin: 4px 0 0;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.55;
}

.child-approval-actions {
  display: flex;
  flex: none;
  gap: 8px;
}

.child-plan-steps {
  margin: 10px 0 0;
  padding-left: 0;
  list-style: none;
}

.child-plan-steps li {
  display: flex;
  gap: 8px;
  padding: 5px 0;
  border-top: 1px dashed #f0e3c6;
  font-size: 13px;
}

.child-plan-seq {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff3d6;
  color: #8a6116;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.child-plan-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.child-plan-detail {
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.5;
}
</style>

<style>
/* 下拉挂到 body，需非 scoped 才能把删除项染红 */
.session-remove-item {
  color: var(--el-color-danger) !important;
}

.session-remove-item:hover,
.session-remove-item:focus {
  color: var(--el-color-danger) !important;
  background-color: var(--el-color-danger-light-9) !important;
}
</style>
