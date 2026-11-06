<template>
  <!--
    is-master：仅主从态锁整页高度，由详情内部各区自行滚动。
    网格态与回程飞卡不锁（否则网格会被锁高裁切），仍交给布局 .main 统一滚动。
  -->
  <div
    ref="pageRef"
    class="page"
    :class="{ 'is-flip-busy': flipBusy || trialOpen, 'is-master': viewMode === 'master' }"
  >
    <!-- 页头只承载标题/说明 + 全局「新建 Agent」；筛选条内不再重复放新建 -->
    <PageHeader title="Agent 管理" description="配置 ReAct / Plan-Execute / Router Agent；绑定模型、工具、技能与子 Agent。">
      <template #extra>
        <!-- 试跑抽屉打开或飞卡过程中禁用新建：避免弹层叠加与视图切换抢焦点 -->
        <el-button
          v-if="user.hasPerm('agent:add')"
          type="primary"
          :disabled="flipBusy || trialOpen"
          @click="createOpen = true"
        >
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建 Agent
        </el-button>
      </template>
    </PageHeader>

    <!-- 网格工具栏：标签对齐用户管理；仅一页时隐藏分页 -->
    <div v-show="viewMode === 'grid'" class="browse-filters">
      <div class="view-toggle" role="group" aria-label="视图切换">
        <button type="button" class="view-btn is-active" title="网格" aria-label="网格" aria-pressed="true">
          <el-icon :size="16"><Grid /></el-icon>
        </button>
        <button
          type="button"
          class="view-btn"
          title="列表"
          aria-label="列表"
          aria-pressed="false"
          :disabled="!rows.length || flipBusy || trialOpen"
          @click="handleEnterListView"
        >
          <el-icon :size="16"><List /></el-icon>
        </button>
      </div>
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="名称">
          <el-input
            v-model="agentBrowse.keyword"
            class="filter-input"
            clearable
            placeholder="名称或编码"
            :disabled="flipBusy || trialOpen"
            @keyup.enter="search"
            @clear="search"
          />
        </el-form-item>
        <el-form-item label="运行模式">
          <el-select
            v-model="agentBrowse.runMode"
            class="filter-select"
            placeholder="全部"
            :disabled="flipBusy || trialOpen"
            @change="search"
          >
            <el-option label="全部" value="ALL" />
            <el-option label="ReAct" value="REACT" />
            <el-option label="Plan-Execute" value="PLAN_AND_EXECUTE" />
            <el-option label="Router" value="ROUTER" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="agentBrowse.status"
            class="filter-select"
            placeholder="全部"
            :disabled="flipBusy || trialOpen"
            @change="search"
          >
            <el-option label="全部" value="ALL" />
            <el-option label="启用" value="1" />
            <el-option label="停用" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" :disabled="flipBusy || trialOpen" @click="search">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" :disabled="flipBusy || trialOpen" @click="resetGridFilter">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <!--
      双层同页：飞卡过程中两层都用 v-show 留在 DOM，禁止路由卸载网格。
      非激活层 visibility 藏起但仍可量落点。
    -->
    <div class="workspace">
      <div
        v-show="showGridLayer"
        class="layer layer-grid"
        :class="{ 'is-active': viewMode === 'grid' }"
      >
        <div ref="gridWrapRef" v-loading="listLoading && viewMode === 'grid'" class="grid-wrap">
          <el-empty v-if="!listLoading && !gridRows.length" description="还没有 Agent" />
          <div v-else-if="gridRows.length" class="grid">
            <ConfigCard
              v-for="row in gridRows"
              :key="row.id"
              class="card"
              :size="isCompact ? 'small' : 'normal'"
              :class="{ 'is-flying': flyingId === row.id }"
              :data-agent-id="row.id"
              tabindex="0"
              @click="handleGridCardClick(row, $event)"
              @keyup.enter="handleGridCardClick(row, $event)"
            >
              <template #header>
                <!-- 右上灰色 ×；stop 防止进详情；二次确认后删除 -->
                <el-popconfirm
                  v-if="user.hasPerm('agent:remove')"
                  width="280"
                  :title="`删除 Agent「${row.name}」？绑定和子路由会一并移除。`"
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
                <p class="code" :title="row.code">{{ row.code }}</p>
                <div class="badges">
                  <el-tag size="small" effect="plain" :type="row.runMode === 'ROUTER' ? 'warning' : row.runMode === 'PLAN_AND_EXECUTE' ? 'success' : 'primary'">
                    {{ runModeLabel(row.runMode) }}
                  </el-tag>
                  <el-tag size="small" effect="plain" :class="row.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </div>
              </template>
              <el-tooltip :content="row.description?.trim() || '暂无简介'" placement="top">
                <span :class="{ muted: !row.description?.trim() }">{{ row.description?.trim() || '暂无简介' }}</span>
              </el-tooltip>
            </ConfigCard>
          </div>
        </div>
        <!-- 网格卡片墙精简底栏（mode=grid）：总数常显 + 页码仅多页时出现，无「x条/页」「前往x页」 -->
        <TablePager
          v-if="viewMode === 'grid' && total > 0"
          v-model:current="agentBrowse.current"
          v-model:size="agentBrowse.size"
          :total="total"
          mode="grid"
          :total-text="`共 ${total} 个智能体`"
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
              <div class="view-toggle" role="group" aria-label="视图切换">
                <button
                  type="button"
                  class="view-btn"
                  title="网格"
                  aria-label="网格"
                  aria-pressed="false"
                  :disabled="flipBusy || trialOpen"
                  @click="backToGrid"
                >
                  <el-icon :size="16"><Grid /></el-icon>
                </button>
                <button type="button" class="view-btn is-active" title="列表" aria-label="列表" aria-pressed="true">
                  <el-icon :size="16"><List /></el-icon>
                </button>
              </div>
              <el-input
                v-model="railKeyword"
                class="rail-search"
                clearable
                placeholder="搜索名称或编码"
                :disabled="flipBusy || trialOpen"
              />
            </div>
            <!-- loading 只反映左栏自身分页；网格用 listLoading，两者不可混用 -->
            <div v-loading="railLoading" class="rail-list">
              <el-empty v-if="!railLoading && !railRows.length" description="没有符合筛选的 Agent" />
              <button
                v-for="row in railRows"
                :key="row.id"
                type="button"
                class="rail-card"
                :class="{
                  'is-current': row.id === activeId,
                  'is-flying': flyingId === row.id
                }"
                :data-agent-id="row.id"
                :disabled="flipBusy || trialOpen"
                :aria-current="row.id === activeId ? 'true' : undefined"
                @click="openDetail(row)"
              >
                <span class="rail-name" :title="row.name">{{ row.name }}</span>
                <span class="rail-code" :title="row.code">{{ row.code }}</span>
                <span class="badges">
                  <el-tag size="small" effect="plain" :type="row.runMode === 'ROUTER' ? 'warning' : row.runMode === 'PLAN_AND_EXECUTE' ? 'success' : 'primary'">
                    {{ runModeLabel(row.runMode) }}
                  </el-tag>
                  <el-tag size="small" effect="plain" :class="row.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </span>
              </button>
              <!--
                分页哨兵：必须真实占据 1px 且不可 display:none，
                否则不产生交叉、IntersectionObserver 永不回调。
              -->
              <div ref="railSentinelRef" class="rail-sentinel" aria-hidden="true" />
              <!-- 分页状态提示；首屏未满一页时不显示「没有更多」以免干扰 -->
              <p v-if="railLoading && railRows.length" class="rail-tip">加载中…</p>
              <p v-else-if="!railHasMore && railRows.length > RAIL_PAGE_SIZE" class="rail-tip">没有更多了</p>
            </div>
          </aside>
          <div
            class="detail-pane"
            :class="{
              'is-enter': detailPhase === 'enter',
              'is-leave': detailPhase === 'leave'
            }"
          >
            <!--
              骨架作为「覆盖层」盖在详情上方，而不是把详情 v-if 掉：
              组件常驻挂载（form 为空时只是一层轻壳），只把 6 个接口与表单渲染推迟到落地后。
              若改用 v-if 摘除，会被 KeepAlive 反复搬进搬出缓存子树，
              且再次激活时 agentId 未变、watch 不触发 → 详情永远不加载 → 右栏空白。
            -->
            <KeepAlive :max="4">
              <AgentDetail
                v-if="activeId > 0 && (viewMode === 'master' || flipBusy)"
                :key="activeId"
                :agent-id="activeId"
                :auto-load="!detailSuspended"
                :initial-tab="initialTab"
                @changed="onCatalogChanged"
                @trial="onTrial"
              />
            </KeepAlive>
            <AgentDetailSkeleton v-if="detailSuspended" class="skeleton-overlay" />
          </div>
        </div>
      </div>
    </div>

    <AgentFormDialog v-model="createOpen" @submit="saveCreate" />

    <FlipGhost :snap="ghostSnap" :frame="ghostFrame" :landing="ghostLanding" />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；列表与详情共用同一名字，故共用一个页签与一份缓存。 */
defineOptions({ name: 'AgentManage' })
/**
 * Agent 管理页。所属域：agent。
 * 网格↔主从用同页 viewMode，飞卡过程不卸载网格 DOM；飞完再 replace URL。
 * 飞时源/落点 visibility:hidden；克隆按 Last 定尺寸，Invert translate+scale，尺寸连续变到落点。
 */
import { computed, h, nextTick, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { Grid, Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { createAgent, deleteAgent, pageAgents } from '@/api/agent'
import AgentDetail from '@/components/agent/AgentDetail.vue'
import AgentDetailSkeleton from '@/components/agent/AgentDetailSkeleton.vue'
import AgentFormDialog from '@/components/agent/AgentFormDialog.vue'
import FlipGhost from '@/components/browse/FlipGhost.vue'
import ConfigCard from '@/components/ConfigCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { agentBrowse } from '@/composables/agentBrowse'
import { closeAgentTrial, openAgentTrial, useAgentTrial } from '@/composables/agentTrial'
import { runModeLabel, snapFromCard } from '@/composables/agentViewFlip'
import { useBrowseFlip } from '@/composables/useBrowseFlip'
import { confirmLeaveDraft } from '@/composables/draftGuard'
import { CARD_HEIGHT_NORMAL, lastPage, useGridPageSize } from '@/composables/gridPageSize'
import { useUserStore } from '@/stores/user'
import { maTrialPathOrEmpty } from '@/utils/maTrial'
import type { AgentCard, AgentCardPatch, AgentSave } from '@/types/agent'

/** 三横线列表图标。 */
const List = {
  name: 'ViewListIcon',
  render() {
    return h(
      'svg',
      {
        xmlns: 'http://www.w3.org/2000/svg',
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': '2',
        'stroke-linecap': 'round'
      },
      [h('path', { d: 'M4 7h16' }), h('path', { d: 'M4 12h16' }), h('path', { d: 'M4 17h16' })]
    )
  }
}

const RAIL_PAGE_SIZE = 100
/** 左栏最多翻多少页；防御异常 total 导致无限请求。 */
const RAIL_MAX_PAGES = 20
/** 左栏搜索防抖时长。 */
const RAIL_SEARCH_DEBOUNCE = 300

const user = useUserStore()
const route = useRoute()
const router = useRouter()
const { open: trialOpen } = useAgentTrial()

const pageRef = ref<HTMLElement | null>(null)
const listLoading = ref(false)
/**
 * 飞卡期间是否挂起右栏详情（用骨架顶替）。
 * 只在「去程首挂」置 true：此时 AgentDetail 尚不存在，挂起才能把它的
 * 挂载 + 6 个并发请求推迟到落地之后。
 * 回程与「主从内切 Agent」不挂起——详情已挂载，强行 v-if 停用会让
 * KeepAlive 搬运整棵子树，反而在动画帧里制造大 DOM 移动。
 */
const detailSuspended = ref(false)
const listError = ref('')
const createOpen = ref(false)
const removingId = ref(0)
/** 网格卡片紧凑档；暂无切换入口，固定 false → ConfigCard normal。 */
const isCompact = false
/** 网格当前页。 */
const rows = ref<AgentCard[]>([])
/** 左栏已加载累计（分页追加，非全量）。 */
const railSource = ref<AgentCard[]>([])
/** 下一次要请求的左栏页码；初始 1，成功后自增。 */
const railPage = ref(1)
/** 左栏服务端命中总数；与网格分页的 total 相互独立，勿混用。 */
const railTotal = ref(0)
/** 左栏是否还有下一页；false 时断开观察器，避免空转。 */
const railHasMore = ref(true)
/** 左栏 loading；搜索重置 / 首次进入 / 翻页共用。 */
const railLoading = ref(false)
/** 底部哨兵节点。 */
const railSentinelRef = ref<HTMLElement | null>(null)
const total = ref(0)

/** 卡片墙容器（.grid-wrap）：一屏容量按它的宽高实测 */
const gridWrapRef = ref<HTMLElement | null>(null)
/**
 * 一屏容量分页：pageSize = 当前栅格列数 × 行数（上限 100）。
 * 卡片为 ConfigCard normal 档 160px；尺寸变化（含侧栏折叠、窗口 resize）防抖重算。
 */
const {
  pageSize: gridPageSize,
  whenReady: gridPageSizeReady,
  remeasure: remeasureGridSize
} = useGridPageSize({
  container: () => gridWrapRef.value,
  options: { cardHeight: CARD_HEIGHT_NORMAL }
})

/**
 * 首次测量前不取数，避免先按默认条数拉一次再按实测 pageSize 重拉；序号用于丢弃过期请求。
 */
let rowsSeq = 0
/**
 * 首屏是否已把实测 pageSize 写进分页态。
 * 首次测量由 {@link loadRows} 自己消费（它在请求前就等测量完成），
 * 所以容量 watch 只有在置位后才需要重取，避免首屏连打两次同一个请求。
 */
let gridSizeSynced = false

/**
 * 一屏容量变化 → 写回共享分页态，并在当前页越界时落到合法末页。
 * 首屏那一次跳过重取（loadRows 已按测量结果取过数）；resize 引起的后续变化才重新取数。
 */
watch(gridPageSize, (next) => {
  if (!next) {
    return
  }
  if (next !== agentBrowse.size) {
    agentBrowse.size = next
    // pageSize 变大后原页码可能越界：先夹到末页再查，避免后端返回空页
    agentBrowse.current = Math.min(agentBrowse.current, lastPage(total.value, next))
  }
  if (!gridSizeSynced) {
    return
  }
  void loadRows()
})

/** 搜索防抖定时器句柄；失活与卸载时都要清。 */
let railSearchTimer = 0
/** 哨兵观察器；失活与卸载时都要断开。 */
let railObserver: IntersectionObserver | null = null

/**
 * 深链约定：`/agent/list/:id?tab=orchestration` 由「编排 → 多智能体」列表使用，
 * 详情壳据此落在「编排」Tab；其它入口不带 tab，仍落基本信息。
 */
const initialTab = computed(() => {
  if (!route.path.startsWith('/agent/list/')) {
    return ''
  }
  const raw = route.query.tab
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value : ''
})

/**
 * 网格↔主从骨架。Agent 网格与左栏**都是服务端分页**，因此 `takeOverGrid: false`：
 * 骨架只管视图态、图层显隐、左栏关键词输入框与飞卡导航；
 * `agentBrowse` 分页、左栏无限滚动、URL 同步、详情挂起仍由本页负责。
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
  pagedRows: gridRows,
  railKeyword,
  railRows,
  findRowEl,
  onGridCardClick,
  backToGrid,
  enterListView
} = useBrowseFlip<AgentCard, Record<string, unknown>>({
  rows,
  railSource,
  idOf: (row) => row.id,
  snapOf: snapFromCard,
  // 左栏由服务端按关键词分页，骨架只管输入框，不再做本地过滤
  matchRail: () => true,
  takeOverGrid: false,
  idAttr: 'data-agent-id',
  /**
   * 起飞前：左栏先用当前页占位（保证落点节点立刻存在），当前页不含该卡时插到首位兜底。
   * 返回值告知落地后是否需要把左栏游标拉回第 1 页重新累积。
   */
  prepare: (row) => {
    if (!railSource.value.length) {
      railSource.value = rows.value.slice()
    }
    if (railSource.value.every((item) => item.id !== row.id)) {
      railSource.value = [row, ...railSource.value]
    }
    return railSource.value.length > 0
  },
  /** 去程落点：左栏可能已滚动，先滚进可视区再交给执行器量 Last，避免飞到屏幕外 */
  resolveRailTarget: (row) => revealRailCard(row.id),
  /** 落地后：先改 URL 再续拉左栏；无动画直切时按第一页重建左栏（与原实现一致） */
  landed: async (row, prepared, meta) => {
    if (!meta.animated) {
      railSource.value = rows.value.slice()
      await replaceMasterUrl(row.id)
      void resetRail()
      return
    }
    // 交接完成后再改 URL，避免落地帧整列重挂
    await replaceMasterUrl(row.id)
    void resumeRailAfterSeed(!!prepared)
  },
  /** 回网格前：当前 Agent 有未保存草稿先确认，取消则保持主从态 */
  beforeLeave: (): boolean | Promise<boolean> => {
    if (activeId.value <= 0) {
      return true
    }
    return confirmLeaveDraft(`agent:${activeId.value}`, '当前 Agent 配置')
  },
  /** 回程量落点前：网格页若还没有这张卡（被全量覆盖过），先补取当前页 */
  prepareLeave: async (row) => {
    if (!findRowEl(row.id, 'grid') && rows.value.every((item) => item.id !== row.id)) {
      await loadRows()
      await nextTick()
    }
  },
  /**
   * 回网格收尾：飞卡路径下关键词非空则整份丢弃左栏；无动画直切时重取网格。
   * （与原实现一致：关键词为空时保留已加载分页，下次进主从可秒开）
   */
  left: async (result) => {
    if (result.animated && result.prevRailKeyword) {
      clearRailState()
    }
    await replaceGridUrl()
    if (!result.animated) {
      void loadRows()
    }
  },
  // 回到网格后重申一次一屏容量（主从态下网格层不参与布局，量出来是 0）
  onReturnToGrid: () => {
    remeasureGridSize()
  }
})

/**
 * 网格卡点击入口：试跑抽屉打开时忽略；去程期间挂起右栏详情，
 * 把 AgentDetail 的挂载与并发请求推迟到落地之后。
 */
async function handleGridCardClick(row: AgentCard, event: Event) {
  if (trialOpen.value) {
    return
  }
  detailSuspended.value = true
  try {
    await onGridCardClick(row, event)
  } finally {
    detailSuspended.value = false
  }
}

/** 工具栏「列表」入口：与卡片点击同一套守卫与详情挂起 */
async function handleEnterListView() {
  if (trialOpen.value) {
    return
  }
  detailSuspended.value = true
  try {
    await enterListView()
  } finally {
    detailSuspended.value = false
  }
}

function parseAgentId(raw: unknown) {
  const text = Array.isArray(raw) ? raw[0] : raw
  const parsed = Number(text)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
}

/**
 * 放弃左栏完整状态（分页 + 搜索词）。
 * 用于离开主从：关键词与数据必须同时清，否则会出现
 * 「搜索框为空、列表仍被过滤」的错位状态。
 */
function clearRailState(): void {
  railKeyword.value = ''
  railSource.value = []
  railPage.value = 1
  railTotal.value = 0
  railHasMore.value = true
}

/** 外部进页 / 侧栏点「Agent 管理」时同步；飞卡中忽略，避免拆 DOM。 */
function syncFromRoute() {
  if (flipBusy.value) {
    return
  }
  const id = parseAgentId(route.params.id)
  if (id) {
    activeId.value = id
    viewMode.value = 'master'
    // 左栏为空才拉第一页；已有数据说明上次已加载，保持现状避免无谓重取
    if (!railSource.value.length) {
      void resetRail()
    }
    return
  }
  activeId.value = 0
  viewMode.value = 'grid'
  clearRailState()
  void loadRows()
}

watch(
  () => [route.params.id, route.fullPath] as const,
  () => {
    /*
     * KeepAlive 下本组件失活后 watch 仍会跑。其它路由若也声明了 :id
     *（如 /rag/kb/:id/docs），params.id 会变成知识库 id；旧逻辑只在 id 非法时
     * 才按 path 过滤，合法数字会 sync 成 activeId → AgentDetail 误调 /api/agent/**，
     * 后端连弹「Agent 不存在」。必须先确认仍在 Agent 管理路由再读 id。
     */
    const onAgentRoute = route.path === '/agent/list' || route.path.startsWith('/agent/list/')
    if (!onAgentRoute) {
      return
    }
    // 非法 id：/agent/list/abc 一类，退回列表
    if (route.params.id !== undefined && !parseAgentId(route.params.id) && route.path.startsWith('/agent/list/')) {
      router.replace('/agent/list')
      return
    }
    syncFromRoute()
  },
  { immediate: true }
)

function filterParams() {
  return {
    keyword: agentBrowse.keyword.trim() || undefined,
    runMode: agentBrowse.runMode === 'ALL' ? undefined : agentBrowse.runMode,
    status: agentBrowse.status === 'ALL' ? undefined : Number(agentBrowse.status)
  }
}

/** 等一帧：滚动写入后要等浏览器完成布局，量的坐标才是最终值。 */
function nextFrame(): Promise<void> {
  return new Promise<void>((resolve) => {
    requestAnimationFrame(() => resolve())
  })
}

/**
 * 让元素在滚动容器内垂直居中，但仅在「已完整可见」判断不成立时才滚。
 * 用同步写 scrollTop 而非 scrollIntoView：后者会连带滚动外层 .main，
 * 把起飞坐标和落点坐标一起挪走，幽灵会飞偏。
 * @param el 目标元素
 * @param scroller 滚动容器（自身 overflow:auto）
 * @param margin 判定可视时留的安全边距
 * @return 是否真的发生了滚动（调用方据此决定要不要再等一帧）
 */
function centerInScroller(el: HTMLElement, scroller: HTMLElement | null, margin = 8): boolean {
  if (!scroller) {
    return false
  }
  const box = scroller.getBoundingClientRect()
  const card = el.getBoundingClientRect()
  // 已经完整落在可视区内就不动，避免无意义重排与视觉跳动
  if (card.top >= box.top + margin && card.bottom <= box.bottom - margin) {
    return false
  }
  // 目标位置 = 当前位置 + 与容器顶的差值 - 居中补偿
  const target = scroller.scrollTop + (card.top - box.top) - (box.height - card.height) / 2
  const next = Math.max(0, target)
  if (Math.abs(next - scroller.scrollTop) < 1) {
    return false
  }
  scroller.scrollTop = next
  return true
}

/** 左栏列表的滚动容器；落点居中与后续懒加载哨兵都以它为 root。 */
function railScroller(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.layer-master .rail-list')
}

/**
 * 把左栏落点卡滚进可视区并返回节点。
 * 必须「先滚动、后量坐标」：.rail-list 是 overflow:auto，被裁剪时
 * getBoundingClientRect 会给到视口外坐标，幽灵会飞到屏幕外再落下。
 *
 * 注意：只允许滚 .rail-list 这类子级滚动容器。
 * 若改滚外层 .main，起飞元素（网格卡 / 左栏卡）会跟着位移，
 * 而飞卡执行器已在 prepare 之前量过 first，起点会失真。
 */
async function revealRailCard(id: number): Promise<HTMLElement | null> {
  const card = findRowEl(id, 'rail')
  if (!card) {
    return null
  }
  if (centerInScroller(card, railScroller())) {
    await nextFrame()
  }
  return card
}

/** 首次测量前不取数，避免先按默认条数拉一次再按实测 pageSize 重拉；序号用于丢弃过期请求 */
async function loadRows() {
  const seq = ++rowsSeq
  // 首屏等一屏容量测完再用它作为 size；后续调用时该 Promise 已兑现，等同同步
  const measured = await gridPageSizeReady()
  /*
   * 等待期间若有更新的一次 loadRows（例如容量 watch 已触发重取），
   * 本次直接放弃，保证一次状态变化只打一个请求。
   */
  if (seq !== rowsSeq) {
    return
  }
  if (measured && measured !== agentBrowse.size) {
    agentBrowse.size = measured
    agentBrowse.current = Math.min(agentBrowse.current, lastPage(total.value, measured))
  }
  // 首屏已按实测 pageSize 取数：此后容量再变才需要 watch 触发重取
  gridSizeSynced = true
  listLoading.value = true
  listError.value = ''
  try {
    const { data } = await pageAgents({
      current: agentBrowse.current,
      size: agentBrowse.size,
      ...filterParams()
    })
    // 落地前又被新请求顶掉：保留最新一次的结果，旧响应直接丢弃
    if (seq !== rowsSeq) {
      return
    }
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
    /*
     * 删到当前页没有数据（例如删空了末页，或别处改了数据）：
     * 回落到合法末页再取一次，避免停在空白页。仅越界时退避，正常空结果是 0 条。
     */
    const fallback = lastPage(total.value, agentBrowse.size)
    if (!rows.value.length && total.value > 0 && agentBrowse.current > fallback) {
      agentBrowse.current = fallback
      void loadRows()
    }
  } catch {
    if (seq !== rowsSeq) {
      return
    }
    listError.value = 'Agent 列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    if (seq === rowsSeq) {
      listLoading.value = false
    }
  }
}

/**
 * 左栏查询参数：继承网格筛选，关键词以左栏输入为准。
 * 左栏关键词为空时回落到网格关键词，保持与顶部筛选一致。
 */
function railParams() {
  const keyword = railKeyword.value.trim()
  return {
    ...filterParams(),
    ...(keyword ? { keyword } : {})
  }
}

/** 断开哨兵观察器。失活 / 卸载 / 无更多 / 出错时都要调。 */
function disconnectRailObserver(): void {
  if (railObserver) {
    railObserver.disconnect()
    railObserver = null
  }
}

/**
 * 建立哨兵观察。
 * root 必须绑定 .rail-list 这个真正的滚动容器；
 * 用默认视口作 root 会因主区自身滚动而误判触发时机。
 */
function observeRailSentinel(): void {
  disconnectRailObserver()
  const sentinel = railSentinelRef.value
  const root = railScroller()
  if (!sentinel || !root) {
    return
  }
  railObserver = new IntersectionObserver(
    (entries) => {
      // 只关心「进入预取区」，离开不处理
      if (!entries.some((entry) => entry.isIntersecting)) {
        return
      }
      void loadRailMore()
    },
    {
      root,
      // 提前 200px 触发，滚动到底前就开始加载，观感连续
      rootMargin: '200px',
      threshold: 0
    }
  )
  railObserver.observe(sentinel)
}

/**
 * 追加一页到左栏。
 * 幂等 + 可重入保护：正在加载或已无更多时直接返回，
 * 因此观察器反复回调不会打出重复请求。
 */
async function loadRailMore(): Promise<void> {
  if (railLoading.value || !railHasMore.value) {
    return
  }
  railLoading.value = true
  const page = railPage.value
  try {
    const { data } = await pageAgents({
      current: page,
      size: RAIL_PAGE_SIZE,
      ...railParams()
    })
    const result = data.data
    const records = result?.records || []
    const totalCount = result?.total || 0

    /*
     * 按 id 去重追加：飞卡前用网格页 10 条做种子，
     * 第 1 页 100 条必然与之重叠，不去重会出现重复 key。
     */
    const seen = new Set(railSource.value.map((row) => row.id))
    const fresh = records.filter((row) => !seen.has(row.id))
    if (fresh.length) {
      railSource.value = [...railSource.value, ...fresh]
    }

    railTotal.value = totalCount
    railPage.value = page + 1
    /*
     * 三个终止条件缺一不可：
     * 本页未满 → 已到末页；累计已达总数 → 完成；页数达上限 → 防御异常 total。
     */
    railHasMore.value =
      records.length >= RAIL_PAGE_SIZE &&
      railSource.value.length < totalCount &&
      page < RAIL_MAX_PAGES

    // 没有更多时主动断开，避免哨兵常驻视口内反复触发
    if (!railHasMore.value) {
      disconnectRailObserver()
    }
  } catch {
    listError.value = 'Agent 列表加载失败'
    // 失败即停止，防止错误状态下持续重试打满后端
    railHasMore.value = false
    disconnectRailObserver()
  } finally {
    railLoading.value = false
  }
}

/**
 * 重置左栏并加载第一页。
 * 搜索、筛选变更、以及需要丢弃种子数据时调用。
 * 注意：飞卡落地后不要调它，否则刚建立的 10 条种子会被清空、落点卡消失。
 */
async function resetRail(): Promise<void> {
  disconnectRailObserver()
  railPage.value = 1
  railTotal.value = 0
  railHasMore.value = true
  railSource.value = []
  /*
   * 清空后哨兵位置会变，必须等 DOM 更新再重新 observe；
   * 否则哨兵已滚出视口时观察器不再回调，第一页之后永远加载不出第二页。
   */
  await nextTick()
  observeRailSentinel()
  await loadRailMore()
}

/**
 * 飞卡落地后继续加载左栏。
 * 不能调 resetRail：那会清掉刚用于定位落点的种子行，
 * 导致落点卡瞬间消失、选中态跳变。
 * 但种子里只有网格当前页的 10 条，分页游标必须回到第 1 页，
 * 让后续页面按 id 去重后补齐整份列表。
 * @param seeded 本次是否向 railSource 注入过种子/兜底行
 */
async function resumeRailAfterSeed(seeded: boolean): Promise<void> {
  if (seeded) {
    // 种子不是「已加载分页」的一部分，游标回到起点重新累积
    railPage.value = 1
    railTotal.value = 0
    railHasMore.value = true
    await nextTick()
    observeRailSentinel()
  }
  await loadRailMore()
}

/**
 * 用详情页回传的补丁原地修补左栏行。
 * 只更新字段、不动数组顺序与分页状态，
 * 避免「改个名称就把左栏滚回顶部」。
 * @param patch 变更后的卡片字段；缺省时退化为整页刷新
 * @return 是否成功就地修补（false 表示该行尚未加载，需要刷新数据）
 */
function patchRailRow(patch?: AgentCardPatch): boolean {
  if (!patch) {
    return false
  }
  const index = railSource.value.findIndex((row) => row.id === patch.id)
  if (index < 0) {
    return false
  }
  /*
   * 整体替换该行对象而非逐字段赋值：
   * 保证引用变化触发重渲染，同时字段集合与 AgentCard 保持一致。
   */
  const next = railSource.value.slice()
  next[index] = { ...railSource.value[index], ...patch }
  railSource.value = next
  return true
}

function search() {
  agentBrowse.current = 1
  if (viewMode.value === 'master') {
    // 筛选条件变了，已加载的分页数据全部失效，必须从第一页重来
    void resetRail()
    return
  }
  void loadRows()
}

/**
 * 左栏搜索：300ms 防抖后走服务端重置。
 * 分页后本地过滤只能搜到已加载部分，必须交给后端。
 */
watch(railKeyword, () => {
  window.clearTimeout(railSearchTimer)
  railSearchTimer = window.setTimeout(() => {
    /*
     * 网格态下左栏不可见，不发请求。
     * backToGrid 会清空 railKeyword，此处拦截可避免它触发一次无谓加载；
     * 下次切回主从由 resumeRailAfterSeed / resetRail 正常接管。
     */
    if (viewMode.value !== 'master') {
      return
    }
    void resetRail()
  }, RAIL_SEARCH_DEBOUNCE)
})

/** 清定时器与观察器：失活 / 卸载共用，避免后台偷发请求与内存泄漏。 */
function teardownRail(): void {
  window.clearTimeout(railSearchTimer)
  railSearchTimer = 0
  disconnectRailObserver()
}

/*
 * 外层 Layout 已按 meta.cacheName 套 KeepAlive：关页签才销毁。
 * onActivated 重建左栏哨兵；onDeactivated 停定时器，避免后台偷发请求。
 * 路由串台防护见上方 watch 的 onAgentRoute 守卫（勿只靠失活钩子）。
 */
onActivated(() => {
  void nextTick(() => {
    // 重新可见后哨兵可能已不在观察范围内，重建一次
    if (viewMode.value === 'master' && railHasMore.value) {
      observeRailSentinel()
    }
  })
})

onDeactivated(() => {
  teardownRail()
})

onBeforeUnmount(() => {
  teardownRail()
  /*
   * agentBrowse 是模块级 reactive 单例（不是组件内状态），
   * 页签关闭导致组件被销毁后它依然存在；若不在这里清空，
   * 下次从菜单进来会看到上一次的筛选词，与「关闭页签即释放」的预期不符。
   * 只重置字段，不发请求：此刻组件即将卸载，拉数据没有意义。
   */
  agentBrowse.keyword = ''
  agentBrowse.runMode = 'ALL'
  agentBrowse.status = 'ALL'
  agentBrowse.current = 1
})

/** 重置：清空网格筛选并重查。 */
function resetGridFilter() {
  agentBrowse.keyword = ''
  agentBrowse.runMode = 'ALL'
  agentBrowse.status = 'ALL'
  search()
}

/**
 * 详情数据变更回调。
 * Q2 策略：
 * - 能在 railSource 中就地修补（改名 / 改状态 / 改运行模式）→ 只打补丁，
 *   不动数组顺序与分页，左栏不会滚回顶部；
 * - 修补不到（该行尚未加载）或在网格态 → 重新取数。
 * @param patch 详情回传的变更字段
 */
function onCatalogChanged(patch?: AgentCardPatch) {
  if (viewMode.value === 'master') {
    // 先在已加载区间内就地修补；命中则无需任何网络请求
    if (patchRailRow(patch)) {
      return
    }
    /*
     * 未命中说明该行不在已加载分页内（例如从 URL 直达一个靠后页码的 Agent），
     * 此时重置分页才能保证左栏能滚到这一行。
     */
    void resetRail()
    return
  }
  void loadRows()
}

/** 飞完再改 URL，飞的过程中网格不卸载。 */
function replaceMasterUrl(id: number) {
  return router.replace(`/agent/list/${id}`)
}

function replaceGridUrl() {
  const query = { ...route.query }
  delete query.agentId
  return router.replace({ path: '/agent/list', query })
}

/** 主从内切 Agent：无飞卡。切走前若有未保存草稿先确认，避免静默丢稿。 */
async function openDetail(row: AgentCard) {
  if (trialOpen.value || flipBusy.value) {
    return
  }
  if (row.id === activeId.value) {
    return
  }
  const keep = await confirmLeaveDraft(`agent:${activeId.value}`, '上一个 Agent 配置')
  if (!keep) {
    return
  }
  activeId.value = row.id
  void replaceMasterUrl(row.id)
}

async function removeCard(row: AgentCard) {
  if (removingId.value || flipBusy.value) {
    return
  }
  removingId.value = row.id
  try {
    await deleteAgent(row.id)
    if (trialOpen.value) {
      closeAgentTrial()
    }
    await loadRows()
  } finally {
    removingId.value = 0
  }
}

async function saveCreate(payload: AgentSave, done: () => void) {
  try {
    const { data } = await createAgent(payload)
    const warnings = data.data?.warnings || []
    if (warnings.length) {
      ElMessage.warning(warnings.join('；'))
    }
    createOpen.value = false
    const id = data.data?.id
    if (id) {
      activeId.value = id
      viewMode.value = 'master'
      railSource.value = rows.value.slice()
      await replaceMasterUrl(id)
      // 新建 Agent 必须从第一页重来，否则新行可能不在已加载区间内
      void resetRail()
      return
    }
    agentBrowse.current = 1
    await loadRows()
  } finally {
    done()
  }
}

/**
 * 打开试跑。路由依据落点不同：
 * - Router 编排根 → 独立全屏试跑页（/orchestration/multi-agent/:id/trial），
 *   消息流内带编排调度卡；不再开右侧抽屉；
 * - ReAct / 计划执行 → 维持原任务抽屉，行为不变。
 *
 * <p>都只做导航，不预置会话：会话的创建/续订仍由试跑面板自己完成。
 *
 * @param payload 详情回传的 agentId / 名称 / 运行模式（取已保存值）
 */
function onTrial(payload: { agentId: number; name: string; runMode: string }) {
  // 只有 Router 编排根去独立页；其余模式保持原抽屉，行为不变
  const trialPath = maTrialPathOrEmpty(payload.agentId, payload.runMode)
  if (trialPath) {
    void router.push(trialPath)
    return
  }
  openAgentTrial(payload)
  router.replace({ path: route.path, query: { ...route.query, trial: '1' } })
}
</script>

<style scoped>
.page {
  width: 100%;
  max-width: none;
}

/*
 * 主从态：整页锁成视口内的 flex 纵向容器。
 * 高度取布局 .main 的 100%（主区已扣顶栏与内边距），overflow:hidden 禁止全局滚动条；
 * 页头 / 筛选 / 提示条不收缩，.workspace 吃满剩余高度，内部再分区滚动。
 */
.page.is-master {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.page.is-master > .ph-toolbar,
.page.is-master > .block-alert {
  flex: none;
}

.workspace {
  position: relative;
  width: 100%;
  /* 不要用 100vh：主区已扣顶栏与内边距，再按视口撑高会一进页就出滚动条 */
  min-height: 0;
}

/* 主从态：工作区吃满页头以下的剩余高度，作为下方两层定位的容器 */
.page.is-master .workspace {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* 当前层撑满工作区，细节区的内部滚动才有确定的可用高度 */
.page.is-master .layer.is-active {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.layer {
  width: 100%;
}

/* 非当前层：绝对定位藏起，但保留布局可被 query 量到；勿用 display:none */
.layer:not(.is-active) {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 0;
  visibility: hidden;
  pointer-events: none;
}

.layer.is-active {
  position: relative;
  z-index: 1;
  visibility: visible;
  pointer-events: auto;
}

/* 飞卡遮罩只用 JS visibility，禁止 !important，否则交接无法同帧露真卡 */
.card.is-flying,
.rail-card.is-flying {
  pointer-events: none;
}

.view-toggle {
  display: inline-flex;
  flex: none;
  align-items: center;
  padding: 2px;
  background: #f3f4f6;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
}

.view-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary);
  cursor: pointer;
}

.view-btn:hover:not(:disabled) {
  color: var(--ah-text);
  background: #fff;
}

.view-btn.is-active {
  color: #3b82f6;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

.view-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.block-alert {
  margin-bottom: 12px;
}

.grid-wrap {
  min-height: 160px;
}

/*
 * 众卡淡出已移除：切主从同帧网格层即变 visibility:hidden，opacity 过渡在隐藏层上空跑。
 */

.grid {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

/* 外观由 ConfigCard 接管；此处仅保留飞卡选择器所需的 .card 与内部排版 */
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

.code {
  margin: 0;
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
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

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.master {
  display: flex;
  /* 左右两列等高：右栏详情锁高后，左轨才能用 flex:1 撑满并独立滚动 */
  align-items: stretch;
  gap: 16px;
  min-height: 0;
}

/* 主从态：主从容器吃满当前层；右栏 detail-pane 自己再分纵向 flex */
.page.is-master .master {
  flex: 1;
  min-height: 0;
}

.rail {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 10px;
  /* 相对原 280px 约缩 1/3：主从左侧列表卡更窄 */
  width: 186px;
  /* 左轨内部滚动；预留顶栏 56 + 主区内边距与页头筛选约 160 */
  max-height: calc(100vh - 220px);
  min-height: 0;
}

/* 主从锁高后不再猜视口：直接跟 .master 等高，列表区用 flex:1 得到确定高度 */
.page.is-master .rail {
  height: 100%;
  max-height: none;
}

.rail-toolbar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.rail-search {
  flex: 1;
  min-width: 0;
}

.rail-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow: auto;
  padding: 2px;
}

/*
 * 分页哨兵：1px 高度、不可见但必须参与布局。
 * 严禁 display:none —— 那样 IntersectionObserver 永远不会触发。
 */
.rail-sentinel {
  flex: none;
  width: 100%;
  height: 1px;
}

/* 分页状态提示：弱化样式，不干扰卡片列表节奏 */
.rail-tip {
  flex: none;
  margin: 4px 0 2px;
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
}

.rail-card {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--ah-border);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--ah-shadow);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.rail-card:hover:not(:disabled) {
  border-color: #93c5fd;
}

.rail-card.is-current {
  background: #eff6ff;
  border-color: #3b82f6;
  box-shadow: 0 0 0 1px #3b82f6;
}

.rail-card:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.rail-name {
  width: 100%;
  overflow: hidden;
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-code {
  width: 100%;
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-pane {
  position: relative;
  /* 纵向 flex：详情壳（.detail-root）用 flex:1 撑满，骨架覆盖层仍绝对定位 */
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
}

/*
 * 骨架作为覆盖层：绝对定位盖住详情，不参与文档流。
 * 这样详情组件可以常驻挂载（保持 KeepAlive 缓存、不重挂），
 * 由骨架负责「动画期间看起来是占位」这件事，互不干扰。
 */
.detail-pane > .skeleton-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
}

/*
 * 覆盖层不贡献高度，而此刻详情还是空壳（只有内边距），
 * 不给容器兜底高度的话骨架会被压扁成一条。
 * 只在使用骨架时才生效，正常态不引入多余空白。
 */
.detail-pane:has(> .skeleton-overlay) {
  min-height: 452px;
}

.detail-pane.is-enter {
  animation: detail-enter 280ms cubic-bezier(0.25, 0.8, 0.25, 1) both;
}

.detail-pane.is-leave {
  animation: detail-leave 200ms ease both;
}

@keyframes detail-enter {
  from {
    opacity: 0;
    transform: translateX(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@keyframes detail-leave {
  from {
    opacity: 1;
  }
  to {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .detail-pane.is-enter,
  .detail-pane.is-leave {
    animation: none !important;
    transform: none !important;
  }
}
</style>
