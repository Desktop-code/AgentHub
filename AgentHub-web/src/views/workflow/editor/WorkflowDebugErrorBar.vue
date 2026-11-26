<template>
  <!--
    调试框顶部的错误悬浮条：只复述这次 run 的失败原因。
    节点上的原因与标题右侧的终态仍保留，这条不再挂在卡片最底下。
  -->
  <div class="wf-debug-error-bar" role="alert">
    <el-icon class="wf-debug-error-bar__icon"><WarningFilled /></el-icon>
    <span class="wf-debug-error-bar__text">{{ message }}</span>
  </div>
</template>

<script setup lang="ts">
/**
 * 调试框顶部错误悬浮条。所属域：workflow 的前端编辑页。
 *
 * <p>只做展示，不请求接口、不改投影：文案由父层从 run 的 errorSummary 收口后传进来。
 * 粘在过程区滚动容器顶部，卡片再长、往下滚也仍停在上方可见位置。
 * 只用在调试悬浮框（调试 / 记录两页），不进对话页，也不画进共用的 `WorkflowRunCard`。
 */
import { WarningFilled } from '@element-plus/icons-vue'

defineProps<{
  /** 要复述的错误原因；空串时由父层 v-if 直接不挂本组件 */
  message: string
}>()
</script>

<style scoped>
/* 悬浮条：粘在滚动容器顶部，白底内容从下方滚过时不跟着滚走 */
.wf-debug-error-bar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  box-shadow: 0 2px 8px rgba(220, 38, 38, 0.08);
  color: #991b1b;
  font-size: 13px;
  line-height: 1.6;
}

/* 图标与第一行文字对齐，长原因换行时不悬空 */
.wf-debug-error-bar__icon {
  flex: none;
  margin-top: 2px;
  font-size: 14px;
}

/* 原因可能带节点名与一句话，允许换行，不撑破调试框宽度 */
.wf-debug-error-bar__text {
  min-width: 0;
  word-break: break-word;
}
</style>
