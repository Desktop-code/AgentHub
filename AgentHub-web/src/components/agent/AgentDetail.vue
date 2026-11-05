<template>
  <div class="detail-root">
  <section v-loading="loading" class="panel">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="block-alert" />
    <template v-if="form">
      <header class="detail-title">
        <div class="title-main">
          <h2 :title="heading">{{ heading }}</h2>
          <span v-if="headingCode" class="code">{{ headingCode }}</span>
        </div>
        <!-- 对话只在标题行右侧；删除只在网格卡，详情不再提供。停用时不可点 -->
        <el-button
          v-if="canTrial"
          class="talk-btn"
          type="primary"
          :disabled="form.status !== 1"
          :title="form.status !== 1 ? 'Agent 已停用，无法对话' : undefined"
          @click="onTalk"
        >
          对话
        </el-button>
      </header>
      <!--
        before-leave：切 Tab 前若基本信息 / 运行配置有未保存草稿，先确认再丢弃，
        避免用户以为已经保存、实际切走即丢稿。无脏时直接放行，不做任何拦阻。
      -->
      <el-tabs v-model="tab" class="tabs" :before-leave="beforeTabLeave">
        <el-tab-pane label="基本信息" name="basic">
          <el-form label-width="96px">
            <div class="basic-grid">
              <el-form-item label="名称" required>
                <el-input v-model="form.name" maxlength="64" :disabled="!canEdit" />
              </el-form-item>
              <el-form-item label="编码" required>
                <el-input v-model="form.code" maxlength="64" :disabled="!canEdit" />
              </el-form-item>
              <el-form-item required>
                <template #label>
                  <span class="label-with-tip">
                    运行模式
                    <el-tooltip placement="top" :show-after="200">
                      <template #content>
                        <div class="run-mode-tip">
                          <div><strong>ReAct</strong>：单 Agent 边推理边调用工具/技能，适合探索式、交互式任务。</div>
                          <div><strong>Plan-Execute</strong>：先拆成有序计划再逐步执行，复杂多步骤更可控（可人工确认计划）。</div>
                          <div><strong>Router</strong>：按意图分发给一层子 Agent，父节点只做路由，不直接跑工具链。</div>
                        </div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-radio-group
                  v-model="form.runMode"
                  class="run-mode-group"
                  :disabled="!canEdit"
                  @change="onRunModeChange"
                >
                  <el-radio-button value="REACT" class="is-react">ReAct</el-radio-button>
                  <el-radio-button value="PLAN_AND_EXECUTE" class="is-plan">Plan-Execute</el-radio-button>
                  <el-radio-button value="ROUTER" class="is-router">Router</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="状态">
                <el-switch
                  :model-value="form.status === 1"
                  :loading="statusSaving"
                  :disabled="!canEnable || statusSaving"
                  :before-change="beforeStatusChange"
                  @change="onStatusChanged"
                />
              </el-form-item>
              <!--
                允许被编排调用：关掉后其它编排根无法把本 Agent 选为候选专家。
                ROUTER 恒为关且禁用（编排根不可作子），与后端强制写 0 的口径一致。
              -->
              <el-form-item class="span-2" label="允许被编排调用">
                <div class="allow-orch">
                  <el-switch
                    :model-value="form.runMode === 'ROUTER' ? false : form.allowOrchestrated !== false"
                    :disabled="!canEdit || form.runMode === 'ROUTER'"
                    @change="onAllowOrchestratedChange"
                  />
                  <span class="allow-orch__hint">
                    {{ form.runMode === 'ROUTER'
                      ? '编排根不可作为子专家'
                      : '关闭后，其它编排根无法将本 Agent 选为候选专家' }}
                  </span>
                </div>
              </el-form-item>
              <el-form-item class="span-2" label="简介">
                <el-input v-model="form.description" type="textarea" maxlength="1000" :rows="2" :disabled="!canEdit" />
              </el-form-item>
              <el-form-item class="span-2" label="系统提示词">
                <AgentPromptEditor
                  ref="promptEditor"
                  v-model="form.systemPrompt"
                  :prompt="form.prompt"
                  :agent-name="form.name"
                  :run-mode="form.runMode"
                  :disabled="!canEdit"
                  @update:prompt="onPromptChange"
                />
              </el-form-item>
              <el-form-item class="span-2" label="备注">
                <el-input v-model="form.remark" type="textarea" maxlength="500" :rows="2" :disabled="!canEdit" />
              </el-form-item>
            </div>
          </el-form>
          <el-alert
            v-if="form.runMode !== 'ROUTER' && savedMode === 'ROUTER'"
            title="当前按所选模式隐藏「编排」。保存后已有子路由仍会保留，改回 Router 后可见。"
            type="info"
            show-icon
            :closable="false"
          />
          <el-alert
            v-else-if="form.runMode === 'ROUTER' && savedMode !== 'ROUTER'"
            title="Router 不展示工具/技能/记忆（已有绑定保留）。保存为 Router 后才能添加子 Agent。"
            type="info"
            show-icon
            :closable="false"
          />
          <p v-else-if="savedMode !== 'ROUTER' && routeCount > 0" class="keep-note">
            已保留 {{ routeCount }} 条子路由，切回 Router 后可见。
          </p>
        </el-tab-pane>

        <el-tab-pane v-if="showOrchestrationTab" label="编排" name="orchestration">
          <!--
            编排 Tab：产品化现网一层路由。候选 CRUD 走现网 /routes 接口；
            策略字段写回 form.runtimeConfig，由页面底栏统一保存。
          -->
          <AgentOrchestrationPanel
            :agent-id="agentId"
            :agent-name="form.name"
            :agent-status="form.status"
            :config="form.runtimeConfig || {}"
            :routes="routes"
            :can-edit="canEdit"
            :can-bind="canBind"
            :saved-router="savedMode === 'ROUTER'"
            :can-trial="canTrial"
            @update:config="onOrchestrationConfig"
            @routes-changed="onRoutesChanged"
            @trial="onTalk"
          />
        </el-tab-pane>

        <el-tab-pane label="模型配置" name="model">
          <div class="tab-bar">
            <div class="tab-title">
              <h3>用途槽绑定</h3>
              <!-- 降级长规则收进 Info 悬浮说明，标题行只留一句话 -->
              <el-tooltip placement="top" :show-after="120">
                <template #content>
                  <div class="rule-tip">
                    槽耗尽后：平台默认该用途 → 环境默认 →（ROUTER / REWRITE）可再降 CHAT；<br />
                    视觉 / 语音等非文本用途禁止降 CHAT。<br />
                    摘要与记忆抽取模型在「记忆配置」Tab 绑定；<br />
                    嵌入跟知识库的向量实例走，重排到「知识库」Tab 的检索链路里配，两者都不在本页。
                  </div>
                </template>
                <el-icon class="rule-icon"><InfoFilled /></el-icon>
              </el-tooltip>
            </div>
            <span class="tab-sub">槽内从上到下即降级顺序，每槽最多 3 个</span>
          </div>
          <ModelPurposeSlots
            :models="models"
            :run-mode="savedMode"
            :can-bind="canBind"
            :available-codes="availableCapabilities"
            @add="openModelAdd"
            @reorder="reorderModelSlot"
            @remove="removeModel"
            @prompt="openPurposePrompt"
          />
        </el-tab-pane>

        <el-tab-pane v-if="showReactBindTabs" label="工具配置" name="tool">
          <div class="tab-bar">
            <h3>
              已绑定工具
              <span v-if="tools.length" class="tab-count">{{ tools.length }}</span>
            </h3>
            <!-- 添加走双栏穿梭选择器（ToolBindDialog 内即 ResourceSelector）：分组 + 搜索 + 富信息 -->
            <el-button v-if="canBind" type="primary" @click="toolOpen = true">
              <el-icon class="btn-icon"><Plus /></el-icon>
              添加工具
            </el-button>
          </div>
          <el-table class="bind-table" :data="pagedTools" empty-text="还没有绑定工具" table-layout="fixed">
            <!-- 中文名加粗 + 英文调用码灰色小字：与选择器里的展示口径保持一致 -->
            <el-table-column label="工具" min-width="200" align="left" header-align="left">
              <template #default="{ row }">
                <div class="bind-name" :title="toolDisplayName(row)">{{ toolDisplayName(row) }}</div>
                <div v-if="row.name" class="bind-code" :title="row.name">{{ row.name }}</div>
              </template>
            </el-table-column>
            <el-table-column label="来源" min-width="140" align="left" header-align="left" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sourceName || '—' }}</template>
            </el-table-column>
            <!-- 工具自身启停：先说清目标能不能用，再看绑定开关 -->
            <el-table-column label="工具状态" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="targetTagType(row)" effect="plain">{{ targetTagText(row, '工具') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="绑定状态" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <el-switch
                  v-if="canBind"
                  :model-value="row.status === 1"
                  :disabled="switchDisabled(row)"
                  :title="switchTitle(row)"
                  @change="(on: string | number | boolean) => onToolSwitch(row, on === true)"
                />
                <span v-else>{{ row.status === 1 ? '启用' : '停用' }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canBind" label="操作" width="90" align="center" header-align="center">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeTool(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <!-- 表格视图用完整分页（总数 + 条/页 + 页码 + 跳转），pageSize 走 10/20/50/100 默认选项 -->
          <TablePager
            v-if="tools.length"
            v-model:current="toolPager.current"
            v-model:size="toolPager.size"
            :total="tools.length"
          />
        </el-tab-pane>

        <el-tab-pane v-if="showReactBindTabs" label="技能配置" name="skill">
          <div class="tab-bar">
            <h3>
              已绑定技能
              <span v-if="skills.length" class="tab-count">{{ skills.length }}</span>
            </h3>
            <el-button v-if="canBind" type="primary" @click="skillOpen = true">
              <el-icon class="btn-icon"><Plus /></el-icon>
              添加技能
            </el-button>
          </div>
          <el-table class="bind-table" :data="pagedSkills" empty-text="还没有绑定技能" table-layout="fixed">
            <!-- 标题加粗 + 英文编码灰字；表头也用中文，避免出现 name / title 这类库列名 -->
            <el-table-column label="技能" min-width="200" align="left" header-align="left">
              <template #default="{ row }">
                <div class="bind-name" :title="skillDisplayName(row)">{{ skillDisplayName(row) }}</div>
                <div v-if="row.name" class="bind-code" :title="row.name">{{ row.name }}</div>
              </template>
            </el-table-column>
            <!-- 生效版本：运行时实际注入的正文版本；未发布过显示「未发布」 -->
            <el-table-column label="生效版本" width="96" align="center" header-align="center">
              <template #default="{ row }">
                <el-tag v-if="row.activeVersionNo" size="small" effect="plain" type="success">
                  v{{ row.activeVersionNo }}
                </el-tag>
                <span v-else class="bind-code">未发布</span>
              </template>
            </el-table-column>
            <el-table-column label="技能状态" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="targetTagType(row)" effect="plain">{{ targetTagText(row, '技能') }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="绑定状态" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <el-switch
                  v-if="canBind"
                  :model-value="row.status === 1"
                  :disabled="switchDisabled(row)"
                  :title="switchTitle(row)"
                  @change="(on: string | number | boolean) => onSkillSwitch(row, on === true)"
                />
                <span v-else>{{ row.status === 1 ? '启用' : '停用' }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canBind" label="操作" width="90" align="center" header-align="center">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeSkill(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <!-- 表格视图用完整分页（总数 + 条/页 + 页码 + 跳转），pageSize 走 10/20/50/100 默认选项 -->
          <TablePager
            v-if="skills.length"
            v-model:current="skillPager.current"
            v-model:size="skillPager.size"
            :total="skills.length"
          />
        </el-tab-pane>

        <el-tab-pane v-if="showReactBindTabs" label="记忆配置" name="memory">
          <!--
            记忆页自身管行为字段；摘要 / 抽取模型的绑模型入口也在页内，
            但绑定数据与写路径复用本组件（同 agent_model_bind），故由这里传参并处理事件。
          -->
          <AgentMemoryPanel
            ref="memoryPanel"
            :agent-id="agentId"
            :can-edit="canEditMemory"
            :models="models"
            :can-bind="canBind"
            :summary-available="purposeAvailable('SUMMARY')"
            :memory-available="purposeAvailable('MEMORY')"
            @add-model="openModelAdd"
            @reorder-model="reorderModelSlot"
            @remove-model="removeModel"
            @prompt-model="openPurposePrompt"
          />
        </el-tab-pane>

        <!-- 知识库绑定：与工具配置互补，工具管「能不能调检索」，这里管「能查哪些库」 -->
        <el-tab-pane v-if="showReactBindTabs" label="知识库" name="kb">
          <AgentKbPanel :agent-id="agentId" />
        </el-tab-pane>

        <el-tab-pane label="运行配置" name="limits">
          <!--
            计划执行专属：仅 PLAN_AND_EXECUTE 出现，与下方通用运行限制同属本 Tab。
            落点在 runtime_config_json，与 Router 那组字段共存，切模式不会互相清空。
          -->
          <div v-if="form.runMode === 'PLAN_AND_EXECUTE'" class="plan-runtime">
            <h3 class="plan-runtime-title">Plan-Execute</h3>
            <el-form label-width="140px" class="plan-runtime-form">
              <el-form-item label="规划后需确认">
                <!-- 开关即时落库：与「多意图并行」同一条写路径，不点亮页底取消/保存 -->
                <el-switch
                  :model-value="form.runtimeConfig!.requirePlanConfirm === true"
                  :loading="runtimeSaving === 'requirePlanConfirm'"
                  :disabled="!canEdit || runtimeSaving !== ''"
                  inline-prompt
                  @change="(on: string | number | boolean) => saveRuntimeSwitch('requirePlanConfirm', on === true)"
                />
                <span class="field-hint">
                  关闭＝规划完自动执行；开启＝规划后停住，运行页点「批准」才继续，也可「取消」中止。
                </span>
              </el-form-item>
              <el-form-item label="计划步数上限">
                <el-input-number
                  v-model="form.runtimeConfig!.maxPlanSteps"
                  :min="1"
                  :max="20"
                  :step="1"
                  :disabled="!canEdit"
                />
                <span class="field-hint">限制一次计划最多拆几步，防止串行执行过长撞整次超时（系统最大 20）。</span>
              </el-form-item>
              <el-form-item label="单步失败重试">
                <el-input-number
                  v-model="form.runtimeConfig!.maxStepRetries"
                  :min="0"
                  :max="3"
                  :step="1"
                  :disabled="!canEdit"
                />
                <span class="field-hint">某步失败后重试次数；仍失败则整轮失败，并保留已完成步骤摘要（最大 3）。</span>
              </el-form-item>
            </el-form>
          </div>
          <p class="limits-hint">留空表示跟平台默认；不得超过平台硬顶。硬拦在后端。</p>
          <el-form label-width="120px" class="limits-form">
            <div v-for="group in limitGroups" :key="group.key" class="limits-group">
              <h3 class="limits-group-title">{{ group.title }}</h3>
              <div class="basic-grid limits-grid">
                <el-form-item
                  v-for="item in group.fields"
                  :key="item.key"
                  :label="item.label"
                >
                  <el-input-number
                    v-model="form[item.key]"
                    :min="item.floor"
                    :max="limitCap(item.key)"
                    :step="item.step"
                    controls-position="right"
                    :disabled="!canEdit"
                    :placeholder="limitPlaceholder(item.key)"
                    class="limit-input"
                    @change="(v: number | undefined) => onLimitChange(item.key, v)"
                  />
                </el-form-item>
              </div>
            </div>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <!--
        模型/工具/技能即时落库；基本信息 / 路由运行配置 / 运行限制走外层底栏；记忆配置底栏在面板内。
        未脏（只有开关动过，或压根没改）时两个按钮都不可点：开关是即时接口，不该让人以为还要点保存。
      -->
      <div v-if="showStickyBar" class="sticky-bar">
        <template v-if="canEdit">
          <el-button :disabled="!isDirty || saving" @click="revert">取消</el-button>
          <el-button type="primary" :disabled="!isDirty" :loading="saving" @click="save">保存</el-button>
        </template>
      </div>
    </template>
  </section>

  <ModelBindDialog
    v-model="modelOpen"
    :bound-ids="models.map((item) => item.modelInstanceId)"
    :fixed-purpose="modelAddPurpose"
    @submit="addModel"
  />
  <ToolBindDialog v-model="toolOpen" :bound-ids="tools.map((item) => item.toolItemId)" @submit="addTool" />
  <SkillBindDialog v-model="skillOpen" :bound-ids="skills.map((item) => item.skillId)" @submit="addSkill" />

  <!-- 非 CHAT 用途的 L3：落 agent_model_bind，与 CHAT 共用同一套 L3 表单 -->
  <PurposePromptDialog
    v-model="purposePromptOpen"
    :bind="purposePromptBind"
    :purpose="purposePromptPurpose"
    :agent-name="form?.name"
    :disabled="!canBind"
    @submit="savePurposePrompt"
  />
  </div>
</template>

<script setup lang="ts">
/**
 * Agent 详情表单。所属域：agent。
 * 固定 Tab：基本信息 /（编排，仅 Router）/ 模型配置 / 工具配置 / 技能配置 / 记忆配置 / 知识库 / 运行配置。
 * 基本信息与运行配置共用 Agent 保存；记忆配置在面板内取消/保存策略。模型/工具/技能即时落库。
 * 用途槽只放模型能力（生成/多模态/音频/安全）；嵌入跟知识库向量实例、重排归「知识库」Tab 的检索链路。
 * 启停走 Switch；删除只在网格卡。标题行「对话」只通知外层打开已有抽屉。
 */
import { computed, onActivated, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { InfoFilled, Plus, QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  changeAgentStatus,
  getAgent,
  getRuntimeLimits,
  createModelBind,
  createSkillBind,
  createToolBind,
  deleteModelBind,
  deleteSkillBind,
  deleteToolBind,
  listModelBinds,
  listRoutes,
  listSkillBinds,
  listToolBinds,
  updateAgent,
  updateModelBind,
  updateSkillBind,
  updateToolBind
} from '@/api/agent'
import AgentMemoryPanel from '@/components/agent/AgentMemoryPanel.vue'
import AgentKbPanel from '@/components/agent/AgentKbPanel.vue'
import AgentOrchestrationPanel from '@/components/agent/AgentOrchestrationPanel.vue'
import AgentPromptEditor from '@/components/agent/AgentPromptEditor.vue'
import ModelBindDialog from '@/components/agent/ModelBindDialog.vue'
import ModelPurposeSlots from '@/components/agent/ModelPurposeSlots.vue'
import PurposePromptDialog from '@/components/agent/PurposePromptDialog.vue'
import SkillBindDialog from '@/components/agent/SkillBindDialog.vue'
import ToolBindDialog from '@/components/agent/ToolBindDialog.vue'
import TablePager from '@/components/TablePager.vue'
import { useUserStore } from '@/stores/user'
import { pageInstances } from '@/api/model'
import type {
  AgentCardPatch,
  AgentDetail,
  AgentRouteRow,
  AgentRuntimeConfig,
  AgentSave,
  ModelBind,
  PromptBinding,
  RuntimeLimitsView,
  SkillBind,
  ToolBind
} from '@/types/agent'
import { MEMORY_SLOT_PURPOSES, normalizeCapability, PURPOSE_SLOT_GROUPS } from '@/types/model'
import { checkUserPrompt, promptAfterModeSwitch } from '@/utils/promptSafety'
import {
  normalizeChildRunModes,
  normalizeContextPolicy,
  normalizeCriticMaxRounds,
  normalizeTemplate,
  supportsVoteTemplate
} from '@/utils/orchestrationValidation'
import { useFormDirty } from '@/composables/useFormDirty'
import { registerDraft } from '@/composables/draftGuard'
import { confirmDiscardUnsaved } from '@/utils/unsavedConfirm'

/** 单条运行限制字段。占位用平台 default，max 用平台硬顶。 */
type LimitFieldDef = {
  key:
    | 'timeoutMs'
    | 'maxRounds'
    | 'consecutiveFailAbort'
    | 'llmTimeoutMs'
    | 'maxLlmCalls'
    | 'toolTimeoutMs'
    | 'toolMaxRetries'
    | 'maxToolCalls'
    | 'toolResultMaxChars'
  label: string
  floor: number
  step: number
}

/** 按类型分组：整体 / LLM / 工具，便于在「运行配置」Tab 分段排版。 */
const limitGroups: { key: string; title: string; fields: LimitFieldDef[] }[] = [
  {
    key: 'session',
    title: '整体',
    fields: [
      { key: 'timeoutMs', label: '整次超时(ms)', floor: 1000, step: 1000 },
      { key: 'maxRounds', label: '最大轮次', floor: 1, step: 1 },
      { key: 'consecutiveFailAbort', label: '连续失败中止', floor: 1, step: 1 }
    ]
  },
  {
    key: 'llm',
    title: 'LLM',
    fields: [
      { key: 'llmTimeoutMs', label: 'LLM超时(ms)', floor: 1000, step: 1000 },
      { key: 'maxLlmCalls', label: 'LLM调用上限', floor: 1, step: 1 }
    ]
  },
  {
    key: 'tool',
    title: '工具',
    fields: [
      { key: 'toolTimeoutMs', label: '工具超时(ms)', floor: 1000, step: 1000 },
      { key: 'toolMaxRetries', label: '工具重试', floor: 0, step: 1 },
      { key: 'maxToolCalls', label: '工具调用上限', floor: 1, step: 1 },
      { key: 'toolResultMaxChars', label: '工具结果截断', floor: 256, step: 256 }
    ]
  }
]

/** 扁平字段列表，校验与遍历用。 */
const limitFields = limitGroups.flatMap((g) => g.fields)

/**
 * 组件入参。
 * <p>
 * `autoLoad` 用运行时声明而非纯类型：语义是「默认 true」，而 Vue 3 对 `type: Boolean`
 * 的 prop 在父级未传时会强转成 `false`，纯类型声明拿不到默认值，会让「不传」变成「不加载」。
 * 这里显式 `default: true`，与文档语义一致。
 */
const props = defineProps({
  agentId: { type: Number, required: true },
  /**
   * 是否在挂载时立即拉取详情，默认 true。
   * Agent 列表页在飞卡期间用骨架顶替、落地后才挂载本组件，
   * 因此正常情况下用不到 false；保留此开关作为防御：
   * 若将来需要在动画帧内先挂载外壳，可传 false 后再手动触发 load。
   */
  autoLoad: { type: Boolean, default: true },
  /**
   * 打开详情时默认停留的 Tab，可空。
   * 目前只由「编排 → 多智能体」列表传 'orchestration'（约定 query.tab=orchestration）；
   * 其它入口不传，仍落「基本信息」。非法值/不可见 Tab 会回落到基本信息。
   */
  initialTab: { type: String, default: '' }
})

const emit = defineEmits<{
  /**
   * 详情数据发生变更。
   * 携带「变更后的卡片字段」：外层据此原地修补左栏行，
   * 避免为了刷新一个名称就把左栏分页重置、滚回顶部。
   * 增删绑定等不影响卡片外观的操作也会触发，载荷是当前快照（幂等）。
   */
  changed: [patch?: AgentCardPatch]
  /** 外层打开试跑抽屉。名称和模式用已落库的值，避免未保存草稿进标题。 */
  trial: [payload: { agentId: number; name: string; runMode: string }]
}>()

/**
 * 用当前表单值组装左栏卡片补丁。
 * 必须取 form 而非 saved：save() / onStatusChanged() 都在刷新前就 emit，
 * 此刻 saved 还是旧快照，用它会导致补丁值滞后。
 * form 为空（详情尚未加载）时返回 undefined，由外层退化为重新取数。
 */
function changedPatch(): AgentCardPatch | undefined {
  const source = form.value
  if (!source) {
    return undefined
  }
  return {
    id: props.agentId,
    name: source.name,
    code: source.code,
    runMode: source.runMode,
    status: source.status,
    description: source.description,
    updateTime: source.updateTime
  }
}

const user = useUserStore()
/**
 * 用途槽灰显用的「已启用实例覆盖码」。
 * 缓存在组件内：同一详情页反复 load 不必重复探测；模型中心改动后重开页面即可刷新。
 * 类型允许 null，表示探测失败——此时不灰显分组，避免一次网络抖动把整组槽位锁死。
 */
let capabilityProbe: Promise<string[] | null> | null = null

/**
 * 取模型中心「哪些用途还有已启用实例」，供用途槽判断整组是否灰显。
 * <p>
 * 先取一页 100 条做粗判，再对缺失的槽用途各发一次小页探测：
 * 实例总数超过 100 时，只靠首页会把排在后面的用途误判成「没有能力」，从而错误灰掉整组。
 * 任一请求失败返回 null（未知）——此时不灰显，宁可让用户点开看到空下拉。
 */
async function loadAvailableCapabilities(): Promise<string[] | null> {
  try {
    const { data } = await pageInstances({ current: 1, size: 100 })
    const codes = new Set<string>()
    for (const item of data.data?.records || []) {
      if (item.status !== 1) {
        continue
      }
      const code = normalizeCapability(item.capability)
      if (code) {
        codes.add(code)
      }
    }
    // 只对本页在册的槽码补齐探测（含记忆页承接的 SUMMARY / MEMORY），避免为用不到的用途多发请求
    const slotCodes: string[] = [
      ...new Set([
        ...PURPOSE_SLOT_GROUPS.flatMap((group) => [...group.codes]),
        ...MEMORY_SLOT_PURPOSES
      ])
    ]
    const missing = slotCodes.filter((code) => !codes.has(code))
    const probes = await Promise.all(
      missing.map((code) =>
        pageInstances({ current: 1, size: 5, capability: code })
          // 只要有一条已启用实例就算该用途有能力；禁用实例不算
          .then((res) => ((res.data.data?.records || []).some((row) => row.status === 1) ? code : ''))
          .catch(() => '')
      )
    )
    for (const code of probes) {
      if (code) {
        codes.add(code)
      }
    }
    return [...codes]
  } catch {
    return null
  }
}
const loading = ref(false)
const saving = ref(false)
const statusSaving = ref(false)
const error = ref('')
const form = ref<AgentDetail | null>(null)
const savedMode = ref('REACT')
const routeCount = ref(0)
const tab = ref('basic')
const models = ref<ModelBind[]>([])
/**
 * 模型中心已启用实例覆盖的用途码，供用途槽判断「整组有没有可用实例」。
 * null 表示还没取到（探测失败或未加载），此时不灰显分组。
 */
const availableCapabilities = ref<string[] | null>(null)

/**
 * 某用途在模型中心是否有已启用实例。
 * 探测未完成 / 失败（null）时返回 true，即不灰显——一次探测失败不该把入口锁死。
 *
 * @param purpose 用途码
 * @return true 表示可绑定（或未知，允许尝试）
 */
function purposeAvailable(purpose: string): boolean {
  const codes = availableCapabilities.value
  return !codes || codes.includes(purpose)
}
const tools = ref<ToolBind[]>([])
const skills = ref<SkillBind[]>([])
const routes = ref<AgentRouteRow[]>([])
/** 工具 / 技能绑定表前端分页；默认每页 10 条，与全站列表一致 */
const toolPager = reactive({ current: 1, size: 10 })
const skillPager = reactive({ current: 1, size: 10 })
/** 本页工具绑定 */
const pagedTools = computed(() => slicePage(tools.value, toolPager))
/** 本页技能绑定 */
const pagedSkills = computed(() => slicePage(skills.value, skillPager))

/**
 * 前端分页切片：页码越界时按最后一页取数（不改分页态，避免在 computed 里写响应式）。
 * 删行后页码由 {@link clampPager} 收敛。
 *
 * @param rows  全量绑定行
 * @param pager 分页态
 * @return 当前页数据
 */
function slicePage<T>(rows: T[], pager: { current: number; size: number }): T[] {
  const size = Math.max(pager.size, 1)
  const maxPage = Math.max(1, Math.ceil(rows.length / size))
  const current = Math.min(Math.max(pager.current, 1), maxPage)
  const start = (current - 1) * size
  return rows.slice(start, start + size)
}

/**
 * 把页码收到合法区间；只在列表变短时修正 current。
 *
 * @param pager 分页态
 * @param total 当前总条数
 */
function clampPager(pager: { current: number; size: number }, total: number) {
  const maxPage = Math.max(1, Math.ceil(total / Math.max(pager.size, 1)))
  if (pager.current > maxPage) {
    pager.current = maxPage
  }
}

/**
 * 绑定目标（模型实例 / 工具项 / 技能）自身启停对应的标签类型。
 * targetStatus 为空表示目标已被删除，按警告色提示。
 *
 * @param row 绑定的目标状态
 * @return el-tag type
 */
function targetTagType(row: { targetStatus?: number }): 'success' | 'info' | 'warning' | 'danger' {
  if (row.targetStatus === undefined || row.targetStatus === null) {
    return 'danger'
  }
  return row.targetStatus === 1 ? 'success' : 'warning'
}

/**
 * 绑定目标自身启停的展示文案。
 *
 * @param row  绑定的目标状态
 * @param noun 目标名词，如「工具」「技能」
 * @return 标签文本
 */
function targetTagText(row: { targetStatus?: number }, noun: string): string {
  if (row.targetStatus === undefined || row.targetStatus === null) {
    return `${noun}已删除`
  }
  return row.targetStatus === 1 ? `${noun}启用` : `${noun}停用`
}

/**
 * 工具绑定行的主展示名。
 * <p>
 * 库里 `title` 才是中文展示名，`name` 是英文调用码；只显示 name 会让配置人员看不懂
 * 这一行到底调的是什么能力。目标已被删除时绑定的 name 仍可能有值，故以 name 兜底。
 *
 * @param row 工具绑定行
 * @return 中文名；无中文名时退回英文码；都为空时提示已删除
 */
function toolDisplayName(row: { title?: string; name?: string }): string {
  return row.title?.trim() || row.name?.trim() || '工具已删除'
}

/**
 * 技能绑定行的主展示名。
 *
 * @param row 技能绑定行
 * @return 标题；无标题时退回编码；都为空时提示已删除
 */
function skillDisplayName(row: { title?: string; name?: string }): string {
  return row.title?.trim() || row.name?.trim() || '技能已删除'
}

/**
 * 目标不可用时禁止打开绑定开关；返回原因作为悬浮提示。
 * 目标已删 / 已停用都不能把绑定开到启用，后端也会拒绝。
 *
 * @param row 绑定的目标状态
 * @return 阻塞原因；可为空表示可切换
 */
function targetBlockReason(row: { targetStatus?: number }): string {
  if (row.targetStatus === undefined || row.targetStatus === null) {
    return '目标已删除，无法启用该绑定'
  }
  if (row.targetStatus !== 1) {
    return '目标已停用，请先在对应管理页启用'
  }
  return ''
}

/**
 * 绑定开关是否禁用。
 * 只在「当前是停用、想开启」时拦：目标已删 / 已停用不允许把绑定开到启用；
 * 已启用的绑定仍可关闭，避免目标停用后连关都关不掉。
 *
 * @param row 绑定行，含 status 与 targetStatus
 * @return true 表示开关不可点
 */
function switchDisabled(row: { status: number; targetStatus?: number }): boolean {
  if (row.status === 1) {
    return false
  }
  return !!targetBlockReason(row)
}

/**
 * 绑定开关的悬浮说明，仅在被拦时给出原因。
 *
 * @param row 绑定行
 * @return 提示文案；不需要提示时为 undefined
 */
function switchTitle(row: { status: number; targetStatus?: number }): string | undefined {
  return switchDisabled(row) ? targetBlockReason(row) : undefined
}
const modelOpen = ref(false)
/** 从用途槽添加时固定 purpose */
const modelAddPurpose = ref('')
const toolOpen = ref(false)
const skillOpen = ref(false)
/** 非 CHAT 用途的 L3 弹窗：状态、绑定行、用途码 */
const purposePromptOpen = ref(false)
const purposePromptBind = ref<ModelBind | null>(null)
const purposePromptPurpose = ref('')
const promptEditor = ref<{ reveal: () => { errors: string[]; warnings: string[] } } | null>(null)
const limitView = ref<RuntimeLimitsView | null>(null)

/** 切换 Agent 后旧请求返回时不再改界面，避免标题串到下一个 Agent。 */
let active = true

const canEdit = computed(() => user.hasPerm('agent:edit'))
/** 记忆策略单独权限码；无 edit 时 Tab 只读。 */
const canEditMemory = computed(() => user.hasPerm('memory:policy:edit'))
const canEnable = computed(() => user.hasPerm('agent:enable'))
const canTrial = computed(() => user.hasPerm('runtime:trial:run') || user.hasPerm('runtime:trial:list'))
const canBind = computed(() => user.hasPerm('agent:bind'))
/** 跟表单上的运行模式走：切到非 Router 立刻收起编排，不必等保存。 */
const showOrchestrationTab = computed(() => form.value?.runMode === 'ROUTER')
/** Router 父节点不配工具/技能/记忆；REACT 与计划执行都要配，切模式立刻藏/显 Tab，绑定数据不删。 */
const showReactBindTabs = computed(() => !!form.value && form.value.runMode !== 'ROUTER')
/** 模型/工具/技能即时落库；基本信息 / 编排策略 / 运行配置走外层底栏（记忆配置自带底栏）。 */
/** 基本信息 / 编排策略 / 运行限制 共用底栏保存 */
const showStickyBar = computed(() => tab.value === 'basic' || tab.value === 'orchestration' || tab.value === 'limits')

type MemoryPanelExpose = {
  deactivate: () => void
  /** 记忆 Tab 是否还有未提交的数字 / 备注草稿（开关已即时落库，不算脏） */
  isDirty: boolean
  /** 丢弃记忆 Tab 草稿，回到上次保存的快照 */
  cancel: () => void
}
const memoryPanel = ref<MemoryPanelExpose | null>(null)

/**
 * 提示词弹窗保存回调。所属域：agent。
 * <p>
 * 需求定稿：提示词弹窗自带保存链路，保存成功即落库，**不因此点亮页底取消 / 保存**。
 * 因此这里直接调用保存接口（用已落库快照拼包，避免把其它 Tab 的未提交草稿一并写库），
 * 成功后刷新快照让 isDirty 保持原样，页面底部按钮状态不受影响。
 *
 * @param value 子组件回传的完整四列
 */
async function onPromptChange(value: PromptBinding) {
  if (!form.value || promptSaving.value) {
    return
  }
  // 先就地改表单：弹窗已关闭，摘要行应立即反映新模板
  const previous = form.value.prompt
  form.value.prompt = normalizePromptBinding(value)
  const snapshot = saved.value
  if (!snapshot || !canEdit.value || !active) {
    return
  }
  promptSaving.value = true
  try {
    const { data } = await updateAgent(props.agentId, {
      ...payloadFromSaved(snapshot),
      // 弹窗可能同时迁移了业务提示词正文（CHAT 未挂模板时），随同一次请求落库
      systemPrompt: form.value.systemPrompt || '',
      // 落库的是「快照 + 本次提示词」，与其它草稿隔离
      prompt: {
        templateId: value.templateId ?? null,
        templateVersion: value.templateVersion ?? null,
        override: value.override || {},
        promptExtra: value.promptExtra || ''
      }
    })
    if (!active || !form.value) {
      return
    }
    const landed = normalizePromptBinding(data.data?.prompt ?? value)
    form.value.prompt = landed
    // 只洗白提示词两列：同一页其它未提交的文本 / 选择草稿必须继续算脏
    saved.value = {
      ...saved.value!,
      prompt: landed,
      systemPrompt: data.data?.systemPrompt ?? saved.value!.systemPrompt
    }
    patchBaseline({ prompt: landed, systemPrompt: data.data?.systemPrompt ?? saved.value!.systemPrompt })
    ElMessage.success('提示词已保存')
  } catch {
    // 失败回滚到保存前的四列，摘要行不能显示一个没落库的模板
    if (form.value) {
      form.value.prompt = previous
    }
  } finally {
    promptSaving.value = false
  }
}

/**
 * 脏检查纳入的字段：全部是「填写 / 选择 / 开关」类配置（文本、单选、下拉、数字、Switch）。
 * <p>
 * 例外：`status`、`requirePlanConfirm` **不在**本结构里——它们即时调接口落库，
 * 拨开关不应点亮页底取消 / 保存。编排 Tab 的策略字段（含多意图并行）统一走本快照，
 * 由底部保存按钮一次提交，避免「开关即时落库、其它策略要保存」的分裂语义。
 * 因此本结构同时也是「取消」要回滚的字段集合。
 */
interface DraftSnap {
  name: string
  code: string
  runMode: string
  /** 允许被编排调用；ROUTER 快照恒为 false，切回其它模式默认 true */
  allowOrchestrated: boolean
  description: string
  systemPrompt: string
  /** L3 四列；取消时一并回滚，避免只回滚正文留下半套配置 */
  prompt: PromptBinding
  remark: string
  timeoutMs: number | null
  maxRounds: number | null
  toolTimeoutMs: number | null
  toolMaxRetries: number | null
  maxToolCalls: number | null
  llmTimeoutMs: number | null
  maxLlmCalls: number | null
  consecutiveFailAbort: number | null
  toolResultMaxChars: number | null
  /** 编排策略：多意图并行（旧字段，行为生效） */
  multiIntentEnabled: boolean
  /** 编排策略：汇总 CONCAT / SUMMARIZE / VOTE，复用 answerMode */
  answerMode: string
  /** 编排策略：最大并行，复用 maxRouteChildren */
  maxRouteChildren: number
  /** 编排模板；ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP | ROUTER_THEN_SEQUENCE */
  maTemplate: string
  /** 本编排允许的子运行模式；空按仅 REACT（订单3 刀4） */
  maChildRunModesAllowed: string[]
  /** 评审环：评审专家 agentId；非评审环时也保留，切回可恢复 */
  maCriticAgentId: number | null
  /** 评审环：最大评审轮次，1～3 */
  maCriticMaxRounds: number
  /** 上下文策略；ISOLATED（默认）| PRIOR_SUMMARY_ONLY（仅串行模板）| SHARED_SCRATCH（共享草稿区） */
  maContextPolicy: string
  /** 单意图是否继承父历史；各模板都按启用候选数=1 时生效 */
  maAllowParentHistoryWhenSingle: boolean
  maxPlanSteps: number
  maxStepRetries: number
}

/** 已落库快照 = 草稿字段 + 即时开关值；拼即时保存体时要带上开关，但开关不参与 isDirty。 */
interface SavedSnap extends DraftSnap {
  requirePlanConfirm: boolean
}

/**
 * 结果合成归一：只认 CONCAT / SUMMARIZE / VOTE；VOTE 在模板不支持时回落 CONCAT。
 *
 * <p>与服务端 `AgentRuntimeConfig.answerModeEnum` 同口径：历史脏值、或模板已换成不支持投票的，
 * 都不能让页面显示「投票」却按拼接执行。
 *
 * @param raw      库内 answerMode
 * @param template 库内模板
 * @return 可回显的 answerMode
 */
function normalizeAnswerMode(raw: string | undefined | null, template: string | undefined | null): string {
  const mode = (raw || '').toUpperCase()
  if (mode === 'SUMMARIZE') {
    return 'SUMMARIZE'
  }
  if (mode === 'VOTE') {
    return supportsVoteTemplate(normalizeTemplate(template)) ? 'VOTE' : 'CONCAT'
  }
  return 'CONCAT'
}

/**
 * 表单 → 脏检查快照。只取填写 / 选择类字段，忽略计数、updateTime 等纯展示字段。
 * 表单未加载时返回 null，此时一律不判脏（避免空壳页误显示可保存）。
 *
 * @param source 当前表单
 * @return 可稳定序列化的快照；null 表示无表单
 */
function draftFromForm(source: AgentDetail | null): DraftSnap | null {
  if (!source) {
    return null
  }
  const runtime = source.runtimeConfig
  return {
    name: source.name,
    code: source.code,
    runMode: source.runMode,
    // ROUTER 恒为关（编排根不可作子）；其它模式 undefined 视为开，与服务端默认一致
    allowOrchestrated: source.runMode === 'ROUTER' ? false : source.allowOrchestrated !== false,
    description: source.description || '',
    systemPrompt: source.systemPrompt || '',
    prompt: normalizePromptBinding(source.prompt),
    remark: source.remark || '',
    timeoutMs: source.timeoutMs ?? null,
    maxRounds: source.maxRounds ?? null,
    toolTimeoutMs: source.toolTimeoutMs ?? null,
    toolMaxRetries: source.toolMaxRetries ?? null,
    maxToolCalls: source.maxToolCalls ?? null,
    llmTimeoutMs: source.llmTimeoutMs ?? null,
    maxLlmCalls: source.maxLlmCalls ?? null,
    consecutiveFailAbort: source.consecutiveFailAbort ?? null,
    toolResultMaxChars: source.toolResultMaxChars ?? null,
    // 编排策略：开关也进快照，统一由底栏保存；status / requirePlanConfirm 仍即时落库
    multiIntentEnabled: runtime?.multiIntentEnabled !== false,
    answerMode: normalizeAnswerMode(runtime?.answerMode, runtime?.maTemplate),
    maxRouteChildren: Math.min(5, Math.max(1, runtime?.maxRouteChildren ?? 3)),
    maTemplate: runtime?.maTemplate || 'ROUTER_PARALLEL',
    maChildRunModesAllowed: normalizeChildRunModes(runtime?.maChildRunModesAllowed),
    // 评审环：库里的 id 与轮次都进快照，取消 / 脏检查才不会把评审人洗掉
    maCriticAgentId: runtime?.maCriticAgentId ?? null,
    maCriticMaxRounds: normalizeCriticMaxRounds(runtime?.maCriticMaxRounds),
    maContextPolicy: runtime?.maContextPolicy || 'ISOLATED',
    maAllowParentHistoryWhenSingle: runtime?.maAllowParentHistoryWhenSingle !== false,
    maxPlanSteps: Math.min(20, Math.max(1, runtime?.maxPlanSteps ?? 8)),
    maxStepRetries: Math.min(3, Math.max(0, runtime?.maxStepRetries ?? 0))
  }
}

/**
 * 取消：把快照写回表单。只回写填写 / 选择类字段，开关保持已落库的当前值。
 *
 * @param snap 上次成功保存（或载入）的快照
 */
function restoreDraft(snap: DraftSnap) {
  const target = form.value
  if (!target) {
    return
  }
  target.name = snap.name
  target.code = snap.code
  target.runMode = snap.runMode
  target.allowOrchestrated = snap.allowOrchestrated
  target.description = snap.description
  target.systemPrompt = snap.systemPrompt
  target.prompt = normalizePromptBinding(snap.prompt)
  target.remark = snap.remark
  target.timeoutMs = snap.timeoutMs
  target.maxRounds = snap.maxRounds
  target.toolTimeoutMs = snap.toolTimeoutMs
  target.toolMaxRetries = snap.toolMaxRetries
  target.maxToolCalls = snap.maxToolCalls
  target.llmTimeoutMs = snap.llmTimeoutMs
  target.maxLlmCalls = snap.maxLlmCalls
  target.consecutiveFailAbort = snap.consecutiveFailAbort
  target.toolResultMaxChars = snap.toolResultMaxChars
  // 编排策略与选项类字段一起回滚；requirePlanConfirm 是即时开关，保持当前已落库值
  target.runtimeConfig = {
    ...target.runtimeConfig,
    multiIntentEnabled: snap.multiIntentEnabled,
    answerMode: snap.answerMode,
    maxRouteChildren: snap.maxRouteChildren,
    maTemplate: snap.maTemplate,
    maChildRunModesAllowed: snap.maChildRunModesAllowed,
    maCriticAgentId: snap.maCriticAgentId,
    maCriticMaxRounds: snap.maCriticMaxRounds,
    maContextPolicy: snap.maContextPolicy,
    maAllowParentHistoryWhenSingle: snap.maAllowParentHistoryWhenSingle,
    maxPlanSteps: snap.maxPlanSteps,
    maxStepRetries: snap.maxStepRetries
  }
  // 提示词模板跟着回滚，避免下次切模式时拿旧锚点做整段替换
  promptModeAnchor.value = snap.runMode
}

const { isDirty, markClean, patchBaseline, reset: discardDraft } = useFormDirty<DraftSnap>(
  () => draftFromForm(form.value),
  restoreDraft
)

/** 最近一次成功落库的完整快照；标题与「即时保存」拼包都以它为准。 */
const saved = ref<SavedSnap | null>(null)
/** 提示词弹窗保存中：避免连点重复落库 */
const promptSaving = ref(false)
/** 正在即时保存的运行配置开关名；空串表示没有在提交 */
const runtimeSaving = ref('')
/** 标题用已落库名称。输入框里的未保存改名不带动标题。 */
const heading = computed(() => saved.value?.name || form.value?.name || 'Agent')
const headingCode = computed(() => saved.value?.code || form.value?.code || '')

watch(
  [() => props.agentId, () => props.autoLoad],
  () => {
    /*
     * 默认回基本信息。从「编排 → 多智能体」进来时带 initialTab=orchestration，
     * 需停在该 Tab；这里只做初值，等详情加载后若该 Agent 不是 ROUTER，
     * 下方 showOrchestrationTab 的 watch 会把不可见 Tab 收回基本信息。
     */
    tab.value = props.initialTab === 'orchestration' ? 'orchestration' : 'basic'
    /*
     * autoLoad 为 false 时只做准备不发请求。
     * 该 prop 是响应式的：调用方从 false 改成 true 时会自动触发 load，
     * 用于「组件常驻挂载、但把数据请求推迟到动画结束之后」的场景。
     */
    if (props.autoLoad === false) {
      return
    }
    /*
     * 切 Agent：表单里还是上一个 Agent 的数据，必须先清空再加载。
     * 若不清，load() 会把上一份草稿当成「当前草稿」保留下来，串到另一个 Agent 上。
     * markClean 在 form 为空时会把基线置 null，于是新数据回填前一律不判脏。
     */
    form.value = null
    saved.value = null
    markClean()
    load(false)
  },
  { immediate: true }
)

/**
 * 兜底：从 KeepAlive 缓存中被重新激活时，若发现还没数据就补一次加载。
 * watch 依赖 agentId / autoLoad，若两者都没变（缓存的同一实例被重新激活），
 * watch 不会触发；此时靠这里保证右栏不会停在空壳。
 */
onActivated(() => {
  if (props.autoLoad === false || form.value || loading.value) {
    return
  }
  load()
})

/** 当前 Tab 被模式藏起时回到基本信息，避免停在不可见 pane。 */
watch([showOrchestrationTab, showReactBindTabs], ([orchestrationVisible, reactVisible]) => {
  if ((!orchestrationVisible && tab.value === 'orchestration') || (!reactVisible && ['tool', 'skill', 'memory', 'kb'].includes(tab.value))) {
    tab.value = 'basic'
  }
})

/**
 * 同一个 Agent 再次从「编排 → 多智能体」进入时，KeepAlive 复用详情实例，
 * agentId 未变 → 上面的重置 watch 不触发；这里只补一次 Tab 切换，不重拉详情。
 */
watch(
  () => props.initialTab,
  (value) => {
    if (value === 'orchestration' && showOrchestrationTab.value) {
      tab.value = 'orchestration'
    }
  }
)

/**
 * 与表单 runMode 对齐的锚点：仅用户点 radio 时做模板替换，避免 load/切 Agent 误覆盖。
 */
const promptModeAnchor = ref('REACT')

/**
 * 基本信息里改 runMode：空或仍为旧模式默认模板才换成新默认；自定义不覆盖。
 * <p>
 * Router 边界：agent_def 只承载 CHAT 模板，切到 ROUTER 后已选的模板永远不会生效，
 * 后端也会拒绝保存。因此这里就地清空并提示，并同步 supported 让编辑区退化为自由段。
 */
function onRunModeChange(value: string | number | boolean) {
  if (!form.value) {
    return
  }
  const next = String(value)
  const prev = promptModeAnchor.value
  promptModeAnchor.value = next
  const replaced = promptAfterModeSwitch(form.value.systemPrompt || '', form.value.name, prev, next)
  if (replaced !== null) {
    form.value.systemPrompt = replaced
  }
  if (next === 'ROUTER') {
    // 编排根不可作子：切到 ROUTER 就地把开关关掉（保存时服务端同样强制写 0）
    form.value.allowOrchestrated = false
    if (form.value.prompt?.templateId) {
      ElMessage.warning('Router 模式的提示词请在「模型配置」的 ROUTER 用途槽配置，已清空 Agent 定义上的 CHAT 模板')
    }
    form.value.prompt = {
      supported: false,
      templateId: null,
      templateVersion: null,
      override: {},
      promptExtra: form.value.prompt?.promptExtra || ''
    }
  } else if (prev === 'ROUTER') {
    // 切回非 Router：恢复可配置能力；按推荐口径让「允许被编排调用」默认恢复为开
    form.value.allowOrchestrated = true
    // 模板需重新选择（原来那份属于 ROUTER 用途槽）
    form.value.prompt = {
      supported: true,
      templateId: null,
      templateVersion: null,
      override: {},
      promptExtra: form.value.prompt?.promptExtra || ''
    }
  }
}

/**
 * 开关「允许被编排调用」。
 *
 * <p>只改表单草稿，随页面底部保存一次落库（与其它填写类字段同口径）。
 * ROUTER 下 UI 已禁用，这里再兜一道：编排根永远不能写成允许，避免后续改动绕过禁用态。
 *
 * @param value el-switch 回传值
 */
function onAllowOrchestratedChange(value: string | number | boolean) {
  if (!form.value) {
    return
  }
  form.value.allowOrchestrated = form.value.runMode === 'ROUTER' ? false : value === true
}

onBeforeUnmount(() => {
  active = false
  unregisterDraft?.()
  memoryPanel.value?.deactivate()
})

/**
 * 把本详情的脏态登记到跨组件草稿表：外层在切 Agent / 回网格前会查询，
 * 有草稿时先确认再丢弃，避免切走时静默丢稿。
 * 详情组件在 KeepAlive 里，同一页可能有多个实例，因此按 agentId 分别登记。
 */
let unregisterDraft: (() => void) | null = null

watch(
  () => props.agentId,
  (id) => {
    unregisterDraft?.()
    unregisterDraft = registerDraft(`agent:${id}`, {
      isDirty: () => isDirty.value || memoryPanel.value?.isDirty === true,
      discard: () => {
        discardDraft()
        memoryPanel.value?.cancel()
        if (form.value && saved.value) {
          form.value.prompt = normalizePromptBinding(saved.value.prompt)
        }
      }
    })
  },
  { immediate: true }
)

/**
 * 拉取详情与全部绑定。
 * <p>
 * 草稿保护：绑定增删等即时操作也会走这里刷新，若直接把服务端值覆盖进表单，
 * 用户正在编辑但尚未保存的文本 / 选择草稿会被静默抹掉（且脏态一起消失）。
 * 因此默认保留当前草稿：只刷新非草稿部分与已落库基线，草稿仍算脏、按钮仍亮着。
 * 保存成功后调用时传 false —— 此刻表单内容就是要落库的内容，直接以服务端回填为准。
 *
 * @param preserveDraft 是否保留表单里未提交的草稿（默认 true）
 */
async function load(preserveDraft = true) {
  loading.value = true
  error.value = ''
  // 刷新前先记下草稿：服务端回来的值要填进 form，但草稿字段必须由用户原值覆盖回去
  const draftBefore = preserveDraft && isDirty.value ? draftFromForm(form.value) : null
  try {
    /*
     * 全部请求并发发出，一个批次结束。
     * getRuntimeLimits 是平台级常量，但与详情无依赖关系，
     * 因此放进同一个 Promise.all；失败不阻断主流程，故就地 catch 降级。
     */
    const [detail, modelRes, toolRes, skillRes, routeRes, limitsRes] = await Promise.all([
      getAgent(props.agentId),
      listModelBinds(props.agentId),
      listToolBinds(props.agentId),
      listSkillBinds(props.agentId),
      listRoutes(props.agentId),
      getRuntimeLimits().catch(() => null)
    ])
    // 用途槽灰显探测与详情并行；整页只探一次，失败按「未知」处理（不灰显）
    if (!capabilityProbe) {
      capabilityProbe = loadAvailableCapabilities()
    }
    void capabilityProbe.then((codes) => {
      if (active) {
        availableCapabilities.value = codes
      }
    })
    if (!active) {
      return
    }
    const agent = detail.data.data
    form.value = {
      ...agent,
      description: agent.description || '',
      systemPrompt: agent.systemPrompt || '',
      prompt: normalizePromptBinding(agent.prompt),
      remark: agent.remark || '',
      timeoutMs: agent.timeoutMs ?? null,
      maxRounds: agent.maxRounds ?? null,
      toolTimeoutMs: agent.toolTimeoutMs ?? null,
      toolMaxRetries: agent.toolMaxRetries ?? null,
      maxToolCalls: agent.maxToolCalls ?? null,
      llmTimeoutMs: agent.llmTimeoutMs ?? null,
      maxLlmCalls: agent.maxLlmCalls ?? null,
      consecutiveFailAbort: agent.consecutiveFailAbort ?? null,
      toolResultMaxChars: agent.toolResultMaxChars ?? null,
      runtimeConfig: normalizeRuntimeConfig(agent.runtimeConfig)
    }
    savedMode.value = agent.runMode
    promptModeAnchor.value = agent.runMode
    routeCount.value = agent.routeCount || 0
    models.value = modelRes.data.data || []
    tools.value = toolRes.data.data || []
    skills.value = skillRes.data.data || []
    routes.value = routeRes.data.data || []
    // 列表变短（如移除绑定、或别处删了目标）时把页码收回合法区间
    clampPager(toolPager, tools.value.length)
    clampPager(skillPager, skills.value.length)
    if (limitsRes?.data?.data) {
      limitView.value = limitsRes.data.data
    }
    // 先以服务端值定基线（这才是「已落库」真相）
    remember()
    // 再把用户的草稿盖回表单：基线不动，于是 isDirty 继续为 true，按钮保持可点
    if (draftBefore) {
      restoreDraft(draftBefore)
    }
  } catch {
    if (!active) {
      return
    }
    error.value = '详情加载失败'
    form.value = null
  } finally {
    if (active) {
      loading.value = false
    }
  }
}

/**
 * 提交草稿。编排策略（含多意图并行）随本次保存落库；
 * `requirePlanConfirm` 是即时开关，取「已落库」值，避免把未落库的拨动写进去。
 *
 * @return 提交用的表单体；表单未加载时为 null
 */
function payload(): AgentSave | null {
  if (!form.value) {
    return null
  }
  const runtime = normalizeRuntimeConfig(form.value.runtimeConfig)
  return {
    name: form.value.name.trim(),
    code: form.value.code.trim(),
    runMode: form.value.runMode,
    // ROUTER 一律上报 false（服务端也会强制）；其它模式按当前开关，undefined 视为开
    allowOrchestrated: form.value.runMode === 'ROUTER' ? false : form.value.allowOrchestrated !== false,
    description: form.value.description?.trim() || '',
    systemPrompt: form.value.systemPrompt || '',
    // L3 四列整体回传：后端据 templateId 是否为空决定「挂模板 / 清空」
    prompt: {
      templateId: form.value.prompt?.templateId ?? null,
      templateVersion: form.value.prompt?.templateVersion ?? null,
      override: form.value.prompt?.override || {},
      promptExtra: form.value.prompt?.promptExtra || ''
    },
    remark: form.value.remark?.trim() || '',
    timeoutMs: form.value.timeoutMs ?? null,
    maxRounds: form.value.maxRounds ?? null,
    toolTimeoutMs: form.value.toolTimeoutMs ?? null,
    toolMaxRetries: form.value.toolMaxRetries ?? null,
    maxToolCalls: form.value.maxToolCalls ?? null,
    llmTimeoutMs: form.value.llmTimeoutMs ?? null,
    maxLlmCalls: form.value.maxLlmCalls ?? null,
    consecutiveFailAbort: form.value.consecutiveFailAbort ?? null,
    toolResultMaxChars: form.value.toolResultMaxChars ?? null,
    runtimeConfig: {
      ...runtime,
      // 多意图并行已并入编排草稿，跟随本次保存；requirePlanConfirm 是即时开关，取已落库值
      requirePlanConfirm: saved.value?.requirePlanConfirm ?? runtime.requirePlanConfirm
    }
  }
}

/**
 * L3 回显归一化：后端可能返回 null 或只给部分字段，统一成四列结构。
 * Router 模式后端返回 supported=false，此时编辑区退化为自由业务段（不显示 L3 表单）。
 *
 * @param raw 接口返回的 prompt
 * @return 完整的四列值
 */
function normalizePromptBinding(raw?: PromptBinding | null): PromptBinding {
  return {
    supported: raw?.supported === true,
    templateId: raw?.templateId ?? null,
    templateVersion: raw?.templateVersion ?? null,
    override: { ...(raw?.override || {}) },
    promptExtra: raw?.promptExtra || ''
  }
}

/**
 * 运行配置缺省：Router 那组（多意图开 / 拼接 / N=3 / ROUTER_PARALLEL / ISOLATED / 单意图继承）
 * 与计划那组（自动执行 / 8 步 / 0 重试）都给齐，切模式不丢值。
 *
 * <p>模板与上下文策略按三值白名单回显：固定串行默认前置摘要，其它模板一律隔离。
 * 只有真正非法的历史脏值才回落 ROUTER_PARALLEL，禁止把用户选的固定模板洗回路由并行。
 */
function normalizeRuntimeConfig(raw?: AgentRuntimeConfig | null): AgentRuntimeConfig {
  const template = normalizeTemplate(raw?.maTemplate)
  return {
    multiIntentEnabled: raw?.multiIntentEnabled !== false,
    answerMode: normalizeAnswerMode(raw?.answerMode, raw?.maTemplate),
    maxRouteChildren: Math.min(5, Math.max(1, raw?.maxRouteChildren ?? 3)),
    maTemplate: template,
    maContextPolicy: normalizeContextPolicy(raw?.maContextPolicy, template),
    maAllowParentHistoryWhenSingle: raw?.maAllowParentHistoryWhenSingle !== false,
    requirePlanConfirm: raw?.requirePlanConfirm === true,
    maxPlanSteps: Math.min(20, Math.max(1, raw?.maxPlanSteps ?? 8)),
    maxStepRetries: Math.min(3, Math.max(0, raw?.maxStepRetries ?? 0)),
    // 子运行模式：白名单清洗并恒补 ReAct；空/非法按仅 ReAct，与服务端保存口径一致
    maChildRunModesAllowed: normalizeChildRunModes(raw?.maChildRunModesAllowed),
    // 评审环：轮次夹到 1～3；评审专家只在模板=CRITIC_LOOP 时必填，其它模板保留值以便切回
    maCriticMaxRounds: normalizeCriticMaxRounds(raw?.maCriticMaxRounds),
    maCriticAgentId: raw?.maCriticAgentId ?? null
  }
}

/**
 * 记下已落库的状态：编排策略 / 填写 / 选择类字段进脏检查基线，
 * 仅 `requirePlanConfirm` 这类即时开关单独保存供拼包。
 * 载入详情、保存成功、即时开关落库成功后都要调一次。
 *
 * 注意调用时机：调用时 form 里必须是「服务端刚返回的已落库值」。
 * load() 会在调用本函数之后才把用户草稿盖回表单，因此草稿不会被当成基线。
 */
function remember() {
  const draft = draftFromForm(form.value)
  const runtime = form.value?.runtimeConfig
  if (!form.value || !draft) {
    saved.value = null
    return
  }
  saved.value = {
    ...draft,
    requirePlanConfirm: runtime?.requirePlanConfirm === true
  }
  // 载入 / 保存成功后重置脏基线：异步回填不能被当成用户改动
  markClean()
}

type LimitKey = (typeof limitFields)[number]['key']

/** 占位显示平台默认 */
function limitPlaceholder(key: LimitKey) {
  const bound = limitView.value?.[key]
  return bound ? `平台默认 ${bound.defaultValue}` : '跟平台默认'
}

/** 输入框 max 跟平台硬顶 */
function limitCap(key: LimitKey) {
  return limitView.value?.[key]?.max ?? 999999
}

/** 清空时写 null，避免 0 被当成覆盖 */
function onLimitChange(key: LimitKey, value: number | undefined) {
  if (!form.value) {
    return
  }
  form.value[key] = value === undefined || value === null || Number.isNaN(value) ? null : value
}

/** 前端先拦超顶；后端仍会再校验 */
function validateLimits(): string | null {
  if (!form.value || !limitView.value) {
    return null
  }
  for (const item of limitFields) {
    const raw = form.value[item.key]
    if (raw === null || raw === undefined) {
      continue
    }
    const cap = limitView.value[item.key].max
    if (raw < item.floor || raw > cap) {
      return `${item.label} 超出平台硬顶：允许 ${item.floor}～${cap}`
    }
  }
  return null
}

/** 放弃未保存的草稿。启停和绑定是即时接口，不在这次回滚里。 */
function revert() {
  if (!form.value || !saved.value) {
    return
  }
  // restoreDraft 已把 prompt 四列一并回滚到快照，无需再单独处理
  discardDraft()
}

/**
 * 只提交填写 / 选择类草稿。绑定与两个运行开关都是即时接口，不在这里提交。
 */
async function save() {
  const body = payload()
  if (!body || !form.value) {
    return
  }
  if (!body.name || !body.code) {
    ElMessage.warning('请填写名称和编码')
    return
  }
  const report = promptEditor.value?.reveal() ?? checkUserPrompt(body.systemPrompt || '')
  if (report.errors.length) {
    ElMessage.error(report.errors.join('；'))
    return
  }
  const limitError = validateLimits()
  if (limitError) {
    ElMessage.error(limitError)
    return
  }
  saving.value = true
  try {
    const { data } = await updateAgent(props.agentId, body)
    const warnings = data.data?.warnings || []
    if (warnings.length) {
      ElMessage.warning(warnings.join('；'))
    } else {
      ElMessage.success('已保存')
    }
    emit('changed', changedPatch())
    // 保存成功：表单内容即刚落库的内容，以服务端回填为准（不保留草稿）
    await load(false)
  } finally {
    saving.value = false
  }
}

/**
 * 运行配置里的即时开关（规划后需确认）走即时落库。
 * <p>
 * 该开关与编排策略 / 选项类字段共用一份 runtime_config_json，没有独立接口，
 * 因此走整份保存：拼包时**其余字段一律取已落库快照**，
 * 这样既不会把用户尚未提交的文本 / 编排草稿写进去，也不会把开关之外的东西改掉；
 * 成功后只刷新开关与已落库快照，底栏脏态保持不变。
 *
 * @param key   requirePlanConfirm
 * @param value 目标布尔值
 */
async function saveRuntimeSwitch(key: 'requirePlanConfirm', value: boolean) {
  const snapshot = saved.value
  if (!form.value || !snapshot || !canEdit.value || !active) {
    return
  }
  const runtime: AgentRuntimeConfig = {
    multiIntentEnabled: snapshot.multiIntentEnabled,
    answerMode: snapshot.answerMode,
    maxRouteChildren: snapshot.maxRouteChildren,
    maTemplate: snapshot.maTemplate,
    maContextPolicy: snapshot.maContextPolicy,
    maAllowParentHistoryWhenSingle: snapshot.maAllowParentHistoryWhenSingle,
    requirePlanConfirm: value,
    maxPlanSteps: snapshot.maxPlanSteps,
    maxStepRetries: snapshot.maxStepRetries,
    // 整份运行配置回传：漏传这几项会被服务端按默认值洗掉（P&E 允许 / 评审环配置）
    maChildRunModesAllowed: snapshot.maChildRunModesAllowed,
    maCriticAgentId: snapshot.maCriticAgentId,
    maCriticMaxRounds: snapshot.maCriticMaxRounds
  }
  const previous = form.value.runtimeConfig
  runtimeSaving.value = key
  // 先乐观更新，让开关立刻到位；失败再回滚（与启停开关同观感）
  form.value.runtimeConfig = { ...normalizeRuntimeConfig(previous), ...runtime }
  try {
    const { data } = await updateAgent(props.agentId, {
      ...payloadFromSaved(snapshot),
      runtimeConfig: runtime
    })
    if (!active || !form.value) {
      return
    }
    const landed = normalizeRuntimeConfig(data.data?.runtimeConfig ?? runtime)
    // 只把即时开关写回表单；编排与计划数字保持用户当前编辑中的草稿，不归这次请求管
    form.value.runtimeConfig = {
      ...form.value.runtimeConfig,
      requirePlanConfirm: landed.requirePlanConfirm
    }
    // 快照同步为落库值；isDirty 只比草稿字段，因此即时开关变化不会点亮底栏
    saved.value = {
      ...saved.value!,
      requirePlanConfirm: landed.requirePlanConfirm === true
    }
    ElMessage.success('开关已保存')
  } catch {
    // 失败只回滚即时开关，不触碰其它草稿字段
    if (form.value) {
      form.value.runtimeConfig = {
        ...form.value.runtimeConfig,
        requirePlanConfirm: snapshot.requirePlanConfirm
      }
    }
  } finally {
    runtimeSaving.value = ''
  }
}

/**
 * 用已落库快照拼提交体。供即时保存使用：确保写库的是「库里的值 + 本次改的那一项」，
 * 而不是用户尚未提交的草稿。
 *
 * @param snapshot 已落库快照
 * @return 提交体
 */
function payloadFromSaved(snapshot: SavedSnap): AgentSave {
  return {
    name: snapshot.name,
    code: snapshot.code,
    runMode: snapshot.runMode,
    // 必须带上：这两条即时接口也走整份保存，漏传会被服务端按默认值重新打开开关
    allowOrchestrated: snapshot.runMode === 'ROUTER' ? false : snapshot.allowOrchestrated,
    description: snapshot.description,
    systemPrompt: snapshot.systemPrompt,
    prompt: {
      templateId: snapshot.prompt.templateId ?? null,
      templateVersion: snapshot.prompt.templateVersion ?? null,
      override: snapshot.prompt.override || {},
      promptExtra: snapshot.prompt.promptExtra || ''
    },
    remark: snapshot.remark,
    timeoutMs: snapshot.timeoutMs,
    maxRounds: snapshot.maxRounds,
    toolTimeoutMs: snapshot.toolTimeoutMs,
    toolMaxRetries: snapshot.toolMaxRetries,
    maxToolCalls: snapshot.maxToolCalls,
    llmTimeoutMs: snapshot.llmTimeoutMs,
    maxLlmCalls: snapshot.maxLlmCalls,
    consecutiveFailAbort: snapshot.consecutiveFailAbort,
    toolResultMaxChars: snapshot.toolResultMaxChars,
    runtimeConfig: {
      multiIntentEnabled: snapshot.multiIntentEnabled,
      answerMode: snapshot.answerMode,
      maxRouteChildren: snapshot.maxRouteChildren,
      maTemplate: snapshot.maTemplate,
      maContextPolicy: snapshot.maContextPolicy,
      maAllowParentHistoryWhenSingle: snapshot.maAllowParentHistoryWhenSingle,
      requirePlanConfirm: snapshot.requirePlanConfirm,
      maxPlanSteps: snapshot.maxPlanSteps,
      maxStepRetries: snapshot.maxStepRetries,
      // 深拷贝数组：保存体不能引用快照里的对象，避免后续草稿编辑连带改基线
      maChildRunModesAllowed: [...snapshot.maChildRunModesAllowed],
      maCriticAgentId: snapshot.maCriticAgentId,
      maCriticMaxRounds: snapshot.maCriticMaxRounds
    }
  }
}

/**
 * 当前 Tab 是否有未提交的草稿。
 * 基本信息 / 编排 / 运行配置看页面级脏态；记忆配置看面板自己的脏态（它的底栏在面板内）。
 *
 * @param name Tab name
 * @return true 表示该 Tab 有未提交改动
 */
function tabHasDraft(name: string | number): boolean {
  if (name === 'basic' || name === 'orchestration' || name === 'limits') {
    return isDirty.value
  }
  if (name === 'memory') {
    return memoryPanel.value?.isDirty === true
  }
  return false
}

/**
 * 切 Tab 前的脏检查。
 * 有未提交草稿时先问一句，答「丢弃」才允许切走；无脏直接放行，不做任何拦阻。
 *
 * @param next 目标 Tab name
 * @param from 当前 Tab name
 * @return true 表示允许切换
 */
async function beforeTabLeave(next: string | number, from: string | number) {
  if (next === from || !tabHasDraft(from)) {
    return true
  }
  const leave = await confirmDiscardUnsaved('当前配置')
  if (!leave) {
    return false
  }
  // 丢弃：页面级草稿回滚到已落库值，记忆面板同理
  if (from === 'memory') {
    memoryPanel.value?.cancel()
  } else {
    discardDraft()
  }
  return true
}

/** 接口已成功、开关即将翻转时写入。提前改 form.status 会让受控开关再翻回去。 */
let pendingStatus: number | null = null

/**
 * 开关提交前调用启停接口。返回 false 时不翻转，失败保持原状态。
 * 无 agent:enable 时直接拒绝。这里不改 form.status。
 */
async function beforeStatusChange() {
  if (!form.value || !canEnable.value || !active) {
    return false
  }
  const next = form.value.status === 1 ? 0 : 1
  statusSaving.value = true
  try {
    const { data } = await changeAgentStatus(props.agentId, next)
    if (!active || !form.value) {
      return false
    }
    const saved = data.data?.status
    pendingStatus = saved === 0 || saved === 1 ? saved : next
    return true
  } catch {
    pendingStatus = null
    return false
  } finally {
    statusSaving.value = false
  }
}

/** before-change 放行后才落本地状态，并让左侧列表的启用态跟着变。 */
function onStatusChanged() {
  if (!form.value || pendingStatus === null) {
    return
  }
  form.value.status = pendingStatus
  pendingStatus = null
  emit('changed', changedPatch())
}

/**
 * 用已保存的名称和运行模式打开试跑。按钮在标题行右侧、编排 Tab 底部也复用同一条路径。
 * 停用 Agent 直接拦截。
 *
 * <p>落点由父级决定：Router 编排根跳独立全屏试跑页（消息流内编排卡），
 * ReAct / 计划执行仍开原抽屉；本组件只管把当前已保存的模式交出去。
 */
function onTalk() {
  if (!form.value || form.value.status !== 1) {
    return
  }
  emit('trial', {
    agentId: props.agentId,
    name: saved.value?.name || form.value.name,
    runMode: savedMode.value || form.value.runMode
  })
}

/** 从指定用途槽打开添加弹窗。 */
function openModelAdd(purpose: string) {
  modelAddPurpose.value = purpose
  modelOpen.value = true
}

/** 按勾选顺序逐条创建；用途固定为本槽，priority 按槽内尾部递增。 */
async function addModel(
  body: { modelInstanceIds: number[]; purpose?: string; priority: number },
  done: () => void
) {
  try {
    const purpose = modelAddPurpose.value || body.purpose
    const slot = models.value
      .filter((row) => normalizeCapability(row.effectivePurpose || row.purpose || row.capability) === purpose)
      .slice()
      .sort((a, b) => a.priority - b.priority || a.id - b.id)
    let nextPriority = slot.length ? slot[slot.length - 1].priority + 10 : 10
    for (const modelInstanceId of body.modelInstanceIds) {
      await createModelBind(props.agentId, {
        modelInstanceId,
        purpose,
        priority: nextPriority
      })
      nextPriority += 10
    }
    modelOpen.value = false
    modelAddPurpose.value = ''
    await load()
    emit('changed', changedPatch())
  } finally {
    done()
  }
}

/**
 * 槽内重排：按新顺序把 priority 重写成递增步长（10、20、30…）。
 * 只提交 priority 有变化的行，减少即时接口调用；顺序即降级优先级。
 *
 * @param purpose    用途槽
 * @param orderedIds 新顺序的绑定 id（从高优先级到低优先级）
 */
async function reorderModelSlot(purpose: string, orderedIds: number[]) {
  const rows = models.value.filter(
    (item) => normalizeCapability(item.effectivePurpose || item.purpose || item.capability) === purpose
  )
  const byId = new Map(rows.map((item) => [item.id, item]))
  const changed = orderedIds
    .map((id, index) => ({ row: byId.get(id), priority: (index + 1) * 10 }))
    .filter((item): item is { row: ModelBind; priority: number } => !!item.row && item.row.priority !== item.priority)
  if (!changed.length) {
    return
  }
  for (const item of changed) {
    await updateModelBind(props.agentId, item.row.id, {
      purpose: item.row.purpose,
      priority: item.priority,
      status: item.row.status
    })
  }
  await load()
  emit('changed', changedPatch())
}

async function removeModel(row: ModelBind) {
  await deleteModelBind(props.agentId, row.id)
  await load()
  emit('changed', changedPatch())
}

/**
 * 打开某用途槽主模型的 L3 配置弹窗。
 * 用途取槽位（而非绑定行上的 purpose），因为绑定行的 purpose 可空表示「跟着实例能力走」。
 *
 * @param purpose 用途码
 * @param row     槽内主模型绑定行
 */
function openPurposePrompt(purpose: string, row: ModelBind) {
  purposePromptPurpose.value = purpose
  purposePromptBind.value = row
  purposePromptOpen.value = true
}

/**
 * 保存非 CHAT 用途的 L3 四列。只改提示词，不动用途/优先级/启停（原值回传）。
 * 成功后重新拉取，让回显与新模板版本同步。
 *
 * @param payload 四列 + 用途
 * @param done    弹窗结果回调；落库失败传 false，弹窗保持打开供修正
 */
async function savePurposePrompt(
  payload: PromptBinding & { purpose: string },
  done: (ok: boolean) => void
) {
  const row = purposePromptBind.value
  if (!row) {
    done(false)
    return
  }
  try {
    await updateModelBind(props.agentId, row.id, {
      purpose: row.purpose,
      priority: row.priority,
      status: row.status,
      prompt: {
        templateId: payload.templateId ?? null,
        templateVersion: payload.templateVersion ?? null,
        override: payload.override || {},
        promptExtra: payload.promptExtra || ''
      }
    })
    purposePromptOpen.value = false
    await load()
    emit('changed', changedPatch())
    done(true)
  } catch {
    // 后端硬闸（core 段 / 未知段 / 版本漂移）错误文案已由 request 拦截器提示，
    // 这里只保留弹窗，让用户改完后重试
    done(false)
  }
}

/** 按勾选顺序逐条创建工具绑定；告警合并提示。 */
async function addTool(toolItemIds: number[], done: () => void) {
  try {
    const warnings: string[] = []
    for (const toolItemId of toolItemIds) {
      const { data } = await createToolBind(props.agentId, { toolItemId })
      if (data.data.warning) {
        warnings.push(data.data.warning)
      }
    }
    if (warnings.length) {
      ElMessage.warning(warnings.join('；'))
    }
    toolOpen.value = false
    await load()
    emit('changed', changedPatch())
  } finally {
    done()
  }
}

/**
 * 工具绑定开关：目标工具停用或已删时开关不可点，避免把绑定开到不可用目标上。
 *
 * @param row    工具绑定行
 * @param enable 目标是否启用
 */
async function onToolSwitch(row: ToolBind, enable: boolean) {
  const next = enable ? 1 : 0
  if (row.status === next) {
    return
  }
  const { data } = await updateToolBind(props.agentId, row.id, next)
  if (data.data.warning) ElMessage.warning(data.data.warning)
  await load()
}

async function removeTool(row: ToolBind) {
  await deleteToolBind(props.agentId, row.id)
  await load()
  clampPager(toolPager, tools.value.length)
  emit('changed', changedPatch())
}

/** 按勾选顺序逐条创建技能绑定。 */
async function addSkill(skillIds: number[], done: () => void) {
  try {
    const warnings: string[] = []
    for (const skillId of skillIds) {
      const { data } = await createSkillBind(props.agentId, { skillId })
      if (data.data.warning) {
        warnings.push(data.data.warning)
      }
    }
    if (warnings.length) {
      ElMessage.warning(warnings.join('；'))
    }
    skillOpen.value = false
    await load()
    emit('changed', changedPatch())
  } finally {
    done()
  }
}

/**
 * 技能绑定开关：目标技能停用或已删时开关不可点。
 *
 * @param row    技能绑定行
 * @param enable 目标是否启用
 */
async function onSkillSwitch(row: SkillBind, enable: boolean) {
  const next = enable ? 1 : 0
  if (row.status === next) {
    return
  }
  const { data } = await updateSkillBind(props.agentId, row.id, next)
  if (data.data.warning) ElMessage.warning(data.data.warning)
  await load()
}

async function removeSkill(row: SkillBind) {
  await deleteSkillBind(props.agentId, row.id)
  await load()
  clampPager(skillPager, skills.value.length)
  emit('changed', changedPatch())
}

/**
 * 编排 Tab 的策略变更：写回 form.runtimeConfig，跟随页面底栏保存。
 * 这里只做归一化，不直接落库——保存仍由 save() 统一提交。
 *
 * @param config 子组件回传的完整运行配置
 */
function onOrchestrationConfig(config: AgentRuntimeConfig) {
  if (!form.value) {
    return
  }
  form.value.runtimeConfig = normalizeRuntimeConfig(config)
}

/**
 * 候选增删改/启停成功：重新拉详情与候选，刷新 routeCount 与关系图。
 * load() 默认保留未提交的编排草稿，不会把用户刚改的策略冲掉。
 */
async function onRoutesChanged() {
  await load()
  emit('changed', changedPatch())
}

</script>

<style scoped>
.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--el-text-color-placeholder);
  cursor: help;
}

.run-mode-tip {
  max-width: 320px;
  line-height: 1.55;
  font-size: 12px;
}

.run-mode-tip > div + div {
  margin-top: 6px;
}

/*
 * 详情壳：外层 .detail-pane 锁高后，这里整块吃满高度并纵向分区。
 * 高度链路：.detail-pane(flex) → .detail-root(flex:1) → .panel(flex:1) → .tabs(flex:1) → 内容区滚动。
 */
.detail-root {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  max-width: none;
  min-height: 0;
}

.panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  max-width: none;
  min-height: 0;
  margin-bottom: 0;
  padding: 16px;
  /* 锁住高度：只允许 Tab 内容区产生滚动条，页面本身不再被撑出全局滚动 */
  overflow: hidden;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  box-shadow: var(--ah-shadow);
}

/*
 * 底部操作栏：作为 .panel 的最后一个 flex 项常驻底部（shrink-0），
 * 不再用 position:sticky 覆盖内容。负外边距让白底/分割线铺满卡片内边距。
 */
.sticky-bar {
  display: flex;
  flex: none;
  justify-content: flex-end;
  gap: 8px;
  margin: 12px -16px -16px;
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid var(--ah-border);
  border-radius: 0 0 8px 8px;
}

/* Tab 壳：标题条定高，内容区吃满剩余高度并独立滚动 */
.tabs {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: 100%;
  max-width: none;
  min-height: 0;
}

.tabs :deep(.el-tabs__header) {
  flex: none;
}

/*
 * 只锁定「详情这一层」的 tab 内容区（直接子级）。
 * 编排 Tab 内部还有一层 el-tabs，若被一并设成滚动容器，会抢走候选表的
 * sticky 参照，导致表头不再吸顶。
 */
.tabs > :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: none;
  /* 列表/表单在卡片内局部滚动，鼠标悬停才显细长半透明滚动条 */
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.tabs > :deep(.el-tabs__content:hover) {
  scrollbar-color: rgba(107, 114, 128, 0.35) transparent;
}

.tabs > :deep(.el-tabs__content)::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.tabs > :deep(.el-tabs__content)::-webkit-scrollbar-track {
  background: transparent;
}

.tabs > :deep(.el-tabs__content)::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(107, 114, 128, 0.22);
}

.tabs > :deep(.el-tabs__content):hover::-webkit-scrollbar-thumb {
  background: rgba(107, 114, 128, 0.36);
}

.tabs > :deep(.el-tabs__content)::-webkit-scrollbar-thumb:hover {
  background: rgba(107, 114, 128, 0.5);
}

.tabs :deep(.el-tab-pane),
.tabs :deep(.el-form),
.tabs :deep(.el-table) {
  width: 100%;
  max-width: none;
}

/* 标题行不参与压缩：Tab 内容区内部滚动时标题始终可见 */
.detail-title {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.title-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.title-main h2 {
  margin: 0;
  overflow: hidden;
  font-size: 18px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code {
  flex: none;
  color: var(--ah-text-secondary);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 13px;
}

.talk-btn {
  flex: none;
  --el-button-bg-color: #3b82f6;
  --el-button-border-color: #3b82f6;
  --el-button-text-color: #fff;
  --el-button-hover-bg-color: #2563eb;
  --el-button-hover-border-color: #2563eb;
  --el-button-hover-text-color: #fff;
  --el-button-active-bg-color: #1d4ed8;
  --el-button-active-border-color: #1d4ed8;
  --el-button-active-text-color: #fff;
}

.tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.field-hint {
  display: inline-block;
  max-width: 36em;
  margin-left: 12px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.5;
  vertical-align: middle;
}

.tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.tab-sub {
  margin-left: 12px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

/* 标题行：标题 + Info 图标一组，说明文字在右 */
.tab-title {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 悬浮说明图标：低对比色，悬浮转主色 */
.rule-icon {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 14px;
  cursor: help;
  transition: color 0.15s ease;
}

.rule-icon:hover {
  color: var(--ah-primary, #635bff);
}

/* tooltip 内长句按行折行，避免撑成一条超长横条 */
.rule-tip {
  max-width: 420px;
  line-height: 1.6;
}

.tab-bar h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}

.block-alert {
  margin-bottom: 12px;
}

.keep-note {
  margin: 0;
  color: var(--ah-text-secondary);
  font-size: 13px;
}

.basic-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
}

/* 「允许被编排调用」：开关 + 一句帮助文案，整行占位避免挤坏两列栅格 */
.allow-orch {
  display: flex;
  align-items: center;
  gap: 10px;
}

.allow-orch__hint {
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.limits-hint {
  margin: 0 0 16px;
  color: var(--ah-text-secondary);
  font-size: 12px;
}

/* 计划执行专属配置块：与下方通用运行限制分隔，但同属「运行配置」Tab */
.plan-runtime {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--ah-border);
}

.plan-runtime-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 650;
  color: var(--ah-text-primary);
}

.plan-runtime-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.limits-form {
  max-width: 960px;
}

.limits-group {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--ah-border);
}

.limits-group:last-child {
  margin-bottom: 0;
  padding-bottom: 0;
  border-bottom: none;
}

.limits-group-title {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 650;
  color: var(--ah-text-primary);
}

.limits-grid {
  grid-template-columns: 1fr 1fr;
}

.limit-input {
  width: 100%;
}

.span-2 {
  grid-column: 1 / -1;
}

@media (max-width: 960px) {
  .limits-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 768px) {
  .basic-grid,
  .limits-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: auto;
  }
}

.gap {
  margin-left: 6px;
}

.bind-table {
  width: 100%;
}

/* 绑定表首列：中文名加粗 + 英文码灰色小字，与选择的展示口径一致 */
.bind-name {
  overflow: hidden;
  color: var(--ah-text-primary, #1f2937);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bind-code {
  margin-top: 2px;
  overflow: hidden;
  color: var(--ah-text-muted, #9ca3af);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Tab 标题后的数量徽标：弱化处理，只作提示不抢标题 */
.tab-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 9px;
  background: #f3f4f6;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  vertical-align: middle;
}

/* 绑定表分页：右对齐，与全站列表一致 */
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
