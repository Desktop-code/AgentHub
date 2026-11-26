/**
 * 工作流入参按定义出槽：schema 解析、AGENT 默认槽、空键剔除与换绑归并。所属域：workflow（前端）。
 *
 * 用例只跑 utils 纯函数，不挂组件：出槽口径、槽位归属和保存形状必须在这里钉死，
 * 避免以后调整 UI 时把「定义槽不可改名 / 空来源不落库」的约定改坏。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  agentDefaultInputKey,
  dropEmptyInputMapping,
  isDefinitionSlot,
  missingRequiredSlotKeys,
  parseToolInputSlots,
  planSlotRows,
  resolveInputSlots,
  slotSignature
} from '../src/utils/workflowInputSlots.ts'
import { buildMappingObject, planSameNameFill } from '../src/utils/workflowMapping.ts'
import type { MappingUiRow } from '../src/utils/workflowMapping.ts'
import type { WorkflowMappingSource } from '../src/types/workflow.ts'

/** 工具 schema：city 必填、days 有 title、filter 是嵌套对象（只算一个顶层槽） */
const TOOL_SCHEMA = JSON.stringify({
  type: 'object',
  properties: {
    city: { type: 'string', description: '城市名称' },
    days: { type: 'integer', title: '天数', description: '预报天数' },
    filter: {
      type: 'object',
      title: '筛选',
      properties: { keyword: { type: 'string' } }
    }
  },
  required: ['city', 'ghost']
})

/** 编辑器行形状（只关心归并需要的 target，其余字段原样带过） */
interface FakeRow {
  target: string
  source?: string
}

test('按 inputSchemaJson 解析顶层槽：title / description / required 取自定义，无 title 不编造', () => {
  const slots = parseToolInputSlots(TOOL_SCHEMA)

  assert.deepEqual(slots.map((slot) => slot.key), ['city', 'days', 'filter'])
  // city 没有 title：保持空串，不翻译、不编造中文名
  assert.equal(slots[0].title, '')
  assert.equal(slots[0].description, '城市名称')
  assert.equal(slots[0].required, true)
  assert.equal(slots[1].title, '天数')
  assert.equal(slots[1].required, false)
  // 嵌套对象只算一个槽，不展开成 filter.keyword
  assert.equal(slots[2].key, 'filter')
  assert.equal(slots.length, 3)
  // required 里没有 properties 的名字不出槽，避免出现配不了的必填项
  assert.equal(slots.some((slot) => slot.key === 'ghost'), false)
  // 槽位来自工具定义，不是 AGENT 默认槽
  slots.forEach((slot) => assert.equal(slot.origin, 'tool'))
})

test('空 / 非法 / 没有 properties 的 schema 都按「没有定义」处理', () => {
  assert.deepEqual(parseToolInputSlots(''), [])
  assert.deepEqual(parseToolInputSlots(undefined), [])
  assert.deepEqual(parseToolInputSlots('not json'), [])
  assert.deepEqual(parseToolInputSlots('{"type":"object"}'), [])
  assert.deepEqual(parseToolInputSlots('{"properties":{}}'), [])
  assert.deepEqual(parseToolInputSlots('["city"]'), [])
})

test('AGENT 默认槽的键：优先 query，其次 message → text → content，都没有用 query', () => {
  assert.equal(agentDefaultInputKey({}), 'query')
  assert.equal(agentDefaultInputKey(undefined), 'query')
  // 已有手填行（reply）不影响默认键
  assert.equal(agentDefaultInputKey({ reply: '固定值' }), 'query')
  assert.equal(agentDefaultInputKey({ message: '$.input.query' }), 'message')
  assert.equal(agentDefaultInputKey({ message: 'a', query: 'b' }), 'query')
  assert.equal(agentDefaultInputKey({ content: 'c', text: 't' }), 'text')
  assert.equal(agentDefaultInputKey({ content: 'c' }), 'content')
})

test('按节点类型出槽：AGENT 只有默认「用户输入」，TOOL 读 schema，其它节点没有定义', () => {
  const agentSlots = resolveInputSlots('AGENT', '', {})
  assert.equal(agentSlots.length, 1)
  assert.equal(agentSlots[0].title, '用户输入')
  assert.equal(agentSlots[0].key, 'query')
  // AGENT 默认槽不强制必填，不额外增加发布拦截
  assert.equal(agentSlots[0].required, false)
  // 已有 reply 不影响默认槽，只有这一条
  assert.equal(resolveInputSlots('AGENT', '', { reply: 'x' })[0].key, 'query')

  assert.deepEqual(resolveInputSlots('TOOL', TOOL_SCHEMA, {}).map((slot) => slot.key), [
    'city',
    'days',
    'filter'
  ])
  // 未绑定工具 / schema 为空：没有定义，保持自由添加
  assert.deepEqual(resolveInputSlots('TOOL', '', {}), [])
  // 开始 / 结束节点本刀不做入参表
  assert.deepEqual(resolveInputSlots('START', TOOL_SCHEMA, {}), [])
  assert.deepEqual(resolveInputSlots('END', TOOL_SCHEMA, {}), [])
})

test('保存前剔除空键：空串 / 全空白不写入，键去空白，值原样保留', () => {
  assert.deepEqual(
    dropEmptyInputMapping({
      query: '',
      reply: '   ',
      ' note ': ' 固定值 ',
      empty: undefined as unknown as string
    }),
    { note: ' 固定值 ' }
  )
  assert.deepEqual(dropEmptyInputMapping(undefined), {})
  assert.deepEqual(dropEmptyInputMapping({}), {})
})

test('定义槽与已有行归并：同名保留已配来源，新槽先空着，旧键降为多余行', () => {
  const slots = parseToolInputSlots(TOOL_SCHEMA)
  const existing: FakeRow[] = [
    { target: 'city', source: 'kept' },
    { target: 'legacy', source: 'old' },
    { target: 'days', source: 'kept2' }
  ]
  const created: string[] = []

  const planned = planSlotRows(existing, slots, (target) => {
    created.push(target)
    return { target, source: 'new' }
  })

  // 定义槽按定义顺序在前，其它手填行按原顺序在后
  assert.deepEqual(planned.map((item) => item.row.target), ['city', 'days', 'filter', 'legacy'])
  // 同名键沿用原行对象：已配来源不被覆盖
  assert.equal(planned[0].row, existing[0])
  assert.equal(planned[0].row.source, 'kept')
  // 新槽先空着（工厂只负责建行，来源留给用户选）
  assert.equal(planned[2].row.source, 'new')
  // 不再属于新定义的旧键：保留为手填行（可删），不会凭空消失
  assert.equal(planned[3].slot, undefined)
  assert.deepEqual(created, ['filter'])
})

test('定义槽不可改名、不可删：目标固定为槽键，只有非定义行可区分出来', () => {
  const slots = parseToolInputSlots(TOOL_SCHEMA)
  const planned = planSlotRows<FakeRow>([], slots, (target) => ({ target }))

  assert.deepEqual(planned.map((item) => item.row.target), ['city', 'days', 'filter'])
  planned.forEach((item) => {
    assert.ok(item.slot)
    // 目标字段就是定义里的参数名，组件据此渲染成只读文本而不是输入框
    assert.equal(item.row.target, item.slot?.key)
    assert.equal(isDefinitionSlot(slots, item.row.target), true)
  })
  // 手填行不属于定义槽，可以编辑 / 删除
  assert.equal(isDefinitionSlot(slots, 'legacy'), false)
})

test('换绑工具：同名键保留来源，新定义的槽补空行，旧键变成「未在工具定义中」', () => {
  const before = parseToolInputSlots(TOOL_SCHEMA)
  const rows: FakeRow[] = planSlotRows<FakeRow>([], before, (target) => ({ target })).map((item) => {
    if (item.slot?.key === 'city') {
      return { ...item.row, source: '$.input.query' }
    }
    return item.row
  })

  const after = parseToolInputSlots(
    JSON.stringify({
      type: 'object',
      properties: { city: { type: 'string' }, url: { type: 'string' } },
      required: ['city', 'url']
    })
  )
  const planned = planSlotRows(rows, after, (target) => ({ target }))

  assert.deepEqual(planned.map((item) => item.row.target), ['city', 'url', 'days', 'filter'])
  // 同名键保留已配来源
  assert.equal(planned[0].row.source, '$.input.query')
  // 新槽先空着
  assert.equal(planned[1].row.source, undefined)
  // 旧键不再属于新定义：留作手填行，交给组件标注「未在工具定义中」
  assert.equal(planned[2].slot, undefined)
  assert.equal(planned[3].slot, undefined)
})

test('定义签名只随定义变化：换工具才重排行，用户填来源不触发', () => {
  const slots = parseToolInputSlots(TOOL_SCHEMA)
  const sameValues = parseToolInputSlots(TOOL_SCHEMA)
  assert.equal(slotSignature(slots), slotSignature(sameValues))

  const otherSchema = parseToolInputSlots('{"type":"object","properties":{"url":{"type":"string"}}}')
  assert.notEqual(slotSignature(slots), slotSignature(otherSchema))
})

test('必填槽未配来源会被列出；空串 / 全空白等同没配', () => {
  const slots = parseToolInputSlots(TOOL_SCHEMA)
  assert.deepEqual(missingRequiredSlotKeys(slots, { city: '$.input.query' }), [])
  assert.deepEqual(missingRequiredSlotKeys(slots, {}), ['city'])
  assert.deepEqual(missingRequiredSlotKeys(slots, { city: '   ' }), ['city'])
  assert.deepEqual(missingRequiredSlotKeys(slots, { city: '固定城市' }), [])
  // AGENT 默认槽不是必填，不产生发布拦截
  assert.deepEqual(missingRequiredSlotKeys(resolveInputSlots('AGENT', '', {}), {}), [])
})

test('只展示、未配来源的槽不写入 inputMapping：保存对象里没有空字符串键', () => {
  const ctx = { sources: [], startCodes: ['start'] }
  const rows: MappingUiRow[] = [
    { target: 'city', sourceKind: 'input', sourceField: 'query', text: '' },
    { target: 'days', sourceKind: 'unset', sourceField: '', text: '' },
    { target: 'reply', sourceKind: 'custom', sourceField: '', text: '' }
  ]

  const built = dropEmptyInputMapping(buildMappingObject(rows, ctx))
  assert.deepEqual(built, { city: '$.input.query' })
  // AGENT 落一个空的 query 会挡住无映射兜底，这里必须真的没有该键
  assert.equal(Object.prototype.hasOwnProperty.call(built, 'days'), false)
  assert.equal(Object.prototype.hasOwnProperty.call(built, 'reply'), false)
})

test('按同名带入只补空来源：不覆盖已配行、不新增定义之外的目标', () => {
  const sources: WorkflowMappingSource[] = [
    { id: 'n1', code: 'review', name: '审稿', label: '审稿', outputKeys: ['city', 'query'] }
  ]
  const rows: MappingUiRow[] = [
    { target: 'city', sourceKind: 'unset', sourceField: '', text: '' },
    { target: 'query', sourceKind: 'input', sourceField: 'query', text: '' }
  ]

  const plan = planSameNameFill(rows, sources)
  assert.deepEqual(plan, [{ target: 'city', sourceNodeId: 'n1', sourceField: 'city', rowIndex: 0 }])

  // 应用计划后：空槽补上来源，已有来源的行原样保留
  plan.forEach((item) => {
    const row = rows[item.rowIndex]
    row.sourceKind = 'node'
    row.sourceNodeId = item.sourceNodeId
    row.sourceField = item.sourceField
  })
  assert.equal(rows[0].sourceKind, 'node')
  assert.equal(rows[1].sourceKind, 'input')
  // 不新增行：目标仍只有定义里已有的两个
  assert.deepEqual(rows.map((row) => row.target), ['city', 'query'])
})
