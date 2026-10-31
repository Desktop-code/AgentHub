<template>
  <div class="browse-page" :class="{ 'is-flip-busy': flipBusy }">
    <!-- 页头只承载标题/说明 + 全局「新建 Skill」；筛选条内不再重复放新建 -->
    <PageHeader title="Skill 管理" description="维护可复用的 Skill 正文（Markdown）；供后续 Agent 绑定使用。">
      <template #extra>
        <!-- 飞卡过程中禁用新建，避免弹窗与视图切换动效抢焦点 -->
        <el-button v-if="user.hasPerm('skill:add')" type="primary" :disabled="flipBusy" @click="createOpen = true">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建 Skill
        </el-button>
      </template>
    </PageHeader>

    <!-- 网格工具栏：名称/标题模糊 + 状态；布局对齐用户管理；仅一页时隐藏分页 -->
    <div v-show="viewMode === 'grid'" class="browse-filters">
      <ViewModeToggle
        mode="grid"
        :disabled="flipBusy"
        :can-enter-master="!!rows.length || total > 0"
        @change="onViewModeChange"
      />
      <el-form class="filter-form" inline @submit.prevent="searchGrid">
        <el-form-item label="名称">
          <el-input
            v-model="query.keyword"
            class="filter-input"
            clearable
            placeholder="名称或标题"
            :disabled="flipBusy"
            @keyup.enter="searchGrid"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            class="filter-select"
            clearable
            placeholder="全部"
            :disabled="flipBusy"
          >
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" :disabled="flipBusy" @click="searchGrid">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" :disabled="flipBusy" @click="resetGridFilter">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <!--
      双层同页：飞卡过程中两层都用 v-show 留在 DOM。
      Skill 无路由 id，仅本地 viewMode + activeId。
    -->
    <div class="browse-workspace">
      <div
        v-show="showGridLayer"
        class="layer layer-grid"
        :class="{ 'is-active': viewMode === 'grid' }"
      >
        <div ref="gridWrapRef" v-loading="listLoading && viewMode === 'grid'" class="grid-wrap">
          <el-empty v-if="!listLoading && !gridRows.length" description="还没有 Skill" />
          <div v-else-if="gridRows.length" class="grid">
            <ConfigCard
              v-for="row in gridRows"
              :key="row.id"
              class="card"
              size="normal"
              :class="{ 'is-flying': flyingId === row.id }"
              :data-browse-id="row.id"
              tabindex="0"
              @click="onGridCardClick(row, $event)"
              @keyup.enter="onGridCardClick(row, $event)"
            >
              <template #header>
                <!-- 右上灰色 ×；stop 防止进详情；二次确认后删除 -->
                <el-popconfirm
                  v-if="user.hasPerm('skill:remove')"
                  width="280"
                  :title="`删除 Skill「${row.name}」？此操作不可恢复。`"
                  confirm-button-text="删除"
                  cancel-button-text="取消"
                  confirm-button-type="danger"
                  @confirm="removeCard(row)"
                >
                  <template #reference>
                    <button
                      type="button"
                      class="card-del"
                      :aria-label="`删除 ${row.name}`"
                      :disabled="removingId === row.id"
                      @click.stop
                    >
                      ×
                    </button>
                  </template>
                </el-popconfirm>
                <h3 class="card-name" :title="row.name">{{ row.name }}</h3>
                <p class="card-title" :title="row.title">{{ row.title }}</p>
                <div class="badges">
                  <el-tag size="small" effect="plain" :class="row.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </div>
              </template>
              <el-tooltip :content="row.description?.trim() || '暂无简介'" placement="top">
                <span :class="{ muted: !row.description?.trim() }">{{ row.description?.trim() || '暂无简介' }}</span>
              </el-tooltip>
              <template #footer>
                <span class="version">v{{ row.version || '1' }}</span>
              </template>
            </ConfigCard>
          </div>
        </div>
        <!-- 网格卡片墙精简底栏（mode=grid）：总数常显 + 页码仅多页时出现，无「x条/页」「前往x页」 -->
        <TablePager
          v-if="viewMode === 'grid' && total > 0"
          v-model:current="query.current"
          v-model:size="query.size"
          :total="total"
          mode="grid"
          :total-text="`共 ${total} 个 Skill`"
          pager-on-multi-page-only
          @change="loadRows"
        />
      </div>

      <div
        v-show="showMasterLayer"
        class="layer layer-master"
        :class="{ 'is-active': viewMode === 'master' }"
      >
        <div class="master">
          <aside class="rail">
            <div class="rail-toolbar">
              <ViewModeToggle mode="master" :disabled="flipBusy" @change="onViewModeChange" />
              <el-input
                v-model="railKeyword"
                class="rail-search"
                clearable
                placeholder="搜索名称或标题"
                :disabled="flipBusy"
              />
            </div>
            <div v-loading="listLoading && viewMode === 'master'" class="rail-list">
              <el-empty v-if="!listLoading && !railRows.length" description="没有符合筛选的 Skill" />
              <button
                v-for="row in railRows"
                :key="row.id"
                type="button"
                class="rail-card"
                :class="{
                  'is-current': row.id === activeId,
                  'is-flying': flyingId === row.id
                }"
                :data-browse-id="row.id"
                :disabled="flipBusy"
                :aria-current="row.id === activeId ? 'true' : undefined"
                @click="openDetail(row)"
              >
                <span class="rail-name" :title="row.name">{{ row.name }}</span>
                <span class="rail-code" :title="row.title">{{ row.title }}</span>
                <span class="rail-badges">
                  <el-tag size="small" effect="plain" :class="row.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </span>
              </button>
            </div>
          </aside>

          <div
            class="detail-pane"
            :class="{
              'is-enter': detailPhase === 'enter',
              'is-leave': detailPhase === 'leave'
            }"
          >
            <div
              v-if="activeId > 0 && (viewMode === 'master' || flipBusy)"
              v-loading="detailLoading"
              class="detail-panel"
            >
              <el-alert
                v-if="detailError"
                :title="detailError"
                type="error"
                show-icon
                :closable="false"
                class="block-alert"
              />
              <!-- 表单体可滚，底栏取消/保存始终贴底可见 -->
              <el-form v-if="form" label-width="96px" class="detail-form">
                <div class="detail-form__body">
                  <!-- 第一行：名称 10 / 标题 8 / 版本 3 / 状态 3；开关即时启停，不计入脏标记 -->
                  <el-row :gutter="12">
                    <el-col :span="10">
                      <el-form-item label="名称" required>
                        <el-input v-model="form.name" maxlength="64" :disabled="!canEdit" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="8">
                      <el-form-item label="标题" required>
                        <el-input v-model="form.title" maxlength="128" :disabled="!canEdit" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="3">
                      <!-- 窄列用数字控件，保证版本数字可见；落库仍走字符串 version -->
                      <el-form-item label="版本" label-width="42px">
                        <el-input-number
                          v-model="versionNumber"
                          class="version-input"
                          :min="1"
                          :max="9999"
                          :step="1"
                          controls-position="right"
                          :disabled="!canEdit"
                        />
                      </el-form-item>
                    </el-col>
                    <el-col :span="3">
                      <el-form-item label="状态" label-width="42px">
                        <el-switch
                          :model-value="form.status === 1"
                          :disabled="!user.hasPerm('skill:enable') || statusSaving"
                          @change="(on: string | number | boolean) => onStatusSwitch(on === true)"
                        />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <!-- 第二行：简介全宽；旁 Tooltip 说明大模型何时加载此技能 -->
                  <el-form-item>
                    <template #label>
                      <span class="label-with-tip">
                        简介
                        <el-tooltip content="此描述会供大模型判断何时加载此技能" placement="top">
                          <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                        </el-tooltip>
                      </span>
                    </template>
                    <el-input
                      v-model="form.description"
                      type="textarea"
                      maxlength="1000"
                      :rows="2"
                      :disabled="!canEdit"
                    />
                  </el-form-item>
                  <!-- 第三行：逗号分隔纯文本；提交仍经 splitKeywords → 数组 -->
                  <el-form-item label="触发关键词">
                    <el-input
                      v-model="keywordsText"
                      maxlength="500"
                      :disabled="!canEdit"
                      placeholder="输入触发词，用逗号分隔，如：天气,查询"
                    />
                  </el-form-item>
                  <!-- 第四行：备注浅灰底；仅内部可见，永不注入模型 -->
                  <el-form-item label="备注">
                    <el-input
                      v-model="form.remark"
                      type="textarea"
                      maxlength="500"
                      :rows="2"
                      class="remark-input"
                      placeholder="仅内部人员可见，不会注入模型"
                      :disabled="!canEdit"
                    />
                  </el-form-item>
                  <!-- 第五行：正文吃满剩余高度；溢出只在 textarea 内滚，首屏不出现表单区滚动条 -->
                  <el-form-item label="正文" class="field-content">
                    <div class="content-wrap">
                      <div class="content-wrap__toolbar">
                        <el-button link type="primary" class="preview-link" @click="previewOpen = true">
                          预览效果
                        </el-button>
                      </div>
                      <el-input
                        v-model="form.contentMd"
                        type="textarea"
                        :rows="1"
                        class="markdown"
                        placeholder="Markdown"
                        :disabled="!canEdit"
                      />
                    </div>
                  </el-form-item>
                </div>
                <div v-if="canEdit" class="detail-actions">
                  <el-button :disabled="!dirty || saving" @click="cancelDetail">取消</el-button>
                  <el-button type="primary" :disabled="!dirty" :loading="saving" @click="saveDetail">保存</el-button>
                </div>
              </el-form>
            </div>
          </div>
        </div>
      </div>
    </div>

    <FlipGhost :snap="ghostSnap" :frame="ghostFrame" :landing="ghostLanding" />
    <SkillFormDialog v-model="createOpen" @submit="saveCreate" />
    <!-- 按 Anthropic SKILL.md 规范拼 frontmatter + 正文后渲染；不参与脏检查 -->
    <SkillPreviewDrawer
      v-model="previewOpen"
      :title="form?.title || form?.name || 'Skill 预览'"
      :name="form?.name || ''"
      :description="form?.description || ''"
      :trigger-keywords="keywordsText"
      :skill-title="form?.title || ''"
      :content="form?.contentMd || ''"
    />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SkillManage' })
/**
 * Skill 管理页。所属域：skill。
 * 网格↔主从飞卡复用 viewFlip / FlipGhost / ViewModeToggle / browse-workspace，禁止本页另写动画。
 * 无路由 id：仅本地 viewMode + activeId；保存/取消跟正文脏标记，启停开关即时提交。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Plus, QuestionFilled, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  changeSkillStatus,
  createSkill,
  deleteSkill,
  getSkill,
  pageSkills,
  updateSkill
} from '@/api/skill'
import ConfigCard from '@/components/ConfigCard.vue'
import FlipGhost from '@/components/browse/FlipGhost.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import SkillFormDialog from '@/components/skill/SkillFormDialog.vue'
import SkillPreviewDrawer from '@/components/skill/SkillPreviewDrawer.vue'
import ViewModeToggle from '@/components/browse/ViewModeToggle.vue'
import { useViewFlip, type FlipCardSnap } from '@/composables/viewFlip'
import { CARD_HEIGHT_NORMAL, lastPage, useGridPageSize } from '@/composables/gridPageSize'
import { confirmDiscardUnsaved } from '@/utils/unsavedConfirm'
import { useUserStore } from '@/stores/user'
import type { SkillCard, SkillDetail } from '@/types/skill'

/**
 * 参与脏检查的字段快照；不含 status（开关即时提交）。
 * triggerKeywords 存详情输入框里的逗号串原文，避免边打边拆词导致脏判抖动。
 */
interface EditSnapshot {
  name: string
  title: string
  version: string
  description: string
  triggerKeywords: string
  remark: string
  contentMd: string
}

const RAIL_PAGE_SIZE = 100
const RAIL_MAX_PAGES = 20

const user = useUserStore()
const {
  busy: flipBusy,
  flyingId,
  ghostSnap,
  ghostFrame,
  ghostLanding,
  detailPhase,
  runFlip
} = useViewFlip()

const listLoading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const statusSaving = ref(false)
const listError = ref('')
const detailError = ref('')
const createOpen = ref(false)
/** 正文预览抽屉开关；只读，不影响脏标记 */
const previewOpen = ref(false)
const removingId = ref(0)
/** 网格当前页。 */
const rows = ref<SkillCard[]>([])
/** 主从左栏全量（进主从后拉）；飞卡前可用网格页占位。 */
const railSource = ref<SkillCard[]>([])
const total = ref(0)
const railKeyword = ref('')
const form = ref<SkillDetail | null>(null)
/**
 * 触发关键词编辑态：逗号分隔纯文本，与接口数组解耦。
 * 提交时再 split；脏检查直接比这段原文。
 */
const keywordsText = ref('')
/** 载入或保存后的基准；取消时回退到这里 */
const baseline = ref<EditSnapshot | null>(null)
/** 网格分页与筛选；size 由一屏容量实测得出，不再写死 10。 */
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  status: undefined as number | undefined
})

/** 卡片墙容器（.grid-wrap）：一屏容量按它的宽高实测 */
const gridWrapRef = ref<HTMLElement | null>(null)
/**
 * 一屏容量分页：pageSize = 当前栅格列数 × 行数（上限 100）。
 * Skill 卡为 ConfigCard normal 档 160px；尺寸变化（含侧栏折叠、窗口 resize）防抖重算。
 */
const { pageSize: gridPageSize, whenReady: gridPageSizeReady, remeasure: remeasureGridSize } = useGridPageSize({
  container: () => gridWrapRef.value,
  options: { cardHeight: CARD_HEIGHT_NORMAL }
})

/**
 * 首屏是否已把实测 pageSize 写进分页态。
 * 首次测量由 {@link loadRows} 自己消费（它在请求前就等测量完成），
 * 所以容量 watch 只有在置位后才需要重取，避免首屏连打两次同一个请求。
 */
let gridSizeSynced = false

/**
 * 一屏容量变化 → 写回分页态，并在当前页越界时落到合法末页。
 * 首屏那一次跳过重取（loadRows 已按测量结果取过数）；resize 引起的后续变化才重新取数。
 */
watch(gridPageSize, (next) => {
  if (!next) {
    return
  }
  if (next !== query.size) {
    query.size = next
    query.current = Math.min(query.current, lastPage(total.value, next))
  }
  if (!gridSizeSynced) {
    return
  }
  void loadRows()
})

/**
 * 同页视图态。Skill 不跟路由绑定。
 */
const viewMode = ref<'grid' | 'master'>('grid')
const activeId = ref(0)

/*
 * 切回网格后补量一次：主从态下网格层是 display:none（.layer:not(.is-active)），
 * 浏览器不给它算宽度，容量会停在进主从前的值。等 DOM 真正可见再量；
 * pageSize 若变化，上面的容量 watch 会自动重新取数。
 */
watch(viewMode, (mode) => {
  if (mode !== 'grid') {
    return
  }
  void nextTick(() => {
    remeasureGridSize()
  })
})

/** 飞卡时两层都留着；平时只显示当前态。 */
const showGridLayer = computed(() => viewMode.value === 'grid' || flipBusy.value)
const showMasterLayer = computed(() => viewMode.value === 'master' || flipBusy.value)

const gridRows = computed(() => rows.value)

const railRows = computed(() => {
  const base = railSource.value.length ? railSource.value : rows.value
  const key = railKeyword.value.trim().toLowerCase()
  if (!key) {
    return base
  }
  return base.filter(
    (row) => row.name.toLowerCase().includes(key) || (row.title || '').toLowerCase().includes(key)
  )
})

const canEdit = computed(() => user.hasPerm('skill:edit'))

/**
 * 版本数字控件 ↔ 表单字符串 version。
 * 非法/空值回落为 1，保存仍提交字符串，不改业务语义。
 */
const versionNumber = computed({
  get(): number {
    const raw = form.value?.version
    const n = Number(raw)
    return Number.isFinite(n) && n >= 1 ? Math.floor(n) : 1
  },
  set(value: number | null | undefined) {
    if (!form.value) {
      return
    }
    const n = value == null || !Number.isFinite(value) ? 1 : Math.floor(value)
    form.value.version = String(Math.max(1, n))
  }
})

/** 相对 baseline 是否有未保存改动（不含开关状态）。 */
const dirty = computed(() => {
  if (!form.value || !baseline.value || !canEdit.value) {
    return false
  }
  return snapshotOf(form.value, keywordsText.value) !== serialize(baseline.value)
})

/**
 * Skill 行压成飞卡快照：副行用 title；标签仅启停。
 *
 * @param row 列表/左栏行
 */
function snapFromSkill(row: SkillCard): FlipCardSnap {
  return {
    id: row.id,
    name: row.name,
    code: row.title || '',
    tags: [
      {
        label: row.status === 1 ? '启用' : '停用',
        tone: row.status === 1 ? 'on' : 'off'
      }
    ]
  }
}

/**
 * 从详情 + 关键词输入框抽出可编辑快照。
 *
 * @param detail 当前表单
 * @param keywords 逗号分隔原文
 * @return 快照对象
 */
function toSnapshot(detail: SkillDetail, keywords: string): EditSnapshot {
  return {
    name: detail.name || '',
    title: detail.title || '',
    version: detail.version || '1',
    description: detail.description || '',
    triggerKeywords: keywords,
    remark: detail.remark || '',
    contentMd: detail.contentMd || ''
  }
}

/** 序列化后比较，避免对象引用误判。 */
function serialize(snap: EditSnapshot) {
  return JSON.stringify(snap)
}

function snapshotOf(detail: SkillDetail, keywords: string) {
  return serialize(toSnapshot(detail, keywords))
}

/**
 * 把接口详情归一进表单，并刷新 baseline。
 * 关键词数组 join 成输入框文本；提交时再拆回数组。
 *
 * @param data 接口详情
 */
function applyDetail(data: SkillDetail) {
  form.value = {
    ...data,
    description: data.description || '',
    triggerKeywords: [...(data.triggerKeywords || [])],
    contentMd: data.contentMd || '',
    remark: data.remark || '',
    version: data.version || '1'
  }
  keywordsText.value = joinKeywords(data.triggerKeywords)
  baseline.value = toSnapshot(form.value, keywordsText.value)
}

/** 清空右侧详情态。 */
function clearDetail() {
  form.value = null
  keywordsText.value = ''
  baseline.value = null
  detailError.value = ''
  previewOpen.value = false
}

/**
 * 数组 → 逗号串，供输入框展示。
 *
 * @param words 接口返回的关键词数组
 */
function joinKeywords(words?: string[]) {
  return (words || []).map((word) => word.trim()).filter(Boolean).join(', ')
}

/**
 * 逗号串 → 数组；兼容中英文逗号。
 *
 * @param raw 输入框原文
 */
function splitKeywords(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((word) => word.trim())
    .filter(Boolean)
}

/**
 * 按 data-browse-id 找网格卡或左栏卡，供飞卡量 First/Last。
 *
 * @param id Skill id
 * @param where 网格或左栏
 */
function findEl(id: number, where: 'grid' | 'rail') {
  const root = where === 'grid' ? '.layer-grid .card' : '.layer-master .rail-card'
  return document.querySelector<HTMLElement>(`${root}[data-browse-id="${id}"]`)
}

/** 首次测量前不取数，避免先按默认条数拉一次再按实测 pageSize 重拉；序号用于丢弃过期请求 */
let rowsSeq = 0

async function loadRows() {
  const seq = ++rowsSeq
  // 首屏等一屏容量测完再用它作为 size；后续调用时该 Promise 已兑现，等同同步
  const measured = await gridPageSizeReady()
  if (seq !== rowsSeq) {
    return
  }
  if (measured && measured !== query.size) {
    query.size = measured
    query.current = Math.min(query.current, lastPage(total.value, measured))
  }
  // 首屏已按实测 pageSize 取数：此后容量再变才需要 watch 触发重取
  gridSizeSynced = true
  listLoading.value = true
  listError.value = ''
  try {
    const { data } = await pageSkills({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status === 0 || query.status === 1 ? query.status : undefined
    })
    // 落地前又被新请求顶掉：保留最新一次的结果，旧响应直接丢弃
    if (seq !== rowsSeq) {
      return
    }
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
    /*
     * 删到当前页没有数据（删空了末页）：回落到合法末页再取一次，避免停在空白页。
     * 仅越界时退避；正常的 0 条结果（total=0）不做任何事。
     */
    const fallback = lastPage(total.value, query.size)
    if (!rows.value.length && total.value > 0 && query.current > fallback) {
      query.current = fallback
      void loadRows()
    }
  } catch {
    if (seq !== rowsSeq) {
      return
    }
    listError.value = 'Skill 列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    if (seq === rowsSeq) {
      listLoading.value = false
    }
  }
}

/** 查询：从第一页按当前条件拉取。 */
function searchGrid() {
  query.current = 1
  void loadRows()
}

/** 重置：清空筛选并重查。 */
function resetGridFilter() {
  query.keyword = ''
  query.status = undefined
  searchGrid()
}

/**
 * 主从左栏拉全量（分页拼）；静默时不挡 loading 条。
 *
 * @param silent 是否静默刷新
 */
async function loadAll(silent = false) {
  if (!silent) {
    listLoading.value = true
  }
  listError.value = ''
  try {
    const all: SkillCard[] = []
    let current = 1
    let totalCount = 0
    do {
      const { data } = await pageSkills({
        current,
        size: RAIL_PAGE_SIZE
      })
      const page = data.data
      const records = page?.records || []
      totalCount = page?.total || 0
      all.push(...records)
      if (records.length < RAIL_PAGE_SIZE) {
        break
      }
      current += 1
    } while (all.length < totalCount && current <= RAIL_MAX_PAGES)
    railSource.value = all
    total.value = totalCount
  } catch {
    listError.value = 'Skill 列表加载失败'
    railSource.value = []
  } finally {
    if (!silent) {
      listLoading.value = false
    }
  }
}

/**
 * 载入右侧详情。
 *
 * @param id Skill id
 */
async function loadDetail(id: number) {
  detailLoading.value = true
  detailError.value = ''
  try {
    const { data } = await getSkill(id)
    applyDetail(data.data)
  } catch {
    detailError.value = '详情加载失败'
    clearDetail()
  } finally {
    detailLoading.value = false
  }
}

/** 列表变更后按当前视图刷新网格或左栏。 */
function onCatalogChanged() {
  if (viewMode.value === 'master') {
    void loadAll(true)
    return
  }
  void loadRows()
}

/**
 * 网格 → 主从：同页亮主从层、量落点、飞完再拉全量。
 *
 * @param row 被点卡片
 * @param event 点击事件（取起飞元素）
 */
async function onGridCardClick(row: SkillCard, event: Event) {
  if (flipBusy.value) {
    return
  }
  const fromEl =
    (event.currentTarget as HTMLElement | null) ||
    (event.target as HTMLElement | null)?.closest?.('.card') ||
    findEl(row.id, 'grid')
  if (!fromEl) {
    activeId.value = row.id
    viewMode.value = 'master'
    railSource.value = rows.value.slice()
    void loadDetail(row.id)
    void loadAll(true)
    return
  }

  // 左栏先用当前页数据，保证落点节点立刻存在
  if (!railSource.value.length) {
    railSource.value = rows.value.slice()
  }
  activeId.value = row.id
  void loadDetail(row.id)

  const ok = await runFlip({
    snap: snapFromSkill(row),
    fromEl,
    direction: 'to-master',
    detail: 'enter',
    prepare: async () => {
      viewMode.value = 'master'
      await nextTick()
      await nextTick()
      return findEl(row.id, 'rail')
    }
  })

  if (ok) {
    window.setTimeout(() => {
      void loadAll(true)
    }, 240)
  }
}

/** 主从内切 Skill：只改选中并拉详情，无飞卡。切走前若有未保存草稿先确认。 */
async function openDetail(row: SkillCard) {
  if (flipBusy.value) {
    return
  }
  if (row.id === activeId.value) {
    return
  }
  if (dirty.value) {
    const keep = await confirmDiscardUnsaved('当前 Skill')
    if (!keep) {
      return
    }
  }
  activeId.value = row.id
  void loadDetail(row.id)
}

/** 主从 → 网格：先亮网格层再飞。回网格入口仅 ViewModeToggle。 */
async function backToGrid() {
  if (flipBusy.value) {
    return
  }
  // 回网格会清空右侧详情：有草稿时先确认
  if (dirty.value) {
    const keep = await confirmDiscardUnsaved('当前 Skill')
    if (!keep) {
      return
    }
  }
  const id = activeId.value
  const row = railRows.value.find((item) => item.id === id) || rows.value.find((item) => item.id === id)
  const fromEl = id ? findEl(id, 'rail') : null
  if (!id || !row || !fromEl) {
    viewMode.value = 'grid'
    activeId.value = 0
    railKeyword.value = ''
    clearDetail()
    void loadRows()
    return
  }

  const ok = await runFlip({
    snap: snapFromSkill(row),
    fromEl,
    direction: 'to-grid',
    detail: 'leave',
    prepare: async () => {
      viewMode.value = 'grid'
      await nextTick()
      await nextTick()
      if (!findEl(id, 'grid') && rows.value.every((item) => item.id !== id)) {
        await loadRows()
        await nextTick()
      }
      return findEl(id, 'grid')
    }
  })

  activeId.value = 0
  railKeyword.value = ''
  clearDetail()
  if (!ok) {
    void loadRows()
  }
}

/**
 * ViewModeToggle：网格进主从 / 主从回网格（与 Agent 同一入口，勿独造「返回」）。
 *
 * @param mode 目标视图
 */
function onViewModeChange(mode: 'grid' | 'master') {
  if (mode === 'grid') {
    void backToGrid()
    return
  }
  void enterListView()
}

/** 工具栏点「列表」：以当前页第一张飞入主从。 */
async function enterListView() {
  const first = rows.value[0]
  if (!first) {
    return
  }
  const fakeEvent = { currentTarget: findEl(first.id, 'grid') } as unknown as Event
  await onGridCardClick(first, fakeEvent)
}

/**
 * 网格卡删除；二次确认后调用。
 *
 * @param row 待删行
 */
async function removeCard(row: SkillCard) {
  if (removingId.value || flipBusy.value) {
    return
  }
  removingId.value = row.id
  try {
    await deleteSkill(row.id)
    ElMessage.success('已删除')
    await loadRows()
  } finally {
    removingId.value = 0
  }
}

/**
 * 新建成功后直接进主从选中该 id（无飞卡，对齐 Agent create）。
 *
 * @param payload 新建表单
 * @param done 关闭弹窗 loading
 */
async function saveCreate(payload: Record<string, unknown>, done: () => void) {
  try {
    const { data } = await createSkill(payload)
    createOpen.value = false
    const id = data.data?.id
    if (id) {
      activeId.value = id
      viewMode.value = 'master'
      railSource.value = rows.value.slice()
      void loadDetail(id)
      void loadAll(true)
      return
    }
    query.current = 1
    await loadRows()
  } finally {
    done()
  }
}

/** 保存正文类字段；status 不走这里。 */
async function saveDetail() {
  if (!form.value || !activeId.value || !dirty.value) {
    return
  }
  if (!form.value.name.trim() || !form.value.title.trim()) {
    ElMessage.warning('请填写名称和标题')
    return
  }
  saving.value = true
  try {
    const { data } = await updateSkill(activeId.value, {
      name: form.value.name.trim(),
      title: form.value.title.trim(),
      description: form.value.description?.trim() || '',
      triggerKeywords: splitKeywords(keywordsText.value),
      version: form.value.version?.trim() || '1',
      contentMd: form.value.contentMd || '',
      remark: form.value.remark?.trim() || ''
    })
    // 保留当前开关态（可能刚改过），再叠回接口字段
    const status = form.value.status
    applyDetail({ ...data.data, status })
    onCatalogChanged()
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

/** 取消：回到载入/上次保存的正文类字段，不改开关。 */
function cancelDetail() {
  if (!form.value || !baseline.value) {
    return
  }
  const snap = baseline.value
  form.value = {
    ...form.value,
    name: snap.name,
    title: snap.title,
    version: snap.version,
    description: snap.description,
    // 同步数组字段，避免脏检查与下次保存口径不一致
    triggerKeywords: splitKeywords(snap.triggerKeywords),
    remark: snap.remark,
    contentMd: snap.contentMd
  }
  keywordsText.value = snap.triggerKeywords
}

/**
 * 启停开关即时提交；同步 baseline 无关，不点亮保存/取消。
 *
 * @param enabled 目标是否启用
 */
async function onStatusSwitch(enabled: boolean) {
  if (!form.value || !activeId.value) {
    return
  }
  const next = enabled ? 1 : 0
  if (form.value.status === next) {
    return
  }
  statusSaving.value = true
  try {
    const { data } = await changeSkillStatus(activeId.value, next)
    form.value = {
      ...form.value,
      status: data.data.status,
      updateTime: data.data.updateTime
    }
    onCatalogChanged()
  } finally {
    statusSaving.value = false
  }
}

onMounted(loadRows)
</script>

<style scoped>
/* 壳层靠 browse-workspace；左栏走公共样式，本页仅详情表单与底栏 */

.block-alert {
  margin: 12px 16px 0;
}

.card-del {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #9ca3af;
  font-size: 20px;
  font-weight: 400;
  line-height: 1;
  cursor: pointer;
}

.card-del:hover:not(:disabled) {
  color: #ef4444;
  background: #fef2f2;
}

.card-del:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.card-name {
  margin: 0;
  padding-right: 32px;
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-title {
  margin: 0;
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.badge-on {
  --el-tag-text-color: #10b981;
  --el-tag-border-color: #a7f3d0;
  --el-tag-bg-color: #ecfdf5;
}

.badge-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}

.muted {
  color: var(--ah-text-muted);
}

.version {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

/* 主从区拉满到视口底部，详情卡下边框贴底（与左栏 max-height 口径一致） */
.master {
  align-items: stretch;
  height: calc(100vh - 220px);
}

.detail-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.detail-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: var(--ah-radius-lg, 12px);
  box-shadow: var(--ah-shadow);
}

.detail-form {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.detail-form__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  /* 正文用 flex 吃满剩余高度，外层不滚，避免首屏内部滚动条 */
  overflow: hidden;
  padding: 14px 16px 4px;
}

/* 正文：Label 左侧顶对齐；整项 flex 吃满剩余空间 */
.field-content {
  display: flex;
  flex: 1;
  align-items: stretch;
  margin-bottom: 0;
  min-height: 0;
}

.field-content :deep(.el-form-item__label) {
  padding-top: 4px;
  line-height: 1.4;
}

.field-content :deep(.el-form-item__content) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.content-wrap {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  min-height: 0;
}

.content-wrap__toolbar {
  display: flex;
  flex: none;
  justify-content: flex-end;
  margin-bottom: 4px;
}

.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--el-text-color-placeholder);
  cursor: help;
}

.preview-link {
  height: auto;
  padding: 0;
  font-size: 12px;
  font-weight: 400;
}

/* Label 一律不换行；「触发关键词」五字靠 96px label-width 撑开 */
.detail-form :deep(.el-form-item__label) {
  white-space: nowrap;
}

/* 窄列版本数字：占满列宽，避免控件把数字挤没 */
.version-input {
  width: 100%;
}

.version-input :deep(.el-input__wrapper) {
  padding-left: 8px;
  padding-right: 28px;
}

/* 备注浅灰底，与会进 Prompt 的简介在视觉上分开 */
.remark-input :deep(textarea) {
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  background-color: #f5f7fa;
}

.remark-input :deep(.el-textarea__inner) {
  background-color: #f5f7fa;
}

/* 底栏固定在详情卡底部，长正文滚动时始终可见 */
.detail-actions {
  display: flex;
  flex: none;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 0;
  padding: 12px 16px;
  border-top: 1px solid var(--ah-border);
  background: var(--ah-surface);
}

/* 正文框撑满 content-wrap 剩余高度；字多时只在框内滚 */
.markdown {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.markdown :deep(.el-textarea__inner) {
  flex: 1;
  height: 100% !important;
  min-height: 0;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
