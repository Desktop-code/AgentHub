/**
 * 「工作流等待回复 → 同一条运行续跑」的投影单测（本地 tests/，不进仓）。
 *
 * <p>钉住三件会直接骗到用户的事：
 * 1) participant begin 占位行不是入口判定，不能合成「智能网关 → X」；
 * 2) 回放里的续跑轮要标记为 continuation，投影不再等入口身份；
 * 3) 续跑后没有节点仍在 WAITING_REPLY，旧 chips 不能继续显示。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock, ChatTurn } from '../src/types/runtime.ts'
import {
  appendWorkflow,
  entryRouteTextsOf,
  processLanes,
  turnsFromEvents
} from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { buildThinkTimeline } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'

const BEGIN_ROUTE = '路由到 小游戏研发（mini_game_dev）'

/** 卡片视图：续跑后询问节点已成功，没有节点再等待。 */
const RESUMED_CARD = {
  name: '小游戏研发',
  code: 'mini_game_dev',
  status: 'RUNNING',
  answer: '请问你想用哪种语言实现？\n\n已按 Java 开始实现',
  nodes: [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', note: '已完成' },
    { code: 'impl', name: '游戏实现', type: 'AGENT', status: 'RUNNING', note: '执行中' }
  ]
}

/** 造一条本地事件：只填回放需要的字段。 */
function event(seq: number, eventType: string, contentText: string, summary = '') {
  return { id: seq, sessionId: 7, seq, eventType, contentText, summary }
}

/** 走真实回放：用户 → participant begin → 工作流卡 → 助手正文。 */
function replayResumedTurn(): ChatTurn[] {
  return turnsFromEvents([
    event(1, 'user', JSON.stringify({ text: '做一个2048小游戏' })),
    event(2, 'participant', JSON.stringify({
      id: 'p-0-root-mini_game_dev',
      name: '小游戏研发',
      kind: 'workflow',
      routeKey: 'mini_game_dev',
      depth: 0,
      begin: true
    })),
    event(3, 'workflow', JSON.stringify(RESUMED_CARD), '工作流「小游戏研发」执行中'),
    event(4, 'assistant', '已按 Java 开始实现')
  ])
}

test('入口路由文案：participant begin 占位行不算入口判定', () => {
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: BEGIN_ROUTE, begin: true, participantKind: 'workflow' },
    { kind: 'route', text: '路由到 小游戏研发（mini_game_dev），依据：用户要做2048' }
  ]
  assert.deepEqual(entryRouteTextsOf(blocks), ['路由到 小游戏研发（mini_game_dev），依据：用户要做2048'])
})

test('回放续跑轮：标记 continuation，且没有可当作入口的真实路由', () => {
  const turns = replayResumedTurn()
  assert.equal(turns.length, 2)
  const resumed = turns[1]
  assert.equal(resumed.continuation, true)
  assert.deepEqual(entryRouteTextsOf(resumed.blocks), [])
})

test('续跑轮投影：不出现智能网关，直接出工作流卡', () => {
  const turns = replayResumedTurn()
  const resumed = turns[1]
  const cards = buildParticipantFeed({
    routeTexts: entryRouteTextsOf(resumed.blocks),
    lanes: processLanes(resumed.blocks),
    blocks: resumed.blocks,
    streaming: false,
    entryName: '智能网关',
    fallbackName: '智能网关',
    expectEntry: resumed.continuation !== true
  })

  assert.equal(cards.some((card) => card.kind === 'gateway'), false)
  const workflow = cards.find((card) => card.workflow)
  assert.ok(workflow, '应有一张工作流卡')
  assert.equal(workflow.participantId, 'p-0-root-mini_game_dev')
})

test('续跑轮即使混进 route 文案，expectEntry=false 也不合成网关卡', () => {
  const steps = buildThinkTimeline({
    routeTexts: [BEGIN_ROUTE],
    lanes: [{
      kind: 'agent',
      // 生产投影的泳道标题已去掉 route_key；这里保持同样的形状
      title: '路由到 小游戏研发',
      participantId: 'p-0-root-mini_game_dev',
      participantKind: 'workflow',
      participantDepth: 0,
      steps: []
    }],
    streaming: false,
    entryName: '智能网关',
    fallbackName: '小游戏研发',
    expectEntry: false,
    workflowSnapshots: [{ participantId: 'p-0-root-mini_game_dev', view: RESUMED_CARD }]
  })

  assert.equal(steps.some((step) => step.kind === 'gateway'), false)
  assert.equal(steps[0]?.kind, 'expert')
  assert.equal(steps[0]?.workflow?.code, 'mini_game_dev')
})

test('等待中保留旧 chips；续跑后没有节点等待时清掉', () => {
  const turn: ChatTurn = {
    key: 't-1',
    role: 'assistant',
    text: '',
    blocks: [],
    phase: 'answering',
    status: 'streaming',
    startTime: 0
  }
  const waitingCard = {
    ...RESUMED_CARD,
    status: 'WAITING_REPLY',
    nodes: [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY', note: '等待你的回复' }
    ],
    choice: {
      question: '你想用哪种语言实现？',
      options: [{ id: 'java', label: 'Java' }, { id: 'python', label: 'Python' }]
    }
  }
  appendWorkflow(turn, waitingCard)

  // 只来了一份不带 choice 的节点状态拍：仍在等待，必须留住 chips
  appendWorkflow(turn, { ...waitingCard, choice: undefined })
  const waiting = turn.blocks.find((block) => block.kind === 'workflow')
  assert.ok(waiting?.workflow?.choice)

  // 续跑：询问节点已 SUCCESS，其它节点 RUNNING，旧 chips 必须消失
  appendWorkflow(turn, { ...RESUMED_CARD, choice: undefined })
  assert.equal(waiting?.workflow?.choice, undefined)
})
