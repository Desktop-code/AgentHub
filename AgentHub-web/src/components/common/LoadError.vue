<template>
  <!--
    请求失败态：短标题 + 失败原因 + 重试。
    与「还没有 xxx」空态互斥：失败时由调用方用它替换空态，不能让两者同时出现。
  -->
  <div class="load-error" :class="{ 'is-center': center }" role="alert">
    <el-icon class="load-error__icon" :size="18"><WarningFilled /></el-icon>
    <div class="load-error__text">
      <p class="load-error__title">{{ title }}</p>
      <p v-if="reason" class="load-error__reason">{{ reason }}</p>
    </div>
    <el-button class="load-error__retry" size="small" :loading="retrying" @click="emit('retry')">
      重试
    </el-button>
  </div>
</template>

<script setup lang="ts">
/**
 * 列表 / 详情请求失败块。所属域：前端壳（components 公共组件）。
 *
 * 职责：把「加载失败」从 el-alert 顶条或 el-empty 空态里拆出来，给统一短标题、原因与重试入口。
 * 约束：只表达失败，不承载「没有数据」「没有权限」「筛选无结果」——那些由页面各自给文案；
 *      `center` 仅用于占据整块列表区域（网格 / 详情空壳）时的居中对齐。
 */
import { WarningFilled } from '@element-plus/icons-vue'

defineProps<{
  /** 短标题，例如「Agent 列表加载失败」 */
  title: string
  /** 失败原因，可空；有值时第二行展示，不塞长堆栈 */
  reason?: string
  /** 重试请求进行中，用于按钮 loading 并防连点 */
  retrying?: boolean
  /** 是否在整块空白区域居中（网格 / 详情壳用），默认左对齐贴顶 */
  center?: boolean
}>()

const emit = defineEmits<{
  /** 用户点「重试」：由调用方重新发起同一请求 */
  (e: 'retry'): void
}>()
</script>

<style scoped>
.load-error {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-height: 72px;
  padding: 14px 16px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
}

/* 居中态：占满列表区域并纵向居中，避免错误条孤零零贴在角落 */
.load-error.is-center {
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  text-align: center;
}

.load-error__icon {
  flex: none;
  color: #dc2626;
}

.load-error__text {
  min-width: 0;
  flex: 1;
}

.load-error__title {
  margin: 0;
  color: #b91c1c;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}

.load-error__reason {
  margin: 2px 0 0;
  color: #7f1d1d;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

.load-error__retry {
  flex: none;
}

/* 居中态下按钮另起一行，与文案拉开距离 */
.load-error.is-center .load-error__retry {
  margin-top: 10px;
}
</style>
