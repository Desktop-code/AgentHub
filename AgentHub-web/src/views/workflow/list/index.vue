<template>
  <div class="page">
    <!-- 页头只保留标题与说明；接入配置语义较弱，收进「更多」下拉避免误点 -->
    <PageHeader title="工作流" description="可视化编排 DAG：建图、存草稿、发布不可变版本；调试与人工待办在编辑页与「编排 → 人工待办」处理。">
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

    <!-- 失败但已有旧数据：保留列表，顶部出错误条 + 重试；无数据时表内改用错误块 -->
    <el-alert
      v-if="listError && rows.length"
      :title="listError"
      type="error"
      show-icon
      :closable="false"
      class="block-alert"
    >
      <div class="block-alert__body">
        <span>{{ listErrorReason }}</span>
        <el-button link type="danger" size="small" @click="loadRows">重试</el-button>
      </div>
    </el-alert>

    <section class="panel">
      <el-table v-loading="loading" :data="rows" class="wf-table">
        <!-- 空态按原因分开：加载失败 / 筛选无结果 / 首次没有；失败时不再出现「还没有工作流」 -->
        <template #empty>
          <LoadError
            v-if="listError"
            title="工作流列表加载失败"
            :reason="listErrorReason"
            :retrying="loading"
            @retry="loadRows"
          />
          <div v-else class="ah-empty-inline">
            <span>{{ rowsFiltered ? '没有符合条件的结果' : '还没有工作流' }}</span>
            <el-button v-if="rowsFiltered" link type="primary" @click="resetFilter">清除筛选</el-button>
          </div>
        </template>
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
            <!-- 版本号只展示当前发布版本；切换入口在画布顶栏，列表不再提供 -->
            <span v-if="row.defaultVersion">v{{ row.defaultVersion }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditor(row)">
              {{ canEdit ? '编辑' : '查看' }}
            </el-button>
            <!-- 复制 / 删除改为并排常驻：不再收进「...」下拉，避免多一次点击 -->
            <template v-if="canEdit">
              <el-button link type="primary" @click="openCopy(row)">复制</el-button>
              <el-button
                link
                type="danger"
                :loading="removingId === row.id"
                @click="removeRow(row)"
              >
                删除
              </el-button>
            </template>
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

    <!-- 调试 / 版本切换 / 运行历史入口统一收进画布定义页，列表不再自留一份 -->
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'WorkflowList' })
/**
 * 工作流列表页。所属域：workflow。
 * <p>职责：分页检索、新建（后端自动建最小草稿）、复制、删除、进入画布编辑。
 * <p>边界：调试、版本切换、运行历史入口都在画布定义页；本页只保留列表与定义管理，
 * 不自建执行引擎，也不代调运行接口。
 */
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import LoadError from '@/components/common/LoadError.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { requestErrorText } from '@/utils/requestError'
import {
  createWorkflowDef,
  getWorkflowDraft,
  pageWorkflowDefs,
  removeWorkflowDef,
  saveWorkflowDraft
} from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import type { WorkflowDefRow } from '@/types/workflow'

const user = useUserStore()
const router = useRouter()

/** 按钮权限与后端注解同码；模板与脚本共用，避免两处判断漂移 */
const canEdit = computed(() => user.hasPerm('workflow:def:edit'))
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
/** 列表失败的可读原因（表内错误块第二行） */
const listErrorReason = ref('')
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

/** 列表是否带筛选条件：决定空态写「还没有工作流」还是「没有符合条件的结果」 */
const rowsFiltered = computed(() => !!(query.keyword.trim() || query.status))

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
/** 正在删除的行 id：只让当前行的删除按钮转圈，不阻塞整表 */
const removingId = ref<number | null>(null)

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
  listErrorReason.value = ''
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
  } catch (e) {
    listError.value = '工作流列表加载失败'
    listErrorReason.value = requestErrorText(e)
    // 保留旧 rows / total：失败时不能把已有列表清成空白再配一句「没有数据」
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

/* 顶部 / 抽屉错误条正文：原因 + 重试同排 */
.block-alert__body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.cell-name {
  overflow: hidden;
  color: var(--ah-text-primary, #1f2937);
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

/* 操作列：编辑 / 复制 / 删除并排，按钮间距保持紧凑 */
.wf-table .el-button + .el-button {
  margin-left: 8px;
}

.muted {
  color: var(--ah-text-muted, #9ca3af);
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
  border: 1px solid var(--ah-border, #ebeef5);
  border-radius: 8px;
  background: var(--ah-surface-muted);
  color: var(--ah-text);
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
