<template>
  <!--
    工作流卡片头像里的固定齿轮（单色线稿）。
    白底圆角方座由外层 `.wb-actor-avatar` 统一负责：这里只画图形，
    与专家头像（AgentLookArt 的机器人线稿）同一套「浅底 + 单色线框」口径。
  -->
  <svg class="workflow-gear-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <g
      transform="translate(12 12) scale(0.7) translate(-12 -12)"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path
        d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"
      />
      <circle cx="12" cy="12" r="3" />
    </g>
  </svg>
</template>

<script setup lang="ts">
/**
 * 工作流卡片头像：固定齿轮线稿。所属域：runtime（前端展示）。
 *
 * <p>为什么固定、且不读 lookOf：工作流不是某位专家，用户已拍板左侧头像恒为同一枚黑色齿轮；
 * 有没有保存形象都不影响它，也不拿彩色图标冒充专家头像。
 *
 * <p>颜色走 `currentColor`，由外层头像容器统一给深色；容器负责 36px 方座、9px 圆角与 1px 内描边，
 * 与消息流上方的入口 / 方案 / 专家头像完全对齐。纯展示，无状态、无副作用。
 */
</script>

<style scoped>
/* 撑满外层方座：尺寸与圆角由 `.wb-actor-avatar` 统一，这里只保证图形铺满 */
.workflow-gear-glyph {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
