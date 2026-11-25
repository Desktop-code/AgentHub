/**
 * 工作台「方案汇总出过就不许在收尾消失」的单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 没有「方案结论：X」标记：不把方案名下的 ### 正文猜成汇总结论；
 * 2) 有标记、正文是「### 作者」拼接稿：结论记录不靠标题把拼接稿收成汇总正文；
 * 3) 方案内部走 CONCAT（方案那段本身就是子专家拼接稿）：不假造结论块；
 * 4) 收尾合并时回放没有结论标记：本地已画出的结论块留在原位，不摘掉；
 * 5) 合并配对时沿用本地身份：两位子专家的正文不会挤到最后一位头上。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock, ChatTurn } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import {
  SUMMARY_REDISTRIBUTED_NOTE,
  buildParticipantFeed as projectParticipantFeed,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}
import { stampBeginLanes } from './stampBegin.ts'
import { mergeTurnsAfterRun } from '../src/components/runtime/trialMerge.ts'

/** 决策块：与后端 `RouterTexts.questionDecisionLine` 同形 */
const DECISION = '路由决策\n已拆成 2 个问题，按序交给「企业综合台」「制度要点与报告」'

/** 一位方案 + 它下面两位子专家；工作流那一路是第二问 */
const lanes: ProcessLane[] = [
  { kind: 'agent', title: '路由到 制度要点与报告', steps: [] },
  { kind: 'agent', title: '路由到 企业综合台', steps: [] },
  { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
  { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
]

/** 一张工作流卡片（本文件只关心位置与正文，不校验节点细节） */
const workflowBlock: AssistantBlock = {
  kind: 'workflow',
  text: '',
  workflow: {
    name: '制度要点与报告',
    code: 'wf_report',
    status: 'SUCCESS',
    answer: '工作流报告已产出。',
    nodes: []
  }
}

function block(kind: AssistantBlock['kind'], text: string, participantName?: string): AssistantBlock {
  return participantName ? { kind, text, participantName } : { kind, text }
}

function feedOf(blocks: AssistantBlock[], streaming = false) {
  const targetTypeOf = (name: string) =>
    name === '企业综合台' ? 'MA' : name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT'
  const isTopLevelOf = (name: string) => name === '企业综合台' || name === '制度要点与报告'
  return buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: stampBeginLanes(lanes, targetTypeOf, isTopLevelOf),
    blocks,
    streaming,
    fallbackName: '企业综合台',
    entryName: '企业综合台',
    targetTypeOf: (name: string) =>
      name === '企业综合台' ? 'MA' : name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT',
    isTopLevelOf: (name: string) => name === '企业综合台' || name === '制度要点与报告',
    expectEntry: true
  })
}

const SUMMARY = '综合结论：制度要点为年假按工龄计算，报告已按骨架完成。'

/** 落库轮廓（本刀之前的旧形状）：没有「方案结论」标记，正文只有末尾一条拼接稿 */
const replayWithoutMark: AssistantBlock[] = [
  block('route', DECISION),
  block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
  block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
  block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
  block('route', '路由到 制度要点与报告（wf_report），依据：用户要求按工作流跑一次'),
  workflowBlock,
  block('assistant', `### 企业综合台\n${SUMMARY}\n\n### 制度要点与报告\n工作流报告已产出。`)
]

/** 落库轮廓（本刀之后）：标记与汇总推理已落库，正文仍只在末尾拼接稿里 */
const replayWithMark: AssistantBlock[] = [
  block('route', DECISION),
  block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
  block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
  block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
  block('route', '方案结论：企业综合台'),
  block('reasoning', '把两位子专家的结论合成一段总述'),
  block('route', '路由到 制度要点与报告（wf_report），依据：用户要求按工作流跑一次'),
  workflowBlock,
  block('assistant', `### 企业综合台\n${SUMMARY}\n\n### 制度要点与报告\n工作流报告已产出。`)
]

test('没有汇总标记时，不把方案名下的 ### 正文猜成结论卡', () => {
  const cards = feedOf(replayWithoutMark)
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
})

test('有标记但正文是 ### 拼接稿：不把方案标题段收成汇总正文', () => {
  const cards = feedOf(replayWithMark)
  const conclusion = cards.find((card) => card.kind === 'conclusion')
  assert.ok(conclusion)
  assert.equal(conclusion!.answer, '')
  assert.equal(conclusion!.terminalNote, SUMMARY_REDISTRIBUTED_NOTE)
  assert.equal((conclusion!.terminalNote || '').includes(SUMMARY), false)
  // 汇总推理仍留在这条记录的折叠区
  assert.equal((conclusion!.steps || []).length > 0, true)
})

test('方案内部走 CONCAT：方案那段本身是子专家拼接稿，不假造结论', () => {
  const concatReplay: AssistantBlock[] = [
    block('route', DECISION),
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
    block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
    block(
      'assistant',
      '### 企业综合台\n### 制度知识专家\n制度要点：年假按工龄\n### 报告撰写专家\n报告骨架已生成'
    )
  ]
  const cards = feedOf(concatReplay)
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
})

test('收尾合并：回放没有结论标记时，本地已画出的结论块原地保留', () => {
  // 流式：方案分给两位子专家 + 真汇总（标记 + 推理 + 正文）
  const local = [
    block('route', DECISION),
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
    block('assistant', '制度要点：年假按工龄计算。', '制度知识专家'),
    block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
    block('assistant', '报告骨架已生成。', '报告撰写专家'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '把两位子专家的结论合成一段总述'),
    block('assistant', SUMMARY),
    workflowBlock
  ]
  const merged = mergeTurnsAfterRun(
    [assistantTurn('wb-a-1', local)],
    [assistantTurn('e-11', replayWithoutMark)]
  )
  const cards = feedOf(merged[0].blocks as AssistantBlock[])

  const conclusion = cards.find((card) => card.kind === 'conclusion')
  assert.ok(conclusion, '合并不能把已经画出来的结论摘掉')
  assert.equal(conclusion!.answer, SUMMARY)
  // 块序跟 begin：工作流泳道在方案之前，就不按提问名把它挪到子专家后面
  assert.deepEqual(
    cards.map((card) => `${card.kind}:${card.title}`),
    [
      'gateway:企业综合台',
      'actor:制度要点与报告',
      'actor:企业综合台',
      'actor:制度知识专家',
      'actor:报告撰写专家',
      'conclusion:企业综合台'
    ]
  )
})

test('收尾合并：配对上的正文块沿用本地身份，子专家正文不挤到最后一位', () => {
  const local = [
    block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
    block('assistant', '制度要点：年假按工龄计算。', '制度知识专家'),
    block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
    block('assistant', '报告骨架已生成。', '报告撰写专家')
  ]
  // 回放：正文没有身份字段（落库事件只有 kind / text）
  const fresh = [
    block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
    block('assistant', '制度要点：年假按工龄计算。'),
    block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
    block('assistant', '报告骨架已生成。')
  ]
  const merged = mergeTurnsAfterRun([assistantTurn('wb-a-1', local)], [assistantTurn('e-11', fresh)])

  assert.equal(merged[0].blocks[1].participantName, '制度知识专家')
  assert.equal(merged[0].blocks[3].participantName, '报告撰写专家')
})

test('收尾合并：拼接稿不与单段正文配对，否则一段正文会被复制进别人的块', () => {
  const local = [
    block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
    block('assistant', '制度要点：年假按工龄计算。', '制度知识专家')
  ]
  const fresh = [
    block('route', '路由到 制度知识专家（policy），依据：查制度原文'),
    block('assistant', '### 制度知识专家\n制度要点：年假按工龄计算。\n\n### 报告撰写专家\n报告骨架已生成。')
  ]
  const merged = mergeTurnsAfterRun([assistantTurn('wb-a-1', local)], [assistantTurn('e-11', fresh)])
  const blocks = merged[0].blocks as AssistantBlock[]

  // 本地那段原样留下（身份还在），回放的拼接稿另起一块，互不覆盖
  const kept = blocks.find((item) => item.text === '制度要点：年假按工龄计算。')
  assert.ok(kept, '本地单段正文不能被整份换成拼接稿')
  assert.equal(kept!.participantName, '制度知识专家')
  const concat = blocks.find((item) => (item.text || '').startsWith('### 制度知识专家'))
  assert.ok(concat, '回放拼接稿仍要落进这一轮')
  assert.equal(blocks.filter((item) => item.kind === 'assistant').length, 2)
})

test('收尾合并：带小标题的报告正文也不会被当成拼接稿覆盖', () => {
  const report = '## 一、制度依据\n年假按工龄计算\n\n## 二、报告骨架\n已完成'
  const local = [
    block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
    block('assistant', report, '报告撰写专家')
  ]
  const fresh = [
    block('route', '路由到 报告撰写专家（writer），依据：起草报告'),
    block('assistant', '### 制度知识专家\n制度要点：年假按工龄计算。\n\n### 报告撰写专家\n报告骨架已生成。')
  ]
  const merged = mergeTurnsAfterRun([assistantTurn('wb-a-1', local)], [assistantTurn('e-11', fresh)])
  const blocks = merged[0].blocks as AssistantBlock[]

  const kept = blocks.find((item) => item.text === report)
  assert.ok(kept, '报告正文（带章节小标题）不能被拼接稿整份换掉')
  assert.equal(kept!.participantName, '报告撰写专家')
})

/** 造一个助手轮：本文件的合并用例只关心 blocks。 */
function assistantTurn(key: string, blocks: AssistantBlock[]): ChatTurn {
  return { key, role: 'assistant', text: '', blocks, phase: 'done', status: 'done', startTime: 0 }
}
