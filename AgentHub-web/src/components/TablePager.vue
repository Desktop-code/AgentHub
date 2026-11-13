<template>
  <div class="table-pager" :class="`is-${mode}`">
    <!-- 网格底栏：左总数（数字加粗）+ 右方块分页，space-between -->
    <template v-if="isGrid">
      <span v-if="gridTotalParts" class="pager-total">
        共 <strong>{{ gridTotalParts.n }}</strong> {{ gridTotalParts.rest }}
      </span>
      <span v-else-if="totalText" class="pager-total">{{ totalText }}</span>
      <span v-else class="pager-total">共 <strong>{{ total }}</strong> 条</span>
      <el-pagination
        v-if="gridPagerLayout"
        :current-page="current"
        :page-size="size"
        :total="total"
        :layout="gridPagerLayout"
        :small="small"
        :background="true"
        @update:current-page="onCurrentChange"
      />
    </template>
    <!-- 表格底栏：完整分页，行为不变 -->
    <el-pagination
      v-else
      :current-page="current"
      :page-size="size"
      :page-sizes="pageSizes"
      :total="total"
      :layout="resolvedLayout"
      :small="small"
      :background="background"
      @update:current-page="onCurrentChange"
      @update:page-size="onSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 分页底栏。所属域：前端壳。
 *
 * 两种模式严格分开，禁止互相渗透：
 * - `table`（默认）：完整分页 —— 总数 + 每页条数 + 页码 + 跳页。
 * - `grid`（卡片墙）：全宽 Footer，左「共 N 个…」（数字加粗）、右方块分页（background）；
 *   页码仅多页时出现；不出现 sizes / jumper。
 *
 * 约束：
 * 1. 不持有分页状态，页码与每页条数由调用方保管。
 * 2. 只在「用户操作」时抛 change。
 * 3. 切每页条数时页码归一到第 1 页；用一次性标记吞掉 Element Plus 纠正事件。
 * 4. grid 模式下 size 只读（一屏容量），故无 sizes 选择器。
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
    /** 页码是否带底色；table 默认 false；grid 默认 true（可显式关掉） */
    background?: boolean
    /**
     * 业务总数文案；仅 grid 模式渲染（如「共 12 个智能体」）。
     * table 模式传了也忽略。
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
  (e: 'update:current', current: number): void
  (e: 'update:size', size: number): void
  (e: 'change'): void
}>()

const isGrid = computed(() => props.mode === 'grid')

const pageCount = computed(() => pagerPageCount(props.total, props.size))

/**
 * 解析「共 N 个智能体」→ 数字加粗；解析失败则整段灰字展示。
 */
const gridTotalParts = computed(() => {
  const matched = props.totalText.match(/^共\s*(\d+)\s*(.*)$/)
  if (!matched) {
    return null
  }
  return { n: matched[1], rest: matched[2] }
})

/** 网格页码：单页时不渲染；多页时仅 prev/pager/next */
const gridPagerLayout = computed(() => {
  if (props.pagerOnMultiPageOnly && pageCount.value <= 1) {
    return ''
  }
  if (pageCount.value <= 1) {
    return ''
  }
  return 'prev, pager, next'
})

/** 表格模式布局串 */
const resolvedLayout = computed(() =>
  resolvePagerLayout({
    mode: props.mode,
    layout: props.layout,
    totalText: props.totalText,
    pageCount: pageCount.value
  })
)

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
 * 每页条数变化：先回第 1 页再请求一次。
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

/* 网格卡片墙：全宽 Footer，顶部分割线，左右分布 */
.table-pager.is-grid {
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--ah-border-soft, #f2f6fc);
}

.pager-total {
  color: var(--ah-text-secondary, #909399);
  font-size: 13px;
  line-height: 32px;
  white-space: nowrap;
}

.pager-total strong {
  color: var(--ah-text, #303133);
  font-weight: 650;
}
</style>
