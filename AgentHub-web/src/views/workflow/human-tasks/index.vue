<template>
  <div class="page">
    <PageHeader
      title="人工待办"
      description="工作流停在人工节点时产生的待办。默认只显示待我处理的 OPEN 记录；处理后会同步续跑或失败对应运行。"
    >
      <template #extra>
        <el-button :loading="loading" @click="loadRows">
          <el-icon class="btn-icon"><RefreshRight /></el-icon>
          刷新
        </el-button>
      </template>
    </PageHeader>

    <!-- 筛选：mine 打开时只看可处理，status 自动失效（服务端恒按 OPEN 查） -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="只看待我处理">
          <el-switch v-model="mine" @change="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="status"
            class="filter-select"
            :disabled="mine"
            placeholder="全部"
            @change="search"
          >
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="search">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">待办列表</span>
        <span class="panel-hint">
          共 {{ rows.length }} 条<span v-if="mine"> · 其中可处理 {{ actionableCount }} 条</span>
        </span>
      </div>
      <el-table v-loading="loading" :data="rows" empty-text="暂无人工待办" class="wf-table">
        <el-table-column label="Run" width="90" align="center">
          <template #default="{ row }">#{{ row.runId }}</template>
        </el-table-column>
        <el-table-column label="工作流" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-name">{{ row.workflowName || '—' }}</span>
            <span v-if="row.workflowCode" class="cell-sub">{{ row.workflowCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="节点" min-width="140" align="center" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nodeCode }}</template>
        </el-table-column>
        <el-table-column label="处理人规则" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.assigneeText }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="到期时间" min-width="180" align="center">
          <template #default="{ row }">{{ formatTime(row.expireTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDrawer(row)">查看</el-button>
            <el-button
              link
              type="primary"
              :disabled="!row.actionable"
              :title="row.actionable ? '打开并处理' : '当前用户不是该节点处理人，或运行不在等待人工'"
              @click="openDrawer(row)"
            >
              处理
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 详情抽屉：只展示导航信息 + 处理动作，不搬运行输出正文 -->
    <el-drawer v-model="drawerOpen" title="人工待办详情" size="460px" :close-on-click-modal="false">
      <div v-if="current" class="task-detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="待办 ID">{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="Run">#{{ current.runId }}（版本 v{{ current.releaseVersion ?? '—' }}）</el-descriptions-item>
          <el-descriptions-item label="工作流">
            {{ current.workflowName || '—' }}<span v-if="current.workflowCode"> · {{ current.workflowCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="节点">{{ current.nodeCode }}</el-descriptions-item>
          <el-descriptions-item label="运行状态">{{ runStatusLabel(current.runStatus) }}</el-descriptions-item>
          <el-descriptions-item label="处理人规则">{{ current.assigneeText }}</el-descriptions-item>
          <el-descriptions-item label="超时策略">{{ timeoutLabel(current.timeoutPolicy) }}</el-descriptions-item>
          <el-descriptions-item label="到期时间">{{ formatTime(current.expireTime) }}</el-descriptions-item>
          <el-descriptions-item label="待办状态">{{ statusLabel(current.status) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="!current.actionable"
          type="warning"
          show-icon
          :closable="false"
          class="block-alert"
          title="当前不可处理：你不是该节点处理人，或运行已不在等待人工。"
        />

        <el-form label-position="top" class="task-form">
          <el-form-item label="处理备注（可空，最多 512 字）">
            <el-input
              v-model="note"
              type="textarea"
              :rows="3"
              maxlength="512"
              show-word-limit
              placeholder="通过：可选填意见；驳回：建议写原因"
              :disabled="acting || !current.actionable"
            />
          </el-form-item>
        </el-form>

        <div class="task-actions">
          <el-button
            type="success"
            :loading="acting"
            :disabled="!current.actionable || acting"
            @click="decide(true)"
          >
            通过并续跑
          </el-button>
          <el-button
            type="danger"
            plain
            :loading="acting"
            :disabled="!current.actionable || acting"
            @click="decide(false)"
          >
            驳回
          </el-button>
          <el-button :disabled="acting" @click="drawerOpen = false">关闭</el-button>
        </div>
        <div class="task-tip">
          先按 Ctrl/⌘ 刷新页面再处理可拿到最新状态；通过后运行会同步续跑到下一个卡点或终态。
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'WorkflowHumanTasks' })
/**
 * 人工待办中心。所属域：workflow。
 *
 * <p>职责：拉取 {@code GET /api/workflow/human-tasks}，默认 mine=1 只看自己能处理的 OPEN；
 * 抽屉里展示 run / 工作流 / 节点 / 规则 / 到期，并执行 approve / reject。
 *
 * <p>边界：不读 run 详情（避免要求 workflow:run:trial）；不展示表单正文与运行输出；
 * 是否可处理以后端 actionable 为准，前端不再自算。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { approveHumanTask, listHumanTasks, rejectHumanTask } from '@/api/workflow'
import type { WorkflowHumanTaskItem } from '@/types/workflow'

/** 状态下拉：与后端 HumanTaskStatus 一致 */
const STATUS_OPTIONS = [
  { label: '待处理', value: 'OPEN' },
  { label: '已处理', value: 'DONE' },
  { label: '已过期', value: 'EXPIRED' }
]

const loading = ref(false)
const listError = ref('')
const rows = ref<WorkflowHumanTaskItem[]>([])

/** 默认只看待我处理：待办中心的主动作是「处理」而不是「审计」 */
const mine = ref(true)
const status = ref<string>('OPEN')

const drawerOpen = ref(false)
const current = ref<WorkflowHumanTaskItem | null>(null)
const note = ref('')
const acting = ref(false)

/** mine 下服务端只回可处理行，这里再数一遍用于提示 */
const actionableCount = computed(() => rows.value.filter((row) => row.actionable).length)

/**
 * 加载待办列表。
 * <p>mine=1 时不传 status：后端固定按 OPEN + 规则命中过滤。
 */
async function loadRows() {
  loading.value = true
  listError.value = ''
  try {
    const params = mine.value
      ? { mine: 1 }
      : { mine: 0, status: status.value || undefined }
    const { data } = await listHumanTasks(params)
    rows.value = data.data || []
  } catch {
    listError.value = '人工待办加载失败：请确认具备 workflow:human:act 权限'
    rows.value = []
  } finally {
    loading.value = false
  }
}

/** 查询：关掉抽屉并重拉 */
function search() {
  drawerOpen.value = false
  void loadRows()
}

/** 打开抽屉并重置备注 */
function openDrawer(row: WorkflowHumanTaskItem) {
  current.value = row
  note.value = ''
  drawerOpen.value = true
}

/**
 * 通过 / 驳回；成功后关闭抽屉并刷新列表（处理完的 OPEN 会消失）。
 *
 * @param approved true=通过
 */
async function decide(approved: boolean) {
  const task = current.value
  if (!task) {
    return
  }
  acting.value = true
  try {
    const { data } = approved
      ? await approveHumanTask(task.id, note.value || undefined)
      : await rejectHumanTask(task.id, note.value || undefined)
    const runStatus = data.data?.status
    drawerOpen.value = false
    ElMessage.success(
      approved
        ? `已通过，运行状态：${runStatusLabel(runStatus)}`
        : `已驳回，运行状态：${runStatusLabel(runStatus)}`
    )
    await loadRows()
  } catch {
    // 拦截器已弹出后端中文原因（含「你不是该节点处理人」）
  } finally {
    acting.value = false
  }
}

/** 待办状态中文名 */
function statusLabel(value: string): string {
  const map: Record<string, string> = { OPEN: '待处理', DONE: '已处理', EXPIRED: '已过期' }
  return map[value] || value || '—'
}

/** 待办状态 → tag 类型 */
function statusTagType(value: string): 'success' | 'warning' | 'info' | 'danger' {
  if (value === 'OPEN') return 'warning'
  if (value === 'DONE') return 'success'
  if (value === 'EXPIRED') return 'danger'
  return 'info'
}

/** 运行状态中文名（与试跑抽屉口径一致） */
function runStatusLabel(value?: string | null): string {
  const map: Record<string, string> = {
    CREATED: '已创建',
    RUNNING: '运行中',
    PAUSED_HUMAN: '等待人工',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return value ? map[value] || value : '—'
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

/** 时间展示：后端给 ISO / yyyy-MM-dd HH:mm:ss，统一截断到秒；空值给占位 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

onMounted(loadRows)
</script>

<style scoped>
.block-alert {
  margin-bottom: 12px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.panel-title {
  color: var(--ah-text-primary, #111827);
  font-size: 13px;
  font-weight: 600;
}

.panel-hint {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.cell-name {
  font-weight: 600;
}

.cell-sub {
  margin-left: 6px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.task-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.task-actions {
  display: flex;
  gap: 8px;
}

.task-tip {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.6;
}
</style>
