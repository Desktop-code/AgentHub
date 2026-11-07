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

    <!-- 记忆向量可观测条：覆盖「未就绪 / 维冲突 / 补同步中 / 重建中」四种状态。
         重建开始后必须变成「重建进行中」并且撤掉「去重建」按钮，否则会被反复点击。
         「去重建」权限与后端一致，只需 model:instance:platform（与设平台级同级）。 -->
    <el-alert
      v-if="vectorAlert"
      class="vector-alert"
      :type="vectorAlert.type"
      show-icon
      :closable="false"
    >
      <template #title>
        {{ vectorAlert.title }}
      </template>
      <div class="vector-alert-body">
        <!-- 进度只在提示条内表达：不遮挡整页，操作者可以切菜单；重建中禁用所有动作按钮 -->
        <el-icon v-if="vectorAlert.busy" class="vector-alert-spin is-loading">
          <Loading />
        </el-icon>
        <span>{{ vectorAlert.text }}</span>
        <!-- 重建中：给「继续补同步」，而不是让操作者反复点「去重建」 -->
        <el-button
          v-if="vectorAlert.action === 'reconcile'"
          link
          type="primary"
          class="vector-alert-btn"
          :loading="reconcileBusy"
          @click="continueReconcile"
        >
          继续补同步
        </el-button>
        <el-button
          v-if="vectorAlert.action === 'rebuild'"
          link
          type="danger"
          class="vector-alert-btn"
          :loading="rebuildBusy"
          @click="openRebuildFromStatus"
        >
          去重建
        </el-button>
      </div>
    </el-alert>

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
                <!--
                  删除优先放在网格卡 ×；右栏不再重复危险删供应商。
                  名下存在平台级实例时，× 换成锁：后端会拒绝删除厂商卡片，前端先给出明确原因。
                -->
                <el-tooltip
                  v-if="user.hasPerm('model:provider:remove') && item.platformLocked"
                  content="该供应商下有平台级模型实例，不能删除；请先取消平台级或换绑到其他实例"
                  placement="top-start"
                >
                  <span class="card-lock" aria-label="已锁定，不可删除" @click.stop>
                    <el-icon><Lock /></el-icon>
                  </span>
                </el-tooltip>
                <el-popconfirm
                  v-else-if="user.hasPerm('model:provider:remove')"
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
                  <el-table-column label="平台级" width="90" align="center">
                    <template #default="{ row }">
                      <!-- 平台级是记忆/检索的全局唯一实例，仅 EMBEDDING / RERANK 可能出现 -->
                      <el-tag v-if="row.platformLocked === 1" type="warning" size="small">平台级</el-tag>
                      <span v-else class="muted">—</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="90" align="center">
                    <template #default="{ row }">
                      <!-- 平台级实例不可停用：开关禁用并给出 title 提示，后端同样强校验 -->
                      <el-switch
                        v-if="user.hasPerm('model:instance:edit')"
                        :model-value="row.status === 1"
                        :disabled="row.platformLocked === 1"
                        :title="row.platformLocked === 1 ? '平台级实例不可停用，请先取消平台级' : ''"
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
                  <el-table-column label="操作" width="250" align="center" fixed="right">
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
                      <!-- 平台级：仅 EMBEDDING / RERANK 可切换；Embedding 须先有连通成功维度快照 -->
                      <el-button
                        v-if="user.hasPerm('model:instance:platform') && isPlatformCapable(row)"
                        link
                        :type="row.platformLocked === 1 ? 'warning' : 'primary'"
                        :disabled="row.platformLocked !== 1 && !platformEligible(row)"
                        :title="platformActionTitle(row)"
                        @click="onPlatformLock(row)"
                      >
                        {{ row.platformLocked === 1 ? '取消平台级' : '设为平台级' }}
                      </el-button>
                      <!-- 平台级实例不可删除：后端强校验，前端直接隐藏入口避免误点 -->
                      <el-button
                        v-if="user.hasPerm('model:instance:remove') && row.platformLocked !== 1"
                        link
                        type="danger"
                        @click="removeInstance(row)"
                      >
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

    <!-- 平台级设置 / 换绑 / 取消 / 受控重建统一走结构化确认弹窗（键值行 + 提示块，680px） -->
    <PlatformConfirmDialog
      :model-value="confirmDialog.open"
      :title="confirmDialog.title"
      :subtitle="confirmDialog.subtitle"
      :description="confirmDialog.description"
      :rows="confirmDialog.rows"
      :notices="confirmDialog.notices"
      :danger="confirmDialog.danger"
      :confirm-text="confirmDialog.confirmText"
      :cancel-text="confirmDialog.cancelText"
      @cancel="settleConfirm(false)"
      @confirm="settleConfirm(true)"
    />

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
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Loading, Lock, Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeInstanceStatus,
  createInstance,
  createProvider,
  deleteInstance,
  deleteProvider,
  pageInstances,
  listProviders,
  setInstancePlatformLock,
  testInstance,
  updateInstance,
  updateProvider
} from '@/api/model'
import {
  getMemoryVectorStatus,
  previewPlatformRebind,
  rebuildMemoryVector,
  reconcileMemoryVector
} from '@/api/memory'
import FlipGhost from '@/components/browse/FlipGhost.vue'
import ViewModeToggle from '@/components/browse/ViewModeToggle.vue'
import ConfigCard from '@/components/ConfigCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import CredentialDialog from '@/components/model/CredentialDialog.vue'
import InstanceFormDialog from '@/components/model/InstanceFormDialog.vue'
import PlatformConfirmDialog from '@/components/model/PlatformConfirmDialog.vue'
import ProviderFormDialog from '@/components/model/ProviderFormDialog.vue'
import TestResultDialog from '@/components/model/TestResultDialog.vue'
import type { FlipCardSnap } from '@/composables/viewFlip'
import { useBrowseFlip } from '@/composables/useBrowseFlip'
import { useListIndexColumn } from '@/composables/listIndex'
import { useUserStore } from '@/stores/user'
import type { ConnectivityResult, InstanceRow, ProviderCard } from '@/types/model'
import type { ConfirmNotice, ConfirmRow } from '@/types'
import type {
  MemoryVectorRebindPreview,
  MemoryVectorRebuildResult,
  MemoryVectorStatus
} from '@/types/memory'
import {
  CAPABILITY_GROUPS,
  CAPABILITY_LABEL,
  PROTOCOL_LABEL,
  PROTOCOLS,
  capabilityOptionLabel
} from '@/types/model'

const user = useUserStore()

const providerLoading = ref(false)
const instanceLoading = ref(false)
const listError = ref('')
const providers = ref<ProviderCard[]>([])
const instances = ref<InstanceRow[]>([])
const total = ref(0)
const testingId = ref<number>()
const removingId = ref(0)

const query = reactive({ current: 1, size: 10, capability: '', keyword: '' })
/** 模型实例表最左「序号」列；跨页连续，仅前端展示，不改模型实例分页接口 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)
const providerDialog = reactive({ open: false, editing: null as ProviderCard | null })
const credentialDialog = reactive({ open: false, providerId: undefined as number | undefined })
const instanceDialog = reactive({ open: false, editing: null as InstanceRow | null })
const testDialog = reactive({ open: false, result: null as ConnectivityResult | null })
/**
 * 结构化确认弹窗（平台级设置 / 换绑 / 取消 / 受控重建共用）。
 * 这些操作的事实条目多（实例、用途、双方维度、集合名），纯文本对话框排版不可控，改用键值行 + 提示块。
 */
const confirmDialog = reactive({
  open: false,
  title: '',
  subtitle: '',
  description: '',
  rows: [] as ConfirmRow[],
  notices: [] as ConfirmNotice[],
  danger: false,
  confirmText: '确认',
  cancelText: '取消'
})
/** 当前待收口的确认 Promise；同一时刻只允许一个确认弹窗 */
let confirmResolve: ((ok: boolean) => void) | null = null

/**
 * 打开结构化确认并等待用户选择。
 * 上一次未收口的 Promise 先按「取消」收口，避免异常路径把后续请求永久挂起。
 *
 * @param options 弹窗内容（标题必填，其余可选）
 * @returns true=确认，false=取消 / 关闭
 */
function askConfirm(options: {
  title: string
  subtitle?: string
  description?: string
  rows?: ConfirmRow[]
  notices?: ConfirmNotice[]
  danger?: boolean
  confirmText?: string
  cancelText?: string
}): Promise<boolean> {
  if (confirmResolve) {
    settleConfirm(false)
  }
  Object.assign(confirmDialog, {
    title: options.title,
    subtitle: options.subtitle ?? '',
    description: options.description ?? '',
    rows: options.rows ?? [],
    notices: options.notices ?? [],
    danger: options.danger ?? false,
    confirmText: options.confirmText ?? '确认',
    cancelText: options.cancelText ?? '取消',
    open: true
  })
  return new Promise<boolean>((resolve) => {
    confirmResolve = resolve
  })
}

/**
 * 收口确认弹窗。
 *
 * @param ok true=确认；false=取消 / 关闭（含 ESC、右上角）
 */
function settleConfirm(ok: boolean) {
  const resolve = confirmResolve
  confirmResolve = null
  confirmDialog.open = false
  resolve?.(ok)
}
/** 记忆向量就绪状态：只用于页头可观测提示，不参与模型列表逻辑 */
const vectorStatus = ref<MemoryVectorStatus | null>(null)
/** 重建 / 补同步请求进行中：用于按钮 loading 与防重复点击（与后端互斥状态互补） */
const rebuildBusy = ref(false)
/** 补同步单轮进行中；与重建分开，提示条文案才不会把补同步说成重建 */
const reconcileBusy = ref(false)
/**
 * onActivated 首次触发标记：KeepAlive 组件首次挂载也会触发 activated，
 * 用这个标记跳过那一次，避免与 onMounted 重复请求状态。
 */
let activatedOnce = false
/**
 * 重建已持续时间的简短文案：只做展示，时间戳缺失或异常时返回空串（不显示）。
 *
 * @param startedAt 后端 rebuildStartedAt（毫秒），0/空表示未在重建
 */
function elapsedHint(startedAt?: number): string {
  if (!startedAt || startedAt <= 0) return ''
  const seconds = Math.max(0, Math.round((Date.now() - startedAt) / 1000))
  if (seconds < 60) return `（已进行 ${seconds} 秒）`
  const minutes = Math.floor(seconds / 60)
  return `（已进行 ${minutes} 分钟）`
}

/**
 * 页头提示条的统一渲染模型。为什么不在模板里堆 v-if：
 * 各种状态的标题、颜色、可执行动作各不相同（尤其重建中必须撤掉「去重建」），
 * 收敛成一处可以保证「状态 → 文案 → 按钮」不会漏配。
 *
 * action：rebuild=可去重建；reconcile=只能继续补同步；none=只提示。
 * busy：true 时在提示条内显示转圈（不占用全屏遮罩，操作者仍可切菜单）。
 */
const vectorAlert = computed(() => {
  // 请求在途优先：重建请求可能跑数秒，先立刻把提示切成「执行中」，不能停留在旧的维冲突文案；
  // 放在状态判空之前，即使状态读取失败也要告诉操作者正在重建，避免重复点击
  if (rebuildBusy.value) {
    return {
      type: 'info' as const,
      title: '记忆向量重建执行中',
      text: '正在删除现网集合并按新维度重建、重排存量记忆；可切到其它菜单，回到本页仍会显示进度。',
      action: 'none' as const,
      busy: true
    }
  }
  const status = vectorStatus.value
  if (!status) return null
  // 服务端正在执行重建（DROP → ensure → requeue → 首轮补同步）：
  // 页面刷新会丢掉本地「请求在途」标记，必须靠这个字段继续显示「执行中」，否则会误显示成收尾/静态提示
  if (status.rebuildRunning) {
    return {
      type: 'info' as const,
      title: '记忆向量重建执行中',
      text: '正在删除现网集合并按新维度重建、重排存量记忆；可切到其它菜单或刷新本页，回来仍会显示进度。',
      action: 'none' as const,
      busy: true
    }
  }
  const rawPending = status.pendingSync
  const pendingKnown = typeof rawPending === 'number' && rawPending >= 0
  const pending = pendingKnown ? rawPending : 0
  // 服务端自动续补：重建未完成期间由后端一轮轮推进，页面刷新 / 切菜单都不打断，
  // 因此不再要求人工点「继续补同步」；老后端没有该字段时按 false 处理，保留现网的按钮兜底
  const autoDrain = status.autoDrain === true
  // 重建态优先级最高：即使 ready 已为 true（集合已按新维建好），存量没补完也不允许再重建
  if (status.rebuilding) {
    // 重建中一律转圈：autoDrain 时即使当前没有请求在途，服务端也在持续补，必须表现为「进行中」；
    // 这样刷新或切页面回来时看到的第一眼就是进度条而不是「需要手工点」的静态提示
    const busy = autoDrain || reconcileBusy.value
    const action = autoDrain
      ? ('none' as const)
      : reconcileBusy.value
        ? ('none' as const)
        : canContinueReconcile.value
          ? ('reconcile' as const)
          : ('none' as const)
    const elapsed = elapsedHint(status.rebuildStartedAt)
    if (!pendingKnown) {
      // 待补条数读取失败（后端返回 -1）：不能当成「已补齐」，仍按补同步中处理
      return {
        type: 'info' as const,
        title: '记忆向量补同步进行中',
        text: autoDrain
          ? `重建已执行，正在自动补同步${elapsed}；待补条数暂时读取失败，可离开或刷新本页，服务端会继续。`
          : '重建已执行，待补条数暂时读取失败；补齐前 LONG 召回可能不完整，期间禁止变更平台级 Embedding。',
        action,
        busy
      }
    }
    if (pending > 0) {
      return {
        type: 'info' as const,
        title: '记忆向量补同步进行中',
        text: autoDrain
          ? `集合已按维度 ${status.dimension || '未知'} 重建${elapsed}，仍在自动补同步：剩余 ${pending} 条（每轮有上限）。` +
            '可离开本页或刷新，服务端会继续推进，补齐后本提示自动消失。'
          : `集合已按维度 ${status.dimension || '未知'} 重建，仍有 ${pending} 条存量待补同步（每轮有上限）；` +
            '补齐前 LONG 召回可能不完整，期间禁止设置 / 换绑 / 取消平台级 Embedding。',
        action,
        busy
      }
    }
    // 重建态 + 无待补 + 未就绪：说明 DROP 成功但建集/补同步失败，必须给重试入口
    if (!status.ready) {
      return {
        type: 'warning' as const,
        title: '记忆向量重建未完成',
        text: `${status.reason || '集合尚未就绪'}；可重试重建，期间禁止设置 / 换绑 / 取消平台级 Embedding。`,
        action: canRebuildVector.value ? ('rebuild' as const) : ('none' as const),
        busy: false
      }
    }
    return {
      type: 'info' as const,
      title: '记忆向量重建收尾中',
      text: '集合已就绪，正在确认重建状态收口；期间禁止变更平台级 Embedding。',
      action: 'none' as const,
      busy: false
    }
  }
  if (status.syncing) {
    // 走到这里说明不是重建态（重建分支在前）：这是平台级换绑后续补/手动补同步在本轮执行，
    // 因此不能说「禁止变更平台级 Embedding」（换绑续补不锁平台级）
    return {
      type: 'info' as const,
      title: '记忆向量补同步进行中',
      text: '正在补写本轮存量向量（单轮有上限），结束后进度与提示会自动刷新。',
      action: 'none' as const,
      busy: true
    }
  }
  if (status.dimensionConflict) {
    return {
      type: 'warning' as const,
      title: '记忆向量维度冲突：需要受控重建',
      text:
        `现网集合维度 ${status.collectionDimension ?? '未知'}，平台级 Embedding 快照维度 ${status.dimension || '未知'}；` +
        '不重建则 LONG 召回降级为 MySQL Top N。',
      action: canRebuildVector.value ? ('rebuild' as const) : ('none' as const),
      busy: rebuildBusy.value
    }
  }
  // 就绪但仍有待补：重建标记是进程内的，服务重启后会丢；只要还有 PENDING 就必须让操作者看到
  // 否则「上次没补完」在页面上会完全消失，只能靠翻库
  if (pendingKnown && pending > 0) {
    // 平台级换绑留下的待补：服务端已接手自动续补，这里只展示进度，不再要求人工点
    const draining = status.draining === true
    return {
      type: 'info' as const,
      title: draining ? '记忆向量补同步进行中' : '记忆向量待补同步未完成',
      text: draining
        ? `平台级 Embedding 变更后仍有 ${pending} 条存量待补，服务端正在自动推进。` +
          '可离开本页或刷新，补齐后本提示自动消失。'
        : `仍有 ${pending} 条存量待补同步（每轮有上限）；补齐前 LONG 召回可能不完整。`,
      action: draining
        ? ('none' as const)
        : reconcileBusy.value
          ? ('none' as const)
          : canContinueReconcile.value
            ? ('reconcile' as const)
            : ('none' as const),
      busy: draining || reconcileBusy.value
    }
  }
  if (!status.ready) {
    return {
      type: 'warning' as const,
      title: `记忆向量未就绪：${status.reason || '原因未知'}`,
      text: 'LONG 召回当前走 MySQL Top N；排除原因后会自动恢复（无需重启服务）。',
      action: 'none' as const,
      busy: false
    }
  }
  return null
})
/**
 * 「去重建」权限：与后端 `@SaCheckPermission(INSTANCE_PLATFORM)` 对齐。
 * 后端刻意不叠加专用权限码——重建权限只需与「设平台级」同级，
 * 危动作由 `confirmRebuild` + 集合名校验兜底；前端同样只判这一个码，避免入口可见但必然 403。
 */
const canRebuildVector = computed(
  () =>
    !vectorStatus.value?.syncing &&
    !rebuildBusy.value &&
    !reconcileBusy.value &&
    (!!vectorStatus.value?.dimensionConflict || !!vectorStatus.value?.rebuilding) &&
    user.hasPerm('model:instance:platform')
)

/** 继续补同步权限：与后端 reconcile 的 OR 语义一致（能改记忆 或 能管平台级）。 */
const canContinueReconcile = computed(
  () => user.hasPerm('memory:item:edit') || user.hasPerm('model:instance:platform')
)

/**
 * 网格 ↔ 主从浏览骨架（useBrowseFlip）：视图态、一屏容量分页、网格筛选、左栏过滤、飞卡导航。
 * 本页只提供「怎么筛、怎么取数」，骨架时序与原实现逐条一致（见 composable 头部注释）。
 * 起飞的预取与落地写数据通过 prepare / landed 钩子插入，保持「动画与请求并行」。
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
  filteredRows: filteredProviders,
  pagedRows: pagedProviders,
  applyGridFilter,
  resetGridFilter,
  onGridPageChange,
  railKeyword,
  railRows,
  onGridCardClick,
  enterListView,
  backToGrid,
  onViewModeChange
} = useBrowseFlip<ProviderCard, { keyword: string; protocol: string; status?: number }>({
  rows: providers,
  idOf: (row) => row.id,
  snapOf: snapFromProvider,
  createFilter: () => ({ keyword: '', protocol: '', status: undefined }),
  // 网格筛选：名称模糊 + 协议 + 状态（前端筛选，不另打接口）
  matchFilter: (row, filter) => {
    const key = filter.keyword.trim().toLowerCase()
    if (key && !row.name.toLowerCase().includes(key)) {
      return false
    }
    if (filter.protocol && row.protocol !== filter.protocol) {
      return false
    }
    if (filter.status === 0 || filter.status === 1) {
      if (row.status !== filter.status) {
        return false
      }
    }
    return true
  },
  // 左栏关键词：名称 / 协议中文名 / 协议码三选一命中
  matchRail: (row, key) =>
    row.name.toLowerCase().includes(key) ||
    protocolLabel(row.protocol).toLowerCase().includes(key) ||
    row.protocol.toLowerCase().includes(key),
  /*
   * 起飞即清空旧行：prepare 之后主从层立刻可见，
   * 若不清空会在整个飞行过程中显示上一个供应商的实例列表，观感错误。
   * 同时发起预取，返回 Promise 交给 landed 等待。
   */
  prepare: () => {
    query.current = 1
    instances.value = []
    total.value = 0
    return fetchInstances().catch(() => null)
  },
  // 落地即写状态：预取已完成的直接同步落地，未完成的等它返回
  landed: async (_row, prepared) => {
    instanceLoading.value = true
    const result = (await prepared) as { records: InstanceRow[]; total: number } | null
    instances.value = result?.records ?? []
    total.value = result?.total ?? 0
    instanceLoading.value = false
  },
  // 回到网格：右栏详情清空（选中态与左栏关键词由骨架清零）
  left: () => {
    instances.value = []
    total.value = 0
  }
})


const activeProvider = computed(() =>
  activeId.value > 0 ? providers.value.find((item) => item.id === activeId.value) : undefined
)

/** 网格筛选 / 分页 / 左栏过滤已由 useBrowseFlip 提供（filteredProviders / pagedProviders / railRows） */


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

/**
 * 是否属于可设为平台级的用途：只有 EMBEDDING / RERANK 参与全局唯一锁定。
 *
 * @param row 实例行
 */
function isPlatformCapable(row: InstanceRow) {
  const cap = normalizedCapability(row)
  return cap === 'EMBEDDING' || cap === 'RERANK'
}

/**
 * 归一化用途别名：历史数据里可能存在 EMBED / RE_RANK 写法，比较前统一成后端口径。
 *
 * @param row 实例行
 * @return 归一化后的用途码
 */
function normalizedCapability(row: Pick<InstanceRow, 'capability'>) {
  if (row.capability === 'EMBED') return 'EMBEDDING'
  if (row.capability === 'RE_RANK') return 'RERANK'
  return row.capability
}

/**
 * 是否满足设为平台级的前置条件：实例必须启用、连通测试成功，EMBEDDING 还需维度快照。
 * <p>
 * 为什么停用实例一票否决：平台级是全局依赖，锁一条停用实例会立刻让记忆/检索失去向量能力，
 * 且停用的平台级实例又不允许再停用/删除，只能靠取消平台级救援——入口先挡住更干脆。
 *
 * @param row 实例行
 */
function platformEligible(row: InstanceRow) {
  // 停用（status!=1）不可设为平台级：前端置灰，后端同样强校验
  if (row.status !== 1) {
    return false
  }
  // 产品拍板：连通测试成功才允许设为平台级；Embedding 还必须有可建集的维度快照
  if (row.lastTestOk !== 1) {
    return false
  }
  if (normalizedCapability(row) === 'EMBEDDING') {
    return (row.embeddingDim ?? 0) > 0
  }
  return true
}

/**
 * 平台级按钮的提示文案：解释为何不可点。
 *
 * @param row 实例行
 */
function platformActionTitle(row: InstanceRow) {
  if (row.platformLocked === 1) {
    return '取消后记忆/检索将失去该全局实例；补同步进行中时会被拒绝'
  }
  // 停用与「未测试」的原因分开写：同一个置灰按钮，用户要知道先去启用还是先去测试
  if (row.status !== 1) {
    return '实例已停用，请先启用再设为平台级'
  }
  return platformEligible(row) ? '' : '请先对 Embedding 做连通测试并成功解析向量维度'
}

/**
 * 设置 / 取消平台级。产品拍板：凡「设为平台级」一律二次确认；
 * 同用途已有平台级时升级为**换绑**确认，并且 Embedding 必须先走服务端预检：
 * 同维 → 一级确认；异维 → 换绑后再弹一次「受控重建」危险确认，绝不在普通路径里静默 DROP。
 *
 * @param row 实例行
 */
async function onPlatformLock(row: InstanceRow) {
  const locking = row.platformLocked !== 1
  if (!locking) {
    await cancelPlatformLock(row)
    return
  }
  // 停用实例不可设为平台级：按钮已置灰，这里再兜一层——避免键盘回车 / 后续新增入口绕开禁用态
  if (!platformEligible(row)) {
    ElMessage.warning(platformActionTitle(row) || '当前实例暂不满足设为平台级的条件')
    return
  }
  // 一律先走服务端预检：当前平台级实例可能不在本页列表里（分页），只有服务端知道真实换绑对象；
  // 同时拿现网集合维度判断「同维 / 异维」，避免靠列表推断导致静默换绑
  let preview: MemoryVectorRebindPreview | null = null
  try {
    const { data } = await previewPlatformRebind(row.id)
    preview = data.data ?? null
  } catch {
    // request 层已提示；预检失败不允许继续，避免「没确认就换绑」
    return
  }
  if (!preview || !preview.allowed) {
    try {
      await ElMessageBox.alert(preview?.reason || '当前状态不允许设置平台级，请稍后重试', '暂不能设置平台级', {
        type: 'warning',
        confirmButtonText: '知道了'
      })
    } catch {
      // 关闭/ESC 视同「知道了」；不产生任何后续调用
    }
    return
  }
  const isRebind = !!preview.currentInstanceId
  const confirmed = await askConfirm(buildPlatformLockConfirm(row, preview))
  if (!confirmed) return
  // 已通过二次确认；首次设置时 confirmRebind 不参与后端判断，统一带上以免换绑被误拒
  await setInstancePlatformLock(row.id, { locked: true, confirmRebind: true })
  await refreshAfterPlatformChange()
  // 异维换绑：换绑只切槽位，向量会变成 not_ready；重建是独立的危险动作，必须再确认一次
  if (preview.rebuildRequired) {
    await offerRebuildAfterRebind(preview)
  }
}

/**
 * 取消平台级：普通二次确认即可（无维度/重建步骤）。
 *
 * @param row 实例行
 */
async function cancelPlatformLock(row: InstanceRow) {
  const confirmed = await askConfirm({
    title: '取消平台级',
    subtitle: '取消后该用途失去全局实例，相关能力会降级',
    rows: [
      { label: '实例', value: row.displayName },
      { label: '用途', value: capabilityText(row.capability) },
      { label: '当前状态', value: '平台级（全局唯一）', tone: 'warning' }
    ],
    notices: [
      { type: 'warning', text: '取消后记忆 / 检索将不再使用该实例；Embedding 取消后记忆向量不再可用，需重新设置才能恢复。' }
    ],
    confirmText: '确认取消',
    danger: false
  })
  if (!confirmed) return
  await setInstancePlatformLock(row.id, { locked: false, confirmRebind: true })
  await refreshAfterPlatformChange()
}

/** 用途码转展示名；历史别名（EMBED / RE_RANK）也要能显示。 */
function capabilityText(capability: string) {
  const code = normalizedCapability({ capability })
  return CAPABILITY_LABEL[code] || code
}

/**
 * 组装「设为 / 换绑」结构化确认。以服务端预检为唯一事实来源：
 * 当前平台级实例、双方维度、现网集合维度、是否必须重建都由 preview 给出，
 * 不看当前页列表（分页时列表里可能没有那条平台级实例）。
 *
 * @param row     目标实例
 * @param preview 服务端预检结果
 * @returns 确认弹窗配置
 */
function buildPlatformLockConfirm(row: InstanceRow, preview: MemoryVectorRebindPreview) {
  const isRebind = !!preview.currentInstanceId
  const isRerank = preview.targetCapability === 'RERANK'
  const rows: ConfirmRow[] = [
    { label: '实例', value: row.displayName },
    { label: '用途', value: capabilityText(row.capability) }
  ]
  if (isRebind) {
    rows.push({ label: '当前平台级', value: preview.currentDisplayName || '当前实例', tone: 'warning' })
    rows.push({ label: '当前维度', value: preview.currentEmbeddingDim ? String(preview.currentEmbeddingDim) : '—（Rerank 无维度）' })
  }
  if (!isRerank) {
    rows.push({
      label: '目标维度',
      value: preview.targetEmbeddingDim ? String(preview.targetEmbeddingDim) : '未知'
    })
    rows.push({
      label: '现网集合维度',
      value: preview.collectionDimension ? String(preview.collectionDimension) : '尚未创建',
      tone: preview.rebuildRequired ? 'warning' : 'default'
    })
    rows.push({ label: '记忆集合', value: preview.collectionName || '未配置' })
  }

  const notices: ConfirmNotice[] = []
  if (preview.rebuildRequired) {
    // 异维：换绑本身只切槽位，真正危险的是后续重建；两级确认各说各的后果
    notices.push({
      type: 'danger',
      text: `维度不一致（${preview.collectionDimension ?? '未知'} → ${preview.targetEmbeddingDim ?? '未知'}）：确认换绑后记忆向量立即不可用，LONG 召回降级为 MySQL Top N。`
    })
    notices.push({
      type: 'warning',
      text: '换绑完成会再弹一次「重建记忆向量集合」确认；重建未完成前禁止再次变更平台级 Embedding。'
    })
  } else if (isRerank) {
    notices.push({
      type: 'info',
      text: 'Rerank 不参与向量维度：换绑只替换全局重排实例，不涉及集合重建，召回仍可正常进行。'
    })
  } else {
    notices.push({
      type: 'info',
      text: '维度一致：换绑后可能触发有界一轮补同步（单轮有条数上限），不会删除或重建集合。'
    })
  }
  // 服务端 warnings 可能包含「集合尚未创建」「旧实例退出平台级」等补充事实，去重后追加
  const extra = preview.warnings.filter(
    (text) => !notices.some((notice) => notice.text.includes(text) || text.includes(notice.text))
  )
  extra.forEach((text) => notices.push({ type: 'info', text }))
  if (!isRebind) {
    notices.push({
      type: 'info',
      text: '设置后该实例不可删除 / 停用 / 修改关键参数；更换需走换绑流程。'
    })
  }

  return {
    title: isRebind ? '换绑平台级' : '设为平台级',
    subtitle: isRebind ? '同用途已有平台级实例，本次操作将替换它' : '确认把一个实例提升为该用途的全局唯一实例',
    rows,
    notices,
    danger: false,
    confirmText: isRebind ? '确认换绑' : '确认设置'
  }
}

/**
 * 异维换绑成功后的第二级确认：是否立刻受控重建。
 * 选「稍后」不是静默放过——页面顶部的未就绪提示会保留「去重建」入口。
 *
 * @param preview 换绑预检（含集合名与新旧维度）
 */
async function offerRebuildAfterRebind(preview: MemoryVectorRebindPreview) {
  const from = preview.collectionDimension ?? null
  const to = preview.targetEmbeddingDim ?? null
  const confirmed = await askConfirm({
    title: '需要重建记忆向量集合',
    subtitle: '平台级 Embedding 已切换，但维度与现网集合不一致',
    rows: [
      { label: '记忆集合', value: preview.collectionName || '未配置' },
      { label: '原维度', value: from ? String(from) : '未知' },
      { label: '新维度', value: to ? String(to) : '未知', tone: 'warning' },
      { label: '当前影响', value: '记忆向量不可用，LONG 召回降级 MySQL Top N', tone: 'danger' }
    ],
    notices: [
      { type: 'danger', text: '重建会删除现网记忆向量集合，并把有效且未过期的 LONG 记忆全部打回 PENDING 重新补同步。' },
      { type: 'warning', text: '该操作不可撤销，重建及补同步完成前禁止变更平台级 Embedding。' },
      { type: 'info', text: '选择「稍后再说」不会自动重试；页头提示条会保留「去重建」入口。' }
    ],
    confirmText: '立即重建',
    cancelText: '稍后再说'
  })
  if (!confirmed) {
    ElMessage.warning('记忆向量暂不可用：LONG 召回降级为 MySQL Top N，可在页头提示中点「去重建」')
    await loadVectorStatus()
    return
  }
  await executeRebuild(preview.collectionName, from, to)
}

/**
 * 从页头「去重建」入口进入：用 status 暴露的集合名与维度组织危险确认。
 */
async function openRebuildFromStatus() {
  const status = vectorStatus.value
  if (!status) return
  await executeRebuild(status.collectionName, status.collectionDimension, status.dimension)
}

/**
 * 受控重建的危险确认 + 调用。文案写死不可撤销、DROP 现网集合、有效 LONG 打回 PENDING。
 *
 * @param collectionName 必须与后端配置一致
 * @param fromDim        重建前集合维度
 * @param toDim          重建后（平台级快照）维度
 */
async function executeRebuild(collectionName: string, fromDim: number | null | undefined, toDim: number | null | undefined) {
  // 重建中不允许再次发起：后端会拒，但先把入口收掉，避免点了才知道
  if (rebuildBusy.value) return
  const confirmed = await askConfirm({
    title: '重建记忆向量集合',
    subtitle: '危险操作：删除现网集合并按新维度重建，不可撤销',
    rows: [
      { label: '记忆集合', value: collectionName },
      { label: '原维度', value: fromDim ? String(fromDim) : '未知' },
      { label: '新维度', value: toDim ? String(toDim) : '未知', tone: 'warning' }
    ],
    notices: [
      { type: 'danger', text: `将 DROP 现网记忆向量集合「${collectionName}」，并按平台级 Embedding 维度 ${toDim ?? '未知'} 重新建集。` },
      { type: 'danger', text: '有效且未过期的 LONG 记忆会全部打回 PENDING，重新调用 Embedding 补写向量；补齐前召回不完整。' },
      { type: 'warning', text: '重建完成到全部补齐之间禁止设置 / 换绑 / 取消平台级 Embedding。' }
    ],
    danger: true,
    confirmText: '我已知晓，执行重建'
  })
  if (!confirmed) return
  rebuildBusy.value = true
  // 转圈只走页头提示条（见 vectorAlert.busy），不挂全屏遮罩：重建可能跑数秒到数十秒，
  // 全屏 loading 会让人误以为整页卡死，连切菜单都做不了
  let result: MemoryVectorRebuildResult | undefined
  let failed = false
  try {
    const { data } = await rebuildMemoryVector({ confirmRebuild: true, collectionName })
    result = data.data
  } catch {
    failed = true
  } finally {
    rebuildBusy.value = false
    // 先刷新状态再给提示：文案要按「服务端是否自动续补」区分，提示条也要立刻切到补同步中
    await loadVectorStatus()
  }
  if (failed) {
    // 失败必须让操作者看到原因：request 层已弹错，这里补一条「状态保持重建中」的说明
    ElMessage.error('重建未完成，记忆向量仍不可用；请在页头提示中重试或继续补同步')
    return
  }
  const pendingAfter = result?.pendingAfter ?? 0
  if (result?.rebuilding) {
    // 自动续补已开：剩余存量由服务端一轮轮推进，不需要守着页面点按钮
    if (vectorStatus.value?.autoDrain === true) {
      ElMessage.warning(
        pendingAfter > 0
          ? `集合已重建，剩余 ${pendingAfter} 条由服务端自动补同步（可离开本页，回来仍显示进行中）`
          : '集合已重建，剩余存量由服务端自动补同步（可离开本页，回来仍显示进行中）'
      )
      return
    }
    ElMessage.warning(
      pendingAfter > 0
        ? `集合已重建，仍有 ${pendingAfter} 条待补同步（单轮上限 ${result.batchLimit}）；继续补同步完成前禁止切换平台级 Embedding`
        : '集合已重建，仍有存量待补同步（条数暂时读取失败）；补齐完成前禁止切换平台级 Embedding'
    )
    return
  }
  ElMessage.success(`重建完成：重排 ${result?.requeued ?? 0} 条，本轮补同步 ${result?.synced ?? 0} 条`)
}

/**
 * 页头「继续补同步」入口：手动推进一轮。
 * 不自动连跑：单轮有硬上限且会真出站调 Embedding，连续跑可能把页面挂很久；
 * 由操作者按提示逐轮推进，每轮结束用 pendingSync 说明还剩多少。
 */
async function continueReconcile() {
  if (reconcileBusy.value || !canContinueReconcile.value) return
  reconcileBusy.value = true
  // 进度同样只体现在页头提示条（转圈 + 文案），不遮挡整页
  try {
    const { data } = await reconcileMemoryVector()
    const body = data.data as { rebuilding?: boolean; synced?: number } | undefined
    await loadVectorStatus()
    if (!body?.rebuilding && !vectorStatus.value?.rebuilding) {
      ElMessage.success(`补同步完成：本轮成功 ${body?.synced ?? 0} 条`)
      return
    }
    const pending = vectorStatus.value?.pendingSync
    ElMessage.warning(
      pending && pending > 0
        ? `本轮成功 ${body?.synced ?? 0} 条，仍有 ${pending} 条待补；可继续补同步`
        : `本轮成功 ${body?.synced ?? 0} 条，仍有存量待补；可继续补同步`
    )
  } catch {
    ElMessage.error('补同步失败，请稍后重试')
  } finally {
    reconcileBusy.value = false
  }
}

/** 平台级变更后的统一刷新：实例表、厂商锁标识、记忆向量状态。 */
async function refreshAfterPlatformChange() {
  await loadInstances()
  // 厂商卡片上的锁标识依赖 platformLocked，设置/取消后同步刷新，避免回到网格仍显示旧状态
  await loadProviders()
  await loadVectorStatus()
}

/** 重建 / 补同步期间的轮询句柄；undefined 表示当前没有轮询 */
let vectorPollTimer: number | undefined
/** 轮询请求是否在途：/status 首次会触发就绪探测，避免慢响应时 3 秒定时器叠加请求 */
let vectorPollInFlight = false

/**
 * 停止轮询：离开页面、重建完成、无权限都必须停，避免后台空转请求。
 */
function stopVectorPolling() {
  if (vectorPollTimer !== undefined) {
    window.clearInterval(vectorPollTimer)
    vectorPollTimer = undefined
  }
}

/**
 * 按当前状态决定是否轮询。
 * <p>
 * 只在 rebuilding / syncing 为真时轮询：这两种状态下 pendingSync 会下降，
 * 轮询才能把「还在进行中」如实展示出来；空闲期一律停掉，不产生多余请求。
 */
function syncVectorPolling() {
  const status = vectorStatus.value
  const active =
    !!status &&
    (status.rebuildRunning === true || status.rebuilding || status.syncing || status.draining === true)
  if (active) {
    if (vectorPollTimer === undefined) {
      vectorPollTimer = window.setInterval(async () => {
        // 上一轮还没回来就跳过本次：避免慢探测把请求越堆越多
        if (vectorPollInFlight) return
        vectorPollInFlight = true
        try {
          await loadVectorStatus()
        } finally {
          vectorPollInFlight = false
        }
      }, 3000)
    }
    return
  }
  stopVectorPolling()
}

/**
 * 读取记忆向量就绪状态。只有平台级管理员才需要这条可观测入口；
 * 无权限时不请求，避免控制台出现无意义 403。
 * <p>
 * 读取完立即按新状态决定是否轮询：重建 / 补同步期间每 3 秒刷新一次，
 * 这样进度（剩余条数）会自己往下走，刷新或切菜单回来后也能接上进行中状态。
 */
async function loadVectorStatus() {
  if (!user.hasPerm('model:instance:platform')) {
    vectorStatus.value = null
    stopVectorPolling()
    return
  }
  try {
    const { data } = await getMemoryVectorStatus()
    vectorStatus.value = data.data ?? null
  } catch {
    // 状态只用于提示；失败不影响模型页主流程
    vectorStatus.value = null
  }
  syncVectorPolling()
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
  // 页头可观测条：仅平台级管理员会真正请求，未就绪/维冲突时给出「去重建」入口
  await loadVectorStatus()
})

/**
 * KeepAlive 缓存页再次进入时补一次状态：重建 / 补同步是后端状态，可能在上次离开后才变化，
 * 只靠 onMounted 会导致「下次进来还看不到重建中」。
 * 首次挂载已由 onMounted 拉过，用 activatedOnce 跳过那次重复请求。
 */
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  // 状态读取失败只影响提示条，不打断页面
  void loadVectorStatus()
})

/**
 * 离开页面（KeepAlive 缓存或真正卸载）时停掉轮询：
 * 重建 / 补同步是服务端在推进，页面不在前台也没必要继续请求；
 * 回来时 onActivated 会重拉一次状态，若仍在进行中会自动恢复轮询。
 */
onDeactivated(() => {
  stopVectorPolling()
})

onUnmounted(() => {
  stopVectorPolling()
})
</script>

<style scoped>
/* 布局壳走 browse-workspace.css；此处只留网格卡细节与右栏业务表样式 */

.block-alert {
  margin-bottom: 12px;
}

/* 记忆向量可观测条：与网格/主从共用同一页头间距，不撑开筛选区 */
.vector-alert {
  margin-bottom: 12px;
}

.vector-alert-body {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  line-height: 1.6;
}

.vector-alert-btn {
  padding: 0;
}

/* 提示条内的进度转圈：尺寸跟随字号，不额外占高度 */
.vector-alert-spin {
  flex: 0 0 auto;
  font-size: 14px;
  color: var(--el-color-primary);
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

/* 平台级占用：与删除 × 同尺寸同位，只做提示不可点，避免误点弹删除确认 */
.card-lock {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  color: #b8860b;
  font-size: 16px;
  cursor: help;
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
