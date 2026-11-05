/**
 * 管理列表「序号」列。所属域：前端壳（展示层）。
 *
 * 目标：给 el-table 统一提供最左侧、窄宽度、中文表头的序号列，避免每页复制一份
 * `type="index"` + `(page-1)*size+rowIndex+1` 公式（15 处分散复制最容易改漏或写错）。
 *
 * 关键约束：
 * 1. 纯前端展示：不参与 API 排序 / 后端分页 DTO，也不改任何请求参数。
 * 2. 分页列表用跨页连续序号；不分页列表（如提示词模板版本列表）用行号。
 * 3. 依赖 el-table-column 的 `type="index"` + `index` 回调；回调只读，不改变行数据。
 */
import { computed, type ComputedRef } from 'vue'

/** 序号列宽（px）：窄列，不抢主列；表头与单元格均居中 */
export const INDEX_COLUMN_WIDTH = 64

/** el-table 序号列属性；type=index 时由 Element Plus 在渲染期调用 index 回调取号 */
export interface ListIndexColumnProps {
  /** 固定为 Element Plus 的索引列类型 */
  type: 'index'
  /** 中文表头 */
  label: string
  /** 窄列宽度 */
  width: number
  /** 单元格居中 */
  align: 'center'
  /** 表头居中 */
  headerAlign: 'center'
  /** 第 rowIndex 行（0 起）应显示的序号（1 起） */
  index: (rowIndex: number) => number
}

/**
 * 组装序号列属性。
 * computed 依赖调用方传入的 page / size getter：翻页或改条数时列属性重建，
 * 序号随之刷新；否则 Element Plus 会沿用首帧序号，翻页后仍从 1 开始。
 *
 * @param page   当前页码（1 起）的取值函数
 * @param size   每页条数的取值函数
 * @param width  列宽，默认 INDEX_COLUMN_WIDTH
 * @return 可直接 v-bind 到 el-table-column 的响应式属性
 */
function buildIndexColumn(
  page: () => number,
  size: () => number,
  width: number
): ComputedRef<ListIndexColumnProps> {
  return computed<ListIndexColumnProps>(() => {
    // 页码 / 条数异常（0、负数、NaN）时按第一页每页 1 条兜底，避免序号出现 0 或负数
    const safePage = Math.max(1, Number(page()) || 1)
    const safeSize = Math.max(1, Number(size()) || 1)
    const offset = (safePage - 1) * safeSize
    return {
      type: 'index',
      label: '序号',
      width,
      align: 'center',
      headerAlign: 'center',
      // Element Plus 传入的是当前页内的行下标（0 起），加上跨页偏移即全局序号
      index: (rowIndex: number) => offset + rowIndex + 1
    }
  })
}

/**
 * 分页列表的序号列（跨页连续）。
 *
 * @param page  当前页码 getter，如 `() => query.current`
 * @param size  每页条数 getter，如 `() => query.size`
 * @param width 列宽，默认 64
 * @return `{ indexColumnProps }`，模板里 `<el-table-column v-bind="indexColumnProps" />`
 */
export function useListIndexColumn(
  page: () => number,
  size: () => number,
  width = INDEX_COLUMN_WIDTH
): { indexColumnProps: ComputedRef<ListIndexColumnProps> } {
  return { indexColumnProps: buildIndexColumn(page, size, width) }
}

/**
 * 不分页列表的序号列（恒为行号 + 1）。
 * 仍走 type="index"，保持与分页列表同一套样式和表头文案。
 *
 * @param width 列宽，默认 64
 * @return `{ indexColumnProps }`
 */
export function useRowIndexColumn(
  width = INDEX_COLUMN_WIDTH
): { indexColumnProps: ComputedRef<ListIndexColumnProps> } {
  // 页码恒 1、每页恒 1 条：offset 为 0，序号即 rowIndex + 1
  return { indexColumnProps: buildIndexColumn(() => 1, () => 1, width) }
}
