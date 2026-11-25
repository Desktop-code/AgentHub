/**
 * participant 帧只认一次解析后的对象。双重编码时不建卡。所属域：runtime（前端）。
 *
 * 本地用例，不进仓库。不二次解析，也不按无身份的路由名补卡。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import { asParticipantChunk } from '../src/api/trialParticipantFrame.ts'
import { appendParticipantBegin, appendRoute, processLanes } from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}

/** 决策行：两问分别点了综合台和工作流 */
const DECISION = '路由决策\n已拆成 2 个问题，按序交给「综合台」「制度要点与报告」'

/**
 * 造一轮流式助手消息。
 *
 * @return 只有决策行之前的空轮
 */
function liveTurn() {
  return {
    key: 't-frame',
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
    status: 'streaming' as const,
    startTime: 0
  }
}

/**
 * 用现网投影出卡。不传候选池。
 *
 * @param turn 本轮
 * @return 卡片
 */
function project(turn: ReturnType<typeof liveTurn>) {
  return buildParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    streaming: true,
    entryName: '智能网关',
    fallbackName: '智能网关'
  })
}

/** 三个 begin 的对象载荷，与出站字段一致 */
const BEGINS = [
  { id: 'p-0-root-scheme', name: '综合台', kind: 'scheme', routeKey: 'scheme', depth: 0, begin: true },
  { id: 'p-0-root-wf', name: '制度要点与报告', kind: 'workflow', routeKey: 'wf_report', depth: 0, begin: true },
  { id: 'p-1-scheme-policy', name: '制度知识专家', kind: 'agent', routeKey: 'policy', depth: 1, begin: true }
]

test('双重编码时一次解析收不到 begin，决策行只留入口卡', () => {
  const turn = liveTurn()
  appendRoute(turn, DECISION)
  // 后端若把 JSON 字符串再编码一次，前端只 parse 一次，得到的是字符串
  for (const begin of BEGINS) {
    const parsed = JSON.parse(JSON.stringify(JSON.stringify(begin)))
    assert.equal(typeof parsed, 'string')
    assert.equal(asParticipantChunk(parsed), undefined)
  }
  // 开跑后的「路由到」能到，但没有身份，不建卡
  appendRoute(turn, '路由到 综合台（scheme），依据：整理制度')
  appendRoute(turn, '路由到 制度要点与报告（wf_report）')
  const cards = project(turn)
  assert.deepEqual(cards.map((card) => card.kind), ['gateway'])
  assert.equal(cards[0].title, '智能网关')
  assert.match(cards[0].lines[0], /已拆成 2 个问题，分别交给「综合台」「制度要点与报告」/)
  assert.equal(cards.some((card) => card.kind === 'actor'), false)
})

test('一次解析后的 begin 立刻建卡：工作流与综合台并列，子专家跟在方案下', () => {
  const turn = liveTurn()
  appendRoute(turn, DECISION)
  for (const begin of BEGINS) {
    const parsed = JSON.parse(JSON.stringify(begin))
    const chunk = asParticipantChunk(parsed)
    assert.ok(chunk)
    assert.equal(chunk.begin, true)
    assert.equal(chunk.kind, begin.kind)
    assert.equal(chunk.depth, begin.depth)
    appendParticipantBegin(turn, chunk)
  }
  const cards = project(turn)
  assert.deepEqual(
    cards.map((card) => `${card.kind}:${card.title}:${card.participantDepth ?? ''}`),
    [
      'gateway:智能网关:',
      'actor:综合台:0',
      'actor:制度知识专家:1',
      'actor:制度要点与报告:0'
    ]
  )
  const scheme = cards.find((card) => card.title === '综合台')
  const flow = cards.find((card) => card.title === '制度要点与报告')
  const expert = cards.find((card) => card.title === '制度知识专家')
  assert.equal(scheme?.participantKind, 'scheme')
  assert.equal(flow?.participantKind, 'workflow')
  assert.equal(expert?.participantDepth, 1)
  // 工作流是 depth 0 的独立目标，不进综合台的框；专家在框里，不再写进名单
  assert.equal((scheme?.children || []).some((card) => card.title === '制度要点与报告'), false)
  assert.equal((scheme?.children || []).some((card) => card.title === '制度知识专家'), true)
  assert.equal(scheme?.lines.some((line) => line.includes('已把问题分给')), false)
})
