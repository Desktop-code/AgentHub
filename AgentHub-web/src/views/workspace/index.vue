<template>
  <TrialPanel variant="workbench" />
</template>

<script setup lang="ts">
/**
 * 工作台。所属域：前端入口（登录默认落地页）。
 *
 * 唯一正式对话入口：左栏历史会话 + 右侧对话框，打开即可输入，不先选 Agent。
 * 具体状态与请求在 `useWorkbenchChat` / `WorkbenchChat`，本页只做挂载；
 * 不渲染 PageHeader，页签与主区留白由壳层按 `meta.hideTabs` 处理。
 */
import TrialPanel from '@/components/runtime/TrialPanel.vue'

/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致。 */
defineOptions({ name: 'Workspace' })
</script>
