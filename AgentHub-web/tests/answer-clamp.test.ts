/**
 * 结果框表格口径：含表不走四行，普通正文仍走四行。所属域：runtime（前端展示）。
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  RESULT_TABLE_MAX_HEIGHT,
  answerClampOf,
  answerContainsMarkdownTable,
  tableFoldedByDefault
} from '../src/components/runtime/workbench/workbenchContentBox.ts'

const MISSING_FOUR = [
  '还缺的4项',
  '',
  '| 项 | 说明 |',
  '| --- | --- |',
  '| 事由 | 需补充出差事由 |',
  '| 日期 | 需补充起止日期 |',
  '| 出发地 | 需补充出发城市 |',
  '| 到达地 | 需补充到达城市 |'
].join('\n')

describe('answerClampOf', () => {
  it('报销清单这类管道表整段按表格口径，不按四行裁', () => {
    assert.equal(answerContainsMarkdownTable(MISSING_FOUR), true)
    assert.equal(answerClampOf(MISSING_FOUR), 'table')
    assert.equal(RESULT_TABLE_MAX_HEIGHT, '70vh')
  })

  it('智能助手清单表同样命中', () => {
    const list = '| 序号 | 事项 |\n| --- | --- |\n| 1 | 核对发票 |\n| 2 | 补差旅 |'
    assert.equal(answerClampOf(list), 'table')
  })

  it('没有表的短结果仍按四行口径', () => {
    assert.equal(answerClampOf('已收到，请补充出差事由。'), 'lines')
    assert.equal(answerClampOf(''), 'lines')
  })

  it('分隔行还没写出时仍按普通文本，避免半张表被当成完整表', () => {
    assert.equal(answerClampOf('还缺的4项\n\n| 项 | 说明 |'), 'lines')
  })

  it('代码块里的示例表不算结果表', () => {
    const fenced = '说明如下：\n\n```md\n| a | b |\n| --- | --- |\n| 1 | 2 |\n```\n'
    assert.equal(answerClampOf(fenced), 'lines')
  })

  it('裸 HTML 表按表格口径', () => {
    assert.equal(answerClampOf('<table><tr><td>事由</td></tr></table>'), 'table')
  })

  it('全角竖线的表同样识别', () => {
    const wide = '清单\n｜项｜说明｜\n｜---｜---｜\n｜事由｜待补｜'
    assert.equal(answerClampOf(wide), 'table')
  })
})

describe('tableFoldedByDefault', () => {
  const table = '| 项 | 说明 |\n| --- | --- |\n| 事由 | 待补 |'

  it('方案里的中间子结果有表也先收四行', () => {
    const child = { kind: 'actor' as const, answer: table, isFinal: false }
    assert.equal(answerClampOf(child.answer), 'table')
    assert.equal(tableFoldedByDefault(child, false), true)
  })

  it('本轮最后一份有表时默认展开', () => {
    const last = { kind: 'actor' as const, answer: table, isFinal: true }
    assert.equal(tableFoldedByDefault(last, false), false)
  })

  it('综答有表时不收四行', () => {
    const summary = { kind: 'conclusion' as const, answer: table, isFinal: true }
    assert.equal(tableFoldedByDefault(summary, false), false)
    assert.equal(tableFoldedByDefault(summary, true), false)
  })

  it('直播中还没打上终态标记时，含表也先按中间过程收', () => {
    const expert = { kind: 'actor' as const, answer: table, isFinal: false }
    assert.equal(tableFoldedByDefault(expert, true), true)
  })
})
