<template>
  <div class="page">
    <!-- 页头：本页只做入口与总览，新建定义仍回 Agent 管理，避免双入口 -->
    <PageHeader
      title="多智能体"
      description="编排根（ROUTER）总览：点一行进入该 Agent 的「编排」Tab 编辑候选与模板；「试跑」开全屏对话页。本页不新建定义。"
    />

    <!-- 筛选：关键字匹配名称/编码；状态跟 agent_def 启停 -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" class="filter-input" clearable placeholder="名称或编码" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="filter-select" clearable placeholder="全部">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="search">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" @click="resetFilter">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <section class="panel">
      <el-table
        v-loading="loading"
        :data="rows"
        class="ma-table"
        :row-class-name="() => 'ma-row'"
        @row-click="openOrchestration"
      >
        <!-- 名称：主信息，点整行与点此列都进编排 Tab -->
        <el-table-column label="名称" min-width="200" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <button type="button" class="cell-name" @click.stop="openOrchestration(row)">{{ row.name }}</button>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :class="row.status === 1 ? 'tag-on' : 'tag-off'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模板" min-width="140" align="center">
          <template #default="{ row }">
            <el-tooltip :content="maTemplateHint(row.maTemplate)" placement="top">
              <el-tag size="small" effect="plain" :type="maTemplateTagType(row.maTemplate)">
                {{ maTemplateLabel(row.maTemplate) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="候选数" min-width="110" align="center">
          <template #default="{ row }">
            <span class="cell-count">{{ row.candidateCount }}</span>
            <span class="cell-count-unit">个启用</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="180" align="center">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="170" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openOrchestration(row)">打开编排</el-button>
            <!-- 试跑进独立全屏页：本页不做对话，只给入口 -->
            <el-button v-if="canTrial" link type="primary" @click.stop="openTrial(row)">试跑</el-button>
          </template>
        </el-table-column>

        <!-- 空态：无筛选时引导去 Agent 管理把运行模式设为 ROUTER；有筛选时提示清空条件 -->
        <template #empty>
          <div class="empty-guide">
            <template v-if="hasFilter">
              <p class="empty-guide__title">没有匹配的多智能体</p>
              <p class="empty-guide__text">试试清空关键字或状态筛选。</p>
              <el-button @click="resetFilter">清空筛选</el-button>
            </template>
            <template v-else>
              <p class="empty-guide__title">还没有多智能体编排</p>
              <p class="empty-guide__text">
                多智能体 = 运行模式为 ROUTER 的 Agent。去 Agent 管理把 Agent 的运行模式设为 ROUTER 后，
                这里会出现它，并可继续配置候选。
              </p>
              <el-button type="primary" @click="goAgentManage">去 Agent 管理</el-button>
            </template>
          </div>
        </template>
      </el-table>
      <TablePager v-model:current="query.current" v-model:size="query.size" :total="total" @change="loadRows" />
    </section>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'MaList' })

/**
 * 多智能体列表页。所属域：agent（前端入口层）。
 *
 * <p>职责：只列运行模式为 ROUTER 的编排根，展示模板与启用候选数；
 * 点行或「打开编排」跳既有 Agent 详情并激活「编排」Tab。
 *
 * <p>边界：
 * - 不发任何执行/试跑请求，也不在本页编辑编排；
 * - 不提供「新建多智能体定义」：定义仍在 Agent 管理，避免造第二套 Agent；
 * - 数据源为后端薄聚合接口，不自行拉全部 Agent 再逐条补路由计数。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { pageMultiAgents } from '@/api/ma'
import { useUserStore } from '@/stores/user'
import type { MaListRow } from '@/types/ma'
import { maTemplateHint, maTemplateLabel, maTemplateTagType } from '@/utils/maList'
import { maTrialPath } from '@/utils/maTrial'

const router = useRouter()
const user = useUserStore()

/** 试跑权限：与 Agent 详情「对话」按钮同口径；无权限时不渲染试跑入口 */
const canTrial = computed(() => user.hasPerm('runtime:trial:run') || user.hasPerm('runtime:trial:list'))

const loading = ref(false)
const listError = ref('')
const rows = ref<MaListRow[]>([])
const total = ref(0)
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  status: undefined as number | undefined
})

/** 状态筛选归一：clearable 清空后可能是 ''/null，只认 0 / 1；0 不能按 falsy 丢掉 */
function statusParam(): number | undefined {
  return query.status === 0 || query.status === 1 ? query.status : undefined
}

/** 是否有筛选条件：决定空态是「无数据引导」还是「无匹配」 */
const hasFilter = computed(() => Boolean(query.keyword.trim()) || statusParam() !== undefined)

/** 时间展示：后端给 yyyy-MM-dd HH:mm:ss，直接截断毫秒；空值给占位 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

/** 分页加载；失败只出中文错误态，不伪造空数据成功 */
async function loadRows() {
  loading.value = true
  listError.value = ''
  try {
    const { data } = await pageMultiAgents({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: statusParam()
    })
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
  } catch {
    listError.value = '多智能体列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询回第一页，避免停在越界页 */
function search() {
  query.current = 1
  void loadRows()
}

/** 重置筛选并重查 */
function resetFilter() {
  query.keyword = ''
  query.status = undefined
  search()
}

/**
 * 跳转既有 Agent 详情并激活「编排」Tab。
 * 约定用 query.tab=orchestration，详情壳读 route.query.tab 后落在编排页；
 * 本页不复制编排编辑器，编辑能力始终只有详情页一份。
 *
 * @param row 被点击的多智能体行（el-table row-click 会带 row/column/event 三参）
 */
function openOrchestration(row: MaListRow) {
  void router.push({ path: `/agent/list/${row.id}`, query: { tab: 'orchestration' } })
}

/**
 * 进入该编排根的独立试跑页。
 * 运行时再按 run_mode 校验一次（本页数据源已限定 ROUTER），失败由试跑页中文提示并回列表。
 *
 * @param row 被点击的多智能体行
 */
function openTrial(row: MaListRow) {
  void router.push(maTrialPath(row.id))
}

/** 空态引导：去 Agent 管理创建 ROUTER */
function goAgentManage() {
  void router.push('/agent/list')
}

onMounted(loadRows)
</script>

<style scoped>
.block-alert {
  margin-bottom: 12px;
}

.ma-table :deep(.ma-row) {
  cursor: pointer;
}

.cell-name {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ah-text-primary, #111827);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.cell-name:hover {
  color: var(--el-color-primary);
}

.cell-count {
  font-weight: 600;
}

.cell-count-unit {
  margin-left: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.tag-on {
  --el-tag-text-color: #10b981;
  --el-tag-border-color: #a7f3d0;
  --el-tag-bg-color: #ecfdf5;
}

.tag-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}

.empty-guide {
  padding: 18px 12px;
  text-align: center;
}

.empty-guide__title {
  margin: 0 0 6px;
  color: var(--ah-text-primary, #111827);
  font-size: 14px;
  font-weight: 600;
}

.empty-guide__text {
  max-width: 460px;
  margin: 0 auto 14px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  line-height: 1.6;
}
</style>
