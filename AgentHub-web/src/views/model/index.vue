<template>
  <div class="browse-page" :class="{ 'is-flip-busy': flipBusy }">
    <!-- 页头只承载标题/说明 + 全局「新建供应商」；筛选条内不再重复放新建 -->
    <PageHeader
      title="模型管理"
      description="配置出站 LLM / 嵌入等厂商凭证与模型实例。Key 加密存储，列表只显示掩码。"
    >
      <template #extra>
        <!-- 供应商是网格层主实体，新建入口固定在页头；飞卡过程中禁用，避免弹窗与动效抢焦点 -->
        <el-button
          v-if="user.hasPerm('model:provider:add')"
          type="primary"
          :disabled="flipBusy"
          @click="openProvider()"
        >
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建供应商
        </el-button>
      </template>
    </PageHeader>

    <!-- 网格工具栏：与用户管理一致（标签 + 220/160 + 查询/重置）；仅一页时隐藏分页 -->
    <div v-show="viewMode === 'grid'" class="browse-filters">
      <ViewModeToggle
        mode="grid"
        :disabled="flipBusy"
        :can-enter-master="filteredProviders.length > 0"
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
        <el-form-item label="兼容类型">
          <el-select
            v-model="gridDraft.protocol"
            class="filter-select"
            clearable
            placeholder="全部"
            :disabled="flipBusy"
          >
            <el-option
              v-for="item in PROTOCOLS"
              :key="item"
              :label="PROTOCOL_LABEL[item] || item"
              :value="item"
            />
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
      非激活层 visibility 藏起但仍可量落点。无路由 id，仅本地 viewMode + activeId。
    -->
    <div class="browse-workspace">
      <div
        v-show="showGridLayer"
        class="layer layer-grid"
        :class="{ 'is-active': viewMode === 'grid' }"
      >
        <div ref="gridWrapRef" v-loading="providerLoading && viewMode === 'grid'" class="grid-wrap">
          <el-empty v-if="!providerLoading && !filteredProviders.length" description="还没有供应商" />
          <div v-else-if="pagedProviders.length" class="grid">
            <ConfigCard
              v-for="item in pagedProviders"
              :key="item.id"
              class="card"
              size="normal"
              :class="{ 'is-flying': flyingId === item.id }"
              :data-browse-id="item.id"
              tabindex="0"
              @click="onGridCardClick(item, $event)"
              @keyup.enter="onGridCardClick(item, $event)"
            >
              <template #header>
                <!-- 删除优先放在网格卡 ×；右栏不再重复危险删供应商 -->
                <el-popconfirm
                  v-if="user.hasPerm('model:provider:remove')"
                  width="280"
                  :title="`删除供应商「${item.name}」？仍有模型实例时会失败。`"
                  confirm-button-text="删除"
                  cancel-button-text="取消"
                  confirm-button-type="danger"
                  @confirm="removeProvider(item)"
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
                <h3 class="card-name" :title="item.name">{{ item.name }}</h3>
                <p class="card-code" :title="protocolLabel(item.protocol)">{{ protocolLabel(item.protocol) }}</p>
                <div class="badges">
                  <el-tag size="small" effect="plain" :class="item.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ item.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </div>
              </template>
              <el-tooltip
                :content="item.remark || item.baseUrl || '暂无备注'"
                placement="top"
                :disabled="!(item.remark || item.baseUrl)"
              >
                <span :class="{ muted: !(item.remark || item.baseUrl) }">
                  {{ item.remark || item.baseUrl || '暂无备注' }}
                </span>
              </el-tooltip>
              <!-- 编辑回网格卡；stop 防进主从 -->
              <template v-if="user.hasPerm('model:provider:edit')" #footer>
                <el-button link type="primary" @click.stop="openProvider(item)">编辑</el-button>
              </template>
            </ConfigCard>
          </div>
        </div>
        <!-- 网格卡片墙精简底栏（mode=grid）：总数常显 + 页码仅多页时出现，无「x条/页」「前往x页」 -->
        <TablePager
          v-if="viewMode === 'grid' && filteredProviders.length > 0"
          v-model:current="gridPage.current"
          v-model:size="gridPage.size"
          :total="filteredProviders.length"
          mode="grid"
          :total-text="`共 ${filteredProviders.length} 个模型`"
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
              <ViewModeToggle mode="master" :disabled="flipBusy" @change="onViewModeChange" />
              <el-input
                v-model="railKeyword"
                class="rail-search"
                clearable
                placeholder="搜索供应商"
                :disabled="flipBusy"
              />
            </div>
            <div v-loading="providerLoading && viewMode === 'master'" class="rail-list">
              <!-- 伪项：跨商实例表；activeId=0，不参与飞卡落点 -->
              <button
                type="button"
                class="rail-card"
                :class="{ 'is-current': activeId === 0 }"
                data-browse-id="0"
                :disabled="flipBusy"
                :aria-current="activeId === 0 ? 'true' : undefined"
                @click="selectAllProviders"
              >
                <span class="rail-name">全部</span>
                <span class="rail-code">跨供应商实例</span>
              </button>
              <el-empty v-if="!providerLoading && !railRows.length" description="没有符合筛选的供应商" />
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
                @click="openProviderDetail(row)"
              >
                <span class="rail-name" :title="row.name">{{ row.name }}</span>
                <span class="rail-code" :title="protocolLabel(row.protocol)">{{ protocolLabel(row.protocol) }}</span>
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
            <div v-if="viewMode === 'master' || flipBusy" class="detail-body">
              <div class="detail-toolbar">
                <div class="detail-title-row">
                  <h2>{{ activeProvider ? activeProvider.name : '全部供应商' }}</h2>
                  <p class="scope">
                    {{ activeProvider ? `协议：${protocolLabel(activeProvider.protocol)}` : '当前显示全部供应商的模型实例' }}
                  </p>
                </div>
                <div class="detail-actions">
                  <!-- 选中具体供应商时才出供应商级操作；勿在此放删除 / 新建模型 -->
                  <template v-if="activeProvider">
                    <el-switch
                      v-if="user.hasPerm('model:provider:edit')"
                      :model-value="activeProvider.status === 1"
                      :disabled="flipBusy"
                      @change="(on: string | number | boolean) => onProviderSwitch(activeProvider!, on === true)"
                    />
                    <el-badge
                      v-if="user.hasPerm('model:provider:edit') || user.hasPerm('model:provider:resetKey')"
                      :value="activeProvider.credentialCount ?? 0"
                      :hidden="!(activeProvider.credentialCount > 0)"
                      type="danger"
                      class="cred-badge"
                    >
                      <el-button :disabled="flipBusy" @click="openCredentials(activeProvider)">凭证</el-button>
                    </el-badge>
                    <el-button
                      v-if="user.hasPerm('model:provider:edit')"
                      :disabled="flipBusy"
                      @click="openProvider(activeProvider)"
                    >
                      编辑供应商
                    </el-button>
                  </template>
                </div>
              </div>

              <!-- 与系统管理-用户管理一致：输入 220 / 下拉 160；查询、重置、新建模型跟在后 -->
              <div class="filter-bar">
                <el-form class="filter-form" inline @submit.prevent="searchInstances">
                  <el-form-item label="用途">
                    <el-select v-model="query.capability" clearable placeholder="全部" class="filter-select">
                      <el-option-group v-for="group in CAPABILITY_GROUPS" :key="group.key" :label="group.label">
                        <el-option
                          v-for="item in group.codes"
                          :key="item"
                          :label="capabilityOptionLabel(item)"
                          :value="item"
                        />
                      </el-option-group>
                    </el-select>
                  </el-form-item>
                  <el-form-item label="关键词">
                    <el-input
                      v-model="query.keyword"
                      class="filter-input"
                      clearable
                      placeholder="名称或模型 ID"
                      @keyup.enter="searchInstances"
                    />
                  </el-form-item>
                  <el-form-item>
                    <el-button class="btn-query" @click="searchInstances">
                      <el-icon class="btn-icon"><Search /></el-icon>
                      查询
                    </el-button>
                    <el-button class="btn-reset" @click="resetInstanceFilter">
                      <el-icon class="btn-icon"><RefreshRight /></el-icon>
                      重置
                    </el-button>
                    <el-button
                      v-if="user.hasPerm('model:instance:add')"
                      type="primary"
                      :disabled="flipBusy"
                      @click="openInstance()"
                    >
                      <el-icon class="btn-icon"><Plus /></el-icon>
                      新建
                    </el-button>
                  </el-form-item>
                </el-form>
              </div>

              <el-card class="table-card" shadow="never">
                <el-table v-loading="instanceLoading" :data="instances" empty-text="没有模型实例">
                  <!-- 最左序号列：跨页连续，纯展示 -->
                  <el-table-column v-bind="indexColumnProps" />
                  <el-table-column v-if="activeId === 0" prop="providerName" label="供应商" min-width="100" align="center" />
                  <el-table-column prop="displayName" label="名称" min-width="120" align="center" />
                  <el-table-column prop="modelId" label="模型 ID" min-width="120" align="center" />
                  <el-table-column label="用途" width="100" align="center">
                    <template #default="{ row }">
                      <el-tag :type="tagType(row.capability)" size="small">
                        {{ CAPABILITY_LABEL[row.capability] || row.capability }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="关联凭证" min-width="160" align="center">
                    <template #default="{ row }">{{ row.credentialName }} · {{ row.apiKeyMask }}</template>
                  </el-table-column>
                  <el-table-column label="状态" width="90" align="center">
                    <template #default="{ row }">
                      <el-switch
                        v-if="user.hasPerm('model:instance:edit')"
                        :model-value="row.status === 1"
                        @change="(on: string | number | boolean) => onInstanceSwitch(row, on === true)"
                      />
                      <span v-else>{{ row.status === 1 ? '启用' : '停用' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="最近连通" min-width="220" align="center" show-overflow-tooltip>
                    <template #default="{ row }">
                      <span v-if="!row.lastTestTime" class="muted">未测试</span>
                      <span v-else class="probe-line">
                        <el-tag :type="row.lastTestOk === 1 ? 'success' : 'danger'" size="small">
                          {{ row.lastTestOk === 1 ? '成功' : '失败' }}
                        </el-tag>
                        <span>{{ row.lastTestTime }}</span>
                        <span v-if="row.lastTestMsg"> · {{ row.lastTestMsg }}</span>
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="160" align="center" fixed="right">
                    <template #default="{ row }">
                      <el-button v-if="user.hasPerm('model:instance:edit')" link type="primary" @click="openInstance(row)">
                        编辑
                      </el-button>
                      <el-button
                        v-if="user.hasPerm('model:instance:test')"
                        link
                        type="primary"
                        :loading="testingId === row.id"
                        @click="onTest(row)"
                      >
                        测试
                      </el-button>
                      <el-button v-if="user.hasPerm('model:instance:remove')" link type="danger" @click="removeInstance(row)">
                        删除
                      </el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <!-- 表格视图用完整分页（总数 + 条/页 + 页码 + 跳转），pageSize 走 10/20/50/100 默认选项 -->
                <TablePager
                  v-model:current="query.current"
                  v-model:size="query.size"
                  :total="total"
                  @change="loadInstances"
                />
              </el-card>
            </div>
          </div>
        </div>
      </div>
    </div>

    <ProviderFormDialog v-model="providerDialog.open" :editing="providerDialog.editing" @submit="saveProvider" />
    <CredentialDialog v-model="credentialDialog.open" :provider-id="credentialDialog.providerId" @changed="onCredentialsChanged" />
    <InstanceFormDialog
      v-model="instanceDialog.open"
      :providers="providers"
      :editing="instanceDialog.editing"
      :preset-provider-id="activeId > 0 ? activeId : undefined"
      @submit="saveInstance"
    />
    <TestResultDialog v-model="testDialog.open" :result="testDialog.result" />

    <FlipGhost :snap="ghostSnap" :frame="ghostFrame" :landing="ghostLanding" />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'ModelManage' })
/**
 * 模型管理页。所属域：model。
 * 网格↔主从飞卡：复用 useViewFlip / FlipGhost / ViewModeToggle / browse-workspace。
 * 无路由 id；本地 viewMode + activeId。网格卡=供应商；左栏含「全部」伪项；右栏实例表 + 供应商级操作。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import {
  changeInstanceStatus,
  createInstance,
  createProvider,
  deleteInstance,
  deleteProvider,
  pageInstances,
  listProviders,
  testInstance,
  updateInstance,
  updateProvider
} from '@/api/model'
import FlipGhost from '@/components/browse/FlipGhost.vue'
import ViewModeToggle from '@/components/browse/ViewModeToggle.vue'
import ConfigCard from '@/components/ConfigCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import CredentialDialog from '@/components/model/CredentialDialog.vue'
import InstanceFormDialog from '@/components/model/InstanceFormDialog.vue'
import ProviderFormDialog from '@/components/model/ProviderFormDialog.vue'
import TestResultDialog from '@/components/model/TestResultDialog.vue'
import { useViewFlip, type FlipCardSnap } from '@/composables/viewFlip'
import { CARD_HEIGHT_NORMAL, lastPage, useGridPageSize } from '@/composables/gridPageSize'
import { useListIndexColumn } from '@/composables/listIndex'
import { useUserStore } from '@/stores/user'
import type { ConnectivityResult, InstanceRow, ProviderCard } from '@/types/model'
import {
  CAPABILITY_GROUPS,
  CAPABILITY_LABEL,
  PROTOCOL_LABEL,
  PROTOCOLS,
  capabilityOptionLabel
} from '@/types/model'

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

const providerLoading = ref(false)
const instanceLoading = ref(false)
const listError = ref('')
const providers = ref<ProviderCard[]>([])
const instances = ref<InstanceRow[]>([])
const total = ref(0)
const testingId = ref<number>()
const removingId = ref(0)
const railKeyword = ref('')

/** 同页视图态；无路由同步。 */
const viewMode = ref<'grid' | 'master'>('grid')
/** 0=左栏「全部」跨商实例；>0=具体供应商。 */
const activeId = ref(0)

/*
 * 切回网格后补量一次：主从态下网格层是 display:none（.layer:not(.is-active)），
 * 浏览器不给它算宽度，容量会停在进主从前的值。等 DOM 真正可见再量；
 * pageSize 若变化，容量 watch 会把页码夹回合法范围。
 */
watch(viewMode, (mode) => {
  if (mode !== 'grid') {
    return
  }
  void nextTick(() => {
    remeasureGridSize()
  })
})

/** 网格筛选草稿（点查询才落到 gridFilter）。 */
const gridDraft = reactive({
  keyword: '',
  protocol: '' as string,
  status: undefined as number | undefined
})
/** 网格已生效筛选：名称模糊 + 协议 + 状态。 */
const gridFilter = reactive({
  keyword: '',
  protocol: '' as string,
  status: undefined as number | undefined
})
/** 网格分页（前端切片）；size 由一屏容量实测得出，仅一页时只显示总数文案。 */
const gridPage = reactive({ current: 1, size: 10 })

/** 卡片墙容器（.grid-wrap）：一屏容量按它的宽高实测 */
const gridWrapRef = ref<HTMLElement | null>(null)
/**
 * 一屏容量分页：pageSize = 当前栅格列数 × 行数（上限 100）。
 * 供应商卡为 ConfigCard normal 档 160px；尺寸变化防抖重算。
 */
const { pageSize: gridPageSize, remeasure: remeasureGridSize } = useGridPageSize({
  container: () => gridWrapRef.value,
  options: { cardHeight: CARD_HEIGHT_NORMAL }
})

/**
 * 一屏容量变化 → 写回本地分页态。
 * 网格是前端切片，无需重新请求；页码越界由 pagedProviders 夹到末页，
 * 这里再把 current 落回去，避免底栏页码与实际切片不一致。
 */
watch(gridPageSize, (next) => {
  if (!next || next === gridPage.size) {
    return
  }
  gridPage.size = next
  gridPage.current = Math.min(gridPage.current, lastPage(filteredProviders.value.length, next))
})

const query = reactive({ current: 1, size: 10, capability: '', keyword: '' })
/** 模型实例表最左「序号」列；跨页连续，仅前端展示，不改模型实例分页接口 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)
const providerDialog = reactive({ open: false, editing: null as ProviderCard | null })
const credentialDialog = reactive({ open: false, providerId: undefined as number | undefined })
const instanceDialog = reactive({ open: false, editing: null as InstanceRow | null })
const testDialog = reactive({ open: false, result: null as ConnectivityResult | null })

/** 飞卡时两层都留着；平时只显示当前态。 */
const showGridLayer = computed(() => viewMode.value === 'grid' || flipBusy.value)
const showMasterLayer = computed(() => viewMode.value === 'master' || flipBusy.value)

const activeProvider = computed(() =>
  activeId.value > 0 ? providers.value.find((item) => item.id === activeId.value) : undefined
)

/** 网格全量筛选结果（前端筛选，不另打接口）。 */
const filteredProviders = computed(() => {
  const key = gridFilter.keyword.trim().toLowerCase()
  return providers.value.filter((row) => {
    if (key && !row.name.toLowerCase().includes(key)) {
      return false
    }
    if (gridFilter.protocol && row.protocol !== gridFilter.protocol) {
      return false
    }
    if (gridFilter.status === 0 || gridFilter.status === 1) {
      if (row.status !== gridFilter.status) {
        return false
      }
    }
    return true
  })
})

/** 当前页卡片；页码越界时回退到末页切片。 */
const pagedProviders = computed(() => {
  const list = filteredProviders.value
  const size = gridPage.size
  const maxPage = Math.max(1, Math.ceil(list.length / size) || 1)
  const current = Math.min(gridPage.current, maxPage)
  const start = (current - 1) * size
  return list.slice(start, start + size)
})

/** 查询：草稿写入生效条件并回到第一页。 */
function applyGridFilter() {
  gridFilter.keyword = gridDraft.keyword
  gridFilter.protocol = gridDraft.protocol
  gridFilter.status = gridDraft.status
  gridPage.current = 1
}

/** 重置：清空草稿与生效条件。 */
function resetGridFilter() {
  gridDraft.keyword = ''
  gridDraft.protocol = ''
  gridDraft.status = undefined
  applyGridFilter()
}

/** 翻页时校正越界（删除后总数变少）。 */
function onGridPageChange() {
  const maxPage = Math.max(1, Math.ceil(filteredProviders.value.length / gridPage.size) || 1)
  if (gridPage.current > maxPage) {
    gridPage.current = maxPage
  }
}

/** 左栏供应商列表（本地关键词过滤；「全部」伪项不在此列）。 */
const railRows = computed(() => {
  const key = railKeyword.value.trim().toLowerCase()
  if (!key) {
    return providers.value
  }
  return providers.value.filter(
    (row) =>
      row.name.toLowerCase().includes(key) ||
      protocolLabel(row.protocol).toLowerCase().includes(key) ||
      row.protocol.toLowerCase().includes(key)
  )
})

/**
 * 用途标签色；与改版前一致。
 *
 * @param capability 用途码
 */
function tagType(capability: string) {
  if (capability === 'EMBEDDING' || capability === 'EMBED') return 'success'
  if (capability === 'ROUTER' || capability === 'INTENT' || capability === 'REWRITE' || capability === 'SUMMARY') {
    return 'warning'
  }
  if (capability === 'CHAT' || capability === 'LLM') return 'primary'
  if (capability === 'GUARDRAIL') return 'danger'
  return 'info'
}

/**
 * 卡片 / 飞卡副行协议展示名；未知码原样回退。
 *
 * @param protocol 协议枚举码
 */
function protocolLabel(protocol: string) {
  return PROTOCOL_LABEL[protocol] || protocol
}

/**
 * 飞卡快照：副行用协议中文；标签仅启停。
 *
 * @param row 供应商
 */
function snapFromProvider(row: ProviderCard): FlipCardSnap {
  return {
    id: row.id,
    name: row.name,
    code: protocolLabel(row.protocol),
    tags: [{ label: row.status === 1 ? '启用' : '停用', tone: row.status === 1 ? 'on' : 'off' }]
  }
}

/**
 * 按 data-browse-id 找网格卡或左栏卡。
 *
 * @param id 供应商 id
 * @param where 网格或左轨
 */
function findBrowseEl(id: number, where: 'grid' | 'rail') {
  const root = where === 'grid' ? '.layer-grid .card' : '.layer-master .rail-card'
  return document.querySelector<HTMLElement>(`${root}[data-browse-id="${id}"]`)
}

async function loadProviders() {
  providerLoading.value = true
  listError.value = ''
  try {
    const { data } = await listProviders()
    providers.value = data.data || []
    // 主从下当前选中已被删：回落到「全部」
    if (activeId.value > 0 && !providers.value.some((item) => item.id === activeId.value)) {
      activeId.value = 0
      if (viewMode.value === 'master') {
        query.current = 1
        await loadInstances()
      }
    }
  } catch {
    listError.value = '供应商列表加载失败'
    providers.value = []
  } finally {
    providerLoading.value = false
  }
}

/**
 * 只发请求不写状态：供飞卡期间并行预取使用。
 * 返回 null 表示无权限，调用方按空结果处理。
 */
async function fetchInstances(): Promise<{ records: InstanceRow[]; total: number } | null> {
  if (!user.hasPerm('model:instance:list')) {
    return null
  }
  const { data } = await pageInstances({
    current: query.current,
    size: query.size,
    providerId: activeId.value > 0 ? activeId.value : undefined,
    capability: query.capability || undefined,
    keyword: query.keyword || undefined
  })
  return { records: data.data.records, total: data.data.total }
}

/**
 * 拉实例分页；主从右栏才调用。
 * providerId：activeId>0 时过滤；0（全部）不传。
 */
async function loadInstances() {
  if (!user.hasPerm('model:instance:list')) {
    instances.value = []
    total.value = 0
    return
  }
  instanceLoading.value = true
  try {
    const result = await fetchInstances()
    instances.value = result?.records ?? []
    total.value = result?.total ?? 0
  } finally {
    instanceLoading.value = false
  }
}

/**
 * ViewModeToggle：网格进主从 / 主从回网格。
 *
 * @param mode 目标视图
 */
function onViewModeChange(mode: 'grid' | 'master') {
  if (mode === 'master') {
    void enterListView()
    return
  }
  void backToGrid()
}

/**
 * 网格 → 主从：同页亮主从层、量落点、飞完再拉实例。
 *
 * @param row 点中的供应商
 * @param event 点击事件（取起飞元素）
 */
async function onGridCardClick(row: ProviderCard, event: Event) {
  if (flipBusy.value) {
    return
  }
  const fromEl =
    (event.currentTarget as HTMLElement | null) ||
    (event.target as HTMLElement | null)?.closest?.('.card') ||
    findBrowseEl(row.id, 'grid')
  if (!fromEl) {
    activeId.value = row.id
    viewMode.value = 'master'
    query.current = 1
    await loadInstances()
    return
  }

  activeId.value = row.id
  query.current = 1
  /*
   * 起飞即清空旧行：prepare 之后主从层立刻可见，
   * 若不清空会在整个飞行过程中显示上一个供应商的实例列表，观感错误。
   * 此时表格尚不可见，清空不产生可见重绘。
   */
  instances.value = []
  total.value = 0

  /*
   * 并行预取：动画一开始就发请求，与 340ms 飞卡同时进行。
   * 这样落地时数据多半已到，只需写一次状态即可，不再是
   * 「动画 340ms → 干等 240ms → 再等网络往返」的串行等待。
   * 失败不影响动画，落地后仍有 v-loading 兜底。
   */
  const prefetch = fetchInstances().catch(() => null)

  const ok = await runFlip({
    snap: snapFromProvider(row),
    fromEl,
    direction: 'to-master',
    detail: 'enter',
    prepare: async () => {
      viewMode.value = 'master'
      await nextTick()
      await nextTick()
      return findBrowseEl(row.id, 'rail')
    }
  })

  if (ok) {
    // 落地即写状态：预取已完成的直接同步落地，未完成的等它返回
    instanceLoading.value = true
    const result = await prefetch
    instances.value = result?.records ?? []
    total.value = result?.total ?? 0
    instanceLoading.value = false
  }
}

/** 工具栏点「列表」：以当前网格筛选结果第一张飞入主从。 */
async function enterListView() {
  // 用当前页第一张，保证网格 DOM 里有起飞元素
  const first = pagedProviders.value[0]
  if (!first) {
    return
  }
  const el = findBrowseEl(first.id, 'grid')
  const fakeEvent = { currentTarget: el } as unknown as Event
  await onGridCardClick(first, fakeEvent)
}

/** 主从实例：点查询回到第一页再拉。 */
function searchInstances() {
  query.current = 1
  void loadInstances()
}

/** 清空实例筛选后重新查询。 */
function resetInstanceFilter() {
  query.capability = ''
  query.keyword = ''
  query.current = 1
  void loadInstances()
}

/**
 * 主从 → 网格。选中「全部」(activeId=0) 时无对应网格卡，直接切态。
 */
async function backToGrid() {
  if (flipBusy.value) {
    return
  }
  const id = activeId.value
  const row = providers.value.find((item) => item.id === id)
  const fromEl = id > 0 ? findBrowseEl(id, 'rail') : null
  if (!id || !row || !fromEl) {
    viewMode.value = 'grid'
    activeId.value = 0
    railKeyword.value = ''
    instances.value = []
    total.value = 0
    return
  }

  const ok = await runFlip({
    snap: snapFromProvider(row),
    fromEl,
    direction: 'to-grid',
    detail: 'leave',
    prepare: async () => {
      viewMode.value = 'grid'
      await nextTick()
      await nextTick()
      return findBrowseEl(id, 'grid')
    }
  })

  activeId.value = 0
  railKeyword.value = ''
  instances.value = []
  total.value = 0
  if (!ok) {
    viewMode.value = 'grid'
  }
}

/** 左栏点「全部」：无飞卡，右栏跨商实例表。 */
function selectAllProviders() {
  if (flipBusy.value) {
    return
  }
  if (activeId.value === 0) {
    return
  }
  activeId.value = 0
  query.current = 1
  void loadInstances()
}

/**
 * 主从内切供应商：无飞卡。
 *
 * @param row 目标供应商
 */
function openProviderDetail(row: ProviderCard) {
  if (flipBusy.value) {
    return
  }
  if (row.id === activeId.value) {
    return
  }
  activeId.value = row.id
  query.current = 1
  void loadInstances()
}

function openProvider(row?: ProviderCard) {
  providerDialog.editing = row || null
  providerDialog.open = true
}

async function saveProvider(payload: Record<string, unknown>, done: () => void) {
  try {
    if (providerDialog.editing) {
      await updateProvider(providerDialog.editing.id, payload)
    } else {
      // 新建不再带凭证；Key 只在凭证弹窗添加，且任何响应都不回明文
      await createProvider(payload)
    }
    providerDialog.open = false
    await loadProviders()
  } finally {
    done()
  }
}

function openCredentials(row: ProviderCard) {
  credentialDialog.providerId = row.id
  credentialDialog.open = true
}

/** 凭证变更后刷新供应商计数；主从下顺带刷新实例（凭证掩码可能变）。 */
async function onCredentialsChanged() {
  await loadProviders()
  if (viewMode.value === 'master') {
    await loadInstances()
  }
}

/**
 * 右栏开关切换供应商启停。
 *
 * @param row 当前供应商
 * @param enabled 目标是否启用
 */
async function onProviderSwitch(row: ProviderCard, enabled: boolean) {
  const next = enabled ? 1 : 0
  if (row.status === next) {
    return
  }
  await updateProvider(row.id, {
    name: row.name,
    protocol: row.protocol,
    baseUrl: row.baseUrl,
    remark: row.remark || '',
    status: next
  })
  await loadProviders()
}

/** 网格卡右上 × 确认后删除。 */
async function removeProvider(row: ProviderCard) {
  if (removingId.value || flipBusy.value) {
    return
  }
  removingId.value = row.id
  try {
    await deleteProvider(row.id)
    if (activeId.value === row.id) {
      activeId.value = 0
    }
    await loadProviders()
    if (viewMode.value === 'master') {
      await loadInstances()
    }
  } finally {
    removingId.value = 0
  }
}

function openInstance(row?: InstanceRow) {
  instanceDialog.editing = row || null
  instanceDialog.open = true
}

async function saveInstance(payload: Record<string, unknown>, done: () => void) {
  try {
    if (instanceDialog.editing) {
      await updateInstance(instanceDialog.editing.id, payload)
    } else {
      await createInstance(payload)
    }
    instanceDialog.open = false
    await loadInstances()
  } finally {
    done()
  }
}

/**
 * 列表开关切换实例启停。
 *
 * @param row 当前实例
 * @param enabled 目标是否启用
 */
async function onInstanceSwitch(row: InstanceRow, enabled: boolean) {
  const next = enabled ? 1 : 0
  if (row.status === next) {
    return
  }
  await changeInstanceStatus(row.id, next)
  await loadInstances()
}

async function removeInstance(row: InstanceRow) {
  try {
    await ElMessageBox.confirm(`删除模型「${row.displayName}」？`, '删除模型')
  } catch {
    return
  }
  await deleteInstance(row.id)
  await loadInstances()
}

async function onTest(row: InstanceRow) {
  testingId.value = row.id
  try {
    const { data } = await testInstance(row.id)
    testDialog.result = data.data
    testDialog.open = true
    await loadInstances()
  } finally {
    testingId.value = undefined
  }
}

onMounted(async () => {
  // 默认仅网格；进主从后再拉实例
  await loadProviders()
})
</script>

<style scoped>
/* 布局壳走 browse-workspace.css；此处只留网格卡细节与右栏业务表样式 */

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
  padding-right: 32px;
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-code {
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

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.detail-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.detail-title-row {
  min-width: 0;
}

.detail-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.cred-badge :deep(.el-badge__content) {
  border: none;
}

.scope {
  margin: 4px 0 0;
  color: var(--ah-text-secondary);
  font-size: 13px;
}

.table-card.el-card {
  border: none;
  background: transparent;
  box-shadow: none;
}

:deep(.table-card .el-card__body) {
  padding: 0;
}

.probe-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.muted {
  color: var(--ah-text-muted, var(--ah-text-secondary));
  font-size: 12px;
}
</style>
