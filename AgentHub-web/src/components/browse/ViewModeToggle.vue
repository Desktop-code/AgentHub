<template>
  <!--
    网格 / 列表（主从）切换。所属域：前端壳。
    与 Agent 飞卡页同一入口文案与图标；禁用态由父级在飞卡 / 抽屉打开时传入。
  -->
  <div class="view-toggle" role="group" aria-label="视图切换">
    <button
      type="button"
      class="view-btn"
      :class="{ 'is-active': mode === 'grid' }"
      title="网格"
      aria-label="网格"
      :aria-pressed="mode === 'grid'"
      :disabled="disabled"
      @click="onPick('grid')"
    >
      <el-icon :size="16"><Grid /></el-icon>
    </button>
    <button
      type="button"
      class="view-btn"
      :class="{ 'is-active': mode === 'master' }"
      title="列表"
      aria-label="列表"
      :aria-pressed="mode === 'master'"
      :disabled="disabled || (mode === 'grid' && !canEnterMaster)"
      @click="onPick('master')"
    >
      <el-icon :size="16"><ListIcon /></el-icon>
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 视图切换按钮组。所属域：前端壳。
 * 网格态点「列表」进主从；主从态点「网格」回网格。禁用时两侧都不可点。
 */
import { h } from 'vue'
import { Grid } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    /** 当前视图 */
    mode: 'grid' | 'master'
    /** 飞卡 / 业务忙时整组禁用 */
    disabled?: boolean
    /** 网格态是否允许进主从（无数据时 false） */
    canEnterMaster?: boolean
  }>(),
  {
    disabled: false,
    canEnterMaster: true
  }
)

const emit = defineEmits<{
  /** 用户点另一态入口 */
  change: [mode: 'grid' | 'master']
}>()

/** 三横线列表图标（与 Agent 页一致）。 */
const ListIcon = {
  name: 'ViewListIcon',
  render() {
    return h(
      'svg',
      {
        xmlns: 'http://www.w3.org/2000/svg',
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': '2',
        'stroke-linecap': 'round'
      },
      [h('path', { d: 'M4 7h16' }), h('path', { d: 'M4 12h16' }), h('path', { d: 'M4 17h16' })]
    )
  }
}

/**
 * 点当前态忽略；点对侧才向外抛。
 *
 * @param next 目标视图
 */
function onPick(next: 'grid' | 'master') {
  if (props.disabled || next === props.mode) {
    return
  }
  if (next === 'master' && !props.canEnterMaster) {
    return
  }
  emit('change', next)
}
</script>
