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
      <section class="pane pane-docs">
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
            <span class="toolbar-hint">支持 txt/md/pdf，≤ 20MB</span>

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

          <!-- 表格单独滚动：分页钉在卡片底，两栏才能同高贴底 -->
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
              <el-table-column prop="title" label="文档名" min-width="110" show-overflow-tooltip align="left" />
              <el-table-column label="状态" width="88">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain" :class="statusClass(row.status)">
                    {{ statusLabel(row.status) }}
                  </el-tag>
                  <div v-if="row.status === 'RUNNING'" class="muted">
                    {{ row.stage || '处理中' }} {{ row.progress ?? 0 }}%
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="切片" width="52" align="center">
                <template #default="{ row }">{{ row.chunkCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button
                    v-if="canViewChunks"
                    link
                    type="primary"
                    @click.stop="openChunks(row)"
                  >
                    查看
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

      <!-- 右栏：阶段条 + 左解析文本 / 右切片列表对照 -->
      <section class="pane pane-main">
        <el-card v-if="currentDoc" class="pane-card compare-card" shadow="never">
          <template #header>
            <div class="pane-head">
              <div class="pane-head-left">
                <div class="doc-summary">
                  <span class="doc-summary-name" :title="currentDoc.title">{{ currentDoc.title }}</span>
                  <span class="doc-summary-sep">|</span>
                  <span>切片 {{ chunks.length }} 片</span>
                  <span class="doc-summary-sep">|</span>
                  <span>总 Tokens {{ totalTokens }}</span>
                </div>
                <span class="pane-sub">入库链路：解析 → 结构识别 → 切片 → 向量化 → 写入；点某一步看摘要</span>
              </div>
              <el-button
                v-if="user.hasPerm('rag:doc:rechunk')"
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

          <!-- 五步阶段条：成功 / 失败后仍保留，状态与摘要来自任务行 stage_trace -->
          <div class="stage-bar">
            <el-popover
              v-for="(step, index) in stages"
              :key="step.stage"
              trigger="click"
              placement="bottom-start"
              :width="280"
            >
              <template #reference>
                <div :class="['stage-step', `stage-${step.status.toLowerCase()}`]">
                  <span class="stage-dot">{{ index + 1 }}</span>
                  <div class="stage-text">
                    <span class="stage-name">{{ step.stage }}</span>
                    <span class="stage-meta">{{ stageMeta(step) }}</span>
                  </div>
                </div>
              </template>
              <div class="stage-pop">
                <div class="stage-pop-title">{{ step.stage }} · {{ stageStatusLabel(step.status) }}</div>
                <div class="stage-pop-line">
                  耗时：{{ step.elapsedMs == null ? '未记录' : formatElapsed(step.elapsedMs) }}
                </div>
                <div class="stage-pop-line">摘要：{{ step.summary || '该步没有记录摘要' }}</div>
                <div v-if="step.stage === '解析'" class="stage-pop-line muted">左侧展示的就是这一步产出的解析文本</div>
              </div>
            </el-popover>
          </div>

          <el-alert v-if="chunkError" :title="chunkError" type="error" show-icon :closable="false" class="block-alert" />

          <div class="compare">
            <!-- 左侧：本轮入库实际使用的解析文本 -->
            <section class="compare-pane compare-parse">
              <header class="compare-head">
                <span class="compare-title">解析文本</span>
                <span class="compare-sub">
                  {{ parseInfo ? `${parseInfo.charCount} 字符` : '—' }}
                  <template v-if="parseInfo?.headerCount"> · {{ parseInfo.headerCount }} 个标题</template>
                  <template v-if="parseInfo?.fromSource"> · 原文件只读解析</template>
                </span>
              </header>
              <div ref="parseScrollRef" v-loading="parseLoading" class="compare-body parse-body">
                <el-alert
                  v-if="parseError"
                  :title="parseError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="compare-alert"
                />
                <template v-else-if="parseBlocks.length">
                  <p
                    v-for="block in parseBlocks"
                    :key="block.key"
                    :data-parse-key="block.key"
                    :class="[
                      'parse-block',
                      block.level ? `parse-h${block.level}` : '',
                      { 'is-active': isBlockActive(block) }
                    ]"
                    @click="onParseBlockClick(block)"
                  >
                    <span v-if="block.level" class="parse-badge">H{{ block.level }}</span>
                    {{ block.text }}
                  </p>
                </template>
                <div v-else-if="!parseLoading" class="compare-empty">
                  {{ parseMessage || '该文档没有可展示的解析文本' }}
                </div>
              </div>
            </section>

            <!-- 右侧：切片卡片，点击与左侧原文联动 -->
            <section class="compare-pane compare-chunks">
              <header class="compare-head">
                <span class="compare-title">切片</span>
                <span class="compare-sub">点一片可在左侧定位原文</span>
              </header>
              <div ref="chunkListRef" v-loading="chunkLoading" class="compare-body chunk-list">
                <div v-if="!chunks.length" class="compare-empty">
                  <p class="compare-empty-text">{{ emptyChunkText }}</p>
                  <el-button
                    v-if="currentDoc.status === 'FAILED' && user.hasPerm('rag:doc:retry')"
                    link
                    type="primary"
                    @click="onRetry(currentDoc)"
                  >
                    查看失败原因后重试
                  </el-button>
                </div>
                <article
                  v-for="chunk in chunks"
                  :key="chunk.id"
                  :data-chunk-id="chunk.id"
                  :class="['chunk-card', { 'is-active': activeChunkId === chunk.id }]"
                  @click="focusChunk(chunk)"
                >
                  <header class="chunk-head">
                    <div class="chunk-head-left">
                      <span class="chunk-title">切片 #{{ (chunk.chunkIndex ?? 0) + 1 }}</span>
                      <el-tag size="small" effect="plain" :class="['strategy-tag', strategyClass(chunk.strategy)]">
                        {{ chunk.strategyLabel || '未记录' }}
                      </el-tag>
                      <span class="chunk-token">Token: {{ chunk.tokenEst ?? 0 }}</span>
                    </div>
                    <el-button
                      v-if="user.hasPerm('rag:doc:rechunk')"
                      link
                      type="primary"
                      size="small"
                      @click.stop="openEditChunk(chunk)"
                    >
                      编辑
                    </el-button>
                  </header>
                  <div class="chunk-path" :title="chunk.sectionPath || ''">
                    章节：{{ chunk.sectionPath || '—' }}
                    <span v-if="chunk.partIndex"> · 第 {{ chunk.partIndex }} 段</span>
                  </div>
                  <pre class="chunk-body">{{ chunk.content }}</pre>
                </article>
              </div>
            </section>
          </div>
        </el-card>

        <!-- 未选文档：浅灰图标空态 -->
        <el-card v-else class="pane-card" shadow="never">
          <div class="chunk-empty">
            <el-icon class="chunk-empty-icon"><Document /></el-icon>
            <p class="chunk-empty-text">请在左侧选择一个文档，查看入库阶段、解析文本与切片对照</p>
          </div>
        </el-card>
      </section>
    </div>

    <!-- 重切：先只读预览，确认后才清旧切片与向量 -->
    <el-dialog v-model="rechunk.open" title="重新切片" :width="rechunk.preview ? '760px' : '460px'" :close-on-click-modal="false">
      <template v-if="!rechunk.preview">
        <el-form label-width="88px">
          <el-form-item label="切片长度" required>
            <el-input-number v-model="rechunk.chunkSize" :min="100" :max="4000" :step="50" controls-position="right" />
            <div class="hint">字符数；越小切片越细，越大单条信息越完整但易被模型截断</div>
          </el-form-item>
          <el-form-item label="重叠">
            <el-input-number
              v-model="rechunk.chunkOverlap"
              :min="0"
              :max="overlapMax"
              :step="10"
              controls-position="right"
            />
            <div class="hint">不超过切片长度的一半；不填则沿用知识库入库参数</div>
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="先预览：只按新片长重算并展示卡片，不会清掉现有切片、不会改向量，该文档仍可检索。"
        />
      </template>

      <template v-else>
        <div class="preview-summary">
          预计 <b>{{ rechunk.preview?.chunkCount ?? 0 }}</b> 片 · 总 Tokens
          <b>{{ rechunk.preview?.tokenTotal ?? 0 }}</b>
          <span class="muted">
            （片长 {{ rechunk.preview?.chunkSize }} / 重叠 {{ rechunk.preview?.chunkOverlap }}）
          </span>
        </div>
        <el-alert
          v-if="rechunk.preview?.truncated"
          type="info"
          :closable="false"
          show-icon
          :title="`片数较多，预览只展示前 ${rechunk.preview?.chunks.length ?? 0} 片；确认后仍按全量落库。`"
        />
        <div class="preview-list">
          <div v-for="card in rechunk.preview?.chunks || []" :key="card.chunkIndex" class="preview-card">
            <header class="chunk-head">
              <div class="chunk-head-left">
                <span class="chunk-title">切片 #{{ card.chunkIndex + 1 }}</span>
                <el-tag size="small" effect="plain" :class="['strategy-tag', strategyClass(card.strategy)]">
                  {{ card.strategyLabel || '未记录' }}
                </el-tag>
                <span class="chunk-token">Token: {{ card.tokenEst ?? 0 }}</span>
              </div>
            </header>
            <div class="chunk-path">章节：{{ card.sectionPath || '—' }}</div>
            <pre class="chunk-body">{{ card.content }}</pre>
          </div>
        </div>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="确认覆盖会先清掉该文档现有切片与向量，再按预览参数重新入库；期间该文档不可检索，完成后恢复。"
        />
      </template>

      <template #footer>
        <template v-if="!rechunk.preview">
          <el-button @click="rechunk.open = false">取消</el-button>
          <el-button type="primary" :loading="rechunk.previewing" @click="previewRechunk">预览切片</el-button>
        </template>
        <template v-else>
          <el-button :disabled="rechunk.saving" @click="backToRechunkEdit">返回修改</el-button>
          <el-button :disabled="rechunk.saving" @click="rechunk.open = false">取消</el-button>
          <el-button type="primary" :loading="rechunk.saving" @click="submitRechunk">确认覆盖并重灌</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 单条切片编辑：只改这一片正文/章节路径，并只重写这一片向量 -->
    <el-dialog v-model="edit.open" title="编辑切片" width="620px" :close-on-click-modal="false">
      <el-form label-width="88px">
        <el-form-item label="切片序号">
          <span class="muted">切片 #{{ edit.chunkIndex + 1 }}</span>
        </el-form-item>
        <el-form-item label="章节路径">
          <el-input v-model="edit.sectionPath" clearable placeholder="可留空；例如：退货政策 / 质检时效" />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input
            v-model="edit.content"
            type="textarea"
            :rows="10"
            resize="vertical"
            placeholder="切片正文不能为空"
          />
          <div class="hint">保存后只更新这一片正文与向量，其它切片保持不变；空正文不允许保存。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="edit.saving" @click="edit.open = false">取消</el-button>
        <el-button type="primary" :loading="edit.saving" @click="saveChunk">保存</el-button>
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
 * 2. 左栏工具栏上传与检索，表格展示文档状态、切片数；
 * 3. 右栏展示选中文档的五步入库阶段条、解析文本与切片卡片，并支持左右联动；
 * 4. 重新切片先只读预览，确认后才覆盖；单条切片可编辑正文与章节路径。
 *
 * 约束：
 * - 不在此页改 Embedding / 建库参数；重建索引复用知识库 rebuild 接口；
 * - 预览接口只读，不得在前端先把旧切片清掉；
 * - 旧文档没有策略/偏移时显示「未记录」，不编造策略结论。
 */
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Document, RefreshRight, Search, Upload } from '@element-plus/icons-vue'
import {
  deleteDocument,
  getDocumentParseText,
  getKb,
  listDocumentChunks,
  pageDocuments,
  previewRechunkDocument,
  rebuildKb,
  rechunkDocument,
  retryDocument,
  updateDocumentChunk,
  uploadDocument
} from '@/api/rag'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { useUserStore } from '@/stores/user'
import {
  DOC_STATUS_LABEL,
  KB_CAPABILITY_LABEL,
  type RagChunkRow,
  type RagDocumentRow,
  type RagKbRow,
  type RagParseTextRow,
  type RagRechunkPreviewRow,
  type RagStageRow
} from '@/types/rag'

/** KeepAlive 的 include 比对组件名，必须与 router meta.cacheName 一致 */
defineOptions({ name: 'RagKbDocs' })

/** 重切参数缺省值，与后端 RagIngestAppService 的默认入库参数一致 */
const DEFAULT_CHUNK_SIZE = 450
const DEFAULT_CHUNK_OVERLAP = 100

/** 对外固定的五步入库阶段；顺序与后端 stage_trace 一致 */
const STAGE_NAMES = ['解析', '结构识别', '切片', '向量化', '写入'] as const

/** 本页状态文案：偏操作态，与列表页「可检索」略有区分 */
const DOC_STATUS_UI: Record<string, string> = {
  PENDING: '待解析',
  RUNNING: '解析中',
  READY: '就绪',
  FAILED: '失败'
}

/** 阶段状态中文 */
const STAGE_STATUS_UI: Record<string, string> = {
  SUCCESS: '已完成',
  RUNNING: '进行中',
  FAILED: '失败',
  PENDING: '待处理',
  UNKNOWN: '未记录'
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
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size, 48)

/* ---------------- 右栏：阶段 / 解析文本 / 切片 ---------------- */
const currentDoc = ref<RagDocumentRow | null>(null)
const chunks = ref<RagChunkRow[]>([])
const chunkLoading = ref(false)
const chunkError = ref('')
const parseInfo = ref<RagParseTextRow | null>(null)
const parseLoading = ref(false)
const parseError = ref('')
const parseScrollRef = ref<HTMLElement | null>(null)
const chunkListRef = ref<HTMLElement | null>(null)
/** 当前联动选中的切片 id；为空表示未选中 */
const activeChunkId = ref<number | null>(null)

/** 重切弹窗状态；preview 为空表示仍在参数步骤 */
const rechunk = reactive({
  open: false,
  previewing: false,
  saving: false,
  chunkSize: DEFAULT_CHUNK_SIZE,
  chunkOverlap: DEFAULT_CHUNK_OVERLAP,
  preview: null as RagRechunkPreviewRow | null
})

/** 单条切片编辑弹窗状态 */
const edit = reactive({
  open: false,
  saving: false,
  chunkId: 0,
  chunkIndex: 0,
  content: '',
  sectionPath: ''
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

/** 重叠上限：不超过片长一半，与后端 IngestParams 的收紧规则一致 */
const overlapMax = computed(() => Math.max(0, Math.floor(rechunk.chunkSize / 2)))

// 片长调小时同步收紧重叠，避免提交一个必然被后端夹紧的值造成预览与落库口径不一致
watch(
  () => rechunk.chunkSize,
  () => {
    if (rechunk.chunkOverlap > overlapMax.value) {
      rechunk.chunkOverlap = overlapMax.value
    }
  }
)

/** 阶段条展示行 */
interface StageView {
  stage: string
  status: string
  elapsedMs: number | null
  summary: string | null
}

/** 五个阶段的可展示行：后端有记录用记录，否则显示未记录，不伪造摘要 */
const stages = computed<StageView[]>(() => {
  const recorded = new Map<string, RagStageRow>()
  for (const row of currentDoc.value?.stages || []) {
    if (row?.stage) {
      recorded.set(row.stage, row)
    }
  }
  const activeStage = currentDoc.value?.stage || ''
  return STAGE_NAMES.map((name) => {
    const row = recorded.get(name)
    if (row) {
      return {
        stage: name,
        status: row.status || 'UNKNOWN',
        elapsedMs: row.elapsedMs ?? null,
        summary: row.summary ?? null
      }
    }
    // 老任务没有阶段记录：进行中的当前步可据任务 stage 推断，其余不猜测
    if (busyCurrent.value && activeStage === name) {
      return { stage: name, status: 'RUNNING', elapsedMs: null, summary: null }
    }
    return { stage: name, status: 'UNKNOWN', elapsedMs: null, summary: null }
  })
})

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

/** 解析文本不可用时的提示（旧文档无产物且原文件缺失） */
const parseMessage = computed(() => parseInfo.value?.message || '')

/** 解析文本按行切成段落，带偏移与标题层级，供左侧渲染与联动 */
interface ParseBlock {
  key: number
  text: string
  level: number
  start: number
  end: number
}

const parseBlocks = computed<ParseBlock[]>(() => {
  const text = parseInfo.value?.text
  if (!text) {
    return []
  }
  const blocks: ParseBlock[] = []
  let offset = 0
  text.split('\n').forEach((line, index) => {
    const start = offset
    const end = start + line.length
    offset = end + 1
    if (!line.trim()) {
      return
    }
    const heading = detectHeading(line)
    blocks.push({ key: index, text: heading.text, level: heading.level, start, end })
  })
  return blocks
})

/**
 * 识别一行是否标题并给出层级：Markdown # 优先，其次常见中文编号。
 * 只用于展示层级，不参与切片算法。
 *
 * @param line 原始行
 * @return 展示文本与层级；普通正文层级为 0
 */
function detectHeading(line: string): { text: string; level: number } {
  const trimmed = line.trim()
  const markdown = /^(#{1,6})\s+(.*)$/.exec(trimmed)
  if (markdown) {
    return { text: markdown[2], level: markdown[1].length }
  }
  if (/^第[一二三四五六七八九十百千0-9]+[章节部篇]/.test(trimmed)) {
    return { text: trimmed, level: 2 }
  }
  if (/^[一二三四五六七八九十]+、/.test(trimmed)) {
    return { text: trimmed, level: 3 }
  }
  if (/^（[一二三四五六七八九十]+）/.test(trimmed)) {
    return { text: trimmed, level: 4 }
  }
  return { text: line, level: 0 }
}

/** 字符区间：[start, end)，下标对齐解析文本 */
interface ChunkRange {
  start: number
  end: number
}

/** 定位结果：除原文本区间外带上归一化终点，供下一片继续往后找 */
interface LocatedSpan extends ChunkRange {
  normEnd: number
}

/** 解析文本的归一化视图：折叠空白 + 去掉行首 Markdown 记号，并保留回原文本的下标 */
interface NormalizedParse {
  /** 原解析文本 */
  raw: string
  /** 归一化文本 */
  text: string
  /** map[k] = 归一化第 k 个字符在原文本中的下标；末位是原文本长度 */
  map: number[]
}

/** 行首 Markdown 记号：标题井号、无序列表符号、有序列表序号（与后端 ChunkPresentation 同口径） */
const LINE_MARKER = /^[ \t]*(?:#{1,6}[ \t]+|[-*+][ \t]+|\d+[.、)][ \t]+)/

/** 超长再切给标题追加的 (i/n) 后缀 */
const PART_SUFFIX = /\s*\(\d+\/\d+\)\s*$/

/** 解析文本归一化视图：只随解析文本变化重算，切片定位与旧偏移校验共用 */
const parseNorm = computed<NormalizedParse | null>(() => {
  const raw = parseInfo.value?.text
  return raw ? normalizeWithIndex(raw) : null
})

/** 切片范围缓存：解析文本或切片变化后失效，点击时再懒建一遍 */
let chunkRangeMap: Map<number, ChunkRange | null> | null = null

/** 清掉切片范围缓存；解析文本或切片列表一变就必须调用，避免高亮落到旧范围 */
function invalidateChunkRanges() {
  chunkRangeMap = null
}

watch(parseNorm, invalidateChunkRanges)

/** 当前选中切片在解析文本中的范围；无偏移时用整段正文定位作为等价映射 */
const activeRange = computed<ChunkRange | null>(() => {
  if (activeChunkId.value == null) {
    return null
  }
  const chunk = chunks.value.find((item) => item.id === activeChunkId.value)
  return chunk ? chunkRange(chunk) : null
})

/** 左侧段落是否落在当前选中切片的范围内 */
function isBlockActive(block: ParseBlock): boolean {
  const range = activeRange.value
  if (!range) {
    return false
  }
  return block.end > range.start && block.start < range.end
}

/**
 * 取某切片在解析文本中的范围：首次调用时按全篇顺序算一遍并缓存。
 *
 * @param chunk 切片
 * @return 范围；无偏移且定位不到时为 null（不高亮）
 */
function chunkRange(chunk: RagChunkRow): ChunkRange | null {
  if (!chunkRangeMap) {
    chunkRangeMap = buildChunkRanges()
  }
  return chunkRangeMap.get(chunk.id) ?? null
}

/**
 * 一次算完全篇切片范围：按切片顺序带游标定位，避免相邻片互相抢位置。
 * 每片优先用后端偏移，但偏移明显没盖住该片正文时（历史数据只记了标题）改用正文定位结果。
 *
 * @return 切片 id → 范围（无映射为 null）
 */
function buildChunkRanges(): Map<number, ChunkRange | null> {
  const ranges = new Map<number, ChunkRange | null>()
  const norm = parseNorm.value
  if (!norm) {
    for (const chunk of chunks.value) {
      ranges.set(chunk.id, null)
    }
    return ranges
  }
  let cursor = 0
  for (const chunk of chunks.value) {
    const located = locateChunkSpan(norm, chunk, cursor)
    if (located) {
      cursor = Math.max(cursor, located.normEnd)
    }
    ranges.set(chunk.id, pickRange(norm.raw, chunk, located))
  }
  return ranges
}

/**
 * 决定用哪个范围：后端偏移已完整覆盖该片正文（含标题）时直接用，否则以正文定位结果为准。
 *
 * @param raw     解析文本
 * @param chunk   切片
 * @param located 正文定位结果，可空
 * @return 最终范围；没有可信范围时为 null
 */
function pickRange(raw: string, chunk: RagChunkRow, located: LocatedSpan | null): ChunkRange | null {
  const stored = storedRange(raw, chunk)
  if (located) {
    if (stored && stored.start <= located.start && stored.end >= located.end) {
      return stored
    }
    return { start: located.start, end: located.end }
  }
  if (!stored) {
    return null
  }
  // 正文在原文里定位不到时，只有标题的旧偏移宁可不亮，也不伪造范围
  return isHeadingOnlyRange(raw, stored, chunk) ? null : stored
}

/**
 * 后端偏移做基础校验：缺失、越界、空区间一律视为不可用。
 *
 * @param raw   解析文本
 * @param chunk 切片
 * @return 可用的偏移范围；不可用为 null
 */
function storedRange(raw: string, chunk: RagChunkRow): ChunkRange | null {
  const start = chunk.charStart
  const end = chunk.charEnd
  if (start == null || end == null) {
    return null
  }
  if (start < 0 || end <= start || end > raw.length) {
    return null
  }
  return { start, end }
}

/**
 * 判断偏移是否「只盖住了章节标题」：切片还有正文、范围却正好等于标题。
 * <p>
 * 历史数据把标题长度当整片偏移写入，这类范围不参与高亮，避免显示成「整片只有标题」。
 *
 * @param raw   解析文本
 * @param range 后端偏移范围
 * @param chunk 切片
 * @return true 表示只有标题、不可用
 */
function isHeadingOnlyRange(raw: string, range: ChunkRange, chunk: RagChunkRow): boolean {
  const heading = collapse(headingTitle(chunk))
  if (!heading) {
    return false
  }
  if (collapse(raw.slice(range.start, range.end)) !== heading) {
    return false
  }
  return collapse(chunk.content || '').length > heading.length
}

/**
 * 在归一化文本中定位整片正文。
 *
 * @param norm  归一化解析文本
 * @param chunk 切片
 * @param from  归一化搜索起点（上一片终点）
 * @return 范围与归一化终点；定位不到为 null
 */
function locateChunkSpan(norm: NormalizedParse, chunk: RagChunkRow, from: number): LocatedSpan | null {
  for (const candidate of chunkBodyCandidates(chunk)) {
    const needle = collapse(candidate)
    // 过短正文容易命中无关位置，宁可不标偏移
    if (needle.length < 4) {
      continue
    }
    let at = norm.text.indexOf(needle, Math.max(0, from))
    if (at < 0 && from > 0) {
      at = norm.text.indexOf(needle)
    }
    if (at < 0) {
      continue
    }
    return {
      start: extendToHeading(norm.raw, norm.map[at], chunk),
      end: norm.map[at + needle.length - 1] + 1,
      normEnd: at + needle.length
    }
  }
  return null
}

/**
 * 可用来在原文定位的正文候选：整片优先，其次去掉策略合成的非原文前缀。
 *
 * @param chunk 切片
 * @return 候选正文，按优先级排列
 */
function chunkBodyCandidates(chunk: RagChunkRow): string[] {
  const content = chunk.content || ''
  const lines = content.split('\n')
  const heading = headingTitle(chunk)
  const path = (chunk.sectionPath || '')
    .split(' / ')
    .map((part) => part.trim())
    .filter(Boolean)
  const candidates: string[] = []
  const push = (value: string) => {
    const text = value.trim()
    if (text.length >= 4) {
      candidates.push(text)
    }
  }
  push(content)
  let drop = 0
  if (content.startsWith('表头：') && lines.length > 1) {
    // 表格片首行是「表头：…」，原文只有后面的数据行
    drop = 1
  } else if (
    path.length > 1 &&
    lines.length > 2 &&
    lines[0].trim() === path[0] &&
    lines[1].trim() === path[path.length - 1]
  ) {
    // 条目片首两行是「条目名 / 字段名」，原文没有
    drop = 2
  } else if (lines.length > 1 && heading && lines[0].replace(PART_SUFFIX, '').trim() === heading) {
    // 正文被前置了章节标题时先去掉，避免把标题当成正文定位
    drop = 1
  }
  if (drop > 0 && drop < lines.length) {
    push(lines.slice(drop).join('\n'))
  }
  return candidates
}

/**
 * 把正文起点前移到该节标题行行首，让高亮覆盖「标题 + 正文」。
 * <p>
 * 只在标题独占一行、且标题行与正文之间只有空行/列表记号时前移；否则保持正文起点，
 * 避免把上一节标题或正文里的同名串带进选区。
 *
 * @param raw       解析文本
 * @param bodyStart 正文起点
 * @param chunk     切片
 * @return 前移后的起点；不该前移时原样返回
 */
function extendToHeading(raw: string, bodyStart: number, chunk: RagChunkRow): number {
  const heading = headingTitle(chunk)
  if (!heading) {
    return bodyStart
  }
  // 正文本身从标题文字开始（正文被前置标题）时从当前起点算，否则找紧邻正文的上一处标题
  const startsWithHeading = raw.startsWith(heading, bodyStart)
  const at = startsWithHeading ? bodyStart : raw.lastIndexOf(heading, Math.max(0, bodyStart - 1))
  if (at < 0) {
    return bodyStart
  }
  const lineStart = raw.lastIndexOf('\n', at - 1) + 1
  let lineEnd = raw.indexOf('\n', at + heading.length)
  if (lineEnd < 0) {
    lineEnd = raw.length
  }
  // 标题必须独占该行：标题之后到换行之间只剩空白
  if (raw.slice(at + heading.length, lineEnd).trim()) {
    return bodyStart
  }
  // 标题在正文之前时，两者之间只允许空行与该片首行的列表记号
  if (!startsWithHeading && (lineEnd >= bodyStart || hasContent(raw.slice(lineEnd, bodyStart)))) {
    return bodyStart
  }
  // 行首只允许 Markdown 井号，否则命中可能是正文里的同名串
  const prefix = raw.slice(lineStart, at).trim()
  if (prefix && !/^#{1,6}$/.test(prefix)) {
    return bodyStart
  }
  return lineStart
}

/**
 * 章节标题：取章节路径最后一段，并去掉超长再切追加的 (i/n) 后缀。
 *
 * @param chunk 切片
 * @return 标题文本；没有为 ''
 */
function headingTitle(chunk: RagChunkRow): string {
  const path = (chunk.sectionPath || '').trim()
  const last = path.includes(' / ') ? path.slice(path.lastIndexOf(' / ') + 3) : path
  return last.replace(PART_SUFFIX, '').trim()
}

/**
 * 片段里除空行与该行行首 Markdown 记号外是否还有内容。
 *
 * @param gap 待检查片段
 * @return true 表示有实际内容
 */
function hasContent(gap: string): boolean {
  return gap.split('\n').some((line) => line.replace(LINE_MARKER, '').trim().length > 0)
}

/**
 * 构建解析文本的归一化视图：连续空白折叠为单空格、行首 Markdown 记号跳过，并记录回原文本的下标。
 *
 * @param raw 解析文本
 * @return 归一化视图
 */
function normalizeWithIndex(raw: string): NormalizedParse {
  let text = ''
  const map: number[] = []
  let pendingSpace = -1
  let lineStart = true
  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i]
    if (ch === '\n') {
      lineStart = true
    }
    if (lineStart) {
      const marker = lineMarkerLength(raw, i)
      if (marker > 0) {
        i += marker - 1
        continue
      }
    }
    if (/\s/.test(ch)) {
      if (text.length > 0 && pendingSpace < 0) {
        pendingSpace = i
      }
      continue
    }
    lineStart = false
    if (pendingSpace >= 0) {
      map.push(pendingSpace)
      text += ' '
      pendingSpace = -1
    }
    map.push(i)
    text += ch
  }
  map.push(raw.length)
  return { raw, text, map }
}

/**
 * 行首 Markdown 记号的长度（只含记号本身，不含其后的空白）。
 *
 * @param text 原文本
 * @param i    行首下标
 * @return 需要跳过的字符数；不是记号返回 0
 */
function lineMarkerLength(text: string, i: number): number {
  const first = text[i]
  if (first === '#') {
    let n = 0
    while (text[i + n] === '#') {
      n++
    }
    const next = text[i + n]
    return n <= 6 && (next === undefined || /\s/.test(next)) ? n : 0
  }
  if (first === '-' || first === '*' || first === '+') {
    return /\s/.test(text[i + 1] ?? '') ? 1 : 0
  }
  if (/\d/.test(first)) {
    let n = 0
    while (/\d/.test(text[i + n] ?? '')) {
      n++
    }
    const punct = text[i + n]
    return punct && '.、)'.includes(punct) && /\s/.test(text[i + n + 1] ?? '') ? n + 1 : 0
  }
  return 0
}

/**
 * 折叠连续空白并去掉行首 Markdown 记号，供切片正文与解析文本做等价比较。
 *
 * @param text 待归一化文本
 * @return 归一化文本
 */
function collapse(text: string): string {
  return text
    .split('\n')
    .map((line) => line.replace(LINE_MARKER, ''))
    .join('\n')
    .replace(/\s+/g, ' ')
    .trim()
}

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

/** 阶段状态中文 */
function stageStatusLabel(status: string) {
  return STAGE_STATUS_UI[status] || status
}

/** 阶段副标题：优先产物摘要，其次耗时，最后状态 */
function stageMeta(step: { status: string; elapsedMs: number | null; summary: string | null }) {
  if (step.summary) {
    return step.summary
  }
  if (step.elapsedMs != null) {
    return formatElapsed(step.elapsedMs)
  }
  return stageStatusLabel(step.status)
}

/** 毫秒转可读耗时 */
function formatElapsed(ms: number) {
  if (!Number.isFinite(ms) || ms < 0) {
    return '—'
  }
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`
}

/** 策略标签配色；未知策略保持中性 */
function strategyClass(code?: string | null) {
  switch (code) {
    case 'HIERARCHICAL':
      return 'strategy-hierarchical'
    case 'ENTRY_BASED':
      return 'strategy-entry'
    case 'TABULAR':
      return 'strategy-tabular'
    case 'NARRATIVE':
      return 'strategy-narrative'
    default:
      return 'strategy-none'
  }
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
 * 点击行即打开对照，减少必须点「查看」的步骤；操作列按钮自行 stop。
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
    rechunk.chunkOverlap = Math.min(fromConfig.overlap, Math.floor(fromConfig.size / 2))
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
    parseInfo.value = null
    activeChunkId.value = null
    invalidateChunkRanges()
    return
  }
  const wasRunning = selected.status === 'RUNNING' || selected.status === 'PENDING'
  currentDoc.value = fresh
  // 重切/入库刚跑完：右栏还是旧的，自动补一次解析文本与切片，省掉用户再点一次
  if (wasRunning && fresh.status === 'READY') {
    void loadChunks()
    void loadParseText()
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
    // 上传后直接打开该文档：阶段条与解析/切片对照立刻可见，不必再找一行点一次
    const uploaded = data.data
    if (uploaded && canViewChunks.value) {
      const fresh = rows.value.find((row) => row.id === uploaded.id) || uploaded
      await openChunks(fresh)
    }
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
      parseInfo.value = null
      activeChunkId.value = null
      invalidateChunkRanges()
    }
    ElMessage.success('已删除')
    await load()
  } finally {
    removingId.value = 0
  }
}

/**
 * 拉取某文档的解析文本与切片并展示在右栏。
 *
 * @param row 文档行
 */
async function openChunks(row: RagDocumentRow) {
  currentDoc.value = row
  // 先清空上一份数据：否则加载期间右栏会短暂显示别的文档内容
  chunks.value = []
  parseInfo.value = null
  activeChunkId.value = null
  chunkError.value = ''
  parseError.value = ''
  invalidateChunkRanges()
  await Promise.all([loadChunks(), loadParseText()])
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
    invalidateChunkRanges()
  } catch {
    chunkError.value = '切片加载失败'
    chunks.value = []
    invalidateChunkRanges()
  } finally {
    chunkLoading.value = false
  }
}

/** 读取解析文本：只读接口，失败只提示，不影响切片列表 */
async function loadParseText() {
  const doc = currentDoc.value
  if (!doc) {
    return
  }
  parseLoading.value = true
  parseError.value = ''
  try {
    const { data } = await getDocumentParseText(kbId.value, doc.id)
    parseInfo.value = data.data
  } catch {
    parseError.value = '解析文本加载失败；可重新切片后重试'
    parseInfo.value = null
  } finally {
    parseLoading.value = false
  }
}

/** 点击左侧原文段落：选中包含该段落的切片并滚动到对应卡片 */
function onParseBlockClick(block: ParseBlock) {
  const hit = chunks.value.find((chunk) => {
    const range = chunkRange(chunk)
    return range ? block.end > range.start && block.start < range.end : false
  })
  if (!hit) {
    ElMessage.info('该段没有对应的切片（可能被清洗规则过滤）')
    return
  }
  activeChunkId.value = hit.id
  void nextTick(() => {
    chunkListRef.value
      ?.querySelector<HTMLElement>(`[data-chunk-id="${hit.id}"]`)
      ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  })
}

/** 点击右侧切片：选中并滚动左侧对应原文 */
function focusChunk(chunk: RagChunkRow) {
  activeChunkId.value = chunk.id
  const range = chunkRange(chunk)
  if (!range) {
    return
  }
  const target = parseBlocks.value.find((block) => block.end > range.start && block.start < range.end)
  if (!target) {
    return
  }
  void nextTick(() => {
    parseScrollRef.value
      ?.querySelector<HTMLElement>(`[data-parse-key="${target.key}"]`)
      ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  })
}

/** 打开重切弹窗：重置到参数步骤，片长以知识库当前入库参数为默认 */
function openRechunk() {
  if (!currentDoc.value) {
    return
  }
  const fromConfig = parseChunkSize(kb.value?.ingestConfig)
  rechunk.chunkSize = fromConfig.size
  rechunk.chunkOverlap = Math.min(fromConfig.overlap, Math.floor(fromConfig.size / 2))
  rechunk.preview = null
  rechunk.open = true
}

/** 只读预览：不写库、不动向量，也不改变右栏已有切片 */
async function previewRechunk() {
  const doc = currentDoc.value
  if (!doc) {
    return
  }
  if (rechunk.chunkSize < 100 || rechunk.chunkSize > 4000) {
    ElMessage.warning('切片长度需在 100–4000 之间')
    return
  }
  rechunk.previewing = true
  try {
    const { data } = await previewRechunkDocument(kbId.value, doc.id, {
      chunkSize: rechunk.chunkSize,
      chunkOverlap: rechunk.chunkOverlap
    })
    rechunk.preview = data.data
  } finally {
    rechunk.previewing = false
  }
}

/** 从预览返回参数步骤，不产生任何写操作 */
function backToRechunkEdit() {
  rechunk.preview = null
}

async function submitRechunk() {
  const doc = currentDoc.value
  const preview = rechunk.preview
  if (!doc || !preview) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认覆盖「${doc.title}」的现有切片与向量？覆盖开始后该文档暂时不可检索，完成后恢复。`,
      '确认覆盖',
      { confirmButtonText: '确认覆盖', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  rechunk.saving = true
  try {
    await rechunkDocument(kbId.value, doc.id, {
      chunkSize: preview.chunkSize,
      chunkOverlap: preview.chunkOverlap
    })
    rechunk.open = false
    rechunk.preview = null
    // 旧切片已被服务端清空，这里立即清右栏，等文档回到 READY 再自动补拉
    chunks.value = []
    parseInfo.value = null
    activeChunkId.value = null
    invalidateChunkRanges()
    ElMessage.success('已受理，正在按新片长重新入库')
    await load()
  } finally {
    rechunk.saving = false
  }
}

/**
 * 打开单条切片编辑：带上当前正文与章节路径。
 *
 * @param chunk 当前切片
 */
function openEditChunk(chunk: RagChunkRow) {
  edit.chunkId = chunk.id
  edit.chunkIndex = chunk.chunkIndex ?? 0
  edit.content = chunk.content
  edit.sectionPath = chunk.sectionPath || ''
  edit.open = true
}

/** 保存单条切片：只替换本地这一片，不重载整篇，避免其它卡片闪动 */
async function saveChunk() {
  const doc = currentDoc.value
  if (!doc || !edit.chunkId) {
    return
  }
  const content = edit.content.trim()
  if (!content) {
    ElMessage.warning('切片正文不能为空')
    return
  }
  edit.saving = true
  try {
    const { data } = await updateDocumentChunk(kbId.value, doc.id, edit.chunkId, {
      content,
      // 空字符串表示清空章节路径；null 会被后端当作「不修改」
      sectionPath: edit.sectionPath.trim()
    })
    const updated = data.data
    const index = chunks.value.findIndex((item) => item.id === edit.chunkId)
    if (index >= 0 && updated) {
      // 只替换更新后的这一片：序号、其它片与总 Token 由 computed 自然刷新
      chunks.value.splice(index, 1, updated)
      // 正文改了，原偏移/定位结果都不再可信，下一次点击重算
      invalidateChunkRanges()
    }
    edit.open = false
    ElMessage.success('已保存，仅更新该切片及其向量')
  } finally {
    edit.saving = false
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
    parseInfo.value = null
    parseError.value = ''
    chunkError.value = ''
    activeChunkId.value = null
    invalidateChunkRanges()
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
  font-weight: 600;
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
  background: var(--ah-neutral-tint);
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

/* 左栏只放文档列表，收窄后把宽度留给对照区 */
.pane-docs {
  flex: 0 0 400px;
  max-width: 400px;
}

.pane-main {
  flex: 1 1 auto;
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

.compare-card :deep(.el-card__body) {
  gap: 10px;
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
  max-width: 280px;
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
  width: 150px;
  max-width: 100%;
}

.toolbar .btn-query,
.toolbar .btn-reset {
  min-width: 52px;
}

:deep(.is-current-doc) {
  --el-table-tr-bg-color: var(--ah-neutral-tint);
  cursor: pointer;
}

:deep(.el-table__row) {
  cursor: pointer;
}

/* ---------------- 五步阶段条 ---------------- */
.stage-bar {
  display: flex;
  flex: none;
  gap: 8px;
  min-width: 0;
  overflow-x: auto;
  padding-bottom: 2px;
}

.stage-step {
  display: flex;
  flex: 1 1 0;
  align-items: center;
  gap: 8px;
  min-width: 120px;
  padding: 8px 10px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface-muted);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.stage-step:hover {
  border-color: var(--el-color-primary-light-5);
}

.stage-dot {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--ah-neutral-tint);
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.stage-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.stage-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--ah-text);
  white-space: nowrap;
}

.stage-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ah-text-muted);
}

/* 五步阶段条只走语义色令牌：通过绿 / 待办琥珀 / 故障红 / 停用灰 */
.stage-success {
  border-color: var(--el-color-success-light-5);
  background: var(--ah-success-tint);
}

.stage-success .stage-dot {
  background: var(--ah-success);
  color: #fff;
}

.stage-running {
  border-color: var(--el-color-warning-light-5);
  background: var(--ah-warning-tint);
}

.stage-running .stage-dot {
  background: var(--ah-warning);
  color: #fff;
}

.stage-failed {
  border-color: var(--el-color-danger-light-5);
  background: var(--ah-danger-tint);
}

.stage-failed .stage-dot {
  background: var(--ah-danger);
  color: #fff;
}

.stage-unknown .stage-dot {
  background: var(--ah-neutral-tint);
  color: var(--ah-text-faint);
}

.stage-pop {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ah-text-secondary);
}

.stage-pop-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ah-text);
}

.stage-pop-line {
  word-break: break-word;
}

/* ---------------- 左原文 / 右切片对照 ---------------- */
.compare {
  display: flex;
  flex: 1;
  gap: 10px;
  min-height: 0;
  min-width: 0;
}

.compare-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--ah-surface);
}

.compare-parse {
  flex: 0 0 42%;
}

.compare-chunks {
  flex: 1 1 auto;
}

.compare-head {
  display: flex;
  flex: none;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--ah-border-soft);
  background: var(--ah-surface-muted);
}

.compare-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ah-text);
}

.compare-sub {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ah-text-muted);
}

.compare-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px 12px;
}

.parse-body {
  font-size: 13px;
  line-height: 1.75;
  color: var(--ah-text);
}

.parse-block {
  margin: 0 0 4px;
  padding: 3px 6px;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  cursor: pointer;
  transition: background 0.15s ease;
}

.parse-block:hover {
  background: var(--ah-primary-tint);
}

.parse-block.is-active {
  background: var(--ah-primary-tint);
  box-shadow: inset 2px 0 0 var(--ah-primary);
}

/* 对照区标题层级只取 16 / 14 / 13 / 12 四级，字重负责继续分层 */
.parse-h1 {
  font-size: 16px;
  font-weight: 600;
  margin-top: 8px;
}

.parse-h2 {
  font-size: 16px;
  font-weight: 600;
  margin-top: 7px;
}

.parse-h3 {
  font-size: 14px;
  font-weight: 600;
  margin-top: 6px;
}

.parse-h4 {
  font-size: 13px;
  font-weight: 600;
  margin-top: 5px;
}

.parse-h5,
.parse-h6 {
  font-size: 12px;
  font-weight: 600;
}

.parse-badge {
  display: inline-block;
  margin-right: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--ah-neutral-tint);
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 18px;
  vertical-align: 1px;
}

.compare-alert {
  margin-bottom: 8px;
}

.compare-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 160px;
  padding: 24px 12px;
  color: var(--ah-text-muted);
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}

.compare-empty-text {
  margin: 0;
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 2px;
}

.chunk-card {
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--ah-surface-muted);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.chunk-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.chunk-card.is-active {
  border-color: var(--ah-primary);
  background: var(--ah-primary-tint);
  box-shadow: 0 0 0 1px rgba(99, 91, 255, 0.18);
}

.chunk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.chunk-head-left {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
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

.chunk-path {
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ah-text-secondary);
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

/* ---------------- 重切预览 ---------------- */
.preview-summary {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--ah-text-secondary);
}

.preview-summary b {
  color: var(--ah-text);
}

.preview-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 46vh;
  margin: 10px 0;
  overflow: auto;
  padding-right: 4px;
}

.preview-card {
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--ah-surface-muted);
}

/* ---------------- 状态色 ---------------- */
/* 切片策略标签只取令牌色：主色 / 通过绿 / 待办琥珀 / 停用灰，不再用靛蓝与天蓝 */
.strategy-tag {
  --el-tag-bg-color: var(--ah-neutral-tint);
  --el-tag-border-color: var(--ah-border);
  --el-tag-text-color: var(--ah-neutral);
}

.strategy-hierarchical {
  --el-tag-bg-color: var(--ah-primary-tint);
  --el-tag-border-color: var(--el-color-primary-light-5);
  --el-tag-text-color: var(--ah-primary);
}

.strategy-entry {
  --el-tag-bg-color: var(--ah-success-tint);
  --el-tag-border-color: var(--el-color-success-light-5);
  --el-tag-text-color: var(--ah-success);
}

.strategy-tabular {
  --el-tag-bg-color: var(--ah-warning-tint);
  --el-tag-border-color: var(--el-color-warning-light-5);
  --el-tag-text-color: var(--ah-warning);
}

.strategy-narrative {
  --el-tag-bg-color: var(--ah-neutral-tint);
  --el-tag-border-color: var(--ah-border);
  --el-tag-text-color: var(--ah-text-regular);
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
  color: var(--ah-text-faint);
}

.chunk-empty-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
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
  --el-tag-text-color: var(--ah-success);
  --el-tag-border-color: var(--el-color-success-light-5);
  --el-tag-bg-color: var(--ah-success-tint);
}

.tag-error {
  --el-tag-text-color: var(--ah-danger);
  --el-tag-border-color: var(--el-color-danger-light-5);
  --el-tag-bg-color: var(--ah-danger-tint);
}

.tag-running {
  --el-tag-text-color: var(--ah-warning);
  --el-tag-border-color: var(--el-color-warning-light-5);
  --el-tag-bg-color: var(--ah-warning-tint);
}

.tag-off {
  --el-tag-text-color: var(--ah-neutral);
  --el-tag-border-color: var(--ah-border);
  --el-tag-bg-color: var(--ah-neutral-tint);
}

@media (max-width: 1440px) {
  .pane-docs {
    flex: 0 0 360px;
    max-width: 360px;
  }

  .compare-parse {
    flex: 0 0 44%;
  }
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

  .pane-docs,
  .pane-main {
    flex: 1 1 auto;
    max-width: 100%;
    width: 100%;
    height: min(72vh, 680px);
  }

  .compare {
    flex-direction: column;
    min-height: 480px;
  }

  .compare-parse,
  .compare-chunks {
    flex: 1 1 auto;
    min-height: 220px;
  }

  .toolbar-search {
    width: 140px;
  }
}
</style>
