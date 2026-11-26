/**
 * 工作流点选映射：出参取值域、同名带入、循环打回预填与往返兼容。所属域：workflow（前端）。
 *
 * 用例只跑 utils 纯函数，不挂组件：点选交互的标签由组件负责，
 * 但落库路径、补洞范围、预填判定必须在这里钉死，避免换 UI 时把契约改坏。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyMappingPathEdit,
  applyOutputPathEdit,
  buildMappingObject,
  buildOutputMapping,
  isMappingPathVisible,
  loopFeedbackPath,
  mappingFieldLabel,
  outputFieldPath,
  parseLoopFeedbackPath,
  parseMappingObject,
  parseOutputRow,
  planSameNameFill,
  singleBackSourcePrefill
} from '../src/utils/workflowMapping.ts'
import type { MappingUiRow, OutputMappingRow } from '../src/utils/workflowMapping.ts'
import type { WorkflowMappingSource } from '../src/types/workflow.ts'

/** 前置节点候选：order 靠前表示距离更近，同名时优先取第一项 */
const SOURCES: WorkflowMappingSource[] = [
  { id: 'n1', code: 'review', name: '审稿', label: '审稿', outputKeys: ['query', 'code'] },
  { id: 'n2', code: 'draft', name: '起草', label: '起草', outputKeys: ['query'] }
]

test('出参点选只产出 $.output.*，不出现前置节点或运行入参', () => {
  assert.equal(outputFieldPath('text'), '$.output.text')
  assert.equal(outputFieldPath(''), '$.output')
  assert.equal(outputFieldPath('  '), '$.output')

  const rows: OutputMappingRow[] = [
    { target: 'answer', field: 'text', picked: true, manual: false, text: '' },
    { target: 'whole', field: '', picked: true, manual: false, text: '' }
  ]
  const built = buildOutputMapping(rows)
  assert.deepEqual(built, { answer: '$.output.text', whole: '$.output' })
  // 点选行永远落在 $.output 域，不会拼出 $.outputs / $.input
  Object.values(built).forEach((path) => assert.match(path, /^\$\.output(\.|$)/))
})

test('出参新行还没选字段时整行不落库，不把空值猜成整段产出', () => {
  const rows: OutputMappingRow[] = [
    { target: 'answer', field: '', picked: false, manual: false, text: '' }
  ]
  assert.deepEqual(buildOutputMapping(rows), {})
})

test('出参历史写法不丢：非 $.output 的值留到手写原样落库', () => {
  const parsed = parseOutputRow('answer', '$.input.query')
  assert.equal(parsed.manual, true)
  assert.equal(parsed.text, '$.input.query')

  const built = buildOutputMapping([parsed])
  assert.deepEqual(built, { answer: '$.input.query' })

  // 前置节点写法同样不会被点选“纠正”，避免把旧配置猜错
  assert.equal(parseOutputRow('x', '$.outputs.review.text').manual, true)
})

test('出参 $.output 往返一致，字段名仍可带嵌套路径', () => {
  const parsed = parseOutputRow('answer', '$.output.data.items')
  assert.equal(parsed.manual, false)
  assert.equal(parsed.field, 'data.items')
  assert.deepEqual(buildOutputMapping([parsed]), { answer: '$.output.data.items' })
})

test('按同名带入只补空行：已有来源、无同名候选、整段入参都不动', () => {
  const rows: MappingUiRow[] = [
    { target: 'query', sourceKind: 'unset', sourceField: '', text: '' },
    { target: 'code', sourceKind: 'node', sourceNodeId: 'n2', sourceField: 'text', text: '' },
    { target: 'note', sourceKind: 'unset', sourceField: '', text: '' },
    { target: 'message', sourceKind: 'input', sourceField: '', text: '' }
  ]
  const plan = planSameNameFill(rows, SOURCES)

  // 只命中第 1 行；距离更近的 review 优先
  assert.deepEqual(plan, [
    { target: 'query', sourceNodeId: 'n1', sourceField: 'query', rowIndex: 0 }
  ])
  // 规划阶段不改原行，避免调用方还没确认就覆盖
  assert.equal(rows[1].sourceNodeId, 'n2')
  assert.equal(rows[3].sourceKind, 'input')
})

test('按同名带入不新增行：没有空行时返回空计划', () => {
  const rows: MappingUiRow[] = [
    { target: 'query', sourceKind: 'input', sourceField: 'query', text: '' }
  ]
  assert.deepEqual(planSameNameFill(rows, SOURCES), [])
})

test('循环一条打回边预填来源，多条留空且不猜', () => {
  const one = [SOURCES[0]]
  assert.equal(singleBackSourcePrefill('', one), '$.outputs.review')
  // 已有配置不覆盖
  assert.equal(singleBackSourcePrefill('$.outputs.old', one), '$.outputs.old')
  // 多条 / 没有时留空，交给用户点选
  assert.equal(singleBackSourcePrefill('', SOURCES), '')
  assert.equal(singleBackSourcePrefill('', []), '')
})

test('循环 feedbackPath 点选往返：只认 $.outputs.*，其它写法原样保留', () => {
  const parsed = parseLoopFeedbackPath('$.outputs.review.text')
  assert.equal(parsed.manual, false)
  assert.equal(parsed.sourceCode, 'review')
  assert.equal(parsed.field, 'text')
  assert.equal(loopFeedbackPath('review', 'text'), '$.outputs.review.text')
  assert.equal(loopFeedbackPath('review', ''), '$.outputs.review')
  assert.equal(loopFeedbackPath('', 'text'), '')

  // 历史 / 高级写法不猜来源，留在手写
  const legacy = parseLoopFeedbackPath('$.vars.loop.feedback.text')
  assert.equal(legacy.manual, true)
  assert.equal(legacy.text, '$.vars.loop.feedback.text')
})

test('入参映射保存形状与现网兼容：解析后再拼回是同一份对象', () => {
  const ctx = { sources: SOURCES, startCodes: ['start'] }
  const mapping = {
    query: '$.input.query',
    answer: '$.outputs.review.text',
    round: '$.vars.loop.iteration',
    fixed: '固定提示词'
  }
  const rows = parseMappingObject(mapping, ctx)
  assert.deepEqual(buildMappingObject(rows, ctx), mapping)
})

test('点选默认不展示完整路径：只有展开高级才出现，手写由输入框承担', () => {
  // 默认点选：路径灰字不铺在行下
  assert.equal(isMappingPathVisible('pick', false), false)
  // 打开高级：可以核对 / 微调路径
  assert.equal(isMappingPathVisible('pick', true), true)
  // 手写模式：路径直接在输入框里，不再额外铺一条灰字
  assert.equal(isMappingPathVisible('manual', false), false)
  assert.equal(isMappingPathVisible('manual', true), false)
})

test('自定义值标签是「固定值」，切回运行入参 / 节点后恢复「字段」', () => {
  assert.equal(mappingFieldLabel('custom'), '固定值')
  ;(['unset', 'input', 'node', 'vars'] as const).forEach((kind) => {
    assert.equal(mappingFieldLabel(kind), '字段')
  })
})

test('高级里改入参路径：能归一就回点选，常量仍按固定值原文保存', () => {
  const ctx = { sources: SOURCES, startCodes: ['start'] }
  const base: MappingUiRow = {
    target: 'query',
    sourceKind: 'custom',
    sourceField: '',
    text: '旧常量'
  }

  // 改成前置节点路径：回到点选，字段与落库路径保持一致
  const picked = applyMappingPathEdit(base, '$.outputs.review.code', ctx)
  assert.equal(picked.sourceKind, 'node')
  assert.equal(picked.sourceNodeId, 'n1')
  assert.equal(picked.sourceField, 'code')
  assert.deepEqual(buildMappingObject([picked], ctx), { query: '$.outputs.review.code' })

  // 改成常量：仍落固定值原文，不被当成路径
  const constant = applyMappingPathEdit(base, '写死的内容', ctx)
  assert.equal(constant.sourceKind, 'custom')
  assert.equal(constant.text, '写死的内容')
  assert.deepEqual(buildMappingObject([constant], ctx), { query: '写死的内容' })

  // 清空：回到未选择，不落空路径
  const cleared = applyMappingPathEdit(base, '   ', ctx)
  assert.equal(cleared.sourceKind, 'unset')
  assert.deepEqual(buildMappingObject([cleared], ctx), {})
})

test('高级里改出参路径：能归一回到点选，清空不猜整段产出', () => {
  const base: OutputMappingRow = {
    target: 'answer',
    field: '',
    picked: false,
    manual: false,
    text: ''
  }

  const picked = applyOutputPathEdit(base, '$.output.data.items')
  assert.equal(picked.manual, false)
  assert.equal(picked.field, 'data.items')
  assert.deepEqual(buildOutputMapping([picked]), { answer: '$.output.data.items' })

  // 历史写法留到手写原样保存，不被点选“纠正”
  const manual = applyOutputPathEdit(base, '$.input.query')
  assert.equal(manual.manual, true)
  assert.deepEqual(buildOutputMapping([manual]), { answer: '$.input.query' })

  // 清空：回到未选字段，整行不落库
  const cleared = applyOutputPathEdit(picked, '  ')
  assert.equal(cleared.picked, false)
  assert.deepEqual(buildOutputMapping([cleared]), {})
})

test('改路径不改变保存形状：仍是「目标字段 → 路径字符串」扁平对象', () => {
  const ctx = { sources: SOURCES, startCodes: ['start'] }
  const base: MappingUiRow = {
    target: 'answer',
    sourceKind: 'node',
    sourceNodeId: 'n1',
    sourceField: 'text',
    text: ''
  }
  const edited = applyMappingPathEdit(base, '$.vars.final', ctx)
  const built = buildMappingObject([edited], ctx)
  assert.deepEqual(built, { answer: '$.vars.final' })
  Object.values(built).forEach((value) => assert.equal(typeof value, 'string'))
})
