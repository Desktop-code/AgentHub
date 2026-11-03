<template>
  <div class="wf-page">
    <!-- 顶栏：保存 / 校验 / 发布；试跑本刀禁用并标注「下刀」 -->
    <PageHeader :title="def?.name || '工作流编辑'" :description="headerDesc">
      <template #extra>
        <el-button @click="goList">返回列表</el-button>
        <el-button v-if="canEdit" link type="primary" @click="reloadDraft">重新加载</el-button>
        <el-button v-if="canEdit" :loading="saving" :disabled="!dirty" @click="onSaveDraft()">
          保存草稿
        </el-button>
        <el-button :loading="validating" @click="onValidate">校验</el-button>
        <el-button
          v-if="canPublish"
          type="primary"
          :loading="publishing"
          :disabled="!workflowId"
          @click="onPublish"
        >
          发布
        </el-button>
        <el-button
          v-if="canRunTrial"
          type="primary"
          plain
          :disabled="!workflowId"
          @click="trialVisible = true"
        >
          试跑
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="block-alert" />

    <!-- 校验结果：点击条目定位节点；图改过之后提示重新校验 -->
    <div v-if="issues.length || issuesStale" class="wf-issues">
      <span class="wf-issues__title">
        <el-tag size="small" type="danger" effect="plain">{{ errorCount }} 错误</el-tag>
        <el-tag size="small" type="warning" effect="plain">{{ warnCount }} 提示</el-tag>
        <span v-if="issuesStale" class="wf-issues__stale">图已修改，建议重新校验</span>
      </span>
      <div class="wf-issues__list">
        <button
          v-for="issue in issues"
          :key="issue.key"
          type="button"
          class="wf-issue"
          :class="issue.level === 'error' ? 'is-error' : 'is-warn'"
          :title="issue.nodeCode ? `定位到 ${issue.nodeCode}` : '无关联节点'"
          @click="focusIssue(issue)"
        >
          <span class="wf-issue__code">{{ issue.code }}</span>
          <span class="wf-issue__msg">{{ issue.message }}</span>
        </button>
      </div>
    </div>

    <div v-loading="loading" class="wf-body">
      <!-- 左：P0 节点面板；P1 灰显不可拖 -->
      <aside class="wf-palette">
        <h3 class="wf-panel__title">节点</h3>
        <div class="wf-palette__list">
          <div
            v-for="item in PALETTE"
            :key="item.type"
            class="wf-palette__item"
            :class="{ 'is-p1': !item.p0 }"
            :draggable="item.p0"
            :title="item.p0 ? `拖到画布添加「${item.label}」` : 'P1：本刀不实现'"
            @dragstart="onDragStart(item, $event)"
          >
            <span class="wf-palette__label">{{ item.label }}</span>
            <span v-if="!item.p0" class="wf-palette__p1">P1</span>
            <span class="wf-palette__hint">{{ item.hint }}</span>
          </div>
        </div>
      </aside>

      <!-- 中：VueFlow 画布 -->
      <div class="wf-canvas" @dragover.prevent @drop.prevent="onDrop">
        <el-alert
          v-if="canvasError"
          :title="canvasError"
          type="error"
          show-icon
          :closable="false"
          class="canvas-alert"
        />
        <VueFlow
          :node-types="nodeTypes"
          :snap-to-grid="true"
          :snap-grid="[10, 10]"
          :min-zoom="0.2"
          :max-zoom="2"
          :delete-key-code="['Delete', 'Backspace']"
          :fit-view-on-init="false"
          @connect="onConnect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="onPaneClick"
          @error="onVueFlowError"
        >
          <Background :gap="16" color="#e5e7eb" />
          <Controls />
        </VueFlow>
      </div>

      <!-- 右：属性抽屉 -->
      <aside class="wf-props">
        <template v-if="selectedNode">
          <h3 class="wf-panel__title">
            节点属性
            <el-tag size="small" effect="plain">{{ nodeTypeLabel(selectedNode.data.nodeType) }}</el-tag>
          </h3>
          <el-form label-position="top" class="wf-form">
            <el-form-item label="编码 code" required>
              <el-input v-model="selectedNode.data.code" maxlength="64" :disabled="!canEdit" />
            </el-form-item>
            <el-form-item label="显示名">
              <el-input v-model="selectedNode.data.name" maxlength="64" :disabled="!canEdit" />
            </el-form-item>

            <!-- AGENT：只能绑叶子 Agent，ROUTER 已在选项中排除 -->
            <template v-if="selectedNode.data.nodeType === 'AGENT'">
              <el-form-item label="绑定 Agent" required>
                <el-alert
                  v-if="agentLoadError"
                  :title="agentLoadError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="block-alert"
                />
                <el-select
                  v-else
                  v-model="selectedNode.data.config.agentId"
                  class="wf-field"
                  clearable
                  filterable
                  placeholder="选择 Agent（不含 ROUTER）"
                  :disabled="!canEdit"
                >
                  <el-option
                    v-for="item in agentOptions"
                    :key="item.id"
                    :label="`${item.name}（${item.code}）`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <el-alert
                v-if="routerBindingHint"
                :title="routerBindingHint"
                type="error"
                show-icon
                :closable="false"
                class="block-alert"
              />
            </template>

            <!-- TOOL：直接绑定工具项 -->
            <template v-else-if="selectedNode.data.nodeType === 'TOOL'">
              <el-form-item label="绑定工具" required>
                <el-alert
                  v-if="toolLoadError"
                  :title="toolLoadError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="block-alert"
                />
                <el-select
                  v-else
                  v-model="selectedNode.data.config.toolId"
                  class="wf-field"
                  clearable
                  filterable
                  placeholder="选择工具项"
                  :disabled="!canEdit"
                >
                  <el-option
                    v-for="item in toolOptions"
                    :key="item.id"
                    :label="toolLabel(item)"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
            </template>

            <!-- CONDITION：JSONLogic 子集，禁 SpEL -->
            <template v-else-if="selectedNode.data.nodeType === 'CONDITION'">
              <el-form-item label="条件表达式（JSONLogic 子集）">
                <JsonLogicEditor
                  :model-value="selectedNode.data.config.condition"
                  @update:model-value="setCondition"
                />
              </el-form-item>
              <div class="wf-tip">条件节点必须恰好有 1 条 DEFAULT 出边，否则发布被拒。</div>
            </template>

            <!-- HUMAN：超时策略必填 -->
            <template v-else-if="selectedNode.data.nodeType === 'HUMAN'">
              <el-form-item label="超时策略" required>
                <el-select
                  v-model="selectedNode.data.config.timeoutPolicy"
                  class="wf-field"
                  clearable
                  placeholder="必须选择"
                  :disabled="!canEdit"
                >
                  <el-option
                    v-for="item in TIMEOUT_POLICIES"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <div v-if="!selectedNode.data.config.timeoutPolicy" class="wf-tip wf-tip--error">
                未配置超时策略会阻塞发布；待办流转与提醒留给后续刀。
              </div>
              <el-form-item label="超时毫秒数（可选）">
                <el-input-number
                  v-model="selectedNode.data.config.timeoutMs"
                  class="wf-field"
                  :min="0"
                  :step="60000"
                  controls-position="right"
                  :disabled="!canEdit"
                />
              </el-form-item>
              <!-- W4 处理人规则：空=有 human:act 即可；非法规则校验器会拒发 -->
              <el-form-item label="处理人规则">
                <el-select
                  v-model="assigneeKind"
                  class="wf-field"
                  :disabled="!canEdit"
                >
                  <el-option v-for="item in ASSIGNEE_KINDS" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item
                v-if="assigneeKind !== 'EMPTY'"
                :label="assigneeKind === 'ROLE' ? '角色编码' : '用户 ID 列表'"
              >
                <el-input
                  v-model="assigneeValue"
                  maxlength="128"
                  :placeholder="assigneeKind === 'ROLE' ? '如 ops_admin' : '如 1,2,7（逗号分隔）'"
                  :disabled="!canEdit"
                />
              </el-form-item>
              <div v-if="assigneeParsed.problem" class="wf-tip wf-tip--error">
                规则不合法：{{ assigneeParsed.problem }}（发布会失败）
              </div>
              <div v-else-if="assigneeKind === 'EMPTY'" class="wf-tip">
                未配置处理人：任何具备 workflow:human:act 的用户都能处理。建议配置角色或用户，避免误批。
              </div>
              <div v-else class="wf-tip">仅命中该规则且具备 workflow:human:act 的用户可处理；无超级管理员绕过。</div>
            </template>

            <!-- FORK / JOIN：同组才能成对 -->
            <template
              v-else-if="
                selectedNode.data.nodeType === 'PARALLEL_FORK' ||
                selectedNode.data.nodeType === 'PARALLEL_JOIN'
              "
            >
              <el-form-item label="并行组 forkGroupId" required>
                <el-input
                  v-model="selectedNode.data.config.forkGroupId"
                  maxlength="64"
                  placeholder="如 g1；分叉与汇合必须一致"
                  :disabled="!canEdit"
                />
              </el-form-item>
            </template>

            <!-- 映射：简单 KV，路径只认 $.input / $.outputs / $.vars -->
            <template v-if="showMapping">
              <el-form-item label="入参映射">
                <MappingEditor
                  :model-value="selectedNode.data.config.inputMapping"
                  @update:model-value="setInputMapping"
                />
              </el-form-item>
              <el-form-item label="出参映射">
                <MappingEditor
                  :model-value="selectedNode.data.config.outputMapping"
                  @update:model-value="setOutputMapping"
                />
              </el-form-item>
            </template>

            <el-button v-if="canEdit" type="danger" plain class="wf-delete" @click="deleteSelected">
              删除节点
            </el-button>
          </el-form>
        </template>

        <template v-else-if="selectedEdge">
          <h3 class="wf-panel__title">
            连线属性
            <el-tag size="small" effect="plain">{{ edgeTypeLabel(selectedEdge.data.edgeType) }}</el-tag>
          </h3>
          <el-form label-position="top" class="wf-form">
            <el-form-item label="连线类型">
              <el-select
                :model-value="selectedEdge.data.edgeType"
                class="wf-field"
                :disabled="!canEdit"
                @update:model-value="setEdgeType"
              >
                <el-option v-for="item in EDGE_TYPES" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="显示标签">
              <el-input
                :model-value="edgeLabelInput"
                maxlength="64"
                placeholder="留空则按类型显示（条件 / 默认 / 异常）"
                :disabled="!canEdit"
                @update:model-value="setEdgeLabel"
              />
            </el-form-item>
            <el-form-item v-if="selectedEdge.data.edgeType === 'COND'" label="条件表达式（JSONLogic 子集）">
              <JsonLogicEditor
                :model-value="selectedEdge.data.condition"
                @update:model-value="setEdgeCondition"
              />
            </el-form-item>
            <div v-if="selectedEdge.data.edgeType === 'DEFAULT'" class="wf-tip">
              DEFAULT 是条件节点兜底出口，每个条件节点只允许 1 条。
            </div>
            <el-button v-if="canEdit" type="danger" plain class="wf-delete" @click="deleteSelected">
              删除连线
            </el-button>
          </el-form>
        </template>

        <div v-else class="wf-props__empty">
          <p>在画布上选中节点或连线以编辑属性。</p>
          <p class="wf-props__empty-hint">从左侧拖入节点，从节点两侧锚点拉线连线。</p>
        </div>
      </aside>
    </div>

    <!-- 底栏：草稿状态 + 上次校验计数 -->
    <div class="wf-footer">
      <span class="wf-footer__left">{{ draftHint }}</span>
      <span class="wf-footer__right">
        <template v-if="lastResult">
          上次校验：<b :class="lastResult.errors ? 'is-error' : ''">{{ lastResult.errors }}</b> 错误 /
          <b :class="lastResult.warns ? 'is-warn' : ''">{{ lastResult.warns }}</b> 提示
        </template>
        <template v-else>尚未校验</template>
        <span v-if="dirty" class="wf-footer__dirty">· 草稿有未保存修改</span>
      </span>
    </div>

    <!-- 试跑抽屉：选已发布版本、输入样例、处理人工待办；节点状态回填画布 -->
    <WorkflowTrialDrawer
      v-model="trialVisible"
      :workflow-id="workflowId"
      :default-release-id="def?.defaultReleaseId ?? null"
      :can-act="canHumanAct"
      @run-change="onRunChange"
    />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'WorkflowEditor' })
/**
 * 工作流画布编辑页。所属域：workflow。
 * <p>职责：VueFlow 建图（W1）、存草稿（updateTime 乐观锁）、图校验、发布不可变快照，
 * 以及挂「试跑」抽屉（W2：选发布版本 → 建 run → 回填节点状态高亮 / 处理人工待办）。
 * <p>边界：引擎在后端；本页不发模型/工具出站，也不参与 run 状态计算。
 * <p>单一真源：画布状态在 VueFlow store；节点/边的修改都走 store API，
 * 保存时从 store 读，避免「画布显示的」和「保存出去的」不一致。
 */
import { computed, markRaw, nextTick, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type {
  Connection,
  Edge,
  EdgeMouseEvent,
  GraphEdge,
  GraphNode,
  Node,
  NodeMouseEvent,
  NodeTypesObject
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import PageHeader from '@/components/PageHeader.vue'
import MappingEditor from './MappingEditor.vue'
import JsonLogicEditor from './JsonLogicEditor.vue'
import WorkflowNodeCard from './WorkflowNodeCard.vue'
import WorkflowTrialDrawer from './WorkflowTrialDrawer.vue'
import { pageAgents } from '@/api/agent'
import { listAllItems } from '@/api/tool'
import {
  getWorkflowDef,
  getWorkflowDraft,
  publishWorkflow,
  saveWorkflowDraft,
  validateWorkflow
} from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import { parseAssigneeRule } from '@/utils/workflowAssignee'
import type { AgentCard } from '@/types/agent'
import type { ToolItemRow } from '@/types/tool'
import type {
  WorkflowDefRow,
  WorkflowEdgeType,
  WorkflowFlowEdgeData,
  WorkflowFlowNodeData,
  WorkflowGraph,
  WorkflowGraphEdge,
  WorkflowGraphNode,
  WorkflowIssue,
  WorkflowNodeConfig,
  WorkflowNodeType,
  WorkflowPaletteItem,
  WorkflowRunDetail,
  WorkflowValidateResp
} from '@/types/workflow'

/** 拖拽节点时写入的 MIME，避免和外部拖拽混淆 */
const DRAG_MIME = 'application/x-agenthub-workflow-node'

/** 节点面板：P0 可拖，P1 灰显 */
const PALETTE: WorkflowPaletteItem[] = [
  { type: 'START', label: '开始', hint: '唯一入口', p0: true },
  { type: 'END', label: '结束', hint: '至少 1 个', p0: true },
  { type: 'AGENT', label: 'Agent', hint: '调叶子 Agent，禁 ROUTER', p0: true },
  { type: 'TOOL', label: '工具', hint: '直调工具项', p0: true },
  { type: 'CONDITION', label: '条件', hint: 'COND + 恰好 1 条 DEFAULT', p0: true },
  { type: 'PARALLEL_FORK', label: '并行分叉', hint: '按 forkGroupId 成对', p0: true },
  { type: 'PARALLEL_JOIN', label: '并行汇合', hint: '按 forkGroupId 成对', p0: true },
  { type: 'HUMAN', label: '人工审批', hint: '必须配超时策略', p0: true },
  { type: 'SUBGRAPH', label: '子图', hint: 'P1；跨租户永久禁止', p0: false },
  { type: 'KB_RETRIEVE', label: '知识检索', hint: 'P1', p0: false },
  { type: 'SKILL_INJECT', label: 'Skill 注入', hint: 'P1', p0: false },
  { type: 'TIMER', label: '定时等待', hint: 'P1', p0: false },
  { type: 'COMPENSATE', label: '补偿', hint: 'P1', p0: false }
]

/** 节点默认显示名 */
const NODE_NAME: Record<string, string> = {
  START: '开始',
  END: '结束',
  AGENT: 'Agent',
  TOOL: '工具',
  CONDITION: '条件',
  PARALLEL_FORK: '并行分叉',
  PARALLEL_JOIN: '并行汇合',
  HUMAN: '人工审批'
}

/** code 生成前缀 */
const CODE_PREFIX: Record<string, string> = {
  START: 'start',
  END: 'end',
  AGENT: 'agent',
  TOOL: 'tool',
  CONDITION: 'cond',
  PARALLEL_FORK: 'fork',
  PARALLEL_JOIN: 'join',
  HUMAN: 'human'
}

/** 类型中文名（属性面板标题用） */
const NODE_TYPE_LABEL: Record<string, string> = {
  START: '开始',
  END: '结束',
  AGENT: 'Agent',
  TOOL: '工具',
  CONDITION: '条件',
  PARALLEL_FORK: '并行分叉',
  PARALLEL_JOIN: '并行汇合',
  HUMAN: '人工审批'
}

/** 边类型下拉；CANCEL 本刀不产出 */
const EDGE_TYPES: Array<{ label: string; value: WorkflowEdgeType }> = [
  { label: '普通', value: 'NORMAL' },
  { label: '条件', value: 'COND' },
  { label: '默认（兜底）', value: 'DEFAULT' },
  { label: '异常', value: 'ERROR' }
]

const EDGE_TYPE_LABEL: Record<string, string> = {
  NORMAL: '普通',
  COND: '条件',
  DEFAULT: '默认',
  ERROR: '异常'
}

/** 人工卡点超时策略，与后端枚举一致 */
const TIMEOUT_POLICIES = [
  { label: '超时自动拒绝', value: 'AUTO_REJECT' },
  { label: '超时自动通过', value: 'AUTO_APPROVE' },
  { label: '超时升级处理', value: 'ESCALATE' }
]

/** 人工卡点处理人规则类型（W4）；空=任何有 human:act 的人可处理 */
const ASSIGNEE_KINDS = [
  { label: '不限（任何有处理权限的人）', value: 'EMPTY' },
  { label: '按角色（ROLE:角色码）', value: 'ROLE' },
  { label: '按用户（USER:用户id）', value: 'USER' }
]

const user = useUserStore()
const route = useRoute()
const router = useRouter()

/** 组件名固定，按 data.nodeType 上色，避免注册 15 个节点类型 */
const nodeTypes = { workflow: markRaw(WorkflowNodeCard) } as NodeTypesObject

const vf = useVueFlow()
const { addNodes, addEdges, removeNodes, removeEdges, findNode, screenToFlowCoordinate, getViewport, fitView, setCenter } =
  vf

interface SelectedElement {
  kind: 'node' | 'edge'
  id: string
}

interface IssueItem extends WorkflowIssue {
  level: 'error' | 'warn'
  key: string
}

const workflowId = computed(() => {
  const raw = route.params.id
  const text = Array.isArray(raw) ? raw[0] : raw
  const numeric = Number(text)
  return Number.isFinite(numeric) && numeric > 0 ? numeric : 0
})

const loading = ref(false)
const loadError = ref('')
const canvasError = ref('')
const def = ref<WorkflowDefRow | null>(null)
const draftUpdateTime = ref('')
/** 最近一次「载入或保存」的图签名，用于脏检查 */
const baseline = ref('')
const validatedSignature = ref('')

const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)

const issues = ref<IssueItem[]>([])
const lastResult = ref<{ errors: number; warns: number } | null>(null)

const selected = ref<SelectedElement | null>(null)

const agentOptions = ref<AgentCard[]>([])
const toolOptions = ref<ToolItemRow[]>([])
const agentLoadError = ref('')
const toolLoadError = ref('')

const canEdit = computed(() => user.hasPerm('workflow:def:edit'))
const canPublish = computed(() => user.hasPerm('workflow:def:publish'))
/** 试跑入口：只认已发布版本，按钮在无权限时隐藏 */
const canRunTrial = computed(() => user.hasPerm('workflow:run:trial'))
/** 人工待办处理权限；无此码时抽屉内只读 */
const canHumanAct = computed(() => user.hasPerm('workflow:human:act'))

const trialVisible = ref(false)

const errorCount = computed(() => issues.value.filter((item) => item.level === 'error').length)
const warnCount = computed(() => issues.value.filter((item) => item.level === 'warn').length)
/** 图改过但还没重新校验：提示用户结果已过期 */
const issuesStale = computed(
  () => Boolean(validatedSignature.value) && validatedSignature.value !== currentSignature.value
)

const headerDesc = computed(() => {
  const base = def.value ? `编码 ${def.value.code}` : ''
  const version = def.value?.defaultVersion ? ` · 当前发布 v${def.value.defaultVersion}` : ' · 尚未发布'
  return `${base}${version} · 画布建图 / 草稿 / 校验 / 发布 / 试跑`
})

const draftHint = computed(() => {
  if (!def.value) {
    return ''
  }
  return def.value.status === 'HAS_RELEASE'
    ? '当前草稿与已发布版本独立；试跑只跑已发布快照，发布后生成新的不可变快照。'
    : '尚未发布：发布成功后才能试跑。'
})

const selectedNode = computed<GraphNode<WorkflowFlowNodeData> | null>(() => {
  const sel = selected.value
  if (!sel || sel.kind !== 'node') {
    return null
  }
  const found = vf.nodes.value.find((node) => node.id === sel.id)
  return (found as GraphNode<WorkflowFlowNodeData> | undefined) ?? null
})

const selectedEdge = computed<GraphEdge<WorkflowFlowEdgeData> | null>(() => {
  const sel = selected.value
  if (!sel || sel.kind !== 'edge') {
    return null
  }
  const found = vf.edges.value.find((edge) => edge.id === sel.id)
  return (found as GraphEdge<WorkflowFlowEdgeData> | undefined) ?? null
})

/** 只有带输入输出的节点才展示映射编辑 */
const showMapping = computed(() => {
  const type = selectedNode.value?.data?.nodeType
  return type === 'START' || type === 'END' || type === 'AGENT' || type === 'TOOL'
})

/** HUMAN 处理人规则解析结果；非法时模板给红字提示（发布会被后端拒） */
const assigneeParsed = computed(() => parseAssigneeRule(selectedNode.value?.data?.config?.assigneeRule))

/**
 * 从规则原文判断类型（只看前缀，不看值是否合法）。
 * <p>为什么不用 parseAssigneeRule().kind：用户刚切到「按角色」时值是空的，
 * 解析会报 problem，若按 problem 回落 EMPTY，下拉会立刻弹回「不限」。
 */
function ruleKindOf(raw?: string | null): string {
  const text = String(raw ?? '').trim()
  const upper = text.toUpperCase()
  if (upper.startsWith('ROLE:')) {
    return 'ROLE'
  }
  if (upper.startsWith('USER:')) {
    return 'USER'
  }
  return 'EMPTY'
}

/**
 * 处理人规则类型。切换类型时按新语法重建规则串：
 * EMPTY→空串、ROLE→`ROLE:`、USER→`USER:`，旧值不跨语法复用（避免拼出非法串）。
 */
const assigneeKind = computed({
  get: () => ruleKindOf(selectedNode.value?.data?.config?.assigneeRule),
  set: (kind: string) => {
    const node = selectedNode.value
    if (!node) {
      return
    }
    const rule = kind === 'ROLE' ? 'ROLE:' : kind === 'USER' ? 'USER:' : ''
    node.data = { ...node.data, config: { ...node.data.config, assigneeRule: rule } }
  }
})

/** 处理人规则值：ROLE 取角色码，USER 取逗号分隔的用户 id（保留用户原始输入） */
const assigneeValue = computed({
  get: () => {
    const text = String(selectedNode.value?.data?.config?.assigneeRule ?? '').trim()
    return ruleKindOf(text) === 'EMPTY' ? '' : text.slice(5)
  },
  set: (value: string) => {
    const node = selectedNode.value
    if (!node) {
      return
    }
    const kind = assigneeKind.value
    if (kind === 'EMPTY') {
      return
    }
    const rule = `${kind}:${String(value ?? '').trim()}`
    node.data = { ...node.data, config: { ...node.data.config, assigneeRule: rule } }
  }
})

/** 历史脏数据可能绑了 ROUTER：明确报错，避免用户以为能发布 */
const routerBindingHint = computed(() => {  const agentId = selectedNode.value?.data?.config?.agentId
  if (!agentId) {
    return ''
  }
  const agent = agentOptions.value.find((item) => item.id === agentId)
  if (!agent) {
    return '该 Agent 不在可绑定列表中（可能已停用或为 ROUTER），发布会被拒绝'
  }
  return ''
})

/** 边标签输入框：空串表示「按类型自动显示」 */
const edgeLabelInput = computed({
  get: () => selectedEdge.value?.data?.label ?? '',
  set: (value: string) => setEdgeLabel(value)
})

/** 当前画布签名（不含 viewport，避免平移也判脏） */
const currentSignature = computed(() => graphSignature(buildGraph()))

/** 相对最近一次载入/保存是否有改动 */
const dirty = computed(() => currentSignature.value !== baseline.value)

/**
 * 图 → 落库结构。
 *
 * @return 当前画布对应的 graph_json（含 viewport）
 */
function buildGraph(): WorkflowGraph {
  const view = getViewport()
  const nodes: WorkflowGraphNode[] = vf.nodes.value.map((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    return {
      id: node.id,
      code: String(data.code ?? ''),
      type: String(data.nodeType ?? ''),
      name: String(data.name ?? ''),
      config: (data.config ?? {}) as WorkflowNodeConfig,
      ui: {
        x: Math.round(node.position?.x ?? 0),
        y: Math.round(node.position?.y ?? 0)
      }
    }
  })
  const edges: WorkflowGraphEdge[] = vf.edges.value.map((edge) => {
    const data = (edge.data || {}) as WorkflowFlowEdgeData
    return {
      id: edge.id,
      source: String(edge.source ?? ''),
      target: String(edge.target ?? ''),
      type: String(data.edgeType ?? 'NORMAL'),
      label: data.label ?? null,
      config: (data.config ?? {}) as Record<string, unknown>
    }
  })
  return {
    nodes,
    edges,
    viewport: {
      x: Math.round(view.x),
      y: Math.round(view.y),
      zoom: Number(view.zoom.toFixed(3))
    }
  }
}

/**
 * 图签名：只比 nodes / edges，忽略 viewport 与展示字段。
 *
 * @param graph 图
 */
function graphSignature(graph: WorkflowGraph): string {
  return JSON.stringify({ nodes: graph.nodes, edges: graph.edges })
}

/** 落库结构 → 画布节点 */
function toFlowNode(item: WorkflowGraphNode): Node<WorkflowFlowNodeData> {
  return {
    id: String(item.id),
    type: 'workflow',
    position: { x: Number(item.ui?.x ?? 0), y: Number(item.ui?.y ?? 0) },
    data: {
      code: String(item.code ?? ''),
      name: String(item.name ?? item.code ?? ''),
      nodeType: String(item.type ?? ''),
      config: (item.config ?? {}) as WorkflowNodeConfig
    }
  }
}

/** 落库结构 → 画布连线 */
function toFlowEdge(item: WorkflowGraphEdge): Edge<WorkflowFlowEdgeData> {
  const edgeType = normalizeEdgeType(item.type)
  const label = item.label ?? null
  return {
    id: String(item.id),
    source: String(item.source),
    target: String(item.target),
    type: 'default',
    label: labelText(edgeType, label),
    markerEnd: MarkerType.ArrowClosed,
    data: {
      edgeType,
      label,
      condition: (item.config ?? {}).condition,
      config: (item.config ?? {}) as Record<string, unknown>
    }
  }
}

/** 未知边类型回落 NORMAL（后端仍会报 EDGE_TYPE_UNKNOWN 兜底） */
function normalizeEdgeType(raw: string | undefined): WorkflowEdgeType {
  const text = String(raw || '').toUpperCase()
  if (text === 'COND' || text === 'DEFAULT' || text === 'ERROR') {
    return text
  }
  return 'NORMAL'
}

/** 边展示文案：自定义标签优先，否则按类型给默认中文 */
function labelText(edgeType: string, label: string | null): string {
  if (label && label.trim()) {
    return label.trim()
  }
  if (edgeType === 'COND') {
    return '条件'
  }
  if (edgeType === 'DEFAULT') {
    return '默认'
  }
  if (edgeType === 'ERROR') {
    return '异常'
  }
  return ''
}

/** 属性面板里的类型中文名 */
function nodeTypeLabel(type: string): string {
  return NODE_TYPE_LABEL[type] || type || '未知'
}

/** 连线类型中文名 */
function edgeTypeLabel(type: string): string {
  return EDGE_TYPE_LABEL[type] || type || '未知'
}

/** 工具下拉文案：停用也展示，避免用户以为工具消失 */
function toolLabel(item: ToolItemRow): string {
  const name = item.title ? `${item.title}（${item.name}）` : item.name
  return item.status === 1 ? name : `${name} · 已停用`
}

/**
 * 读取定义与草稿并铺到画布。
 *
 * @return 无
 */
async function loadAll() {
  const id = workflowId.value
  if (!id) {
    loadError.value = '缺少工作流 id，请从列表进入'
    return
  }
  loading.value = true
  loadError.value = ''
  canvasError.value = ''
  try {
    const [defResp, draftResp] = await Promise.all([getWorkflowDef(id), getWorkflowDraft(id)])
    def.value = defResp.data.data
    const graph = draftResp.data.data.graph || { nodes: [], edges: [] }
    vf.setNodes((graph.nodes || []).map(toFlowNode))
    vf.setEdges((graph.edges || []).map(toFlowEdge))
    draftUpdateTime.value = draftResp.data.data.updateTime || ''
    selected.value = null
    issues.value = []
    lastResult.value = null
    validatedSignature.value = ''
    baseline.value = graphSignature(buildGraph())
    await nextTick()
    // 首屏自适应；viewpoint 不强制回放，避免旧视口把节点推到屏幕外
    await fitView({ padding: 0.2, maxZoom: 1.1 }).catch(() => false)
  } catch {
    loadError.value = '工作流加载失败：请确认存在、属于当前租户且草稿完好'
  } finally {
    loading.value = false
  }
}

/**
 * 把 run 详情中的节点状态回填到画布 data.runStatus，用于高亮已走 / 失败 / 等待人工的节点。
 * <p>run 详情里的节点按 code 对应；未出现在 run 中的节点清空状态，
 * 这样切换不同 run 不会残留上一次的颜色。
 *
 * @param detail 最新 run 详情；null 表示清空高亮
 */
function onRunChange(detail: WorkflowRunDetail | null) {
  const statusByCode = new Map<string, string>()
  if (detail) {
    detail.nodeRuns.forEach((node) => statusByCode.set(node.nodeCode, node.status))
  }
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    const next = statusByCode.get(String(data.code ?? ''))
    node.data = { ...data, runStatus: next }
  })
}

/** 只刷新定义元数据（发布后状态/默认版本） */
async function loadDef() {
  const id = workflowId.value
  if (!id) {
    return
  }
  try {
    const { data } = await getWorkflowDef(id)
    def.value = data.data
  } catch {
    // 元数据刷新失败不覆盖画布，保持当前编辑态
  }
}

/** Agent 下拉：排除 ROUTER（W2），失败给中文错误且禁用绑定 */
async function loadAgentOptions() {
  try {
    const { data } = await pageAgents({ current: 1, size: 100 })
    agentOptions.value = (data.data?.records || []).filter((item) => item.runMode !== 'ROUTER')
  } catch {
    agentOptions.value = []
    agentLoadError.value = 'Agent 列表加载失败，暂时无法绑定 Agent；请刷新页面重试'
  }
}

/** 工具下拉：含停用项，由文案标注 */
async function loadToolOptions() {
  try {
    toolOptions.value = await listAllItems()
  } catch {
    toolOptions.value = []
    toolLoadError.value = '工具列表加载失败，暂时无法绑定工具；请刷新页面重试'
  }
}

/** 保存草稿；silent=true 用于校验/发布前的自动保存 */
async function onSaveDraft(silent = false): Promise<boolean> {
  const id = workflowId.value
  if (!id) {
    return false
  }
  if (!canEdit.value) {
    ElMessage.warning('没有编辑权限，无法保存草稿')
    return false
  }
  saving.value = true
  try {
    const graph = buildGraph()
    const { data } = await saveWorkflowDraft(id, graph, draftUpdateTime.value || undefined)
    draftUpdateTime.value = data.data.updateTime || ''
    baseline.value = graphSignature(graph)
    if (!silent) {
      ElMessage.success('草稿已保存')
    }
    return true
  } catch (err) {
    // 拦截器已弹出后端中文文案；409 表示他人先改过，明确引导重新加载
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      canvasError.value = '草稿已被他人修改（409）：请先保留你的改动，再点顶栏「重新加载」拿最新草稿'
    }
    return false
  } finally {
    saving.value = false
  }
}

/** 校验：以库里的草稿为准，因此先保存（有编辑权限且脏时） */
async function onValidate() {
  const id = workflowId.value
  if (!id) {
    return
  }
  if (canEdit.value && dirty.value) {
    const saved = await onSaveDraft(true)
    if (!saved) {
      return
    }
  }
  validating.value = true
  try {
    const { data } = await validateWorkflow(id)
    applyValidation(data.data)
    if (data.data.passed) {
      ElMessage.success(`校验通过，${data.data.warns.length} 条提示`)
    } else {
      ElMessage.error(`校验未通过：${data.data.errors.length} 个错误`)
    }
  } catch {
    // 拦截器已提示
  } finally {
    validating.value = false
  }
}

/** 发布：先存草稿 → 生成不可变版本 */
async function onPublish() {
  const id = workflowId.value
  if (!id || !canPublish.value) {
    return
  }
  let note = ''
  try {
    const result = await ElMessageBox.prompt(
      '可选填发布说明。发布后以当前草稿生成新的不可变版本，历史版本不会改动。',
      '发布工作流',
      {
        confirmButtonText: '发布',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '本次发布说明（最多 512 字）',
        inputValidator: (value: string) => (value || '').length <= 512 || '最多 512 字'
      }
    )
    note = ((result as { value?: string }).value || '').trim()
  } catch {
    return
  }
  if (canEdit.value) {
    const saved = await onSaveDraft(true)
    if (!saved) {
      return
    }
  }
  publishing.value = true
  try {
    const { data } = await publishWorkflow(id, { note: note || undefined })
    ElMessage.success(`已发布 v${data.data.version}`)
    issues.value = []
    lastResult.value = null
    validatedSignature.value = ''
    await loadDef()
  } catch {
    // 发布校验未通过时后端已返回中文原因
  } finally {
    publishing.value = false
  }
}

/** 校验结果 → 顶栏问题条 */
function applyValidation(result: WorkflowValidateResp) {
  const list: IssueItem[] = []
  result.errors.forEach((issue, index) => {
    list.push({ ...issue, level: 'error', key: `e-${index}-${issue.code}` })
  })
  result.warns.forEach((issue, index) => {
    list.push({ ...issue, level: 'warn', key: `w-${index}-${issue.code}` })
  })
  issues.value = list
  lastResult.value = { errors: result.errors.length, warns: result.warns.length }
  validatedSignature.value = currentSignature.value
}

/** 点击问题条：选中并居中到节点 */
function focusIssue(issue: IssueItem) {
  const nodeId = issue.nodeId
  if (!nodeId) {
    return
  }
  selected.value = { kind: 'node', id: nodeId }
  const node = findNode(nodeId)
  if (!node) {
    return
  }
  const width = Number(node.dimensions?.width ?? 160)
  const height = Number(node.dimensions?.height ?? 60)
  void setCenter(node.position.x + width / 2, node.position.y + height / 2, { zoom: 1, duration: 300 })
}

/** 拖出面板：把节点类型写进 DataTransfer */
function onDragStart(item: WorkflowPaletteItem, event: Event) {
  const dragEvent = event as DragEvent
  if (!item.p0 || !canEdit.value) {
    dragEvent.preventDefault()
    return
  }
  dragEvent.dataTransfer?.setData(DRAG_MIME, item.type)
  dragEvent.dataTransfer?.setData('text/plain', item.type)
  if (dragEvent.dataTransfer) {
    dragEvent.dataTransfer.effectAllowed = 'move'
  }
}

/** 落到画布：屏幕坐标转流坐标后新增节点 */
function onDrop(event: Event) {
  const dragEvent = event as DragEvent
  if (!canEdit.value) {
    ElMessage.warning('没有编辑权限')
    return
  }
  const raw =
    dragEvent.dataTransfer?.getData(DRAG_MIME) || dragEvent.dataTransfer?.getData('text/plain') || ''
  const meta = PALETTE.find((item) => item.type === raw)
  if (!meta || !meta.p0) {
    return
  }
  const position = screenToFlowCoordinate({ x: dragEvent.clientX, y: dragEvent.clientY })
  addNode(meta.type, position)
}

/** 新增节点并选中 */
function addNode(type: WorkflowNodeType, position: { x: number; y: number }) {
  const node: Node<WorkflowFlowNodeData> = {
    id: nextNodeId(),
    type: 'workflow',
    position: { x: Math.round(position.x), y: Math.round(position.y) },
    data: {
      code: nextNodeCode(type),
      name: NODE_NAME[type] || type,
      nodeType: type,
      config: {}
    }
  }
  addNodes(node)
  selected.value = { kind: 'node', id: node.id }
}

/** 生成未占用的 n1、n2… */
function nextNodeId(): string {
  const used = new Set(vf.nodes.value.map((node) => node.id))
  let index = 1
  while (used.has(`n${index}`)) {
    index += 1
  }
  return `n${index}`
}

/** 生成未占用的节点 code（图内唯一） */
function nextNodeCode(type: WorkflowNodeType): string {
  const base = CODE_PREFIX[type] || String(type).toLowerCase()
  const used = new Set(
    vf.nodes.value.map((node) => String(((node.data || {}) as WorkflowFlowNodeData).code || ''))
  )
  if (!used.has(base)) {
    return base
  }
  let index = 2
  while (used.has(`${base}_${index}`)) {
    index += 1
  }
  return `${base}_${index}`
}

/** 生成未占用的 e1、e2… */
function nextEdgeId(): string {
  const used = new Set(vf.edges.value.map((edge) => edge.id))
  let index = 1
  while (used.has(`e${index}`)) {
    index += 1
  }
  return `e${index}`
}

/** 连线：自环与重复连线在画布层先挡一层，最终仍由后端校验兜底 */
function onConnect(connection: Connection) {
  if (!canEdit.value) {
    return
  }
  const source = connection.source
  const target = connection.target
  if (!source || !target) {
    return
  }
  if (source === target) {
    ElMessage.warning('P0 禁止自环，请连接到其它节点')
    return
  }
  const duplicated = vf.edges.value.some((edge) => edge.source === source && edge.target === target)
  if (duplicated) {
    ElMessage.warning('这两个节点之间已存在连线')
    return
  }
  addEdges({
    id: nextEdgeId(),
    source,
    target,
    type: 'default',
    markerEnd: MarkerType.ArrowClosed,
    data: { edgeType: 'NORMAL', label: null, config: {} }
  })
}

/** 选中节点 */
function onNodeClick({ node }: NodeMouseEvent) {
  selected.value = { kind: 'node', id: node.id }
}

/** 选中连线 */
function onEdgeClick({ edge }: EdgeMouseEvent) {
  selected.value = { kind: 'edge', id: edge.id }
}

/** 点空白清空选中 */
function onPaneClick() {
  selected.value = null
}

/**
 * 画布自身异常（缺尺寸、节点类型非法等）兜底：
 * 不白屏，给出中文错误条；画布仍保留，用户可刷新重试。
 */
function onVueFlowError(payload: unknown) {
  const text = payload instanceof Error ? payload.message : String(payload ?? '')
  canvasError.value = `画布异常：${text || '请刷新页面重试'}`
}

/** 键盘删除后清空指向已删除元素的选中（VueFlow 无 delete 事件，用列表长度守卫） */
watch(
  [() => vf.nodes.value.length, () => vf.edges.value.length],
  () => {
    const sel = selected.value
    if (!sel) {
      return
    }
    const exists =
      sel.kind === 'node'
        ? vf.nodes.value.some((node) => node.id === sel.id)
        : vf.edges.value.some((edge) => edge.id === sel.id)
    if (!exists) {
      selected.value = null
    }
  }
)

/** 条件节点表达式写回 config */
function setCondition(value: unknown) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  node.data = { ...node.data, config: { ...node.data.config, condition: value } }
}

/** 入参映射写回 config */
function setInputMapping(value: Record<string, string>) {
  setMapping('inputMapping', value)
}

/** 出参映射写回 config */
function setOutputMapping(value: Record<string, string>) {
  setMapping('outputMapping', value)
}

/** 映射写回 config */
function setMapping(field: 'inputMapping' | 'outputMapping', value: Record<string, string>) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  node.data = { ...node.data, config: { ...node.data.config, [field]: value } }
}

/** 改连线类型：同步 data.edgeType 与画布标签 */
function setEdgeType(value: WorkflowEdgeType) {
  const edge = selectedEdge.value
  if (!edge) {
    return
  }
  const data = (edge.data || { edgeType: 'NORMAL' }) as WorkflowFlowEdgeData
  const nextData: WorkflowFlowEdgeData = { ...data, edgeType: value }
  edge.data = nextData
  edge.label = labelText(value, nextData.label ?? null)
}

/** 改自定义标签；保留原始输入（不去空格），清空即回退为按类型显示 */
function setEdgeLabel(value: string) {
  const edge = selectedEdge.value
  if (!edge) {
    return
  }
  const data = (edge.data || { edgeType: 'NORMAL' }) as WorkflowFlowEdgeData
  const label = value === '' ? null : value
  edge.data = { ...data, label }
  edge.label = labelText(String(data.edgeType || 'NORMAL'), label)
}

/** 条件边的 JSONLogic：同时写 data.condition（画布绑定）与 config.condition（落库结构） */
function setEdgeCondition(value: unknown) {
  const edge = selectedEdge.value
  if (!edge) {
    return
  }
  const data = (edge.data || { edgeType: 'NORMAL' }) as WorkflowFlowEdgeData
  edge.data = {
    ...data,
    condition: value,
    config: { ...(data.config ?? {}), condition: value }
  }
}

/** 删除当前选中的节点（连带连线）或连线 */
function deleteSelected() {
  const sel = selected.value
  if (!sel) {
    return
  }
  if (sel.kind === 'node') {
    removeNodes([sel.id], true)
  } else {
    removeEdges([sel.id])
  }
  selected.value = null
}

/** 重新加载最新草稿；有未保存修改时先确认（用于 409 冲突后恢复） */
async function reloadDraft() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('重新加载会丢弃当前未保存修改，确定继续？', '重新加载草稿', {
        confirmButtonText: '重新加载',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
  }
  canvasError.value = ''
  await loadAll()
}

/** 返回列表；有未保存修改时先确认 */
async function goList() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前草稿有未保存修改，确定返回列表？', '提示', {
        confirmButtonText: '返回列表',
        cancelButtonText: '继续编辑',
        type: 'warning'
      })
    } catch {
      return
    }
  }
  void router.push('/orchestration/workflow')
}

// 同一页面切换到另一条工作流（页签复用一个组件）时重新载入
watch(workflowId, (next, prev) => {
  if (next && next !== prev) {
    void loadAll()
  }
})

onMounted(async () => {
  await Promise.all([loadAll(), loadAgentOptions(), loadToolOptions()])
})

/**
 * KeepAlive 页签切回时：
 * 未改动则静默刷新（拿最新 updateTime，避免拿旧时间撞 409）；
 * 已有未保存改动则保留画布，不覆盖用户输入。
 */
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  if (workflowId.value && !dirty.value) {
    void loadAll()
  }
})
</script>

<style scoped>
.wf-page {
  display: flex;
  flex-direction: column;
  /* 顶栏 + 页签 + 主区留白之外的可视高度；画布区自行滚动 */
  height: calc(100vh - 200px);
  min-height: 520px;
}

.block-alert {
  margin-bottom: 10px;
}

/* 校验问题条：横向滚动，不挤占画布高度 */
.wf-issues {
  flex: none;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 10px;
  background: #fff;
}

.wf-issues__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.wf-issues__stale {
  color: #b45309;
}

.wf-issues__list {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  padding-bottom: 2px;
  overflow-x: auto;
}

.wf-issue {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  max-width: 420px;
  padding: 4px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
}

.wf-issue.is-error {
  border-color: #fecaca;
  background: #fef2f2;
  color: #b91c1c;
}

.wf-issue.is-warn {
  border-color: #fde68a;
  background: #fffbeb;
  color: #b45309;
}

.wf-issue__code {
  color: inherit;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  opacity: 0.75;
}

.wf-issue__msg {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-body {
  display: flex;
  flex: 1;
  gap: 12px;
  min-height: 0;
}

.wf-palette {
  flex: 0 0 200px;
  padding: 12px;
  overflow-y: auto;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 12px;
  background: #fff;
}

.wf-panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  color: var(--ah-text-primary, #111827);
  font-size: 13px;
  font-weight: 600;
}

.wf-palette__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-palette__item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 7px 9px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafafa;
  font-size: 12px;
  cursor: grab;
  user-select: none;
}

.wf-palette__item:hover {
  border-color: #c7c3ff;
  background: #f5f4ff;
}

.wf-palette__item.is-p1 {
  border-style: dashed;
  color: #9ca3af;
  cursor: not-allowed;
}

.wf-palette__label {
  color: var(--ah-text-primary, #111827);
  font-weight: 600;
}

.wf-palette__item.is-p1 .wf-palette__label {
  color: #9ca3af;
}

.wf-palette__p1 {
  padding: 0 4px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  font-size: 10px;
  line-height: 14px;
}

.wf-palette__hint {
  flex: 1 0 100%;
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.4;
}

.wf-canvas {
  position: relative;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 12px;
  background: #fbfbfd;
}

.canvas-alert {
  position: absolute;
  top: 8px;
  left: 8px;
  right: 8px;
  z-index: 5;
}

.wf-props {
  flex: 0 0 300px;
  padding: 12px;
  overflow-y: auto;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 12px;
  background: #fff;
}

.wf-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.wf-form :deep(.el-form-item__label) {
  padding-bottom: 3px;
  font-size: 12px;
  line-height: 1.4;
}

.wf-field {
  width: 100%;
}

.wf-tip {
  margin: -4px 0 10px;
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.5;
}

.wf-tip--error {
  color: #dc2626;
}

.wf-delete {
  width: 100%;
}

.wf-props__empty {
  padding: 18px 6px;
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.7;
  text-align: center;
}

.wf-props__empty-hint {
  font-size: 11px;
}

.wf-footer {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  padding: 8px 12px;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 10px;
  background: #fff;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
}

.wf-footer__right {
  flex: none;
}

.wf-footer__dirty {
  color: #b45309;
}

.wf-footer .is-error {
  color: #dc2626;
}

.wf-footer .is-warn {
  color: #b45309;
}

/* VueFlow 铺满容器 */
.wf-canvas :deep(.vue-flow) {
  width: 100%;
  height: 100%;
}
</style>
