/**
 * 工作台「一条消息多个问题并跑」的块位（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 顺序和归属只认 begin 的 depth、kind 和先后，不再按提问名重排：
 * 1) 谁先 begin 谁在前；更深的 scheme 才是父块，子块按 begin 留在原位；
 * 2) 流式中与结束后投影出的块序一致；
 * 3) 两路正文各回各块：工作流正文不落到方案块里；
 * 4) 子专家 depth 更深时归回方案，方案仍是分流块；
 * 5) 还没 begin 的工作流不预占块位；
 * 6) 未点名但 depth 更深的方案内工作流跟在综合台下，begin 后不换位。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock, TrialWorkflowView } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { stampBeginLanes } from './stampBegin.ts'

/** 工作流卡片视图（一步时间轴，够判定「卡在哪一块」） */
function workflowView(status: string, answer: string): TrialWorkflowView {
  return {
    name: '制度要点与报告',
    code: 'wf_report',
    status,
    answer,
    nodes: [{ id: 'n1', name: '整理要点', status: status === 'RUNNING' ? 'RUNNING' : 'SUCCESS' }]
  } as TrialWorkflowView
}

/** 决策块：与后端 `RouterTexts.questionDecisionLine` 同形（第一问方案，第二问工作流） */
const DECISION = '路由决策\n已拆成 2 个问题，按序交给「企业综合台」「制度要点与报告」'

/** 决策块：两问都没点名工作流（工作流只是方案内部的一个交付物） */
const DECISION_WF_NESTED = '路由决策\n已拆成 2 个问题，按序交给「企业综合台」「文稿起草」'

/** 决策块：第一问就是工作流本身（独立提问目标） */
const DECISION_WF_FIRST = '路由决策\n已拆成 2 个问题，按序交给「制度要点与报告」「企业综合台」'

/** 投影入参：入口候选池＝本轮两问的目标（方案 / 工作流 / 裸专家） */
function feedOf(blocks: AssistantBlock[], lanes: ProcessLane[], streaming: boolean) {
  const targetTypeOf = (name: string) =>
    name === '企业综合台' ? 'MA' : name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT'
  const isTopLevelOf = (name: string) => name === '企业综合台' || name === '制度要点与报告' || name === '文稿起草'
  return visitParticipantCards(projectParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: stampBeginLanes(lanes, targetTypeOf, isTopLevelOf),
    blocks,
    streaming,
    fallbackName: '企业综合台',
    entryName: '企业综合台',
    expectEntry: true
  }))
}

const titlesOf = (cards: { kind: string; title: string }[]) =>
  cards.map((card) => `${card.kind}:${card.title}`)

test('块位只认 begin 先后：工作流先 begin 就留在方案前面，不按提问名重排', () => {
  // 工作流的 begin 在方案之前。投影不把它挪到第二问的位置上
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 企业综合台', steps: [{ type: 'reasoning', title: '推理', text: '分给制度专家' }] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [{ type: 'reasoning', title: '推理', text: '先查制度原文' }] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '运行工作流「制度要点与报告」…' },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, true)), [
    'gateway:企业综合台',
    'actor:制度要点与报告',
    'actor:企业综合台',
    'actor:制度知识专家'
  ])
})

test('方案的路由后到也不让工作流插到第一位（块位不随到达顺序变）', () => {
  // 流式：方案的路由先落、工作流的路由后落——块位仍按提问顺序
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    {
      kind: 'workflow',
      text: '',
      workflow: workflowView('RUNNING', '')
    },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, true)), [
    'gateway:企业综合台',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:制度要点与报告'
  ])
})

test('工作流自己就是第一问的独立提问目标：仍落在入口之后第一位', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION_WF_FIRST },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, true)), [
    'gateway:企业综合台',
    'actor:制度要点与报告',
    'actor:企业综合台',
    'actor:制度知识专家'
  ])
})

test('两路正文各回各块：工作流正文不落到方案或子专家块里', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    {
      kind: 'assistant',
      text: '制度要点：年假按工龄计算。',
      participantId: 'p-policy',
      participantName: '制度知识专家'
    },
    {
      kind: 'assistant',
      text: '报告已按工作流产出。',
      participantId: 'p-wf_report',
      participantName: '制度要点与报告'
    }
  ]
  const cards = feedOf(blocks, lanes, true)
  const scheme = cards.find((card) => card.title === '企业综合台')
  const child = cards.find((card) => card.title === '制度知识专家')
  const workflow = cards.find((card) => card.title === '制度要点与报告')
  assert.ok(scheme && child && workflow)
  assert.equal(child.answer.includes('制度要点'), true)
  assert.equal(child.answer.includes('报告已按工作流产出'), false)
  assert.equal(scheme.answer.includes('报告已按工作流产出'), false)
  assert.equal(workflow.answer.includes('报告已按工作流产出'), true)
  assert.equal(workflow.answer.includes('制度要点'), false)
})

test('结束后不重排：终态与流式中块序完全一致', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    { kind: 'assistant', text: '制度要点：年假按工龄计算。', participantId: 'p-policy', participantName: '制度知识专家' }
  ]
  const live = feedOf(blocks, lanes, true)
  // 收尾：工作流卡换成终态，其余块内容不变
  const finals = blocks.map((item) =>
    item.kind === 'workflow'
      ? { ...item, workflow: workflowView('SUCCESS', '报告已按工作流产出。') }
      : item
  )
  const done = feedOf(finals, lanes, false)
  assert.deepEqual(titlesOf(done), titlesOf(live))
  // key 也必须一致：位置钉死的判据是「同一个节点原地补数据」，不是「顺序看起来一样」
  assert.deepEqual(done.map((card) => card.key), live.map((card) => card.key))
})

test('工作流还没 begin 时不预占块位，begin 之后才出现且不再换位', () => {
  // 还没有工作流的 begin：决策行里点了名也不先造一张卡
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' }
  ]
  const placeholder = feedOf(blocks, lanes, true)
  assert.deepEqual(titlesOf(placeholder), [
    'gateway:企业综合台',
    'actor:企业综合台',
    'actor:制度知识专家'
  ])
  assert.equal(placeholder.some((card) => card.title === '制度要点与报告'), false)

  // begin 到了才出卡，位置就是这次 begin 的位置
  const lanesWithFlow: ProcessLane[] = [
    ...lanes,
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] }
  ]
  const withCard = [
    ...blocks,
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' } as AssistantBlock,
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') } as AssistantBlock
  ]
  const filled = feedOf(withCard, lanesWithFlow, true)
  assert.deepEqual(titlesOf(filled), [
    'gateway:企业综合台',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:制度要点与报告'
  ])
  assert.ok(filled[3].workflow)
})

test('决策行点了工作流的名、但还没有 begin：不预造工作流卡', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [{ type: 'reasoning', title: '推理', text: '分给制度专家' }] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, true)), [
    'gateway:企业综合台',
    'actor:企业综合台'
  ])
})

test('工作流始终没起来：收尾后不留下没有卡片的占位块', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' }
  ]
  const done = feedOf(blocks, lanes, false)
  assert.deepEqual(titlesOf(done), ['gateway:企业综合台', 'actor:企业综合台', 'actor:制度知识专家'])
})

test('落库把工作流写在后（分段序）时，投影仍按提问顺序——流式与回放同一块位', () => {
  // 回放：分段顺序＝方案 → 子专家 → 工作流
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    { kind: 'assistant', text: '制度要点：年假按工龄计算。' },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('SUCCESS', '报告已按工作流产出。') }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, false)), [
    'gateway:企业综合台',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:制度要点与报告'
  ])
})

test('子专家泳道落在工作流之后仍归回方案块：方案是分流块，不是自己在作答', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    {
      kind: 'assistant',
      text: '制度要点：年假按工龄计算。',
      participantId: 'p-policy',
      participantName: '制度知识专家'
    },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') }
  ]
  const cards = feedOf(blocks, lanes, true)
  const scheme = cards.find((card) => card.kind === 'actor' && card.title === '企业综合台')
  assert.ok(scheme)
  assert.equal(scheme.roleLabel, '方案')
  assert.equal(scheme.lines.some((line) => line.includes('已把问题分给')), false)
  assert.ok((scheme.children || []).some((card) => card.title === '制度知识专家'))
  assert.equal(scheme.answer, '')
})

test('未点名的方案内工作流：跟在综合台下，begin 之后不换位', () => {
  // 两问＝企业综合台 / 文稿起草。工作流在候选池里，但归属只认 begin 的 kind=workflow、depth=2。
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [{ type: 'reasoning', title: '推理', text: '分给专家' }] },
    { kind: 'agent', title: '路由到 文稿起草', steps: [{ type: 'reasoning', title: '推理', text: '先拟提纲' }] },
    { kind: 'agent', title: '路由到 制度要点与报告', participantKind: 'workflow', participantDepth: 2, steps: [] },
    { kind: 'agent', title: '路由到 知识问答', steps: [{ type: 'reasoning', title: '推理', text: '检索制度' }] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }] },
    { kind: 'agent', title: '路由到 报告撰写专家', steps: [{ type: 'reasoning', title: '推理', text: '起草报告' }] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION_WF_NESTED },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 文稿起草（draft）' },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '第一节要点') },
    { kind: 'route', text: '路由到 知识问答（qa）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    { kind: 'route', text: '路由到 报告撰写专家（writer），依据：起草报告' }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, true)), [
    'gateway:企业综合台',
    'actor:企业综合台',
    // 方案内工作流按自己的 begin 留在子专家前面，不因为在候选池里就被提前或沉底
    'actor:制度要点与报告',
    'actor:知识问答',
    'actor:制度知识专家',
    'actor:报告撰写专家',
    'actor:文稿起草'
  ])
})

test('工作流 begin 在方案之前：不事后跳进方案组', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 企业综合台', steps: [{ type: 'reasoning', title: '推理', text: '分给专家' }] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }] },
    { kind: 'agent', title: '路由到 文稿起草', steps: [{ type: 'reasoning', title: '推理', text: '先拟提纲' }] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION_WF_NESTED },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    { kind: 'route', text: '路由到 文稿起草（draft）' }
  ]
  assert.deepEqual(titlesOf(feedOf(blocks, lanes, true)), [
    'gateway:企业综合台',
    'actor:制度要点与报告',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:文稿起草'
  ])
})

test('点名的工作流占自己的提问块位：即使它是方案内部也在候选池，也不并进方案组', () => {
  // 工作流是第一问的独立目标：它不跟着后面的方案走，块位就是第一问
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [{ type: 'reasoning', title: '推理', text: '分给专家' }] },
    { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }] }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: DECISION_WF_FIRST },
    { kind: 'route', text: '路由到 企业综合台（enterprise）' },
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度原文' },
    { kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
    { kind: 'workflow', text: '', workflow: workflowView('RUNNING', '') }
  ]
  const cards = feedOf(blocks, lanes, true)
  // begin 顺序是方案、工作流、子专家。工作流 depth=1，不并进方案的子块
  assert.deepEqual(titlesOf(cards), [
    'gateway:企业综合台',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:制度要点与报告'
  ])
  const scheme = cards.find((card) => card.kind === 'actor' && card.title === '企业综合台')
  assert.ok(scheme)
  assert.equal(scheme.lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((scheme.children || []).map((card) => card.title), ['制度知识专家'])
})
