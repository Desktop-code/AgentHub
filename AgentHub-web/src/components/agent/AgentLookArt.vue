<template>
  <!-- 白底圆角方座 + 线稿；线稿颜色由 color 驱动，背景恒为白色 -->
  <svg class="agent-look-art" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <!-- 只铺白底，轮廓由外层容器的内描边统一负责，避免双重 1px 边框 -->
    <rect x="0" y="0" width="24" height="24" rx="5.5" :fill="AGENT_AVATAR_BASE" />
    <g
      transform="translate(12 12) scale(0.7) translate(-12 -12)"
      :stroke="color"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <template v-for="(shape, index) in art" :key="index">
        <rect
          v-if="shape.t === 'rect'"
          :x="shape.x"
          :y="shape.y"
          :width="shape.w"
          :height="shape.h"
          :rx="shape.rx"
        />
        <path v-else-if="shape.t === 'path'" :d="shape.d" />
        <circle v-else :cx="shape.cx" :cy="shape.cy" :r="shape.r" />
      </template>
    </g>
  </svg>
</template>

<script setup lang="ts">
/**
 * Agent 机器人线稿渲染器。所属域：agent（前端展示组件）。
 * <p>
 * 不用静态 .svg + img：颜色必须能随 avatarConfig.color 变，img 改不了描边色。
 * 这里把图元内联成 SVG，背景固定白底圆角方座，线稿吃 color 属性。
 * 纯展示，无状态、无副作用。
 */
import { AGENT_AVATAR_BASE } from '@/constants/agentLooks'
import type { AgentRobotShape } from '@/constants/agentRobotShapes'

withDefaults(
  defineProps<{
    /** 线稿图元 */
    art: AgentRobotShape[]
    /** 线稿颜色 */
    color?: string
  }>(),
  { color: '#38bdf8' }
)
</script>

<style scoped>
/* 撑满外层方座：外层负责尺寸与圆角，这里只画内容 */
.agent-look-art {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
