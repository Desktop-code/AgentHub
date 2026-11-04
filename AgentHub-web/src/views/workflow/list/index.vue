<template>
  <div class="page">
    <!-- 页头：标题 + 说明 + 新建入口（新建按钮只在有编辑权限时出现） -->
    <PageHeader title="工作流" description="可视化编排 DAG：建图、存草稿、发布不可变版本；试跑与人工待办在编辑页与「编排 → 人工待办」处理。">
      <template #extra>
        <!-- 应用触发：走 /api/open/workflow/runs（app_key + secret），控制台只做说明与授权指引 -->
        <el-button v-if="user.hasPerm('workflow:run:invoke')" @click="invokeOpen = true">
          应用触发
        </el-button>
        <el-button v-if="user.hasPerm('workflow:def:edit')" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建工作流
        </el-button>
      </template>
    </PageHeader>

    <!-- 筛选条：关键字匹配编码/名称，状态按生命周期过滤 -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" class="filter-input" clearable placeholder="编码或名称" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="filter-select" clearable placeholder="全部">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="search">
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

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <section class="panel">
      <el-table v-loading="loading" :data="rows" empty-text="还没有工作流" class="wf-table">
        <el-table-column label="名称" min-width="180" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" min-width="160" align="center" show-overflow-tooltip />
        <el-table-column label="状态" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :class="row.status === 'HAS_RELEASE' ? 'tag-on' : 'tag-off'">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认版本" min-width="110" align="center">
          <template #default="{ row }">
            <span v-if="row.defaultVersion">v{{ row.defaultVersion }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" min-width="180" align="center" />
        <el-table-column label="操作" min-width="170" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditor(row)">
              {{ user.hasPerm('workflow:def:edit') ? '编辑' : '查看' }}
            </el-button>
            <el-button link type="primary" @click="openReleases(row)">版本</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePager v-model:current="query.current" v-model:size="query.size" :total="total" @change="loadRows" />
    </section>

    <!-- 新建：编码创建后冻结，故在此一次定稿 -->
    <el-dialog v-model="createOpen" title="新建工作流" width="520px" :close-on-click-modal="false">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="88px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="createForm.code" maxlength="64" placeholder="如 order_approval，创建后不可改" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="createForm.name" maxlength="128" placeholder="中文名，如 订单审批流" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.description" type="textarea" :rows="3" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="creating" @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建并编辑</el-button>
      </template>
    </el-dialog>

    <!-- 应用触发说明：不在控制台代调，只给接口契约与授权入口指路 -->
    <el-dialog v-model="invokeOpen" title="应用触发（OpenAPI）" width="640px">
      <div class="invoke-doc">
        <p class="invoke-doc__lead">
          外部应用用 <strong>AppId + 密钥</strong> 调 OpenAPI 触发工作流；只跑<strong>已发布版本</strong>，
          草稿不可触发。run 的 <code>triggerType</code> 记为 <code>APP</code>。
        </p>
        <ol class="invoke-doc__steps">
          <li>在「系统管理 → 应用管理 → 工作流授权」里，把本工作流绑定到对应应用（仅列已发布版本）。</li>
          <li>用该应用的 AppId 与密钥发起请求（密钥只在创建 / 重置时显示一次）。</li>
          <li>按返回的 <code>runId</code> 查询运行结果；人工卡点仍在「编排 → 人工待办」处理。</li>
        </ol>
        <div class="invoke-doc__label">请求示例</div>
        <pre class="invoke-doc__code">POST /api/open/workflow/runs
X-App-Key: app_xxxxxxxx
X-App-Secret: ********

{
  "workflowId": 1,
  "input": { "query": "帮我审批这张订单" },
  "clientRequestId": "order-20261104-001"
}

→ { "runId": 12, "status": "CREATED", "workflowId": 1, "releaseId": 3, "triggerType": "APP" }</pre>
        <p class="invoke-doc__note">
          同一应用下 <code>clientRequestId</code> 重复提交会返回已创建的 run，不会重复触发。
        </p>
      </div>
      <template #footer>
        <el-button @click="invokeOpen = false">关闭</el-button>
        <el-button type="primary" @click="goAppManage">去应用管理授权</el-button>
      </template>
    </el-dialog>

    <!-- 版本：只读快照列表，发布后不可改 -->
    <el-drawer v-model="releaseOpen" :title="`发布版本 · ${releaseTarget?.name || ''}`" size="520px">
      <div v-loading="releaseLoading" class="release-body">
        <el-alert
          v-if="releaseError"
          :title="releaseError"
          type="error"
          show-icon
          :closable="false"
          class="block-alert"
        />
        <el-empty v-else-if="!releaseLoading && !releases.length" description="还没有发布版本" />
        <div v-for="item in releases" :key="item.id" class="release-item">
          <div class="release-item__head">
            <el-tag size="small" type="primary" effect="plain">v{{ item.version }}</el-tag>
            <span class="release-item__time">{{ item.publishTime }}</span>
          </div>
          <div class="release-item__note">{{ item.publishNote || '无发布说明' }}</div>
          <div class="release-item__fp" :title="item.fingerprint">指纹 {{ item.fingerprint.slice(0, 12) }}…</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'WorkflowList' })
/**
 * 工作流列表页。所属域：workflow。
 * <p>职责：分页检索、新建（后端自动建最小草稿）、进入画布编辑、查看历史发布版本。
 * <p>边界：本页不发任何执行请求，也不展示试跑入口。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import {
  createWorkflowDef,
  listWorkflowReleases,
  pageWorkflowDefs
} from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import type { WorkflowDefRow, WorkflowReleaseRow } from '@/types/workflow'

const user = useUserStore()
const router = useRouter()

/** 状态下拉：生命周期，不是启停开关 */
const STATUS_OPTIONS = [
  { label: '未发布', value: 'DRAFT_ONLY' },
  { label: '已发布', value: 'HAS_RELEASE' }
]

const loading = ref(false)
const listError = ref('')
const rows = ref<WorkflowDefRow[]>([])
const total = ref(0)
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  status: undefined as string | undefined
})

const createOpen = ref(false)
/** 应用触发说明弹窗；本页不代调 OpenAPI */
const invokeOpen = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance | null>(null)
const createForm = reactive({ code: '', name: '', description: '' })
const createRules: FormRules = {
  code: [
    { required: true, message: '请填写编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9][A-Za-z0-9_.-]{1,63}$/,
      message: '仅支持字母、数字、点、下划线、中划线，2～64 位',
      trigger: 'blur'
    }
  ],
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }]
}

const releaseOpen = ref(false)
const releaseLoading = ref(false)
const releaseError = ref('')
const releaseTarget = ref<WorkflowDefRow | null>(null)
const releases = ref<WorkflowReleaseRow[]>([])

/** 生命周期 → 中文 */
function statusLabel(status: string): string {
  return status === 'HAS_RELEASE' ? '已发布' : '未发布'
}

/** 分页加载；失败只出中文错误态，不伪造空数据成功 */
async function loadRows() {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await pageWorkflowDefs({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined
    })
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
  } catch {
    listError.value = '工作流列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询回第一页 */
function search() {
  query.current = 1
  void loadRows()
}

/** 重置筛选条件并重查 */
function resetFilter() {
  query.keyword = ''
  query.status = undefined
  search()
}

function openCreate() {
  createForm.code = ''
  createForm.name = ''
  createForm.description = ''
  createOpen.value = true
}

/** 创建成功后直接进编辑页：新定义自带 1 START + 1 END 骨架 */
async function submitCreate() {
  const form = createFormRef.value
  if (!form) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }
  creating.value = true
  try {
    const { data } = await createWorkflowDef({
      code: createForm.code.trim(),
      name: createForm.name.trim(),
      description: createForm.description.trim() || undefined
    })
    createOpen.value = false
    ElMessage.success('已创建')
    const id = data.data?.id
    if (id) {
      void router.push(`/orchestration/workflow/${id}`)
      return
    }
    void loadRows()
  } finally {
    creating.value = false
  }
}

/** 进入画布编辑页（只读角色也能看，保存按钮由页面按权限隐藏） */
function openEditor(row: WorkflowDefRow) {
  void router.push(`/orchestration/workflow/${row.id}`)
}

/** 打开历史版本抽屉 */
async function openReleases(row: WorkflowDefRow) {
  releaseTarget.value = row
  releaseOpen.value = true
  releaseLoading.value = true
  releaseError.value = ''
  releases.value = []
  try {
    const { data } = await listWorkflowReleases(row.id)
    releases.value = data.data || []
  } catch {
    releaseError.value = '版本列表加载失败'
  } finally {
    releaseLoading.value = false
  }
}

/** 跳到应用管理页做工作流授权；保持弹窗关闭，避免回退时残留 */
function goAppManage() {
  invokeOpen.value = false
  void router.push('/system/app')
}

onMounted(loadRows)
</script>

<style scoped>
.block-alert {
  margin-bottom: 12px;
}

.cell-name {
  font-weight: 600;
}

.muted {
  color: var(--ah-text-muted, #9ca3af);
}

.tag-on {
  --el-tag-text-color: #10b981;
  --el-tag-border-color: #a7f3d0;
  --el-tag-bg-color: #ecfdf5;
}

.tag-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}

.release-body {
  min-height: 120px;
}

.release-item {
  padding: 12px;
  margin-bottom: 10px;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 10px;
  background: #fff;
}

.release-item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.release-item__time {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.release-item__note {
  margin-top: 6px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  line-height: 1.5;
  word-break: break-all;
}

.release-item__fp {
  margin-top: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
}

/* 应用触发说明：偏文档排版，代码块保持可读但不喧宾夺主 */
.invoke-doc__lead {
  margin: 0 0 10px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  line-height: 1.7;
}

.invoke-doc__steps {
  margin: 0 0 14px;
  padding-left: 20px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  line-height: 1.9;
}

.invoke-doc__label {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}

.invoke-doc__code {
  margin: 0 0 10px;
  padding: 12px;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 8px;
  background: #f8fafc;
  color: #1f2937;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}

.invoke-doc__note {
  margin: 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}
</style>
