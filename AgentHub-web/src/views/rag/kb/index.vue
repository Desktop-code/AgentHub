<template>
  <div class="page">
    <!-- 页头：按全局规范用 PageHeader（浅灰底无卡片），新建入口只在 #extra -->
    <PageHeader title="知识库" description="创建知识库即初始化向量集合；供后续 Agent 绑定检索使用。">
      <template #extra>
        <el-button v-if="user.hasPerm('rag:kb:add')" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建知识库
        </el-button>
      </template>
    </PageHeader>

    <!-- 筛选条：名称 / 状态 / 可见性；与全站其它列表页一致的查询 + 重置 -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <el-form-item label="名称">
          <el-input v-model="query.keyword" class="filter-input" clearable placeholder="名称或描述" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="filter-select" clearable placeholder="全部">
            <el-option label="就绪" value="READY" />
            <el-option label="初始化失败" value="INIT_FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="query.visibility" class="filter-select" clearable placeholder="全部">
            <el-option label="公开" value="PUBLIC" />
            <el-option label="私有" value="PRIVATE" />
          </el-select>
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

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" empty-text="还没有知识库">
        <!-- 最左序号列：跨页连续，纯展示，不参与接口排序 -->
        <el-table-column v-bind="indexColumnProps" />
        <el-table-column label="名称" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 名称本身就是进入该库文档管理页的入口，比只靠「操作」列更符合直觉 -->
            <el-button
              v-if="user.hasPerm('rag:doc:list')"
              link
              type="primary"
              class="name-link"
              @click="openDocs(row)"
            >
              {{ row.name }}
            </el-button>
            <span v-else>{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :class="statusClass(row.status)">
              {{ KB_STATUS_LABEL[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="检索能力" min-width="120">
          <template #default="{ row }">
            <!-- 能力为 null 表示集合没建成功，不显示「仅向量」以免误导 -->
            <span v-if="row.storeCapability">{{ KB_CAPABILITY_LABEL[row.storeCapability] || row.storeCapability }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="Embedding" min-width="180">
          <template #default="{ row }">
            <div class="cell-stack">
              <span :title="row.embeddingModelName || ''">{{ row.embeddingModelName || `#${row.embeddingModelId}` }}</span>
              <span class="muted">维度 {{ row.vectorDimension }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="可见性" min-width="100">
          <template #default="{ row }">{{ KB_VISIBILITY_LABEL[row.visibility] || row.visibility }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ row.createTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="290" align="center">
          <template #default="{ row }">
            <el-button
              v-if="user.hasPerm('rag:doc:list')"
              link
              type="primary"
              @click="openDocs(row)"
            >
              文档
            </el-button>
            <el-button v-if="user.hasPerm('rag:kb:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-if="user.hasPerm('rag:kb:rebuild')"
              link
              type="primary"
              :loading="rebuildingId === row.id"
              @click="onRebuild(row)"
            >
              重建集合
            </el-button>
            <el-button v-if="user.hasPerm('rag:kb:remove')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePager v-model:current="query.current" v-model:size="query.size" :total="total" @change="load" />
    </el-card>

    <!-- 新建：Embedding 与维度在此确定，建库后不可改 -->
    <el-dialog v-model="create.open" title="新建知识库" width="560px" :close-on-click-modal="false">
      <el-form label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="create.form.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="create.form.description" type="textarea" :rows="2" maxlength="512" show-word-limit />
        </el-form-item>
        <el-form-item label="Embedding" required>
          <el-select
            v-model="create.form.embeddingModelId"
            class="full"
            placeholder="选择用途为 EMBEDDING 的模型实例"
            filterable
            @change="onEmbeddingChange"
          >
            <el-option
              v-for="item in embeddingOptions"
              :key="item.id"
              :label="`${item.displayName}（${item.modelId}）`"
              :value="item.id"
            />
          </el-select>
          <div class="hint">维度取自模型实例的扩展 JSON（dimension），缺失时用下方手填值。</div>
        </el-form-item>
        <el-form-item label="维度">
          <el-input-number v-model="create.form.vectorDimension" :min="1" :max="65536" controls-position="right" />
          <span class="hint inline">建库后锁定，不可修改</span>
        </el-form-item>
        <el-form-item label="Rerank">
          <el-select v-model="create.form.rerankModelId" class="full" clearable placeholder="可选，用途为 RERANK 的实例">
            <el-option
              v-for="item in rerankOptions"
              :key="item.id"
              :label="`${item.displayName}（${item.modelId}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="隔离模式">
          <el-radio-group v-model="create.form.isolationMode">
            <el-radio value="PHYSICAL">物理隔离</el-radio>
            <el-radio value="PUBLIC">公开池</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="可见性">
          <el-radio-group v-model="create.form.visibility">
            <el-radio value="PRIVATE">私有</el-radio>
            <el-radio value="PUBLIC">公开</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检索参数">
          <el-input v-model="create.form.retrievalConfig" type="textarea" :rows="3" class="mono" />
          <div class="hint">JSON；留空用平台默认（topK=5、向量/BM25 各 20、RRF k=60、改写与 Rerank 关）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.open = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" @click="saveCreate">创建并初始化集合</el-button>
      </template>
    </el-dialog>

    <!-- 编辑：不含 Embedding / 维度 / 隔离模式 -->
    <el-dialog v-model="edit.open" title="编辑知识库" width="560px" :close-on-click-modal="false">
      <el-form label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="edit.form.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="edit.form.description" type="textarea" :rows="2" maxlength="512" show-word-limit />
        </el-form-item>
        <el-form-item label="Embedding">
          <!-- 已锁定：换模型必须新建库，这里只展示 -->
          <span>{{ edit.embeddingLabel }}</span>
        </el-form-item>
        <el-form-item label="Rerank">
          <el-select v-model="edit.form.rerankModelId" class="full" clearable placeholder="不启用">
            <el-option
              v-for="item in rerankOptions"
              :key="item.id"
              :label="`${item.displayName}（${item.modelId}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="可见性">
          <el-radio-group v-model="edit.form.visibility">
            <el-radio value="PRIVATE">私有</el-radio>
            <el-radio value="PUBLIC">公开</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检索参数">
          <el-input v-model="edit.form.retrievalConfig" type="textarea" :rows="3" class="mono" />
        </el-form-item>
        <el-form-item label="入库参数">
          <el-input v-model="edit.form.ingestConfig" type="textarea" :rows="3" class="mono" />
          <div class="hint">JSON；② 期入库使用（chunkSize / chunkOverlap / minChunkSize）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 未改任何字段时取消 / 保存都不可点：打开即回填基线 -->
        <el-button :disabled="!editDirty" @click="edit.open = false">取消</el-button>
        <el-button type="primary" :loading="edit.saving" :disabled="!editDirty" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 文档管理已改为二级路由页 /rag/kb/:id/docs，本页不再挂抽屉 -->
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'RagKbManage' })
/**
 * 知识库管理页。所属域：rag。
 * 一期只做建库 + 初始化 + CRUD：建库时同步建 Collection，失败落 INIT_FAILED 并给出原因，
 * 管理员可在列表直接「重建集合」，不必重填表单。
 * 维度与 Embedding 建库即锁定，编辑弹窗不提供修改入口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createKb, deleteKb, pageKbs, rebuildKb, updateKb } from '@/api/rag'
import { pageInstances } from '@/api/model'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import type { InstanceRow } from '@/types/model'
import {
  KB_CAPABILITY_LABEL,
  KB_STATUS_LABEL,
  KB_VISIBILITY_LABEL,
  type RagKbRow
} from '@/types/rag'

/** 默认检索参数，与后端 RagKbChecks 保持一致 */
const DEFAULT_RETRIEVAL_CONFIG =
  '{"topK":5,"vectorTopK":20,"bm25TopK":20,"rrfK":60,"rrfTopN":20,"scoreThreshold":0.5,"rewriteEnabled":false,"rerankEnabled":false}'
/** 默认入库参数，② 期使用 */
const DEFAULT_INGEST_CONFIG = '{"chunkSize":450,"chunkOverlap":100,"minChunkSize":150}'

const user = useUserStore()
const router = useRouter()
const loading = ref(false)
const listError = ref('')
const rows = ref<RagKbRow[]>([])
const total = ref(0)
const rebuildingId = ref(0)
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  status: undefined as string | undefined,
  visibility: undefined as string | undefined
})
/** 知识库表最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)

/** Embedding / Rerank 候选只在打开弹窗时拉一次，模型中心变更后重新打开即可刷新 */
const embeddingOptions = ref<InstanceRow[]>([])
const rerankOptions = ref<InstanceRow[]>([])
const optionsLoaded = ref(false)

const create = reactive({
  open: false,
  saving: false,
  form: {
    name: '',
    description: '',
    embeddingModelId: undefined as number | undefined,
    vectorDimension: 768,
    rerankModelId: undefined as number | undefined,
    isolationMode: 'PHYSICAL',
    visibility: 'PRIVATE',
    retrievalConfig: DEFAULT_RETRIEVAL_CONFIG
  }
})

const edit = reactive({
  open: false,
  saving: false,
  id: 0,
  embeddingLabel: '',
  form: {
    name: '',
    description: '',
    visibility: 'PRIVATE',
    rerankModelId: undefined as number | undefined,
    retrievalConfig: DEFAULT_RETRIEVAL_CONFIG,
    ingestConfig: DEFAULT_INGEST_CONFIG
  }
})

/**
 * 编辑弹窗的脏检查：只比表单里填写 / 选择类字段。
 * Embedding 已锁定（只展示不改），id / embeddingLabel 是纯展示值，都不纳入。
 */
const editDirtyTracker = useObjectDirty(() => edit.form)
const editDirty = editDirtyTracker.isDirty

/**
 * 打开某知识库的文档管理二级页。
 *
 * @param row 当前知识库行
 */
function openDocs(row: RagKbRow) {
  void router.push(`/rag/kb/${row.id}/docs`)
}

/** 状态标签配色：就绪绿、失败红、其它灰 */
function statusClass(status: string) {
  if (status === 'READY') {
    return 'tag-on'
  }
  if (status === 'INIT_FAILED') {
    return 'tag-error'
  }
  return 'tag-off'
}

const hasOptions = computed(() => embeddingOptions.value.length > 0)

async function load() {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await pageKbs({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status,
      visibility: query.visibility
    })
    rows.value = data.data.records || []
    total.value = data.data.total || 0
  } catch {
    listError.value = '知识库列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.current = 1
  void load()
}

function onReset() {
  query.keyword = ''
  query.status = undefined
  query.visibility = undefined
  onSearch()
}

/**
 * 拉取可选模型实例。EMBEDDING 与 RERANK 分两次请求，只取启用的前 100 条，
 * 控制台量级下足够；超量再改成分页下拉。
 */
async function loadModelOptions() {
  if (optionsLoaded.value) {
    return
  }
  try {
    const [embedding, rerank] = await Promise.all([
      pageInstances({ current: 1, size: 100, capability: 'EMBEDDING' }),
      pageInstances({ current: 1, size: 100, capability: 'RERANK' })
    ])
    embeddingOptions.value = (embedding.data.data.records || []).filter((item) => item.status === 1)
    rerankOptions.value = (rerank.data.data.records || []).filter((item) => item.status === 1)
    optionsLoaded.value = true
  } catch {
    ElMessage.warning('模型实例列表加载失败，请先在「模型管理」确认有 EMBEDDING 实例')
  }
}

/**
 * 选中 Embedding 后尝试从 extraJson 带出维度，省得管理员手填。
 * 解析失败不提示：后端会以模型元数据为准，手填值只是兜底。
 */
function onEmbeddingChange(id: number) {
  const target = embeddingOptions.value.find((item) => item.id === id)
  const dimension = parseDimension(target?.extraJson)
  if (dimension) {
    create.form.vectorDimension = dimension
  }
}

/** 从模型实例 extraJson 读取维度，兼容三个键名，与后端 RagModelLookup 一致 */
function parseDimension(extraJson?: string | null) {
  if (!extraJson?.trim()) {
    return undefined
  }
  try {
    const obj = JSON.parse(extraJson) as Record<string, unknown>
    for (const key of ['dimension', 'embeddingDimension', 'vectorDimension']) {
      const value = obj[key]
      if (typeof value === 'number' && value > 0) {
        return value
      }
    }
  } catch {
    // 非法 JSON 交给后端判定，这里保持手填值
  }
  return undefined
}

async function openCreate() {
  await loadModelOptions()
  create.form = {
    name: '',
    description: '',
    embeddingModelId: undefined,
    vectorDimension: 768,
    rerankModelId: undefined,
    isolationMode: 'PHYSICAL',
    visibility: 'PRIVATE',
    retrievalConfig: DEFAULT_RETRIEVAL_CONFIG
  }
  create.open = true
  if (!hasOptions.value) {
    ElMessage.warning('还没有可用的 EMBEDDING 模型实例，请先到「模型管理」创建')
  }
}

async function saveCreate() {
  if (!create.form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!create.form.embeddingModelId) {
    ElMessage.warning('请选择 Embedding 模型')
    return
  }
  create.saving = true
  try {
    const { data } = await createKb({
      name: create.form.name.trim(),
      description: create.form.description.trim() || undefined,
      storeType: 'LOCAL_MILVUS',
      embeddingModelId: create.form.embeddingModelId,
      vectorDimension: create.form.vectorDimension,
      rerankModelId: create.form.rerankModelId,
      isolationMode: create.form.isolationMode,
      visibility: create.form.visibility,
      retrievalConfig: create.form.retrievalConfig.trim() || undefined
    })
    create.open = false
    const result = data.data
    // 建集失败不是接口错误：明确提示原因，引导管理员点「重建集合」
    if (result?.status === 'INIT_FAILED') {
      ElMessage.warning(`知识库已创建，但集合初始化失败：${result.errorMsg || '未知原因'}。可点「重建集合」重试。`)
    } else {
      ElMessage.success('知识库已创建，集合初始化完成')
    }
    query.current = 1
    await load()
  } finally {
    create.saving = false
  }
}

async function openEdit(row: RagKbRow) {
  await loadModelOptions()
  edit.id = row.id
  edit.embeddingLabel = `${row.embeddingModelName || `#${row.embeddingModelId}`}（维度 ${row.vectorDimension}，建库后锁定）`
  edit.form = {
    name: row.name,
    description: row.description || '',
    visibility: row.visibility,
    rerankModelId: row.rerankModelId ?? undefined,
    retrievalConfig: row.retrievalConfig || DEFAULT_RETRIEVAL_CONFIG,
    ingestConfig: row.ingestConfig || DEFAULT_INGEST_CONFIG
  }
  edit.open = true
  // 回填完成即定为干净基线：打开弹窗不该显示可保存
  editDirtyTracker.markClean()
}

async function saveEdit() {
  if (!edit.form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  edit.saving = true
  try {
    // 清空 Rerank 用 0 表示，与后端约定一致
    await updateKb(edit.id, {
      name: edit.form.name.trim(),
      description: edit.form.description.trim() || undefined,
      visibility: edit.form.visibility,
      rerankModelId: edit.form.rerankModelId ?? 0,
      retrievalConfig: edit.form.retrievalConfig.trim() || undefined,
      ingestConfig: edit.form.ingestConfig.trim() || undefined
    })
    edit.open = false
    ElMessage.success('已保存')
    await load()
  } finally {
    edit.saving = false
  }
}

/**
 * 重建集合。会丢弃该库已有向量，二次确认后再执行。
 */
async function onRebuild(row: RagKbRow) {
  try {
    await ElMessageBox.confirm(
      `重建「${row.name}」的向量集合？已有切片向量会被清空（一期尚无入库数据，通常无影响）。`,
      '重建集合',
      { confirmButtonText: '重建', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  rebuildingId.value = row.id
  try {
    const { data } = await rebuildKb(row.id)
    const result = data.data
    if (result?.status === 'READY') {
      ElMessage.success(`重建成功，检索能力：${KB_CAPABILITY_LABEL[result.storeCapability || ''] || result.storeCapability}`)
    } else {
      ElMessage.error(`重建失败：${result?.errorMsg || '未知原因'}`)
    }
    await load()
  } finally {
    rebuildingId.value = 0
  }
}

/**
 * 删除知识库。后端会先删向量集合，删集失败则整体失败并提示重试。
 */
async function onDelete(row: RagKbRow) {
  try {
    await ElMessageBox.confirm(
      `删除知识库「${row.name}」？将同时删除其向量集合，操作不可恢复。`,
      '删除知识库',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteKb(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 后端已给出具体原因（如向量库未启动），这里不再叠一条通用提示
  }
}

onMounted(load)
</script>

<style scoped>
.full {
  width: 100%;
}

.block-alert {
  margin-bottom: 12px;
}

.cell-stack {
  display: flex;
  flex-direction: column;
  line-height: 1.5;
}

.muted {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.hint {
  margin-top: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.hint.inline {
  margin: 0 0 0 8px;
}

.mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.tag-on {
  --el-tag-text-color: #10b981;
  --el-tag-border-color: #a7f3d0;
  --el-tag-bg-color: #ecfdf5;
}

.tag-error {
  --el-tag-text-color: #ef4444;
  --el-tag-border-color: #fecaca;
  --el-tag-bg-color: #fef2f2;
}

.tag-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}
</style>
