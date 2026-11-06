<template>
  <section class="child-approval">
    <div class="child-approval-head">
      <div class="child-approval-main">
        <div class="section-label">子专家计划待批准</div>
        <p class="child-approval-title">
          子专家「{{ pending.childAgentName || '子 Agent' }}」计划待批准
        </p>
        <p class="child-approval-sub">
          根运行已暂停（等待子计划批准）；同时仅允许一个待批子。批准后该子继续执行，拒绝则该子记为失败、其它子继续。
        </p>
      </div>
      <div class="child-approval-actions">
        <el-button type="primary" size="small" :loading="deciding" @click="emit('decide', true)">
          批准
        </el-button>
        <el-button size="small" :disabled="deciding" @click="emit('decide', false)">拒绝</el-button>
      </div>
    </div>
    <ol v-if="pending.steps?.length" class="child-plan-steps">
      <li v-for="step in pending.steps" :key="step.seq">
        <span class="child-plan-seq">{{ step.seq }}</span>
        <span class="child-plan-text">
          <strong>{{ step.title }}</strong>
          <span v-if="step.detail" class="child-plan-detail">{{ step.detail }}</span>
        </span>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
/**
 * 子专家计划待批准条。所属域：runtime（前端）。
 *
 * 职责：在 MA 试跑的「中栏协作流」与「对话栏」两处宿主里渲染同一条待批卡片
 * （MA 三栏与单列对话各自需要一份，原先是两份完全重复的模板）。
 *
 * 约束（结构边界）：
 * - **纯展示**：待批内容由父组件通过 SSE `ma_child_plan_pending` 或 child-pending 接口维护，
 *   本组件不请求、不持有状态，只把「批准 / 拒绝」意图 emit 回去；
 * - 同时只允许一个待批（后端单批准槽），因此父组件只渲染一条，本组件不做排队；
 * - 按钮文案与禁用语义与拆分前完全一致，不新增任何交互。
 */
import type { TrialChildPlanPendingChunk } from '@/api/runtime'

defineProps<{
  /** 当前待批准的子专家计划；父组件已保证非空时才渲染本组件 */
  pending: TrialChildPlanPendingChunk
  /** 批准/拒绝请求进行中：复用同一标记，避免连点重复消费同一份计划 */
  deciding: boolean
}>()

const emit = defineEmits<{
  /** 用户点了批准（true）或拒绝（false） */
  (e: 'decide', approved: boolean): void
}>()
</script>

<style scoped>
/* 子专家计划待批准：一条窄卡片，压在泳道与对话之间，不占聊天气泡位 */
.child-approval {
  margin-bottom: 10px;
  padding: 12px 14px;
  background: #fffdf6;
  border: 1px solid #f2d9a0;
  border-radius: 10px;
}

.child-approval-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.child-approval-main {
  min-width: 0;
}

.child-approval-title {
  margin: 4px 0 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ah-text, #1f2430);
}

.child-approval-sub {
  margin: 4px 0 0;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.55;
}

.child-approval-actions {
  display: flex;
  flex: none;
  gap: 8px;
}

.child-plan-steps {
  margin: 10px 0 0;
  padding-left: 0;
  list-style: none;
}

.child-plan-steps li {
  display: flex;
  gap: 8px;
  padding: 5px 0;
  border-top: 1px dashed #f0e3c6;
  font-size: 13px;
}

.child-plan-seq {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff3d6;
  color: #8a6116;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.child-plan-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.child-plan-detail {
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.5;
}
</style>
