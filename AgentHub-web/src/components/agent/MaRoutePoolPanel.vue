<template>
  <!--
    路由池列表（入口与普通方案共用）。
    不再画 Vue Flow：增删改只在这张表里做，简介只读，来自目标自己的说明。
  -->
  <section class="form-block form-block--first">
    <div class="candidate-head">
      <h3 class="form-block__title candidate-head__title">{{ isEntry ? '对话入口路由池' : '路由池' }}</h3>
      <el-tooltip placement="top" :content="poolHint">
        <el-icon class="candidate-head__info"><QuestionFilled /></el-icon>
      </el-tooltip>
      <div class="candidate-head__add">
        <el-dropdown trigger="click" :disabled="!canEdit" @command="onAddCommand">
          <el-button size="small" type="primary" :disabled="!canEdit">
            添加
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="AGENT">单兵 Agent</el-dropdown-item>
              <el-dropdown-item command="MA">多智能体</el-dropdown-item>
              <el-dropdown-item command="WORKFLOW">工作流</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
    <el-table :data="candidates" class="candidate-table" size="small">
      <el-table-column label="目标" min-width="240">
        <template #default="{ row }">
          <div v-if="row.targetType === 'MA'" class="candidate-ma">
            <el-tag size="small" effect="plain">方案</el-tag>
            <el-select
              v-model="row.maDefId"
              class="candidate-ma__select"
              filterable
              clearable
              :disabled="!canEdit"
              placeholder="选择要入池的方案"
              @change="onCandidateMaChange(row)"
            >
              <el-option v-for="item in maOptionsOfRow(row)" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" />
            </el-select>
          </div>
          <div v-else-if="row.targetType === 'WORKFLOW'" class="candidate-ma">
            <el-tag size="small" type="success" effect="plain">工作流</el-tag>
            <el-select
              v-model="row.workflowDefId"
              class="candidate-ma__select"
              filterable
              clearable
              :disabled="!canEdit"
              placeholder="选择已发布工作流"
              @change="onCandidateWorkflowChange(row)"
            >
              <el-option
                v-for="item in workflowOptionsOfRow(row)"
                :key="item.id"
                :label="`${item.name}（${item.code}）`"
                :value="item.id"
              />
            </el-select>
          </div>
          <div v-else class="candidate-ma">
            <el-tag size="small" type="info" effect="plain">Agent</el-tag>
            <el-select
              v-model="row.agentId"
              class="candidate-ma__select"
              filterable
              :disabled="!canEdit"
              placeholder="选择 Agent"
              @change="onCandidateAgentChange(row)"
            >
              <el-option v-for="agent in agentOptionsOfRow(row)" :key="agent.id" :label="`${agent.name}（${agent.code}）`" :value="agent.id" />
            </el-select>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="简介" min-width="220">
        <template #default="{ row }">
          <!-- 只读：简介的唯一来源是目标自己的说明，这里不另编一份路由意图 -->
          <div v-if="row.intentDescMissing" class="candidate-intent candidate-intent--missing">
            <el-tag size="small" type="danger" effect="plain">未填说明</el-tag>
            <span>{{ intentMissingHint(row) }}</span>
          </div>
          <span v-else class="candidate-intent">{{ row.intentDesc || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="执行顺序" width="140" align="center">
        <template #default="{ row }">
          <el-input-number
            v-if="orderEditable"
            v-model="row.sortNo"
            :min="1"
            :max="9999"
            size="small"
            controls-position="right"
            :disabled="!canEdit"
          />
          <span v-else>{{ row.sortNo ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" :disabled="!canEdit" />
        </template>
      </el-table-column>
      <el-table-column label="移除" width="72" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :disabled="!canEdit" @click="onRemove($index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
/**
 * 路由池列表。所属域：前端 agent（方案编辑共享块）。
 *
 * <p>职责：用一张表维护候选。对话入口和普通方案都能加单兵 Agent、其它方案、已发布工作流。
 * 简介只展示目标自己的说明，不在行上另填「路由意图」。
 *
 * <p>边界：候选数组、可选列表、去重与校验留在 {@code ma/list}。本组件只调用父级回调。
 * 方案详情不再使用 Vue Flow；工作流画布仍使用 Vue Flow，许可声明留在仓库 LICENSE。
 */
import { computed } from 'vue'
import { ArrowDown, QuestionFilled } from '@element-plus/icons-vue'
import type { AgentCard } from '@/types/agent'
import type { MaCandidateRow } from '@/types/ma'
import type { ReceptionSchemeOption, ReceptionWorkflowOption } from '@/types/reception'

const props = withDefaults(
  defineProps<{
    /** 当前候选（含停用） */
    candidates?: MaCandidateRow[]
    /** 是否对话入口：文案略有不同，数据规则与普通方案相同 */
    isEntry?: boolean
    /** 是否允许编辑 */
    canEdit?: boolean
    /** 仅路由后串行可改执行顺序；并行时只读 */
    orderEditable?: boolean
    /** 单行可选 Agent */
    agentOptionsOfRow?: (row: MaCandidateRow) => AgentCard[]
    /** 单行可选方案 */
    maOptionsOfRow?: (row: MaCandidateRow) => ReceptionSchemeOption[]
    /** 单行可选工作流 */
    workflowOptionsOfRow?: (row: MaCandidateRow) => ReceptionWorkflowOption[]
    /** 加一行空专家 */
    onAddAgent?: () => void
    /** 加一行空方案 */
    onAddMa?: () => void
    /** 加一行空工作流 */
    onAddWorkflow?: () => void
    /** 移除第 index 行 */
    onRemove?: (index: number) => void
    /** 选中专家后同步只读简介 */
    onCandidateAgentChange?: (row: MaCandidateRow) => void
    /** 选中方案后同步只读简介 */
    onCandidateMaChange?: (row: MaCandidateRow) => void
    /** 选中工作流后同步只读简介 */
    onCandidateWorkflowChange?: (row: MaCandidateRow) => void
  }>(),
  {
    candidates: () => [],
    isEntry: false,
    canEdit: false,
    orderEditable: false,
    agentOptionsOfRow: () => [],
    maOptionsOfRow: () => [],
    workflowOptionsOfRow: () => [],
    onAddAgent: () => {},
    onAddMa: () => {},
    onAddWorkflow: () => {},
    onRemove: () => {},
    onCandidateAgentChange: () => {},
    onCandidateMaChange: () => {},
    onCandidateWorkflowChange: () => {}
  }
)

/**
 * 标题旁悬浮说明：候选从哪来、简介为什么只读、去重怎么做。
 */
const poolHint = computed(() =>
  props.isEntry
    ? '对话入口的路由池可加单兵 Agent、其它方案、已发布工作流。名称、编码、备注在基础信息里只读。简介只读，来自目标自己的说明。去重：同一专家既裸加入、又被某套入池方案包含时，保存保留方案、去掉裸专家。'
    : '路由池可加单兵 Agent、其它方案、已发布工作流。简介只读，来自目标自己的说明，供判路由引用。去重与运行相同：同一专家既裸加入、又被入池方案包含时，保存保留方案、去掉裸专家。执行顺序只在「路由后串行」里可改。'
)

/**
 * 分组添加：三条命令对应三类目标，不在表里再手填意图。
 *
 * @param command AGENT | MA | WORKFLOW
 */
function onAddCommand(command: string) {
  if (command === 'MA') props.onAddMa()
  else if (command === 'WORKFLOW') props.onAddWorkflow()
  else props.onAddAgent()
}

/**
 * 说明缺失时的去处提示：三类目标的真源不同。
 *
 * @param row 候选行
 * @return 中文提示
 */
function intentMissingHint(row: MaCandidateRow): string {
  if (row.targetType === 'MA') return '请到方案里补方案说明'
  if (row.targetType === 'WORKFLOW') return '请到工作流里补说明'
  return '请到 Agent 管理补上'
}
</script>

<style scoped>
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

.form-block__title::before {
  content: '';
  width: 3px;
  height: 14px;
  margin-right: 8px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.candidate-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.candidate-head__title {
  margin: 0;
  flex: none;
}

.candidate-head__info {
  flex: none;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 14px;
  cursor: help;
}

.candidate-head__add {
  display: flex;
  flex: none;
  gap: 8px;
  margin-left: auto;
}

.candidate-table {
  width: 100%;
}

.candidate-intent {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ah-text-secondary, #4b5563);
  font-size: 12px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.candidate-intent--missing {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-color-danger);
}

.candidate-ma {
  display: flex;
  align-items: center;
  gap: 8px;
}

.candidate-ma__select {
  flex: 1;
  min-width: 0;
}
</style>
