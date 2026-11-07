<template>
  <div class="page">
    <!-- 页头承载标题/说明 + 全局「重算」：用页头筛选的日期范围重算，并刷新下面三个区块 -->
    <PageHeader title="Token 用量" description="按 Agent 名称、模型名称和渠道查看 Token 流水与日汇总。">
      <template #extra>
        <el-button v-if="user.hasPerm('billing:daily:list')" :loading="rebuilding" @click="rebuild">
          重算
        </el-button>
      </template>
    </PageHeader>

    <!-- 筛选区：日期 / Agent / 模型 / 渠道 / 操作，控件同高、同右边距 -->
    <div class="filter-bar">
      <el-form class="filter-form billing-filter" inline @submit.prevent="search">
        <el-form-item label="日期">
          <el-date-picker
            v-model="dates"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            class="filter-date"
            @change="onDateRangeChange"
          />
        </el-form-item>
        <el-form-item label="Agent 名称">
          <el-select
            v-model="filter.agentId"
            clearable
            filterable
            placeholder="全部"
            class="filter-select"
            @change="search"
          >
            <el-option v-for="item in agents" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名称">
          <el-select
            v-model="filter.modelInstanceId"
            clearable
            filterable
            placeholder="全部"
            class="filter-select"
            @change="search"
          >
            <el-option
              v-for="item in instances"
              :key="item.id"
              :label="instanceOptionLabel(item)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="filter.channel" clearable placeholder="全部" class="filter-select" @change="search">
            <el-option v-for="item in CHANNEL_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="search">
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

    <section class="panel">
      <div class="panel-head">
        <div class="panel-title">
          <h2>消耗趋势</h2>
          <span class="panel-sub">柱状为输入 / 输出，折线为当日总量；跟随上方筛选条件</span>
        </div>
        <el-radio-group v-model="trendDays" size="small" @change="loadTrend">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
      </div>
      <div v-loading="trendLoading">
        <TokenTrendChart :points="trendPoints" />
      </div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div class="panel-title">
          <h2>日汇总</h2>
          <span v-if="activeDate" class="date-chip">
            已按 {{ activeDate }} 过滤下方流水
            <button type="button" class="chip-clear" title="取消日期过滤" @click="clearDateFilter">×</button>
          </span>
          <span v-else class="panel-sub">点击任意一行，可按当天查看下方流水</span>
        </div>
      </div>
      <el-table
        v-loading="dailyLoading"
        :data="dailyRows"
        empty-text="没有日汇总"
        highlight-current-row
        row-class-name="daily-row"
        @row-click="onDailyRowClick"
      >
        <el-table-column v-bind="dailyIndexColumnProps" />
        <el-table-column prop="bizDate" label="日期" width="120" align="center" />
        <el-table-column label="Agent 名称" min-width="180" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="agent-cell">
              <span class="agent-name">{{ agentLabel(row) }}</span>
              <el-tag
                v-if="agentStatusTag(row)"
                size="small"
                effect="plain"
                :class="agentTagClass(row)"
              >
                {{ agentStatusTag(row) }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模型名称" min-width="240" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="model-cell">
              <span class="model-name">{{ modelLabel(row) }}</span>
              <el-tag
                v-if="modelStatusTag(row)"
                size="small"
                effect="plain"
                :class="modelTagClass(row)"
              >
                {{ modelStatusTag(row) }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="输入 (Token)" width="130" align="right">
          <template #default="{ row }">{{ formatNumber(row.tokenPrompt) }}</template>
        </el-table-column>
        <el-table-column label="输出 (Token)" width="130" align="right">
          <template #default="{ row }">{{ formatNumber(row.tokenCompletion) }}</template>
        </el-table-column>
        <el-table-column label="总计 (Token)" width="130" align="right">
          <template #default="{ row }">
            <span class="total-cell">{{ formatNumber(row.tokenTotal) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="100" align="center">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </el-table-column>
        <el-table-column v-if="SHOW_PRICE" label="金额" min-width="120" align="center">
          <template #default="{ row }">{{ money(row.amount, row.currency) }}</template>
        </el-table-column>
      </el-table>
      <TablePager
        v-if="dailyTotal > dailyQuery.size"
        v-model:current="dailyQuery.current"
        v-model:size="dailyQuery.size"
        :total="dailyTotal"
        @change="loadDaily"
      />
    </section>

    <section class="panel">
      <div class="panel-head">
        <div class="panel-title">
          <h2>流水</h2>
          <span v-if="activeDate" class="panel-sub">仅显示 {{ activeDate }} 的记录</span>
        </div>
      </div>
      <el-table v-loading="ledgerLoading" :data="ledgerRows" empty-text="没有流水">
        <el-table-column v-bind="ledgerIndexColumnProps" />
        <el-table-column prop="createTime" label="时间" min-width="170" align="center" />
        <el-table-column label="Agent 名称" min-width="180" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="agent-cell">
              <span class="agent-name">{{ agentLabel(row) }}</span>
              <el-tag
                v-if="agentStatusTag(row)"
                size="small"
                effect="plain"
                :class="agentTagClass(row)"
              >
                {{ agentStatusTag(row) }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模型名称" min-width="240" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="model-cell">
              <span class="model-name">{{ modelLabel(row) }}</span>
              <el-tag
                v-if="modelStatusTag(row)"
                size="small"
                effect="plain"
                :class="modelTagClass(row)"
              >
                {{ modelStatusTag(row) }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Token 用量" min-width="160" align="right">
          <template #default="{ row }">
            <div class="token-cell">
              <span class="total-cell">{{ formatNumber(row.tokenTotal) }}</span>
              <span class="token-split">
                输入 {{ formatNumber(row.tokenPrompt) }} · 输出 {{ formatNumber(row.tokenCompletion) }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="100" align="center">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">{{ row.status === 1 ? '正常' : '作废' }}</template>
        </el-table-column>
      </el-table>
      <TablePager
        v-if="ledgerTotal > ledgerQuery.size"
        v-model:current="ledgerQuery.current"
        v-model:size="ledgerQuery.size"
        :total="ledgerTotal"
        @change="loadLedgers"
      />
    </section>

    <!-- 单价区块整体受 SHOW_PRICE 控制：本期只计量、不对外暴露计价配置 -->
    <section v-if="SHOW_PRICE" class="panel">
      <div class="panel-head">
        <h2>单价</h2>
        <el-button v-if="user.hasPerm('billing:price:add')" type="primary" @click="openPrice()">+ 新建单价</el-button>
      </div>
      <el-table v-loading="priceLoading" :data="prices" empty-text="没有单价规则">
        <el-table-column prop="name" label="名称" min-width="140" align="center" show-overflow-tooltip />
        <el-table-column label="模型名称" min-width="180" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.modelInstanceId ? modelNameFromId(row.modelInstanceId) : '默认' }}
          </template>
        </el-table-column>
        <el-table-column prop="pricePrompt" label="输入单价" width="130" align="center" />
        <el-table-column prop="priceCompletion" label="输出单价" width="130" align="center" />
        <el-table-column label="生效期" min-width="280" align="center" show-overflow-tooltip>
          <template #default="{ row }">{{ row.effectiveFrom }} ~ {{ row.effectiveTo || '长期' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center">
          <template #default="{ row }">
            <el-switch
              v-if="user.hasPerm('billing:price:enable')"
              :model-value="row.status === 1"
              @change="(on: string | number | boolean) => onPriceSwitch(row, on === true)"
            />
            <el-tag v-else size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
            <el-button v-if="user.hasPerm('billing:price:edit')" link type="primary" @click="openPrice(row)">编辑</el-button>
            <el-popconfirm
              v-if="user.hasPerm('billing:price:remove')"
              :title="`删除单价「${row.name}」？`"
              @confirm="removePrice(row)"
            >
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <TablePager
        v-if="priceTotal > priceQuery.size"
        v-model:current="priceQuery.current"
        v-model:size="priceQuery.size"
        :total="priceTotal"
        @change="loadPrices"
      />
    </section>

    <PriceFormDialog v-if="SHOW_PRICE" v-model="priceOpen" :draft="editing" :instances="instances" @submit="savePrice" />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'BillingUsage' })
/**
 * Token 用量页。所属域：billing。
 * 结构：筛选 → 消耗趋势 → 日汇总 → 流水（→ 单价，当前隐藏）。
 * 展示约束：Agent / 模型一律显示名称；token 千分位；channel 显示中文。
 * 联动：点日汇总某行 → 流水按该业务日过滤；清除后回到筛选区日期范围。
 * 「单价」区块与日汇总「金额」列由 SHOW_PRICE 统一控制：本期只计量，不展示计价。
 */
import { onMounted, reactive, ref } from 'vue'
import { RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { pageAgents } from '@/api/agent'
import {
  changePriceStatus,
  createPrice,
  deletePrice,
  pageDaily,
  pageDailyTrend,
  pageLedgers,
  pagePrices,
  rebuildDaily,
  updatePrice
} from '@/api/billing'
import { getInstance, pageInstances } from '@/api/model'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import PriceFormDialog from '@/components/billing/PriceFormDialog.vue'
import TokenTrendChart from '@/components/billing/TokenTrendChart.vue'
import { useUserStore } from '@/stores/user'
import type { AgentCard } from '@/types/agent'
import type { DailyRow, LedgerRow, PriceRow, PriceSave, TrendPoint } from '@/types/billing'
import type { InstanceRow } from '@/types/model'

const user = useUserStore()

/**
 * 单价能力总开关。
 * 置为 false 时隐藏「单价」区块（含新建/编辑/删除/启停）与日汇总的「金额」列，
 * 前端也不再请求单价列表；恢复计价展示只需把这里改回 true。
 */
const SHOW_PRICE = false

/** 渠道码 → 中文。库内仍是 CONSOLE / APP_API，仅展示层翻译。 */
const CHANNEL_OPTIONS = [
  { value: 'CONSOLE', label: '控制台' },
  { value: 'APP_API', label: 'API调用' }
] as const

const CHANNEL_LABEL: Record<string, string> = {
  CONSOLE: '控制台',
  APP_API: 'API调用'
}

const dates = ref<[string, string] | null>(null)
const filter = reactive<{ agentId?: number; modelInstanceId?: number; channel?: string }>({})
const agents = ref<AgentCard[]>([])
const instances = ref<InstanceRow[]>([])
/** 兜底名称缓存：列表接口没带回、选项里也没有时由详情补齐 */
const agentNameMap = ref<Map<number, string>>(new Map())
const modelNameMap = ref<Map<number, string>>(new Map())

const trendLoading = ref(false)
const trendPoints = ref<TrendPoint[]>([])
/** 趋势图默认看近 7 天，可切 30 天 */
const trendDays = ref<7 | 30>(7)

const dailyLoading = ref(false)
const ledgerLoading = ref(false)
const priceLoading = ref(false)
const rebuilding = ref(false)
const dailyRows = ref<DailyRow[]>([])
const ledgerRows = ref<LedgerRow[]>([])
const prices = ref<PriceRow[]>([])
const dailyTotal = ref(0)
const ledgerTotal = ref(0)
const priceTotal = ref(0)
const dailyQuery = reactive({ current: 1, size: 10 })
const ledgerQuery = reactive({ current: 1, size: 10 })
const priceQuery = reactive({ current: 1, size: 10 })
/** 日汇总表最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps: dailyIndexColumnProps } = useListIndexColumn(() => dailyQuery.current, () => dailyQuery.size)
/** 流水表最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps: ledgerIndexColumnProps } = useListIndexColumn(() => ledgerQuery.current, () => ledgerQuery.size)
const priceOpen = ref(false)
const editing = ref<PriceRow | null>(null)

/** 从日汇总点选出来的业务日；有值时流水只显示当天。 */
const activeDate = ref('')
/** 记住筛选区的日期范围，取消行过滤后流水要回到它。 */
const filterDates = ref<[string, string] | null>(null)

/** 筛选条条件（不含日期，日期单独管理以便行联动覆盖）。 */
function baseParams() {
  return {
    agentId: filter.agentId,
    modelInstanceId: filter.modelInstanceId,
    channel: filter.channel
  }
}

/** 日汇总用筛选区日期。 */
function dailyParams() {
  return {
    ...baseParams(),
    dateFrom: filterDates.value?.[0],
    dateTo: filterDates.value?.[1]
  }
}

/**
 * 流水日期条件：点选某天后收敛到当天，否则跟随筛选区范围。
 * 这是「点击日汇总过滤流水」的唯一落点。
 */
function ledgerParams() {
  return {
    ...baseParams(),
    dateFrom: activeDate.value || filterDates.value?.[0],
    dateTo: activeDate.value || filterDates.value?.[1]
  }
}

/** 趋势区间：有筛选日期就跟随区间；否则按所选天数回溯（近 7 / 30 天）。 */
function trendParams() {
  const start = filterDates.value?.[0]
  const end = filterDates.value?.[1]
  if (start && end) {
    return { ...baseParams(), dateFrom: start, dateTo: end }
  }
  // 无筛选日期时以今天为锚点回溯 N 天，与右上角天数切换保持一致
  const anchor = new Date()
  const from = new Date(anchor)
  from.setDate(from.getDate() - (trendDays.value - 1))
  return { ...baseParams(), dateFrom: toDateText(from), dateTo: toDateText(anchor) }
}

/**
 * Date → yyyy-MM-dd（本地时区）。
 * 不用 toISOString：那是 UTC，东八区会少一天。
 *
 * @param date 日期对象
 * @return 日期文本
 */
function toDateText(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 下拉：供应商 / 实例名；没有供应商就只显示实例名。 */
function instanceOptionLabel(item: InstanceRow) {
  const name = item.displayName?.trim() || '未命名实例'
  const provider = item.providerName?.trim()
  return provider ? `${provider} / ${name}` : name
}

/** 与后端对齐：仅「查无行」时整格占位；有行保留真名旁挂 Tag */
const DELETED_MODEL_NAME = '已删除模型'

type NamedRef = {
  agentId?: number | null
  agentName?: string | null
  agentDeleted?: boolean | null
  agentDisabled?: boolean | null
  modelInstanceId?: number | null
  modelInstanceName?: string | null
  providerName?: string | null
  modelDeleted?: boolean | null
  modelDisabled?: boolean | null
}

/**
 * Agent 列文案：接口 → 缓存 → 「未知 Agent」。
 * 软删/停用也保留真名；只有真查无行（硬删/脏数据）才落到「未知 Agent」。
 * 绝不回退原始 id：id 对使用者没有业务含义。
 */
function agentLabel(row: NamedRef) {
  if (row.agentId == null) {
    return '—'
  }
  const fromRow = row.agentName?.trim()
  if (fromRow) {
    return fromRow
  }
  return agentNameMap.value.get(Number(row.agentId)) || '未知 Agent'
}

/**
 * Agent 列旁挂 Tag：删除 > 停用。
 * 无真名（整格「未知 Agent」）时不叠 Tag，避免误导成仅停用。
 *
 * @return 「已删除」|「已停用」| null
 */
function agentStatusTag(row: NamedRef): '已删除' | '已停用' | null {
  if (row.agentId == null) {
    return null
  }
  const hasName = Boolean(row.agentName?.trim() || agentNameMap.value.get(Number(row.agentId)))
  if (!hasName) {
    return null
  }
  if (row.agentDeleted) {
    return '已删除'
  }
  if (row.agentDisabled) {
    return '已停用'
  }
  return null
}

/** Agent Tag 样式类：已删除红底，已停用灰底（与模型列共用样式）。 */
function agentTagClass(row: NamedRef) {
  return agentStatusTag(row) === '已删除' ? 'tag-deleted' : 'tag-disabled'
}

/**
 * 模型列真名：「供应商 / 实例名」。
 * 有行用原名；仅查无行才整格「已删除模型」。删/停状态另见 Tag。
 */
function modelLabel(row: NamedRef) {
  if (row.modelInstanceId == null) {
    return '—'
  }
  const instanceName = row.modelInstanceName?.trim()
  if (instanceName) {
    if (instanceName === DELETED_MODEL_NAME) {
      return DELETED_MODEL_NAME
    }
    const provider = row.providerName?.trim()
    return provider ? `${provider} / ${instanceName}` : instanceName
  }
  return modelNameMap.value.get(Number(row.modelInstanceId)) || DELETED_MODEL_NAME
}

/**
 * 旁挂 Tag：删除 > 停用。查无行（整格占位）不加 Tag。
 *
 * @return 「已删除」|「已停用」| null
 */
function modelStatusTag(row: NamedRef): '已删除' | '已停用' | null {
  if (row.modelInstanceId == null) {
    return null
  }
  if (row.modelDeleted) {
    const name = row.modelInstanceName?.trim()
    // 无行占位不加 Tag，避免「已删除模型」旁再叠「已删除」
    if (!name || name === DELETED_MODEL_NAME) {
      return null
    }
    return '已删除'
  }
  if (row.modelDisabled) {
    return '已停用'
  }
  return null
}

/** Tag 样式类：已删除红底，已停用灰底。 */
function modelTagClass(row: NamedRef) {
  return modelStatusTag(row) === '已删除' ? 'tag-deleted' : 'tag-disabled'
}

/** 单价表只存 id，走选项/缓存；不在未删下拉里则按已删占位。 */
function modelNameFromId(id: number) {
  const hit = instances.value.find((item) => Number(item.id) === Number(id))
  if (hit) {
    return instanceOptionLabel(hit)
  }
  return modelNameMap.value.get(Number(id)) || DELETED_MODEL_NAME
}

/**
 * token 数值千分位。
 *
 * @param value 原始数值，空值按 0
 * @return 形如 1,833,793 的文本
 */
function formatNumber(value?: number | null) {
  const num = Number(value ?? 0)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : '0'
}

/** 渠道码转中文；未知码原样显示，便于发现脏数据。 */
function channelLabel(code?: string | null) {
  if (!code) {
    return '—'
  }
  return CHANNEL_LABEL[code] || code
}

function money(amount?: number | null, currency?: string | null) {
  if (amount == null) {
    return '—'
  }
  return currency ? `${amount} ${currency}` : String(amount)
}

/**
 * 用列表行和选项种子缓存名称，供筛选下拉与单价表复用。
 * Agent 名称后端已按「含删」解析：查无行时不做 getAgent 补拉，
 * 否则已硬删的历史 id 会触发全局错误 toast（本次修复的报错来源）。
 */
async function rememberNames(rows: NamedRef[]) {
  for (const row of rows) {
    if (row.agentId != null && row.agentName?.trim()) {
      agentNameMap.value.set(Number(row.agentId), row.agentName.trim())
    }
    if (row.modelInstanceId != null && row.modelInstanceName?.trim()) {
      const name = row.modelInstanceName.trim()
      if (name === DELETED_MODEL_NAME) {
        modelNameMap.value.set(Number(row.modelInstanceId), DELETED_MODEL_NAME)
        continue
      }
      const provider = row.providerName?.trim()
      modelNameMap.value.set(
        Number(row.modelInstanceId),
        provider ? `${provider} / ${name}` : name
      )
    }
  }

  // 已有后端名称（含删/停真名）或占位的不再补拉；筛选下拉仍只来自未删列表
  const missingModels = [
    ...new Set(
      rows
        .filter((row) => !row.modelDeleted && !row.modelDisabled)
        .map((row) => row.modelInstanceId)
        .filter((id): id is number => id != null && !modelNameMap.value.has(Number(id)))
        .map(Number)
    )
  ]

  await Promise.all(
    missingModels.map(async (id) => {
      try {
        const { data } = await getInstance(id)
        modelNameMap.value.set(id, instanceOptionLabel(data.data))
      } catch {
        // 无权限或已删除：写入占位，避免反复请求与 toast 轰炸
        modelNameMap.value.set(id, DELETED_MODEL_NAME)
      }
    })
  )
}

function seedMapsFromOptions() {
  for (const item of agents.value) {
    if (item.name?.trim()) {
      agentNameMap.value.set(Number(item.id), item.name.trim())
    }
  }
  for (const item of instances.value) {
    modelNameMap.value.set(Number(item.id), instanceOptionLabel(item))
  }
}

/** 拉趋势：接口已按天补 0，前端只需映射字段。 */
async function loadTrend() {
  trendLoading.value = true
  try {
    const { data } = await pageDailyTrend(trendParams())
    trendPoints.value = (data.data || []).map((point) => ({
      date: point.bizDate,
      prompt: Number(point.tokenPrompt ?? 0),
      completion: Number(point.tokenCompletion ?? 0),
      total: Number(point.tokenTotal ?? 0)
    }))
  } catch {
    trendPoints.value = []
  } finally {
    trendLoading.value = false
  }
}

async function loadDaily() {
  dailyLoading.value = true
  try {
    const { data } = await pageDaily({ ...dailyParams(), ...dailyQuery })
    dailyRows.value = data.data.records
    dailyTotal.value = data.data.total
    await rememberNames(dailyRows.value)
  } finally {
    dailyLoading.value = false
  }
}

async function loadLedgers() {
  ledgerLoading.value = true
  try {
    const { data } = await pageLedgers({ ...ledgerParams(), ...ledgerQuery })
    ledgerRows.value = data.data.records
    ledgerTotal.value = data.data.total
    await rememberNames(ledgerRows.value)
  } finally {
    ledgerLoading.value = false
  }
}

async function loadPrices() {
  priceLoading.value = true
  try {
    const { data } = await pagePrices(priceQuery)
    prices.value = data.data.records
    priceTotal.value = data.data.total
    await rememberNames(prices.value.map((row) => ({ modelInstanceId: row.modelInstanceId })))
  } finally {
    priceLoading.value = false
  }
}

/** 查询：三个区块一起按当前筛选刷新，并回到第一页。 */
function search() {
  filterDates.value = dates.value
  // 主动查询视为放弃行级日期过滤，回到筛选区范围，避免条件互相打架
  activeDate.value = ''
  dailyQuery.current = 1
  ledgerQuery.current = 1
  void Promise.all([loadTrend(), loadDaily(), loadLedgers()])
}

/**
 * 点日汇总某行：流水按该业务日过滤。
 * 再点同一行则取消过滤，恢复筛选区日期范围。
 *
 * @param row 日汇总行
 */
function onDailyRowClick(row: DailyRow) {
  const date = String(row.bizDate || '')
  if (!date) {
    return
  }
  activeDate.value = activeDate.value === date ? '' : date
  ledgerQuery.current = 1
  void loadLedgers()
}

/** 清除行级日期过滤，流水回到筛选区日期范围。 */
function clearDateFilter() {
  activeDate.value = ''
  ledgerQuery.current = 1
  void loadLedgers()
}

/**
 * 日期范围变化即查询：筛选类选择（下拉 / 日期）统一采用「选完就查」，
 * 只保留「查询」按钮作为显式重查入口。清空日期同样触发一次（回到近 N 天趋势）。
 */
function onDateRangeChange() {
  activeDate.value = ''
  search()
}

/** 清空筛选条件后重新查询。 */
function onReset() {
  dates.value = null
  filterDates.value = null
  activeDate.value = ''
  filter.agentId = undefined
  filter.modelInstanceId = undefined
  filter.channel = undefined
  dailyQuery.current = 1
  ledgerQuery.current = 1
  void Promise.all([loadTrend(), loadDaily(), loadLedgers()])
}

/** 重算使用当前筛选的日期范围，权限与日汇总查询相同。 */
async function rebuild() {
  if (!dates.value?.[0] || !dates.value?.[1]) {
    ElMessage.warning('请选择日期范围')
    return
  }
  rebuilding.value = true
  try {
    await rebuildDaily({ dateFrom: dates.value[0], dateTo: dates.value[1] })
    ElMessage.success('已重算')
    await Promise.all([loadTrend(), loadDaily(), loadLedgers()])
  } finally {
    rebuilding.value = false
  }
}

function openPrice(row?: PriceRow) {
  editing.value = row || null
  priceOpen.value = true
}

async function savePrice(payload: PriceSave, done: () => void) {
  try {
    if (editing.value) {
      await updatePrice(editing.value.id, payload)
    } else {
      await createPrice(payload)
    }
    ElMessage.success('已保存')
    priceOpen.value = false
    await loadPrices()
  } finally {
    done()
  }
}

async function onPriceSwitch(row: PriceRow, enabled: boolean) {
  await changePriceStatus(row.id, enabled ? 1 : 0)
  await loadPrices()
}

async function removePrice(row: PriceRow) {
  await deletePrice(row.id)
  ElMessage.success('已删除')
  await loadPrices()
}

onMounted(async () => {
  // 筛选项需要 Agent / 模型列表；没有对应权限时下拉留空，表格仍可靠接口带回的名称。
  agents.value = await loadOptions(() => pageAgents({ current: 1, size: 100 }))
  instances.value = await loadOptions(() => pageInstances({ current: 1, size: 100 }))
  seedMapsFromOptions()
  const tasks = [loadTrend(), loadDaily(), loadLedgers()]
  if (SHOW_PRICE) {
    tasks.push(loadPrices())
  }
  await Promise.all(tasks)
})

async function loadOptions<T>(load: () => Promise<{ data: { data: { records: T[] } } }>) {
  try {
    const { data } = await load()
    return data.data.records
  } catch {
    return []
  }
}
</script>

<style scoped>
.panel {
  margin-bottom: 16px;
  padding: 16px;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: var(--ah-radius-lg);
  box-shadow: var(--ah-shadow);
}

.panel:last-of-type {
  margin-bottom: 0;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.panel-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.panel-sub {
  color: var(--ah-text-secondary);
  font-size: 12px;
}

/* 行过滤提示：浅主色底，附一键清除 */
.date-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 4px 1px 8px;
  color: var(--ah-primary);
  font-size: 12px;
  background: rgba(99, 91, 255, 0.08);
  border-radius: 4px;
}

.chip-clear {
  padding: 0 4px;
  color: var(--ah-primary);
  font-size: 13px;
  line-height: 1;
  background: transparent;
  border: none;
  cursor: pointer;
}

.chip-clear:hover {
  color: var(--el-color-danger);
}

/*
 * 筛选区对齐：日期 / 下拉 / 按钮统一 32px 高。
 * 项目全局 .filter-bar 已用 gap 控制间距，这里不再加 margin-right，避免出现双倍间距。
 */
.filter-bar .filter-date,
.filter-bar .filter-select {
  height: 32px;
}

/*
 * 本页筛选控件偏多（日期 + 3 个下拉 + 2 个按钮），宽度必须收一点，
 * 否则在 1366 宽的屏上「查询 / 重置」会被 .filter-form 的 flex-wrap 挤到第二行。
 * 按 1366（主区可用约 1070px）反算过总宽，留了余量。
 */
.billing-filter {
  gap: 10px;
}

.billing-filter .filter-date {
  width: 180px;
}

.billing-filter .filter-select {
  width: 132px;
}

/* 按钮不参与收缩，也不换行到下一行；本页按钮收窄一点，省出横向空间 */
.billing-filter :deep(.el-form-item:last-child) {
  flex: none;
}

.billing-filter :deep(.el-button) {
  min-width: 76px;
  padding: 0 10px;
}

.filter-bar :deep(.el-date-editor),
.filter-bar :deep(.el-select__wrapper) {
  height: 32px;
}

.filter-bar :deep(.el-button) {
  height: 32px;
  margin-left: 0;
}

/* 查询与重置之间保持与其它控件一致的 12px */
.filter-bar :deep(.el-form-item:last-child .el-form-item__content) {
  gap: 12px;
}

/* 日期框宽度统一由上方 .billing-filter 规则给定，这里不再重复 */

/* 数字列右对齐，末位对齐更易扫读 */
.total-cell {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--ah-text);
}

.token-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.4;
}

.token-split {
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

/* 日汇总可点：整行手型 + 悬浮浅底，提示可下钻 */
:deep(.daily-row) {
  cursor: pointer;
}

/* 悬浮说明里的长句不撑宽 tooltip */
:deep(.el-tooltip__popper) {
  max-width: 360px;
}

/* Agent / 模型列：真名 + 旁挂删/停 Tag */
.model-cell,
.agent-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  max-width: 100%;
}

.model-name,
.agent-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-deleted {
  flex-shrink: 0;
  --el-tag-text-color: #b91c1c;
  --el-tag-border-color: #fecaca;
  --el-tag-bg-color: #fef2f2;
}

.tag-disabled {
  flex-shrink: 0;
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}
</style>
