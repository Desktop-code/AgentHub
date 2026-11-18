<template>
  <TrialPanel variant="workbench" :page-active="pageActive" />
</template>

<script setup lang="ts">
/**
 * 工作台。所属域：前端入口（登录默认落地页）。
 *
 * 唯一正式对话入口：左栏历史会话 + 右侧对话框，打开即可输入，不先选 Agent。
 * 具体状态与请求在 `useWorkbenchChat` / `WorkbenchChat`，本页只做挂载；
 * 不渲染 PageHeader，页签与主区留白由壳层按 `meta.hideTabs` 处理。
 *
 * KeepAlive 的失活钩子只派给缓存根（本组件，name=Workspace）。
 * 子树收不到 onDeactivated，所以这里记下前台状态再传下去，对齐评测页停表
 * 和工作流抽屉的 pageActive：切走只停表，不关页签、不清消息。
 */
import { onActivated, onDeactivated, ref } from 'vue'
import TrialPanel from '@/components/runtime/TrialPanel.vue'

/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致。 */
defineOptions({ name: 'Workspace' })

/** 本页是否在前台。初始为 true；失活置 false，再次进入置 true。 */
const pageActive = ref(true)

/** 切到其它菜单：缓存不卸载，通知子树停时钟、续订和 SSE。 */
onDeactivated(() => {
  pageActive.value = false
})

/** 切回工作台：子树按「本轮是否还没结束」决定要不要续上。 */
onActivated(() => {
  pageActive.value = true
})
</script>
