<template>
  <section v-if="tasks.length" class="wf-debug-human">
    <div class="wf-debug-human__title">人工待办</div>
    <el-alert
      v-if="!canAct"
      type="warning"
      :closable="false"
      show-icon
      title="没有 workflow:human:act 权限，只能查看不能处理。"
    />
    <el-alert
      v-else-if="mine.length"
      type="warning"
      :closable="false"
      show-icon
      :title="`有 ${mine.length} 条待我处理；处理后会同步续跑或按错误边收口。`"
    >
      <template #default>
        <el-button link type="primary" @click="emit('todo')">去待办中心</el-button>
      </template>
    </el-alert>
    <div v-for="task in tasks" :key="task.id" class="wf-debug-human__item">
      <div class="wf-debug-human__head">
        <b>节点 {{ task.nodeCode }}</b>
        <el-tag v-if="canHandle(task)" size="small" type="warning" effect="plain">待我处理</el-tag>
        <el-tag v-else-if="canAct" size="small" effect="plain">非处理人</el-tag>
        <span>规则：{{ describeAssigneeRule(task.assigneeRule) }}</span>
        <span>超时策略：{{ timeoutLabel(task.timeoutPolicy) }}</span>
        <span v-if="task.expireTime">到期：{{ formatTime(task.expireTime) }}</span>
      </div>
      <el-input
        v-model="note"
        type="textarea"
        :rows="2"
        maxlength="512"
        placeholder="处理备注（可空，最多 512 字）"
        :disabled="acting || !canHandle(task)"
      />
      <div class="wf-debug-human__actions">
        <el-button
          type="success"
          size="small"
          :loading="acting"
          :disabled="!canHandle(task) || acting"
          @click="emit('decide', task.id, true, note)"
        >
          通过并续跑
        </el-button>
        <el-button
          type="danger"
          size="small"
          plain
          :loading="acting"
          :disabled="!canHandle(task) || acting"
          @click="emit('decide', task.id, false, note)"
        >
          驳回
        </el-button>
      </div>
      <div v-if="canAct && !canHandle(task)" class="wf-debug-human__tip">
        你不是该节点处理人（规则：{{ describeAssigneeRule(task.assigneeRule) }}），无法通过或驳回。
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 调试框里的人工待办。所属域：workflow 的前端。
 *
 * <p>通过 / 驳回仍走现成待办接口。能不能点只做提示，越权由后端兜底。
 */
import { computed, ref } from 'vue'
import type { WorkflowHumanTaskRow } from '@/types/workflow'
import { describeAssigneeRule, matchesAssigneeRule } from '@/utils/workflowAssignee'

const props = defineProps<{
  tasks: WorkflowHumanTaskRow[]
  /** 是否持有 workflow:human:act */
  canAct: boolean
  acting: boolean
  userId: number | null
  roles: string[]
}>()

const emit = defineEmits<{
  decide: [taskId: number, approved: boolean, note: string]
  todo: []
}>()

const note = ref('')

const mine = computed(() => props.tasks.filter((task) => canHandle(task)))

/**
 * 当前用户是否命中这条待办的处理人规则。
 *
 * @param task 待办行
 * @return true 表示按钮可点
 */
function canHandle(task: WorkflowHumanTaskRow): boolean {
  if (!props.canAct) {
    return false
  }
  return matchesAssigneeRule(task.assigneeRule, props.userId, props.roles)
}

/**
 * 超时策略的中文名。未知值原样显示，避免编造成另一种策略。
 *
 * @param value 策略码
 * @return 展示文案
 */
function timeoutLabel(value?: string | null): string {
  const map: Record<string, string> = {
    AUTO_REJECT: '超时自动拒绝',
    AUTO_APPROVE: '超时自动通过',
    ESCALATE: '超时升级（本刀按拒绝收口）'
  }
  return value ? map[value] || value : '—'
}

/**
 * 后端时间截到秒。空值用占位，不显示 Invalid Date。
 *
 * @param value 时间字符串
 * @return 展示文本
 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.wf-debug-human {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.wf-debug-human__title {
  font-weight: 600;
}

.wf-debug-human__item {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
}

.wf-debug-human__head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.wf-debug-human__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.wf-debug-human__tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
