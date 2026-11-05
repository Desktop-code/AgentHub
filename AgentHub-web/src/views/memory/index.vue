<template>
  <div class="page">
    <!-- 本页只读审计：无新建/刷新主操作，页头只留标题与说明 -->
    <PageHeader
      title="记忆"
      description="查询与审计记忆条目；短期/长期行为策略在 Agent 配置侧维护，本页不做策略配置。"
    />

    <!-- 强制 Agent + 记忆范围；User 用下拉；状态码 0/1/2 映射后端 lifeStatus；样式对齐用户管理 -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="Agent" required>
          <el-select
            v-model="filter.agentId"
            filterable
            placeholder="请选择 Agent"
            class="filter-select"
          >
            <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="记忆范围" required>
          <el-select
            v-model="filter.scope"
            placeholder="请选择记忆范围"
            class="filter-select"
            @change="onScopeChange"
          >
            <el-option label="短期记忆" value="SHORT" />
            <el-option label="长期记忆" value="LONG" />
            <el-option label="工作记忆" value="AGENT" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="showSessionId" label="Session ID">
          <el-input
            v-model="filter.sessionId"
            clearable
            placeholder="按 Session ID 查询"
            class="filter-input"
          />
        </el-form-item>
        <el-form-item label="用户">
          <div class="user-field">
            <el-select
              v-model="filter.userId"
              clearable
              filterable
              placeholder="请选择用户"
              class="filter-select"
            >
              <el-option
                v-for="u in users"
                :key="u.id"
                :label="userOptionLabel(u)"
                :value="u.id"
              />
            </el-select>
            <p v-if="filter.scope === 'LONG'" class="hint-long">
              长期记忆属于用户级数据，强烈建议选择用户进行精准查询
            </p>
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filter.status" clearable placeholder="全部" class="filter-select">
            <el-option label="全部" :value="null" />
            <el-option label="正常" :value="0" />
            <el-option label="已过期" :value="1" />
            <el-option label="已删除" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            class="filter-date"
          />
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" native-type="submit">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" @click="resetFilter">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" empty-text="请先选择 Agent 和记忆范围后查询">
        <!-- 最左序号列：跨页连续（本页分页字段为 pageNum/pageSize），纯展示 -->
        <el-table-column v-bind="indexColumnProps" />
        <el-table-column prop="title" label="标题" min-width="120" show-overflow-tooltip />
        <el-table-column prop="factKey" label="事实键" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.factKey || '—' }}</template>
        </el-table-column>
        <el-table-column prop="scope" label="范围" width="100">
          <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="归属" min-width="180">
          <template #default="{ row }">
            <el-tooltip :content="ownerIdTip(row)" placement="top">
              <span class="owner-text">{{ ownerLabel(row) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="记忆内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="重要性" width="100">
          <template #default="{ row }">
            <el-tag :type="importanceTag(row.importance).type" size="small" effect="plain">
              {{ importanceTag(row.importance).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row).type" size="small">{{ statusTag(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sessionId" label="Session" width="100" />
        <el-table-column prop="expiredTime" label="过期时间" min-width="170" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="user.hasPerm('memory:item:remove')"
              link
              type="danger"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <TablePager
        v-model:current="query.pageNum"
        v-model:size="query.pageSize"
        :total="total"
        @change="onPageChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'MemoryManage' })
/**
 * 记忆页。所属域：memory。
 * 审计查询为主：强制 Agent + 范围；筛选条对齐用户管理；本页不做新建。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageAgents } from '@/api/agent'
import { deleteMemoryItem, pageMemoryItems } from '@/api/memory'
import { pageUsers } from '@/api/user'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { useUserStore } from '@/stores/user'
import type { AgentCard } from '@/types/agent'
import type { UserRow } from '@/types'
import type { MemoryItem } from '@/types/memory'

/** 列表展示用生命周期：0 已生效 / 1 已过期 / 2 已覆盖删除（筛选项同码）。 */
type DisplayStatus = 0 | 1 | 2

const user = useUserStore()
const agents = ref<AgentCard[]>([])
const users = ref<UserRow[]>([])
const loading = ref(false)
const rows = ref<MemoryItem[]>([])
const total = ref(0)
/** 是否已通过必选维度发起过查询。 */
const searched = ref(false)

/** 分页：对外请求用 pageNum / pageSize，与后端别名对齐。 */
const query = reactive({ pageNum: 1, pageSize: 10 })
/** 记忆表最左「序号」列；分页字段是 pageNum/pageSize，序号仍按跨页连续计算 */
const { indexColumnProps } = useListIndexColumn(() => query.pageNum, () => query.pageSize)

/**
 * 筛选：status 用展示码 0/1/2，请求时映射 lifeStatus。
 * userId 为下拉选中的平台用户主键。
 */
const filter = reactive<{
  agentId?: number
  scope?: string
  sessionId: string
  userId?: number
  /** null/undefined=全部；0 正常；1 已过期；2 已删除 */
  status: DisplayStatus | null
}>({
  agentId: undefined,
  scope: undefined,
  sessionId: '',
  userId: undefined,
  status: null
})

const dateRange = ref<[string, string] | null>(null)

const agentNameMap = computed(() => {
  const map = new Map<number, string>()
  for (const a of agents.value) {
    map.set(a.id, a.name)
  }
  return map
})

const userNameMap = computed(() => {
  const map = new Map<number, string>()
  for (const u of users.value) {
    map.set(u.id, u.nickname?.trim() || u.username)
  }
  return map
})

/** SHORT / AGENT 显示 Session；LONG 隐藏。 */
const showSessionId = computed(() => filter.scope === 'SHORT' || filter.scope === 'AGENT')

/** 展示码 → 后端 lifeStatus；null 表示不限。 */
function toLifeStatus(status: DisplayStatus | null): 'NORMAL' | 'EXPIRED' | 'DELETED' | undefined {
  if (status === 0) return 'NORMAL'
  if (status === 1) return 'EXPIRED'
  if (status === 2) return 'DELETED'
  return undefined
}

/** 用户下拉展示：昵称优先，否则登录名。 */
function userOptionLabel(u: UserRow) {
  return u.nickname?.trim() ? `${u.nickname}（${u.username}）` : u.username
}

function scopeLabel(scope: string) {
  if (scope === 'SHORT') return '短期'
  if (scope === 'LONG') return '长期'
  if (scope === 'AGENT') return '工作'
  return scope
}

/** 归属列文案：Agent名 / 用户名；缺省时降级为 #id。 */
function ownerLabel(row: MemoryItem) {
  const agentPart =
    row.agentId == null
      ? '—'
      : agentNameMap.value.get(row.agentId) || `#${row.agentId}`
  const userPart =
    row.userId == null
      ? '—'
      : userNameMap.value.get(row.userId) || `#${row.userId}`
  return `${agentPart} / ${userPart}`
}

/** Tooltip 展示原始 ID，便于核对映射。 */
function ownerIdTip(row: MemoryItem) {
  return `Agent ID: ${row.agentId ?? '—'} · User ID: ${row.userId ?? '—'}`
}

/** 重要性 Tag：≥90 极高，≥70 高，其余普通。 */
function importanceTag(importance?: number | null) {
  const n = importance ?? 0
  if (n >= 90) return { type: 'danger' as const, label: '极高' }
  if (n >= 70) return { type: 'warning' as const, label: '高' }
  return { type: 'info' as const, label: '普通' }
}

/**
 * 行状态展示码：筛「已删除」时整页为 2；否则按停用/过期时间推导。
 * 0 已生效 · 1 已过期 · 2 已覆盖/删除
 */
function resolveDisplayStatus(row: MemoryItem): DisplayStatus {
  if (filter.status === 2) {
    return 2
  }
  if (row.status === 0) {
    return 2
  }
  if (row.expiredTime) {
    const ts = Date.parse(String(row.expiredTime).replace(/-/g, '/'))
    if (!Number.isNaN(ts) && ts <= Date.now()) {
      return 1
    }
  }
  return 0
}

function statusTag(row: MemoryItem) {
  const code = resolveDisplayStatus(row)
  if (code === 0) return { type: 'success' as const, label: '已生效' }
  if (code === 1) return { type: 'info' as const, label: '已过期' }
  return { type: 'danger' as const, label: '已覆盖/删除' }
}

async function loadAgents() {
  const { data } = await pageAgents({ current: 1, size: 100, status: 1 })
  agents.value = data.data.records
}

/** 拉取用户供筛选与归属列映射。 */
async function loadUsers() {
  const { data } = await pageUsers({ current: 1, size: 100, status: 1 })
  users.value = data.data.records
}

function onScopeChange() {
  if (filter.scope === 'LONG') {
    filter.sessionId = ''
  }
}

function ensureRequiredFilter(): boolean {
  if (filter.agentId == null || !filter.scope) {
    ElMessage.warning('请先选择 Agent 和记忆范围')
    return false
  }
  return true
}

/** 查询：校验后从第一页重新加载。 */
async function search() {
  if (!ensureRequiredFilter()) {
    return
  }
  query.pageNum = 1
  searched.value = true
  await loadItems()
}

/**
 * 重置：清空条件与结果，并回到未查询态（表格空列表即一次「重载」）。
 * 必选维度已清空时不能打列表接口，否则会被后端拒绝。
 */
async function resetFilter() {
  filter.agentId = undefined
  filter.scope = undefined
  filter.sessionId = ''
  filter.userId = undefined
  filter.status = null
  dateRange.value = null
  query.pageNum = 1
  query.pageSize = 10
  searched.value = false
  rows.value = []
  total.value = 0
}

function parseOptionalSessionId(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (!trimmed) return undefined
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : undefined
}

/** 按筛选 + 分页拉取；pageNum/pageSize 写入请求。 */
async function loadItems() {
  if (!ensureRequiredFilter()) {
    return
  }
  loading.value = true
  try {
    const { data } = await pageMemoryItems({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      agentId: filter.agentId,
      scope: filter.scope,
      sessionId: showSessionId.value ? parseOptionalSessionId(filter.sessionId) : undefined,
      userId: filter.userId,
      lifeStatus: toLifeStatus(filter.status),
      createTimeFrom: dateRange.value?.[0],
      createTimeTo: dateRange.value?.[1]
    })
    rows.value = data.data.records
    total.value = data.data.total
  } finally {
    loading.value = false
  }
}

/** 翻页：仅在已成功查询过时请求；每页条数切换由 TablePager 归一到第 1 页后同样走这里。 */
async function onPageChange() {
  if (!searched.value) return
  await loadItems()
}

async function remove(row: MemoryItem) {
  await ElMessageBox.confirm('删除后不再注入这条记忆', '删除记忆')
  await deleteMemoryItem(row.id)
  if (searched.value) {
    await loadItems()
  }
}

onMounted(async () => {
  await Promise.all([loadAgents(), loadUsers()])
})
</script>

<style scoped>
.filter-date {
  width: 360px;
}

.user-field {
  display: inline-flex;
  flex-direction: column;
}

.hint-long {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.4;
  max-width: 220px;
}

.owner-text {
  cursor: default;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
