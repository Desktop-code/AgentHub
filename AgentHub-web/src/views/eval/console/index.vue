<template>
  <div class="page">
    <PageHeader
      title="Agent 评测"
      description="套件与用例管理、跑批执行与报告、规则失败坏案回流。OpenJudge 仅为软评分，不翻转门禁。"
    />

    <el-tabs v-model="tab" class="eval-tabs" @tab-change="onTabChange">
      <!-- ========== 套件 ========== -->
      <el-tab-pane label="套件" name="suites">
        <div class="filter-bar">
          <el-form class="filter-form" inline @submit.prevent="loadSuites">
            <el-form-item label="关键字">
              <el-input v-model="suiteQ.keyword" clearable placeholder="suite_key / 名称" class="filter-input" />
            </el-form-item>
            <el-form-item>
              <el-button class="btn-query" @click="loadSuites">
                <el-icon class="btn-icon"><Search /></el-icon>
                查询
              </el-button>
              <el-button
                v-if="user.hasPerm('eval:suite:edit')"
                type="primary"
                :loading="sampleLoading"
                @click="onImportSample"
              >
                导入样例
              </el-button>
              <el-button v-if="user.hasPerm('eval:suite:edit')" type="primary" @click="openSuiteCreate">
                新建套件
              </el-button>
            </el-form-item>
          </el-form>
        </div>
        <section class="panel">
          <el-table
            v-loading="suiteLoading"
            :data="suites"
            empty-text="暂无评测套件"
            class="eval-table"
          >
            <el-table-column prop="suiteKey" label="键" min-width="140" align="center" show-overflow-tooltip />
            <el-table-column prop="name" label="名称" min-width="160" align="center" show-overflow-tooltip />
            <el-table-column prop="currentVersion" label="版本" min-width="80" align="center" />
            <el-table-column label="状态" min-width="90" align="center">
              <template #default="{ row }">
                <span class="status-dot" :class="row.enabled === 1 ? 'ok' : 'off'" />
                {{ row.enabled === 1 ? '启用' : '停用' }}
              </template>
            </el-table-column>
            <el-table-column prop="updateTime" label="更新时间" min-width="170" align="center" />
            <el-table-column label="操作" min-width="160" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openSuiteDetail(row)">用例</el-button>
                <el-button
                  v-if="user.hasPerm('eval:suite:edit') && !row.readOnly"
                  link
                  type="primary"
                  @click="openImport(row)"
                >
                  导入
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <TablePager
            v-model:current="suiteQ.current"
            v-model:size="suiteQ.size"
            :total="suiteTotal"
            @change="loadSuites"
          />
        </section>
      </el-tab-pane>

      <!-- ========== 跑批 ========== -->
      <el-tab-pane label="跑批" name="runs">
        <div class="filter-bar">
          <el-form class="filter-form" inline @submit.prevent="loadRuns">
            <el-form-item label="状态">
              <el-select v-model="runQ.status" clearable placeholder="全部" class="filter-select">
                <el-option v-for="s in RUN_STATUS" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="套件">
              <el-select v-model="runQ.suiteId" clearable filterable placeholder="全部" class="filter-select">
                <el-option
                  v-for="s in suiteOptions"
                  :key="s.id"
                  :label="`${s.name} (${s.suiteKey})`"
                  :value="s.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button class="btn-query" @click="loadRuns">
                <el-icon class="btn-icon"><Search /></el-icon>
                查询
              </el-button>
              <el-button v-if="user.hasPerm('eval:run:trigger')" type="primary" @click="openRunCreate">
                创建跑批
              </el-button>
            </el-form-item>
          </el-form>
        </div>
        <section class="panel">
          <el-table v-loading="runLoading" :data="runs" empty-text="暂无跑批" class="eval-table">
            <el-table-column prop="id" label="ID" min-width="80" align="center" />
            <el-table-column label="套件" min-width="260" align="center" show-overflow-tooltip>
              <template #default="{ row }">
                <!-- 单行：名称 + 版本 Tag，避免换行叠字 -->
                <el-tooltip :content="suiteTooltip(row)" placement="top">
                  <span class="cell-inline">
                    <span class="cell-main">{{ suitePrimary(row) }}</span>
                    <el-tag size="small" type="info" effect="plain" class="cell-tag">
                      v{{ row.suiteVersion }}
                    </el-tag>
                  </span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="Agent" min-width="200" align="center" show-overflow-tooltip>
              <template #default="{ row }">
                <el-tooltip :content="`Agent ID: ${row.agentId}`" placement="top">
                  <span class="cell-inline">
                    <span class="cell-main">{{ agentPrimary(row.agentId) }}</span>
                    <el-tag size="small" type="info" effect="plain" class="cell-tag">
                      #{{ row.agentId }}
                    </el-tag>
                  </span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="120" align="center">
              <template #default="{ row }">
                <el-tooltip :content="statusTip(row)" placement="top">
                  <el-tag size="small" :type="statusTagType(row.status)" effect="light">
                    {{ row.status }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="门禁" min-width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.gatePassed == null">—</span>
                <span v-else-if="row.gatePassed === 1" class="gate-ok">通过</span>
                <span v-else class="gate-fail">未过</span>
              </template>
            </el-table-column>
            <el-table-column label="通过率" min-width="120" align="center">
              <template #default="{ row }">{{ passRate(row) }}</template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" min-width="170" align="center" />
            <el-table-column label="操作" width="140" align="center" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="user.hasPerm('eval:run:trigger') && row.status === 'PENDING'"
                  link
                  type="primary"
                  :loading="executingId === row.id"
                  @click="onExecute(row)"
                >
                  执行
                </el-button>
                <el-button v-if="user.hasPerm('eval:run:view')" link type="primary" @click="openRunDetail(row)">
                  详情
                </el-button>
                <el-button
                  v-if="user.hasPerm('eval:run:cancel') && canCancel(row.status)"
                  link
                  type="danger"
                  @click="onCancel(row)"
                >
                  取消
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <TablePager
            v-model:current="runQ.current"
            v-model:size="runQ.size"
            :total="runTotal"
            @change="loadRuns"
          />
        </section>
      </el-tab-pane>

      <!-- ========== 坏案 ========== -->
      <el-tab-pane label="坏案" name="bad">
        <div class="filter-bar">
          <el-form class="filter-form" inline @submit.prevent="loadBad">
            <el-form-item label="含 ERROR">
              <el-switch v-model="badQ.includeError" @change="loadBad" />
            </el-form-item>
            <el-form-item>
              <el-button class="btn-query" @click="loadBad">
                <el-icon class="btn-icon"><Search /></el-icon>
                查询
              </el-button>
            </el-form-item>
          </el-form>
        </div>
        <section class="panel">
          <el-table
            v-loading="badLoading"
            :data="badCases"
            empty-text="暂无规则失败坏案"
            class="eval-table"
          >
            <el-table-column prop="resultId" label="结果ID" min-width="90" align="center" />
            <el-table-column label="跑批/套件" min-width="200" align="center" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="cell-inline">
                  <span class="cell-main">run {{ row.runId }}</span>
                  <el-tag size="small" type="info" effect="plain" class="cell-tag">
                    {{ suitePrimary({ suiteId: row.suiteId, suiteVersion: row.suiteVersion }) }}
                  </el-tag>
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="caseKey" label="case" min-width="120" align="center" show-overflow-tooltip />
            <el-table-column prop="verdict" label="判定" min-width="90" align="center" />
            <el-table-column label="规则失败摘要" min-width="180" align="center" show-overflow-tooltip>
              <template #default="{ row }">{{ ruleFailSummary(row.assertionDetails) }}</template>
            </el-table-column>
            <el-table-column label="终答摘要" min-width="140" align="center" show-overflow-tooltip>
              <template #default="{ row }">{{ finalAnswerOf(row.artifactSummary) }}</template>
            </el-table-column>
            <el-table-column label="Judge" min-width="100" align="center">
              <template #default="{ row }">{{ ojLabel(row) }}</template>
            </el-table-column>
            <el-table-column prop="finishedTime" label="时间" min-width="170" align="center" />
            <el-table-column label="操作" min-width="180" align="center" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="user.hasPerm('eval:suite:edit')"
                  link
                  type="primary"
                  @click="openPromote(row)"
                >
                  回流套件
                </el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="!row.traceId"
                  @click="openTraceDrawer(row)"
                >
                  Trace
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <TablePager
            v-model:current="badQ.current"
            v-model:size="badQ.size"
            :total="badTotal"
            @change="loadBad"
          />
        </section>
      </el-tab-pane>
    </el-tabs>

    <!-- 套件详情抽屉 -->
    <el-drawer v-model="suiteDetailOpen" :title="suiteDetailTitle" size="560px" destroy-on-close>
      <el-table :data="suiteCases" empty-text="该版本无用例" size="small" class="eval-table">
        <el-table-column prop="caseKey" label="键" min-width="120" align="center" />
        <el-table-column prop="title" label="标题" min-width="140" align="center" show-overflow-tooltip />
        <el-table-column label="标签" min-width="120" align="center">
          <template #default="{ row }">
            <el-tag v-for="t in row.tags || []" :key="t" size="small" class="tag">{{ t }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" min-width="70" align="center">
          <template #default="{ row }">{{ row.enabled === 0 ? '否' : '是' }}</template>
        </el-table-column>
      </el-table>
      <p class="hint">编辑用例须走升版接口；请用「导入」或回流写入，勿直改历史版本行。</p>
    </el-drawer>

    <!-- 跑批详情抽屉 -->
    <el-drawer
      v-model="runDetailOpen"
      :title="runDetailTitle"
      size="720px"
      destroy-on-close
      @closed="stopPoll"
    >
      <div v-if="runDetail" class="detail-meta">
        <el-tag size="small" :type="statusTagType(runDetail.status)" effect="light">
          {{ runDetail.status }}
        </el-tag>
        <span>门禁 {{ gateText(runDetail.gatePassed) }}</span>
        <span>{{ passRate(runDetail) }}</span>
        <span v-if="runDetail.createTime" class="meta-muted">{{ runDetail.createTime }}</span>
        <el-button v-if="user.hasPerm('eval:run:view')" size="small" @click="loadReport">刷新报告</el-button>
      </div>
      <el-table
        v-loading="resultLoading"
        :data="runResults"
        size="small"
        empty-text="暂无结果"
        class="eval-table"
      >
        <el-table-column prop="caseKey" label="case" min-width="120" align="center" />
        <el-table-column prop="verdict" label="判定" min-width="90" align="center" />
        <el-table-column prop="rulePassed" label="规则" min-width="70" align="center" />
        <el-table-column label="Judge" min-width="100" align="center">
          <template #default="{ row }">{{ ojStatusText(row.openjudgeStatus) }}</template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时ms" min-width="90" align="center" />
        <el-table-column label="执行链路" min-width="100" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.traceId"
              link
              type="primary"
              @click="openTraceDrawer(row)"
            >
              查看
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="report-box">
        <div class="report-head">
          <h3>报告</h3>
          <el-button
            v-if="reportText"
            size="small"
            @click="copyReport"
          >
            复制
          </el-button>
        </div>
        <p class="hint">规则失败 ≠ Judge 不可用。gate_passed 仅由规则决定。</p>
        <el-empty v-if="!reportJson" description="跑完后生成报告" :image-size="64" />
        <pre v-else class="report-pre">{{ reportText }}</pre>
      </div>
    </el-drawer>

    <!-- 新建套件：每次打开重置 -->
    <el-dialog
      v-model="suiteCreateOpen"
      title="新建套件"
      width="520px"
      destroy-on-close
      @open="resetSuiteForm"
    >
      <el-form label-width="72px">
        <el-form-item label="键" required>
          <el-input
            v-model="suiteForm.suiteKey"
            placeholder="例：biz.qa.refund_cs"
          />
          <p class="field-tip">
            格式：域.场景.名称；仅字母、数字、点、下划线、中划线（与后端一致）；禁空格与中文。例
            <code>biz.qa.refund_cs</code>
          </p>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="suiteForm.name" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="suiteForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="suiteCreateOpen = false">取消</el-button>
        <el-button type="primary" :loading="suiteSaving" @click="onCreateSuite">创建</el-button>
      </template>
    </el-dialog>

    <!-- 导入 JSON：打开时回显当前套件原 JSON；提供一键清空 -->
    <el-dialog v-model="importOpen" title="导入套件 JSON（升版）" width="640px" destroy-on-close>
      <div v-loading="importLoading" class="import-body">
        <div class="import-toolbar">
          <el-button size="small" @click="clearImportText">一键清空</el-button>
        </div>
        <el-input
          v-model="importText"
          type="textarea"
          :rows="14"
          placeholder="粘贴 EvalSuiteSpec JSON；打开时会回显当前套件"
        />
      </div>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button type="primary" :loading="importSaving" @click="onImportJson">导入并升版</el-button>
      </template>
    </el-dialog>

    <!-- 创建跑批：每次打开重置，避免残留 -->
    <el-dialog
      v-model="runCreateOpen"
      title="创建跑批"
      width="520px"
      destroy-on-close
      @open="resetRunForm"
    >
      <el-form label-width="100px">
        <el-form-item label="套件" required>
          <el-select v-model="runForm.suiteId" filterable class="full" placeholder="请选择套件">
            <el-option
              v-for="s in suiteOptions"
              :key="s.id"
              :label="`${s.name} · v${s.currentVersion}`"
              :value="s.id"
              :disabled="s.enabled === 0"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Agent" required>
          <el-select v-model="runForm.agentId" filterable class="full" placeholder="请选择 Agent">
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="创建后执行">
          <el-switch v-model="runForm.executeNow" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runCreateOpen = false">取消</el-button>
        <el-button type="primary" :loading="runCreating" @click="onCreateRun">创建</el-button>
      </template>
    </el-dialog>

    <!-- 回流 -->
    <el-dialog v-model="promoteOpen" title="坏案回流到套件" width="520px" destroy-on-close @open="ensureSuiteOptions">
      <el-form label-width="100px">
        <el-form-item label="目标套件" required>
          <el-select v-model="promoteForm.suiteId" filterable class="full">
            <el-option
              v-for="s in suiteOptions.filter((x) => !x.readOnly)"
              :key="s.id"
              :label="s.name"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="case_key">
          <el-input v-model="promoteForm.caseKey" placeholder="空则自动生成 bc-..." />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="promoteForm.title" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="promoteForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="promoteOpen = false">取消</el-button>
        <el-button type="primary" :loading="promoteSaving" @click="onPromote">升版写入</el-button>
      </template>
    </el-dialog>

    <!-- 执行链路右拉：跑批详情「查看」与坏案 Trace 共用 -->
    <TraceLinkDrawer
      v-model="traceDrawerOpen"
      :trace-id="traceCtx.traceId"
      :session-id="traceCtx.sessionId"
      :run-id="traceCtx.runId"
      :result-id="traceCtx.resultId"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 评测控制台。所属域：eval。
 * 单路由三 Tabs：套件 / 跑批 / 坏案；薄 FE 调既有 API。
 * Trace 统一走 TraceLinkDrawer（禁跳 /ops/traces）；套件/Agent 名由 FE 字典拼接。
 * 跑批进度用轮询 GET run（禁 SSE）；卸载须 clearInterval。
 */
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import TraceLinkDrawer from '@/components/obs/TraceLinkDrawer.vue'
import {
  cancelEvalRun,
  createEvalRun,
  createEvalSuite,
  executeEvalRun,
  getEvalReport,
  getEvalRun,
  getEvalSuite,
  importEvalSample,
  importEvalSuite,
  pageEvalBadCases,
  pageEvalRunResults,
  pageEvalRuns,
  pageEvalSuites,
  promoteFromResult
} from '@/api/eval'
import { pageAgents } from '@/api/agent'
import { useUserStore } from '@/stores/user'
import type { AgentCard } from '@/types/agent'
import type {
  EvalBadCaseRow,
  EvalCaseRow,
  EvalResultRow,
  EvalRunRow,
  EvalSuiteDetail,
  EvalSuiteRow
} from '@/types/eval'

defineOptions({ name: 'EvalConsole' })

const user = useUserStore()
const tab = ref('suites')
const RUN_STATUS = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PARTIAL', 'CANCELING']

const suiteLoading = ref(false)
const suites = ref<EvalSuiteRow[]>([])
const suiteTotal = ref(0)
/** 默认每页 10；可选 10/20/50/100 由 TablePager 统一提供 */
const suiteQ = reactive({ current: 1, size: 10, keyword: '' })
const suiteOptions = ref<EvalSuiteRow[]>([])

const runLoading = ref(false)
const runs = ref<EvalRunRow[]>([])
const runTotal = ref(0)
const runQ = reactive<{ current: number; size: number; status?: string; suiteId?: number }>({
  current: 1,
  size: 10
})
const executingId = ref<number | null>(null)

const badLoading = ref(false)
const badCases = ref<EvalBadCaseRow[]>([])
const badTotal = ref(0)
const badQ = reactive({ current: 1, size: 10, includeError: false })

const suiteDetailOpen = ref(false)
const suiteDetailTitle = ref('')
const suiteDetailId = ref<number | null>(null)
const suiteCases = ref<EvalCaseRow[]>([])

const runDetailOpen = ref(false)
const runDetailTitle = ref('')
const runDetail = ref<EvalRunRow | null>(null)
const runResults = ref<EvalResultRow[]>([])
const resultLoading = ref(false)
const reportJson = ref<unknown>(null)
const reportText = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

const suiteCreateOpen = ref(false)
const suiteSaving = ref(false)
const suiteForm = reactive({ suiteKey: '', name: '', description: '' })
const sampleLoading = ref(false)

const importOpen = ref(false)
const importSaving = ref(false)
const importLoading = ref(false)
/** 打开导入时由套件详情回填；「一键清空」可清空 */
const importText = ref('')
const importSuiteId = ref<number | null>(null)

const runCreateOpen = ref(false)
const runCreating = ref(false)
const runForm = reactive({
  suiteId: undefined as number | undefined,
  agentId: undefined as number | undefined,
  executeNow: true
})
const agents = ref<AgentCard[]>([])

const promoteOpen = ref(false)
const promoteSaving = ref(false)
const promoteForm = reactive({
  suiteId: undefined as number | undefined,
  resultId: 0,
  caseKey: '',
  title: '',
  note: '',
  force: false
})

/** 执行链路抽屉上下文 */
const traceDrawerOpen = ref(false)
const traceCtx = reactive<{
  traceId: string | null
  sessionId: string | null
  runId: number | null
  resultId: number | null
}>({
  traceId: null,
  sessionId: null,
  runId: null,
  resultId: null
})

/** 加载套件字典（跑批/坏案名称解析） */
async function loadSuiteOptions() {
  const { data } = await pageEvalSuites({ current: 1, size: 100 })
  suiteOptions.value = data.data.records
}

async function ensureSuiteOptions() {
  if (suiteOptions.value.length) return
  await loadSuiteOptions()
}

async function loadSuites() {
  if (!user.hasPerm('eval:suite:view')) return
  suiteLoading.value = true
  try {
    const { data } = await pageEvalSuites({
      current: suiteQ.current,
      size: suiteQ.size,
      keyword: suiteQ.keyword || undefined
    })
    suites.value = data.data.records
    suiteTotal.value = data.data.total
    // 同步字典，便于跑批列拼名称
    suiteOptions.value = mergeSuiteDict(suiteOptions.value, data.data.records)
  } finally {
    suiteLoading.value = false
  }
}

/** 合并套件字典，避免分页结果冲掉其它页已缓存的名称 */
function mergeSuiteDict(prev: EvalSuiteRow[], next: EvalSuiteRow[]) {
  const map = new Map<number, EvalSuiteRow>()
  for (const s of prev) map.set(s.id, s)
  for (const s of next) map.set(s.id, s)
  return Array.from(map.values())
}

async function loadRuns() {
  if (!user.hasPerm('eval:run:view')) return
  runLoading.value = true
  try {
    const { data } = await pageEvalRuns({
      current: runQ.current,
      size: runQ.size,
      status: runQ.status,
      suiteId: runQ.suiteId
    })
    runs.value = data.data.records
    runTotal.value = data.data.total
  } finally {
    runLoading.value = false
  }
}

async function loadBad() {
  if (!user.hasPerm('eval:run:view')) return
  badLoading.value = true
  try {
    const { data } = await pageEvalBadCases({
      current: badQ.current,
      size: badQ.size,
      includeError: badQ.includeError || undefined
    })
    badCases.value = data.data.records
    badTotal.value = data.data.total
  } finally {
    badLoading.value = false
  }
}

function onTabChange(name: string | number) {
  if (name === 'suites') loadSuites()
  if (name === 'runs') {
    void loadSuiteOptions()
    void ensureAgents()
    loadRuns()
  }
  if (name === 'bad') {
    void loadSuiteOptions()
    loadBad()
  }
}

async function openSuiteDetail(row: EvalSuiteRow) {
  const { data } = await getEvalSuite(row.id)
  suiteDetailId.value = row.id
  suiteDetailTitle.value = `${data.data.name} · v${data.data.currentVersion}`
  suiteCases.value = data.data.cases || []
  suiteDetailOpen.value = true
}

/** 详情仍打开且是同一套件时，刷新 cases */
async function refreshSuiteDetailIfOpen(suiteId: number) {
  if (!suiteDetailOpen.value || suiteDetailId.value !== suiteId) return
  const { data } = await getEvalSuite(suiteId)
  suiteDetailTitle.value = `${data.data.name} · v${data.data.currentVersion}`
  suiteCases.value = data.data.cases || []
}

async function onImportSample() {
  sampleLoading.value = true
  try {
    const { data } = await importEvalSample()
    ElMessage.success(`样例已导入：v${data.data.currentVersion}，${data.data.caseCount} 条`)
    await loadSuites()
  } finally {
    sampleLoading.value = false
  }
}

function openSuiteCreate() {
  resetSuiteForm()
  suiteCreateOpen.value = true
}

/** 新建弹窗打开时清空上次填写 */
function resetSuiteForm() {
  suiteForm.suiteKey = ''
  suiteForm.name = ''
  suiteForm.description = ''
}

async function onCreateSuite() {
  if (!suiteForm.suiteKey || !suiteForm.name) {
    ElMessage.warning('请填写键与名称')
    return
  }
  // 与后端 EvalSpecs.checkSuiteKeyPattern 对齐，提前拦非法字符
  if (!/^[A-Za-z0-9._-]+$/.test(suiteForm.suiteKey.trim())) {
    ElMessage.warning('键只能包含字母、数字、点、下划线和中划线')
    return
  }
  suiteSaving.value = true
  try {
    await createEvalSuite({
      suiteKey: suiteForm.suiteKey.trim(),
      name: suiteForm.name.trim(),
      description: suiteForm.description || undefined,
      cases: [
        {
          caseKey: 'c0',
          title: '占位用例',
          input: { message: '请改写为真实用例' },
          enabled: 1,
          sortOrder: 0
        }
      ]
    })
    ElMessage.success('已创建')
    suiteCreateOpen.value = false
    await loadSuites()
  } finally {
    suiteSaving.value = false
  }
}

/**
 * 打开导入：拉取当前套件详情，序列化为可再导入的 EvalSuiteSpec JSON 回显。
 * 无历史草稿时也必须有「原 JSON」；拉失败则空编辑器并 toast。
 */
async function openImport(row: EvalSuiteRow) {
  importSuiteId.value = row.id
  importText.value = ''
  importOpen.value = true
  importLoading.value = true
  try {
    const { data } = await getEvalSuite(row.id)
    importText.value = stringifySuiteSpec(data.data)
  } catch {
    importText.value = ''
    ElMessage.error('加载套件 JSON 失败')
  } finally {
    importLoading.value = false
  }
}

/**
 * 将详情转为导入契约形态（snake_case，对齐样例 / EvalSuiteSpec）。
 */
function stringifySuiteSpec(detail: EvalSuiteDetail): string {
  const spec = {
    suite_key: detail.suiteKey,
    name: detail.name,
    description: detail.description || undefined,
    default_config: detail.defaultConfig ?? undefined,
    cases: (detail.cases || []).map((c) => ({
      case_key: c.caseKey,
      title: c.title,
      tags: c.tags?.length ? c.tags : undefined,
      enabled: c.enabled,
      sort_order: c.sortOrder,
      input: c.input ?? undefined,
      expect: c.expect ?? undefined,
      assertions: c.assertions ?? undefined,
      scoring: c.scoring ?? undefined
    }))
  }
  return JSON.stringify(spec, null, 2)
}

function clearImportText() {
  if (!importText.value) return
  importText.value = ''
}

async function onImportJson() {
  if (!importSuiteId.value) return
  let body: unknown
  try {
    body = parseImportJsonText(importText.value)
  } catch (err) {
    // 真·非法 JSON 才报格式；字段问题交给后端中文业务错
    const tip = err instanceof Error && err.message ? err.message : 'JSON 格式不正确'
    ElMessage.error(tip)
    return
  }
  importSaving.value = true
  try {
    const suiteId = importSuiteId.value
    const { data } = await importEvalSuite(suiteId, body)
    ElMessage.success(`已升至 v${data.data.currentVersion}`)
    importOpen.value = false
    await loadSuites()
    await refreshSuiteDetailIfOpen(suiteId)
  } finally {
    importSaving.value = false
  }
}

/**
 * 导入文本预处理 + JSON.parse。
 * 剥 BOM / markdown 代码围栏；禁止把整段当字符串二次包一层提交。
 */
function parseImportJsonText(raw: string): unknown {
  let text = (raw || '').replace(/^\uFEFF/, '').trim()
  if (!text) {
    throw new Error('套件 JSON 不能为空')
  }
  // 用户从 Chat/文档复制时常带 ```json ... ```
  const fence = text.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i)
  if (fence) {
    text = fence[1].trim()
  }
  try {
    return JSON.parse(text)
  } catch {
    throw new Error('JSON 格式不正确：无法解析，请检查是否截断或含非法字符')
  }
}

async function ensureAgents() {
  if (agents.value.length) return
  const { data } = await pageAgents({ current: 1, size: 100 })
  agents.value = data.data.records
}

async function openRunCreate() {
  await ensureSuiteOptions()
  await ensureAgents()
  resetRunForm()
  runCreateOpen.value = true
}

/** 每次打开创建跑批弹窗都重置，避免 destroy-on-close 仍残留父级 reactive */
function resetRunForm() {
  runForm.suiteId = undefined
  runForm.agentId = undefined
  runForm.executeNow = true
}

async function onCreateRun() {
  if (!runForm.suiteId || !runForm.agentId) {
    ElMessage.warning('请选择套件与 Agent')
    return
  }
  // 关弹窗前先记下是否立即执行，避免关窗/重置导致漏刷或漏跑
  const shouldExecute = runForm.executeNow && user.hasPerm('eval:run:trigger')
  runCreating.value = true
  try {
    const { data } = await createEvalRun({ suiteId: runForm.suiteId, agentId: runForm.agentId })
    ElMessage.success(`已建跑批 #${data.data.id}`)
    runCreateOpen.value = false
    // 创建后立刻刷列表（勿等 execute 结束，否则长时间无新行）
    await loadRuns()
    if (shouldExecute) {
      await onExecute(data.data)
    }
  } finally {
    runCreating.value = false
  }
}

async function onExecute(row: EvalRunRow) {
  executingId.value = row.id
  try {
    const { data } = await executeEvalRun(row.id)
    ElMessage.success(`执行结束：${data.data.status}`)
    await loadRuns()
    if (runDetailOpen.value && runDetail.value?.id === row.id) {
      await openRunDetail(data.data)
    }
  } finally {
    executingId.value = null
  }
}

async function onCancel(row: EvalRunRow) {
  await ElMessageBox.confirm(`确认取消跑批 #${row.id}？`, '取消跑批')
  const { data } = await cancelEvalRun(row.id)
  ElMessage.success(`已收尾为 ${data.data.status}`)
  await loadRuns()
}

async function openRunDetail(row: EvalRunRow) {
  await ensureSuiteOptions()
  await ensureAgents()
  runDetailTitle.value = buildRunDetailTitle(row)
  runDetailOpen.value = true
  await refreshRunDetail(row.id)
  startPoll(row.id)
}

/** 可读标题：跑批 #id · 套件名 · Agent 名 */
function buildRunDetailTitle(row: EvalRunRow) {
  const suite = suitePrimary(row)
  const agent = agentPrimary(row.agentId)
  return `跑批 #${row.id} · ${suite} · ${agent}`
}

async function refreshRunDetail(id: number) {
  resultLoading.value = true
  try {
    const [runRes, resultRes] = await Promise.all([
      getEvalRun(id),
      pageEvalRunResults(id, { current: 1, size: 100 })
    ])
    runDetail.value = runRes.data.data
    runResults.value = resultRes.data.data.records
    runDetailTitle.value = buildRunDetailTitle(runDetail.value)
    if (runDetail.value.report) {
      reportJson.value = runDetail.value.report
      reportText.value = JSON.stringify(runDetail.value.report, null, 2)
    } else {
      reportJson.value = null
      reportText.value = ''
    }
    if (runDetail.value.status && !isTerminal(runDetail.value.status)) {
      // keep polling
    } else {
      stopPoll()
    }
  } finally {
    resultLoading.value = false
  }
}

async function loadReport() {
  if (!runDetail.value) return
  try {
    const { data } = await getEvalReport(runDetail.value.id)
    reportJson.value = data.data
    reportText.value = JSON.stringify(data.data, null, 2)
  } catch {
    ElMessage.info('报告尚未生成')
  }
}

/** 复制详情区报告 JSON */
function copyReport() {
  if (!reportText.value) {
    ElMessage.warning('暂无报告可复制')
    return
  }
  void navigator.clipboard.writeText(reportText.value).then(
    () => ElMessage.success('报告已复制'),
    () => ElMessage.error('复制失败')
  )
}

function startPoll(id: number) {
  stopPoll()
  pollTimer = setInterval(() => {
    if (!runDetail.value || isTerminal(runDetail.value.status)) {
      stopPoll()
      return
    }
    void refreshRunDetail(id)
  }, 2000)
}

function stopPoll() {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function openPromote(row: EvalBadCaseRow) {
  promoteForm.resultId = row.resultId
  promoteForm.caseKey = `bc-${row.caseKey}-${row.resultId}`
  promoteForm.title = `坏案回流：${row.caseKey}`
  promoteForm.note = ''
  promoteForm.force = row.verdict === 'ERROR'
  promoteForm.suiteId = row.suiteId
  promoteOpen.value = true
}

async function onPromote() {
  if (!promoteForm.suiteId) {
    ElMessage.warning('请选择目标套件')
    return
  }
  promoteSaving.value = true
  try {
    const { data } = await promoteFromResult(promoteForm.suiteId, {
      resultId: promoteForm.resultId,
      caseKey: promoteForm.caseKey || undefined,
      title: promoteForm.title || undefined,
      note: promoteForm.note || undefined,
      force: promoteForm.force || undefined
    })
    ElMessage.success(`已写入套件，新版本 v${data.data.currentVersion}`)
    promoteOpen.value = false
  } finally {
    promoteSaving.value = false
  }
}

/**
 * 打开执行链路右拉；无 traceId 则 toast，不跳转全页。
 */
function openTraceDrawer(row: {
  traceId?: string | null
  sessionId?: string | null
  runId?: number
  resultId?: number
  id?: number
}) {
  if (!row.traceId) {
    ElMessage.warning('无追踪数据')
    return
  }
  traceCtx.traceId = row.traceId
  traceCtx.sessionId = row.sessionId ?? null
  traceCtx.runId = row.runId ?? runDetail.value?.id ?? null
  traceCtx.resultId = row.resultId ?? row.id ?? null
  traceDrawerOpen.value = true
}

/**
 * 套件主文案：优先 name，其次 suiteKey，最后 #id。
 * FE 字典来自 pageEvalSuites；本刀未改 BE。
 */
function suitePrimary(row: { suiteId?: number | null; suiteVersion?: number | null }) {
  if (row.suiteId == null) return '—'
  const s = suiteOptions.value.find((x) => x.id === row.suiteId)
  if (s?.name) return s.name
  if (s?.suiteKey) return s.suiteKey
  return `套件 #${row.suiteId}`
}

function suiteTooltip(row: EvalRunRow) {
  const s = suiteOptions.value.find((x) => x.id === row.suiteId)
  const key = s?.suiteKey ? `key=${s.suiteKey}` : ''
  return `suiteId=${row.suiteId}${key ? ' · ' + key : ''} · v${row.suiteVersion}`
}

/** Agent 主文案：字典名；缺失则 Agent #id */
function agentPrimary(agentId: number) {
  const a = agents.value.find((x) => x.id === agentId)
  if (a?.name) return a.name
  return `Agent #${agentId}`
}

function passRate(row: EvalRunRow) {
  const p = row.passed ?? 0
  const f = row.failed ?? 0
  const den = p + f
  if (den <= 0) return `${p}/${row.totalCases ?? 0}`
  return `${p}/${den} (${Math.round((p / den) * 100)}%)`
}

function canCancel(status: string) {
  return status === 'PENDING' || status === 'RUNNING' || status === 'CANCELING'
}

function isTerminal(status: string) {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'PARTIAL'
}

/**
 * 状态 Tag 色：成功绿、失败红、运行蓝/橙、取消灰、错误深红；对齐 obs/billing 语义。
 */
function statusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  const s = (status || '').toUpperCase()
  // 成功绿；失败/错误红；运行/排队橙；取消灰（info）
  if (s === 'SUCCEEDED' || s === 'SUCCESS' || s === 'COMPLETED' || s === 'OK') return 'success'
  if (s === 'FAILED' || s === 'FAIL' || s === 'PARTIAL' || s === 'ERROR') return 'danger'
  if (s === 'RUNNING' || s === 'PENDING' || s === 'CANCELING') return 'warning'
  if (s === 'CANCELLED' || s === 'CANCELED') return 'info'
  return 'info'
}

function statusTip(row: EvalRunRow) {
  return `pass=${row.passed ?? 0} fail=${row.failed ?? 0} err=${row.errored ?? 0} skip=${row.skipped ?? 0}`
}

function gateText(v?: number | null) {
  if (v == null) return '—'
  return v === 1 ? '通过' : '未过'
}

function ojStatusText(s?: string | null) {
  if (!s || s === 'DISABLED') return '未启用'
  if (s === 'SKIPPED') return '跳过'
  if (s === 'ERROR') return 'Judge错误'
  if (s === 'OK') return 'OK'
  return s
}

function ojLabel(row: EvalBadCaseRow) {
  const st = ojStatusText(row.openjudgeStatus)
  const scores = row.scores as { openjudge?: { score?: number } } | null
  const score = scores?.openjudge?.score
  return score != null ? `${st} ${score}` : st
}

function ruleFailSummary(details: unknown) {
  if (!Array.isArray(details)) return '—'
  const fail = details.find((d) => d && typeof d === 'object' && (d as { ok?: boolean }).ok === false) as
    | { type?: string; message?: string }
    | undefined
  if (!fail) return '—'
  return `${fail.type || '?'}: ${fail.message || ''}`
}

function finalAnswerOf(artifact: unknown) {
  if (!artifact || typeof artifact !== 'object') return '—'
  const a = (artifact as { final_answer?: string }).final_answer
  return a || '—'
}

onBeforeUnmount(() => stopPoll())

void loadSuites()
void ensureAgents()
void loadSuiteOptions()
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.eval-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.filter-bar {
  background: #fff;
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.filter-form :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: 12px;
}
.filter-input,
.filter-select {
  width: 200px;
}
.btn-icon {
  margin-right: 4px;
}
.panel {
  background: #fff;
  border-radius: 12px;
  padding: 12px 16px 8px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.eval-table :deep(.el-table__header th) {
  text-align: center;
}
/* 套件/Agent：名称与版本/ID 同一行，不换行 */
.eval-table :deep(.el-tooltip__trigger) {
  display: inline-flex !important;
  max-width: 100%;
  vertical-align: middle;
}
.cell-inline {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  max-width: 100%;
  white-space: nowrap;
  vertical-align: middle;
}
.cell-main {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.cell-tag {
  flex: none;
}
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  background: #94a3b8;
}
.status-dot.ok {
  background: #22c55e;
}
.status-dot.off {
  background: #cbd5e1;
}
.gate-ok {
  color: #16a34a;
}
.gate-fail {
  color: #dc2626;
}
.hint {
  margin-top: 12px;
  color: #64748b;
  font-size: 12px;
}
.field-tip {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}
.field-tip code {
  padding: 0 4px;
  background: #f1f5f9;
  border-radius: 4px;
  font-size: 12px;
}
.tag {
  margin-right: 4px;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 16px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
  color: #475569;
}
.meta-muted {
  color: #94a3b8;
}
.report-box {
  margin-top: 16px;
}
.report-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.report-box h3 {
  margin: 0;
  font-size: 14px;
}
.report-pre {
  max-height: 320px;
  overflow: auto;
  background: #f8fafc;
  border-radius: 8px;
  padding: 12px;
  font-size: 12px;
  line-height: 1.45;
}
.full {
  width: 100%;
}
.import-body {
  min-height: 120px;
}
.import-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
</style>
