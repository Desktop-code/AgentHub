/**
 * 工作台「其它 / 自填」行的纯逻辑用例（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 平台固定追加「其它，我自己说」，且不写进模型的 choice.options；
 * 2) 模型自带同义项时展示去重，不出现两行「其它」；
 * 3) 平台行没有 option，点击路径不可能把它当答复提交；
 * 4) 自填答复校验：空 / 超长（不静默截断）/ 控制字符 / 合法文本 trim。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { choiceRowsOf } from '../src/components/runtime/workbench/chat/chipRows.ts'
import {
  WORKBENCH_ANSWER_MAX_CHARS,
  WORKBENCH_OTHER_CHOICE_LABEL,
  isOtherChoiceLabel,
  validateWorkbenchAnswer
} from '../src/components/runtime/workbench/chat/workbenchAnswerText.ts'

const TWO = [
  { id: 'vue3', label: 'Vue3 + Vite + TS' },
  { id: 'react', label: 'React + Vite + TS' }
]

test('平台在模型选项之后固定追加一行「其它，我自己说」', () => {
  const rows = choiceRowsOf(TWO)
  assert.equal(rows.length, TWO.length + 1)
  const modelRows = rows.slice(0, TWO.length)
  assert.deepEqual(modelRows.map((row) => row.text), ['Vue3 + Vite + TS', 'React + Vite + TS'])
  const last = rows[rows.length - 1]
  assert.equal(last.other, true)
  assert.equal(last.text, WORKBENCH_OTHER_CHOICE_LABEL)
  // 字母序号接在模型选项之后，不插进模型候选里
  assert.equal(modelRows.map((row) => row.letter).join(''), 'AB')
  assert.equal(last.letter, 'C')
})

test('平台自填行没有 option：点击不可能把文案当答复提交', () => {
  const rows = choiceRowsOf(TWO)
  const other = rows.find((row) => row.other)
  assert.ok(other, '必须存在平台自填行')
  assert.equal(other?.option, undefined)
  // 模型选项行仍保留原始对象，普通点击口径不变
  assert.deepEqual(rows[0].option, TWO[0])
})

test('模型自带同义「其它」时展示去重，只保留平台一行', () => {
  const rows = choiceRowsOf([
    { id: 'a', label: '方案A' },
    { id: 'other', label: '其它（请说明）' },
    { id: 'b', label: '方案B' }
  ])
  const labelCount = rows.filter((row) => row.other).length
  assert.equal(labelCount, 1, '平台行只能出现一次')
  assert.equal(rows.some((row) => row.text.includes('请说明')), false, '模型的其它项不应展示')
  // 去重后字母连续，不留下空位
  assert.equal(rows.map((row) => row.letter).join(''), 'ABC')
})

test('同义写法覆盖常见兜底词，真正的候选不受影响', () => {
  for (const label of ['其它', '其他', '其他选项', '自定义', '自定义回答', '自己说', '自行填写', '其它，我自己说']) {
    assert.equal(isOtherChoiceLabel(label), true, `应视为其它项：${label}`)
  }
  for (const label of ['方案A', 'Vue3', '其他部门的审批流程', '自定义字典']) {
    assert.equal(isOtherChoiceLabel(label), false, `不应误删：${label}`)
  }
})

test('没有模型选项时不追加平台行（纯回执 / 空列表）', () => {
  assert.equal(choiceRowsOf([]).length, 0)
})

test('自填校验：空与纯空白直接拒绝', () => {
  for (const raw of ['', '   ', '\n\t ']) {
    const check = validateWorkbenchAnswer(raw)
    assert.equal(check.ok, false)
    assert.equal(check.code, 'empty')
    assert.ok(check.message.length > 0)
  }
})

test('自填校验：长度按 2000 对齐，超长提示且不截断原文', () => {
  const atLimit = '答'.repeat(WORKBENCH_ANSWER_MAX_CHARS)
  assert.equal(validateWorkbenchAnswer(atLimit).ok, true)

  const tooLong = '答'.repeat(WORKBENCH_ANSWER_MAX_CHARS + 1)
  const check = validateWorkbenchAnswer(tooLong)
  assert.equal(check.ok, false)
  assert.equal(check.code, 'too-long')
  assert.ok(check.message.includes(String(WORKBENCH_ANSWER_MAX_CHARS)))
  // 不静默截断：返回的 text 仍是用户输入的完整内容，由用户自己删减
  assert.equal(Array.from(check.text).length, WORKBENCH_ANSWER_MAX_CHARS + 1)
})

test('自填校验：拦不可见控制字符，但允许换行与制表符', () => {
  const bad = validateWorkbenchAnswer('好的\u0000请继续')
  assert.equal(bad.ok, false)
  assert.equal(bad.code, 'control')

  const multiline = '第一行\n第二行\t缩进'
  assert.equal(validateWorkbenchAnswer(multiline).ok, true)
})

test('自填校验：通过时返回 trim 后的文本', () => {
  const check = validateWorkbenchAnswer('  就用 Vue3 方案  ')
  assert.equal(check.ok, true)
  assert.equal(check.code, '')
  assert.equal(check.text, '就用 Vue3 方案')
})
