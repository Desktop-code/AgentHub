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
          <el-select v-model="query.status" class="filter-select" clearable placeholder="全部" @change="onSearch">
            <el-option label="就绪" value="READY" />
            <el-option label="初始化失败" value="INIT_FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="query.visibility" class="filter-select" clearable placeholder="全部" @change="onSearch">
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
        <el-table-column label="名称" min-width="160" align="center" show-overflow-tooltip>
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
        <el-table-column label="状态" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :class="statusClass(row.status)">
              {{ KB_STATUS_LABEL[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="检索能力" min-width="120" align="center">
          <template #default="{ row }">
            <!-- 能力为 null 表示集合没建成功，不显示「仅向量」以免误导 -->
            <span v-if="row.storeCapability">{{ KB_CAPABILITY_LABEL[row.storeCapability] || row.storeCapability }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="Embedding" min-width="180" align="center">
          <template #default="{ row }">
            <div class="cell-stack">
              <span :title="row.embeddingModelName || ''">{{ row.embeddingModelName || `#${row.embeddingModelId}` }}</span>
              <span class="muted">维度 {{ row.vectorDimension }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="可见性" min-width="100" align="center">
          <template #default="{ row }">{{ KB_VISIBILITY_LABEL[row.visibility] || row.visibility }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
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

    <!--
      新建：三个 Tab（基础信息 / 入库与切片 / 检索与重排），底部按钮固定不跟 Tab 走。
      Embedding 与维度在此确定，建库后不可改；不做脏保护，点遮罩 / ESC 即可关闭。
    -->
    <el-dialog v-model="create.open" title="新建知识库" width="760px" class="kb-param-dialog">
      <KbConfigTabs
        v-model:tab="create.tab"
        :retrieval="create.form.retrieval"
        :ingest="create.form.ingest"
        @update:retrieval="(next) => (create.form.retrieval = next)"
        @update:ingest="(next) => (create.form.ingest = next)"
      >
        <template #base>
          <el-form label-width="96px">
            <el-form-item label="名称" required>
              <el-input v-model="create.form.name" maxlength="100" show-word-limit />
            </el-form-item>
            <el-form-item label="描述" required>
              <el-input v-model="create.form.description" type="textarea" :rows="2" maxlength="512" show-word-limit />
              <div class="hint">必填；多库时按描述判断哪些库与问句相关，只检索相关的库</div>
            </el-form-item>
            <!-- Embedding 与 Rerank 同排两列，与视觉稿一致 -->
            <div class="base-grid">
              <el-form-item label="Embedding" required>
                <el-select
                  v-model="create.form.embeddingModelId"
                  class="full"
                  placeholder="选择用途为 EMBEDDING 的模型实例"
                  filterable
                  @change="onEmbeddingChange"
                >
                  <!--
                    未就绪（未测过连通 / 测试失败 / 无维度）的实例仍列出来，但置灰不可选：
                    说明直接写进选项文案，只把「选不了」摆明，不在本弹窗测连通、也不把人跳去模型管理页。
                  -->
                  <el-option
                    v-for="item in embeddingOptions"
                    :key="item.id"
                    :label="embeddingOptionLabel(item)"
                    :value="item.id"
                    :disabled="!isEmbeddingReady(item)"
                  />
                </el-select>
                <div class="hint">维度由模型连通测试结果带出；建库后锁定不可修改。</div>
              </el-form-item>
              <el-form-item label="Rerank">
                <el-select v-model="create.form.rerankModelId" class="full" clearable placeholder="不启用">
                  <el-option
                    v-for="item in rerankOptions"
                    :key="item.id"
                    :label="`${item.displayName}（${item.modelId}）`"
                    :value="item.id"
                  />
                </el-select>
                <div class="hint">可选；检索与重排 Tab 的「启用本库重排」需选中实例才生效。</div>
              </el-form-item>
            </div>
            <!--
              维度只在选中模型后出现：没选模型时没有任何来源可算，提前摆一个数字反而像在让人手填。
              值全部由模型侧给出（连通测试返回的维度 → 扩展 JSON → 无），用户不可编辑。
            -->
            <el-form-item v-if="create.form.embeddingModelId" label="维度">
              <el-input :model-value="dimensionText" disabled class="dimension-input" />
              <div class="hint" :class="{ 'hint-error': !dimensionResolved }">{{ dimensionHint }}</div>
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
          </el-form>
        </template>
      </KbConfigTabs>
      <template #footer>
        <el-button @click="create.open = false">取消</el-button>
        <el-button type="primary" :loading="create.saving" @click="saveCreate">创建并初始化集合</el-button>
      </template>
    </el-dialog>

    <!-- 编辑：与新建同一套三 Tab 结构；Embedding / 维度 / 隔离模式锁定不可改 -->
    <el-dialog
      v-model="edit.open"
      title="编辑知识库"
      width="760px"
      class="kb-param-dialog"
      :before-close="beforeEditClose"
    >
      <KbConfigTabs
        v-model:tab="edit.tab"
        :retrieval="edit.form.retrieval"
        :ingest="edit.form.ingest"
        @update:retrieval="(next) => (edit.form.retrieval = next)"
        @update:ingest="(next) => (edit.form.ingest = next)"
      >
        <template #base>
          <el-form label-width="96px">
            <el-form-item label="名称" required>
              <el-input v-model="edit.form.name" maxlength="100" show-word-limit />
            </el-form-item>
            <el-form-item label="描述" required>
              <el-input v-model="edit.form.description" type="textarea" :rows="2" maxlength="512" show-word-limit />
              <div class="hint">必填；已有空描述的老库在此补上后才能保存</div>
            </el-form-item>
            <div class="base-grid">
              <el-form-item label="Embedding">
                <!-- 已锁定：换模型必须新建库，这里只展示，保持原样 -->
                <span class="locked-text">{{ edit.embeddingLabel }}</span>
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
            </div>
            <el-form-item label="可见性">
              <el-radio-group v-model="edit.form.visibility">
                <el-radio value="PRIVATE">私有</el-radio>
                <el-radio value="PUBLIC">公开</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </template>
      </KbConfigTabs>
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
import KbConfigTabs from '@/components/rag/KbConfigTabs.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import { confirmDiscardUnsaved } from '@/utils/unsavedConfirm'
import { formatDateTime } from '@/utils/datetime'
import {
  buildIngestConfig,
  buildRetrievalConfig,
  defaultIngestParams,
  defaultRetrievalParams,
  parseIngestConfig,
  parseRetrievalConfig,
  validateKbParams
} from '@/utils/ragKbParams'
import type { IngestParams, RetrievalParams } from '@/utils/ragKbParams'
import type { InstanceRow } from '@/types/model'
import {
  KB_CAPABILITY_LABEL,
  KB_STATUS_LABEL,
  KB_VISIBILITY_LABEL,
  type RagKbRow
} from '@/types/rag'

/**
 * 表单里的参数部分：表单值 + 库中原有但表单未展示的其它合法键。
 * extra 保存时原样带回，避免「打开再保存」把解析器仍认识的其它键清掉。
 */
interface KbParamFields {
  retrieval: RetrievalParams
  ingest: IngestParams
  retrievalExtra: Record<string, unknown>
  ingestExtra: Record<string, unknown>
}

/**
 * 按库里 JSON 生成参数表单字段；空串 / 缺键都回落平台默认。
 *
 * @param retrievalConfig 库里的 retrieval_config，可空
 * @param ingestConfig    库里的 ingest_config，可空
 * @return 可直接塞进表单的参数部分
 */
function buildParamFields(retrievalConfig?: string | null, ingestConfig?: string | null): KbParamFields {
  const retrieval = parseRetrievalConfig(retrievalConfig)
  const ingest = parseIngestConfig(ingestConfig)
  return {
    retrieval: retrieval.params,
    ingest: ingest.params,
    retrievalExtra: retrieval.extra,
    ingestExtra: ingest.extra
  }
}

/**
 * 新建弹窗的参数初值。
 *
 * <p>与「库里 JSON 缺失时的解析默认值」刻意分开：解析默认值（{@code ragKbParams}）必须与后端
 * {@code RetrievalConfigResolver.defaults()} 一致，不能因为界面偏好去改它；但**新建**是一个未落库的
 * 新库，产品口径是「查询改写」「本库重排」两个开关默认打开，让用户先带着更强的检索能力跑起来，
 * 不合适再手动关。因此这里只覆盖表单初值，保存时照常写进 retrieval_config。
 *
 * @return 新建表单的参数部分（检索开关已置开，入库用平台默认）
 */
function buildNewParamFields(): KbParamFields {
  return {
    retrieval: { ...defaultRetrievalParams(), rerankEnabled: true, rewriteEnabled: true },
    ingest: defaultIngestParams(),
    // 新库没有历史 JSON，不存在需要带回的其它键
    retrievalExtra: {},
    ingestExtra: {}
  }
}

/** 维度初始占位：0 表示「还没选模型 / 模型未返回」，提交前必须 > 0 */
const UNKNOWN_DIMENSION = 0

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
  /** 当前 Tab：base / ingest / retrieval；每次打开弹窗重置回基础信息 */
  tab: 'base',
  form: {
    name: '',
    description: '',
    embeddingModelId: undefined as number | undefined,
    vectorDimension: UNKNOWN_DIMENSION,
    rerankModelId: undefined as number | undefined,
    isolationMode: 'PHYSICAL',
    visibility: 'PRIVATE',
    ...buildNewParamFields()
  }
})

const edit = reactive({
  open: false,
  saving: false,
  id: 0,
  embeddingLabel: '',
  /** 当前 Tab：base / ingest / retrieval；每次打开弹窗重置回基础信息 */
  tab: 'base',
  form: {
    name: '',
    description: '',
    visibility: 'PRIVATE',
    rerankModelId: undefined as number | undefined,
    ...buildParamFields()
  }
})

/**
 * 编辑弹窗的脏检查：只比表单里填写 / 选择类字段。
 * Embedding 已锁定（只展示不改），id / embeddingLabel 是纯展示值，都不纳入。
 */
const editDirtyTracker = useObjectDirty(() => edit.form)
const editDirty = editDirtyTracker.isDirty

/**
 * 编辑弹窗关闭前拦截：允许点遮罩 / ESC 关闭，但有未保存修改时先确认，避免静默丢改动。
 *
 * @param done Element Plus 的关闭回调；调用即真正关闭
 */
async function beforeEditClose(done: () => void) {
  if (!editDirty.value) {
    done()
    return
  }
  if (await confirmDiscardUnsaved('知识库修改')) {
    done()
  }
}

/**
 * 维度提示文案。维度有三个来源，按可信度取第一个命中的：
 * ① 模型连通测试返回的 embeddingDim（模型真实回答的维度）；
 * ② 模型实例扩展 JSON 里登记的 dimension；
 * ③ 都没有 → 视为未就绪（下拉里本就点不进来）。
 */
const dimensionHint = computed(() => {
  if (!create.form.embeddingModelId) {
    return '选择 Embedding 模型后自动带出'
  }
  const target = embeddingOptions.value.find((item) => item.id === create.form.embeddingModelId)
  if (probedDimension(target)) {
    return '由模型连通测试返回；建库后锁定，不可修改'
  }
  if (parseDimension(target?.extraJson)) {
    return '取自模型实例的扩展 JSON；建库后锁定，不可修改'
  }
  return '该实例还没有可用维度，请先在「模型管理」页做一次连通测试'
})

/** 维度是否已确定；未确定时不允许提交，避免建出写不进向量的集合 */
const dimensionResolved = computed(() => create.form.vectorDimension > 0)

/** 维度展示文本：未确定时给明确占位，而不是显示 0 */
const dimensionText = computed(() => (dimensionResolved.value ? String(create.form.vectorDimension) : '未获取'))

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
 * 选中 Embedding 后计算维度，供建库使用。
 *
 * <p>维度已改为只读，必须算准：优先用模型连通测试返回的 embeddingDim（真实返回），
 * 其次用扩展 JSON 登记的 dimension；两者都没有就留 0，由保存前校验拦下并提示去测连通性。
 *
 * @param id 选中的模型实例 id
 */
function onEmbeddingChange(id: number) {
  const target = embeddingOptions.value.find((item) => item.id === id)
  create.form.vectorDimension = probedDimension(target) ?? parseDimension(target?.extraJson) ?? 0
}

/**
 * 读取模型连通测试返回的维度快照（仅 EMBEDDING 用途有值）。
 *
 * @param instance 模型实例行，可空
 * @return 合法维度或 undefined
 */
function probedDimension(instance?: InstanceRow) {
  const value = instance?.embeddingDim
  return typeof value === 'number' && value > 0 ? value : undefined
}

/**
 * Embedding 选项文案。未就绪的实例仍显示，但在文案里标注去模型管理页测通，
 * 配合 disabled 让「看得到、点不了」这件事自解释。
 * 未测过与测试失败共用同一句标注：两者在可用性上是同一档，不值得再分两种说法。
 *
 * @param instance 模型实例行
 * @return 下拉显示文案
 */
function embeddingOptionLabel(instance: InstanceRow) {
  const base = `${instance.displayName}（${instance.modelId}）`
  return isEmbeddingReady(instance) ? base : `${base} · 未连通，请到模型管理页测通`
}

/**
 * Embedding 实例是否可选。沿用现网已有的判断，不新造状态位：
 * <ol>
 *   <li><b>已连通</b>：最近一次连通测试成功，即 {@code lastTestOk === 1}。
 *       与模型管理页「设为平台级」的前置条件同源。</li>
 *   <li><b>维度就绪</b>：维度能解析出来 —— 成功快照 {@code embeddingDim} 优先，其次扩展 JSON 登记的 dimension，
 *       与 {@link parseDimension}、后端 {@code RagModelLookup#resolveDimension} 同口径。</li>
 * </ol>
 * <b>测试失败（{@code lastTestOk === 0}）与从未测过（空）同等处理，一律不可选</b>：
 * 后端失败时不会覆盖上一次成功的 {@code embeddingDim} 快照，若只按「有维度」放行，
 * 就会把刚测失败的实例当成可用，建出连不上的集合。所以这里必须再卡一道连通结果。
 * <p>
 * 未就绪的仍列在下拉里但置灰；本函数不发请求、不做探活。
 *
 * @param instance 模型实例行
 * @return true 表示可选
 */
function isEmbeddingReady(instance: InstanceRow) {
  // 未测过与测试失败都不放行：两者的可判定结果都是「当前不可用」
  if (instance.lastTestOk !== 1) {
    return false
  }
  return (probedDimension(instance) ?? parseDimension(instance.extraJson)) != null
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
  // 每次打开都回到基础信息，参数重置为「新建」初值（改写 / 重排默认打开）
  create.tab = 'base'
  create.form = {
    name: '',
    description: '',
    embeddingModelId: undefined,
    vectorDimension: UNKNOWN_DIMENSION,
    rerankModelId: undefined,
    isolationMode: 'PHYSICAL',
    visibility: 'PRIVATE',
    ...buildNewParamFields()
  }
  create.open = true
  if (!hasOptions.value) {
    ElMessage.warning('还没有可用的 EMBEDDING 模型实例，请先到「模型管理」创建')
    return
  }
  // 有实例但一个都不可选（未测过 / 测试失败 / 无维度）：只提示一次，下拉里仍可见并置灰
  if (!embeddingOptions.value.some(isEmbeddingReady)) {
    ElMessage.warning('现有 EMBEDDING 实例都还没连通测试成功，请先在「模型管理」测通后再建库')
  }
}

/**
 * 按 Tab 归类参数校验错误：检索参数错误归「检索与重排」，入库参数错误归「入库与切片」。
 * 复用 {@link validateKbParams}，另一侧传默认值（必然合法），不另写一套区间。
 *
 * @param retrieval 检索参数
 * @param ingest    入库参数
 * @return 两块各自的错误文案列表
 */
function splitParamErrors(retrieval: RetrievalParams, ingest: IngestParams) {
  return {
    retrieval: validateKbParams(retrieval, defaultIngestParams()),
    ingest: validateKbParams(defaultRetrievalParams(), ingest)
  }
}

/**
 * 保存前的参数校验：有错就切到出错的那个 Tab 再提示，避免提示落在看不见的页面上。
 *
 * @param target 承载 tab 与参数的弹窗状态（新建或编辑）
 * @return true 表示通过
 */
function checkParams(target: { tab: string; form: KbParamFields }) {
  const errors = splitParamErrors(target.form.retrieval, target.form.ingest)
  if (errors.retrieval.length) {
    target.tab = 'retrieval'
    ElMessage.warning(errors.retrieval[0])
    return false
  }
  if (errors.ingest.length) {
    target.tab = 'ingest'
    ElMessage.warning(errors.ingest[0])
    return false
  }
  return true
}

async function saveCreate() {
  if (!create.form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  // 描述必填：运行时靠它挑库，空描述会让该库在多库场景下无法参与相关性判断
  if (!create.form.description.trim()) {
    ElMessage.warning('请填写描述')
    return
  }
  if (!create.form.embeddingModelId) {
    ElMessage.warning('请选择 Embedding 模型')
    return
  }
  // 维度必须由模型侧给出：宁可拦下，也不猜一个值建出写不进向量的集合
  if (!dimensionResolved.value) {
    ElMessage.warning('所选 Embedding 实例还没有可用维度，请改选已连通的实例')
    return
  }
  // 参数区间与后端解析器一致：超界直接拦下，避免后端静默夹紧后与界面显示不一致
  if (!checkParams(create)) {
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
      // 没切到某个 Tab 也照常提交当前值（默认或已回填），不会写回空对象
      retrievalConfig: buildRetrievalConfig(create.form.retrieval, create.form.retrievalExtra),
      ingestConfig: buildIngestConfig(create.form.ingest, create.form.ingestExtra)
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
  // 每次打开都回到基础信息；已回填的参数在切 Tab 后仍在
  edit.tab = 'base'
  edit.form = {
    name: row.name,
    description: row.description || '',
    visibility: row.visibility,
    rerankModelId: row.rerankModelId ?? undefined,
    // 已有 JSON 按表单回填；缺键显示默认值，其它合法键留在 extra 里保存时带回
    ...buildParamFields(row.retrievalConfig, row.ingestConfig)
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
  // 描述必填：老库允许先保持空，但一旦编辑保存就要补齐
  if (!edit.form.description.trim()) {
    ElMessage.warning('请填写描述')
    return
  }
  if (!checkParams(edit)) {
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
      // 没切到某个 Tab 也用当前值重建 JSON，保持与界面显示一致
      retrievalConfig: buildRetrievalConfig(edit.form.retrieval, edit.form.retrievalExtra),
      ingestConfig: buildIngestConfig(edit.form.ingest, edit.form.ingestExtra)
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
  align-items: center;
  line-height: 1.5;
}

/* 维度已改为只读展示，固定宽度避免撑满整行 */
.dimension-input {
  width: 160px;
}

/* 维度未获取时用警示色，和普通灰色说明区分 */
.hint-error {
  color: var(--el-color-warning, #e6a23c);
}

.muted {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.hint {
  margin-top: 4px;
  color: var(--ah-text-muted, #6b7280);
  font-size: 12px;
  line-height: 1.5;
}

/* 基础信息里 Embedding / Rerank 同排两列；窄屏单列堆叠，Tab 与表单都不横向溢出 */
.base-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

@media (max-width: 680px) {
  .base-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 编辑态 Embedding 已锁定：按正文色展示，不用禁用输入框的灰，避免看着像坏了 */
.locked-text {
  color: var(--ah-text, #1f2937);
  font-size: 13px;
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

.tag-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}
</style>

<!--
  弹窗本体由 Element Plus teleport 到 body，不在本组件的 scoped 作用域内，
  所以这两条用非 scoped 样式；选择器都带 kb-param-dialog 前缀，不会外溢到其它弹窗。
-->
<style>
/*
  Tab 内容长短不一（检索与重排最长），限制体高并内部滚动，底部取消 / 保存始终留在视口内；
  Tab 条本身放在滚动区里，内容长时一起滚，不会把页脚顶出去。
*/
.kb-param-dialog .el-dialog__body {
  max-height: 68vh;
  overflow-y: auto;
}

/* 窄屏兜底：760px 在小屏会溢出，退化为近满宽；两栏 / 三栏由组件内断点改成单列 */
@media (max-width: 820px) {
  .kb-param-dialog.el-dialog {
    width: 94vw !important;
  }
}
</style>
