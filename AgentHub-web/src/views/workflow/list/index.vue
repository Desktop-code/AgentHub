<template>
  <div class="page">
    <!-- 页头只保留标题与说明；接入配置语义较弱，收进「更多」下拉避免误点 -->
    <PageHeader title="工作流" description="可视化编排 DAG：建图、存草稿、发布不可变版本；试跑与人工待办在编辑页与「编排 → 人工待办」处理。">
      <template #extra>
        <!-- 应用触发 / 应用授权不是高频动作，统一收进下拉；无任一权限时不占位 -->
        <el-dropdown v-if="showMoreMenu" trigger="click" @command="onHeaderCommand">
          <el-button>
            更多
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <!-- 原「应用触发」弹窗不改接口契约，仅在入口处改名为「接入配置」 -->
              <el-dropdown-item v-if="user.hasPerm('workflow:run:invoke')" command="access">
                接入配置
              </el-dropdown-item>
              <el-dropdown-item v-if="user.hasPerm('system:app:list')" command="app">
                应用授权
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </PageHeader>

    <!-- 筛选条：关键字匹配编码/名称，状态按生命周期过滤；主新建与筛选同卡右侧对齐 -->
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
      <!-- 主操作跟随筛选卡右对齐；filter-actions 的 margin-left:auto 由全局样式提供 -->
      <div v-if="canEdit" class="filter-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建工作流
        </el-button>
      </div>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <section class="panel">
      <el-table v-loading="loading" :data="rows" empty-text="还没有工作流" class="wf-table">
        <!-- 最左序号列：跨页连续，纯展示，不参与接口排序 -->
        <el-table-column v-bind="indexColumnProps" />
        <!-- 名称双行：主行名称 + 副行简介，信息密度接近企业级中台列表 -->
        <el-table-column label="名称" min-width="220">
          <template #default="{ row }">
            <div class="cell-name" :title="row.name">{{ row.name }}</div>
            <div class="cell-sub" :title="row.description?.trim() || '暂无简介'">
              {{ row.description?.trim() || '暂无简介' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <!-- 已发布绿 / 草稿灰 / 停用红；停用为后端预留态，无数据时不显示 -->
            <el-tag size="small" effect="light" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="100" align="center">
          <template #default="{ row }">
            <!-- 版本号可点：复用右栏「版本」抽屉，避免只读数字不可操作 -->
            <el-button v-if="row.defaultVersion" link type="primary" @click="openReleases(row)">
              v{{ row.defaultVersion }}
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditor(row)">
              {{ canEdit ? '编辑' : '查看' }}
            </el-button>
            <el-button link type="primary" @click="openReleases(row)">版本</el-button>
            <!-- 试跑与运行记录都依赖 run:trial（与后端 @SaCheckPermission 同码） -->
            <el-button v-if="canRunTrial" link type="primary" @click="openTrial(row)">试跑</el-button>
            <el-button v-if="canRunTrial" link type="primary" @click="openRunRecords(row)">运行记录</el-button>
            <!-- 破坏性 / 低频动作收进 ...，避免与常用按钮并排误触 -->
            <el-dropdown v-if="canEdit" trigger="click" @command="onRowCommand($event, row)">
              <el-button link type="primary" class="btn-more" aria-label="更多操作" :disabled="removingId === row.id">
                <el-icon><MoreFilled /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="copy">复制</el-dropdown-item>
                  <el-dropdown-item command="remove" divided>
                    <!-- 下拉菜单被 teleport 到 body，scoped 样式不生效，故用行内色值 -->
                    <span style="color: var(--el-color-danger)">删除</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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

    <!-- 复制：先读源草稿、再建新定义并回写图，避免复制出空骨架 -->
    <el-dialog v-model="copyOpen" title="复制工作流" width="520px" :close-on-click-modal="false">
      <el-form ref="copyFormRef" :model="copyForm" :rules="createRules" label-width="88px">
        <el-form-item label="源工作流">
          <el-input :model-value="copySource?.name || ''" disabled />
        </el-form-item>
        <el-form-item label="编码" prop="code">
          <el-input v-model="copyForm.code" maxlength="64" placeholder="新编码，创建后不可改" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="copyForm.name" maxlength="128" placeholder="副本名称" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="copyForm.description" type="textarea" :rows="3" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="copying" @click="copyOpen = false">取消</el-button>
        <el-button type="primary" :loading="copying" @click="submitCopy">复制并编辑</el-button>
      </template>
    </el-dialog>

    <!-- 应用触发说明：不在控制台代调，只给接口契约与授权入口指路 -->
    <el-dialog v-model="invokeOpen" title="接入配置（OpenAPI）" width="640px">
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

    <!-- 试跑抽屉：复用编辑页同一个组件，列表页不再自造运行面板 -->
    <WorkflowTrialDrawer
      v-if="trialTarget"
      v-model="trialOpen"
      :workflow-id="trialTarget.id"
      :default-release-id="trialTarget.defaultReleaseId ?? null"
      :can-act="canHumanAct"
    />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'WorkflowList' })
/**
 * 工作流列表页。所属域：workflow。
 * <p>职责：分页检索、新建（后端自动建最小草稿）、复制、删除、进入画布编辑、
 * 查看历史发布版本、发起试跑、跳转调用追踪查看运行记录。
 * <p>边界：本页不自建执行引擎；试跑复用编辑页抽屉，运行详情仍在后端 run 表。
 */
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, MoreFilled, Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import WorkflowTrialDrawer from '@/views/workflow/editor/WorkflowTrialDrawer.vue'
import {
  createWorkflowDef,
  getWorkflowDraft,
  listWorkflowReleases,
  pageWorkflowDefs,
  removeWorkflowDef,
  saveWorkflowDraft
} from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import type { WorkflowDefRow, WorkflowReleaseRow } from '@/types/workflow'

const user = useUserStore()
const router = useRouter()

/** 按钮权限与后端注解同码；模板与脚本共用，避免两处判断漂移 */
const canEdit = computed(() => user.hasPerm('workflow:def:edit'))
const canRunTrial = computed(() => user.hasPerm('workflow:run:trial'))
const canHumanAct = computed(() => user.hasPerm('workflow:human:act'))
/** 页头「更多」只要有一个入口可用就显示，避免空下拉占位 */
const showMoreMenu = computed(
  () => user.hasPerm('workflow:run:invoke') || user.hasPerm('system:app:list')
)

/** 状态下拉：生命周期，不是启停开关；草稿=尚未发布过 */
const STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT_ONLY' },
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
/** 工作流表最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)

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

/** 复制弹窗：编码与名称由用户确认，避免自动生成重复编码 */
const copyOpen = ref(false)
const copying = ref(false)
const copyFormRef = ref<FormInstance | null>(null)
const copySource = ref<WorkflowDefRow | null>(null)
const copyForm = reactive({ code: '', name: '', description: '' })
/** 正在删除的行 id：只禁用当前行下拉，不阻塞整表 */
const removingId = ref<number | null>(null)

/** 试跑抽屉目标；为空时不渲染抽屉，防止旧 target 串行 */
const trialOpen = ref(false)
const trialTarget = ref<WorkflowDefRow | null>(null)

const releaseOpen = ref(false)
const releaseLoading = ref(false)
const releaseError = ref('')
const releaseTarget = ref<WorkflowDefRow | null>(null)
const releases = ref<WorkflowReleaseRow[]>([])

/** 生命周期 → 中文；停用为后端预留态，保留兜底文案 */
function statusLabel(status: string): string {
  if (status === 'HAS_RELEASE') {
    return '已发布'
  }
  if (status === 'DISABLED' || status === 'STOPPED') {
    return '停用'
  }
  return '草稿'
}

/** 状态 → el-tag 语义色：已发布绿、草稿灰、停用红 */
function statusTagType(status: string): 'success' | 'info' | 'danger' {
  if (status === 'HAS_RELEASE') {
    return 'success'
  }
  if (status === 'DISABLED' || status === 'STOPPED') {
    return 'danger'
  }
  return 'info'
}

/** 列表时间统一到分钟，避免毫秒/T 分隔符把列宽撑开 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
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

/** 打开新建弹窗；每次重置表单，避免上一次输入残留 */
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

/**
 * 打开试跑抽屉。仅传已发布定义；没有版本时由抽屉自身提示先发布。
 *
 * @param row 当前定义行
 */
function openTrial(row: WorkflowDefRow) {
  trialTarget.value = row
  trialOpen.value = true
}

/**
 * 跳到调用追踪页，并把入口类型与工作流 id 作为查询条件带上。
 * <p>type=workflow 让追踪页直接进「工作流」类型；workflowId 供后端反查该定义的 run。
 *
 * @param row 当前定义行
 */
function openRunRecords(row: WorkflowDefRow) {
  void router.push({
    path: '/ops/traces',
    query: { type: 'workflow', workflowId: String(row.id), workflowName: row.name }
  })
}

/**
 * 打开复制弹窗，预填「编码_copy / 名称 副本」；编码仍允许手改。
 *
 * @param row 被复制的源定义
 */
function openCopy(row: WorkflowDefRow) {
  copySource.value = row
  const suffix = '_copy'
  const maxBase = 64 - suffix.length
  copyForm.code = `${row.code.slice(0, maxBase)}${suffix}`
  copyForm.name = `${row.name} 副本`.slice(0, 128)
  copyForm.description = row.description || ''
  copyOpen.value = true
}

/**
 * 复制工作流：先读源草稿，再建新定义并回写同一份 graph。
 * <p>只克隆草稿，不带发布版本；新定义由用户确认后再发布，避免误把旧版本带进新流程。
 */
async function submitCopy() {
  const form = copyFormRef.value
  if (!form || !copySource.value) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }
  copying.value = true
  try {
    // 先读源图：源草稿损坏时直接失败，不产生空副本
    const draftResp = await getWorkflowDraft(copySource.value.id)
    const graph = draftResp.data.data?.graph
    if (!graph) {
      ElMessage.error('源工作流草稿为空，无法复制')
      return
    }
    const { data } = await createWorkflowDef({
      code: copyForm.code.trim(),
      name: copyForm.name.trim(),
      description: copyForm.description.trim() || undefined
    })
    const newId = data.data?.id
    if (!newId) {
      ElMessage.error('复制失败：未返回新工作流 ID')
      return
    }
    try {
      await saveWorkflowDraft(newId, graph)
    } catch (error) {
      // 回写失败时清理刚建的空定义，避免列表里留下半成品
      await removeWorkflowDef(newId).catch(() => undefined)
      throw error
    }
    copyOpen.value = false
    ElMessage.success('已复制，正在打开新工作流')
    void router.push(`/orchestration/workflow/${newId}`)
  } catch {
    // 接口错误由 request 拦截器统一提示，这里只负责收口 loading
  } finally {
    copying.value = false
  }
}

/**
 * 行内「...」菜单分发：复制 / 删除。
 *
 * @param command 下拉命令
 * @param row     当前定义行
 */
function onRowCommand(command: string, row: WorkflowDefRow) {
  if (command === 'copy') {
    openCopy(row)
  } else if (command === 'remove') {
    void removeRow(row)
  }
}

/**
 * 删除工作流定义（逻辑删除，历史版本与运行记录保留）。
 * <p>删除后应用触发立即失效；当前页删空时回退一页，避免停在空页。
 *
 * @param row 当前定义行
 */
async function removeRow(row: WorkflowDefRow) {
  try {
    await ElMessageBox.confirm(
      `删除工作流「${row.name}」？删除后应用触发立即失效，历史运行记录与发布版本仍保留。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    // 用户取消：不提示，保持页面原状
    return
  }
  removingId.value = row.id
  try {
    await removeWorkflowDef(row.id)
    ElMessage.success('已删除')
    if (rows.value.length === 1 && query.current > 1) {
      query.current -= 1
    }
    void loadRows()
  } catch {
    // 删除失败由 request 拦截器提示；保留当前页与行，方便重试
  } finally {
    removingId.value = null
  }
}

/**
 * 页头「更多」分发：接入配置 / 应用授权。
 *
 * @param command 下拉命令
 */
function onHeaderCommand(command: string) {
  if (command === 'access') {
    invokeOpen.value = true
  } else if (command === 'app') {
    goAppManage()
  }
}

/** 跳到应用管理页做工作流授权；保持弹窗关闭，避免回退时残留 */
function goAppManage() {
  invokeOpen.value = false
  void router.push('/system/app')
}

onMounted(loadRows)

/**
 * KeepAlive 页签切回时重拉列表。
 * <p>典型场景：编辑页发布 / 删除后返回列表，组件没有重新挂载，
 * 只靠 onMounted 会一直显示发布前的状态与版本号。
 * <p>首次激活紧随 onMounted 触发，跳过以免重复请求。
 */
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  void loadRows()
})
</script>

<style scoped>
.block-alert {
  margin-bottom: 12px;
}

.cell-name {
  overflow: hidden;
  color: var(--ah-text-primary, #111827);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 副行简介：浅灰小字，单行省略，保持行高稳定 */
.cell-sub {
  overflow: hidden;
  margin-top: 2px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 操作列 link 按钮间距收紧，五个动作仍能在一行内排开 */
.wf-table .el-button + .el-button {
  margin-left: 8px;
}

.btn-more {
  margin-left: 8px;
}

.muted {
  color: var(--ah-text-muted, #9ca3af);
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
