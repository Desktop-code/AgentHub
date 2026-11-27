/**
 * 入参声明解析 / 出槽的本地用例。所属域：workflow（前端）。
 *
 * 只跑纯函数：声明形态（顶层 properties / required）、异常结构兜底、
 * 以及 AGENT / SUBGRAPH 按声明出槽的口径必须在这里钉死。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildInputDeclaration,
  declarationRequiredKeys,
  declaredInputSlots,
  duplicateDeclarationKeys,
  parseDeclarationFields
} from '../src/utils/workflowInputDeclaration.ts'
import {
  agentInputSlot,
  missingRequiredSlotKeys,
  planSlotRows,
  resolveInputSlots
} from '../src/utils/workflowInputSlots.ts'

const DECLARATION = {
  type: 'object',
  properties: {
    topic: { type: 'string', title: '主题', description: '要写什么' },
    audience: { type: 'string', required: true }
  },
  required: ['topic', 'ghost']
}

test('声明 → 编辑行：只认顶层 properties，required 取交集，没有 title 不编造', () => {
  const fields = parseDeclarationFields(DECLARATION)
  assert.deepEqual(fields.map((field) => field.key), ['topic', 'audience'])
  assert.equal(fields[0].title, '主题')
  assert.equal(fields[0].description, '要写什么')
  assert.equal(fields[0].required, true)
  // audience 不在 required 里：不因为字段定义里写了 required 就算必填
  assert.equal(fields[1].required, false)
  // JSON 文本输入同样能解析
  assert.deepEqual(parseDeclarationFields(JSON.stringify(DECLARATION)), fields)
})

test('声明异常结构按「没有声明」处理，不抛异常', () => {
  assert.deepEqual(parseDeclarationFields(null), [])
  assert.deepEqual(parseDeclarationFields('not json'), [])
  assert.deepEqual(parseDeclarationFields([]), [])
  assert.deepEqual(parseDeclarationFields({ type: 'object' }), [])
  assert.deepEqual(parseDeclarationFields({ properties: {} }), [])
})

test('编辑行 → 声明：空键丢弃、同名只留第一条、全空返回 null', () => {
  const built = buildInputDeclaration([
    { key: ' topic ', title: '主题', required: true, description: '' },
    { key: 'topic', title: '重复', required: false, description: '' },
    { key: '', title: '空键', required: true, description: '' },
    { key: 'audience', title: '', required: false, description: '受众' }
  ])
  assert.deepEqual(built, {
    type: 'object',
    properties: {
      topic: { type: 'string', title: '主题' },
      audience: { type: 'string', description: '受众' }
    },
    required: ['topic']
  })
  assert.equal(buildInputDeclaration([]), null)
  assert.equal(buildInputDeclaration([{ key: '  ', title: '', required: false, description: '' }]), null)
})

test('重复键 / 必填键读取', () => {
  const fields = [
    { key: 'a', title: '', required: false, description: '' },
    { key: ' a ', title: '', required: true, description: '' }
  ]
  assert.deepEqual(duplicateDeclarationKeys(fields), ['a'])
  assert.deepEqual(declarationRequiredKeys(DECLARATION), ['topic'])
})

test('按声明出槽：AGENT / SUBGRAPH 有声明就按声明，没有声明时 AGENT 仍给默认槽', () => {
  const declared = declaredInputSlots(DECLARATION)
  assert.deepEqual(declared.map((slot) => slot.key), ['topic', 'audience'])
  assert.equal(declared[0].origin, 'declaration')
  assert.equal(declared[0].required, true)
  assert.equal(declared[1].required, false)

  // AGENT：有声明时不再出现默认「用户输入」槽
  const agentSlots = resolveInputSlots('AGENT', '', {}, DECLARATION)
  assert.deepEqual(agentSlots.map((slot) => slot.key), ['topic', 'audience'])
  // 没有声明时退回默认槽
  assert.deepEqual(resolveInputSlots('AGENT', '', {}, null), [agentInputSlot()])
  // SUBGRAPH：没有声明就是空（允许自由添加），不编造槽
  assert.deepEqual(resolveInputSlots('SUBGRAPH', '', {}, null), [])
  assert.deepEqual(
    resolveInputSlots('SUBGRAPH', '', {}, DECLARATION).map((slot) => slot.key),
    ['topic', 'audience']
  )
})

test('必填声明槽未配来源会被列出；未配来源的槽不写进保存对象', () => {
  const slots = declaredInputSlots(DECLARATION)
  assert.deepEqual(missingRequiredSlotKeys(slots, { topic: '$.input.query' }), [])
  assert.deepEqual(missingRequiredSlotKeys(slots, {}), ['topic'])
  assert.deepEqual(missingRequiredSlotKeys(slots, { topic: '   ' }), ['topic'])

  const planned = planSlotRows<{ target: string; source?: string }>([], slots, (target) => ({
    target
  }))
  assert.deepEqual(planned.map((item) => item.row.target), ['topic', 'audience'])
  planned.forEach((item) => assert.ok(item.slot))
})
