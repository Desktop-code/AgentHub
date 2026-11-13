<template>
  <!-- 单根节点，便于 class / data-* / 点击事件透传；选中态由外部挂 is-selected -->
  <article class="config-card" :class="[`is-${size}`]" tabindex="0">
    <header v-if="$slots.header" class="config-card__header">
      <slot name="header" />
    </header>
    <!-- 中间区占满剩余高度，描述最多两行省略 -->
    <div class="config-card__body">
      <div class="config-card__body-inner">
        <slot />
      </div>
    </div>
    <footer v-if="$slots.footer" class="config-card__footer" @click.stop>
      <slot name="footer" />
    </footer>
  </article>
</template>

<script setup lang="ts">
/**
 * 配置类业务卡片壳（模型供应商 / 工具源 / Skill / Agent 网格等共用）。
 * 所属域：前端壳。
 * Flex 竖排 + 固定高度；header / 默认正文（两行截断）/ footer；外观与选中态由本组件统一。
 * 选中：外部传入 class `is-selected`（不新增 prop，避免改对外接口）。
 */
withDefaults(
  defineProps<{
    /**
     * 卡片高度档：
     * - normal：160px（模型 / 工具 / Skill）
     * - small：120px（Agent 紧凑态）
     */
    size?: 'normal' | 'small'
  }>(),
  {
    size: 'normal'
  }
)
</script>

<style scoped>
.config-card {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  /* 防止作为 grid/flex 子项时被内容 min-height:auto 撑开 */
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  padding: 0;
  overflow: hidden;
  background: #fff;
  /* 可点业务卡：无生硬描边，轻阴影分层；hover 才轻微上浮 */
  border: none;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  cursor: pointer;
  /*
   * 只过渡合成/绘制成本低的属性；禁止 transition: all ——
   * 会把 width/height/padding 一并纳入过渡，网格列宽变化时逐帧重排。
   */
  transition:
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

/* 高度绝对死锁：min/max/height 同值，禁止被内部文本撑高 */
.config-card.is-normal {
  height: 160px;
  min-height: 160px;
  max-height: 160px;
}

.config-card.is-small {
  height: 120px;
  min-height: 120px;
  max-height: 120px;
}

/* 悬浮：轻微上浮 + 略加深阴影（可点卡才有，静态表格容器勿仿） */
.config-card:hover,
.config-card:focus-visible {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  outline: none;
}

/* 选中：极浅紫底 + 品牌色轻阴影 */
.config-card.is-selected {
  background-color: #f4f4ff;
  box-shadow: 0 1px 3px rgba(99, 91, 255, 0.12);
}

.config-card.is-selected:hover,
.config-card.is-selected:focus-visible {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(99, 91, 255, 0.16);
}

.config-card__header {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 0;
  min-width: 0;
  overflow: hidden;
  color: #303133;
}

/* 标题区：加深加粗；单行截断，避免标题换行抬高整卡 */
.config-card__header :deep(.card-name),
.config-card__header :deep(h3),
.config-card__header :deep(strong) {
  min-width: 0;
  overflow: hidden;
  color: #303133;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 中间描述区：吃掉剩余高度，超出裁切 */
.config-card__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  padding: 8px 14px;
}

/* 描述文本：严格两行截断；min-height 锁满两行行高，
   使「暂无简介」与超长简介的正文起点/占位一致，卡片底边绝对齐平 */
.config-card__body-inner {
  display: -webkit-box;
  min-height: calc(1.5em * 2);
  max-height: calc(1.5em * 2);
  overflow: hidden;
  color: #606266;
  font-size: 14px;
  line-height: 1.5;
  text-overflow: ellipsis;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.config-card__body-inner :deep(*) {
  max-width: 100%;
  color: inherit;
  font-size: inherit;
}

/* 底部信息区：margin-top:auto 钉在卡片最下沿，高度固定保证同行对齐 */
.config-card__footer {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 4px 8px;
  height: 36px;
  margin-top: auto;
  padding: 0 10px;
  overflow: hidden;
  background: #fafafa;
  border-top: 1px solid var(--ah-border-soft, #f2f6fc);
  color: #909399;
}

.config-card__footer :deep(.meta),
.config-card__footer :deep(.card-meta),
.config-card__footer :deep(.version) {
  color: #909399;
}
</style>
