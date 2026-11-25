/**
 * 工作台消息流：顺序和归属只认 begin 的 depth / kind / 先后。
 * 本地用例，不进仓库。所属域：runtime（前端）。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendParticipantBegin,
  appendParticipantEnd,
  appendRoute,
  appendText,
  appendWorkflow,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import { isMidProcessBox } from '../src/components/runtime/workbench/workbenchContentBox.ts'
import {
  buildParticipantFeed,
  showLooseAssistant,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import {
  schemeChildrenSettled,
  workflowOutputState
} from '../src/components/runtime/workbench/workflowOutputView.ts'

/** 两问决策行：点了综合台和文稿专家，没有点工作流的名 */
const DECISION = '路由决策\n已拆成 2 个问题，按序交给「企业综合台」「文稿专家」'

/**
 * 造一轮助手消息。字段只满足投影和追加块要用的那些。
 *
 * @return 流式中的助手轮
 */
function liveTurn() {
  return {
    key: 't-begin',
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
      closed?: 'done' | 'failed' | 'aborted'
      workflow?: {
        name: string
        code: string
        status: string
        answer?: string
        nodes: { code: string; name: string; type: string; status: string }[]
      }
    }[],
    phase: 'answering',
    status: 'streaming' as const,
    startTime: 0,
    entryParticipantId: 'p-0-root-gw'
  }
}

/**
 * 按 begin 先后摆两问：综合台（方案）→ 制度专家 → 未点名的方案内工作流 → 第二问专家。
 *
 * @param turn 目标轮
 */
function beginTwoQuestions(turn: ReturnType<typeof liveTurn>) {
  appendRoute(turn, DECISION)
  appendParticipantBegin(turn, {
    id: 'p-1-root-scheme',
    name: '企业综合台',
    kind: 'scheme',
    depth: 1,
    begin: true
  })
  appendParticipantBegin(turn, {
    id: 'p-2-scheme-rule',
    name: '制度专家',
    kind: 'agent',
    depth: 2,
    begin: true
  })
  appendParticipantBegin(turn, {
    id: 'p-2-scheme-flow',
    name: '制度要点与报告',
    kind: 'workflow',
    depth: 2,
    begin: true
  })
  appendParticipantBegin(turn, {
    id: 'p-1-root-note',
    name: '文稿专家',
    kind: 'agent',
    depth: 1,
    begin: true
  })
}

/**
 * 用同一套投影出卡。候选池不参与：这里不传 isTopLevel / targetType。
 *
 * @param turn 本轮
 * @return 卡片标题与归属字段
 */
function project(turn: ReturnType<typeof liveTurn>) {
  return visitParticipantCards(buildParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    streaming: turn.status === 'streaming',
    entryName: '入口',
    fallbackName: '智能助手',
    entryParticipantId: turn.entryParticipantId
  }))
}

test('两问下未点名的方案内工作流跟在综合台下，begin 之后不换位', () => {
  const turn = liveTurn()
  beginTwoQuestions(turn)
  appendText(turn, 'assistant', '制度专家先写到这里', 'p-2-scheme-rule', '制度专家')
  const before = project(turn).map((card) => card.title)
  assert.deepEqual(before, ['入口', '企业综合台', '制度专家', '制度要点与报告', '文稿专家'])

  // 后到的正文和快照只填进已有卡，不把工作流提前或沉到第二问后面
  appendText(turn, 'assistant', '又补了一段', 'p-2-scheme-rule', '制度专家')
  appendWorkflow(
    turn,
    {
      name: '制度要点与报告',
      code: 'rule-report',
      status: 'RUNNING',
      answer: '节点一已经写完的正文',
      nodes: [{ code: 'n1', name: '整理', type: 'AGENT', status: 'SUCCESS' }]
    },
    'p-2-scheme-flow',
    '制度要点与报告'
  )
  const after = project(turn).map((card) => card.title)
  assert.deepEqual(after, before)
})

test('所属方案还有子块没结束时，工作流结果区不整屏展开', () => {
  const turn = liveTurn()
  beginTwoQuestions(turn)
  appendWorkflow(
    turn,
    {
      name: '制度要点与报告',
      code: 'rule-report',
      status: 'SUCCESS',
      answer: '第一段\n\n第二段',
      nodes: [{ code: 'n1', name: '整理', type: 'AGENT', status: 'SUCCESS' }]
    },
    'p-2-scheme-flow',
    '制度要点与报告'
  )
  const running = project(turn)
  const flow = running.find((card) => card.title === '制度要点与报告')
  assert.ok(flow)
  assert.equal(schemeChildrenSettled(running, flow), false)
  assert.equal(
    workflowOutputState(flow.workflow, flow.workflow?.answer || '', 0, false, !schemeChildrenSettled(running, flow)),
    'folded'
  )

  // 方案下的子块都结束后展开。第二问专家还没结束、整轮仍在 streaming，也不再把结果区按住
  appendParticipantEnd(turn, { id: 'p-2-scheme-rule', name: '制度专家', state: 'done' })
  appendParticipantEnd(turn, { id: 'p-2-scheme-flow', name: '制度要点与报告', state: 'done' })
  const settledCards = project(turn)
  const settledFlow = settledCards.find((card) => card.title === '制度要点与报告')
  assert.ok(settledFlow)
  assert.equal(settledCards.find((card) => card.title === '文稿专家')?.closed, undefined)
  assert.equal(turn.status, 'streaming')
  assert.equal(schemeChildrenSettled(settledCards, settledFlow), true)
  assert.equal(
    workflowOutputState(
      settledFlow.workflow,
      settledFlow.workflow?.answer || '',
      0,
      false,
      !schemeChildrenSettled(settledCards, settledFlow)
    ),
    'open'
  )
})

test('建卡保留 begin 的 kind 和 depth', () => {
  const turn = liveTurn()
  beginTwoQuestions(turn)
  const scheme = turn.blocks.find((block) => block.participantName === '企业综合台')
  const flow = turn.blocks.find((block) => block.participantName === '制度要点与报告' && block.kind === 'route')
  assert.equal(scheme?.participantKind, 'scheme')
  assert.equal(scheme?.participantDepth, 1)
  assert.equal(flow?.participantKind, 'workflow')
  assert.equal(flow?.participantDepth, 2)

  const cards = project(turn)
  const schemeCard = cards.find((card) => card.title === '企业综合台')
  const flowCard = cards.find((card) => card.title === '制度要点与报告')
  assert.equal(schemeCard?.participantKind, 'scheme')
  assert.equal(schemeCard?.participantDepth, 1)
  assert.equal(flowCard?.participantKind, 'workflow')
  assert.equal(flowCard?.participantDepth, 2)
})

test('工作台在没有参与者卡时不把 assistant 画成整屏答案', () => {
  assert.equal(showLooseAssistant(true, false), false)
  assert.equal(showLooseAssistant(true, true), false)
  // 调试皮肤仍可在没有参与者卡时显示原文
  assert.equal(showLooseAssistant(false, false), true)
})

test('直播中即使最后一张已经有正文也不放开高度', () => {
  assert.equal(
    isMidProcessBox({ kind: 'actor', answer: '已经写了很长的一段', isFinal: true }, true),
    true
  )
  assert.equal(
    isMidProcessBox({ kind: 'actor', answer: '已经写了很长的一段', isFinal: true }, false),
    false
  )
  const turn = liveTurn()
  beginTwoQuestions(turn)
  appendText(turn, 'assistant', '文稿专家的长答案', 'p-1-root-note', '文稿专家')
  const cards = project(turn)
  assert.equal(cards.some((card) => card.isFinal), false)
})

test('第一条路线是工作流时，不只留入口加一张工作流', () => {
  const turn = liveTurn()
  appendRoute(turn, '运行工作流「知识工作流」')
  appendParticipantBegin(turn, {
    id: 'p-1-root-flow',
    name: '知识工作流',
    kind: 'workflow',
    depth: 1,
    begin: true
  })
  appendParticipantBegin(turn, {
    id: 'p-1-root-expert',
    name: '制度专家',
    kind: 'agent',
    depth: 1,
    begin: true
  })
  const titles = project(turn).map((card) => card.title)
  assert.deepEqual(titles, ['入口', '知识工作流', '制度专家'])
})
