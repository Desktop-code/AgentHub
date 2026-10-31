<template>
  <!--
    主从右栏占位骨架。所属域：agent。
    仅 Agent 列表页在飞卡期间使用；纯静态，禁止发起任何请求、禁止读写业务状态。
    存在的意义：把 AgentDetail 的 6 个并发接口与表单/表格首帧渲染
    推迟到飞卡结束之后，避免这些重活挤进动画帧。

    实现说明：刻意不用 el-skeleton / el-skeleton-item。
    el-skeleton-item 的底色来自 var(--el-skeleton-color)，该变量只定义在
    .el-skeleton 根节点上；一旦包裹层缺失或被宿主样式重置，色块会渲染成
    完全透明，整块骨架「看不见」，右栏表现为一片空白。纯 CSS 灰块没有
    任何外部变量依赖，不会出现这种失败模式。
  -->
  <div class="skeleton-root" aria-busy="true" aria-live="polite">
    <section class="skeleton-panel">
      <!-- 标题行：对应真详情的 h2 + 编码 + 右侧「对话」按钮 -->
      <header class="sk-title">
        <div class="sk-title-main">
          <span class="sk-block sk-title-text" />
          <span class="sk-block sk-code-text" />
        </div>
        <span class="sk-block sk-btn" />
      </header>

      <!-- Tab 头条：对应基本信息 / 路由 / 模型 / 工具… 的等宽占位 -->
      <div class="sk-tabs">
        <span v-for="n in 4" :key="n" class="sk-block sk-tab" />
      </div>

      <!-- 表单区：两列栅格，行数与基本信息一致，避免骨架与真内容高度突变 -->
      <div class="sk-form">
        <div v-for="n in 6" :key="n" class="sk-field">
          <span class="sk-block sk-label" />
          <span class="sk-block sk-input" />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 右栏骨架屏。所属域：agent。
 * 无 props / 无 emits / 无副作用；尺寸与 .detail-pane 内真详情保持一致，
 * 保证骨架 → 真内容交接时右栏高度不塌陷、不触发主区重排。
 */
</script>

<style scoped>
.skeleton-root {
  display: flex;
  flex: 1 0 auto;
  flex-direction: column;
  width: 100%;
  max-width: none;
  min-height: 0;
}

/* 与 AgentDetail 的 .panel 同款外壳，交接时边框位置不跳 */
.skeleton-panel {
  display: flex;
  flex: 1 0 auto;
  flex-direction: column;
  width: 100%;
  min-height: 420px;
  padding: 16px;
  background: var(--ah-surface, #fff);
  border: 1px solid var(--ah-border, #ebeef5);
  border-radius: 8px;
  box-shadow: var(--ah-shadow, 0 2px 12px 0 rgba(0, 0, 0, 0.04));
}

/* 灰块本体：实心底色 + 微光扫过，不依赖任何外部 CSS 变量 */
.sk-block {
  display: block;
  border-radius: 4px;
  background-color: #eef0f4;
  background-image: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0) 0%,
    rgba(255, 255, 255, 0.65) 50%,
    rgba(255, 255, 255, 0) 100%
  );
  background-size: 200% 100%;
  background-repeat: no-repeat;
  animation: sk-shimmer 1.4s ease-in-out infinite;
}

@keyframes sk-shimmer {
  from {
    background-position: 120% 0;
  }
  to {
    background-position: -120% 0;
  }
}

.sk-title {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  min-height: 32px;
}

.sk-title-main {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.sk-title-text {
  width: 42%;
  height: 20px;
}

.sk-code-text {
  width: 24%;
  height: 14px;
}

.sk-btn {
  flex: none;
  width: 72px;
  height: 32px;
  border-radius: 6px;
}

.sk-tabs {
  display: flex;
  flex: none;
  gap: 24px;
  padding-bottom: 12px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--ah-border-soft, #f2f6fc);
}

.sk-tab {
  width: 64px;
  height: 16px;
}

/* 两列栅格与基本信息表单一致，减少骨架→真内容的视觉位移 */
.sk-form {
  display: grid;
  flex: 1 0 auto;
  align-content: start;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px 24px;
  width: 100%;
}

.sk-field {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.sk-label {
  flex: none;
  width: 72px;
  height: 14px;
}

.sk-input {
  flex: 1;
  height: 32px;
  min-width: 0;
}

/* 窄屏（左栏 + 详情挤压）时退化为单列，与真详情表单换行行为对齐 */
@media (max-width: 960px) {
  .sk-form {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* 尊重系统「减弱动态效果」：关掉微光，保留静态灰块 */
@media (prefers-reduced-motion: reduce) {
  .sk-block {
    animation: none;
  }
}
</style>
