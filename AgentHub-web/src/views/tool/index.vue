<template>
  <div class="browse-page page" :class="{ 'is-flip-busy': flipBusy }">
    <!-- 页头只承载标题/说明 + 全局「新建工具源」；筛选条内不再重复放新建 -->
    <PageHeader title="工具管理" description="配置内置与 MCP 工具源；同步后手动启用工具项（白名单）。">
      <template #extra>
        <!-- 工具源是网格层主实体，新建入口固定在页头；飞卡过程中禁用，避免弹窗与动效抢焦点 -->
        <el-button
          v-if="user.hasPerm('tool:source:add')"
          type="primary"
          :disabled="flipBusy"
          @click="openSource()"
        >
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建工具源
        </el-button>
      </template>
    </PageHeader>

    <!-- 网格工具栏：名称/类型/状态筛选，布局对齐用户管理；仅一页时隐藏分页 -->
    <div v-show="viewMode === 'grid'" class="browse-filters">
      <ViewModeToggle
        mode="grid"
        :disabled="flipBusy"
        :can-enter-master="true"
        @change="onViewModeChange"
      />
      <el-form class="filter-form" inline @submit.prevent="applyGridFilter">
        <el-form-item label="名称">
          <el-input
            v-model="gridDraft.keyword"
            class="filter-input"
            clearable
            placeholder="模糊搜索"
            :disabled="flipBusy"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="gridDraft.type"
            class="filter-select"
            clearable
            placeholder="全部"
            :disabled="flipBusy"
          >
            <el-option label="BUILTIN" value="BUILTIN" />
            <el-option label="MCP" value="MCP" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="gridDraft.status"
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
          <el-button class="btn-query" :disabled="flipBusy" @click="applyGridFilter">
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
      无路由 id；本地 viewMode + activeId（0=伪源「全部」）。
    -->
    <div class="browse-workspace">
      <div
        v-show="showGridLayer"
        class="layer layer-grid"
        :class="{ 'is-active': viewMode === 'grid' }"
      >
        <div ref="gridWrapRef" v-loading="sourceLoading && viewMode === 'grid'" class="grid-wrap">
          <el-empty v-if="!sourceLoading && !filteredSources.length" description="还没有工具源" />
          <div v-else-if="pagedSources.length" class="grid">
            <ConfigCard
              v-for="item in pagedSources"
              :key="item.id"
              class="card"
              size="normal"
              :class="{ 'is-flying': flyingId > 0 && flyingId === item.id }"
              :data-browse-id="item.id"
              tabindex="0"
              @click="onGridCardClick(item, $event)"
              @keyup.enter="onGridCardClick(item, $event)"
            >
              <template #header>
                <!-- MCP 才有 ×；stop 防止误触进主从；源删除只放网格卡 -->
                <el-popconfirm
                  v-if="item.type === 'MCP' && user.hasPerm('tool:source:remove')"
                  width="280"
                  :title="`删除 MCP 源「${item.name}」及其工具项？`"
                  confirm-button-text="删除"
                  cancel-button-text="取消"
                  confirm-button-type="danger"
                  @confirm="removeSource(item)"
                >
                  <template #reference>
                    <button
                      type="button"
                      class="card-del"
                      :aria-label="`删除 ${item.name}`"
                      :disabled="removingId === item.id"
                      @click.stop
                    >
                      ×
                    </button>
                  </template>
                </el-popconfirm>
                <h3 class="card-name" :class="{ 'has-del': item.type === 'MCP' }" :title="item.name">
                  {{ item.name }}
                </h3>
                <div class="card-tags">
                  <el-tag size="small" effect="plain">{{ item.type }}</el-tag>
                  <span v-if="item.type === 'MCP'" class="transport">{{ item.transport || 'SSE' }}</span>
                  <el-tag
                    size="small"
                    effect="plain"
                    :class="sourceStatusTagClass(item)"
                  >
                    {{ sourceStatusLabel(item) }}
                  </el-tag>
                </div>
              </template>
              <!-- BUILTIN：计数；MCP：同步态摘要 -->
              <template v-if="item.type !== 'MCP'">
                工具数 {{ builtinCountText(item.id, 'total') }} · 已启用 {{ builtinCountText(item.id, 'enabled') }}
              </template>
              <!-- MCP 同步摘要：完整信息用统一气泡兜底（clamp-tip：宽 320px） -->
              <el-tooltip v-else :content="metaTitle(item)" placement="top" popper-class="clamp-tip">
                <span class="sync-status">
                  <i
                    class="sync-dot"
                    :class="{
                      'is-ok': item.lastSyncTime && item.lastSyncOk === 1,
                      'is-fail': item.lastSyncTime && item.lastSyncOk !== 1,
                      'is-idle': !item.lastSyncTime
                    }"
                  />
                  {{ syncStatusLabel(item) }}
                </span>
              </el-tooltip>
              <!-- MCP 编辑回网格卡；stop 防进主从；「测试链接」仍在编辑弹窗内 -->
              <template v-if="item.type === 'MCP' && user.hasPerm('tool:source:edit')" #footer>
                <el-button link type="primary" @click.stop="openSource(item)">编辑</el-button>
              </template>
            </ConfigCard>
          </div>
        </div>
        <!-- 网格卡片墙精简底栏（mode=grid）：总数常显 + 页码仅多页时出现，无「x条/页」「前往x页」 -->
        <TablePager
          v-if="viewMode === 'grid' && filteredSources.length > 0"
          v-model:current="gridPage.current"
          v-model:size="gridPage.size"
          :total="filteredSources.length"
          mode="grid"
          :total-text="`共 ${filteredSources.length} 个工具`"
          pager-on-multi-page-only
          @change="onGridPageChange"
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
              <ViewModeToggle
                mode="master"
                :disabled="flipBusy"
                @change="onViewModeChange"
              />
              <el-input
                v-model="railKeyword"
                class="rail-search"
                clearable
                placeholder="搜索源名称"
                :disabled="flipBusy"
              />
            </div>
            <div v-loading="sourceLoading && viewMode === 'master'" class="rail-list">
              <!-- 伪源「全部」：activeId=0，切此项不做飞卡 -->
              <button
                type="button"
                class="rail-card"
                :class="{ 'is-current': activeId === 0 }"
                data-browse-id="0"
                :disabled="flipBusy"
                :aria-current="activeId === 0 ? 'true' : undefined"
                @click="openRailSource(0)"
              >
                <span class="rail-name">全部</span>
                <span class="rail-code">跨源汇总</span>
              </button>
              <el-empty
                v-if="!sourceLoading && !railRows.length"
                description="没有符合筛选的工具源"
              />
              <button
                v-for="item in railRows"
                :key="item.id"
                type="button"
                class="rail-card"
                :class="{
                  'is-current': item.id === activeId,
                  'is-flying': flyingId > 0 && flyingId === item.id
                }"
                :data-browse-id="item.id"
                :disabled="flipBusy"
                :aria-current="item.id === activeId ? 'true' : undefined"
                @click="openRailSource(item.id)"
              >
                <span class="rail-name" :title="item.name">{{ item.name }}</span>
                <span class="rail-code" :title="sourceCodeText(item)">{{ sourceCodeText(item) }}</span>
                <span class="rail-badges">
                  <el-tag size="small" effect="plain" :class="sourceStatusTagClass(item)">
                    {{ sourceStatusLabel(item) }}
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
            <!-- 右栏：工具项表 + 源级操作（选中真源时）；勿放源删除 -->
            <div v-if="viewMode === 'master' || flipBusy" class="detail-body">
              <div class="panel-head panel-head--items">
                <h2>{{ itemPanelTitle }}</h2>
                <div class="panel-actions">
                  <!-- 源级启停：仅 MCP 真源；开关内不显示文字 -->
                  <el-switch
                    v-if="selected && selected.type === 'MCP' && user.hasPerm('tool:source:enable')"
                    :model-value="selected.status === 1"
                    @change="(on: string | number | boolean) => onSourceSwitch(selected!, on === true)"
                  />
                  <el-button
                    v-if="selected && selected.type === 'MCP' && user.hasPerm('tool:source:edit')"
                    @click="openSource(selected)"
                  >
                    编辑
                  </el-button>
                  <!-- 源内批量启停：仅 MCP 真源；对当前源下全部未删除工具项生效 -->
                  <el-button
                    v-if="selected && selected.type === 'MCP' && user.hasPerm('tool:item:enable')"
                    :loading="batchStatusLoading"
                    @click="onBatchItemStatus(1)"
                  >
                    全部启用
                  </el-button>
                  <el-button
                    v-if="selected && selected.type === 'MCP' && user.hasPerm('tool:item:enable')"
                    :loading="batchStatusLoading"
                    @click="onBatchItemStatus(0)"
                  >
                    全部停用
                  </el-button>
                  <el-button
                    v-if="user.hasPerm('tool:source:sync') && !isBuiltinSelected && selected"
                    type="primary"
                    :disabled="!canSync"
                    :loading="syncing"
                    @click="onSync()"
                  >
                    同步
                  </el-button>
                </div>
              </div>
              <!-- 与用户管理一致：输入 220 / 下拉 160；查询、重置、新建工具项跟在后 -->
              <div class="filter-bar">
                <el-form class="filter-form" inline @submit.prevent="searchItems">
                  <el-form-item label="关键词">
                    <el-input
                      v-model="query.keyword"
                      class="filter-input"
                      clearable
                      placeholder="名称或展示名"
                      @keyup.enter="searchItems"
                    />
                  </el-form-item>
                  <el-form-item label="类型">
                    <el-select v-model="query.type" clearable placeholder="全部" class="filter-select">
                      <el-option label="BUILTIN" value="BUILTIN" />
                      <el-option label="MCP" value="MCP" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="状态">
                    <el-select v-model="query.status" clearable placeholder="全部" class="filter-select">
                      <el-option label="启用" :value="1" />
                      <el-option label="停用" :value="0" />
                    </el-select>
                  </el-form-item>
                  <el-form-item>
                    <el-button class="btn-query" @click="searchItems">
                      <el-icon class="btn-icon"><Search /></el-icon>
                      查询
                    </el-button>
                    <el-button class="btn-reset" @click="resetItemFilter">
                      <el-icon class="btn-icon"><RefreshRight /></el-icon>
                      重置
                    </el-button>
                    <el-button
                      v-if="user.hasPerm('tool:item:add') && !isBuiltinSelected"
                      type="primary"
                      @click="openItem()"
                    >
                      <el-icon class="btn-icon"><Plus /></el-icon>
                      新建
                    </el-button>
                  </el-form-item>
                </el-form>
              </div>
              <!-- 五列内容都短：等分宽度、表头与单元格居中 -->
              <el-table
                v-loading="itemLoading"
                class="item-table"
                :data="items"
                empty-text="没有工具项"
                table-layout="fixed"
              >
                <!-- 内容列都短：等分宽度、表头与单元格居中；最左序号列固定窄宽 -->
                <el-table-column v-bind="indexColumnProps" />
                <el-table-column prop="name" label="名称" min-width="1" align="center" header-align="center" show-overflow-tooltip />
                <el-table-column prop="title" label="展示名" min-width="1" align="center" header-align="center" show-overflow-tooltip />
                <el-table-column label="来源类型" min-width="1" align="center" header-align="center">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain">{{ row.sourceType || '-' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="状态" min-width="1" align="center" header-align="center">
                  <template #default="{ row }">
                    <el-switch
                      v-if="user.hasPerm('tool:item:enable')"
                      :model-value="row.status === 1"
                      @change="(on: string | number | boolean) => onItemSwitch(row, on === true)"
                    />
                    <span v-else>{{ row.status === 1 ? '启用' : '停用' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" min-width="1" align="center" header-align="center">
                  <template #default="{ row }">
                    <el-button v-if="user.hasPerm('tool:item:edit')" link type="primary" @click="openItem(row)">编辑</el-button>
                    <el-button v-if="user.hasPerm('tool:item:remove')" link type="danger" @click="removeItem(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <!-- 表格视图用完整分页（总数 + 条/页 + 页码 + 跳转），pageSize 走 10/20/50/100 默认选项 -->
              <TablePager
                v-model:current="query.current"
                v-model:size="query.size"
                :total="total"
                @change="loadItems"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <SourceFormDialog v-model="sourceDialog.open" :editing="sourceDialog.editing" @submit="saveSource" />
    <ItemFormDialog
      v-model="itemDialog.open"
      :editing="itemDialog.editing"
      :sources="sources"
      :preset-source-id="activeId > 0 ? activeId : undefined"
      @submit="saveItem"
    />
    <SyncResultDialog v-model="syncDialog.open" :result="syncDialog.result" />

    <FlipGhost :snap="ghostSnap" :frame="ghostFrame" :landing="ghostLanding" />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'ToolManage' })
/**
 * 工具管理页。所属域：tool。
 * 网格↔主从飞卡由 useBrowseFlip 统一装配（筛选/前端切片/一屏容量/飞卡与返回），
 * 页面只提供数据源、飞卡快照与「起飞前拉右栏工具项」的预取钩子。
 * 无路由 id：本地 viewMode + activeId；activeId=0 表示伪源「全部」（跨源汇总表）。
 * 源删除只在网格卡 ×；源级编辑/同步/启停在右栏；BUILTIN 仍隐藏新建项与同步。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeAllItemStatus,
  changeItemStatus,
  changeSourceStatus,
  createItem,
  createSource,
  deleteItem,
  deleteSource,
  pageItems,
  listSources,
  resetSourceAuth,
  syncSource,
  updateItem,
  updateSource
} from '@/api/tool'
import ConfigCard from '@/components/ConfigCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import FlipGhost from '@/components/browse/FlipGhost.vue'
import ViewModeToggle from '@/components/browse/ViewModeToggle.vue'
import ItemFormDialog from '@/components/tool/ItemFormDialog.vue'
import SourceFormDialog from '@/components/tool/SourceFormDialog.vue'
import SyncResultDialog from '@/components/tool/SyncResultDialog.vue'
import { useBrowseFlip } from '@/composables/useBrowseFlip'
import type { FlipCardSnap } from '@/composables/viewFlip'
import { useListIndexColumn } from '@/composables/listIndex'
import { useUserStore } from '@/stores/user'
import type { SyncResult, ToolItemRow, ToolSource } from '@/types/tool'

const user = useUserStore()

const sourceLoading = ref(false)
const itemLoading = ref(false)
const syncing = ref(false)
/** 源内批量启停请求中；两个按钮共用，避免并发重复提交 */
const batchStatusLoading = ref(false)
const listError = ref('')
const removingId = ref(0)
const sources = ref<ToolSource[]>([])
const items = ref<ToolItemRow[]>([])
const total = ref(0)
/** BUILTIN 卡片统计：总数 + 启用数，前端分页 total 聚合 */
const builtinStatsBySource = ref<Record<number, { total: number; enabled: number }>>({})
/** 右栏工具项查询条件；size 默认 10，与全站列表默认一致。 */
const query = reactive({ current: 1, size: 10, type: '', status: undefined as number | undefined, keyword: '' })
/** 工具项表最左「序号」列；跨页连续，仅前端展示，不改工具项分页接口 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)
const sourceDialog = reactive({ open: false, editing: null as ToolSource | null })
const itemDialog = reactive({ open: false, editing: null as ToolItemRow | null })
const syncDialog = reactive({ open: false, result: null as SyncResult | null })
/** 内置源排第一，其余保持原顺序（网格与左栏共用这份顺序）。 */
const sortedSources = computed(() => {
  const list = [...sources.value]
  list.sort((a, b) => {
    const aw = a.type === 'BUILTIN' ? 0 : 1
    const bw = b.type === 'BUILTIN' ? 0 : 1
    return aw - bw
  })
  return list
})

/**
 * 网格↔主从骨架。工具源是前端全量数据，骨架接管筛选、切片与一屏容量；
 * 页面只提供数据源、飞卡快照与「起飞前拉右栏工具项」的预取钩子。
 */
const {
  viewMode,
  activeId,
  flipBusy,
  flyingId,
  ghostSnap,
  ghostFrame,
  ghostLanding,
  detailPhase,
  showGridLayer,
  showMasterLayer,
  gridDraft,
  gridPage,
  gridWrapRef,
  filteredRows: filteredSources,
  pagedRows: pagedSources,
  applyGridFilter,
  resetGridFilter,
  onGridPageChange,
  railKeyword,
  railRows,
  onGridCardClick,
  onViewModeChange,
  backToGrid
} = useBrowseFlip<ToolSource, { keyword: string; type: string; status?: number }>({
  rows: sortedSources,
  idOf: (row) => row.id,
  snapOf: snapFromSource,
  createFilter: () => ({ keyword: '', type: '', status: undefined }),
  matchFilter: (row, filter) => {
    const key = filter.keyword.trim().toLowerCase()
    if (key && !row.name.toLowerCase().includes(key)) {
      return false
    }
    if (filter.type && row.type !== filter.type) {
      return false
    }
    // BUILTIN 无启停语义：选状态时仍按 status 字段比
    if ((filter.status === 0 || filter.status === 1) && row.status !== filter.status) {
      return false
    }
    return true
  },
  matchRail: (row, key) => row.name.toLowerCase().includes(key) || row.type.toLowerCase().includes(key),
  // 起飞即重置右栏页码并并行取数；失败由右栏 loading / 空态兜底
  prepare: () => {
    query.current = 1
    void loadItems()
  },
  // 网格为空时点「列表」：直接进伪源「全部」，无飞卡（与原实现一致）
  enterEmpty: async () => {
    activeId.value = 0
    viewMode.value = 'master'
    query.current = 1
    await loadItems()
  }
})

const selected = computed(() =>
  activeId.value > 0 ? sources.value.find((item) => item.id === activeId.value) : undefined
)
/** 内置源下隐藏「新建工具项 / 同步」，避免误操作。 */
const isBuiltinSelected = computed(() => selected.value?.type === 'BUILTIN')
/** 工具项区块标题：选中真源用源名，否则「全部工具」。 */
const itemPanelTitle = computed(() => (selected.value ? selected.value.name : '全部工具'))
const canSync = computed(
  () => !!selected.value && selected.value.type === 'MCP' && selected.value.status === 1 && !syncing.value
)

/** 主从工具项筛选重置。 */
function resetItemFilter() {
  query.type = ''
  query.status = undefined
  query.keyword = ''
  searchItems()
}

/**
 * 飞卡副行：类型，MCP 附带传输协议。
 *
 * @param item 工具源
 */
function sourceCodeText(item: ToolSource) {
  if (item.type === 'MCP') {
    return `${item.type} · ${item.transport || 'SSE'}`
  }
  return item.type
}

/**
 * 状态标签文案：BUILTIN 显示「内置」，MCP 显示启用/停用。
 *
 * @param item 工具源
 */
function sourceStatusLabel(item: ToolSource) {
  if (item.type === 'BUILTIN') {
    return '内置'
  }
  return item.status === 1 ? '启用' : '停用'
}

/** 状态标签色板 class。 */
function sourceStatusTagClass(item: ToolSource) {
  if (item.type === 'BUILTIN') {
    return 'badge-plain'
  }
  return item.status === 1 ? 'badge-on' : 'badge-off'
}

/**
 * 工具源压成飞卡快照。
 *
 * @param item 工具源
 */
function snapFromSource(item: ToolSource): FlipCardSnap {
  return {
    id: item.id,
    name: item.name,
    code: sourceCodeText(item),
    tags: [
      {
        label: sourceStatusLabel(item),
        tone: item.type === 'BUILTIN' ? 'plain' : item.status === 1 ? 'on' : 'off'
      }
    ]
  }
}

/**
 * BUILTIN 统计文案：`x个`；未拉到时显示省略号。
 *
 * @param sourceId 内置源 id
 * @param kind total=工具数，enabled=已启用
 */
function builtinCountText(sourceId: number, kind: 'total' | 'enabled') {
  const stats = builtinStatsBySource.value[sourceId]
  if (!stats) {
    return '…个'
  }
  return `${kind === 'total' ? stats.total : stats.enabled}个`
}

/**
 * MCP 卡片第 3 行摘要：鉴权掩码 + 最近同步。
 *
 * @param item 工具源
 * @return 一行文案
 */
function metaLine(item: ToolSource) {
  const auth = item.authHeaderMask || '无鉴权'
  if (!item.lastSyncTime) {
    return `${auth} · 未同步`
  }
  const flag = item.lastSyncOk === 1 ? '成功' : '失败'
  const msg = item.lastSyncMsg ? ` · ${item.lastSyncMsg}` : ''
  return `${auth} · ${item.lastSyncTime} ${flag}${msg}`
}

/** 失败时用完整错误作 tooltip；否则回退整行摘要。 */
function metaTitle(item: ToolSource) {
  if (item.lastSyncTime && item.lastSyncOk !== 1 && item.lastSyncMsg) {
    return item.lastSyncMsg
  }
  return metaLine(item)
}

/** 卡片表面极简同步态文案；完整信息见 metaTitle。 */
function syncStatusLabel(item: ToolSource) {
  if (!item.lastSyncTime) {
    return '未同步'
  }
  return item.lastSyncOk === 1 ? '连接正常' : '同步失败'
}

/**
 * 为 BUILTIN 源拉总数与启用数（size=1 只要 total）。无 list 权限则清空。
 */
async function loadBuiltinItemCounts() {
  if (!user.hasPerm('tool:item:list')) {
    builtinStatsBySource.value = {}
    return
  }
  const builtins = sources.value.filter((item) => item.type === 'BUILTIN')
  const next: Record<number, { total: number; enabled: number }> = { ...builtinStatsBySource.value }
  await Promise.all(
    builtins.map(async (item) => {
      try {
        const [allRes, onRes] = await Promise.all([
          pageItems({ current: 1, size: 1, sourceId: item.id }),
          pageItems({ current: 1, size: 1, sourceId: item.id, status: 1 })
        ])
        next[item.id] = {
          total: allRes.data.data.total ?? 0,
          enabled: onRes.data.data.total ?? 0
        }
      } catch {
        next[item.id] = { total: 0, enabled: 0 }
      }
    })
  )
  builtinStatsBySource.value = next
}

async function loadSources() {
  sourceLoading.value = true
  listError.value = ''
  try {
    const { data } = await listSources()
    sources.value = data.data || []
    // 当前选中真源已被删：退回「全部」
    if (activeId.value > 0 && !sources.value.some((item) => item.id === activeId.value)) {
      activeId.value = 0
    }
    await loadBuiltinItemCounts()
  } catch {
    listError.value = '工具源加载失败'
    sources.value = []
  } finally {
    sourceLoading.value = false
  }
}

async function loadItems() {
  if (!user.hasPerm('tool:item:list')) {
    items.value = []
    total.value = 0
    return
  }
  itemLoading.value = true
  try {
    const { data } = await pageItems({
      current: query.current,
      size: query.size,
      // activeId=0 → 不传 sourceId，对齐原「全部工具」跨源汇总
      sourceId: activeId.value > 0 ? activeId.value : undefined,
      type: query.type || undefined,
      status: query.status === 0 || query.status === 1 ? query.status : undefined,
      keyword: query.keyword || undefined
    })
    items.value = data.data.records
    total.value = data.data.total
  } finally {
    itemLoading.value = false
  }
}

function searchItems() {
  query.current = 1
  void loadItems()
}

/**
 * 主从内切源：无飞卡。切「全部」(id=0) 也不飞。
 *
 * @param id 源 id；0=全部
 */
function openRailSource(id: number) {
  if (flipBusy.value) {
    return
  }
  if (id === activeId.value) {
    return
  }
  activeId.value = id
  query.current = 1
  void loadItems()
}

function openSource(row?: ToolSource) {
  sourceDialog.editing = row || null
  sourceDialog.open = true
}

/**
 * 保存 MCP 源。新建走 create，编辑先更新资料、再按需重置鉴权头。
 * 成功只给一条固定文案轻提示：任何响应都不含完整鉴权头，UI 也不再回显。
 *
 * @param payload 弹窗提交的名称、传输、地址、备注和可选鉴权头
 * @param done    结束回调，用于解除弹窗保存按钮 loading
 */
async function saveSource(payload: Record<string, unknown>, done: () => void) {
  try {
    const editing = sourceDialog.editing
    if (editing) {
      await updateSource(editing.id, {
        name: payload.name,
        transport: payload.transport,
        baseUrl: payload.baseUrl,
        remark: payload.remark
      })
      // 编辑时鉴权头留空表示不改；填了才走重置接口
      const header = typeof payload.authHeader === 'string' ? payload.authHeader : ''
      if (header) {
        await resetSourceAuth(editing.id, header)
      }
    } else {
      await createSource(payload)
    }
    sourceDialog.open = false
    await loadSources()
    if (viewMode.value === 'master') {
      await loadItems()
    }
    ElMessage.success('已保存')
  } finally {
    done()
  }
}

/**
 * MCP 源启停（右栏开关）。
 *
 * @param row 工具源
 * @param enabled 目标是否启用
 */
async function onSourceSwitch(row: ToolSource, enabled: boolean) {
  const next = enabled ? 1 : 0
  if (row.status === next) {
    return
  }
  await changeSourceStatus(row.id, next)
  await loadSources()
}

/** 网格卡右上角 × 确认后删除 MCP 源。 */
async function removeSource(row: ToolSource) {
  if (removingId.value || flipBusy.value) {
    return
  }
  removingId.value = row.id
  try {
    await deleteSource(row.id)
    if (activeId.value === row.id) {
      activeId.value = 0
    }
    await loadSources()
    if (viewMode.value === 'master') {
      await loadItems()
    }
  } finally {
    removingId.value = 0
  }
}

async function onSync(row?: ToolSource) {
  const target = row || selected.value
  if (!target || target.type !== 'MCP' || target.status !== 1 || syncing.value) {
    return
  }
  activeId.value = target.id
  syncing.value = true
  try {
    const { data } = await syncSource(target.id)
    syncDialog.result = data.data
    syncDialog.open = true
    await loadSources()
    await loadItems()
  } finally {
    syncing.value = false
  }
}

function openItem(row?: ToolItemRow) {
  itemDialog.editing = row || null
  itemDialog.open = true
}

async function saveItem(payload: Record<string, unknown>, done: () => void) {
  try {
    if (itemDialog.editing) {
      await updateItem(itemDialog.editing.id, {
        title: payload.title,
        description: payload.description,
        inputSchemaJson: payload.inputSchemaJson
      })
    } else {
      await createItem(payload)
    }
    itemDialog.open = false
    await loadItems()
    await loadBuiltinItemCounts()
  } finally {
    done()
  }
}

/**
 * 工具项列表开关。
 *
 * @param row 工具项
 * @param enabled 目标是否启用
 */
async function onItemSwitch(row: ToolItemRow, enabled: boolean) {
  const next = enabled ? 1 : 0
  if (row.status === next) {
    return
  }
  await changeItemStatus(row.id, next)
  await loadItems()
}

async function removeItem(row: ToolItemRow) {
  try {
    await ElMessageBox.confirm(`删除工具项「${row.name}」？`, '删除工具项')
  } catch {
    return
  }
  await deleteItem(row.id)
  await loadItems()
  await loadBuiltinItemCounts()
}

/**
 * 源内批量启停：对当前选中 MCP 源下全部未删除工具项一次性置为启用 / 停用。
 * 只改 status，不下架、不改 schema；操作前二次确认，避免误点把整源工具全部打开。
 *
 * @param status 1 全部启用，0 全部停用
 */
async function onBatchItemStatus(status: number) {
  const target = selected.value
  if (!target || target.type !== 'MCP' || batchStatusLoading.value) {
    return
  }
  const label = status === 1 ? '全部启用' : '全部停用'
  try {
    await ElMessageBox.confirm(
      `确定把「${target.name}」下所有工具项${label.replace('全部', '')}？仅影响本工具源。`,
      label
    )
  } catch {
    return
  }
  batchStatusLoading.value = true
  try {
    const { data } = await changeAllItemStatus(target.id, status)
    ElMessage.success(`${label}完成，共 ${data.data.updated} 项`)
    query.current = 1
    await loadItems()
    await loadBuiltinItemCounts()
  } finally {
    batchStatusLoading.value = false
  }
}

onMounted(async () => {
  // 默认网格：只拉源卡，不挂大表
  await loadSources()
})
</script>

<style scoped>
/* 业务局部样式；飞卡壳 / 网格 / 主从布局见 browse-workspace.css */

.block-alert {
  margin-bottom: 12px;
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
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-name.has-del {
  padding-right: 32px;
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.transport {
  color: var(--ah-text-secondary);
  font-size: 12px;
  white-space: nowrap;
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

.badge-plain {
  --el-tag-text-color: #4b5563;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f9fafb;
}

.sync-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  vertical-align: top;
}

.sync-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
}

.sync-dot.is-ok {
  background: #67c23a;
  box-shadow: 0 0 0 3px rgba(103, 194, 58, 0.18);
}

.sync-dot.is-fail {
  background: #f56c6c;
  box-shadow: 0 0 0 3px rgba(245, 108, 108, 0.18);
}

.sync-dot.is-idle {
  background: #c0c4cc;
}

.detail-body {
  padding: 16px;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: var(--ah-radius-lg, 12px);
  box-shadow: var(--ah-shadow);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

/* 与按钮行高对齐，切换内置/MCP 时标题栏不抖 */
.panel-head--items {
  height: 48px;
  min-height: 48px;
  box-sizing: border-box;
}

.panel-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.4;
}

.panel-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-height: 32px;
}

.filter-bar {
  margin-bottom: 16px;
}

.item-table {
  width: 100%;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
