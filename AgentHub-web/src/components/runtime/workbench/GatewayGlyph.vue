<template>
  <!--
    智能网关静态图标（信号塔 + 网络节点风格）。
    纯前端矢量：随组件打包，不经 SSE 下发，也不复用 8 套专家机器人形象。
  -->
  <svg viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
    <path
      d="M12 20v-5"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
    />
    <circle cx="12" cy="13.4" r="1.5" fill="currentColor" />
    <path
      d="M8.2 10.2a5.4 5.4 0 0 1 7.6 0"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
    />
    <path
      d="M5.4 7.2a8.9 8.9 0 0 1 13.2 0"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      opacity="0.65"
    />
    <path
      d="M9 20h6"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
    />
  </svg>
</template>

<script setup lang="ts">
/**
 * 智能网关图标。所属域：runtime（前端）。
 *
 * <p>用独立静态图标把「网关判定」与「专家作答」在视觉上分开：
 * 网关不是某个 Agent，不能借用 8 套机器人形象，也不能由后端下发 SVG。
 */
</script>
