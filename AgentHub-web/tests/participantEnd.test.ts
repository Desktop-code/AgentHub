/**
 * 参与者终态的本地用例。所属域：runtime（前端）。
 *
 * 关键回归：开过 begin 的参与者必须有 done / failed / aborted 终态；
 * 空结论也要收口，不能留一个一直转圈的 pending 空壳。
 *
 * 运行：在 AgentHub-web 目录执行 npm test（用例目录不进仓）。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendParticipantBegin,
  appendParticipantEnd,
  assistantTurn,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}

/**
 * 造一位「已经 begin、还在流式中」的参与者。
 *
 * @param id   参与者 id
 * @param name 展示名
 * @return 助手轮
 */
function startedTurn(id: string, name: string) {
  const turn = assistantTurn('t-1', 'streaming')
  appendParticipantBegin(turn, { id, name, kind: 'agent', begin: true })
  return turn
}

/**
 * 用真实投影链路把时间线收成卡片（与 ChatPanel 同一入口）。
 *
 * @param turn 助手轮
 * @return 参与者卡片
 */
function cardsOf(turn: ReturnType<typeof startedTurn>) {
  return buildParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    streaming: turn.status === 'streaming',
    entryName: 'Router',
    fallbackName: '智能助手'
  })
}

test('失败且没有正文：终态帧写出错误行，卡片退出进行态', () => {
  const turn = startedTurn('a-1', '制度知识专家')
  appendParticipantEnd(turn, { id: 'a-1', name: '制度知识专家', state: 'failed', note: '' })

  const route = turn.blocks.find((block) => block.kind === 'route')
  assert.equal(route?.closed, 'failed', 'route 块必须带上终态')

  const card = cardsOf(turn).find((item) => item.title === '制度知识专家')
  assert.ok(card, '参与者卡片必须存在')
  assert.equal(card?.pending, false, '失败收口后不得再显示进行态')
  assert.deepEqual(card?.errors, ['本轮执行失败'], '空 note 也要有一句明确的失败说明')
})

test('中止：终态说明进错误行，同样不再转圈', () => {
  const turn = startedTurn('a-2', '报告撰写专家')
  appendParticipantEnd(turn, {
    id: 'a-2',
    name: '报告撰写专家',
    state: 'aborted',
    note: '本轮已中止：超过整轮墙钟'
  })

  const card = cardsOf(turn).find((item) => item.title === '报告撰写专家')
  assert.equal(card?.pending, false)
  assert.deepEqual(card?.errors, ['本轮已中止：超过整轮墙钟'])
})

test('done 且一个字都没产出：空结论也要终态（补终态说明，不留在进行态）', () => {
  const turn = startedTurn('a-3', '制度知识专家')
  appendParticipantEnd(turn, {
    id: 'a-3',
    name: '制度知识专家',
    state: 'done',
    note: '本轮未产出正文（该步骤已结束）。'
  })

  const card = cardsOf(turn).find((item) => item.title === '制度知识专家')
  assert.equal(card?.pending, false, 'done 之后不得再进行态')
  assert.equal(card?.answer, '本轮未产出正文（该步骤已结束）。')
})

test('done 且正文已流过：不再重复补正文', () => {
  const turn = startedTurn('a-4', '制度知识专家')
  turn.blocks.push({
    kind: 'assistant',
    text: '制度要点整理如下',
    participantId: 'a-4',
    participantName: '制度知识专家'
  })
  appendParticipantEnd(turn, { id: 'a-4', name: '制度知识专家', state: 'done', note: '' })

  const card = cardsOf(turn).find((item) => item.title === '制度知识专家')
  assert.equal(card?.pending, false)
  assert.equal(card?.answer, '制度要点整理如下')
})

test('重复终态帧幂等：只收口一次，不叠错误行', () => {
  const turn = startedTurn('a-5', '制度知识专家')
  appendParticipantEnd(turn, { id: 'a-5', name: '制度知识专家', state: 'failed', note: '知识库不可用' })
  appendParticipantEnd(turn, { id: 'a-5', name: '制度知识专家', state: 'failed', note: '知识库不可用' })

  const errors = turn.blocks.filter((block) => block.kind === 'error')
  assert.equal(errors.length, 1)
})
