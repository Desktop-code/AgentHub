<template>
  <!--
    Token 消耗趋势图。所属域：billing。
    柱状图（输入 / 输出堆叠）+ 折线（每日总量）组合；X 轴为日期，Y 轴为 token 数。
    只负责渲染，取数与聚合由用量页完成。
  -->
  <div class="trend-chart">
    <div v-if="hasData" ref="host" class="trend-canvas" />
    <el-empty v-else :image-size="72" description="所选区间暂无 Token 消耗" />
  </div>
</template>

<script setup lang="ts">
/**
 * Token 消耗趋势图。所属域：billing。
 * 使用 ECharts 按需引入（柱状 + 折线 + 网格/提示/图例），避免整包进首屏。
 * 容器尺寸变化由 ResizeObserver 驱动重绘；卸载时释放实例，防止内存泄漏。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import type { TrendPoint } from '@/types/billing'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  points: TrendPoint[]
}>()

const host = ref<HTMLDivElement>()
/** echarts 实例；只在挂载后创建，销毁时释放。 */
let chart: ReturnType<typeof echarts.init> | null = null
let observer: ResizeObserver | null = null

/** 全为 0 视作无数据，避免画出一条贴底的空白折线。 */
const hasData = computed(() => props.points.some((point) => point.total > 0))

/**
 * 千分位数字，用于提示框。
 *
 * @param value 原始数值
 * @return 带分隔符的文本
 */
function withSeparator(value: unknown) {
  const num = Number(value)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : '0'
}

/**
 * Y 轴紧凑刻度：过万折算为「万」，避免长数字挤压绘图区。
 *
 * @param value 刻度值
 * @return 刻度文本
 */
function compactAxis(value: number) {
  if (Math.abs(value) >= 10000) {
    return `${(value / 10000).toFixed(1)}万`
  }
  return String(value)
}

/** 依据 props 生成 option；日期只显示月-日，区间跨度大时自动隔点显示。 */
function buildOption() {
  const labels = props.points.map((point) => point.date.slice(5))
  // 只有 1 天时柱宽按类目自适应会撑满，这里给柱子限宽
  return {
    color: ['#b9b4ff', '#635bff', '#f59e0b'],
    grid: { left: 8, right: 16, top: 36, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      valueFormatter: withSeparator
    },
    legend: {
      top: 0,
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 16,
      textStyle: { fontSize: 12, color: '#6b7280' }
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#e4e7ed' } },
      axisLabel: {
        color: '#8b90a0',
        fontSize: 11,
        // 点多时按间隔抽稀，避免标签重叠
        interval: labels.length > 15 ? 'auto' : 0
      }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#f2f3f7' } },
      axisLabel: { color: '#8b90a0', fontSize: 11, formatter: compactAxis }
    },
    series: [
      {
        name: '输入',
        type: 'bar',
        stack: 'token',
        barMaxWidth: 22,
        itemStyle: { color: '#b9b4ff' },
        data: props.points.map((point) => point.prompt)
      },
      {
        name: '输出',
        type: 'bar',
        stack: 'token',
        barMaxWidth: 22,
        itemStyle: { color: '#635bff', borderRadius: [3, 3, 0, 0] },
        data: props.points.map((point) => point.completion)
      },
      {
        name: '总量',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2, color: '#f59e0b' },
        itemStyle: { color: '#f59e0b' },
        data: props.points.map((point) => point.total)
      }
    ]
  }
}

/** 创建或复用实例并重绘；顺带确保尺寸监听挂在当前容器上。 */
function render() {
  if (!hasData.value || !host.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(host.value)
  }
  chart.setOption(buildOption(), true)
  observeHost()
}

/** 尺寸监听：空态切回有数据时容器是新建的，必须重新 observe 旧节点才不会被回收。 */
function observeHost() {
  if (typeof ResizeObserver === 'undefined' || !host.value) {
    return
  }
  observer?.disconnect()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(host.value)
}

onMounted(() => {
  render()
})

// 数据或空态切换后重绘；从空态恢复时容器刚挂载，等下一帧再 init
watch(
  () => props.points,
  async () => {
    if (!hasData.value) {
      chart?.dispose()
      chart = null
      observer?.disconnect()
      observer = null
      return
    }
    // 等 v-if 内的容器落进 DOM 后再 init
    await nextTick()
    render()
  },
  { deep: true }
)

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.trend-chart {
  margin-bottom: 16px;
}

.trend-canvas {
  width: 100%;
  height: 260px;
}
</style>
