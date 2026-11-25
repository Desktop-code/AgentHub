/**
 * 列表「序号」列公式测试（node:test，无浏览器）。所属域：前端壳。
 *
 * 覆盖验收点：
 * 1) 列属性：type=index、中文表头「序号」、窄列宽、居中；
 * 2) 分页列表跨页连续：第 2 页第 1 行必须是 pageSize + 1，而不是从 1 重新开始；
 * 3) 改每页条数 / 翻页后序号立即重算（响应式）；
 * 4) 非法页码 / 条数（0、负数、NaN）兜底为 1，序号不出现 0 或负数；
 * 5) 不分页列表序号 = 行号 + 1。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/listIndex.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { INDEX_COLUMN_WIDTH, useListIndexColumn, useRowIndexColumn } from '../src/composables/listIndex.ts'

/** 取分页列表的序号列属性（每次调用新建，避免用例间共享响应式状态） */
function pagedColumn(page: () => number, size: () => number) {
  return useListIndexColumn(page, size).indexColumnProps.value
}

test('列属性：type=index + 中文表头 + 窄列居中', () => {
  const column = pagedColumn(() => 1, () => 10)
  assert.equal(column.type, 'index')
  assert.equal(column.label, '序号')
  assert.equal(column.width, INDEX_COLUMN_WIDTH)
  assert.ok(column.width >= 56 && column.width <= 72, `列宽应在 56~72，实际 ${column.width}`)
  assert.equal(column.align, 'center')
  assert.equal(column.headerAlign, 'center')
})

test('第一页：从 1 开始逐行递增', () => {
  const column = pagedColumn(() => 1, () => 10)
  assert.deepEqual([0, 1, 2, 9].map((i) => column.index(i)), [1, 2, 3, 10])
})

test('跨页连续：第 2 页第 1 行 = 11，第 3 页第 5 行 = 25', () => {
  const second = pagedColumn(() => 2, () => 10)
  assert.equal(second.index(0), 11, '第 2 页不得从 1 重新计数')
  assert.equal(second.index(9), 20)

  const third = pagedColumn(() => 3, () => 10)
  assert.equal(third.index(4), 25)
})

test('改每页条数：页码不变时按新条数重算偏移', () => {
  assert.equal(pagedColumn(() => 2, () => 20).index(0), 21)
  assert.equal(pagedColumn(() => 2, () => 50).index(0), 51)
})

test('非法页码 / 条数兜底：不出现 0 或负数', () => {
  assert.equal(pagedColumn(() => 0, () => 10).index(0), 1)
  assert.equal(pagedColumn(() => -1, () => 10).index(0), 1)
  assert.equal(pagedColumn(() => 1, () => 0).index(0), 1)
  assert.equal(pagedColumn(() => Number.NaN, () => Number.NaN).index(0), 1)
})

test('响应式：翻页后同一列属性对象立即给出新序号', () => {
  const query = reactive({ current: 1, size: 10 })
  const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)
  assert.equal(indexColumnProps.value.index(0), 1)

  query.current = 2
  assert.equal(indexColumnProps.value.index(0), 11, '翻页后序号必须重算，否则会一直显示 1 起')

  query.size = 20
  assert.equal(indexColumnProps.value.index(0), 21, '改条数后偏移必须随新条数变化')
})

test('不分页列表：序号恒等于行号 + 1', () => {
  const column = useRowIndexColumn().indexColumnProps.value
  assert.equal(column.label, '序号')
  assert.deepEqual([0, 1, 2, 7].map((i) => column.index(i)), [1, 2, 3, 8])
})
