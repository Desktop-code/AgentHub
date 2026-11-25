/**
 * 结果框不把检索 JSON 原样铺给用户。所属域：runtime（前端展示）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { presentVisibleResult } from '../src/components/runtime/workbench/workbenchVisibleText.ts'
import { sanitizeAnswerText } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
import { stripRouteDecisionJson } from '../src/components/runtime/workbench/workbenchMessageMeta.ts'
import { stripWorkbenchLeadHeadings } from '../src/utils/workbenchFriendlyError.ts'
import { appendText, assistantTurn, turnsFromEvents } from '../src/components/runtime/trialMessages.ts'

/**
 * 与结果框同一条展示链：投影先做行级脱敏，渲染时再走 presentVisibleResult。
 *
 * @param raw 直播块原文或回放事件正文
 * @return 用户看见的正文
 */
function visibleAnswer(raw: string): string {
  return presentVisibleResult(stripRouteDecisionJson(stripWorkbenchLeadHeadings(sanitizeAnswerText(raw))))
}

const HITS = JSON.stringify([
  {
    content: '年假须提前申请。',
    title: '员工手册',
    source: 'handbook.pdf',
    score: 0.91,
    hitBy: 'vector'
  }
])

test('检索命中 JSON 收成引用文本，不带分数和命中方式', () => {
  const text = presentVisibleResult(HITS)
  assert.equal(text.includes('**员工手册**'), true)
  assert.equal(text.includes('年假须提前申请。'), true)
  assert.equal(text.includes('来源：handbook.pdf'), true)
  assert.equal(text.includes('score'), false)
  assert.equal(text.includes('hitBy'), false)
  assert.equal(text.includes('{'), false)
})

test('围栏里的检索 JSON 同样收成引用文本', () => {
  const text = presentVisibleResult('```json\n' + HITS + '\n```')
  assert.equal(text.includes('员工手册'), true)
  assert.equal(text.includes('```'), false)
})

test('人话正文里嵌着的检索 JSON 只替换那一段', () => {
  const text = presentVisibleResult('依据如下：\n' + HITS)
  assert.equal(text.startsWith('依据如下：'), true)
  assert.equal(text.includes('员工手册'), true)
  assert.equal(text.includes('hitBy'), false)
})

test('普通 Markdown 原样留下', () => {
  const raw = '年假规则如下：\n\n1. 提前申请\n2. **主管审批**'
  assert.equal(presentVisibleResult(raw), raw)
})

test('不是检索结构的 JSON 保持原样', () => {
  const raw = '{"name":"年假","days":5}'
  assert.equal(presentVisibleResult(raw), raw)
})

test('回放多包一层 JSON 字符串时，与直播解析后的原文收成同一段', () => {
  const live = presentVisibleResult(HITS)
  const replay = presentVisibleResult(JSON.stringify(HITS))
  assert.equal(replay, live)
  assert.equal(replay.includes('hitBy'), false)
  assert.equal(replay.includes('{'), false)
})

test('回放把引号写成反斜杠时，仍收成同一段引用', () => {
  const live = presentVisibleResult(HITS)
  const replay = presentVisibleResult(HITS.replace(/"/g, '\\"'))
  assert.equal(replay, live)
})

test('命中被塞进 text 字段时，不把内部 JSON 串当正文', () => {
  const wrapped = JSON.stringify([{ type: 'text', text: HITS }])
  const text = presentVisibleResult(wrapped)
  assert.equal(text.includes('**员工手册**'), true)
  assert.equal(text.includes('hitBy'), false)
  assert.equal(text.includes('{'), false)
})

test('截断在字段中间时，已写全的标题和正文仍收成引用', () => {
  const cut = HITS.slice(0, HITS.length - 8) + '\n已截断'
  const text = presentVisibleResult(cut)
  assert.equal(text.includes('**员工手册**'), true)
  assert.equal(text.includes('年假须提前申请。'), true)
  assert.equal(text.includes('hitBy'), false)
})

test('多包一层的普通业务 JSON 保持原样', () => {
  const raw = JSON.stringify('{"name":"年假","days":5}')
  assert.equal(presentVisibleResult(raw), raw)
})

test('直播块原文与回放事件正文走同一条展示后一致', () => {
  const live = assistantTurn('live', 'done')
  appendText(live, 'assistant', HITS, 'p1', '知识专家')
  const liveRaw = live.blocks.find((block) => block.kind === 'assistant')?.text || ''
  const replay = turnsFromEvents([
    { id: 1, sessionId: 1, seq: 1, eventType: 'user', contentText: '年假怎么算' },
    { id: 2, sessionId: 1, seq: 2, eventType: 'assistant', contentText: JSON.stringify(HITS) }
  ])
  const replayRaw = replay.flatMap((turn) => turn.blocks).find((block) => block.kind === 'assistant')?.text || ''
  assert.equal(liveRaw, HITS)
  assert.equal(replayRaw, JSON.stringify(HITS))
  assert.equal(visibleAnswer(replayRaw), visibleAnswer(liveRaw))
  assert.equal(visibleAnswer(replayRaw).includes('**员工手册**'), true)
  assert.equal(visibleAnswer(replayRaw).includes('hitBy'), false)
})
