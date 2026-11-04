<template>
  <!--
    紧凑型 Router 时间线单卡。所属域：runtime（展示层）。
    一个子 Agent 只渲染这一张卡：派发时是「运行中」，返回后原地变绿并展开摘要，
    不会再生成第二张「返回」卡，避免把聊天流瞬间顶飞。
  -->
  <article class="tl-card" :class="[`is-${kind}`, `is-${status}`]">
    <header class="tl-card__head">
      <!-- 状态点同时承担「时间线节点」语义，不额外画左侧导轨，压缩高度 -->
      <span class="tl-card__dot" aria-hidden="true" />
      <span class="tl-card__title" :title="title">{{ title }}</span>
      <span class="tl-card__state">
        <el-icon v-if="status === 'running'" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="status === 'success'"><CircleCheckFilled /></el-icon>
        <el-icon v-else-if="status === 'error'"><CircleCloseFilled /></el-icon>
        <span>{{ statusText }}</span>
      </span>
    </header>

    <!-- 运行中只留单行状态；结束后原地展开摘要，最多 3 行 -->
    <p v-if="summary && status !== 'running'" class="tl-card__summary" :title="summary">{{ summary }}</p>
  </article>
</template>

<script setup lang="ts">
/**
 * 紧凑型 Router 时间线单卡。所属域：runtime（展示层）。
 *
 * <p>职责：只负责一张卡的视觉与状态文案；节点数据由父组件 {@link RouterTimeline.vue}
 * 从 obs span 树的泳道行归纳后传入。
 *
 * <p>关键约束：
 * - 边框颜色随 props.status 变化，CSS transition 0.3s，避免「运行中 → 成功」生硬闪烁；
 * - 不展示时间戳，摘要限制 3 行；
 * - 同一 key 下只原地改状态，不重建卡片，保证聊天滚动位置不跳。
 */
import { computed } from 'vue'
import { CircleCheckFilled, CircleCloseFilled, Loading } from '@element-plus/icons-vue'
import type { TimelineCardKind, TimelineCardStatus } from '@/components/runtime/routerTimeline'

const props = withDefaults(
  defineProps<{
    /** 节点名称：Router 分析 / 子 Agent 名 / 汇总 */
    title: string
    /** 卡片类型：决定基础配色（Router/汇总紫色，子 Agent 按状态） */
    kind?: TimelineCardKind
    /** 当前状态：原地流转，父组件只需改这个值 */
    status?: TimelineCardStatus
    /** 返回文本摘要；最多展示 3 行 */
    summary?: string
  }>(),
  {
    kind: 'child',
    status: 'success',
    summary: ''
  }
)

/** 状态 → 中文短标签。运行中带省略号，和静态「成功」区分开。 */
const statusText = computed(() => {
  if (props.status === 'running') {
    return '运行中...'
  }
  if (props.status === 'success') {
    return '成功'
  }
  if (props.status === 'error') {
    return '失败'
  }
  return '已取消'
})
</script>

<style scoped>
/* 基础卡：12px 内边距 + 13px 标题，整体高度比旧泳道卡小一半 */
.tl-card {
  padding: 12px;
  border: 1px solid #e6e8ef;
  border-radius: 10px;
  background: #fff;
  /* 状态原地流转时，只让边框/底色平滑变化；不改布局，聊天流不会抖 */
  transition:
    border-color 0.3s ease,
    background-color 0.3s ease,
    box-shadow 0.3s ease;
  /* 新卡出现：保留 translateY 淡入，不新增位移以外的动画 */
  animation: tl-card-in 0.24s ease-out both;
}

.tl-card__head {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

/* 时间线节点圆点：颜色跟随状态，尺寸压到 6px，不占额外高度 */
.tl-card__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #98a2b3;
  transition: background-color 0.3s ease, box-shadow 0.3s ease;
}

.tl-card__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右侧状态：12px，图标 + 文案；运行中图标由 Element Plus 的 is-loading 旋转 */
.tl-card__state {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 4px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.4;
}

/* 正文摘要：12px、最多 3 行；超出省略，避免长返回把时间线撑爆 */
.tl-card__summary {
  display: -webkit-box;
  margin: 6px 0 0;
  overflow: hidden;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

/* ---------- 子 Agent：运行中黄色 ---------- */
.tl-card.is-child.is-running {
  border-color: #f2c46d;
  background: #fffdf6;
}

.tl-card.is-child.is-running .tl-card__dot {
  background: #e6a23c;
  box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.16);
}

.tl-card.is-child.is-running .tl-card__state {
  color: #b7791f;
}

/* ---------- 子 Agent：成功原地变绿 ---------- */
.tl-card.is-child.is-success {
  border-color: #b7e4c7;
  background: #f7fdf9;
}

.tl-card.is-child.is-success .tl-card__dot {
  background: #12b76a;
  box-shadow: 0 0 0 3px rgba(18, 183, 106, 0.12);
}

.tl-card.is-child.is-success .tl-card__state {
  color: #0e9f6e;
}

/* ---------- 子 Agent：失败 / 取消 ---------- */
.tl-card.is-child.is-error {
  border-color: #f5b5b0;
  background: #fff8f7;
}

.tl-card.is-child.is-error .tl-card__dot {
  background: #d92d20;
  box-shadow: 0 0 0 3px rgba(217, 45, 32, 0.12);
}

.tl-card.is-child.is-error .tl-card__state {
  color: #d92d20;
}

.tl-card.is-child.is-cancelled {
  border-color: #d7dbe3;
  background: #fafbfc;
}

.tl-card.is-child.is-cancelled .tl-card__dot {
  background: #98a2b3;
}

/* ---------- Router 分析 / 最终汇总：紫色边框 ---------- */
.tl-card.is-router,
.tl-card.is-aggregate {
  border-color: #d3ceff;
  background: #fbfaff;
}

.tl-card.is-router .tl-card__dot,
.tl-card.is-aggregate .tl-card__dot {
  background: var(--ah-primary, #635bff);
  box-shadow: 0 0 0 3px rgba(99, 91, 255, 0.12);
}

.tl-card.is-router.is-running .tl-card__state,
.tl-card.is-aggregate.is-running .tl-card__state {
  color: var(--ah-primary, #635bff);
}

/* Router/汇总自身失败时仍要让红色可辨，不被紫色基础色吃掉 */
.tl-card.is-router.is-error,
.tl-card.is-aggregate.is-error {
  border-color: #f5b5b0;
  background: #fff8f7;
}

.tl-card.is-router.is-error .tl-card__dot,
.tl-card.is-aggregate.is-error .tl-card__dot {
  background: #d92d20;
  box-shadow: 0 0 0 3px rgba(217, 45, 32, 0.12);
}

.tl-card.is-router.is-error .tl-card__state,
.tl-card.is-aggregate.is-error .tl-card__state {
  color: #d92d20;
}

@keyframes tl-card-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 尊重系统「减少动效」：关掉淡入与边框过渡，避免前庭敏感用户不适 */
@media (prefers-reduced-motion: reduce) {
  .tl-card,
  .tl-card__dot {
    animation: none;
    transition: none;
  }
}
</style>
