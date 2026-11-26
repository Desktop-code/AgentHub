<template>
  <div class="wf-debug-transcript">
    <div v-if="asked" class="wf-debug-transcript__user">{{ asked }}</div>

    <div v-if="!run" class="wf-debug-transcript__empty">{{ DEBUG_EMPTY_HINT }}</div>

    <div v-else-if="view && participant" class="chat is-workbench wf-debug-transcript__card">
      <WorkflowRunCard
        :turn="participant.turn"
        :card="participant.card"
        :hold="hold"
        :choice-enabled="choiceEnabled"
        :page-active="pageActive"
        :workbench="true"
        @choose="emit('choose', $event.label)"
        @other="emit('other')"
      />
    </div>

    <div v-else class="wf-debug-transcript__empty">过程还没就绪，稍后再看这一条。</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 调试过程区。所属域：workflow 的前端。
 *
 * <p>只渲染用户那一句和首页同一张工作流卡。没有卡片载荷时不拿节点状态另画时间轴。
 */
import { computed } from 'vue'
import WorkflowRunCard from '@/components/runtime/workbench/chat/WorkflowRunCard.vue'
import type { WorkflowRunDetail } from '@/types/workflow'
import { asWorkflowView, DEBUG_EMPTY_HINT, debugHold, debugParticipant } from './workflowDebugCard'

const props = defineProps<{
  /** 当前试跑详情；还没开始为空 */
  run: WorkflowRunDetail | null
  /** 开始时的那一句，续跑回复不另起气泡 */
  asked: string
  /** 停在等待回复且没有请求在飞时，选项才可点 */
  choiceEnabled: boolean
  /** 宿主页是否在前台，传给卡片停掉时限秒表 */
  pageActive: boolean
}>()

const emit = defineEmits<{
  /** 点选项：回传选项文案，由调试框送回同一条 run */
  choose: [label: string]
  /** 点「其它」：只聚焦输入框 */
  other: []
}>()

const view = computed(() => asWorkflowView(props.run?.card))
const participant = computed(() => (props.run && view.value ? debugParticipant(view.value, props.run.id) : null))
const hold = computed(() => debugHold(props.run?.status))
</script>

<style scoped>
.wf-debug-transcript {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 180px;
}

.wf-debug-transcript__user {
  align-self: flex-end;
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 12px 12px 4px 12px;
  background: #635bff;
  color: #fff;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.wf-debug-transcript__empty {
  padding: 28px 8px;
  color: var(--el-text-color-secondary);
  text-align: center;
  line-height: 1.6;
}

.wf-debug-transcript__card :deep(.wf-card) {
  max-width: 100%;
}
</style>
