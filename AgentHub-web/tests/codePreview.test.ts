/**
 * 入出参短预览窄测（node:test，无浏览器）。所属域：前端 obs。
 *
 * 覆盖验收点「入出参默认约 4～6 行，超出才给展开入口」：
 * 1) 默认 112px 预览按 6 行估算（不是 400px 那种半屏高度）；
 * 2) 短内容不出按钮，长内容出按钮；
 * 3) 单行超长（压缩 JSON / 整段 Markdown）也判为超出；
 * 4) 高度阈值被 clamp，预览高度极小时不会把任何内容都判成溢出。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/codePreview.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { CODE_LINE_HEIGHT_PX, isCodeOverflow, previewLineBudget } from '../src/utils/codePreview.ts'

/** 造 N 行文本 */
function lines(count: number): string {
  return Array.from({ length: count }, (_, i) => `第 ${i + 1} 行`).join('\n')
}

test('默认预览高度 112px ≈ 6 行（不再按 400px 铺半屏）', () => {
  // (112 - 20) / 18.6 ≈ 4.95 → floor 4，留 padding 后仍落在「4～6 行」区间
  const budget = previewLineBudget(112)
  assert.ok(budget >= 4 && budget <= 6, `预算应在 4～6 行，实际 ${budget}`)
})

test('短内容不出现展开按钮', () => {
  assert.equal(isCodeOverflow('', 112), false)
  assert.equal(isCodeOverflow(null, 112), false)
  assert.equal(isCodeOverflow(lines(3), 112), false)
  assert.equal(isCodeOverflow('{"a":1}', 112), false)
})

test('超过预览行数 / 字符预算都要出展开按钮', () => {
  // 行数超标
  assert.equal(isCodeOverflow(lines(12), 112), true)
  // 单行超长：行数只有 1，但视觉同样被截断
  assert.equal(isCodeOverflow('x'.repeat(112 * 4 + 1), 112), true)
  // 正好在预算内不进展开
  assert.equal(isCodeOverflow('x'.repeat(112 * 4), 112), false)
})

test('展开上限 320px 时行预算更大（宽屏展开后能看到更多）', () => {
  assert.ok(previewLineBudget(320) > previewLineBudget(112))
  // 同样的 12 行：窄预览截断，展开高度够放就不该再判溢出（调用方传展开高度时）
  assert.equal(isCodeOverflow(lines(12), 320), false)
})

test('预览高度极小时按下限高度处理，不会一律判溢出', () => {
  assert.equal(previewLineBudget(0), 2)
  assert.equal(previewLineBudget(30), 2)
  // 1 行短内容在下限高度（60px）预算内，不该出现展开按钮
  assert.equal(isCodeOverflow('单行', 0), false)
  assert.ok(CODE_LINE_HEIGHT_PX > 0)
})
