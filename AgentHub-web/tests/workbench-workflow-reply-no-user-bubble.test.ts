/**
 * 等待回复续跑：回复不再落成新的用户消息（前端回放与节点合并）。所属域：runtime（前端展示）。
 *
 * <p>锁住三件事：
 * <ol>
 *   <li>事件流里没有第二条 user 事件时，回放不能把续跑结果并回等待那一轮，否则旧等待正文会留下；</li>
 *   <li>询问节点收成完成态后仍带用户选择（reply），这是「我答了什么」的唯一展示；</li>
 *   <li>后续不带 reply 的卡片快照不得把已经记下的选择冲掉。</li>
 * </ol>
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { ChatTurn, TrialWorkflowNodeView, TrialWorkflowView } from '../src/types/runtime.ts'
import { appendWorkflow, turnsFromEvents } from '../src/components/runtime/trialMessages.ts'

const WAITING_CARD: TrialWorkflowView = {
  name: '小游戏研发',
  code: 'mini_game_dev',
  status: 'WAITING_REPLY',
  answer: '请你先确定使用哪种语言实现',
  nodes: [
    {
      code: 'ask_language',
      name: '询问实现语言',
      type: 'AGENT',
      status: 'WAITING_REPLY',
      note: '等待你的回复',
      output: '这个 2048 你想用哪种语言实现？'
    }
  ],
  choice: {
    question: '这个 2048 你想用哪种语言实现？',
    options: [
      { id: 'html', label: 'HTML/CSS/JavaScript 单页' },
      { id: 'java', label: 'Java' }
    ]
  }
}

const RESUMED_CARD: TrialWorkflowView = {
  name: '小游戏研发',
  code: 'mini_game_dev',
  status: 'RUNNING',
  answer: '这个 2048 你想用哪种语言实现？\n\n实现语言已确定为 HTML/CSS/JavaScript 单页',
  nodes: [
    {
      code: 'ask_language',
      name: '询问实现语言',
      type: 'AGENT',
      status: 'SUCCESS',
      note: '已完成',
      output: '这个 2048 你想用哪种语言实现？',
      reply: 'HTML/CSS/JavaScript 单页'
    },
    { code: 'impl', name: '游戏实现', type: 'AGENT', status: 'RUNNING', note: '执行中' }
  ]
}

/** 造一条回放事件；只填回放需要的字段。 */
function event(seq: number, eventType: string, contentText: string, summary = '') {
  return { id: seq, sessionId: 662, seq, eventType, contentText, summary }
}

/** 续跑轮的身份 begin：同一工作流，没有入口判定。 */
function resumedBegin(seq: number) {
  return event(seq, 'participant', JSON.stringify({
    id: 'p-0-root-mini_game_dev',
    name: '小游戏研发',
    kind: 'workflow',
    routeKey: 'mini_game_dev',
    depth: 0,
    begin: true
  }))
}

test('回放：没有第二条用户消息时，续跑结果另起一轮且不并回等待轮', () => {
  const turns = turnsFromEvents([
    event(1, 'user', JSON.stringify({ text: '做一个2048小游戏' })),
    event(2, 'participant', JSON.stringify({
      id: 'gw-ma-3',
      name: '智能网关',
      kind: 'gateway',
      depth: 0,
      begin: true
    })),
    resumedBegin(3),
    event(4, 'workflow', JSON.stringify(WAITING_CARD)),
    event(5, 'assistant', '工作流「小游戏研发」正在等待你的回复；请直接在本会话下方输入框接着说'),
    // 此处没有 user 事件：回复只写进同一条运行的 input.reply
    resumedBegin(6),
    event(7, 'workflow', JSON.stringify(RESUMED_CARD)),
    event(8, 'assistant', '实现语言已确定为 HTML/CSS/JavaScript 单页')
  ])

  const userTurns = turns.filter((turn) => turn.role === 'user')
  assert.equal(userTurns.length, 1, '消息流里只保留最初那句提问，不冒出第二条用户气泡')

  const assistants = turns.filter((turn) => turn.role === 'assistant')
  assert.equal(assistants.length, 2, '等待轮与续跑轮各一张助手轮')

  const waitingBlocks = (assistants[0].blocks || []).filter((block) => block.kind === 'workflow')
  assert.equal(waitingBlocks.length, 0, '旧等待卡应被收走，不能和续跑结果并排')

  const freshBlocks = (assistants[1].blocks || []).filter((block) => block.kind === 'workflow')
  assert.equal(freshBlocks.length, 1, '续跑轮应保留唯一一张执行卡')
  assert.equal(assistants[1].continuation, true, '续跑轮必须标记 continuation，不再合成入口')
})

test('回放：询问节点收成完成态后，节点里记下这次选择', () => {
  const turns = turnsFromEvents([
    event(1, 'user', JSON.stringify({ text: '做一个2048小游戏' })),
    resumedBegin(2),
    event(3, 'workflow', JSON.stringify(WAITING_CARD)),
    event(4, 'assistant', '工作流「小游戏研发」正在等待你的回复'),
    resumedBegin(5),
    event(6, 'workflow', JSON.stringify(RESUMED_CARD))
  ])

  const fresh = turns
    .filter((turn) => turn.role === 'assistant')
    .flatMap((turn) => turn.blocks || [])
    .find((block) => block.kind === 'workflow')
  assert.ok(fresh?.workflow, '续跑轮应有工作流卡')
  const ask = fresh.workflow.nodes.find((node) => node.code === 'ask_language')
  assert.ok(ask, '询问节点必须还在卡里')
  assert.equal(ask.status, 'SUCCESS', '回复后询问节点按完成态收起')
  assert.equal(ask.reply, 'HTML/CSS/JavaScript 单页', '节点内记下选择，作为唯一回复展示')
  assert.equal(fresh.workflow.choice, undefined, '回复后选项清掉')
})

test('卡片快照：后续不带 reply 的节点状态拍不得冲掉已记下的选择', () => {
  const turn: ChatTurn = {
    key: 't-1',
    role: 'assistant',
    text: '',
    blocks: [],
    phase: 'answering',
    status: 'streaming',
    startTime: 0
  }
  appendWorkflow(turn, RESUMED_CARD)
  const block = turn.blocks.find((item) => item.kind === 'workflow')
  assert.ok(block?.workflow)
  assert.equal(block.workflow.nodes[0].reply, 'HTML/CSS/JavaScript 单页')

  // 引擎后续只推节点状态、不带 reply：节点里的选择必须原样保留
  const withoutReply = {
    ...RESUMED_CARD,
    nodes: RESUMED_CARD.nodes.map((node: TrialWorkflowNodeView) => ({ ...node, reply: '' }))
  }
  appendWorkflow(turn, withoutReply)
  assert.equal(block.workflow?.nodes[0].reply, 'HTML/CSS/JavaScript 单页')
})
