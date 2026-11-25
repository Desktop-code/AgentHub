/**
 * 内容框「中间过程 / 最终结果」判定的单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 智能网关判定句始终按中间过程限高；
 * 2) 只做分发的方案（只有分流句）始终限高；
 * 3) 整轮最后一条真正结论：直播中仍限高，整轮结束后才铺开；
 * 4) 其余专家结果始终四行，不被放大成终局；
 * 5) 高度上限是四行，最终结果为不设限。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  BOX_MAX_LINES,
  bodyExpandAction,
  boxMaxHeight,
  formatThoughtElapsed,
  isMidProcessBox,
  isThinkOpenByDefault,
  isUnlimitedAnswerBox
} from '../src/components/runtime/workbench/workbenchContentBox.ts'

test('智能网关判定句：无论是否在流式，都按中间过程限高', () => {
  const gateway = { kind: 'gateway' as const, answer: '' }
  assert.equal(isMidProcessBox(gateway, true), true)
  // 结束后仍限高：它前面还有别的步骤，不能把网关放大成终局
  assert.equal(isMidProcessBox(gateway, false), true)
})

test('整轮最后一条在直播中也限高，结束后才铺开', () => {
  const gateway = { kind: 'gateway' as const, answer: '' }
  const dispatch = { kind: 'actor' as const, roleLabel: '方案', answer: '' }
  const expert = { kind: 'actor' as const, answer: '年假按工龄计算' }
  const summary = { kind: 'conclusion' as const, roleLabel: '方案', answer: '综合台汇总', isFinal: true }

  // 直播中即使已经被标成终局，也不放开高度
  assert.equal(isMidProcessBox(summary, true), true)
  assert.equal(boxMaxHeight(summary, true), `${BOX_MAX_LINES * 22}px`)
  assert.equal(isMidProcessBox(summary, false), false)
  assert.equal(boxMaxHeight(summary, false), null)
  // 前面的块（含已经写完的专家、网关判定句、只做分发的方案）始终四行
  for (const card of [gateway, dispatch, expert]) {
    assert.equal(isMidProcessBox(card, true), true, `${card.kind} 流式中应限高`)
    assert.equal(isMidProcessBox(card, false), true, `${card.kind} 结束后仍限高`)
  }
})

test('无汇总时最后一位专家是终局：只有它结束后不限高', () => {
  const gateway = { kind: 'gateway' as const, answer: '' }
  const expert = { kind: 'actor' as const, answer: '年假按工龄计算', isFinal: true }
  assert.equal(isMidProcessBox(expert, false), false)
  assert.equal(isMidProcessBox(gateway, false), true)
})

test('只做分发的方案（只有分流句、没有结论）：始终限高', () => {
  const dispatchScheme = { kind: 'actor' as const, roleLabel: '方案', answer: '' }
  assert.equal(isMidProcessBox(dispatchScheme, true), true)
  assert.equal(isMidProcessBox(dispatchScheme, false), true)
})

test('方案带真汇总且是整轮最后一条：直播中限高，结束后才完整展开', () => {
  const conclusionScheme = {
    kind: 'conclusion' as const,
    roleLabel: '方案',
    answer: '综合台汇总：已写入报告',
    isFinal: true
  }
  assert.equal(isMidProcessBox(conclusionScheme, true), true)
  assert.equal(isMidProcessBox(conclusionScheme, false), false)
  assert.equal(boxMaxHeight(conclusionScheme, false), null)
})

test('专家作答：没被标成终局时，结束后仍保持四行', () => {
  const expert = { kind: 'actor' as const, answer: '年假按工龄计算，满一年 5 天' }
  assert.equal(isMidProcessBox(expert, true), true)
  // 前面还有汇总时，这位子专家写完了也不放开——避免被放大成终局
  assert.equal(isMidProcessBox(expert, false), true)
  assert.equal(boxMaxHeight(expert, false), `${BOX_MAX_LINES * 22}px`)
  assert.equal(boxMaxHeight(expert, true), `${BOX_MAX_LINES * 22}px`)
})

test('四行上限：按行高折算，行数常量为 4', () => {
  const expert = { kind: 'actor' as const, answer: 'x' }
  assert.equal(BOX_MAX_LINES, 4)
  assert.equal(boxMaxHeight(expert, true, 20), '80px')
  assert.equal(boxMaxHeight(expert, true, 22), '88px')
})

test('空白 answer 的方案按「只分流」处理，不会因为空白字符串被当成最终结果', () => {
  const blankScheme = { kind: 'actor' as const, roleLabel: '方案', answer: '   ' }
  assert.equal(isMidProcessBox(blankScheme, false), true)
})

test('思考区默认展开：只在流式中且这一位还没交出结果时展开', () => {
  const expert = { kind: 'actor' as const, answer: '工具返回补位句' }
  // 仍在跑：即使已有工具返回补位句，思考区也要展开让用户看过程
  assert.equal(isThinkOpenByDefault(expert, true, true), true)
  // 已出结果：立刻收起
  assert.equal(isThinkOpenByDefault(expert, true, false), false)
  // 本轮结束：一律收起
  assert.equal(isThinkOpenByDefault(expert, false, true), false)
  assert.equal(isThinkOpenByDefault(expert, false, false), false)
})

test('思考区默认展开：还没有任何结果时也展开，避免一片空白', () => {
  const emptyExpert = { kind: 'actor' as const, answer: '' }
  assert.equal(isThinkOpenByDefault(emptyExpert, true, false), true)
})

test('正文超过四行才给出展开，点一次变成收起，再点回到展开', () => {
  assert.equal(bodyExpandAction(true, false, false), 'none')
  assert.equal(bodyExpandAction(true, false, true), 'expand')
  assert.equal(bodyExpandAction(true, true, true), 'collapse')
  assert.equal(bodyExpandAction(false, true, true), 'none')
  assert.equal(BOX_MAX_LINES, 4)
})

test('思考耗时只格式化已经量到的毫秒', () => {
  assert.equal(formatThoughtElapsed(0), '')
  assert.equal(formatThoughtElapsed(1200), '1.2s')
  assert.equal(formatThoughtElapsed(5100), '5.1s')
  assert.equal(formatThoughtElapsed(12000), '12s')
})

test('综答不限四行，过程里的入口和专家仍按中间过程', () => {
  const summary = { kind: 'conclusion' as const, answer: '汇总正文很长' }
  const expert = { kind: 'actor' as const, answer: '专家正文很长' }
  assert.equal(isUnlimitedAnswerBox(summary), true)
  assert.equal(isUnlimitedAnswerBox(expert), false)
  assert.equal(isUnlimitedAnswerBox({ kind: 'gateway', answer: '判定句' }), false)
})
