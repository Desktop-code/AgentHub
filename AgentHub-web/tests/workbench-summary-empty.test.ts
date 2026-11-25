/**
 * 工作台「汇总块不得留空」的单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 有「方案结论」标记但模型没写出正文：结束后给一句明确失败说明，不留空串；
 * 2) 汇总失败降级成「### 作者」拼接稿：不把子专家的拼接稿当成汇总；
 * 3) 一条汇总线索都没有：不出汇总块。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import {
  STEP_EMPTY_NOTE,
  SUMMARY_EMPTY_NOTE,
  SUMMARY_REDISTRIBUTED_NOTE,
  buildParticipantFeed as projectParticipantFeed,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}

function block(kind: AssistantBlock['kind'], text: string): AssistantBlock {
  return { kind, text }
}

/** 本轮时间线：入口方案 + 一位子专家（与真实两段路由一致） */
const lanes = [
  { kind: 'agent' as const, title: '路由到 企业综合台', steps: [] },
  { kind: 'agent' as const, title: '路由到 制度知识专家', steps: [] }
]

function feedOf(blocks: AssistantBlock[], streaming: boolean) {
  return buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes,
    blocks,
    streaming,
    fallbackName: '智能网关',
    targetTypeOf: (name: string) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name: string) => name === '企业综合台'
  })
}

test('汇总有标记但没写出正文：结束后给明确说明，不留空串', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/1 · 制度知识专家（policy）'),
    block('assistant', '制度要点：年假按工龄计算'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '把制度专家的结论合成一句话')
  ]
  const cards = feedOf(blocks, false)
  const summary = cards.find((card) => card.kind === 'conclusion')
  assert.ok(summary, '真汇总开始过，汇总记录仍在')
  assert.equal(summary!.answer, '')
  assert.equal(summary!.terminalNote, SUMMARY_EMPTY_NOTE)
})

test('汇总仍在流式且还没正文：记录保留（思考可见），但不预挂占位结论', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/1 · 制度知识专家（policy）'),
    block('assistant', '制度要点'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '正在汇总')
  ]
  const cards = feedOf(blocks, true)
  const summary = cards.find((card) => card.kind === 'conclusion')
  assert.ok(summary)
  assert.equal(summary!.answer, '')
  assert.equal(summary!.terminalNote || '', '')
  assert.equal(summary!.pending, true)
})

test('汇总标记后收到「### 作者」拼接稿：不当汇总，正文回到各专家', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/1 · 制度知识专家（policy）'),
    block('route', '方案结论：企业综合台'),
    block('assistant', '### 制度知识专家\n年假可顺延至次年 3 月\n### 报告撰写专家\n报告骨架已生成')
  ]
  const cards = feedOf(blocks, false)
  // 拼接稿不是真汇总：不出汇总记录（也就没有空的方案块）
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
  // 只认已出现的参与者：制度知识专家收回正文；没出现过的标题不另造卡
  const expert = cards.find((card) => card.title === '制度知识专家')
  assert.ok(expert)
  assert.equal(expert?.answer.includes('年假可顺延至次年 3 月'), true)
  assert.equal(expert?.answer.includes('报告骨架已生成'), true)
  assert.equal(cards.some((card) => card.title === '报告撰写专家'), false)
})

test('一条汇总线索都没有：不出汇总块', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/1 · 制度知识专家（policy）'),
    block('assistant', '制度要点')
  ]
  const cards = feedOf(blocks, false)
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
})

test('专家没有正文：说明在框外，不写成已由谁完成作答', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/1 · 制度知识专家（policy）')
  ]
  const cards = feedOf(blocks, false)
  const expert = cards.find((card) => card.title === '制度知识专家')
  assert.ok(expert)
  assert.equal(expert!.answer, '')
  assert.equal(expert!.terminalNote, STEP_EMPTY_NOTE)
  assert.equal((expert!.terminalNote || '').includes('已由'), false)
})

test('正文和文件并列：文件不改写正文', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/1 · 制度知识专家（policy）'),
    block('assistant', '报告正文：按制度起草。'),
    {
      kind: 'file',
      text: '报告.md',
      file: { fileName: '报告.md', size: 20, downloadUrl: '/f/report' },
      participantName: '制度知识专家'
    }
  ]
  const cards = feedOf(blocks, false)
  const expert = cards.find((card) => card.title === '制度知识专家')
  assert.ok(expert)
  assert.equal(expert!.answer.includes('报告正文：按制度起草。'), true)
  assert.equal(expert!.answer.includes('已生成'), false)
  assert.equal(expert!.files.length, 1)
  assert.equal(expert!.files[0].fileName, '报告.md')
  assert.equal(expert!.terminalNote || '', '')
})
