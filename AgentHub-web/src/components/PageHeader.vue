<template>
  <!-- 极简工具栏：直接铺在浅灰页底，无背景/边框/阴影，仅承载标题、说明与页面专属操作 -->
  <div class="ph-toolbar">
    <!-- 左侧：标题与说明同行；说明超长省略，避免把右侧按钮挤走 -->
    <div class="ph-left">
      <h2 class="ph-title">{{ title }}</h2>
      <span v-if="description" class="ph-desc" :title="description">{{ description }}</span>
    </div>

    <!-- 右侧：页面专属按钮（重算 / 新建等）。无内容时不占位，标题自然靠左 -->
    <div v-if="$slots.extra" class="ph-extra">
      <slot name="extra" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 全局页头工具栏。所属域：前端壳（components 公共组件）。
 * 职责：替代原「白底大标题卡」，输出一行紧凑的标题 + 说明 + 右侧操作区。
 * 约束：容器不画背景/边框/阴影，底色由布局层 .main 的浅灰底透出；
 *      页面专属按钮必须走 #extra 插槽，不要塞进 title 造成语义混淆。
 */
defineProps<{
  /** 页面标题，16px 加粗 */
  title: string
  /** 页面说明，13px 浅灰，与标题同行；不传则不渲染 */
  description?: string
}>()
</script>

<style scoped>
.ph-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  /* 与下方筛选条 / 白卡保持统一节奏 */
  margin: 0 0 12px;
  padding: 0;
  background: transparent;
  border: none;
  box-shadow: none;
  min-width: 0;
}

.ph-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.ph-title {
  flex: none;
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--ah-text-primary);
  letter-spacing: 0;
}

/* 单行省略：工具栏高度恒定，不因长文案换行而撑高 */
.ph-desc {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.4;
  color: var(--ah-text-secondary);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.ph-extra {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

/* 窄屏：说明换行并允许折行，按钮不被压扁 */
@media (max-width: 900px) {
  .ph-left {
    flex-wrap: wrap;
    gap: 4px 8px;
  }

  .ph-desc {
    white-space: normal;
  }
}
</style>
