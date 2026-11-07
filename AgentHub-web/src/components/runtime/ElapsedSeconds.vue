<template>
  <!-- 只有这段文字随秒表重渲；父级整棵树不再因为「已等待」每秒重跑 -->
  <span class="elapsed-seconds">{{ text }}</span>
</template>

<script setup lang="ts">
/**
 * 「已等待 Ns」秒表叶子组件。所属域：runtime（展示层）。
 *
 * <p>职责：自己持有 1Hz 定时器，把起始时间换算成秒数只在本 span 内更新。
 * 父组件不需要再每秒钟刷新一个 now 时间戳 prop，也不会因此让整棵对话树重渲。
 *
 * <p>约束：
 * - 只做展示，不发请求、不读会话数据；
 * - 计时起点为 0（拿不到开始时间）时按 0 秒显示，与旧口径一致；
 * - 卸载时必须清定时器，避免离开运行页后仍有空转。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 计时起点（毫秒时间戳）；<=0 表示拿不到开始时间，按 0 秒处理 */
    startTime?: number
    /** 前缀文案；默认「已等待」 */
    label?: string
  }>(),
  { startTime: 0, label: '已等待' }
)

/** 本地时钟：只驱动本组件的文字，不向父级冒泡 */
const now = ref(Date.now())
/** 1Hz 定时器句柄；组件卸载或起点失效时清空 */
let timer: number | undefined

/**
 * 启动秒表。重复调用不会叠加多个定时器。
 */
function startTimer() {
  if (timer !== undefined) {
    return
  }
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

/**
 * 停止秒表并释放句柄。
 */
function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/** 展示文案：起点缺失时给 0 秒，避免状态条出现空白占位 */
const text = computed(() => {
  const seconds = props.startTime ? Math.max(0, Math.floor((now.value - props.startTime) / 1000)) : 0
  return `${props.label} ${seconds}s`
})

// 起点变化（新一轮开始）时立刻对齐到新起点，不沿用上一轮的秒数
watch(
  () => props.startTime,
  () => {
    now.value = Date.now()
  }
)

onMounted(startTimer)
onBeforeUnmount(stopTimer)
</script>

<style scoped>
.elapsed-seconds {
  font-variant-numeric: tabular-nums;
}
</style>
