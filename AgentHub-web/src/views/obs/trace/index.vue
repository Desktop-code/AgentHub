<template>
  <div class="page">
    <PageHeader
      title="调用追踪"
      description="按会话查看每次问题和执行步骤。一次问题一条记录，路由按父子步骤展开，不计入 Token 用量。"
    />

    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <el-form-item label="智能体">
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
        <el-form-item label="会话">
          <el-input
            v-model="sessionQ"
            class="filter-input"
            clearable
            placeholder="预览或会话编号"
          />
        </el-form-item>
        <el-form-item label="问题">
          <el-input
            v-model="traceQ"
            class="filter-input"
            clearable
            placeholder="搜索问题"
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
    </div>

    <!-- 三栏：会话 25% / 问题 25% / 执行链路 50% -->
    <div class="board">
      <section class="col">
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
            <span class="item-title">{{ row.lastMsgPreview || '未命名会话' }}</span>
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
          <h2>问题</h2>
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
            <span class="item-title">{{ row.title || '（无问题文本）' }}</span>
            <span class="item-meta">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              {{ row.startTime || '—' }}
              <template v-if="row.latencyMs != null">
                · <span :class="latencyClass(row.latencyMs)">{{ latency(row.latencyMs) }}</span>
              </template>
              <template v-if="row.tokenTotal != null"> · {{ row.tokenTotal }} tok</template>
            </span>
          </button>
          <p v-if="selectedSessionId == null" class="empty">先选择左侧会话</p>
          <p v-else-if="!traceLoading && traces.length === 0" class="empty">没有问题</p>
        </div>
        <div class="col-pager">
          <span class="col-pager-meta">共 {{ traceTotal }} 条 · 第 {{ traceQuery.current }}/{{ tracePages }} 页</span>
          <div class="col-pager-nav">
            <el-button
              size="small"
              text
              :disabled="selectedSessionId == null || traceQuery.current <= 1 || traceLoading"
              @click="goTracePage(traceQuery.current - 1)"
            >
              ‹
            </el-button>
            <el-button
              size="small"
              text
              :disabled="selectedSessionId == null || traceQuery.current >= tracePages || traceLoading"
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
                :disabled="selectedSessionId == null || traceLoading"
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
        <div v-loading="spanLoading" class="col-body col-body--scroll">
          <p v-if="!user.hasPerm('obs:span:list')" class="empty">没有步骤查询权限</p>
          <p v-else-if="selectedTraceId == null" class="empty">选择一条问题后查看步骤</p>
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

    <!-- 放大：拓扑 + 宽屏入参/出参并排 -->
    <el-drawer
      v-model="spanDrawerOpen"
      :title="drawerTitle"
      direction="rtl"
      size="85%"
      append-to-body
      class="span-drawer"
      :modal="true"
      modal-class="span-drawer-mask"
    >
      <div v-loading="spanLoading" class="span-drawer__body">
        <p v-if="spans.length === 0" class="empty">没有步骤</p>
        <div v-else class="span-tree span-tree--wide">
          <TraceSpanFlow :spans="spans" :is-wide-mode="true" :show-topology="true" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'CallTrace' })
/**
 * 调用追踪三栏。所属域：obs。
 * 右栏瀑布流常驻；顶部拓扑仅在放大抽屉中展示。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { FullScreen, RefreshRight, Search } from '@element-plus/icons-vue'
import { pageAgents } from '@/api/agent'
import { listObsSpans, pageObsSessions, pageObsTraces } from '@/api/obs'
import type { AgentCard } from '@/types/agent'
import type { ObsSession, ObsSpan, ObsTrace } from '@/types/obs'
import { useUserStore } from '@/stores/user'
import PageHeader from '@/components/PageHeader.vue'
import TraceSpanFlow from '@/components/obs/TraceSpanFlow.vue'

const user = useUserStore()
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

/** 有选中问题且已有步骤时才允许放大 */
const canExpandSpans = computed(
  () => !!selectedTraceId.value && user.hasPerm('obs:span:list') && spans.value.length > 0
)

/** 全屏抽屉标题：执行链路 + 链路 ID */
const drawerTitle = computed(() =>
  selectedTraceId.value ? `执行链路 (${selectedTraceId.value})` : '执行链路'
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

/** 点查询：会话与问题条件同时生效；有选中会话时刷新问题列表。 */
function onSearch() {
  reloadSessions()
  if (selectedSessionId.value != null) {
    reloadTraces()
  }
}

/** 清空智能体 / 会话 / 问题筛选后重新拉取。 */
function onReset() {
  agentId.value = undefined
  sessionQ.value = ''
  traceQ.value = ''
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
 * 提问翻页。未选会话时不请求。
 */
function goTracePage(page: number) {
  if (selectedSessionId.value == null) {
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
  if (selectedSessionId.value == null) {
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
  if (selectedSessionId.value == null) {
    traces.value = []
    traceTotal.value = 0
    traceJump.value = '1'
    return
  }
  traceLoading.value = true
  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      const { data } = await pageObsTraces(selectedSessionId.value, {
        current: traceQuery.current,
        size: traceQuery.size,
        q: traceQ.value.trim() || undefined
      })
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

onMounted(async () => {
  try {
    const { data } = await pageAgents({ current: 1, size: 100 })
    agents.value = data.data.records
  } catch {
    agents.value = []
  }
  await loadSessions()
})
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

.span-tree--wide {
  padding: 0 4px 24px;
}

.span-drawer__body {
  min-height: 200px;
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

<style>
/* 抽屉挂到 body，需非 scoped；半透明遮罩 + 内容区可滚 */
.span-drawer-mask {
  background-color: rgba(15, 23, 42, 0.45) !important;
}

.span-drawer .el-drawer__body {
  padding-top: 4px;
  overflow: auto;
}
</style>
