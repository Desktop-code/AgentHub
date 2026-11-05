<template>
  <div class="page">
    <!--
      顶栏一体：面包屑 + Embedding/重建 + 弱化返回。
      二级页信息密度优先，不再叠一层 PageHeader，避免标题与面包屑重复留白。
    -->
    <div class="topline">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/access/kb' }">知识库</el-breadcrumb-item>
        <el-breadcrumb-item>
          <span class="kb-name">{{ kb?.name || '加载中' }}</span>
        </el-breadcrumb-item>
      </el-breadcrumb>

      <div class="topline-mid">
        <div class="embedding-meta">
          <span class="meta-label">Embedding</span>
          <span class="meta-value">{{ embeddingLabel }}</span>
          <span class="meta-dim">维度 {{ kb?.vectorDimension ?? '—' }}</span>
        </div>
        <el-button
          v-if="user.hasPerm('rag:kb:rebuild')"
          size="small"
          :loading="rebuilding"
          :disabled="!kbId"
          @click="onRebuild"
        >
          重建索引
        </el-button>
      </div>

      <el-button link type="primary" :icon="Back" @click="backToList">返回知识库列表</el-button>
    </div>

    <el-alert v-if="kbError" :title="kbError" type="error" show-icon :closable="false" class="block-alert" />

    <div class="split">
      <!-- 左栏：工具栏 + 文档表（上传并入工具栏，不再占高大拖拽区） -->
      <section class="pane pane-left">
        <el-card class="pane-card" shadow="never">
          <template #header>
            <div class="pane-head">
              <span class="pane-title">文档</span>
              <span class="pane-sub">同名文件将重灌；入库在后台异步执行</span>
            </div>
          </template>

          <div class="toolbar">
            <el-upload
              v-if="user.hasPerm('rag:doc:upload')"
              class="toolbar-upload"
              :show-file-list="false"
              :before-upload="onBeforeUpload"
              accept=".txt,.md,.pdf"
              :disabled="uploading"
            >
              <el-button type="primary" size="small" :icon="Upload" :loading="uploading">上传文档</el-button>
            </el-upload>
            <span class="toolbar-hint">上传文档支持 txt/md/pdf，单文件 ≤ 20MB</span>

            <div class="toolbar-spacer" />

            <el-input
              v-model="query.keyword"
              class="toolbar-search"
              clearable
              size="small"
              placeholder="按文档名搜索"
              @keyup.enter="onSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button size="small" class="btn-query" @click="onSearch">查询</el-button>
            <el-button size="small" class="btn-reset" @click="onReset">重置</el-button>
          </div>

          <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

          <!-- 表格单独滚动：分页钉在卡片底，左右两栏才能同高贴底 -->
          <div class="table-scroll">
            <el-table
              v-loading="loading"
              :data="rows"
              empty-text="还没有文档"
              size="small"
              :row-class-name="rowClass"
              @row-click="onRowClick"
            >
              <el-table-column v-bind="indexColumnProps" />
              <el-table-column prop="title" label="文档名" min-width="140" show-overflow-tooltip align="left" />
              <el-table-column label="大小" width="78" align="right">
                <template #default="{ row }">{{ formatByteSize(row.byteSize) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain" :class="statusClass(row.status)">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                  <div v-if="row.status === 'RUNNING'" class="muted">
                    {{ row.stage || '处理中' }} {{ row.progress ?? 0 }}%
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="切片数" width="64" align="center">
                <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="操作" width="132" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="canViewChunks"
                    link
                    type="primary"
                    @click.stop="openChunks(row)"
                  >
                    查看切片
                  </el-button>
                  <el-button
                    v-if="row.status === 'FAILED' && user.hasPerm('rag:doc:retry')"
                    link
                    type="primary"
                    :loading="retryingId === row.id"
                    @click.stop="onRetry(row)"
                  >
                    重试
                  </el-button>
                  <el-button
                    v-if="user.hasPerm('rag:doc:remove')"
                    link
                    type="danger"
                    :loading="removingId === row.id"
                    @click.stop="onRemove(row)"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <TablePager v-model:current="query.current" v-model:size="query.size" :total="total" small @change="load" />
        </el-card>
      </section>

      <!-- 右栏：选中文档摘要 + 切片卡片 -->
      <section class="pane pane-right">
        <el-card class="pane-card" shadow="never">
          <template #header>
            <div class="pane-head">
              <div class="pane-head-left">
                <template v-if="currentDoc">
                  <div class="doc-summary">
                    <span class="doc-summary-name" :title="currentDoc.title">{{ currentDoc.title }}</span>
                    <span class="doc-summary-sep">|</span>
                    <span>总切片数 {{ chunks.length }}</span>
                    <span class="doc-summary-sep">|</span>
                    <span>总 Tokens {{ totalTokens }}</span>
                  </div>
                </template>
                <template v-else>
                  <span class="pane-title">切片预览</span>
                  <span class="pane-sub">请在左侧选择一个文档以查看切片</span>
                </template>
              </div>
              <el-button
                v-if="currentDoc && user.hasPerm('rag:doc:rechunk')"
                type="primary"
                plain
                size="small"
                :loading="rechunk.saving"
                :disabled="busyCurrent"
                @click="openRechunk"
              >
                <el-icon class="btn-icon"><RefreshRight /></el-icon>
                重新切片
              </el-button>
            </div>
          </template>

          <el-alert v-if="chunkError" :title="chunkError" type="error" show-icon :closable="false" class="block-alert" />

          <!-- 未选文档：浅灰图标空态 -->
          <div v-if="!currentDoc" class="chunk-empty">
            <el-icon class="chunk-empty-icon"><Document /></el-icon>
            <p class="chunk-empty-text">请在左侧选择一个文档以查看切片</p>
          </div>

          <div v-else-if="!chunks.length" class="chunk-empty" v-loading="chunkLoading">
            <el-icon class="chunk-empty-icon"><Document /></el-icon>
            <p class="chunk-empty-text">{{ emptyChunkText }}</p>
            <el-button
              v-if="currentDoc.status === 'FAILED' && user.hasPerm('rag:doc:retry')"
              link
              type="primary"
              @click="onRetry(currentDoc)"
            >
              查看失败原因后重试
            </el-button>
          </div>

          <div v-else v-loading="chunkLoading" class="chunk-list">
            <article v-for="chunk in chunks" :key="chunk.id" class="chunk-card">
              <header class="chunk-head">
                <div class="chunk-head-left">
                  <span class="chunk-title">切片 #{{ (chunk.chunkIndex ?? 0) + 1 }}</span>
                  <span class="chunk-token">Token: {{ chunk.tokenEst ?? 0 }}</span>
                </div>
                <!-- 编辑入口预留：当前无单条切片写接口，点击仅提示后续能力 -->
                <el-button link type="primary" size="small" @click="onEditChunk(chunk)">编辑</el-button>
              </header>
              <pre class="chunk-body">{{ chunk.content }}</pre>
            </article>
          </div>
        </el-card>
      </section>
    </div>

    <el-dialog v-model="rechunk.open" title="重新切片" width="460px" :close-on-click-modal="false">
      <el-form label-width="88px">
        <el-form-item label="切片长度" required>
          <el-input-number v-model="rechunk.chunkSize" :min="100" :max="4000" :step="50" controls-position="right" />
          <div class="hint">字符数；越小切片越细，越大单条信息越完整但易被模型截断</div>
        </el-form-item>
        <el-form-item label="重叠">
          <el-input-number v-model="rechunk.chunkOverlap" :min="0" :max="2000" :step="10" controls-position="right" />
          <div class="hint">不填则沿用知识库入库参数</div>
        </el-form-item>
      </el-form>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="重切会先清掉该文档现有切片与向量，再按新片长重新入库；期间该文档不可检索。"
      />
      <template #footer>
        <el-button @click="rechunk.open = false">取消</el-button>
        <el-button type="primary" :loading="rechunk.saving" @click="submitRechunk">开始重切</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 知识库下的文档管理二级页。所属域：rag（前端 views）。
 * 路由：/rag/kb/:id/docs，从知识库列表的「文档」入口或知识库名称进入。
 *
 * 职责：
 * 1. 顶栏展示库名 / Embedding / 重建索引，并弱化返回入口；
 * 2. 左栏工具栏上传与检索，表格展示文档大小、状态、切片数；
 * 3. 右栏查看选中文档切片摘要与卡片列表；「编辑」按钮为后续人工微调预留。
 *
 * 约束：
 * - 不在此页改 Embedding / 建库参数；重建索引复用知识库 rebuild 接口；
 * - 单条切片写接口尚未开放，编辑仅提示；调整内容靠整篇重切；
 * - 页面按 :id 参数复用同一组件，切库时靠 watch 重新拉数据而不重挂。
 */
import { computed, onActivated, onBeforeUnmount, onDeactivated, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Document, RefreshRight, Search, Upload } from '@element-plus/icons-vue'
import {
  deleteDocument,
  getKb,
  listDocumentChunks,
  pageDocuments,
  rebuildKb,
  rechunkDocument,
  retryDocument,
  uploadDocument
} from '@/api/rag'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { useUserStore } from '@/stores/user'
import { DOC_STATUS_LABEL, KB_CAPABILITY_LABEL, type RagChunkRow, type RagDocumentRow, type RagKbRow } from '@/types/rag'

/** KeepAlive 的 include 比对组件名，必须与 router meta.cacheName 一致 */
defineOptions({ name: 'RagKbDocs' })

/** 重切参数缺省值，与后端 RagIngestAppService 的默认入库参数一致 */
const DEFAULT_CHUNK_SIZE = 450
const DEFAULT_CHUNK_OVERLAP = 100

/** 本页状态文案：偏操作态，与列表页「可检索」略有区分 */
const DOC_STATUS_UI: Record<string, string> = {
  PENDING: '待解析',
  RUNNING: '解析中',
  READY: '就绪',
  FAILED: '失败'
}

const route = useRoute()
const router = useRouter()
const user = useUserStore()

/**
 * 是否处于「知识库文档管理」路由。
 * KeepAlive 失活后本页 watch 仍会跑；其它路由若也声明 :id（如 /agent/list/:id），
 * 若不先判路由就会把 agentId 当成 kbId 去打 GET /api/rag/kbs/{id}，
 * 全局拦截器 toast「知识库不存在」。对称于 AgentManage 的 onAgentRoute。
 *
 * @param path 当前 route.path
 */
function isKbDocsRoute(path: string): boolean {
  return /^\/rag\/kb\/[^/]+\/docs\/?$/.test(path)
}

/**
 * 知识库 id：仅在文档管理路由信任 params.id；失活串台时返回 0，避免误请求。
 * 非法值按 0 处理，页面内各请求会被跳过。
 */
const kbId = computed(() => {
  if (!isKbDocsRoute(route.path)) {
    return 0
  }
  const parsed = Number(route.params.id)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
})

const kb = ref<RagKbRow | null>(null)
const kbError = ref('')
const rebuilding = ref(false)

/* ---------------- 左栏：文档列表 ---------------- */
const loading = ref(false)
const uploading = ref(false)
const listError = ref('')
const rows = ref<RagDocumentRow[]>([])
const total = ref(0)
const retryingId = ref(0)
const removingId = ref(0)
const query = reactive({ current: 1, size: 10, keyword: '' })
/** 文档表最左「序号」列；左栏偏窄，列宽收到 56px，序号仍跨页连续 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size, 56)

/* ---------------- 右栏：切片 ---------------- */
const currentDoc = ref<RagDocumentRow | null>(null)
const chunks = ref<RagChunkRow[]>([])
const chunkLoading = ref(false)
const chunkError = ref('')

/** 重切弹窗状态；chunkSize 默认取知识库入库参数 */
const rechunk = reactive({
  open: false,
  saving: false,
  chunkSize: DEFAULT_CHUNK_SIZE,
  chunkOverlap: DEFAULT_CHUNK_OVERLAP
})

/** 列表轮询句柄：有文档处于 PENDING/RUNNING 时定时刷新 */
let pollTimer: number | undefined

/** Embedding 展示：模型名优先，模型已删时退回实例 id */
const embeddingLabel = computed(() => {
  if (!kb.value) {
    return '—'
  }
  return kb.value.embeddingModelName || `#${kb.value.embeddingModelId}`
})

/** 当前选中文档是否正在入库：入库中禁止重切，否则两次任务会并发写同一文档 */
const busyCurrent = computed(() => {
  const status = currentDoc.value?.status
  return status === 'PENDING' || status === 'RUNNING'
})

/** 选中文档全部切片的 Token 估算合计，供右栏摘要 */
const totalTokens = computed(() =>
  chunks.value.reduce((sum, chunk) => sum + (Number(chunk.tokenEst) || 0), 0)
)

/** 右侧空状态文案：按文档状态区分「还没跑完」与「确实没有切片」 */
const emptyChunkText = computed(() => {
  const status = currentDoc.value?.status
  if (status === 'PENDING' || status === 'RUNNING') {
    return '文档正在入库，切片生成后可刷新查看'
  }
  if (status === 'FAILED') {
    return `入库失败：${currentDoc.value?.errorMsg || '未记录原因'}`
  }
  return '该文档还没有切片，可尝试重新切片'
})

/** 状态配色：就绪绿、失败红、进行中橙、其它灰 */
function statusClass(status: string) {
  if (status === 'READY') {
    return 'tag-on'
  }
  if (status === 'FAILED') {
    return 'tag-error'
  }
  if (status === 'PENDING' || status === 'RUNNING') {
    return 'tag-running'
  }
  return 'tag-off'
}

/**
 * 状态中文：优先本页 UI 文案，未知码回退公共表或原值。
 *
 * @param status 后端状态码
 */
function statusLabel(status: string) {
  return DOC_STATUS_UI[status] || DOC_STATUS_LABEL[status] || status
}

/**
 * 字节数格式化为可读大小。
 *
 * @param bytes 文件字节数；空则显示 —
 */
function formatByteSize(bytes?: number | null) {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * 能否打开切片预览。
 * list 与 chunk 取 OR：与后端 GET chunks 一致；仅有 list 时点行也能预览，避免种子未灌时静默无响应。
 */
const canViewChunks = computed(
  () => user.hasPerm('rag:doc:list') || user.hasPerm('rag:doc:chunk')
)

/** 表格行高亮：让左栏的选中态与右栏内容对应 */
function rowClass({ row }: { row: RagDocumentRow }) {
  return currentDoc.value?.id === row.id ? 'is-current-doc' : ''
}

/**
 * 点击行即打开切片，减少必须点「查看切片」的步骤；操作列按钮自行 stop。
 *
 * @param row 文档行
 */
function onRowClick(row: RagDocumentRow) {
  if (!canViewChunks.value) {
    // 禁止静默 return：否则右侧一直停在「请选择文档」，排查时像「点选坏了」
    ElMessage.warning('当前账号无查看切片权限')
    return
  }
  void openChunks(row)
}

/** 读取知识库详情：只用于顶部展示，失败不影响文档操作；非文档路由直接跳过 */
async function loadKb() {
  // 失活串台时勿请求；再闸一道路由，防止 onActivated 等入口误打
  if (!isKbDocsRoute(route.path)) {
    return
  }
  if (!kbId.value) {
    kbError.value = '知识库参数无效'
    return
  }
  kbError.value = ''
  try {
    const { data } = await getKb(kbId.value)
    kb.value = data.data
    const fromConfig = parseChunkSize(data.data.ingestConfig)
    rechunk.chunkSize = fromConfig.size
    rechunk.chunkOverlap = fromConfig.overlap
  } catch {
    kb.value = null
    kbError.value = '知识库信息加载失败'
  }
}

/** 从 ingestConfig JSON 读片长与重叠，解析失败用默认值 */
function parseChunkSize(json?: string | null) {
  if (!json?.trim()) {
    return { size: DEFAULT_CHUNK_SIZE, overlap: DEFAULT_CHUNK_OVERLAP }
  }
  try {
    const obj = JSON.parse(json) as Record<string, unknown>
    const size = typeof obj.chunkSize === 'number' ? obj.chunkSize : DEFAULT_CHUNK_SIZE
    const overlap = typeof obj.chunkOverlap === 'number' ? obj.chunkOverlap : DEFAULT_CHUNK_OVERLAP
    return { size, overlap }
  } catch {
    return { size: DEFAULT_CHUNK_SIZE, overlap: DEFAULT_CHUNK_OVERLAP }
  }
}

async function load() {
  // 与 loadKb 同闸：非 /rag/kb/:id/docs 不拉文档列表
  if (!isKbDocsRoute(route.path) || !kbId.value) {
    return
  }
  loading.value = true
  listError.value = ''
  try {
    const { data } = await pageDocuments(kbId.value, {
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined
    })
    rows.value = data.data.records || []
    total.value = data.data.total || 0
    // 选中文档的进度以列表为准：重切后状态会从 PENDING 推进到 READY
    syncCurrentDoc()
  } catch {
    listError.value = '文档列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
    schedulePoll()
  }
}

/** 用最新列表刷新右栏选中文档的状态；文档已不存在（被删）则清空右栏 */
function syncCurrentDoc() {
  const selected = currentDoc.value
  if (!selected) {
    return
  }
  const fresh = rows.value.find((row) => row.id === selected.id)
  if (!fresh) {
    currentDoc.value = null
    chunks.value = []
    return
  }
  const wasRunning = selected.status === 'RUNNING' || selected.status === 'PENDING'
  currentDoc.value = fresh
  // 重切/入库刚跑完：右栏是空的，自动补一次切片，省掉用户再点一次「查看切片」
  if (wasRunning && fresh.status === 'READY') {
    void loadChunks()
  }
}

/** 有入库中的文档就 2 秒后刷新一次；入库是后台线程，前端只做被动观察 */
function schedulePoll() {
  stopPoll()
  const busy = rows.value.some((row) => row.status === 'RUNNING' || row.status === 'PENDING')
  if (!busy) {
    return
  }
  pollTimer = window.setTimeout(() => {
    void load()
  }, 2000)
}

function stopPoll() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

function onSearch() {
  query.current = 1
  void load()
}

function onReset() {
  query.keyword = ''
  onSearch()
}

/**
 * 拦截上传：前端先做格式与大小校验，减少无效请求；最终判定仍在服务端。
 *
 * @param file el-upload 选中的文件
 * @return 恒为 false —— 文件由本页自行提交，避免组件再发一次
 */
function onBeforeUpload(file: File) {
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!['txt', 'md', 'pdf'].includes(ext)) {
    ElMessage.warning('仅支持 txt / md / pdf')
    return false
  }
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('文件超过 20MB')
    return false
  }
  void doUpload(file)
  return false
}

async function doUpload(file: File) {
  uploading.value = true
  try {
    const { data } = await uploadDocument(kbId.value, file)
    ElMessage.success(data.data?.status === 'PENDING' ? '已受理，正在后台入库' : '已上传')
    query.current = 1
    await load()
  } finally {
    uploading.value = false
  }
}

/**
 * 重建当前知识库向量集合。会清空已有向量，需二次确认。
 */
async function onRebuild() {
  if (!kb.value || !kbId.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `重建「${kb.value.name}」的向量集合？已有切片向量会被清空，文档需重新入库后才能检索。`,
      '重建索引',
      { confirmButtonText: '重建', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  rebuilding.value = true
  try {
    const { data } = await rebuildKb(kbId.value)
    const result = data.data
    if (result?.status === 'READY') {
      ElMessage.success(
        `重建成功，检索能力：${KB_CAPABILITY_LABEL[result.storeCapability || ''] || result.storeCapability || '—'}`
      )
    } else {
      ElMessage.error(`重建失败：${result?.errorMsg || '未知原因'}`)
    }
    await loadKb()
    await load()
  } finally {
    rebuilding.value = false
  }
}

async function onRetry(row: RagDocumentRow) {
  retryingId.value = row.id
  try {
    await retryDocument(kbId.value, row.id)
    ElMessage.success('已重新排队')
    await load()
  } finally {
    retryingId.value = 0
  }
}

async function onRemove(row: RagDocumentRow) {
  try {
    await ElMessageBox.confirm(
      `删除文档「${row.title}」？其切片与向量会一并删除，操作不可恢复。`,
      '删除文档',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  removingId.value = row.id
  try {
    await deleteDocument(kbId.value, row.id)
    // 删掉的是当前选中文档时，右栏一并清空，避免展示已不存在的切片
    if (currentDoc.value?.id === row.id) {
      currentDoc.value = null
      chunks.value = []
    }
    ElMessage.success('已删除')
    await load()
  } finally {
    removingId.value = 0
  }
}

/**
 * 拉取某文档的切片并展示在右栏。
 *
 * @param row 文档行
 */
async function openChunks(row: RagDocumentRow) {
  currentDoc.value = row
  // 先清空上一份切片：否则加载期间右栏会短暂显示别的文档内容
  chunks.value = []
  chunkError.value = ''
  await loadChunks()
}

async function loadChunks() {
  const doc = currentDoc.value
  if (!doc) {
    return
  }
  chunkLoading.value = true
  chunkError.value = ''
  try {
    const { data } = await listDocumentChunks(kbId.value, doc.id)
    chunks.value = data.data || []
  } catch {
    chunkError.value = '切片加载失败'
    chunks.value = []
  } finally {
    chunkLoading.value = false
  }
}

/**
 * 单条切片编辑预留入口。写接口未交付前不发请求，避免误导为已可用。
 *
 * @param chunk 当前切片
 */
function onEditChunk(chunk: RagChunkRow) {
  ElMessage.info(`切片 #${(chunk.chunkIndex ?? 0) + 1} 的编辑能力即将上线，当前请用「重新切片」调整`)
}

/** 打开重切弹窗：片长以知识库当前入库参数为默认，用户可改 */
function openRechunk() {
  if (!currentDoc.value) {
    return
  }
  const fromConfig = parseChunkSize(kb.value?.ingestConfig)
  rechunk.chunkSize = fromConfig.size
  rechunk.chunkOverlap = fromConfig.overlap
  rechunk.open = true
}

async function submitRechunk() {
  const doc = currentDoc.value
  if (!doc) {
    return
  }
  rechunk.saving = true
  try {
    await rechunkDocument(kbId.value, doc.id, {
      chunkSize: rechunk.chunkSize,
      chunkOverlap: rechunk.chunkOverlap
    })
    rechunk.open = false
    // 旧切片已被服务端清空，这里立即清右栏，等文档回到 READY 再让用户点开查看
    chunks.value = []
    ElMessage.success('已受理，正在按新片长重新入库')
    await load()
  } finally {
    rechunk.saving = false
  }
}

/** 返回知识库列表 */
function backToList() {
  void router.push('/access/kb')
}

/**
 * 切库时重建整页数据：路由同一组件复用，不能依赖 mounted。
 * 必须先确认仍在文档管理路由再读 id（见 isKbDocsRoute），否则 KeepAlive
 * 失活后进 Agent 详情会把 agentId 当 kbId 清空缓存态并误请求。
 */
watch(
  () => [route.params.id, route.fullPath] as const,
  () => {
    if (!isKbDocsRoute(route.path)) {
      return
    }
    stopPoll()
    currentDoc.value = null
    chunks.value = []
    chunkError.value = ''
    query.current = 1
    query.keyword = ''
    void loadKb()
    void load()
  },
  { immediate: true }
)

/** 首次挂载已由 watch(immediate) 拉过数据，onActivated 首次触发要跳过，避免重复请求 */
let activatedOnce = false

/**
 * 从其它页签切回本页时刷新一次：上传或重切在别处完成、后台任务也可能已推进，
 * 只靠本页轮询会拿到旧状态（KeepAlive 缓存期间计时器是停的）。
 * 激活时再确认路由，避免异常激活路径误请求。
 */
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  if (!isKbDocsRoute(route.path)) {
    return
  }
  void loadKb()
  void load()
})

/** 离开页签时停轮询，避免缓存的页面在后台一直打接口 */
onDeactivated(stopPoll)

onBeforeUnmount(stopPoll)
</script>

<style scoped>
/*
 * 高度口径：视口 - 顶栏 56 - 页签 40 - main 上下 padding 48。
 * 页面本身不再撑出 .main 滚动条；左右卡片同高贴底，内容在卡内滚。
 */
.page {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  height: calc(100vh - 144px);
  min-height: 420px;
  overflow: hidden;
}

.topline {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, max-content);
  align-items: center;
  gap: 8px 20px;
  margin-bottom: 12px;
  min-height: 32px;
  flex: none;
}

.kb-name {
  font-weight: 700;
  color: var(--ah-text);
}

.topline-mid {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.embedding-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: var(--ah-text-secondary);
  white-space: nowrap;
}

.meta-label {
  color: var(--ah-text-muted);
}

.meta-value {
  font-weight: 600;
  color: var(--ah-text);
}

.meta-dim {
  padding: 2px 8px;
  border-radius: 6px;
  background: #f3f4f6;
  color: var(--ah-text-secondary);
}

.block-alert {
  margin-bottom: 12px;
  flex: none;
}

.split {
  display: flex;
  align-items: stretch;
  gap: 12px;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.pane-left {
  flex: 0 0 48%;
  max-width: 48%;
}

.pane-right {
  flex: 1 1 52%;
}

.pane-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.pane-card :deep(.el-card__header) {
  flex: none;
  padding: 12px 14px;
}

.pane-card :deep(.el-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 12px 14px 14px;
  overflow: hidden;
}

.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.pane-head-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.pane-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ah-text);
}

.pane-sub {
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 1.4;
}

.doc-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  min-width: 0;
  font-size: 13px;
  color: var(--ah-text-secondary);
  line-height: 1.4;
}

.doc-summary-name {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  color: var(--ah-text);
}

.doc-summary-sep {
  color: var(--ah-border);
}

.toolbar {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.table-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.pane-card :deep(.table-pager) {
  flex: none;
  margin-top: 8px;
}

.toolbar-upload {
  display: inline-flex;
}

.toolbar-hint {
  font-size: 12px;
  color: var(--ah-text-muted);
  line-height: 1.3;
}

.toolbar-spacer {
  flex: 1 1 8px;
  min-width: 8px;
}

.toolbar-search {
  width: 160px;
  max-width: 100%;
}

.toolbar .btn-query,
.toolbar .btn-reset {
  min-width: 56px;
}

:deep(.is-current-doc) {
  --el-table-tr-bg-color: #f3f4f6;
  cursor: pointer;
}

:deep(.el-table__row) {
  cursor: pointer;
}

.chunk-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 0;
  padding: 24px 12px;
  color: var(--ah-text-muted);
}

.chunk-empty-icon {
  font-size: 48px;
  color: #c4c9d2;
}

.chunk-empty-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
}

.chunk-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow: auto;
  padding-right: 2px;
}

.chunk-card {
  border: 1px solid #e8eaed;
  border-radius: 8px;
  padding: 10px 12px;
  background: #f8f9fa;
}

.chunk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.chunk-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.chunk-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ah-text);
}

.chunk-token {
  font-size: 12px;
  color: var(--ah-text-muted);
}

.chunk-body {
  margin: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ah-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.btn-icon {
  margin-right: 4px;
}

.muted {
  color: var(--ah-text-muted);
  font-size: 12px;
}

.hint {
  margin-top: 4px;
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 1.5;
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

.tag-running {
  --el-tag-text-color: #d97706;
  --el-tag-border-color: #fde68a;
  --el-tag-bg-color: #fffbeb;
}

.tag-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}

@media (max-width: 1280px) {
  .page {
    height: auto;
    min-height: 0;
    overflow: visible;
  }

  .topline {
    grid-template-columns: 1fr;
    justify-items: start;
  }

  .split {
    flex-direction: column;
    overflow: visible;
  }

  .pane-left,
  .pane-right {
    flex: 1 1 auto;
    max-width: 100%;
    width: 100%;
    height: min(70vh, 640px);
  }

  .toolbar-search {
    width: 140px;
  }
}
</style>
