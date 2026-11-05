<template>
  <div class="page prompt-center">
    <!-- 页头只放标题与说明；语言切换和刷新属于全页维度，放 #extra -->
    <PageHeader
      title="提示词模板库"
      description="平台全局约束（L1）与用途模板（L2）合并管理：左侧选对象 → 中间看版本 → 右侧抽屉改草稿。"
    >
      <template #extra>
        <!-- ============ 语言切换隐藏，默认中文 ============ -->
        <!-- <el-select v-model="locale" class="locale-select" :disabled="loading" @change="onLocaleChange">
          <el-option label="zh-CN" value="zh-CN" />
          <el-option label="en-US" value="en-US" />
        </el-select> -->
        <el-button class="btn-reset" :loading="loading" @click="reloadAll">
          <el-icon class="btn-icon"><RefreshRight /></el-icon>
          刷新
        </el-button>
      </template>
    </PageHeader>

    <div class="center-body">
      <!-- ============ 左栏：对象导航 ============ -->
      <aside class="rail">
        <!-- L1 只有一条系统级对象，红系描边 + 系统级 Tag 与下方 L2 区分 -->
        <button
          type="button"
          class="obj-card obj-l1"
          :class="{ 'is-selected': selectedKind === 'L1' }"
          @click="selectL1"
        >
          <span class="obj-head">
            <span class="obj-title">平台全局约束 (L1)</span>
            <el-tag type="danger" size="small" effect="dark">系统级</el-tag>
          </span>
          <span class="obj-meta">
            <template v-if="l1CardRow">
              <span class="obj-ver">v{{ l1CardRow.version }}</span>
              <el-tag :type="l1CardRow.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                {{ l1CardRow.status === 1 ? '已发布' : '草稿' }}
              </el-tag>
              <span v-if="l1CardRow.isActive === 1" class="obj-sub">生效中</span>
            </template>
            <span v-else class="obj-ver muted">暂无版本</span>
          </span>
        </button>

        <!-- 明显空白：L1 是系统级单对象，与下方 L2 列表在语义上分层 -->
        <div class="rail-gap" aria-hidden="true">
          <span class="rail-gap-label">L2 用途模板</span>
        </div>

        <div class="rail-head">
          <span class="rail-count">固定 {{ templates.length }} 个用途槽位</span>
        </div>

        <div v-loading="loading" class="rail-list">
          <!--
            固定用途槽位：每个 purpose 恒一张卡，不再按 template_key 铺多卡。
            空槽也占位，点进去开的是该用途下唯一约定 key 的草稿。
          -->
          <button
            v-for="item in templates"
            :key="item.purpose"
            type="button"
            class="obj-card obj-l2"
            :class="{ 'is-selected': selectedKind === 'L2' && selectedPurpose === item.purpose }"
            @click="selectPurpose(item.purpose)"
          >
            <span class="obj-head">
              <span class="obj-title">{{ purposeLabel(item.purpose) }}</span>
              <el-tag :type="purposeType(item.purpose)" size="small" effect="plain">{{ item.purpose }}</el-tag>
            </span>
            <span class="obj-meta">
              <!-- 有生效版显示生效版；否则显示最新版并带真实状态；空槽给明确空态 -->
              <template v-if="item.versionCount > 0">
                <span class="obj-ver">
                  {{ item.activeVersion ? `v${item.activeVersion} 生效` : `v${item.latestVersion ?? 1}` }}
                </span>
                <el-tag
                  v-if="!item.activeVersion"
                  :type="item.latestStatus === 1 ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                >
                  {{ item.latestStatus === 1 ? '已发布' : '草稿' }}
                </el-tag>
                <span class="obj-sub" :title="item.templateKey">{{ item.templateKey }}</span>
              </template>
              <span v-else class="obj-ver muted">未配置</span>
            </span>
          </button>
        </div>
      </aside>

      <!-- ============ 中栏：版本列表 ============ -->
      <section class="versions">
        <el-card class="table-card" shadow="never">
          <template #header>
            <div class="versions-head">
              <div class="versions-title">
                <span class="versions-name">{{ objectTitle }}</span>
                <el-tag v-if="objectPurpose" :type="purposeType(objectPurpose)" size="small" effect="plain">
                  {{ objectPurpose }}
                </el-tag>
                <el-tag v-if="selectedKind === 'L1'" type="danger" size="small" effect="dark">系统级</el-tag>
                <span class="versions-sub">{{ versions.length }} 个版本 · {{ locale }}</span>
              </div>
              <el-button
                v-if="canCreateDraft"
                type="primary"
                size="small"
                :loading="versionsLoading"
                @click="onCreateDraft"
              >
                <el-icon class="btn-icon"><Plus /></el-icon>
                {{ createDraftLabel }}
              </el-button>
            </div>
          </template>

          <el-table
            v-loading="versionsLoading"
            :data="versions"
            highlight-current-row
            :row-class-name="rowClassName"
            :empty-text="emptyText"
            @row-click="onRowClick"
          >
            <el-table-column v-bind="indexColumnProps" />
            <el-table-column label="版本" width="90" align="center">
              <template #default="{ row }">v{{ row.version }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                  {{ row.status === 1 ? '已发布' : '草稿' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="生效" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="isRowActive(row)" type="success" size="small" effect="dark">生效中</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="updateTime" label="更新时间" min-width="170" align="center">
              <template #default="{ row }">{{ row.updateTime || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="230" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="openEditor(row)">
                  {{ row.status === 1 ? '查看' : '编辑' }}
                </el-button>
                <el-button
                  v-if="row.status === 0 && publishPerm"
                  link
                  type="primary"
                  @click.stop="onPublish(row)"
                >
                  发布
                </el-button>
                <el-button
                  v-if="row.status === 1 && editPerm"
                  link
                  type="primary"
                  @click.stop="openNewVersion(row)"
                >
                  新版本
                </el-button>
                <el-button link type="primary" @click.stop="openPreview(row)">预览</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </section>
    </div>

    <!-- ============ 右抽屉：编辑 ============ -->
    <el-drawer
      v-model="drawer.open"
      :title="drawerTitle"
      size="760px"
      destroy-on-close
      :before-close="beforeDrawerClose"
    >
      <!-- L1：只有语言与正文，按 locale 版本发布 -->
      <el-form v-if="selectedKind === 'L1'" label-width="90px" class="drawer-form">
        <el-form-item label="语言">
          <!-- 新草稿可选语言；一旦落库，版本行的 locale 固定，不可再改 -->
          <el-select v-model="l1Form.locale" :disabled="!!l1Form.id || drawer.readonly" style="width: 100%">
            <el-option label="zh-CN" value="zh-CN" />
            <el-option label="en-US" value="en-US" />
          </el-select>
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input
            v-model="l1Form.content"
            type="textarea"
            :rows="18"
            :disabled="drawer.readonly"
            placeholder="平台红线 / 语言强制 / 脱敏等约束文案"
          />
        </el-form-item>
        <p v-if="drawer.readonly" class="form-tip">已发布版本只读；改文案请点「新建草稿」开下一版。</p>
      </el-form>

      <!-- L2：主信息 + 段落列表 -->
      <el-form v-else label-width="90px" class="drawer-form">
        <!-- 用途由左栏槽位决定，抽屉里不给改：同用途只允许一个 key，改了就会造出第二个 -->
        <el-form-item label="用途">
          <el-input :model-value="l2Form.purpose" disabled />
        </el-form-item>
        <el-form-item label="模板键">
          <el-input
            :model-value="l2Form.templateKey"
            disabled
            placeholder="由用途槽位决定"
          />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="l2Form.name" :disabled="drawer.readonly" placeholder="模板中文名" />
        </el-form-item>
        <el-form-item label="语言">
          <el-input :model-value="l2Form.locale" disabled />
        </el-form-item>
        <el-form-item label="协议补丁">
          <el-input v-model="l2Form.protocol" :disabled="drawer.readonly" placeholder="可空，如 OPENAI_COMPAT" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="l2Form.remark" :disabled="drawer.readonly" type="textarea" :rows="2" />
        </el-form-item>

        <div class="seg-head">
          <h3>段落</h3>
          <span class="seg-note">按 sort_no 升序拼接；锁定 Core 段不允许 Agent 覆盖</span>
        </div>

        <div v-if="!l2Form.segments.length" class="seg-empty">还没有段落，点下方「新增段落」开始</div>

        <div v-for="(seg, index) in l2Form.segments" :key="index" class="seg-card">
          <div class="seg-card-head">
            <div class="seg-card-left">
              <span class="seg-index">#{{ index + 1 }}</span>
              <el-tag v-if="seg.mutable === 1" type="primary" size="small" effect="plain">开放 Soft</el-tag>
              <el-tag v-else type="danger" size="small" effect="plain">锁定 Core</el-tag>
            </div>
            <div class="seg-card-right">
              <span class="seg-sort-label">sort_no</span>
              <el-input-number
                v-model="seg.sortNo"
                :disabled="drawer.readonly"
                :min="0"
                :controls="false"
                size="small"
                class="seg-sort"
              />
              <el-button
                v-if="!drawer.readonly"
                link
                type="danger"
                size="small"
                @click="removeSegment(index)"
              >
                删除
              </el-button>
            </div>
          </div>

          <el-form-item label="展示名" required class="seg-item">
            <el-input v-model="seg.displayName" :disabled="drawer.readonly" placeholder="如「角色设定」" />
          </el-form-item>
          <el-form-item label="seg_key" required class="seg-item">
            <el-input v-model="seg.segKey" :disabled="drawer.readonly" placeholder="如 role" />
          </el-form-item>
          <div class="seg-flags">
            <el-checkbox
              :model-value="seg.mutable === 1"
              :disabled="drawer.readonly"
              @change="(checked: boolean) => (seg.mutable = checked ? 1 : 0)"
            >
              允许覆盖（开放 Soft）
            </el-checkbox>
            <el-checkbox
              :model-value="seg.required === 1"
              :disabled="drawer.readonly"
              @change="(checked: boolean) => (seg.required = checked ? 1 : 0)"
            >
              必选段
            </el-checkbox>
          </div>
          <el-form-item label="正文" required class="seg-item">
            <el-input
              v-model="seg.content"
              :disabled="drawer.readonly"
              type="textarea"
              :rows="5"
              placeholder="段落正文"
            />
          </el-form-item>
          <p v-if="seg.mutable === 1" class="seg-footnote">允许 Agent 配置者覆盖</p>
        </div>

        <el-button v-if="!drawer.readonly" class="seg-add" @click="addSegment">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新增段落
        </el-button>
        <p v-if="drawer.readonly" class="form-tip">已发布版本只读；改文案请点「新版本」开下一稿。</p>
      </el-form>

      <template #footer>
        <el-button @click="requestCloseDrawer">关闭</el-button>
        <el-button @click="previewFromDrawer">预览</el-button>
        <!-- 未改任何字段时不提供保存：没有草稿可提交，避免「点了没反应」 -->
        <el-button
          v-if="canSaveDraft"
          type="primary"
          :loading="saving"
          :disabled="!drawerDirty"
          @click="onSave"
        >
          保存草稿
        </el-button>
        <el-button
          v-if="canPublishFromDrawer"
          type="success"
          :loading="publishing"
          @click="onPublishFromDrawer"
        >
          发布
        </el-button>
      </template>
    </el-drawer>

    <!-- ============ 预览：前端按 sort_no 拼接，不调模型、不接 Assembler ============ -->
    <el-dialog v-model="preview.open" :title="preview.title" width="760px" top="6vh">
      <div class="preview-toolbar">
        <el-checkbox v-if="preview.kind === 'L2'" v-model="preview.withL1" :disabled="!l1ActiveContent">
          附带当前生效 L1 前缀
        </el-checkbox>
        <span class="preview-tip">变量占位符原样展示，不做替换</span>
      </div>
      <pre class="preview-body">{{ previewText }}</pre>
      <template #footer>
        <el-button @click="preview.open = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致。 */
defineOptions({ name: 'PromptCenter' })
/**
 * 提示词管理端合成单页。所属域：prompt。
 *
 * 职责：把「平台约束（L1）」与「提示词模板（L2）」收敛到一个信息架构：
 * 左栏选对象 → 中栏看该对象全部版本 → 右抽屉编辑草稿/发布，另带前端拼接预览。
 *
 * 约束：
 * 1. 只消费既有 L1/L2 API + 一个左栏目录聚合接口；不接 PromptAssembler、不做变量替换、不调模型；
 * 2. 生效规则由后端发布逻辑保证（同 locale 的 L1、同 purpose+locale 的 L2 各至多一条生效），
 *    前端只负责展示「生效中」标识，不自行造第二套判定；
 * 3. Agent L3 覆盖 UI 不在本页，Soft 段仅标注「允许 Agent 配置者覆盖」。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getPromptCatalog,
  getTemplate,
  listConstraints,
  pageTemplates,
  publishConstraint,
  publishTemplate,
  saveConstraintDraft,
  saveTemplateDraft
} from '@/api/prompt'
import PageHeader from '@/components/PageHeader.vue'
import { useRowIndexColumn } from '@/composables/listIndex'
import { useFormDirty } from '@/composables/useFormDirty'
import { confirmDiscardUnsaved } from '@/utils/unsavedConfirm'
import { useUserStore } from '@/stores/user'
import type {
  PromptCatalog,
  PromptCatalogTemplate,
  PromptConstraint,
  PromptTemplate
} from '@/types/prompt'

/** L2 用途下拉，与后端 PromptPurpose 枚举同序 */
const PURPOSE_OPTIONS = ['CHAT', 'ROUTER', 'REWRITE', 'SUMMARY', 'MEMORY'] as const

/** 用途中文名；左栏卡标题用它，Tag 仍显示英文码，避免同名重复 */
const PURPOSE_LABELS: Record<string, string> = {
  CHAT: '对话',
  ROUTER: '路由',
  REWRITE: '改写',
  SUMMARY: '摘要',
  MEMORY: '记忆'
}

/**
 * 用途码取中文名，未登记时原样返回码本身。
 *
 * @param purpose 用途码
 * @return 展示名
 */
function purposeLabel(purpose: string) {
  return PURPOSE_LABELS[purpose] || purpose
}

/** 中栏统一行结构：L1/L2 都归一成这一份，表格与选中态复用一套逻辑 */
interface VersionRow {
  id: number
  version: number
  status: number
  isActive: number
  updateTime?: string
}

/** 抽屉内可编辑段落（不含主键，提交时整表替换） */
interface EditableSegment {
  segKey: string
  displayName: string
  sortNo: number
  mutable: number
  required: number
  content: string
}

const user = useUserStore()

const loading = ref(false)
const versionsLoading = ref(false)
const saving = ref(false)
const publishing = ref(false)

const locale = ref('zh-CN')

/** 左栏目录（L2 卡片来自这里，避免每个模板查一次列表） */
const catalog = ref<PromptCatalog | null>(null)
/** L1 版本（含正文）：中栏列表与预览都要用，因此常驻内存 */
const l1Versions = ref<PromptConstraint[]>([])

const selectedKind = ref<'L1' | 'L2'>('L1')
/** 左栏 L2 选中的用途槽位；空表示未选 L2。版本查询用槽位绑定的 key，不用用户自由输入 */
const selectedPurpose = ref('')
/** 中栏当前聚焦的版本行，仅用于高亮，不影响抽屉内容 */
const currentVersionId = ref(0)

const versions = ref<VersionRow[]>([])
/** 中栏版本表最左「序号」列；该列表按对象整体返回、不分页，故序号 = 行号 + 1 */
const { indexColumnProps } = useRowIndexColumn()

const drawer = reactive({ open: false, readonly: false })

/** L1 抽屉表单 */
const l1Form = reactive({
  id: undefined as number | undefined,
  status: 0,
  locale: 'zh-CN',
  content: ''
})

/** L2 抽屉表单 */
const l2Form = reactive({
  id: undefined as number | undefined,
  status: 0,
  templateKey: '',
  name: '',
  purpose: 'CHAT',
  locale: 'zh-CN',
  protocol: '',
  remark: '',
  segments: [] as EditableSegment[]
})

const preview = reactive({
  open: false,
  kind: 'L1' as 'L1' | 'L2',
  title: '预览',
  text: '',
  /** L2 预览时是否把当前生效 L1 拼在前面 */
  withL1: false
})

/**
 * 抽屉草稿快照。L1 只有正文；L2 含主信息与段落数组。
 * 纯展示字段（id、status、templateKey、purpose、locale）不纳入比较。
 */
interface DrawerL1Draft {
  kind: 'L1'
  content: string
}

interface DrawerL2Draft {
  kind: 'L2'
  name: string
  protocol: string
  remark: string
  segments: Array<{
    segKey: string
    displayName: string
    sortNo: number
    mutable: number
    required: number
    content: string
  }>
}

type DrawerDraft = DrawerL1Draft | DrawerL2Draft

/**
 * 按当前编辑对象（L1 / L2）取可编辑字段。
 *
 * @return 可稳定序列化的快照
 */
function drawerDraft(): DrawerDraft {
  if (selectedKind.value === 'L1') {
    return { kind: 'L1', content: l1Form.content }
  }
  return {
    kind: 'L2',
    name: l2Form.name,
    protocol: l2Form.protocol,
    remark: l2Form.remark,
    // 段落任一属性变化都算改动；顺序按 sortNo 语义由后端保证，这里保持数组原序
    segments: l2Form.segments.map((seg) => ({
      segKey: seg.segKey,
      displayName: seg.displayName,
      sortNo: seg.sortNo,
      mutable: seg.mutable,
      required: seg.required,
      content: seg.content
    }))
  }
}

/**
 * 抽屉脏检查：取消时把快照写回表单。
 * 只回写用户可编辑的字段，段落整体替换（草稿与已保存内容互不影响）。
 */
const drawerDirtyTracker = useFormDirty<DrawerDraft>(drawerDraft, (snap) => {
  if (snap.kind === 'L1') {
    l1Form.content = snap.content
    return
  }
  l2Form.name = snap.name
  l2Form.protocol = snap.protocol
  l2Form.remark = snap.remark
  l2Form.segments = snap.segments.map((seg) => ({ ...seg }))
})
const drawerDirty = drawerDirtyTracker.isDirty

/**
 * 关闭抽屉前的统一出口：有未保存草稿时先确认，避免点 × / 遮罩就静默丢稿。
 *
 * @return true 表示允许关闭
 */
async function beforeDrawerClose() {
  if (drawer.readonly || !drawerDirty.value) {
    return true
  }
  return confirmDiscardUnsaved('提示词草稿')
}

/** 页脚「关闭」按钮：走同一套拦截，保证与右上 × 行为一致。 */
async function requestCloseDrawer() {
  if (await beforeDrawerClose()) {
    drawer.open = false
  }
}

/** L1 卡片显示的版本：优先生效行（已发布且生效），其次最新（后端已按 version 倒序） */
const l1CardRow = computed(
  () =>
    l1Versions.value.find((item) => item.isActive === 1 && item.status === 1) ??
    l1Versions.value[0]
)

/** 当前生效 L1 正文，供 L2 预览可选前缀 */
const l1ActiveContent = computed(() => {
  const hit = l1Versions.value.find((item) => item.isActive === 1 && item.status === 1)
  return hit?.content ?? ''
})

/**
 * 左栏 L2 槽位：后端已按用途枚举返回固定顺序的一条一条，前端不再二次分组，
 * 只做一次防御性排序，保证枚举顺序稳定（后端漏排也不会乱）。
 */
const templates = computed<PromptCatalogTemplate[]>(() => {
  const rows = [...(catalog.value?.templates ?? [])]
  return rows.sort((left, right) => {
    const leftIndex = PURPOSE_OPTIONS.indexOf(left.purpose as (typeof PURPOSE_OPTIONS)[number])
    const rightIndex = PURPOSE_OPTIONS.indexOf(right.purpose as (typeof PURPOSE_OPTIONS)[number])
    // 未登记用途排到末尾，避免脏数据把已知用途挤出首屏
    const leftRank = leftIndex === -1 ? PURPOSE_OPTIONS.length : leftIndex
    const rightRank = rightIndex === -1 ? PURPOSE_OPTIONS.length : rightIndex
    return leftRank - rightRank
  })
})

/** 当前选中的用途槽位摘要；空槽也有值（versionCount=0） */
const selectedSlot = computed(() =>
  templates.value.find((item) => item.purpose === selectedPurpose.value)
)

/** 当前槽位绑定的业务键：生效/最新版所属 key；空槽时后端给约定键（用途名） */
const selectedKey = computed(() => selectedSlot.value?.templateKey ?? selectedPurpose.value)

const objectTitle = computed(() => {
  if (selectedKind.value === 'L1') {
    return '平台全局约束 (L1)'
  }
  return selectedPurpose.value ? `${purposeLabel(selectedPurpose.value)}模板（${selectedPurpose.value}）` : 'L2 用途模板'
})

const objectPurpose = computed(() =>
  selectedKind.value === 'L2' ? selectedPurpose.value : ''
)

const drawerTitle = computed(() => {
  if (selectedKind.value === 'L1') {
    if (!l1Form.id) return '新建平台约束草稿'
    return `${drawer.readonly ? '查看' : '编辑'}平台约束 · v${versionOf(l1Form.id)}`
  }
  // L2 标题用用途，键只是该用途绑定的实现细节，不再作为标题主体
  const purpose = purposeLabel(l2Form.purpose || selectedPurpose.value)
  if (!l2Form.id) return `新建 ${purpose} 草稿 · ${l2Form.templateKey || '未命名'}`
  return `${drawer.readonly ? '查看' : '编辑'} ${purpose} · ${l2Form.templateKey} v${versionOf(l2Form.id)}`
})

/** 编辑类权限：L1 用 l1:edit，L2 用 template:add/edit */
const editPerm = computed(() =>
  selectedKind.value === 'L1'
    ? user.hasPerm('prompt:l1:edit')
    : user.hasPerm('prompt:template:add') || user.hasPerm('prompt:template:edit')
)

const publishPerm = computed(() =>
  selectedKind.value === 'L1' ? user.hasPerm('prompt:l1:publish') : user.hasPerm('prompt:template:publish')
)

/**
 * 中栏右上的开稿按钮：L1 与 L2 槽位都要有。
 * L2 只在该用途尚无草稿时出现，避免和行内「新版本」重复同一个动作；
 * 槽位已发布走行内「新版本」，槽位为空走这里的「新建草稿」。
 */
const canCreateDraft = computed(() => {
  if (!editPerm.value) {
    return false
  }
  if (selectedKind.value === 'L1') {
    return true
  }
  if (!selectedPurpose.value) {
    return false
  }
  return !versions.value.some((row) => row.status === 0)
})

/** 中栏按钮文案：L2 空槽是首稿，已有版本时开下一版；L1 永远是「新建草稿」 */
const createDraftLabel = computed(() => {
  if (selectedKind.value === 'L1') {
    return '新建草稿'
  }
  return versions.value.length ? '新版本' : '新建草稿'
})

/** 中栏空态文案：空槽要明确告诉用户「点按钮开首稿」，不要只写「暂无版本」 */
const emptyText = computed(() => {
  if (selectedKind.value === 'L2' && selectedPurpose.value && !versions.value.length) {
    const label = purposeLabel(selectedPurpose.value)
    return `该用途还没有版本，点右上「新建草稿」配置「${label}」模板`
  }
  return '暂无版本'
})

const canSaveDraft = computed(() => !drawer.readonly && editPerm.value)

const canPublishFromDrawer = computed(
  () => !!draftIdInDrawer() && publishPerm.value && !drawer.readonly
)

/**
 * 用途对应的 Tag 颜色，保证左栏与中栏同色可辨。
 *
 * @param purpose 用途码
 * @return el-tag 的 type
 */
function purposeType(purpose?: string) {
  switch (purpose) {
    case 'CHAT':
      return 'primary'
    case 'ROUTER':
      return 'warning'
    case 'REWRITE':
      return 'success'
    case 'SUMMARY':
      return 'info'
    case 'MEMORY':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 版本行是否「生效中」：与后端口径一致，需 is_active=1 且已发布。
 *
 * @param row 版本行
 * @return true 表示当前生效
 */
function isRowActive(row: VersionRow) {
  return row.isActive === 1 && row.status === 1
}

/**
 * 表格行高亮：只给焦点行加类名，避免整表斑马纹干扰「生效唯一」的视觉。
 *
 * @param param Element Plus 的行信息
 * @return 类名
 */
function rowClassName({ row }: { row: VersionRow }) {
  const classes: string[] = []
  if (row.id === currentVersionId.value) {
    classes.push('is-focus-row')
  }
  if (isRowActive(row)) {
    classes.push('is-active-row')
  }
  return classes.join(' ')
}

/**
 * 从当前中栏数据里取某版本号，找不到返回空串。
 *
 * @param id 版本主键
 * @return 版本号文本
 */
function versionOf(id?: number) {
  if (!id) return ''
  return String(versions.value.find((item) => item.id === id)?.version ?? '')
}

/** 抽屉当前是否持有一份可发布的草稿主键 */
function draftIdInDrawer() {
  if (selectedKind.value === 'L1') {
    return l1Form.id && l1Form.status === 0 ? l1Form.id : undefined
  }
  return l2Form.id && l2Form.status === 0 ? l2Form.id : undefined
}

/**
 * 首屏与切语言：目录 + L1 版本一起拉，随后默认选中 L1。
 */
async function reloadAll() {
  loading.value = true
  try {
    const [catalogResp, l1Resp] = await Promise.all([
      getPromptCatalog({ locale: locale.value }),
      listConstraints({ locale: locale.value })
    ])
    catalog.value = catalogResp.data.data
    l1Versions.value = l1Resp.data.data || []
    // 目录是固定用途槽位，恒有 5 条；只有后端异常返回空才回落 L1
    if (selectedKind.value === 'L2' && !selectedSlot.value) {
      selectedKind.value = 'L1'
      selectedPurpose.value = ''
    }
    if (selectedKind.value === 'L1') {
      applyL1Versions()
    } else if (selectedSlot.value && selectedSlot.value.versionCount > 0) {
      await loadL2Versions(selectedKey.value)
    } else {
      // 空槽没有版本可查，别拿约定键去打一次注定为空的请求
      versions.value = []
      currentVersionId.value = 0
    }
  } finally {
    loading.value = false
  }
}

/** 语言切换：清掉选中态重新聚焦 L1，避免看到另一语言的历史版本 */
async function onLocaleChange() {
  selectedKind.value = 'L1'
  selectedPurpose.value = ''
  drawer.open = false
  await reloadAll()
}

/** 选中 L1：版本直接用常驻的 l1Versions，不再请求 */
function selectL1() {
  // 换对象才收抽屉：抽屉内容跟随 selectedKind，不关会看到上一个对象的表单
  if (selectedKind.value !== 'L1') {
    drawer.open = false
  }
  selectedKind.value = 'L1'
  selectedPurpose.value = ''
  applyL1Versions()
}

/**
 * L1 版本归一成中栏行，并按规则聚焦：优先指定主键，其次生效行，再次最新草稿。
 *
 * @param focusId 期望聚焦的版本主键；为空则按默认规则取
 */
function applyL1Versions(focusId?: number) {
  versions.value = l1Versions.value.map((item) => ({
    id: item.id,
    version: item.version,
    status: item.status,
    isActive: item.isActive,
    updateTime: item.updateTime
  }))
  if (focusId && versions.value.some((row) => row.id === focusId)) {
    currentVersionId.value = focusId
    return
  }
  focusDefault()
}

/**
 * 选中 L2 用途槽位：拉该槽位绑定 key 在当前 locale 下的全部版本。
 * 空槽也能选，中栏会展示空态，由「新建草稿」按约定键开首稿。
 *
 * @param purpose 用途码
 */
async function selectPurpose(purpose: string) {
  // 只有换对象才收抽屉，重复点同一个槽位不影响正在编辑的表单
  if (selectedKind.value !== 'L2' || selectedPurpose.value !== purpose) {
    drawer.open = false
  }
  selectedKind.value = 'L2'
  selectedPurpose.value = purpose
  const slot = templates.value.find((item) => item.purpose === purpose)
  // 空槽没有版本可查，直接清空中栏，别拿约定键去打一次空请求
  if (!slot || slot.versionCount === 0) {
    versions.value = []
    currentVersionId.value = 0
    return
  }
  await loadL2Versions(slot.templateKey)
}

/**
 * 加载某模板的全部版本。
 * 用精确 templateKey 过滤而不是模糊 keyword，避免同前缀模板串进列表。
 *
 * @param key 模板业务键
 */
async function loadL2Versions(key: string) {
  if (!key) {
    versions.value = []
    return
  }
  versionsLoading.value = true
  try {
    const { data } = await pageTemplates({
      current: 1,
      size: 100,
      templateKey: key,
      locale: locale.value
    })
    // 版本号倒序：最新在上，和历史 L1 列表口径一致
    versions.value = (data.data.records || [])
      .map((item: PromptTemplate) => ({
        id: item.id,
        version: item.version,
        status: item.status,
        isActive: item.isActive,
        updateTime: item.updateTime
      }))
      .sort((left, right) => right.version - left.version)
    focusDefault()
  } finally {
    versionsLoading.value = false
  }
}

/** 中栏默认焦点：生效行 → 版本号最大的草稿 → 第一行 */
function focusDefault() {
  const active = versions.value.find((row) => isRowActive(row))
  if (active) {
    currentVersionId.value = active.id
    return
  }
  const drafts = versions.value.filter((row) => row.status === 0)
  if (drafts.length) {
    currentVersionId.value = drafts.reduce((best, row) => (row.version > best.version ? row : best)).id
    return
  }
  currentVersionId.value = versions.value[0]?.id ?? 0
}

/**
 * 点行只改焦点，不开抽屉；开抽屉必须显式点「编辑」，避免误触。
 *
 * @param row 版本行
 */
function onRowClick(row: VersionRow) {
  currentVersionId.value = row.id
}

/**
 * 编辑或查看某个版本。
 * L1 的正文已在内存里，L2 需要拉含段落的详情。
 *
 * @param row 版本行
 */
async function openEditor(row: VersionRow) {
  currentVersionId.value = row.id
  if (selectedKind.value === 'L1') {
    const detail = l1Versions.value.find((item) => item.id === row.id)
    if (!detail) return
    l1Form.id = detail.id
    l1Form.status = detail.status
    l1Form.locale = detail.locale
    l1Form.content = detail.content || ''
    drawer.readonly = detail.status === 1
    drawer.open = true
    markDrawerClean()
    return
  }
  const detail = await fetchTemplateDetail(row.id)
  if (!detail) return
  fillL2Form(detail)
  l2Form.id = detail.id
  l2Form.status = detail.status
  drawer.readonly = detail.status === 1
  drawer.open = true
  markDrawerClean()
}

/**
 * 抽屉打开后把当前值定为干净基线。
 * 必须在表单回填之后调用，否则打开弹窗就会被判成「有未保存更改」。
 */
function markDrawerClean() {
  drawerDirtyTracker.markClean()
}

/**
 * 拉 L2 详情（含段落），失败时提示并返回 null。
 *
 * @param id 版本主键
 * @return 详情或 null
 */
async function fetchTemplateDetail(id: number): Promise<PromptTemplate | null> {
  try {
    const { data } = await getTemplate(id)
    return data.data
  } catch {
    ElMessage.error('模板详情加载失败')
    return null
  }
}

/**
 * 详情回填抽屉表单；段落按 sort_no 升序展示。
 *
 * @param detail 模板详情
 */
function fillL2Form(detail: PromptTemplate) {
  l2Form.templateKey = detail.templateKey
  l2Form.name = detail.name
  l2Form.purpose = detail.purpose
  l2Form.locale = detail.locale
  l2Form.protocol = detail.protocol || ''
  l2Form.remark = detail.remark || ''
  l2Form.segments = [...(detail.segments || [])]
    .sort((left, right) => left.sortNo - right.sortNo)
    .map((item) => ({
      segKey: item.segKey,
      displayName: item.displayName,
      sortNo: item.sortNo,
      mutable: item.mutable,
      required: item.required,
      content: item.content
    }))
}

/**
 * 中栏右上开稿按钮。
 * L1：复制当前生效正文开下一稿（L1 无多实例概念，只能开版本）。
 * L2：按用途槽位开稿——槽位已有版本时复制最新版开下一稿，空槽时用约定键（用途名）建首稿。
 */
async function onCreateDraft() {
  if (selectedKind.value === 'L1') {
    const base = l1Versions.value.find((item) => item.isActive === 1) ?? l1Versions.value[0]
    l1Form.id = undefined
    l1Form.status = 0
    l1Form.locale = locale.value
    l1Form.content = base?.content ?? ''
    drawer.readonly = false
    drawer.open = true
    markDrawerClean()
    return
  }

  // L2：槽位为空直接开首稿；有版本时复制最新版内容（含段落），键与用途固定不变
  const focusRow = versions.value.find((row) => row.id === currentVersionId.value) ?? versions.value[0]
  if (focusRow) {
    const detail = await fetchTemplateDetail(focusRow.id)
    if (!detail) return
    fillL2Form(detail)
    l2Form.id = undefined
    l2Form.status = 0
  } else {
    resetL2Form(selectedPurpose.value, selectedKey.value)
  }
  drawer.readonly = false
  drawer.open = true
  markDrawerClean()
}

/**
 * 从已发布版本开下一稿（行内「新版本」）。
 * L1 无模板详情接口，直接复制该版本正文；L2 拉含段落的详情再清掉主键。
 *
 * @param row 已发布版本行
 */
async function openNewVersion(row: VersionRow) {
  if (selectedKind.value === 'L1') {
    const detail = l1Versions.value.find((item) => item.id === row.id)
    l1Form.id = undefined
    l1Form.status = 0
    l1Form.locale = detail?.locale ?? locale.value
    l1Form.content = detail?.content ?? ''
    drawer.readonly = false
    drawer.open = true
    markDrawerClean()
    return
  }
  const detail = await fetchTemplateDetail(row.id)
  if (!detail) return
  fillL2Form(detail)
  l2Form.id = undefined
  l2Form.status = 0
  drawer.readonly = false
  drawer.open = true
  markDrawerClean()
}

/**
 * 重置 L2 表单到新建状态，并放一个空段落占位。
 * 用途与模板键由槽位决定，不允许在抽屉里另造同用途第二个 key。
 *
 * @param purpose 槽位用途码；空则回落 CHAT
 * @param key     槽位绑定的业务键；空则用用途名作为约定键
 */
function resetL2Form(purpose?: string, key?: string) {
  const resolvedPurpose = purpose || 'CHAT'
  l2Form.id = undefined
  l2Form.status = 0
  l2Form.templateKey = key || resolvedPurpose
  // 新建时给一个可读默认名（如「对话模板」），省得空着提交被拦
  l2Form.name = `${purposeLabel(resolvedPurpose)}模板`
  l2Form.purpose = resolvedPurpose
  l2Form.locale = locale.value
  l2Form.protocol = ''
  l2Form.remark = ''
  l2Form.segments = []
  addSegment()
}

/**
 * 追加一个段落：sort_no 取当前最大值 +1，避免与已有段撞号。
 */
function addSegment() {
  const maxSort = l2Form.segments.reduce((max, item) => (item.sortNo > max ? item.sortNo : max), -1)
  l2Form.segments.push({
    segKey: '',
    displayName: '',
    sortNo: maxSort + 1,
    mutable: 0,
    required: 1,
    content: ''
  })
}

/**
 * 删除段落。
 *
 * @param index 段落下标
 */
function removeSegment(index: number) {
  l2Form.segments.splice(index, 1)
}

/**
 * 保存草稿：L1/L2 共用入口，按当前选中对象分发。
 */
async function onSave() {
  if (selectedKind.value === 'L1') {
    if (!l1Form.content.trim()) {
      ElMessage.warning('正文不能为空')
      return
    }
    saving.value = true
    try {
      const { data } = await saveConstraintDraft({
        id: l1Form.id,
        locale: l1Form.id ? undefined : l1Form.locale,
        content: l1Form.content
      })
      l1Form.id = data.data.id
      l1Form.status = data.data.status
      ElMessage.success('草稿已保存')
      await refreshL1()
      // 保存后聚焦刚写入的草稿，避免中栏又跳回生效行
      applyL1Versions(data.data.id)
      // 落库成功即灭脏：底部「保存草稿」重新变为不可点
      markDrawerClean()
    } finally {
      saving.value = false
    }
    return
  }

  // 用途与键由槽位决定，只校验用户能改的部分
  if (!l2Form.templateKey.trim() || !l2Form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (l2Form.segments.some((item) => !item.segKey.trim() || !item.displayName.trim() || !item.content.trim())) {
    ElMessage.warning('每个段落的 seg_key、展示名与正文都要填')
    return
  }
  saving.value = true
  try {
    const { data } = await saveTemplateDraft({
      id: l2Form.id,
      templateKey: l2Form.id ? undefined : l2Form.templateKey,
      name: l2Form.name,
      purpose: l2Form.purpose,
      locale: l2Form.id ? undefined : l2Form.locale,
      protocol: l2Form.protocol || undefined,
      remark: l2Form.remark || undefined,
      segments: [...l2Form.segments].sort((left, right) => left.sortNo - right.sortNo)
    })
    l2Form.id = data.data.id
    l2Form.status = data.data.status
    l2Form.templateKey = data.data.templateKey
    ElMessage.success('草稿已保存')
    await refreshAfterL2Write(l2Form.id)
    // 落库成功即灭脏：底部「保存草稿」重新变为不可点
    markDrawerClean()
  } finally {
    saving.value = false
  }
}

/**
 * 行内发布某个草稿版本。
 *
 * @param row 草稿版本行
 * @return true 表示已发布；用户取消确认时返回 false
 */
async function onPublish(row: VersionRow): Promise<boolean> {
  const message =
    selectedKind.value === 'L1'
      ? '发布后切换为该语言当前生效版本（同 locale 仅一条生效）。确认发布？'
      : '发布后切换为该用途的当前生效版本（同 purpose + locale 仅一条生效）。确认发布？'
  // 取消确认走 reject：这里吞掉并返回 false，避免冒泡成未捕获异常
  try {
    await ElMessageBox.confirm(message, '发布确认')
  } catch {
    return false
  }
  publishing.value = true
  try {
    if (selectedKind.value === 'L1') {
      await publishConstraint(row.id)
      ElMessage.success('已发布')
      await refreshL1()
      applyL1Versions(row.id)
      return true
    }
    await publishTemplate(row.id)
    ElMessage.success('已发布')
    await refreshAfterL2Write(row.id)
    return true
  } finally {
    publishing.value = false
  }
}

/** 抽屉底部发布：对抽屉里的草稿调用行内发布逻辑，成功后抽屉转只读 */
async function onPublishFromDrawer() {
  const id = draftIdInDrawer()
  if (!id) return
  const row = versions.value.find((item) => item.id === id)
  if (!row) return
  const published = await onPublish(row)
  if (!published) return
  // 发布成功后同步表单状态：抽屉转只读，底部「保存/发布」立即收起
  drawer.readonly = true
  if (selectedKind.value === 'L1') {
    l1Form.status = 1
  } else {
    l2Form.status = 1
  }
}

/**
 * L2 写操作后的统一刷新：目录（槽位摘要）+ 当前槽位版本列表。
 *
 * @param focusId 刷新后希望聚焦的版本主键
 */
async function refreshAfterL2Write(focusId?: number) {
  // 槽位写操作必然有选中用途；未选时按表单用途兜底，保证中栏能对上左栏高亮
  if (!selectedPurpose.value) {
    selectedPurpose.value = l2Form.purpose
    selectedKind.value = 'L2'
  }
  await refreshCatalog()
  // 用刷新后的槽位绑定键去查版本，新建首稿时 catalog 才会把它指向刚落库的 key
  const slot = templates.value.find((item) => item.purpose === selectedPurpose.value)
  if (slot && slot.versionCount > 0) {
    await loadL2Versions(slot.templateKey)
  }
  if (focusId) {
    const hit = versions.value.find((item) => item.id === focusId)
    if (hit) {
      currentVersionId.value = hit.id
    }
  }
}

/** 只刷 L1 版本列表 */
async function refreshL1() {
  const { data } = await listConstraints({ locale: locale.value })
  l1Versions.value = data.data || []
  await refreshCatalog()
}

/** 只刷左栏目录（L2 卡片与 L1 卡片摘要） */
async function refreshCatalog() {
  const { data } = await getPromptCatalog({ locale: locale.value })
  catalog.value = data.data
}

/**
 * 行内预览：L1 直接用内存正文，L2 拉详情后按 sort_no 拼接。
 *
 * @param row 版本行
 */
async function openPreview(row: VersionRow) {
  if (selectedKind.value === 'L1') {
    const detail = l1Versions.value.find((item) => item.id === row.id)
    preview.kind = 'L1'
    preview.title = `预览 · 平台约束 v${row.version}`
    preview.text = detail?.content || '（该版本暂无正文）'
    preview.withL1 = false
    preview.open = true
    return
  }
  const detail = await fetchTemplateDetail(row.id)
  if (!detail) return
  preview.kind = 'L2'
  preview.title = `预览 · ${detail.templateKey} v${detail.version}`
  preview.text = composeL2Text(detail.segments || [])
  preview.withL1 = false
  preview.open = true
}

/** 抽屉内预览：L1 取表单正文，L2 取表单里尚未保存的段落（所见即所得） */
function previewFromDrawer() {
  if (selectedKind.value === 'L1') {
    preview.kind = 'L1'
    preview.title = `预览 · 平台约束${l1Form.id ? ` v${versionOf(l1Form.id)}` : '（未保存草稿）'}`
    preview.text = l1Form.content || '（正文为空）'
    preview.withL1 = false
    preview.open = true
    return
  }
  preview.kind = 'L2'
  preview.title = `预览 · ${l2Form.templateKey || '未命名'}${l2Form.id ? ` v${versionOf(l2Form.id)}` : '（未保存草稿）'}`
  preview.text = composeL2Text(l2Form.segments)
  preview.withL1 = false
  preview.open = true
}

/**
 * 按 sort_no 拼接 L2 段落正文，段间用 display_name 作小标题。
 * 纯字符串拼接：不做变量替换、不接 Assembler、不调模型。
 *
 * @param segments 段落（任意顺序）
 * @return 可读正文
 */
function composeL2Text(segments: Array<{ segKey: string; displayName: string; sortNo: number; content: string }>) {
  const ordered = [...segments].sort((left, right) => left.sortNo - right.sortNo)
  return ordered
    .filter((item) => item.content && item.content.trim())
    .map((item) => `## ${item.displayName || item.segKey}\n${item.content.trim()}`)
    .join('\n\n')
}

/** 预览最终文本：L2 勾选前缀时把当前生效 L1 拼在最前，并加一条分隔线 */
const previewText = computed(() => {
  if (preview.kind === 'L2' && preview.withL1 && l1ActiveContent.value) {
    return `# 平台全局约束 (L1)\n${l1ActiveContent.value.trim()}\n\n---\n\n${preview.text}`
  }
  return preview.text
})

onMounted(() => {
  void reloadAll()
})
</script>

<style scoped>
.center-body {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  min-width: 0;
}

/* ============ 左栏 ============ */
.rail {
  display: flex;
  flex-direction: column;
  width: 272px;
  flex: none;
  padding: 12px;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  box-shadow: var(--ah-shadow);
}

.obj-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  border: 1px solid var(--ah-border);
  border-left: 3px solid transparent;
  border-radius: 6px;
  background: #fff;
  color: var(--ah-text);
  font: inherit;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.obj-card + .obj-card {
  margin-top: 8px;
}

.obj-card:hover {
  border-color: var(--ah-primary);
}

/* 选中态：左边框高亮 + 浅底，与草图口径一致 */
.obj-card.is-selected {
  border-color: var(--ah-primary);
  border-left: 3px solid var(--ah-primary);
  background: #f4f3ff;
}

/* L1 系统级：红系描边与淡红底，和下方 L2 卡片视觉分层 */
.obj-l1 {
  border-color: #f3c6c6;
  border-left: 3px solid #f56c6c;
  background: #fef4f4;
}

.obj-l1.is-selected {
  border-color: #f56c6c;
  border-left: 3px solid #f56c6c;
  background: #fdeaea;
}

.obj-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.obj-title {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.obj-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--ah-text-secondary);
}

.obj-ver {
  font-variant-numeric: tabular-nums;
}

.obj-sub {
  color: var(--ah-text-muted);
}

/* L1 与 L2 列表之间的明显空白分隔：留白 + 分隔线 + 小标签 */
.rail-gap {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 22px 2px 14px;
}

.rail-gap::before,
.rail-gap::after {
  content: '';
  flex: 1;
  border-top: 1px dashed var(--ah-divider);
}

.rail-gap-label {
  flex: none;
  color: var(--ah-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 8px;
}

.rail-count {
  color: var(--ah-text-secondary);
  font-size: 12px;
}

.rail-list {
  display: flex;
  flex-direction: column;
  max-height: 560px;
  overflow: auto;
  padding-right: 2px;
}

.rail-list .obj-card + .obj-card {
  margin-top: 8px;
}

/* ============ 中栏 ============ */
.versions {
  flex: 1;
  min-width: 0;
}

.versions-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.versions-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.versions-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.versions-sub {
  color: var(--ah-text-muted);
  font-size: 12px;
}

/* 焦点行与生效行是两种语义：焦点浅紫底，生效用左侧绿条 */
:deep(.el-table .is-focus-row > td.el-table__cell) {
  background: #f7f6ff;
}

:deep(.el-table .is-active-row > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--el-color-success);
}

/* ============ 抽屉 ============ */
.drawer-form {
  padding-right: 8px;
}

.form-tip {
  margin: 4px 0 0;
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.seg-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin: 8px 0 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ah-border-soft);
}

.seg-head h3 {
  margin: 0;
  font-size: 14px;
}

.seg-note {
  color: var(--ah-text-muted);
  font-size: 12px;
}

.seg-empty {
  margin-bottom: 12px;
  padding: 16px;
  border: 1px dashed var(--ah-divider);
  border-radius: 6px;
  color: var(--ah-text-muted);
  font-size: 13px;
  text-align: center;
}

.seg-card {
  margin-bottom: 12px;
  padding: 12px 12px 4px;
  background: #fafbff;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
}

.seg-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.seg-card-left,
.seg-card-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.seg-index {
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.seg-sort-label {
  color: var(--ah-text-muted);
  font-size: 12px;
}

.seg-sort {
  width: 64px;
}

.seg-item {
  margin-bottom: 8px;
}

.seg-flags {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 4px;
}

.seg-footnote {
  margin: 0 0 8px 90px;
  color: var(--el-color-primary);
  font-size: 12px;
}

.seg-add {
  width: 100%;
  margin-bottom: 8px;
  border-style: dashed;
}

/* ============ 预览 ============ */
.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.preview-tip {
  color: var(--ah-text-muted);
  font-size: 12px;
}

.preview-body {
  max-height: 58vh;
  margin: 0;
  padding: 14px 16px;
  overflow: auto;
  background: #f7f8fc;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  color: var(--ah-text);
  font-family: 'Consolas', 'Menlo', 'Microsoft YaHei', monospace;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.muted {
  color: #c0c4cc;
}

.locale-select {
  width: 110px;
}

@media (max-width: 1100px) {
  .center-body {
    flex-direction: column;
  }

  .rail {
    width: 100%;
  }
}
</style>
