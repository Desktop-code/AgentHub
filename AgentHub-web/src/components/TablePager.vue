<template>
  <div class="table-pager" :class="`is-${mode}`">
    <el-pagination
      :current-page="current"
      :page-size="size"
      :page-sizes="pageSizes"
      :total="total"
      :layout="resolvedLayout"
      :small="small"
      :background="background"
      @update:current-page="onCurrentChange"
      @update:page-size="onSizeChange"
    >
      <!-- 网格模式：用业务总数文案（共 N 个智能体…）取代内置「共 x 条」；表格模式不渲染此插槽 -->
      <template v-if="isGrid && totalText" #default>
        <span class="pager-total">{{ totalText }}</span>
      </template>
    </el-pagination>
  </div>
</template>

<script setup lang="ts">
/**
 * 分页底栏。所属域：前端壳。
 *
 * 两种模式严格分开，禁止互相渗透（上轮教训：精简规则误伤表格）：
 * - `table`（默认，表格 / 列表视图）：完整分页 —— 总数 + 每页条数 + 页码 + 跳页，
 *   pageSize 由调用方在 10/20/50/100 里选，不做任何一屏容量推算。
 * - `grid`（卡片墙）：精简底栏 —— 「共 N 个{对象}」常显，页码仅多页时出现，
 *   不出现 sizes / jumper；pageSize 由 `useGridPageSize` 一屏容量实测写入。
 *
 * 约束：
 * 1. 不持有分页状态，页码与每页条数由调用方 reactive 对象保管（v-model:current / v-model:size）。
 * 2. 只在「用户操作」时抛 change，父组件主动改页码（如查询回第一页）不会再触发一次请求。
 * 3. 切每页条数时页码归一到第 1 页；Element Plus 随后可能同步纠正页码并发 current-change，
 *    用一次性标记吞掉那次事件，保证一次操作只请求一次。
 * 4. grid 模式下 size 是只读结果（由一屏容量决定），故 sizes 选择器必须剔除。
 */
import { computed } from 'vue'
import {
  TABLE_PAGER_LAYOUT,
  pagerPageCount,
  resolvePagerLayout,
  type PagerMode
} from '@/utils/pagerLayout'

const props = withDefaults(
  defineProps<{
    /** 总条数 */
    total: number
    /** 当前页码，从 1 开始 */
    current: number
    /** 每页条数 */
    size: number
    /**
     * 底栏模式：
     * - table（默认）：完整分页，表格 / 列表视图用；
     * - grid：精简底栏，仅网格卡片墙用。
     */
    mode?: PagerMode
    /** 可选每页条数；仅 table 模式生效，默认 10/20/50/100 */
    pageSizes?: number[]
    /** Element Plus 布局串；仅 table 模式生效，默认即完整分页 */
    layout?: string
    /** 紧凑模式，用于卡片或抽屉内的窄分页 */
    small?: boolean
    /** 页码是否带底色 */
    background?: boolean
    /**
     * 业务总数文案；仅 grid 模式渲染（如「共 12 个智能体」）。
     * table 模式传了也忽略，避免表格被换成业务口径。
     */
    totalText?: string
    /**
     * 总数常显、页码仅在多页时出现；仅 grid 模式生效。
     * table 模式恒为完整分页，忽略此项。
     */
    pagerOnMultiPageOnly?: boolean
  }>(),
  {
    mode: 'table',
    pageSizes: () => [10, 20, 50, 100],
    layout: TABLE_PAGER_LAYOUT,
    small: false,
    background: false,
    totalText: '',
    pagerOnMultiPageOnly: false
  }
)

const emit = defineEmits<{
  /** 页码双向绑定 */
  (e: 'update:current', current: number): void
  /** 每页条数双向绑定 */
  (e: 'update:size', size: number): void
  /** 用户翻页或切每页条数后通知调用方重新请求（状态已同步） */
  (e: 'change'): void
}>()

/** 是否网格卡片墙的精简底栏 */
const isGrid = computed(() => props.mode === 'grid')

/** 总页数；至少 1，供「仅一页」判断使用 */
const pageCount = computed(() => pagerPageCount(props.total, props.size))

/**
 * 实际交给 Element Plus 的布局串。
 * table 恒为完整分页（补齐 sizes / jumper）；grid 走精简规则（剔除 sizes / jumper）。
 */
const resolvedLayout = computed(() =>
  resolvePagerLayout({
    mode: props.mode,
    layout: props.layout,
    totalText: props.totalText,
    pageCount: pageCount.value
  })
)

/** 切每页条数时置位，用于吞掉 Element Plus 紧随其后的页码纠正事件 */
let absorbNextCurrent = false

/**
 * 页码变化：仅在用户点页码时请求。
 *
 * @param next 目标页码
 */
function onCurrentChange(next: number) {
  if (absorbNextCurrent) {
    return
  }
  if (next === props.current) {
    return
  }
  emit('update:current', next)
  emit('change')
}

/**
 * 每页条数变化：先回第 1 页再请求一次；同一次点击内的页码纠正事件被忽略。
 * grid 模式下没有 sizes 选择器，本函数不会被触发。
 *
 * @param next 目标每页条数
 */
function onSizeChange(next: number) {
  if (next === props.size) {
    return
  }
  absorbNextCurrent = true
  emit('update:size', next)
  emit('update:current', 1)
  emit('change')
  // 标记只在本次点击的同步链路里有效，微任务后复位，避免影响后续翻页
  queueMicrotask(() => {
    absorbNextCurrent = false
  })
}
</script>

<style scoped>
.table-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

/* 业务总数文案：与 Element Plus 内置「共 x 条」同字号同灰度（仅 grid 模式出现） */
.pager-total {
  margin-right: 8px;
  color: var(--ah-text-secondary, #909399);
  font-size: 13px;
  line-height: 32px;
}
</style>
