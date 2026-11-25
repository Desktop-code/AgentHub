/**
 * 工作台直播投影闸 / 工作流增量 / 文件归属的单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * <p>这一组用例**必须经过签名与缓存闸**（{@link ParticipantFeedCache}），
 * 不再直接调 {@link buildParticipantFeed}：线上「方案卡等子专家才出现」「工作流文字整段倒出」
 * 都发生在缓存命中那一拍，只测投影函数永远测不到。
 *
 * 覆盖：
 * 1) begin 原位补身份后签名变化 → 缓存未命中 → 方案卡当拍出现且内容框非空；
 * 2) 同一份内容再算一次签名命中缓存，不重复投影；
 * 3) 工作流正文按节点增量变长 → 签名变化 → 卡片能拿到新文字；
 * 4) 收尾期空正文快照不清空已流出的文字；
 * 5) 「路由到 X（key），依据：…」的依据进入泳道 body，方案卡内容框有真实依据行；
 * 6) 子专家正文增量各归各家，不串块。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock, ChatTurn, TrialFileView } from '../src/types/runtime.ts'
import type { ParticipantCard } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import {
  buildParticipantFeed as projectParticipantFeed,
  isFinishedStatusShell,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { stampBeginLanes } from './stampBegin.ts'

function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0] & {
  targetTypeOf?: (name: string) => string | undefined
  isTopLevelOf?: (name: string) => boolean
}) {
  return visitParticipantCards(projectParticipantFeed({
    ...input,
    lanes: stampBeginLanes(input.lanes, input.targetTypeOf, input.isTopLevelOf)
  }))
}
import { ParticipantFeedCache } from '../src/components/runtime/workbench/workbenchFeedCache.ts'
import {
  appendParticipantBegin,
  appendText,
  appendWorkflow,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import { isWorkflowAvatarCard } from '../src/components/runtime/workbench/workbenchAvatar.ts'
import { WORKFLOW_ROLE_LABEL } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'

/** 造一条工作台助手轮：字段补齐，用例只关心 blocks / status。 */
function turn(blocks: AssistantBlock[], status: ChatTurn['status'] = 'streaming'): ChatTurn {
  return {
    key: 't-1',
    role: 'assistant',
    text: '',
    blocks,
    phase: 'answering',
    status,
    startTime: 0
  }
}

/** 造一块助手块：kind + text，其余按需。 */
function block(kind: AssistantBlock['kind'], text: string, extra: Partial<AssistantBlock> = {}): AssistantBlock {
  return { kind, text, ...extra }
}

/**
 * 与 ChatPanel 同样的投影入口：先过签名闸，再投影，最后按收尾规则过滤。
 *
 * <p>用例必须走这一条，才能验证「begin 那一拍会不会重新投影」。
 */
function project(cache: ParticipantFeedCache, target: ChatTurn): ParticipantCard[] {
  const signatures = cache.signatures([target], '智能网关', '')
  const signature = signatures.get(target.key) || ''
  return cache.project(target, signature, () =>
    buildParticipantFeed({
      routeTexts: target.blocks.filter((item) => item.kind === 'route').map((item) => item.text),
      lanes: processLanes(target.blocks),
      blocks: target.blocks,
      streaming: target.status === 'streaming',
      entryName: '智能网关',
      fallbackName: '智能网关',
      targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT'),
      isTopLevelOf: (name) => name === '企业综合台'
    }).filter((card) => target.status === 'streaming' || !isFinishedStatusShell(card))
  )
}

test('begin 原位补身份后签名必须失效：方案卡当拍出现，内容框不是空的', () => {
  // 第一拍：只有入口判定句 + 一条还没有身份的「路由到 企业综合台」
  const live = turn([
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('reasoning', '先看看方案能不能接这个问句')
  ])
  const cache = new ParticipantFeedCache()
  const before = project(cache, live)
  // 身份还没到：不预挂方案卡，只有入口块
  assert.equal(before.some((card) => card.title === '企业综合台'), false)

  // begin 帧：**原位**把身份补到已有的路由块上（块数量 / 正文长度都不变）
  const route = live.blocks[0]
  const textBefore = route.text
  const countBefore = live.blocks.length
  appendParticipantBegin(live, {
    id: 'p-0-root-enterprise',
    name: '企业综合台',
    kind: 'scheme',
    depth: 1,
    begin: true
  })

  assert.equal(live.blocks.length, countBefore)
  assert.equal(live.blocks[0].text, textBefore)
  assert.equal(live.blocks[0].participantId, 'p-0-root-enterprise')

  // 过了闸：签名变化 → 必须重新投影
  const after = project(cache, live)
  const scheme = after.find((card) => card.title === '企业综合台')
  assert.ok(scheme, 'begin 那一拍就该出方案卡')
  assert.equal(scheme!.participantId, 'p-0-root-enterprise')
  assert.ok(scheme!.lines.length > 0, '内容框不能是空的')
  // 真实依据优先于「正在分发…」：它比状态句更可信
  assert.ok(scheme!.lines.some((line) => line.includes('依据')))
  assert.equal(isFinishedStatusShell(scheme!), false)
})

test('同一份内容再算一次命中缓存，不重复投影', () => {
  const live = turn([
    block('route', '路由到 知识专家（kb），依据：查制度', {
      participantId: 'p-kb',
      participantName: '知识专家'
    }),
    block('assistant', '年假按工龄计算', { participantId: 'p-kb', participantName: '知识专家' })
  ])
  const cache = new ParticipantFeedCache()
  const first = project(cache, live)
  let projected = 0
  const signatures = cache.signatures([live], '智能网关', '')
  const again = cache.project(live, signatures.get(live.key) || '', () => {
    projected += 1
    return []
  })
  assert.equal(projected, 0, '签名没变就不该重算')
  assert.equal(again, first)
})

test('工作流正文按节点增量变长：签名失效，卡片拿到新到的文字', () => {
  // begin 帧先到：身份落在路由块上，工作流卡才有归属（没有身份的散落路由不建卡）
  const live = turn([
    block('route', '路由到 制度要点与报告', { participantId: 'p-wf', participantName: '制度要点与报告' })
  ])
  const cache = new ParticipantFeedCache()
  // 第一拍：只有时间轴，还没有正文
  appendWorkflow(live, { name: '制度要点与报告', code: 'policy-report', status: 'RUNNING', nodes: [], answer: '' })
  const empty = project(cache, live)
  const flowEmpty = empty.find((card) => card.title === '制度要点与报告')
  assert.ok(flowEmpty)
  assert.equal((flowEmpty!.workflow?.answer || '').trim(), '')

  // 第二个 Agent 节点完成：正文变长，但状态 / 节点数都不变
  appendWorkflow(live, {
    name: '制度要点与报告',
    code: 'policy-report',
    status: 'RUNNING',
    nodes: [],
    answer: '制度要点：年假按工龄计算'
  })
  const grown = project(cache, live)
  const flow = grown.find((card) => card.title === '制度要点与报告')
  assert.ok(flow)
  // 过了签名闸：新文字必须出现在卡片上（前端不做假流式，只读源端给的快照）
  assert.equal(flow!.workflow?.answer, '制度要点：年假按工龄计算')
  assert.equal(isWorkflowAvatarCard(flow!), true)
})

test('收尾期空正文快照不得把已经流出的文字清空', () => {
  const live = turn([])
  appendWorkflow(live, {
    name: '制度要点与报告',
    code: 'policy-report',
    status: 'RUNNING',
    nodes: [],
    answer: '制度要点：年假按工龄计算'
  })
  // 乱序 / 重连到达的旧快照：状态推进成 SUCCESS，但正文是空的
  appendWorkflow(live, { name: '制度要点与报告', code: 'policy-report', status: 'SUCCESS', nodes: [], answer: '' })
  const workflow = live.blocks.find((item) => item.kind === 'workflow')
  assert.ok(workflow)
  assert.equal(workflow!.workflow?.status, 'SUCCESS')
  assert.equal(workflow!.workflow?.answer, '制度要点：年假按工龄计算')
})

test('「路由到 X（key），依据：…」的依据进入泳道 body，方案卡用真实依据撑内容框', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案', {
      participantId: 'p-0-root-enterprise',
      participantName: '企业综合台',
      begin: true
    })
  ]
  const lanes = processLanes(blocks)
  const schemeLane = lanes.find((lane) => lane.title === '路由到 企业综合台')
  assert.ok(schemeLane, '方案泳道必须建立')
  // 宽/窄匹配冲突修好之后，依据不能再被 parseChildTitle 整句吃掉
  assert.equal(schemeLane!.body, '依据：用户点名了该方案')
  assert.equal(schemeLane!.participantId, 'p-0-root-enterprise')

  const cards = buildParticipantFeed({
    routeTexts: blocks.map((item) => item.text),
    lanes,
    blocks,
    streaming: true,
    entryName: '智能网关',
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  const scheme = cards.find((card) => card.title === '企业综合台')
  assert.ok(scheme)
  // 依据经 laneBasisOf 归一成一句「分流依据：…」，撑住方案卡的内容框
  assert.deepEqual(scheme!.lines, ['分流依据：用户点名了该方案'])
})

test('子专家正文按身份增量出字：交错到达也不串块', () => {
  // 并行两位专家：begin 各先到，正文增量交错到达（真实直播就是这样）
  const live = turn([
    block('route', '路由到 知识专家', { participantId: 'p-kb', participantName: '知识专家', begin: true }),
    block('route', '路由到 报告专家', { participantId: 'p-writer', participantName: '报告专家', begin: true })
  ])
  const cache = new ParticipantFeedCache()
  project(cache, live)

  appendText(live, 'assistant', '制度', 'p-kb', '知识专家')
  appendText(live, 'assistant', '报告', 'p-writer', '报告专家')
  appendText(live, 'assistant', '要点', 'p-kb', '知识专家')
  appendText(live, 'assistant', '草稿', 'p-writer', '报告专家')

  const cards = project(cache, live)
  const kb = cards.find((card) => card.title === '知识专家')
  const writer = cards.find((card) => card.title === '报告专家')
  assert.ok(kb && writer)
  assert.equal(kb!.answer, '制度要点')
  assert.equal(writer!.answer, '报告草稿')
  // 直播期就已经有字，不是等收尾整段补
  assert.equal(kb!.answer.includes('报告'), false)
})

test('没有身份的文件留作无主，不按「当前归属」挂到工作流卡上', () => {
  const file: TrialFileView = { fileName: '制度要点.md', size: 12, downloadUrl: '/f/1' }
  const blocks: AssistantBlock[] = [
    block('route', '路由到 制度要点与报告', {
      participantId: 'p-wf',
      participantName: '制度要点与报告',
      participantKind: 'workflow'
    }),
    { kind: 'file', text: file.fileName, file }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度要点与报告'],
    lanes: processLanes(blocks),
    blocks,
    streaming: true,
    entryName: '智能网关',
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT'),
    isTopLevelOf: () => false
  })
  const flow = cards.find((card) => card.roleLabel === WORKFLOW_ROLE_LABEL)
  assert.ok(flow)
  // 工作流自身没有文件通道：没有作者的文件不能署到它名下
  assert.deepEqual(flow!.files, [])
})
