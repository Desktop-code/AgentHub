/**
 * 调试框卡片收口的窄测。所属域：workflow（前端）。
 *
 * 覆盖：详情上的卡片原样挂上，不从 nodeRuns 另拼节点；入参 query 覆盖额外 JSON。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  DEBUG_EMPTY_HINT,
  alignClosedCard,
  asWorkflowView,
  canSwitchDebugRecord,
  debugActionLabel,
  debugParticipant,
  debugQueryInput,
  debugRunErrorNotice,
  debugSendMode,
  debugVersionTag,
  isLiveRunStatus,
  mergeLiveRun,
  queryOf,
  shouldPoll
} from '../src/views/workflow/editor/workflowDebugCard.ts'

test('空态文案不出现等待执行', () => {
  assert.equal(DEBUG_EMPTY_HINT, '输入要调试的内容，开始后过程会出现在这里')
  assert.equal(DEBUG_EMPTY_HINT.includes('等待执行'), false)
  assert.equal(DEBUG_EMPTY_HINT.includes('已发布'), false)
})

test('卡片只认后端视图，不把节点状态行当成时间轴', () => {
  const nodeRuns = [{ nodeCode: 'other', status: 'SUCCESS', outputJson: '{"text":"不该出现"}' }]
  assert.equal(asWorkflowView(nodeRuns), null)
  assert.equal(asWorkflowView({ status: 'RUNNING' }), null)

  const view = {
    name: '演示',
    code: 'demo',
    status: 'WAITING_REPLY',
    answer: '你想用哪种语言？',
    nodes: [{ code: 'ask', name: '询问', type: 'AGENT', status: 'WAITING_REPLY', output: '你想用哪种语言？' }],
    choice: { question: '你想用哪种语言？', options: [{ id: 'python', label: 'Python' }, { id: 'java', label: 'Java' }] }
  }
  const parsed = asWorkflowView(view)
  assert.equal(parsed, view)
  const { card } = debugParticipant(parsed!, 9)
  assert.equal(card.participantKind, 'workflow')
  assert.equal(card.workflow, view)
  assert.equal(card.workflow?.nodes.length, 1)
  assert.equal(card.workflow?.nodes[0].code, 'ask')
  assert.equal(card.pending, false)
})

test('第一句只作为 query，空句子不能开始', () => {
  const merged = debugQueryInput(' 你好 ')
  assert.equal(merged.ok, true)
  if (merged.ok) {
    assert.deepEqual(merged.input, { query: '你好' })
  }
  assert.equal(debugQueryInput('   ').ok, false)
  assert.equal(queryOf({ query: '你好' }), '你好')
  assert.equal(queryOf('{"query":"历史"}'), '历史')
})

test('记录按发布版本标号，未发布不能拿来切换', () => {
  // 已发布：只写 vN，不带未发布
  assert.deepEqual(debugVersionTag(8, 6), { versionText: 'v6', unpublished: false })
  // 未发布调试：先写开始那一刻的默认版本，再标未发布
  assert.deepEqual(debugVersionTag(null, null, 6), { versionText: 'v6', unpublished: true })
  assert.deepEqual(debugVersionTag(null, null, 7), { versionText: 'v7', unpublished: true })
  // 当时从未发布过：只标未发布，不编版本号
  assert.deepEqual(debugVersionTag(null, null), { versionText: '', unpublished: true })
  assert.deepEqual(debugVersionTag(null, null, null), { versionText: '', unpublished: true })
  assert.deepEqual(debugVersionTag(null, 1), { versionText: '', unpublished: true })
  assert.deepEqual(debugVersionTag(8, null), { versionText: '', unpublished: true })
  assert.equal(canSwitchDebugRecord(8), true)
  assert.equal(canSwitchDebugRecord(null), false)
  assert.equal(canSwitchDebugRecord(undefined), false)
})

test('进行中要轮询，等待回复时下一句续同一条', () => {
  assert.equal(shouldPoll('CREATED', false), true)
  assert.equal(shouldPoll('RUNNING', false), true)
  assert.equal(shouldPoll('WAITING_REPLY', false), false)
  assert.equal(shouldPoll('WAITING_REPLY', true), true)
  assert.equal(shouldPoll('SUCCESS', true), false)
  assert.equal(debugSendMode(null, false), 'start')
  assert.equal(debugSendMode('WAITING_REPLY', false), 'reply')
  assert.equal(debugSendMode('RUNNING', false), 'blocked')
  assert.equal(debugSendMode('SUCCESS', false), 'start')
  assert.equal(debugSendMode('PAUSED_HUMAN', false), 'blocked')
})

test('输入行只有一个主按钮：开始运行、续跑发送、运行中同键停止', () => {
  assert.equal(debugActionLabel('start', false), '运行')
  assert.equal(debugActionLabel('reply', false), '发送')
  // 运行中仍只有这一个按钮，不再另放「取消」
  assert.equal(debugActionLabel('blocked', true), '停止')
})

test('错误条只为失败出：等待回复 / 用户取消都不是错误', () => {
  // 节点失败 / 超限：原样复述 errorSummary，节点上的原因仍由卡片自己显示
  assert.equal(
    debugRunErrorNotice('FAILED', '节点「play_check」失败：这一步运行超过上限，已停止'),
    '节点「play_check」失败：这一步运行超过上限，已停止'
  )
  assert.equal(debugRunErrorNotice('failed', '节点「a」失败：没返回内容'), '节点「a」失败：没返回内容')
  // 等待回复 / 人工等待 / 运行中 / 成功都不出条
  assert.equal(debugRunErrorNotice('WAITING_REPLY', '等待用户回复'), '')
  assert.equal(debugRunErrorNotice('PAUSED_HUMAN', null), '')
  assert.equal(debugRunErrorNotice('RUNNING', null), '')
  assert.equal(debugRunErrorNotice('SUCCESS', null), '')
  // 取消不是错误：标题已有「已取消」，不再给一条红色提示
  assert.equal(debugRunErrorNotice('CANCELLED', '用户取消运行'), '')
  // 失败但没有原因：不出空条
  assert.equal(debugRunErrorNotice('FAILED', null), '')
  assert.equal(debugRunErrorNotice('FAILED', '   '), '')
  // 旧库里状态与摘要打架时，等待 / 取消文案也不当成错误
  assert.equal(debugRunErrorNotice('FAILED', '等待用户回复'), '')
  assert.equal(debugRunErrorNotice('FAILED', '用户取消运行'), '')
})

test('只有运行中的 run 才接直播，结束的历史不订', () => {
  assert.equal(isLiveRunStatus('CREATED'), true)
  assert.equal(isLiveRunStatus('RUNNING'), true)
  assert.equal(isLiveRunStatus('running'), true)
  assert.equal(isLiveRunStatus('WAITING_REPLY'), false)
  assert.equal(isLiveRunStatus('PAUSED_HUMAN'), false)
  assert.equal(isLiveRunStatus('SUCCESS'), false)
  assert.equal(isLiveRunStatus('FAILED'), false)
  assert.equal(isLiveRunStatus('CANCELLED'), false)
  assert.equal(isLiveRunStatus(null), false)
})

test('取消后卡片只对齐终态，不清空已流出的节点与正文', () => {
  const liveCard = {
    name: '演示',
    code: 'demo',
    status: 'RUNNING',
    answer: '已经流出的正文',
    nodes: [{ code: 'ask', status: 'RUNNING' }]
  }
  // 还没结束：不动卡片
  assert.equal(alignClosedCard(liveCard, 'RUNNING'), liveCard)
  assert.equal(alignClosedCard(liveCard, 'WAITING_REPLY'), liveCard)
  // 取消到达：卡片状态对齐成终态，正文与节点原样保留（取消只改库，直播最后一帧还停在运行中）
  const closed = alignClosedCard(liveCard, 'CANCELLED') as Record<string, unknown>
  assert.equal(closed.status, 'CANCELLED')
  assert.equal(closed.answer, '已经流出的正文')
  assert.deepEqual(closed.nodes, liveCard.nodes)
  // 不改原对象，避免同一个视图被两处引用
  assert.equal(liveCard.status, 'RUNNING')
  // 卡片本身已经是终态：原样返回
  const done = { ...liveCard, status: 'SUCCESS' }
  assert.equal(alignClosedCard(done, 'CANCELLED'), done)
  // 认不出的载荷原样返回，不把详情打成空
  assert.equal(alignClosedCard(null, 'CANCELLED'), null)
  assert.equal(alignClosedCard('x', 'CANCELLED'), 'x')
})

test('直播帧只追加不清空：卡片 / 节点行缺失时保留上一层', () => {
  const base = {
    id: 7,
    workflowId: 3,
    status: 'RUNNING',
    triggerType: 'TRIAL',
    nodeRuns: [{ id: 1, nodeId: 'n1', nodeCode: 'ask', nodeType: 'AGENT', attempt: 1, status: 'RUNNING' }],
    humanTasks: [],
    input: { query: '做点什么' },
    card: { status: 'RUNNING', answer: '已流出的正文', nodes: [{ code: 'ask', status: 'RUNNING' }] }
  }

  // 直播这一帧还没带 card / nodeRuns：不能把已经流出的内容清掉
  const early = mergeLiveRun(base as never, { live: true, status: 'RUNNING' })
  assert.equal(early.card, base.card)
  assert.equal(early.nodeRuns, base.nodeRuns)
  assert.equal(early.input, base.input)
  assert.equal(early.humanTasks, base.humanTasks)

  // 直播帧带了更长的卡：以直播为准，节点状态同步更新
  const nextCard = { status: 'RUNNING', answer: '已流出的正文，又多了工具过程', nodes: [{ code: 'ask', status: 'SUCCESS' }] }
  const nextRows = [{ id: 1, nodeId: 'n1', nodeCode: 'ask', nodeType: 'AGENT', attempt: 1, status: 'SUCCESS' }]
  const later = mergeLiveRun(base as never, {
    live: true,
    status: 'RUNNING',
    card: nextCard,
    nodeRuns: nextRows,
    errorSummary: null
  })
  assert.equal(later.card, nextCard)
  assert.equal(later.nodeRuns, nextRows)
  assert.equal(later.status, 'RUNNING')
  // 直播快照不带入参，沿用开始时的问句
  assert.deepEqual(later.input, { query: '做点什么' })
})
