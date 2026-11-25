/**
 * 汇总不按名字再开一张「综合台」。所属域：runtime（前端）。
 *
 * 一个 begin id 只有一张卡。「方案结论：」之后的增量只进这一条汇总记录。
 * 没有 begin 的块不按名字开卡。本地用例，不进仓库。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendParticipantBegin,
  appendRoute,
  appendText,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, SUMMARY_REDISTRIBUTED_NOTE, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}

const DECISION = '路由决策\n已拆成 2 个问题，按序交给「综合台」「制度要点与报告」'

/**
 * 造一轮流式助手消息。
 *
 * @return 空的助手轮
 */
function liveTurn() {
  return {
    key: 't-summary',
    role: 'assistant' as const,
    text: '',
    blocks: [] as {
      id?: string
      kind: string
      text: string
      participantId?: string
      participantName?: string
      participantKind?: string
      participantDepth?: number
      begin?: boolean
    }[],
    phase: 'answering',
    status: 'done' as const,
    startTime: 0,
    entryParticipantId: 'gw-1'
  }
}

/**
 * 用 begin 泳道投影。不传候选池，kind / depth 只来自 begin。
 *
 * @param turn 本轮
 * @return 卡片
 */
function project(turn: ReturnType<typeof liveTurn>) {
  return buildParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    // 直播中：没有工作流快照的 begin 卡也要留着，收尾过滤不能把本轮独立目标拿掉
    streaming: true,
    entryName: '智能网关',
    fallbackName: '智能网关',
    entryParticipantId: turn.entryParticipantId
  })
}

test('汇总增量不按名字再开综合台：正文只在结论记录里出现一次', () => {
  const turn = liveTurn()
  appendRoute(turn, DECISION)
  appendParticipantBegin(turn, {
    id: 'p-scheme',
    name: '综合台',
    kind: 'scheme',
    routeKey: 'scheme',
    depth: 0,
    begin: true
  })
  appendParticipantBegin(turn, {
    id: 'p-policy',
    name: '制度知识专家',
    kind: 'agent',
    routeKey: 'policy',
    depth: 1,
    begin: true
  })
  appendText(turn, 'assistant', '制度要点：年假按工龄计算。', 'p-policy', '制度知识专家')
  appendParticipantBegin(turn, {
    id: 'p-writer',
    name: '报告撰写专家',
    kind: 'agent',
    routeKey: 'writer',
    depth: 1,
    begin: true
  })
  appendText(turn, 'assistant', '报告草稿已完成。', 'p-writer', '报告撰写专家')
  appendParticipantBegin(turn, {
    id: 'p-flow',
    name: '制度要点与报告',
    kind: 'workflow',
    routeKey: 'wf',
    depth: 0,
    begin: true
  })
  // 与直播一致：汇总标记到达时，当前归属仍是已经 begin 的方案，但标记本身不是 begin
  appendRoute(turn, '方案结论：综合台', 'p-scheme', '综合台', undefined, {
    participantKind: 'scheme',
    participantDepth: 0
  })
  // 思考和正文带方案 id 与名字，不带 kind / depth。旧逻辑会据此再开一张 Agent 卡
  appendText(turn, 'reasoning', '把两位专家的结论收成一段', 'p-scheme', '综合台')
  appendText(turn, 'assistant', '汇总正文：年假规则与报告已就绪', 'p-scheme', '综合台')

  const lanes = processLanes(turn.blocks)
  const schemeLanes = lanes.filter((lane) => lane.title === '路由到 综合台')
  assert.equal(schemeLanes.length, 1)
  assert.equal(schemeLanes[0].participantId, 'p-scheme')
  assert.equal(schemeLanes[0].participantKind, 'scheme')
  assert.equal(schemeLanes[0].participantDepth, 0)
  assert.equal(schemeLanes[0].steps.some((step) => step.text.includes('把两位专家的结论收成一段')), false)

  const cards = project(turn)
  assert.deepEqual(
    cards.map((card) => `${card.kind}:${card.title}`),
    [
      'gateway:智能网关',
      'actor:综合台',
      'actor:制度知识专家',
      'actor:报告撰写专家',
      'conclusion:综合台',
      'actor:制度要点与报告'
    ]
  )
  const scheme = cards.find((card) => card.kind === 'actor' && card.title === '综合台')
  const summary = cards.find((card) => card.kind === 'conclusion')
  assert.ok(scheme && summary)
  assert.equal(scheme!.roleLabel, '方案')
  assert.equal(scheme!.participantKind, 'scheme')
  assert.equal(scheme!.answer, '')
  assert.equal(scheme!.steps.some((step) => step.text.includes('把两位专家的结论收成一段')), false)
  assert.equal(summary!.answer, '汇总正文：年假规则与报告已就绪')
  assert.ok(summary!.steps.some((step) => step.text.includes('把两位专家的结论收成一段')))
  assert.equal(cards.filter((card) => (card.answer || '').includes('汇总正文')).length, 1)
  assert.equal(cards.filter((card) => card.kind === 'actor' && card.title === '综合台').length, 1)
})

test('begin 之前的思考并进同一张卡，不按名字先开一张', () => {
  const turn = liveTurn()
  appendText(turn, 'reasoning', '方案先看了一眼范围', 'p-scheme', '综合台')
  appendParticipantBegin(turn, {
    id: 'p-scheme',
    name: '综合台',
    kind: 'scheme',
    depth: 0,
    begin: true
  })
  const lanes = processLanes(turn.blocks)
  assert.equal(lanes.filter((lane) => lane.title === '路由到 综合台').length, 1)
  assert.equal(lanes.some((lane) => lane.title === '执行' && lane.participantId === 'p-scheme'), false)
  const scheme = lanes.find((lane) => lane.participantId === 'p-scheme')
  assert.equal(scheme?.participantKind, 'scheme')
  assert.ok(scheme?.steps.some((step) => step.text.includes('方案先看了一眼范围')))
})

test('没有 begin 的名字不开参与者卡', () => {
  const turn = liveTurn()
  appendParticipantBegin(turn, {
    id: 'p-scheme',
    name: '综合台',
    kind: 'scheme',
    depth: 0,
    begin: true
  })
  appendText(turn, 'reasoning', '这不是一次 begin', 'p-extra', '另一个综合台')
  const lanes = processLanes(turn.blocks)
  assert.equal(lanes.some((lane) => lane.title.startsWith('路由到') && lane.participantId === 'p-extra'), false)
  const cards = project(turn)
  assert.equal(cards.some((card) => card.participantId === 'p-extra'), false)
  assert.equal(cards.filter((card) => card.kind === 'actor').length, 1)
})

test('入口推理仍归入口块，不变成名为智能网关的参与者卡', () => {
  const turn = liveTurn()
  appendText(turn, 'reasoning', '先判断这是制度问题', 'gw-1', '智能网关')
  appendRoute(turn, DECISION)
  appendParticipantBegin(turn, {
    id: 'p-scheme',
    name: '综合台',
    kind: 'scheme',
    depth: 0,
    begin: true
  })
  const lanes = processLanes(turn.blocks)
  assert.equal(lanes.some((lane) => lane.title === '路由到 智能网关'), false)
  const cards = project(turn)
  const gateway = cards.find((card) => card.kind === 'gateway')
  assert.ok(gateway)
  assert.ok(gateway!.steps.some((step) => step.text.includes('先判断这是制度问题')))
  assert.equal(cards.some((card) => card.kind === 'actor' && card.title === '智能网关'), false)
})

test('回放：汇总正文紧跟标记，后到的拼接稿不再把这条记录刷成占位', () => {
  const summary = '汇总正文：年假规则与报告已就绪'
  const blocks = [
    { kind: 'route' as const, text: DECISION },
    {
      kind: 'route' as const,
      text: '路由到 综合台（scheme）',
      participantId: 'p-scheme',
      participantName: '综合台',
      participantKind: 'scheme',
      participantDepth: 0,
      begin: true
    },
    {
      kind: 'route' as const,
      text: '路由到 制度知识专家（policy）',
      participantId: 'p-policy',
      participantName: '制度知识专家',
      participantKind: 'agent',
      participantDepth: 1,
      begin: true
    },
    {
      kind: 'assistant' as const,
      text: '制度要点：年假按工龄计算。',
      participantId: 'p-policy',
      participantName: '制度知识专家'
    },
    { kind: 'route' as const, text: '方案结论：综合台' },
    { kind: 'reasoning' as const, text: '把两位专家收成一段' },
    { kind: 'assistant' as const, text: summary },
    {
      kind: 'route' as const,
      text: '路由到 制度要点与报告（wf）',
      participantId: 'p-flow',
      participantName: '制度要点与报告',
      participantKind: 'workflow',
      participantDepth: 0,
      begin: true
    },
    {
      kind: 'assistant' as const,
      text: `### 综合台\n${summary}\n\n### 制度要点与报告\n工作流已跑完`,
      participantId: 'p-flow',
      participantName: '制度要点与报告',
      participantKind: 'workflow'
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(blocks),
    blocks,
    streaming: false,
    entryName: '智能网关',
    fallbackName: '智能网关',
    entryParticipantId: 'gw-1'
  })
  const scheme = cards.find((card) => card.kind === 'actor' && card.title === '综合台')
  const conclusion = cards.find((card) => card.kind === 'conclusion' && card.title === '综合台')
  assert.ok(scheme && conclusion)
  assert.equal(cards.filter((card) => card.kind === 'actor' && card.title === '综合台').length, 1)
  assert.equal(conclusion!.answer, summary)
  assert.equal(conclusion!.terminalNote, '')
  assert.equal(conclusion!.terminalNote === SUMMARY_REDISTRIBUTED_NOTE, false)
  assert.equal(scheme!.answer, '')
  assert.equal(cards.filter((card) => (card.answer || '').includes(summary)).length, 1)
})
