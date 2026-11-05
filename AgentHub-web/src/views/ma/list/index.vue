<template>
  <div class="page">
    <!-- 页头：本页是方案真源入口；绑根与虚拟根都可直接试跑（刀2 起） -->
    <PageHeader title="多智能体" description="方案总览：新建/编辑方案与候选；绑根与虚拟根都可直接运行，执行读方案配置。">
      <template #extra>
        <el-button v-if="canEdit" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建方案
        </el-button>
      </template>
    </PageHeader>

    <!-- 筛选：关键字匹配名称/编码；状态看 ma_def.enabled；是否绑根区分虚拟根 -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" class="filter-input" clearable placeholder="名称或编码" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.enabled" class="filter-select" clearable placeholder="全部">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板">
          <el-select v-model="query.template" class="filter-select" clearable placeholder="全部">
            <el-option v-for="item in MA_LIST_TEMPLATES" :key="item" :label="maTemplateLabel(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="绑根">
          <el-select v-model="query.hasRoot" class="filter-select" clearable placeholder="全部">
            <el-option label="已绑 ROUTER" :value="true" />
            <el-option label="虚拟根" :value="false" />
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
      <el-table v-loading="loading" :data="rows" class="ma-table" :row-class-name="() => 'ma-row'" @row-click="openEdit">
        <!-- 最左序号列：跨页连续，纯展示，不参与接口排序 -->
        <el-table-column v-bind="indexColumnProps" />
        <!-- 名称/编码：方案主键之外的业务标识，点名称与点整行都进编辑抽屉 -->
        <el-table-column label="名称" min-width="190" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <button type="button" class="cell-name" @click.stop="openEdit(row)">{{ row.name }}</button>
          </template>
        </el-table-column>
        <el-table-column label="编码" min-width="180" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-code">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模板" min-width="130" align="center">
          <template #default="{ row }">
            <el-tooltip :content="maTemplateHint(row.template)" placement="top">
              <el-tag size="small" effect="plain" :type="maTemplateTagType(row.template)">
                {{ maTemplateLabel(row.template) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="100" align="center">
          <template #default="{ row }">
            <!-- 列表直接切换状态：只改 ma_def；启用会校验同根唯一，失败自动回滚 -->
            <span @click.stop>
              <el-switch
                v-if="canEdit"
                :model-value="row.enabled"
                :active-value="1"
                :inactive-value="0"
                size="small"
                @change="() => toggleEnabled(row)"
              />
              <el-tag v-else size="small" effect="plain" :class="row.enabled === 1 ? 'tag-on' : 'tag-off'">
                {{ row.enabled === 1 ? '启用' : '停用' }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="绑定根" min-width="180" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.rootAgentId" class="cell-root">{{ row.rootAgentName || `Agent #${row.rootAgentId}` }}</span>
            <el-tooltip v-else content="虚拟根：直接按方案运行，无需绑定 Agent" placement="top">
              <el-tag size="small" type="info" effect="plain">虚拟根</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="候选（启用）" min-width="110" align="center">
          <template #default="{ row }">
            <span class="cell-count">{{ row.candidateEnabledCount }}</span>
            <span class="cell-count-unit">个启用</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
            <el-button v-if="row.rootAgentId" link type="primary" @click.stop="openOrchestration(row)">打开编排</el-button>
            <!-- 试跑两条入口：绑根进 Agent 试跑页；虚拟根进方案试跑页（def/:maId/trial） -->
            <el-button v-if="canTrial" link type="primary" @click.stop="openTrial(row)">运行</el-button>
            <el-tooltip v-else content="没有运行权限（runtime:trial:run / list）" placement="top">
              <span class="action-disabled">
                <el-button link disabled>运行</el-button>
              </span>
            </el-tooltip>
            <el-button v-if="canEdit" link type="danger" @click.stop="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>

        <!-- 空态：无筛选时引导新建；有筛选时提示清空条件 -->
        <template #empty>
          <div class="empty-guide">
            <template v-if="hasFilter">
              <p class="empty-guide__title">没有匹配的方案</p>
              <p class="empty-guide__text">试试清空关键字、状态或模板筛选。</p>
              <el-button @click="resetFilter">清空筛选</el-button>
            </template>
            <template v-else>
              <p class="empty-guide__title">还没有多智能体方案</p>
              <p class="empty-guide__text">
                方案保存编排模板与候选池；留空绑定即虚拟根，可直接按方案运行。
              </p>
              <el-button v-if="canEdit" type="primary" @click="openCreate">新建方案</el-button>
            </template>
          </div>
        </template>
      </el-table>
      <TablePager v-model:current="query.current" v-model:size="query.size" :total="total" @change="loadRows" />
    </section>

    <!-- 编辑抽屉：方案 + 配置 + 候选一次提交；不复制执行器逻辑 -->
    <el-drawer
      v-model="drawerVisible"
      :title="form.id ? (canEdit ? '编辑方案' : '查看方案') : '新建方案'"
      size="920px"
      destroy-on-close
      class="ma-drawer"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" :disabled="!canEdit" label-width="112px" class="ma-form">
        <!-- 真源说明：保存只落 ma_def / ma_candidate，旧 agent_route 行保持原样 -->
        <p class="source-note">
          唯一真源：本页保存只更新「方案 + 候选」，不再回写旧的 Agent 子路由；Agent「编排」Tab 已改为只读并深链到此处。
        </p>
        <!-- 区块 1：基本信息。标题加粗 + 顶部实线，和下一块拉开层次 -->
        <section class="form-block form-block--first">
          <h3 class="form-block__title">基本信息</h3>
          <el-row :gutter="24">
            <el-col :span="12">
              <el-form-item label="方案编码" prop="code">
                <el-input v-model="form.code" maxlength="64" placeholder="租户内唯一，如 order_triage" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="方案名称" prop="name">
                <el-input v-model="form.name" maxlength="128" placeholder="给运营看的中文名" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="24">
            <el-col :span="12">
              <el-form-item label="编排模板" prop="template">
                <el-select v-model="form.template" class="full-width" @change="onTemplateChange">
                  <el-option v-for="item in MA_LIST_TEMPLATES" :key="item" :label="maTemplateLabel(item)" :value="item">
                    <span>{{ maTemplateLabel(item) }}</span>
                    <span class="option-hint">{{ maTemplateHint(item) }}</span>
                  </el-option>
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="绑定 ROUTER">
                <el-select
                  v-model="form.rootAgentId"
                  class="full-width"
                  clearable
                  filterable
                  placeholder="不选＝虚拟根（按方案直接运行）"
                >
                  <el-option v-for="agent in rootAgents" :key="agent.id" :label="`${agent.name}（${agent.code}）`" :value="agent.id" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <!-- 绑定说明单独成行：两句提示挤在半栏里会换行成三行，读起来更乱 -->
          <p class="form-block__note">只有启用中的 ROUTER 可选；同一 ROUTER 最多一条启用方案。启停改到列表「状态」列直接切换。</p>
        </section>

        <!-- 区块 2：编排参数。两列对齐，开关类单独成行，避免长短不齐 -->
        <section class="form-block">
          <h3 class="form-block__title">编排参数</h3>
          <el-row :gutter="24">
            <el-col :span="12">
              <el-form-item label="上下文策略">
                <el-select v-model="form.config.maContextPolicy" class="full-width">
                  <el-option label="隔离（子只看用户原问/子问）" value="ISOLATED" />
                  <el-option label="只看前置摘要（仅串行模板）" value="PRIOR_SUMMARY_ONLY" />
                  <el-option label="共享草稿区（本轮内存）" value="SHARED_SCRATCH" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="终答模式">
                <el-radio-group v-model="form.config.answerMode">
                  <el-radio value="CONCAT">直接拼接</el-radio>
                  <el-radio value="SUMMARIZE">模型汇总</el-radio>
                  <!-- 投票选优只对「等齐后统一出终答」的模板开放，与后端 supportsVote 同口径 -->
                  <el-radio v-if="supportsVote" value="VOTE">投票选优</el-radio>
                </el-radio-group>
                <span v-if="supportsVote" class="field-hint inline">
                  投票选优：模型评选一路终答；失败则拼接
                </span>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="24">
            <el-col :span="12">
              <el-form-item label="每轮最多子数">
                <div class="field-inline">
                  <el-input-number v-model="form.config.maxRouteChildren" :min="1" :max="5" />
                  <span class="field-hint inline">1～5，超出候选按序跳过</span>
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="允许子模式">
                <el-checkbox-group v-model="form.config.maChildRunModesAllowed">
                  <el-checkbox label="REACT" disabled>ReAct（恒允许）</el-checkbox>
                  <el-checkbox label="PLAN_AND_EXECUTE">计划执行</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
            </el-col>
          </el-row>
          <!-- 开关成对放一行：语义相近，横向对照比上下堆叠更快看懂 -->
          <el-row :gutter="24">
            <el-col :span="12">
              <el-form-item label="允许多意图">
                <el-switch v-model="form.config.multiIntentEnabled" />
                <span class="field-hint inline">一次提问可路由到多个子</span>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="单意图继承历史">
                <el-switch v-model="form.config.maAllowParentHistoryWhenSingle" />
                <span class="field-hint inline">关闭＝只看用户原问（硬隔离）</span>
              </el-form-item>
            </el-col>
          </el-row>
          <!-- 评审环专属：只在选中该模板时出现，避免无关字段占位 -->
          <el-row v-if="form.template === 'CRITIC_LOOP'" :gutter="24">
            <el-col :span="12">
              <el-form-item label="评审轮次">
                <el-input-number v-model="form.config.maCriticMaxRounds" :min="1" :max="3" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="评审专家">
                <el-select
                  v-model="form.config.maCriticAgentId"
                  class="full-width"
                  clearable
                  filterable
                  placeholder="选择启用的叶子 Agent"
                >
                  <el-option v-for="agent in leafAgents" :key="agent.id" :label="`${agent.name}（${agent.code}）`" :value="agent.id" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <!-- 竞争选优专属：选优策略；缺省 JUDGE_PICK，无评选模型时自动降级为首个成功子 -->
          <el-row v-if="form.template === 'COMPETE'" :gutter="24">
            <el-col :span="24">
              <el-form-item label="选优策略">
                <el-radio-group v-model="form.config.competeStrategy">
                  <el-radio value="JUDGE_PICK">评选模型选优（默认）</el-radio>
                  <el-radio value="FIRST_SUCCESS">第一个成功子</el-radio>
                </el-radio-group>
                <span class="field-hint inline">
                  同题并行后只取 1 份终答；至少 2 个启用候选，评选模型缺省用判路由模型
                </span>
              </el-form-item>
            </el-col>
          </el-row>
        </section>

        <!-- 区块 3：候选池。标题行承担说明 + 添加入口，表格只放数据 -->
        <section class="form-block">
          <h3 class="form-block__title">候选池</h3>
          <div class="candidate-toolbar">
            <span class="field-hint">候选必须是启用、允许被编排的 REACT / 计划执行 Agent，且不能是绑定的根。</span>
            <el-button size="small" @click="addCandidate">添加候选</el-button>
          </div>
          <el-table :data="form.candidates" class="candidate-table" size="small">
            <el-table-column label="子 Agent" min-width="190">
              <template #default="{ row }">
                <el-select v-model="row.agentId" filterable placeholder="选择 Agent" @change="onCandidateAgentChange(row)">
                  <el-option v-for="agent in candidateOptions" :key="agent.id" :label="`${agent.name}（${agent.code}）`" :value="agent.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="路由键" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.routeKey" maxlength="64" placeholder="留空按编码生成" />
              </template>
            </el-table-column>
            <el-table-column label="意图描述" min-width="200">
              <template #default="{ row }">
                <el-input v-model="row.intentDesc" maxlength="512" placeholder="喂给判路由 / 串行步骤说明" />
              </template>
            </el-table-column>
            <el-table-column label="排序" width="100">
              <template #default="{ row }">
                <el-input-number v-model="row.sortNo" :min="0" :max="10000" :step="10" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="启用" width="80" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" />
              </template>
            </el-table-column>
            <el-table-column label="" width="60" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="form.candidates.splice($index, 1)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-form>

      <template #footer>
        <el-button @click="drawerVisible = false">关闭</el-button>
        <el-button v-if="canEdit" type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'MaList' })

/**
 * 多智能体方案列表页。所属域：agent（前端入口层）。
 *
 * <p>职责：
 * - 列出 ma_def 方案，支持启停、筛选、删除；
 * - 抽屉内编辑方案 + 候选，一次提交（后端候选整份替换）；
 * - 有绑根时深链到既有编排 Tab / 独立试跑页；虚拟根进方案试跑页（def/:maId/trial）。
 *
 * <p>边界：不新建 Agent、不复制执行器；试跑仍走旧 ROUTER 执行器与旧试跑权限，
 * 只是执行读源改成 ma_def（模板 / 候选 / 判路由模型）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import {
  changeMaDefEnabled,
  createMaDef,
  deleteMaDef,
  getMaDef,
  pageMaDefs,
  updateMaDef
} from '@/api/ma'
import { pageAgents } from '@/api/agent'
import { useUserStore } from '@/stores/user'
import type { AgentCard } from '@/types/agent'
import type { MaCandidate, MaDefDetail, MaDefRow, MaDefSave, MaRuntimeConfig } from '@/types/ma'
import { MA_LIST_TEMPLATES, maTemplateHint, maTemplateLabel, maTemplateTagType } from '@/utils/maList'
import {
  MA_COMPETE_STRATEGY_JUDGE_PICK,
  normalizeCompeteStrategy,
  supportsVoteTemplate
} from '@/utils/orchestrationValidation'
import { maDefTrialPath, maTrialPath } from '@/utils/maTrial'
import { collectAgentPages } from '@/utils/agentPages'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

/** 本刀权限码：与后端 MaPermissions 完全一致 */
const canView = computed(() => user.hasPerm('ma:def:view'))
const canEdit = computed(() => user.hasPerm('ma:def:edit'))
/** 试跑仍走旧 Agent 试跑权；ma:def:run 只是占位码，不参与入口判断 */
const canTrial = computed(() => user.hasPerm('runtime:trial:run') || user.hasPerm('runtime:trial:list'))
/** 当前模板是否支持投票选优（与服务端 AgentRuntimeConfig.supportsVote 同口径） */
const supportsVote = computed(() => supportsVoteTemplate(form.template))

const loading = ref(false)
const listError = ref('')
const rows = ref<MaDefRow[]>([])
const total = ref(0)
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  enabled: undefined as number | undefined,
  template: undefined as string | undefined,
  hasRoot: undefined as boolean | undefined
})
/** 多智能体方案表最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)

/** 筛选值归一：clearable 清空后可能是 ''/null；0、false 都不能按 falsy 丢掉 */
function enabledParam(): number | undefined {
  return query.enabled === 0 || query.enabled === 1 ? query.enabled : undefined
}

function hasRootParam(): boolean | undefined {
  return query.hasRoot === true || query.hasRoot === false ? query.hasRoot : undefined
}

/** 是否有筛选条件：决定空态是「无数据引导」还是「无匹配」 */
const hasFilter = computed(() =>
  Boolean(query.keyword.trim()) || enabledParam() !== undefined || Boolean(query.template) || hasRootParam() !== undefined
)

/** 时间展示：后端给 yyyy-MM-dd HH:mm:ss，直接截断毫秒；空值给占位 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

/** 分页加载；失败只出中文错误态，不伪造空数据成功 */
async function loadRows() {
  if (!canView.value) {
    listError.value = '没有查看多智能体方案的权限'
    return
  }
  loading.value = true
  listError.value = ''
  try {
    const { data } = await pageMaDefs({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      enabled: enabledParam(),
      template: query.template || undefined,
      hasRoot: hasRootParam()
    })
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
  } catch {
    listError.value = '多智能体方案加载失败'
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
  query.enabled = undefined
  query.template = undefined
  query.hasRoot = undefined
  search()
}

// ---------------------------------------------------------------------------
// 编辑抽屉
// ---------------------------------------------------------------------------

/** 候选行：保存请求字段 + 回显名称；sortNo 为空时由后端生成 */
interface CandidateRow {
  agentId: number | null
  agentName?: string
  agentCode?: string
  agentRunMode?: string
  agentStatus?: number
  routeKey: string
  intentDesc: string
  sortNo?: number
  enabled: number
}

/** 抽屉表单模型 */
interface MaFormState {
  id: number | null
  code: string
  name: string
  template: string
  rootAgentId: number | null
  /**
   * 启用标记。抽屉里不再放开关：启停统一在列表「状态」列切换。
   * 此字段只为提交时原样带回（编辑沿用已存值、新建默认 1），避免保存把状态静默改掉。
   */
  enabled: number
  config: MaRuntimeConfig
  candidates: CandidateRow[]
}

/** 与后端 AgentRuntimeConfig.defaultsFor 同口径的默认配置，避免新建时空字段 */
function defaultConfig(): MaRuntimeConfig {
  return {
    maTemplate: 'ROUTER_PARALLEL',
    maContextPolicy: 'ISOLATED',
    maAllowParentHistoryWhenSingle: true,
    multiIntentEnabled: true,
    answerMode: 'CONCAT',
    maxRouteChildren: 3,
    requirePlanConfirm: false,
    maxPlanSteps: 8,
    maxStepRetries: 0,
    maChildRunModesAllowed: ['REACT'],
    maCriticMaxRounds: 1,
    maCriticAgentId: null,
    // 与后端 AgentRuntimeConfig 同口径：策略缺省 JUDGE_PICK，非 COMPETE 时该值不生效
    competeStrategy: 'JUDGE_PICK'
  }
}

const drawerVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<MaFormState>({
  id: null,
  code: '',
  name: '',
  template: 'ROUTER_PARALLEL',
  rootAgentId: null,
  enabled: 1,
  config: defaultConfig(),
  candidates: []
})

const formRules: FormRules = {
  code: [{ required: true, message: '方案编码不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '方案名称不能为空', trigger: 'blur' }],
  template: [{ required: true, message: '请选择编排模板', trigger: 'change' }]
}

/** 绑定根候选：只拉启用中的 ROUTER */
const rootAgents = ref<AgentCard[]>([])
/** 子 Agent 候选：REACT + 计划执行，且当前方案允许 */
const leafAgents = ref<AgentCard[]>([])
let agentsLoaded = false

/**
 * 按运行模式拉「下拉可选」的全部启用 Agent。
 *
 * <p>为什么必须翻页：服务端对 size 的上限是 100，旧实现直接写 {@code size: 200}
 * 会被分页校验驳回，前端只剩「Agent 列表加载失败」。翻页与硬顶规则在
 * {@link collectAgentPages}（纯函数，有窄测），这里只负责把它包成真实请求。
 *
 * @param runMode REACT / PLAN_AND_EXECUTE / ROUTER
 * @return 该模式下启用中的 Agent；任一分页失败直接抛出，由调用方统一提示
 */
async function listAgentsForSelect(runMode: string): Promise<AgentCard[]> {
  return collectAgentPages(async (current, size) => {
    const { data } = await pageAgents({ current, size, runMode, status: 1 })
    return data.data
  })
}

/**
 * 懒加载 Agent 下拉数据：REACT / PLAN_AND_EXECUTE / ROUTER 各翻页拉全后合并。
 * 只在首次打开抽屉时请求，避免列表页首屏多打接口；失败时不置位，下次可重试。
 */
async function ensureAgents() {
  if (agentsLoaded) return
  const [react, plan, routers] = await Promise.all([
    listAgentsForSelect('REACT'),
    listAgentsForSelect('PLAN_AND_EXECUTE'),
    listAgentsForSelect('ROUTER')
  ])
  leafAgents.value = [...react, ...plan]
  rootAgents.value = routers
  agentsLoaded = true
}

/** 候选下拉：只保留启用、允许被编排、运行模式被本方案允许，且不是绑定的根 */
const candidateOptions = computed(() => {
  const allowed = form.config.maChildRunModesAllowed || ['REACT']
  const options = leafAgents.value.filter(
    (agent) =>
      agent.allowOrchestrated === true &&
      allowed.includes(agent.runMode) &&
      agent.id !== form.rootAgentId
  )
  // 只读回显：详情里已有但下拉没拉到的 Agent 补一条，避免显示裸 id
  for (const candidate of form.candidates) {
    if (!candidate.agentId || options.some((agent) => agent.id === candidate.agentId)) continue
    options.push({
      id: candidate.agentId,
      name: candidate.agentName || `Agent #${candidate.agentId}`,
      code: candidate.agentCode || '',
      runMode: candidate.agentRunMode || 'REACT',
      status: candidate.agentStatus ?? 1,
      allowOrchestrated: true
    })
  }
  return options
})

/** 模板切换：非串行模板不能保留「只看前置摘要」，就地收敛，避免保存后静默变形 */
function onTemplateChange(value: string) {
  if (form.config.maContextPolicy === 'PRIOR_SUMMARY_ONLY' && value !== 'FIXED_SEQUENCE' && value !== 'ROUTER_THEN_SEQUENCE') {
    form.config.maContextPolicy = 'ISOLATED'
  }
  // 切到竞争选优时补一个策略缺省值；其它模板保留原值，但后端只在 COMPETE 读它
  if (value === 'COMPETE' && !form.config.competeStrategy) {
    form.config.competeStrategy = MA_COMPETE_STRATEGY_JUDGE_PICK
  }
  // 切到不支持投票的模板时同步回落拼接，避免抽屉显示「投票」却在服务端被洗成 CONCAT
  if (form.config.answerMode === 'VOTE' && !supportsVoteTemplate(value)) {
    form.config.answerMode = 'CONCAT'
  }
  form.config.maTemplate = value
}

/** 新建：清空表单并打开抽屉 */
async function openCreate() {
  if (!canEdit.value) return
  try {
    await ensureAgents()
  } catch {
    ElMessage.error('Agent 列表加载失败，无法选择绑根或候选')
    return
  }
  form.id = null
  form.code = ''
  form.name = ''
  form.template = 'ROUTER_PARALLEL'
  form.rootAgentId = null
  form.enabled = 1
  form.config = defaultConfig()
  form.candidates = []
  drawerVisible.value = true
}

/**
 * 编辑：按行 id 拉详情后回填；失败不打开抽屉，避免露出半份表单。
 *
 * @param row 列表行（el-table row-click 会带 row/column/event 三参）
 */
async function openEdit(row: MaDefRow) {
  if (!canView.value || row?.id === undefined) return
  try {
    await ensureAgents()
  } catch {
    // 只读用户可能没有 agent:list；仍允许看方案详情，只是下拉选项不全
    if (canEdit.value) {
      ElMessage.error('Agent 列表加载失败，无法选择绑根或候选')
      return
    }
  }
  try {
    const { data } = await getMaDef(row.id)
    const detail = data.data
    if (!detail) {
      ElMessage.error('方案不存在或已删除')
      return
    }
    fillForm(detail)
    drawerVisible.value = true
  } catch {
    // 拦截器已给出中文错误
  }
}

/** 详情 → 表单：config 做一次浅拷贝，避免直接改服务端响应对象 */
function fillForm(detail: MaDefDetail) {
  form.id = detail.id
  form.code = detail.code
  form.name = detail.name
  form.template = detail.template
  form.rootAgentId = detail.rootAgentId ?? null
  form.enabled = detail.enabled
  form.config = { ...defaultConfig(), ...(detail.config || {}) }
  form.config.maTemplate = detail.template
  // 旧数据没有 / 存了非法策略：按服务端同口径回落 JUDGE_PICK，避免单选组空白
  form.config.competeStrategy = normalizeCompeteStrategy(form.config.competeStrategy)
  // 历史配置可能存着模板不支持的 VOTE（手改 JSON）：按服务端同口径收敛回拼接
  if (form.config.answerMode === 'VOTE' && !supportsVoteTemplate(detail.template)) {
    form.config.answerMode = 'CONCAT'
  }
  form.candidates = (detail.candidates || []).map((item: MaCandidate) => ({
    agentId: item.agentId,
    agentName: item.agentName,
    agentCode: item.agentCode,
    agentRunMode: item.agentRunMode,
    agentStatus: item.agentStatus,
    routeKey: item.routeKey,
    intentDesc: item.intentDesc,
    sortNo: item.sortNo,
    enabled: item.enabled
  }))
}

/** 加一行空候选；sortNo 留空交给后端按 max+10 生成 */
function addCandidate() {
  form.candidates.push({ agentId: null, routeKey: '', intentDesc: '', sortNo: undefined, enabled: 1 })
}

/**
 * 选中子 Agent 后，若路由键为空则用 Agent 编码预填，减少手工输入。
 * 编码为空时用 child_{id}，与后端兜底规则一致。
 */
function onCandidateAgentChange(row: CandidateRow) {
  if (row.routeKey.trim() || !row.agentId) return
  const agent = leafAgents.value.find((item) => item.id === row.agentId)
  row.routeKey = agent?.code || `child_${row.agentId}`
}

/** 提交前把 REACT 恒补回允许集合，避免用户取消勾选造成服务端归一化差异 */
function normalizeConfig(): MaRuntimeConfig {
  const config: MaRuntimeConfig = { ...form.config, maTemplate: form.template }
  const modes = new Set(config.maChildRunModesAllowed || [])
  modes.add('REACT')
  config.maChildRunModesAllowed = Array.from(modes)
  return config
}

/** 保存：先过前端必填校验，再整份提交方案 + 候选 */
async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (form.candidates.some((item) => !item.agentId)) {
    ElMessage.warning('候选池存在未选择子 Agent 的行，请先补齐或移除')
    return
  }
  if (form.candidates.some((item) => !item.intentDesc.trim())) {
    ElMessage.warning('候选意图描述不能为空')
    return
  }
  // 绑定的根不能同时当候选：后端会拦，前端先给更直观的提示
  if (form.rootAgentId && form.candidates.some((item) => item.agentId === form.rootAgentId)) {
    ElMessage.warning('候选不能是当前绑定的 ROUTER 根')
    return
  }
  // 竞争选优至少 2 个启用候选：后端同样校验，前端先拦一次，省一次往返
  if (form.template === 'COMPETE' && form.candidates.filter((item) => item.enabled === 1).length < 2) {
    ElMessage.warning('竞争选优至少需要 2 个启用候选，否则无法比较优劣')
    return
  }
  const payload: MaDefSave = {
    code: form.code.trim(),
    name: form.name.trim(),
    template: form.template,
    config: normalizeConfig(),
    rootAgentId: form.rootAgentId ?? null,
    enabled: form.enabled,
    candidates: form.candidates.map((item) => ({
      agentId: item.agentId as number,
      routeKey: item.routeKey.trim() || undefined,
      intentDesc: item.intentDesc.trim(),
      sortNo: item.sortNo,
      enabled: item.enabled
    }))
  }
  saving.value = true
  try {
    if (form.id) {
      await updateMaDef(form.id, payload)
      ElMessage.success('方案已更新')
    } else {
      await createMaDef(payload)
      ElMessage.success('方案已创建')
    }
    drawerVisible.value = false
    await loadRows()
  } catch {
    // 拦截器已给出中文错误；保持抽屉打开便于修正
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------------------
// 启停 / 删除 / 深链
// ---------------------------------------------------------------------------

/**
 * 行内启停。乐观更新，失败回滚，避免用户看到错误状态。
 *
 * @param row 列表行
 */
async function toggleEnabled(row: MaDefRow) {
  const next = row.enabled === 1 ? 0 : 1
  const previous = row.enabled
  row.enabled = next
  try {
    await changeMaDefEnabled(row.id, next)
    ElMessage.success(next === 1 ? '方案已启用' : '方案已停用')
    await loadRows()
  } catch {
    row.enabled = previous
  }
}

/** 删除方案：二次确认；绑定的 ROUTER 不会被删除或解绑 */
async function removeRow(row: MaDefRow) {
  try {
    await ElMessageBox.confirm(
      `删除方案「${row.name}」？候选会一并删除；绑定的 ROUTER 保持现状。`,
      '删除方案',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteMaDef(row.id)
    ElMessage.success('方案已删除')
    await loadRows()
  } catch {
    // 拦截器已给出中文错误
  }
}

/** 打开绑定 ROUTER 的既有「编排」Tab；虚拟根没有 Agent 详情可跳 */
function openOrchestration(row: MaDefRow) {
  if (!row.rootAgentId) return
  void router.push({ path: `/agent/list/${row.rootAgentId}`, query: { tab: 'orchestration' } })
}

/**
 * 打开独立试跑页。
 *
 * <p>两条入口：绑根方案走 Agent 试跑页（会话挂 agentId），虚拟根走方案试跑页
 * （会话挂 maId）；绑定关系由后端复核，前端只按当前行决定落点。
 *
 * @param row 方案行
 */
function openTrial(row: MaDefRow) {
  if (row.rootAgentId) {
    void router.push(maTrialPath(row.rootAgentId))
    return
  }
  void router.push(maDefTrialPath(row.id))
}

/**
 * 深链打开方案编辑：Agent「编排」Tab 的「打开方案编辑」会带 ?edit={id} 进本页。
 * 打开前先清掉 query：一是避免 KeepAlive 复用时同一 id 不再触发 watch，
 * 二是关闭抽屉后刷新页面不会又弹一次。
 */
async function openFromQuery() {
  const raw = route.query.edit
  if (!raw || !canView.value) return
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  if (!Number.isFinite(id) || id <= 0) return
  await router.replace({ path: route.path, query: {} })
  await openEdit({ id } as MaDefRow)
}

onMounted(async () => {
  await loadRows()
  await openFromQuery()
})

// 列表页是 KeepAlive 缓存的（cacheName=MaList）：再次深链只变 query，需要 watch 兜底
watch(
  () => route.query.edit,
  (value) => {
    if (value) void openFromQuery()
  }
)
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

.cell-code {
  color: var(--ah-text-secondary, #6b7280);
  font-family: var(--ah-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}

.cell-root {
  color: var(--ah-text-primary, #111827);
}

.cell-count {
  font-weight: 600;
}

.cell-count-unit {
  margin-left: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.action-disabled {
  display: inline-block;
  margin: 0 8px;
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

.full-width {
  width: 100%;
}

/* 抽屉顶部真源说明：轻提示，不抢区块标题的层级 */
.source-note {
  margin: 0 0 16px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--ah-fill-light, #f5f6fa);
  color: var(--ah-text-secondary, #5b6172);
  font-size: 12px;
  line-height: 1.6;
}

/* 抽屉三块：实线分隔 + 加粗标题，靠间距和线把「基本信息 / 编排参数 / 候选池」切清楚 */
.form-block {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--ah-border, #e8eaf0);
}

.form-block--first {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.form-block__title {
  display: flex;
  align-items: center;
  margin: 0 0 16px;
  color: var(--ah-text-primary, #1f2430);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

/* 左侧短竖条：比纯文字标题更容易扫到分区起点 */
.form-block__title::before {
  content: '';
  width: 3px;
  height: 14px;
  margin-right: 8px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.form-block__note {
  margin: -4px 0 0;
  padding-left: 112px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.6;
}

/* 标签加粗：两列并排后靠字重区分「字段名 / 输入值」 */
.ma-form :deep(.el-form-item__label) {
  font-weight: 600;
}

/* 抽屉加宽后左右留白同步加大，表格不贴边 */
.ma-drawer :deep(.el-drawer__body) {
  padding: 20px 24px 8px;
}

/* 窄屏兜底：size 是行内宽度，只能靠 !important 收回到视口内，避免右侧被裁掉 */
@media (max-width: 1000px) {
  .ma-drawer {
    width: 94vw !important;
  }
}

/* 控件与行内提示同一基线，避免提示文字掉到下一行基线 */
.field-inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  width: 100%;
}

.field-hint {
  margin-top: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.field-hint.inline {
  display: inline-block;
  margin: 0 0 0 8px;
}

.option-hint {
  margin-left: 10px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.candidate-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.candidate-table {
  width: 100%;
}
</style>
