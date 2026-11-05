<template>
  <!--
    MA 运行页中栏协作卡。所属域：runtime（展示层）。
    子 Agent 卡只有名称 + 状态 + 耗时，不出现任何正文；
    路由卡补一行命中/派发名单，结论卡补一段（≤3 行）摘要。
    左侧 4px 色条与右栏日志节点、图例用同一套角色色。
  -->
  <article
    class="ma-card"
    :class="[`is-${card.kind}`, `is-${card.status}`]"
    :style="{ '--ma-color': card.color }"
  >
    <span class="ma-card__bar" aria-hidden="true" />
    <div class="ma-card__main">
      <header class="ma-card__head">
        <span class="ma-card__dot" aria-hidden="true" />
        <span class="ma-card__title" :title="card.title">{{ card.title }}</span>
        <!-- 运行中给转圈，一眼看出这一步还没结束；完成后换成状态文字 -->
        <el-icon v-if="card.status === 'running'" class="ma-card__spin is-loading"><Loading /></el-icon>
        <span class="ma-card__state" :class="`is-${card.status}`">{{ statusText }}</span>
        <span v-if="timeText" class="ma-card__time">{{ timeText }}</span>
      </header>
      <!-- 只有路由卡与结论卡有摘要；子 Agent 卡保持一行，扫读不中断 -->
      <p v-if="card.summary" class="ma-card__summary" :title="card.summary">{{ card.summary }}</p>
    </div>
  </article>
</template>

<script setup lang="ts">
/**
 * MA 运行页中栏协作卡。所属域：runtime（展示层）。
 *
 * <p>职责：一张卡只承载一个协作节点（路由 / 子 Agent / 结论）。
 * 约束：不渲染思考、工具调用与工具结果；子 Agent 卡不接摘要，摘要由父层归纳好后传入。
 */
import { computed } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import type { MaRunCard } from '@/components/runtime/ma/maRunView'
import { formatDuration } from '@/components/runtime/ma/maRunView'

const props = defineProps<{
  /** 卡片数据；状态变化由父层原地替换对象 */
  card: MaRunCard
}>()

/** 状态文案：每张卡都必须能一眼看出「在跑 / 完成 / 失败」 */
const statusText = computed(() => {
  if (props.card.status === 'running') {
    return '运行中'
  }
  if (props.card.status === 'error') {
    return '失败'
  }
  if (props.card.status === 'cancelled') {
    return '已取消'
  }
  return '完成'
})

/** 右上角耗时：运行中给「进行中」，结束后给「耗时」；拿不到时间就不占位 */
const timeText = computed(() => {
  const duration = formatDuration(props.card.durationMs)
  if (props.card.status === 'running') {
    return duration ? `进行中 ${duration}` : '进行中'
  }
  return duration ? `耗时 ${duration}` : ''
})
</script>

<style scoped>
/* 卡片：白底细框 + 左侧 4px 角色色条；色条用绝对定位，避免圆角被 border-left 切出直角 */
.ma-card {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 10px 12px 10px 16px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e9ebf2;
  border-radius: 10px;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.ma-card__bar {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 4px;
  background: var(--ma-color, #635bff);
}

.ma-card__main {
  flex: 1;
  min-width: 0;
}

.ma-card__head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

/* 角色色小圆点：和左侧色条、右栏日志同色，扫一眼就知道是哪一位 */
.ma-card__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ma-color, #635bff);
}

.ma-card__title {
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

/* 运行中转圈：与状态文字并列，尺寸压到 12px，不抢 Agent 名 */
.ma-card__spin {
  flex: none;
  color: #635bff;
  font-size: 12px;
}

/* 状态：完成灰、运行中紫、失败红；与耗时并列，不抢 Agent 名 */
.ma-card__state {
  flex: none;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
}

.ma-card__state.is-running {
  color: #635bff;
}

.ma-card__state.is-error {
  color: var(--el-color-danger, #d92d20);
}

.ma-card__time {
  flex: none;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 摘要：12px、最多 3 行；超出省略，长返回不会把协作流撑成文档 */
.ma-card__summary {
  display: -webkit-box;
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

/* 结论卡摘要更突出一点；路由卡保持次级，避免中栏出现两个视觉重心 */
.ma-card.is-result .ma-card__summary {
  color: var(--ah-text, #1f2430);
}

/* 运行中：色条轻微呼吸，提示还在长；尊重系统「减少动效」 */
.ma-card.is-running {
  border-color: #e4e1ff;
}

.ma-card.is-running .ma-card__bar {
  animation: ma-card-pulse 1.6s ease-in-out infinite;
}

.ma-card.is-error {
  border-color: #f5d0cc;
}

@keyframes ma-card-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.45;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ma-card.is-running .ma-card__bar {
    animation: none;
  }
}
</style>
