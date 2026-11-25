/**
 * 分页底栏 SSR 渲染验证（node:test，无浏览器）。所属域：前端壳。
 *
 * 目的：用真实 el-pagination 渲染结果证明两种模式互不渗透：
 * 1) 表格模式（默认）必须渲染「条/页」选择器与「前往」跳页框，总数用内置 total；
 * 2) 网格模式必须渲染业务文案「共 N 个…」，单页时没有页码，任何情况都无 sizes / jumper。
 *
 * 断言用 Element Plus 的稳定类名（el-pagination__sizes / __jump / __total），
 * 不用中文文案 —— 文案来自语言包，SSR 默认是英文，按文案断言会误判。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/tablePager.render.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createSSRApp, defineComponent, h, ref } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { ElPagination } from 'element-plus'
import { pagerPageCount, resolvePagerLayout, type PagerMode } from '../src/utils/pagerLayout.ts'

/** 网格模式的布局入参（与 TablePager grid 模式同源，故意带上 sizes/jumper 验证会被剔除） */
const GRID_LAYOUT_INPUT = 'total, sizes, prev, pager, next, jumper'

/** Element Plus 渲染出的稳定类名 */
const CLS_SIZES = 'el-pagination__sizes'
const CLS_JUMP = 'el-pagination__jump'
const CLS_TOTAL = 'el-pagination__total'
const CLS_PAGER = 'el-pager'
/** 网格业务文案（TablePager 的 totalText 插槽） */
const CLS_BIZ_TOTAL = 'pager-total'

/**
 * 用与 TablePager 相同的布局推导 + el-pagination 渲染一个底栏。
 *
 * @param mode 底栏模式
 * @param total 总条数
 * @param pageSize 每页条数
 * @param totalText 业务总数文案（仅 grid 模式渲染）
 * @return 渲染出的 HTML
 */
async function renderPager(
  mode: PagerMode,
  total: number,
  pageSize: number,
  totalText = ''
): Promise<string> {
  const layout = resolvePagerLayout({
    mode,
    layout: GRID_LAYOUT_INPUT,
    totalText,
    pageCount: pagerPageCount(total, pageSize)
  })
  const App = defineComponent({
    setup() {
      const current = ref(1)
      const size = ref(pageSize)
      return () =>
        h(
          ElPagination,
          {
            currentPage: current.value,
            pageSize: size.value,
            total,
            layout,
            'onUpdate:current-page': (v: number) => {
              current.value = v
            },
            // Element Plus 对含 sizes 的布局强校验「必须有每页条数监听」，缺了会整块渲染 null
            'onUpdate:page-size': (v: number) => {
              size.value = v
            }
          },
          // 与 TablePager 一致：只有 grid 模式才渲染业务文案插槽
          mode === 'grid' && totalText
            ? { default: () => h('span', { class: CLS_BIZ_TOTAL }, totalText) }
            : undefined
        )
    }
  })
  return renderToString(createSSRApp(App))
}

/* ============ 一、表格模式：完整分页（本轮回归重点） ============ */

test('表格模式（有数据）：渲染条/页选择器 + 前往跳页 + 内置总数', async () => {
  const html = await renderPager('table', 25, 10)
  assert.ok(html.includes(CLS_SIZES), `表格必须渲染每页条数选择器，实际: ${html}`)
  assert.ok(html.includes(CLS_JUMP), '表格必须渲染跳页框')
  assert.ok(html.includes(CLS_TOTAL), '表格总数用内置 total 组件')
  assert.ok(html.includes(CLS_PAGER), '表格必须有页码')
  assert.ok(!html.includes(CLS_BIZ_TOTAL), '表格不得被换成业务口径文案')
})

test('表格模式（只有 3 条，单页）：仍保留条/页与前往', async () => {
  const html = await renderPager('table', 3, 10)
  assert.ok(html.includes(CLS_SIZES), '表格单页也不得精简掉条/页')
  assert.ok(html.includes(CLS_JUMP), '表格单页也不得精简掉前往')
})

/* ============ 二、网格模式：精简底栏保持上轮行为 ============ */

test('网格模式（多页）：有业务总数文案与页码，无 sizes / jumper', async () => {
  const html = await renderPager('grid', 25, 12, '共 25 个智能体')
  assert.ok(html.includes('共 25 个智能体'), `总数文案缺失，实际: ${html}`)
  assert.ok(html.includes(CLS_PAGER), '多页时必须出现页码')
  assert.ok(!html.includes(CLS_SIZES), '网格不得出现每页条数选择器')
  assert.ok(!html.includes(CLS_JUMP), '网格不得出现跳页框')
})

test('网格模式（单页）：只保留总数文案，页码藏起', async () => {
  const html = await renderPager('grid', 11, 12, '共 11 个智能体')
  assert.ok(html.includes('共 11 个智能体'), `单页也必须常显总数文案，实际: ${html}`)
  assert.ok(!html.includes(CLS_PAGER), '单页不应出现页码')
  assert.ok(!html.includes(CLS_SIZES))
  assert.ok(!html.includes(CLS_JUMP))
})

test('网格模式：11 条 / 容量 12 刚好铺满一屏，不会多出第 2 页', async () => {
  const html = await renderPager('grid', 11, 12, '共 11 个智能体')
  assert.ok(!html.includes('el-pager__item'), '11 条容量 12 时不应有页码按钮')
})
