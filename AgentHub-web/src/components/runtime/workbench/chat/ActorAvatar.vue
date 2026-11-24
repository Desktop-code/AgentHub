<template>
  <!--
    参与者头像：白底方座 + 银圈。工作流固定黑齿轮，专家读配置形象，没配置走固定机器人。
    外圈银弧只在「这一位还在执行」时转；结束后留下整圈银边。
  -->
  <span class="wb-actor-avatar" :class="{ 'is-workflow': workflow, 'is-running': running }">
    <!-- 工作流卡固定黑色齿轮：不读形象配置，与列表里的工作流标识同一套方座 -->
    <WorkflowGearGlyph v-if="workflow" />
    <img
      v-else-if="look?.iconSrc"
      :src="look.iconSrc"
      :class="{ 'is-custom': look.custom }"
      alt=""
    />
    <AgentLookArt
      v-else-if="look?.art"
      :art="look.art"
      :color="look.initialColor"
    />
    <span v-else>{{ look?.initial || (fallbackName || '专').slice(0, 1) }}</span>
  </span>
</template>

<script setup lang="ts">
/**
 * 参与者头像。所属域：runtime（前端展示）。
 *
 * <p>职责：只画头像形态；形象配置解析、工作流判定与运行态仍在调用方投影里决定。
 * 工作流头像固定黑齿轮（不读 look），其余按已解析好的 look 渲染。
 */
import WorkflowGearGlyph from '@/components/runtime/workbench/WorkflowGearGlyph.vue'
import AgentLookArt from '@/components/agent/AgentLookArt.vue'
import type { AgentRobotShape } from '@/constants/agentRobotShapes'

defineProps<{
  /** 已解析好的形象视图；工作流时忽略 */
  look?: {
    iconSrc?: string
    art?: AgentRobotShape[] | null | null
    initial?: string
    initialColor?: string
    custom?: boolean
  } | null
  /** 是否工作流块（固定齿轮） */
  workflow?: boolean
  /** 是否仍在执行（银圈转） */
  running?: boolean
  /** 无 look 时的首字兜底来源 */
  fallbackName?: string
}>()
</script>

<style scoped>
.wb-actor-avatar {
  position: relative;
  display: grid;
  place-items: center;
  flex: none;
  box-sizing: border-box;
  width: 36px;
  height: 36px;
  overflow: hidden;
  border: 1.5px solid #c0c4cc;
  border-radius: 50%;
  background: #fff;
  font-size: 14px;
  font-weight: 650;
}

/* 还在执行：只转外圈的一段银弧，形象本身不转。结束后去掉这一层，留下整圈银边。 */
.wb-actor-avatar.is-running {
  border-color: transparent;
}

.wb-actor-avatar.is-running::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  border-radius: 50%;
  background: conic-gradient(#c0c4cc 0 28%, transparent 0 100%);
  mask: radial-gradient(farthest-side, transparent calc(100% - 1.5px), #000 calc(100% - 1.4px));
  animation: wb-avatar-spin 0.8s linear infinite;
  pointer-events: none;
}

.wb-actor-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 工作流头像：固定白底 + 黑色齿轮线稿，外圈与其它参与者同一只银圈。 */
.wb-actor-avatar.is-workflow {
  background: #fff;
  color: #1f2430;
}

@keyframes wb-avatar-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
