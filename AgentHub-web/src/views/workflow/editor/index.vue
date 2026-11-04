<template>
  <!-- is-focus：专注模式，画布吃掉页签条与主区留白后的全部高度 -->
  <div class="wf-page" :class="{ 'is-focus': focusMode }">
    <!-- 顶栏：保存 / 校验 / 发布；试跑入口已移到画布底部悬浮工具栏 -->
    <PageHeader :title="def?.name || '工作流编辑'" :description="headerDesc">
      <template #extra>
        <el-button @click="goList">返回列表</el-button>
        <el-button v-if="canEdit" link type="primary" @click="reloadDraft">重新加载</el-button>
        <el-button v-if="canEdit" :loading="saving" :disabled="!dirty" @click="onSaveDraft()">
          保存草稿
        </el-button>
        <el-button :loading="validating" @click="onValidate">校验</el-button>
        <!-- 专注模式：收起左右面板 + 隐藏全局侧导航；ESC 或再次点击退出 -->
        <el-button :type="focusMode ? 'primary' : 'default'" @click="toggleFocusMode">
          <el-icon class="btn-icon"><FullScreen /></el-icon>
          {{ focusMode ? '退出专注' : '专注模式' }}
        </el-button>
        <el-button
          v-if="canPublish"
          type="primary"
          :loading="publishing"
          :disabled="!workflowId"
          @click="onPublish"
        >
          发布
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
      <!-- 专注模式：贴边 hover 临时唤出面板，移开自动收起；非专注模式不启用，避免误触 -->
      <template v-if="focusMode">
        <div
          v-if="paletteHidden"
          class="wf-edge-hot is-left"
          @mouseenter="peekPanel('left')"
          @mouseleave="scheduleUnpeek('left')"
        />
        <div
          v-if="propsHidden"
          class="wf-edge-hot is-right"
          @mouseenter="peekPanel('right')"
          @mouseleave="scheduleUnpeek('right')"
        />
      </template>

      <!-- 左：节点面板可折叠；折叠后画布吃满剩余宽度 -->
      <aside
        class="wf-palette wf-panel"
        :class="{ 'is-collapsed': paletteHidden }"
        @mouseenter="peekPanel('left')"
        @mouseleave="scheduleUnpeek('left')"
      >
        <!-- 内层定宽：宽度动画期间文字不重排 -->
        <div class="wf-panel__inner">
          <div class="wf-panel__head">
            <h3 class="wf-panel__title">节点</h3>
            <el-tooltip content="收起节点库" placement="right">
              <button
                type="button"
                class="wf-panel__fold"
                aria-label="收起节点库"
                @click="collapsePanel('left')"
              >
                <el-icon><ArrowLeft /></el-icon>
              </button>
            </el-tooltip>
          </div>
          <div class="wf-palette__list">
            <div
              v-for="item in PALETTE"
              :key="item.type"
              class="wf-palette__item"
              :class="{ 'is-p1': !item.p0 }"
              :draggable="item.p0"
              :title="item.p0 ? `拖到画布添加「${item.label}」` : 'P1：本刀不实现'"
              @dragstart="onDragStart(item, $event)"
              @dragend="onPaletteDragEnd"
            >
              <span class="wf-palette__label">{{ item.label }}</span>
              <span v-if="!item.p0" class="wf-palette__p1">P1</span>
              <span class="wf-palette__hint">{{ item.hint }}</span>
            </div>
          </div>
        </div>
      </aside>

      <!-- 折叠后左边缘的唤醒页签：hover 展开并浮出文字，避免用户找不到面板去哪了 -->
      <Transition name="wf-wake">
        <button
          v-if="paletteHidden"
          type="button"
          class="wf-edge-wake is-left"
          aria-label="展开节点库"
          @click="expandPanel('left')"
        >
          <el-icon class="wf-edge-wake__icon"><ArrowRight /></el-icon>
          <span class="wf-edge-wake__label">展开节点库</span>
        </button>
      </Transition>

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
          :zoom-on-scroll="true"
          :zoom-on-pinch="true"
          :zoom-on-double-click="false"
          :pan-on-scroll="false"
          :pan-on-drag="true"
          pan-activation-key-code="Space"
          @connect="onConnect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="onPaneClick"
          @error="onVueFlowError"
        >
          <Background :gap="16" color="#e5e7eb" />
        </VueFlow>

        <!-- 底部悬浮胶囊：缩放 / 自适应 / 试跑；空白处穿透，不挡画布拖拽 -->
        <!-- keydown.space.prevent：避免焦点停在按钮上时按空格误触发按钮，把空格让给画布平移 -->
        <div class="wf-dock" @pointerdown.stop @keydown.space.prevent>
          <el-tooltip content="缩小" placement="top">
            <button type="button" class="wf-dock__btn" aria-label="缩小" @click="zoomOutCanvas">
              <el-icon><Minus /></el-icon>
            </button>
          </el-tooltip>
          <span class="wf-dock__zoom" title="当前缩放比例">{{ zoomPercent }}%</span>
          <el-tooltip content="放大" placement="top">
            <button type="button" class="wf-dock__btn" aria-label="放大" @click="zoomInCanvas">
              <el-icon><Plus /></el-icon>
            </button>
          </el-tooltip>
          <span class="wf-dock__divider" aria-hidden="true" />
          <el-tooltip content="自适应画布" placement="top">
            <button
              type="button"
              class="wf-dock__btn"
              aria-label="自适应画布"
              @click="fitCanvas"
            >
              <el-icon><FullScreen /></el-icon>
            </button>
          </el-tooltip>
          <button
            v-if="canRunTrial"
            type="button"
            class="wf-dock__run"
            :disabled="!workflowId"
            @click="trialVisible = true"
          >
            试跑
          </button>
        </div>
      </div>

      <!-- 右：属性面板可折叠；标题随选中态切换，折叠按钮固定在标题行右侧 -->
      <aside
        class="wf-props wf-panel"
        :class="{ 'is-collapsed': propsHidden }"
        @mouseenter="peekPanel('right')"
        @mouseleave="scheduleUnpeek('right')"
      >
        <div class="wf-panel__inner">
          <div class="wf-panel__head">
            <h3 class="wf-panel__title">
              <template v-if="selectedNode">
                节点属性
                <el-tag size="small" effect="plain">{{ nodeTypeLabel(selectedNode.data.nodeType) }}</el-tag>
              </template>
              <template v-else-if="selectedEdge">
                连线属性
                <el-tag size="small" effect="plain">{{ edgeTypeLabel(selectedEdge.data.edgeType) }}</el-tag>
              </template>
              <template v-else>属性</template>
            </h3>
            <el-tooltip content="收起属性面板" placement="left">
              <button
                type="button"
                class="wf-panel__fold"
                aria-label="收起属性面板"
                @click="collapsePanel('right')"
              >
                <el-icon><ArrowRight /></el-icon>
              </button>
            </el-tooltip>
          </div>
        <template v-if="selectedNode">
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
                  :model-value="selectedNode.data.config.agentId"
                  class="wf-field"
                  clearable
                  filterable
                  placeholder="选择 Agent（不含 ROUTER）"
                  :disabled="!canEdit"
                  @update:model-value="setNodeAgent"
                >
                  <el-option
                    v-for="item in agentOptions"
                    :key="item.id"
                    :label="`${item.name}（${item.code}）`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <!-- 画布节点标题会优先显示绑定 Agent 名称；这里说明回退规则，避免用户以为「显示名」失效 -->
              <div class="wf-tip">
                画布上优先显示绑定 Agent 的名称；未绑定或 Agent 已删除时回退显示「显示名」。
              </div>
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
                  :model-value="selectedNode.data.config.toolId"
                  class="wf-field"
                  clearable
                  filterable
                  placeholder="选择工具项"
                  :disabled="!canEdit"
                  @update:model-value="setNodeTool"
                >
                  <el-option
                    v-for="item in toolOptions"
                    :key="item.id"
                    :label="toolLabel(item)"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <!-- 画布节点标题会优先显示工具显示名；这里说明回退规则 -->
              <div class="wf-tip">
                画布上优先显示工具的显示名；未绑定或工具已删除时回退显示「显示名」。
              </div>
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

            <!-- 入参映射：可视化「来源节点 → 输出字段」，落库仍是 $.input / $.outputs / $.vars 路径 -->
            <template v-if="showMapping">
              <el-form-item label="入参映射">
                <InputMappingEditor
                  :model-value="selectedNode.data.config.inputMapping"
                  :sources="mappingSources"
                  :start-codes="startCodes"
                  :disabled="!canEdit"
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
        <!-- /.wf-panel__inner：折叠动画靠它保持定宽，内容不重排 -->
        </div>
      </aside>

      <!-- 折叠后右边缘的唤醒页签：与左侧对称 -->
      <Transition name="wf-wake">
        <button
          v-if="propsHidden"
          type="button"
          class="wf-edge-wake is-right"
          aria-label="展开属性面板"
          @click="expandPanel('right')"
        >
          <span class="wf-edge-wake__label">展开属性面板</span>
          <el-icon class="wf-edge-wake__icon"><ArrowLeft /></el-icon>
        </button>
      </Transition>
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
import { computed, markRaw, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, FullScreen, Minus, Plus } from '@element-plus/icons-vue'
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
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import PageHeader from '@/components/PageHeader.vue'
import { useWorkflowFocus } from '@/composables/workflowFocus'
import InputMappingEditor from './InputMappingEditor.vue'
import MappingEditor from './MappingEditor.vue'
import JsonLogicEditor from './JsonLogicEditor.vue'
import WorkflowNodeCard from './WorkflowNodeCard.vue'
import WorkflowTrialDrawer from './WorkflowTrialDrawer.vue'
import { pageAgents, getAgent } from '@/api/agent'
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
  WorkflowMappingSource,
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
const {
  addNodes,
  addEdges,
  removeNodes,
  removeEdges,
  findNode,
  screenToFlowCoordinate,
  getViewport,
  fitView,
  setCenter,
  zoomIn,
  zoomOut,
  viewport
} = vf

/** 专注模式跨壳层开关；本页只负责进入 / 退出与失活复位 */
const { setFocus: setWorkflowFocus } = useWorkflowFocus()

/** 下拉第一页以外的 Agent 名称缓存（id → 名称 / 编码），只服务画布标题展示 */
const agentLabelCache = new Map<number, { name: string; code: string }>()
/** 详情接口也解析不到的 id（已删除 / 无权限），避免反复请求 */
const agentLabelMissing = new Set<number>()
/** 详情补查进行中标记：防止回填触发递归请求 */
let agentLabelResolving = false
/** Agent 下拉是否已加载完（成功或失败）：未完成前不补查详情，避免多余请求 */
let agentOptionsReady = false

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

/* ===== 模块一 / 二：布局折叠与悬浮工具栏（纯 UI 状态，不参与 graph_json） ===== */
/** 左「节点库」是否折叠（用户意图，不含 hover 临时展开） */
const paletteCollapsed = ref(false)
/** 右「节点属性」是否折叠（用户意图，不含 hover 临时展开） */
const propsCollapsed = ref(false)
/** 专注模式：同时收起左右面板，并通知壳层隐藏全局侧导航 */
const focusMode = ref(false)
/** 进入专注前的面板状态快照，退出时原样恢复 */
const panelStateBeforeFocus = ref<{ palette: boolean; props: boolean } | null>(null)
/** 当前缩放百分比，供悬浮工具栏展示 */
const zoomPercent = computed(() => Math.round((viewport.value?.zoom ?? 1) * 100))

/** 侧边面板标识 */
type PanelSide = 'left' | 'right'
/** 贴边 hover 临时展开的侧别；非空时该侧即使「已折叠」也显示 */
const peekSide = ref<PanelSide | null>(null)
/**
 * 选中节点 / 连线时临时唤出右侧属性面板。
 * <p>为什么独立于 peekSide：hover 唤出会随鼠标移开被 240ms 定时器收回，
 * 而「正在配参数」必须保持可见；这个标记只由选中态驱动，取消选中才清。
 * <p>只在专注模式用：普通模式下折叠面板被选中直接常驻展开（见 onNodeClick）。
 */
const propsPeekForSelection = ref(false)
/** 收起临时面板的延时句柄，供鼠标跨越缝隙时取消 */
let unpeekTimer: number | undefined
/** 左面板当前是否不可见：折叠意图 + hover 临时展开共同决定 */
const paletteHidden = computed(() => paletteCollapsed.value && peekSide.value !== 'left')
/** 右面板当前是否不可见：折叠意图 + hover 临时展开 + 选中节点临时唤出共同决定 */
const propsHidden = computed(
  () => propsCollapsed.value && peekSide.value !== 'right' && !propsPeekForSelection.value
)
/** 正在从节点库拖拽：拖拽期间不自动收起，否则拖到一半面板消失 */
const paletteDragActive = ref(false)

/** 取消待执行的收起动作 */
function cancelUnpeek() {
  if (unpeekTimer !== undefined) {
    window.clearTimeout(unpeekTimer)
    unpeekTimer = undefined
  }
}

/**
 * 贴边 / 面板 hover：临时唤出该侧面板（仅专注模式启用，避免日常编辑误触）。
 *
 * @param side 侧别
 */
function peekPanel(side: PanelSide) {
  if (!focusMode.value) {
    return
  }
  cancelUnpeek()
  peekSide.value = side
}

/**
 * 延迟收起 hover 唤出的面板。
 * <p>留 240ms 缓冲，让鼠标从贴边热区跨进面板本体时不被判成「已移开」。
 *
 * @param side 侧别
 */
function scheduleUnpeek(side: PanelSide) {
  if (!focusMode.value || paletteDragActive.value) {
    return
  }
  cancelUnpeek()
  unpeekTimer = window.setTimeout(() => {
    unpeekTimer = undefined
    if (peekSide.value === side) {
      peekSide.value = null
    }
  }, 240)
}

/**
 * 点击唤醒页签：把「用户意图」改成展开。
 * <p>不用直接改 peekSide：点了就是明确要常驻，动效交给面板自身的宽度过渡。
 *
 * @param side 侧别
 */
function expandPanel(side: PanelSide) {
  cancelUnpeek()
  peekSide.value = null
  if (side === 'left') {
    paletteCollapsed.value = false
  } else {
    propsCollapsed.value = false
    // 常驻展开后不再需要「选中临时唤出」，避免两个标记互相覆盖
    propsPeekForSelection.value = false
  }
}

/**
 * 收起某一侧面板（用户点折叠按钮）。
 * <p>右侧要同时清掉「选中临时唤出」：显式收起优先级最高，
 * 否则选中节点期间点折叠按钮看起来没反应。
 *
 * @param side 侧别
 */
function collapsePanel(side: PanelSide) {
  cancelUnpeek()
  if (side === 'left') {
    paletteCollapsed.value = true
    return
  }
  propsCollapsed.value = true
  propsPeekForSelection.value = false
}

/**
 * 复位左右面板为「默认全展开」。
 * <p>本页被 KeepAlive 缓存，折叠是纯 UI 状态、不该跨路由残留：
 * 首次挂载与每次页签切回都重置，保证「刚进入页面」就是完整初始视野。
 */
function resetPanelLayout() {
  cancelUnpeek()
  peekSide.value = null
  propsPeekForSelection.value = false
  paletteCollapsed.value = false
  propsCollapsed.value = false
}

/**
 * 选中节点 / 连线后确保属性面板可见。
 * <p>普通模式：折叠意图直接改为展开（用户接下来必然要配参数，常驻更稳）；
 * 专注模式：只临时唤出，取消选中即收回画布空间（专注模式的核心承诺是全画布）。
 *
 * @param hasSelection 当前是否选中了节点或连线
 */
function revealPropsForSelection(hasSelection: boolean) {
  if (!hasSelection) {
    propsPeekForSelection.value = false
    return
  }
  if (focusMode.value) {
    // 清掉 hover 状态，改由选中标记驱动，避免鼠标移开被定时器收回
    cancelUnpeek()
    peekSide.value = null
    propsPeekForSelection.value = true
    return
  }
  if (propsCollapsed.value) {
    expandPanel('right')
  }
}

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

/** 图内所有 START 节点编码：入参映射把 $.outputs.&lt;start&gt; 归一成 $.input，需要这份名单 */
const startCodes = computed(() =>
  vf.nodes.value
    .map((node) => (node.data || {}) as WorkflowFlowNodeData)
    .filter((data) => String(data.nodeType) === 'START')
    .map((data) => String(data.code ?? ''))
    .filter(Boolean)
)

/**
 * 入参映射的「来源节点」候选：当前节点的所有前置节点（多跳祖先）。
 * <p>START 已排除：它的输出等价于运行入参，在编辑器里由固定的「运行入参（$.input）」选项表示，
 * 避免同一份取值出现两个来源选项。
 */
const mappingSources = computed<WorkflowMappingSource[]>(() => {
  const node = selectedNode.value
  if (!node) {
    return []
  }
  return collectAncestors(node.id)
    .filter((item) => String(item.data?.nodeType) !== 'START')
    .map((item) => {
      const data = (item.data || {}) as WorkflowFlowNodeData
      const code = String(data.code ?? '')
      const name = String(data.name ?? '')
      const outputMapping = (data.config?.outputMapping ?? {}) as Record<string, string>
      return {
        id: item.id,
        code,
        name,
        label: `${nodeTypeLabel(String(data.nodeType))} · ${name || code}（${code}）`,
        // 有出参映射时，真实产出键就是这些；没有映射时后端只保留 {text}
        outputKeys: Object.keys(outputMapping)
      }
    })
    .filter((item) => item.code)
})

/**
 * 反向广度优先收集前置节点（多跳祖先）。
 * <p>不区分边类型：ERROR 分支同样会产出输出，理应可被下游引用。
 * <p>已访问集合同时充当环保护：画布允许画出环，成环由校验器拦截，这里不能死循环。
 *
 * @param nodeId 起点节点 id（结果不含自身）
 * @return 祖先节点，按距离由近到远
 */
function collectAncestors(nodeId: string): Node<WorkflowFlowNodeData>[] {
  // 反邻接表：目标节点 → 直接前驱节点 id 列表
  const incoming = new Map<string, string[]>()
  vf.edges.value.forEach((edge) => {
    const source = String(edge.source ?? '')
    const target = String(edge.target ?? '')
    if (!source || !target) {
      return
    }
    const list = incoming.get(target)
    if (list) {
      list.push(source)
    } else {
      incoming.set(target, [source])
    }
  })
  const visited = new Set<string>([nodeId])
  const queue: string[] = [nodeId]
  const result: Node<WorkflowFlowNodeData>[] = []
  while (queue.length) {
    const current = queue.shift() as string
    for (const source of incoming.get(current) ?? []) {
      // 已访问直接跳过：既去重也防环
      if (visited.has(source)) {
        continue
      }
      visited.add(source)
      queue.push(source)
      const found = vf.nodes.value.find((item) => item.id === source)
      if (found) {
        result.push(found as Node<WorkflowFlowNodeData>)
      }
    }
  }
  return result
}

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

/**
 * 切换专注模式。
 * <p>进入：先记下左右面板当前状态，再统一收起并通知壳层隐藏全局导航；
 * 退出：按快照恢复面板，避免用户进专注前专门展开的面板被永久收掉。
 */
function toggleFocusMode() {
  if (focusMode.value) {
    exitFocusMode()
    return
  }
  panelStateBeforeFocus.value = { palette: paletteCollapsed.value, props: propsCollapsed.value }
  paletteCollapsed.value = true
  propsCollapsed.value = true
  // 进专注先给全画布；属性面板由「再次选中节点」按需临时唤出，避免一进来就被面板吃掉宽度
  propsPeekForSelection.value = false
  focusMode.value = true
  setWorkflowFocus(true)
}

/** 退出专注模式：恢复进入前的面板状态，并解除壳层隐藏 */
function exitFocusMode() {
  focusMode.value = false
  setWorkflowFocus(false)
  // hover 临时展开不属于持久状态，一并清掉，避免退出后残留半开面板
  cancelUnpeek()
  peekSide.value = null
  // 选中临时唤出同样不是持久状态：退出专注后按快照恢复折叠意图
  propsPeekForSelection.value = false
  const before = panelStateBeforeFocus.value
  if (before) {
    paletteCollapsed.value = before.palette
    propsCollapsed.value = before.props
    panelStateBeforeFocus.value = null
  }
}

/**
 * 全局 ESC：专注模式下退出。
 * <p>弹窗 / 抽屉打开时不抢占 ESC，避免一次按键同时关弹窗和退专注。
 */
function onFocusKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !focusMode.value) {
    return
  }
  if (document.querySelector('.el-overlay')) {
    return
  }
  exitFocusMode()
}

/** 悬浮工具栏放大；以画布中心为锚点 */
function zoomInCanvas() {
  void zoomIn()
}

/** 悬浮工具栏缩小；以画布中心为锚点 */
function zoomOutCanvas() {
  void zoomOut()
}

/** 悬浮工具栏自适应：与首屏载入使用同一套 padding / maxZoom */
function fitCanvas() {
  void fitView({ padding: 0.2, maxZoom: 1.1, duration: 250 }).catch(() => false)
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
    // Agent / 工具选项可能已先加载完成：铺完节点立刻补一次名称，避免首屏闪「Agent #30」
    syncAgentLabels()
    syncToolLabels()
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
  // 标记下拉已就绪：此后回填节点标题时，第一页没覆盖到的 id 才会去补查详情
  agentOptionsReady = true
  // 选项到位 / 失败后统一回填节点标题：失败时清空名称，回退显示节点「显示名」
  syncAgentLabels()
}

/** 工具下拉：含停用项，由文案标注 */
async function loadToolOptions() {
  try {
    toolOptions.value = await listAllItems()
  } catch {
    toolOptions.value = []
    toolLoadError.value = '工具列表加载失败，暂时无法绑定工具；请刷新页面重试'
  }
  // 选项到位 / 失败后统一回填节点标题：失败时清空名称，回退显示节点「显示名」
  syncToolLabels()
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
  // 拖拽期间锁定面板：hover 收起逻辑要跳过，否则拖到一半节点库自己缩回去
  paletteDragActive.value = true
  dragEvent.dataTransfer?.setData(DRAG_MIME, item.type)
  dragEvent.dataTransfer?.setData('text/plain', item.type)
  if (dragEvent.dataTransfer) {
    dragEvent.dataTransfer.effectAllowed = 'move'
  }
}

/**
 * 拖拽结束（含中途取消）：解锁 hover 收起。
 * <p>必须挂在 dragend 上：用户把节点拖到画布外松手时不会触发 drop，只会有 dragend。
 */
function onPaletteDragEnd() {
  paletteDragActive.value = false
}

/** 落到画布：屏幕坐标转流坐标后新增节点 */
function onDrop(event: Event) {
  const dragEvent = event as DragEvent
  // 不论后面是否成功新增，落点即代表本次拖拽结束，先解锁面板的 hover 收起
  paletteDragActive.value = false
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
  // 选中即要配参数：折叠状态下强制把右侧属性面板拉出来（专注模式为临时唤出）
  revealPropsForSelection(true)
}

/** 选中连线 */
function onEdgeClick({ edge }: EdgeMouseEvent) {
  selected.value = { kind: 'edge', id: edge.id }
  // 连线属性同样在右侧面板编辑，联动规则与节点一致
  revealPropsForSelection(true)
}

/** 点空白清空选中 */
function onPaneClick() {
  selected.value = null
  // 专注模式下取消选中就收回临时唤出的属性面板，恢复全画布视野
  revealPropsForSelection(false)
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
      // 关联元素被删掉后，选中临时唤出的面板不应继续挂着
      revealPropsForSelection(false)
    }
  }
)

/**
 * 绑定 / 解绑工具。
 * <p>除 toolId 外同步写入展示用名称：画布节点标题要直接显示工具名，
 * 但这两个字段只存在于画布 data，buildGraph 不会落库。
 *
 * @param value 下拉选中的 toolId；清空时为 undefined / 空串
 */
function setNodeTool(value: number | string | undefined) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const toolId = value === undefined || value === null || value === '' ? undefined : Number(value)
  const tool = toolOptions.value.find((item) => item.id === toolId)
  node.data = {
    ...node.data,
    config: { ...node.data.config, toolId },
    toolTitle: tool?.title ?? '',
    toolName: tool?.name ?? ''
  }
}

/**
 * 把 toolId 对应的工具名称回填到所有 TOOL 节点。
 * <p>工具下拉与草稿并行加载，草稿先到时名称还解析不出来；
 * 这里在两边任一就绪后统一补齐，同值短路避免无意义重渲染。
 */
function syncToolLabels() {
  const byId = new Map(toolOptions.value.map((item) => [item.id, item]))
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    if (String(data.nodeType) !== 'TOOL') {
      return
    }
    const tool = byId.get(Number(data.config?.toolId))
    const toolTitle = tool?.title ?? ''
    const toolName = tool?.name ?? ''
    if (data.toolTitle === toolTitle && data.toolName === toolName) {
      return
    }
    node.data = { ...data, toolTitle, toolName }
  })
}

/** 条件节点表达式写回 config */
function setCondition(value: unknown) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  node.data = { ...node.data, config: { ...node.data.config, condition: value } }
}

/**
 * 绑定 / 解绑 Agent。
 * <p>除 agentId 外同步写入展示用名称与编码：画布节点标题要直接显示 Agent 名称，
 * 但这两个字段只存在于画布 data，buildGraph 不会落库。
 *
 * @param value 下拉选中的 agentId；清空时为 undefined / 空串
 */
function setNodeAgent(value: number | string | undefined) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const agentId = value === undefined || value === null || value === '' ? undefined : Number(value)
  const agent = agentOptions.value.find((item) => item.id === agentId)
  node.data = {
    ...node.data,
    config: { ...node.data.config, agentId },
    agentName: agent?.name ?? '',
    agentCode: agent?.code ?? ''
  }
}

/**
 * 把 agentId 对应的 Agent 名称/编码回填到所有 AGENT 节点。
 * <p>Agent 下拉与草稿是并行加载的，草稿先到时名称还解析不出来；
 * 这里在两边任一就绪后统一补齐，且做同值短路，避免无意义地触发画布重渲染。
 * <p>下拉只取第一页（上限 100 条）：命中不了的 id 再走详情接口补名称，避免画布退化成「Agent #30」。
 */
function syncAgentLabels() {
  const byId = new Map(agentOptions.value.map((item) => [item.id, item]))
  const unresolved: number[] = []
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    if (String(data.nodeType) !== 'AGENT') {
      return
    }
    const agentId = Number(data.config?.agentId)
    const agent = byId.get(agentId) ?? agentLabelCache.get(agentId)
    if (!agent && Number.isFinite(agentId) && agentId > 0 && !agentLabelMissing.has(agentId)) {
      unresolved.push(agentId)
    }
    const agentName = agent?.name ?? ''
    const agentCode = agent?.code ?? ''
    if (data.agentName === agentName && data.agentCode === agentCode) {
      return
    }
    node.data = { ...data, agentName, agentCode }
  })
  // 下拉还没加载完就先不补查详情：等 loadAgentOptions 收尾后会再同步一次
  if (agentOptionsReady) {
    void resolveAgentLabels(unresolved)
  }
}/**
 * 为下拉第一页里没有的 Agent 补查名称。
 * <p>并发去重 + 失败记入 missing，保证同一 id 不会反复打接口；
 * 补全后清空缓存标记再回填一次节点标题。
 *
 * @param ids 未解析出名称的 agentId 列表
 */
async function resolveAgentLabels(ids: number[]) {
  if (agentLabelResolving) {
    return
  }
  const unique = [...new Set(ids)]
  if (!unique.length) {
    return
  }
  agentLabelResolving = true
  try {
    const results = await Promise.all(
      unique.map(async (id) => {
        try {
          const { data } = await getAgent(id)
          const detail = data.data
          return detail ? { id, name: detail.name, code: detail.code } : { id, missing: true as const }
        } catch {
          // 已删除 / 无查看权限：记下来，后续不再重复请求
          return { id, missing: true as const }
        }
      })
    )
    results.forEach((item) => {
      if ('missing' in item) {
        agentLabelMissing.add(item.id)
        return
      }
      agentLabelCache.set(item.id, { name: item.name, code: item.code })
    })
  } finally {
    agentLabelResolving = false
  }
  syncAgentLabels()
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
  window.addEventListener('keydown', onFocusKeydown)
  // 首次进入：左右面板默认全展开，给完整初始视野（不记忆上次的折叠意图）
  resetPanelLayout()
  await Promise.all([loadAll(), loadAgentOptions(), loadToolOptions()])
})

/** 组件真正销毁（关页签）时摘掉全局监听并复位壳层，防止侧栏永久隐藏 */
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onFocusKeydown)
  cancelUnpeek()
  exitFocusMode()
})

/** KeepAlive 切走时退出专注：壳层布局是全局的，不能跟着缓存页一起走 */
onDeactivated(() => {
  if (focusMode.value) {
    exitFocusMode()
  }
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
  // 页签切回等同「重新进入页面」：折叠是纯 UI 状态，复位为默认全展开
  resetPanelLayout()
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

/* 专注模式：页签条与主区留白已被壳层收起，页面直接吃满剩余高度 */
.wf-page.is-focus {
  height: calc(100vh - 72px);
  min-height: 0;
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
  position: relative;
  display: flex;
  flex: 1;
  gap: 12px;
  min-height: 0;
}

/* ===== 模块一：左右面板折叠（宽度过渡 + 内层定宽防重排） ===== */
.wf-panel {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 12px;
  background: #fff;
  transition: flex-basis 0.22s ease, width 0.22s ease, margin 0.22s ease, border-color 0.22s ease;
}

.wf-palette {
  flex: 0 0 200px;
  width: 200px;
}

/* 折叠后宽度归零；负 margin 抵消 wf-body 的 12px gap，画布真正贴边 */
.wf-palette.is-collapsed {
  flex-basis: 0;
  width: 0;
  margin-right: -12px;
  border-color: transparent;
}

.wf-panel__inner {
  box-sizing: border-box;
  height: 100%;
  padding: 12px;
  overflow-y: auto;
  transition: opacity 0.15s ease;
}

.wf-palette .wf-panel__inner {
  width: 200px;
}

.wf-panel.is-collapsed .wf-panel__inner {
  opacity: 0;
  pointer-events: none;
}

.wf-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.wf-panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  min-width: 0;
  color: var(--ah-text-primary, #111827);
  font-size: 13px;
  font-weight: 600;
}

/* 标题右侧的折叠按钮：低干扰，hover 才显形 */
.wf-panel__fold {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #9ca3af;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-panel__fold:hover {
  background: #f3f4f6;
  color: #4b5563;
}

/*
 * 折叠后贴在画布左右边缘的唤醒页签。
 * 常态只露一条窄边（22px），hover 平滑横向展开并浮出文字，
 * 让用户在专注模式下也能一眼看出「面板收去哪了、点哪里能回来」。
 */
.wf-edge-wake {
  position: absolute;
  top: 50%;
  z-index: 7;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 22px;
  height: 64px;
  padding: 0;
  overflow: hidden;
  border: 1px solid rgb(15 23 42 / 8%);
  /* 半透明浅灰底 + 轻阴影：比纯白描边更显眼，又不喧宾夺主 */
  background: rgb(243 244 246 / 92%);
  color: #4b5563;
  box-shadow: 0 2px 10px rgb(15 23 42 / 12%);
  cursor: pointer;
  white-space: nowrap;
  transform: translateY(-50%);
  transition: width 0.22s ease, background 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
}

.wf-edge-wake:hover,
.wf-edge-wake:focus-visible {
  width: 104px;
  background: #fff;
  color: #635bff;
  box-shadow: 0 6px 18px rgb(99 91 255 / 22%);
}

.wf-edge-wake:focus-visible {
  outline: 2px solid rgb(99 91 255 / 45%);
  outline-offset: 1px;
}

.wf-edge-wake__icon {
  flex: none;
  font-size: 13px;
}

/* 文字默认收起，hover 时随宽度一起浮现 */
.wf-edge-wake__label {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.wf-edge-wake:hover .wf-edge-wake__label,
.wf-edge-wake:focus-visible .wf-edge-wake__label {
  opacity: 1;
  transition-delay: 0.06s;
}

.wf-edge-wake.is-left {
  left: 0;
  border-left: 0;
  border-radius: 0 8px 8px 0;
  /* 图标贴边不居中：展开时箭头位置不动，只有文字浮现，观感更稳 */
  justify-content: flex-start;
  padding-left: 3px;
}

.wf-edge-wake.is-right {
  right: 0;
  border-right: 0;
  border-radius: 8px 0 0 8px;
  justify-content: flex-end;
  padding-right: 3px;
}

/*
 * 专注模式的贴边热区：鼠标贴到画布最左 / 最右 8px 时临时唤出面板。
 * 透明 + 无事件阻挡以外的副作用；面板展开后本元素会被 v-if 移除，不叠加遮挡。
 */
.wf-edge-hot {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 6;
  width: 8px;
}

.wf-edge-hot.is-left {
  left: 0;
}

.wf-edge-hot.is-right {
  right: 0;
}

.wf-wake-enter-active,
.wf-wake-leave-active {
  transition: opacity 0.15s ease;
}

.wf-wake-enter-from,
.wf-wake-leave-to {
  opacity: 0;
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

/* ===== 模块二：底部居中悬浮胶囊工具栏 ===== */
.wf-dock {
  position: absolute;
  bottom: 16px;
  left: 50%;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 5px 8px;
  border: 1px solid rgb(15 23 42 / 8%);
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 10px 26px rgb(15 23 42 / 14%);
  transform: translateX(-50%);
  /* 胶囊留白处穿透：节点拖到工具栏下方时仍能被抓住 */
  pointer-events: none;
  user-select: none;
}

.wf-dock > * {
  pointer-events: auto;
}

.wf-dock__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #4b5563;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-dock__btn:hover {
  background: #f3f4f6;
  color: #635bff;
}

.wf-dock__zoom {
  min-width: 42px;
  color: #6b7280;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.wf-dock__divider {
  width: 1px;
  height: 16px;
  margin: 0 4px;
  background: #e5e7eb;
}

/* 试跑是工具栏唯一主色按钮，视觉权重高于缩放 */
.wf-dock__run {
  height: 28px;
  margin-left: 4px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: #635bff;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}

.wf-dock__run:hover {
  background: #5147e6;
}

.wf-dock__run:disabled {
  background: #c7c3ff;
  cursor: not-allowed;
}

.wf-props {
  flex: 0 0 300px;
  width: 300px;
}

.wf-props.is-collapsed {
  flex-basis: 0;
  width: 0;
  margin-left: -12px;
  border-color: transparent;
}

.wf-props .wf-panel__inner {
  width: 300px;
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
