/**
 * 检索明细抽屉的数据整形窄测（node:test，无浏览器）。所属域：前端 obs。
 *
 * 覆盖验收点：
 * 1) 只把 parentId = TOOL 的 RETRIEVE 子步收进抽屉；
 * 2) 抽屉顺序按执行序（tool.input → rewrite → 每库召回 → rerank → tool.output）；
 * 3) 多库按库分段，段内步骤不再串库；
 * 4) 片段表能解析出 chunkId 与四类分数，缺列保留后端给的 "-"；
 * 5) skipReason 有中文口径，未知码原样露出。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/retrieveTrace.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { ObsSpan } from '../src/types/obs.ts'
import {
  parseChunkRows,
  retrieveAttrs,
  retrieveStepTitle,
  retrieveStepsOf,
  segmentRetrieveSteps,
  skipReasonText,
  retrieveStepState
} from '../src/utils/retrieveTrace.ts'

/** 造一个最小可用 span；只填用例关心的字段 */
function span(patch: Partial<ObsSpan> & { id: number }): ObsSpan {
  return {
    parentId: null,
    stepNo: null,
    seq: 0,
    spanType: 'RETRIEVE',
    name: null,
    agentId: null,
    agentName: null,
    status: 'OK',
    startTime: null,
    endTime: null,
    latencyMs: null,
    inputText: null,
    outputText: null,
    errorSummary: null,
    depth: 0,
    ...patch
  }
}

test('只收 parentId = TOOL 的 RETRIEVE 子步', () => {
  const tool = span({ id: 10, spanType: 'TOOL', name: 'searchKnowledgeBase' })
  const childMap = new Map<number, ObsSpan[]>([
    [10, [span({ id: 11, parentId: 10, name: 'rewrite', stepNo: '2' })]]
  ])
  assert.deepEqual(
    retrieveStepsOf(tool, childMap).map((item) => item.id),
    [11]
  )
  // 非 TOOL 父节点不认检索子步（避免误伤未来别的埋点）
  const llm = span({ id: 20, spanType: 'LLM' })
  assert.deepEqual(retrieveStepsOf(llm, childMap), [])
})

test('顺序按执行序：tool.input → rewrite → 每库召回 → rerank → tool.output', () => {
  const steps = [
    span({ id: 7, name: 'tool.output', stepNo: '9' }),
    span({ id: 2, name: 'rewrite', stepNo: '2' }),
    span({ id: 1, name: 'tool.input', stepNo: '1' }),
    span({ id: 3, name: 'retrieve.vector', stepNo: '3', retrieveKbId: 1 }),
    span({ id: 4, name: 'retrieve.bm25', stepNo: '4', retrieveKbId: 1 }),
    span({ id: 5, name: 'rrf', stepNo: '5', retrieveKbId: 1 }),
    span({ id: 6, name: 'rerank', stepNo: '6' })
  ]
  const segments = segmentRetrieveSteps(steps)
  assert.deepEqual(
    segments.flatMap((segment) => segment.steps.map((step) => step.name)),
    ['tool.input', 'rewrite', 'retrieve.vector', 'retrieve.bm25', 'rrf', 'rerank', 'tool.output']
  )
})

test('多库按库分段，段内不串库', () => {
  const steps = [
    span({ id: 1, name: 'tool.input', stepNo: '1' }),
    span({ id: 2, name: 'rewrite', stepNo: '2' }),
    span({ id: 3, name: 'retrieve.vector', stepNo: '3.1', retrieveKbId: 1 }),
    span({ id: 4, name: 'retrieve.bm25', stepNo: '3.2', retrieveKbId: 1 }),
    span({ id: 5, name: 'rrf', stepNo: '3.3', retrieveKbId: 1 }),
    span({ id: 6, name: 'retrieve.vector', stepNo: '3.4', retrieveKbId: 2 }),
    span({ id: 7, name: 'retrieve.bm25', stepNo: '3.5', retrieveKbId: 2 }),
    span({ id: 8, name: 'rrf', stepNo: '3.6', retrieveKbId: 2 }),
    span({ id: 9, name: 'rerank', stepNo: '4' }),
    span({ id: 10, name: 'tool.output', stepNo: '5' })
  ]
  const segments = segmentRetrieveSteps(steps)
  assert.deepEqual(
    segments.map((segment) => segment.kbId),
    [null, null, 1, 2, null, null]
  )
  assert.deepEqual(
    segments[2].steps.map((step) => step.name),
    ['retrieve.vector', 'retrieve.bm25', 'rrf']
  )
})

test('片段表解析：chunkId + 四类分数，缺列保留 -', () => {
  const text = [
    '向量召回 2 条',
    '1. chunkId=11 docId=101 kbId=1 score=0.9120 vector=0.9120 bm25=- rrf=- rerank=- hitBy=vector',
    '   title: 产品手册.md',
    '   snippet: 退货政策以签收日起 7 天为准',
    '2. chunkId=12 docId=102 kbId=1 score=- vector=- bm25=0.3310 rrf=- rerank=- hitBy=bm25',
    '   snippet: 无标题片段'
  ].join('\n')
  const rows = parseChunkRows(text)
  assert.equal(rows.length, 2)
  assert.equal(rows[0].chunkId, '11')
  assert.equal(rows[0].score, '0.9120')
  assert.equal(rows[0].bm25, '-')
  assert.equal(rows[0].title, '产品手册.md')
  assert.equal(rows[1].chunkId, '12')
  assert.equal(rows[1].vector, '-')
  assert.equal(rows[1].snippet, '无标题片段')
})

test('非片段正文不产出行（不瞎猜格式）', () => {
  assert.deepEqual(parseChunkRows('跳过：未开启精排'), [])
  assert.deepEqual(parseChunkRows(null), [])
})

test('skipReason 有人话口径，未知码原样露出', () => {
  assert.equal(skipReasonText('rerank_disabled'), '未开启精排')
  assert.equal(skipReasonText('brand_new_reason'), 'brand_new_reason')
  assert.equal(skipReasonText(null), '')
})

test('跳过状态优先于状态字段，未知步骤名不隐藏', () => {
  const skipped = span({
    id: 1,
    name: 'rerank',
    status: 'OK',
    attrsJson: '{"skipped":true,"skipReason":"rerank_disabled","brief":"跳过：未开启精排"}'
  })
  assert.equal(retrieveStepState(skipped), 'skip')
  assert.equal(retrieveAttrs(skipped.attrsJson).skipReason, 'rerank_disabled')
  assert.equal(retrieveStepTitle('some.new.step'), 'some.new.step')
  assert.equal(retrieveStepTitle('retrieve.vector'), '向量召回')
})
