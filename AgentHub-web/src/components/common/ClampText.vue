<template>
  <!--
    多行截断文本 + 悬浮看全文。所属域：common（纯展示，无业务）。
    列表里长文本只露 lines 行，超出时鼠标移上去弹深色提示框显示全文；
    没被截断时不弹，避免短文本也冒出黑框。
  -->
  <el-tooltip
    :disabled="!overflow"
    :placement="placement"
    :show-after="250"
    :hide-after="0"
    popper-class="clamp-tip"
  >
    <!-- 用 content 插槽而不是 content 属性：这样才能给提示正文单独限高自滚，不裁掉小箭头 -->
    <template #content>
      <div class="clamp-tip__body">{{ text }}</div>
    </template>
    <span ref="textRef" class="clamp-text" :style="clampStyle">{{ displayText }}</span>
  </el-tooltip>
</template>

<script setup lang="ts">
/**
 * 多行截断文本（列表标题用）。所属域：common。
 *
 * <p>为什么不用字符数估算：同样的字数在中英混排、窄栏 25% 宽度下换行位置完全不同，
 * 估不准就会出现「明明被截了却不给提示」或「没截也弹黑框」。这里按真实布局量
 * scrollHeight / clientHeight，并在容器尺寸变化（窗口缩放、侧栏折叠）后重算。
 *
 * <p>提示框由 popper-class="clamp-tip" 控制：限宽 + 限高自滚，保证黑框不会拉成一大条。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 原文；空时显示占位文案 */
    text?: string | null
    /** 空文本占位 */
    placeholder?: string
    /** 最多显示几行 */
    lines?: number
    /** 提示框弹出方向 */
    placement?: 'top' | 'bottom' | 'left' | 'right'
  }>(),
  { text: '', placeholder: '', lines: 2, placement: 'top' }
)

const textRef = ref<HTMLElement | null>(null)
/** 是否真的被截断：决定要不要弹提示框 */
const overflow = ref(false)
/** 尺寸监听：列宽变化后重新判断，避免仍是旧结论 */
let resizeObserver: ResizeObserver | null = null

const displayText = computed(() => props.text || props.placeholder)

const clampStyle = computed(() => ({ WebkitLineClamp: String(Math.max(1, props.lines)) }))

/**
 * 量一次是否溢出。
 * <p>用 +1 的容差：行高取整后 scrollHeight 常常比 clientHeight 多 1px，
 * 严格大于会把「刚好放得下」误判成溢出。
 */
function measure() {
  const el = textRef.value
  if (!el) {
    overflow.value = false
    return
  }
  overflow.value = el.scrollHeight > el.clientHeight + 1
}

onMounted(() => {
  void nextTick(measure)
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => measure())
    if (textRef.value) {
      resizeObserver.observe(textRef.value)
    }
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

// 文本变了要重新量：同一行复用组件渲染不同记录时必须重算
watch(
  () => props.text,
  () => {
    void nextTick(measure)
  }
)
</script>

<style scoped>
/* 列表标题排版内置：同一条列表里标题字号/行高必须一致，不必每处再抄一遍 */
.clamp-text {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  /* 行数由内联 WebkitLineClamp 控制 */
  width: 100%;
  color: var(--ah-text);
  font-size: 13px;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

<style>
/*
 * 深色提示框（挂 body，必须全局）：限宽 360px；正文限高 220px 内部滚动。
 * 「黑框不要很长」——长问题只弹一块可读区域，不再拉成整列宽的长条。
 */
.clamp-tip.el-popper {
  max-width: 360px;
  padding: 8px 10px;
}

.clamp-tip__body {
  max-height: 220px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
