/**
 * 卡片墙一屏容量分页的纯函数单测（node:test，无 DOM）。所属域：前端壳。
 * 覆盖：列数按 auto-fill 公式、行数按可用高度、容量上限 100、
 * 量不到容器时不算容量、以及 resize 后当前页越界回落到合法末页。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/gridPageSize.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CARD_HEIGHT_NORMAL,
  calcGridColumns,
  calcGridPageSize,
  calcGridRows,
  lastPage
} from '../src/composables/gridPageSize.ts'
import { pagerPageCount, resolvePagerLayout } from '../src/utils/pagerLayout.ts'

/** 默认量算参数：与 .grid 的 minmax(280px,1fr)/gap:16px 及 ConfigCard 160px 一致 */
const options = { cardHeight: CARD_HEIGHT_NORMAL }

/* ============ 一、列数：与 CSS auto-fill 同公式 ============ */

test('列数：宽 1280 摆 4 列（4*280 + 3*16 = 1168 ≤ 1280）', () => {
  assert.equal(calcGridColumns(1280, options), 4)
})

test('列数：宽 1279 仍 4 列，宽 1167 掉到 3 列', () => {
  assert.equal(calcGridColumns(1279, options), 4)
  assert.equal(calcGridColumns(1167, options), 3)
  assert.equal(calcGridColumns(1168, options), 4)
})

test('列数：极窄容器至少 1 列，非法宽度不炸', () => {
  assert.equal(calcGridColumns(200, options), 1)
  assert.equal(calcGridColumns(0, options), 1)
  assert.equal(calcGridColumns(Number.NaN, options), 1)
})

/* ============ 二、行数：按可用高度扣间距 ============ */

test('行数：可用高 528px 摆 3 行（3*160 + 2*16 = 512 ≤ 528）', () => {
  assert.equal(calcGridRows(528, options), 3)
  assert.equal(calcGridRows(511, options), 2)
})

test('行数：上限 maxRows 兜底超高视口', () => {
  assert.equal(calcGridRows(5000, { ...options, maxRows: 6 }), 6)
  assert.equal(calcGridRows(5000, { ...options, maxRows: 20 }), 20)
})

test('行数：高度非法时至少 1 行', () => {
  assert.equal(calcGridRows(0, options), 1)
  assert.equal(calcGridRows(-10, options), 1)
})

/* ============ 三、容量 = 列 × 行，且不超过后端上限 ============ */

test('容量：4 列 × 3 行 = 12（一屏摆得下 12 张）', () => {
  assert.equal(calcGridPageSize({ width: 1280, height: 528 }, options), 12)
})

test('容量：11 条数据 + 容量 12 → 只有 1 页（不再出现无意义第 2 页）', () => {
  const size = calcGridPageSize({ width: 1280, height: 528 }, options)
  assert.equal(size, 12)
  assert.equal(pagerPageCount(11, size), 1)
})

test('容量：绝不越过后端 PageQuery 的 size 上限 100', () => {
  assert.equal(calcGridPageSize({ width: 20000, height: 20000 }, { ...options, maxRows: 50 }), 100)
  assert.equal(calcGridPageSize({ width: 20000, height: 20000 }, { ...options, maxRows: 50, maxPageSize: 200 }), 100)
})

test('容量：容器高度为 0（首帧未排版）仍给 1 列 1 行，绝不返回 0', () => {
  assert.equal(calcGridPageSize({ width: 1280, height: 0 }, options), 4)
  assert.equal(calcGridPageSize({ width: 0, height: 0 }, options), 1)
})

/* ============ 四、resize 后页码越界 → 合法末页 ============ */

test('末页：11 条、容量从 10 变 12 后第 2 页越界 → 落到第 1 页', () => {
  assert.equal(lastPage(11, 10), 2)
  assert.equal(lastPage(11, 12), 1)
})

test('末页：容量变小时页码仍合法；空数据回第 1 页', () => {
  assert.equal(lastPage(25, 12), 3)
  assert.equal(lastPage(0, 12), 1)
  assert.equal(lastPage(10, 0), 1)
})

/* ============ 五、底栏布局：表格完整 / 网格精简，互不渗透 ============ */

test('表格模式（默认）：完整分页，含 sizes 与 jumper', () => {
  const layout = resolvePagerLayout({
    mode: 'table',
    layout: 'total, sizes, prev, pager, next, jumper',
    totalText: '',
    pageCount: 1
  })
  assert.equal(layout, 'total, sizes, prev, pager, next, jumper')
  assert.ok(layout.includes('sizes'), '表格必须有「条/页」')
  assert.ok(layout.includes('jumper'), '表格必须有「前往 x 页」')
})

test('表格模式：单页也不精简（表格不按一屏容量收敛控件）', () => {
  const layout = resolvePagerLayout({
    mode: 'table',
    layout: 'total, sizes, prev, pager, next, jumper',
    totalText: '',
    pageCount: 1
  })
  assert.ok(layout.includes('pager'), '表格单页仍保留页码区')
  assert.ok(layout.includes('sizes'))
})

test('表格模式：调用方漏传 parts 时也补齐 sizes / jumper，不会被精简误伤', () => {
  const layout = resolvePagerLayout({
    mode: 'table',
    layout: '',
    totalText: '',
    pageCount: 4
  })
  assert.equal(layout, 'total, sizes, prev, pager, next, jumper')
})

test('表格模式：即便误传 totalText，也不换成业务口径（不渲染 slot）', () => {
  const layout = resolvePagerLayout({
    mode: 'table',
    layout: 'total, sizes, prev, pager, next, jumper',
    totalText: '共 25 个模型实例',
    pageCount: 3
  })
  assert.ok(!layout.includes('slot'), '表格不得用业务文案插槽')
  assert.ok(layout.includes('total'))
})

test('网格模式：多页时只有总数文案 + 页码，无 sizes / jumper', () => {
  const layout = resolvePagerLayout({
    mode: 'grid',
    layout: 'total, sizes, prev, pager, next, jumper',
    totalText: '共 25 个智能体',
    pageCount: 3
  })
  assert.equal(layout, 'slot, prev, pager, next')
  assert.ok(!layout.includes('sizes'))
  assert.ok(!layout.includes('jumper'))
})

test('网格模式：单页时只留总数文案（页码藏起，总数常显）', () => {
  const layout = resolvePagerLayout({
    mode: 'grid',
    layout: 'total, prev, pager, next',
    totalText: '共 11 个智能体',
    pageCount: 1
  })
  assert.equal(layout, 'slot')
})

test('网格模式：没给业务文案时退回原生 total，仍不含 sizes / jumper', () => {
  const layout = resolvePagerLayout({
    mode: 'grid',
    layout: 'total, sizes, prev, pager, next, jumper',
    totalText: '',
    pageCount: 2
  })
  assert.equal(layout, 'total, prev, pager, next')
})
