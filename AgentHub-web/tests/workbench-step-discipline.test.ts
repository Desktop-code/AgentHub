/**
 * 工作台「每步先思考 → 再工具 → 再结果，且不串块」投影单测（node:test，无浏览器）。
 * 所属域：runtime（前端）。
 *
 * 覆盖写死口径：
 * 1) 入口块只装判定模型的推理（匿名泳道里混进来的工具步不进入口）；
 * 2) 方案分流块只装分流推理，子专家的工具留在子专家块上；
 * 3) 每一步自己的推理 / 工具按发生顺序排列，结果在它们之后；
 * 4) 嵌套方案的两次「方案结论」各归各的思考，不互相串；
 * 5) 拼接兜底不是真汇总：不造汇总框，也不复制子专家正文。
 *
 * 运行：npm test
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import { buildThinkTimeline as projectThinkTimeline } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { stampBeginLanes } from './stampBegin.ts'

function buildThinkTimeline(input: Parameters<typeof projectThinkTimeline>[0] & {
  targetTypeOf?: (name: string) => string | undefined
  isTopLevelOf?: (name: string) => boolean
}) {
  return projectThinkTimeline({
    ...input,
    lanes: stampBeginLanes(input.lanes, input.targetTypeOf, input.isTopLevelOf)
  })
}

function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0] & {
  targetTypeOf?: (name: string) => string | undefined
  isTopLevelOf?: (name: string) => boolean
}) {
  return visitParticipantCards(projectParticipantFeed({
    ...input,
    lanes: stampBeginLanes(input.lanes, input.targetTypeOf, input.isTopLevelOf)
  }))
}

function block(kind: AssistantBlock['kind'], text: string, detail?: string): AssistantBlock {
  return { kind, text, detail }
}

const MA_TYPES = (name: string) => (name === '企业综合台' ? ('MA' as const) : ('AGENT' as const))
const TOP_LEVEL = (name: string) => name === '企业综合台'

test('入口块只装判定推理：匿名泳道里混进来的工具步不进入口', () => {
  const steps = buildThinkTimeline({
    routeTexts: ['路由到 知识问答（kb），依据：问的是年假制度'],
    lanes: [
      {
        kind: 'agent',
        title: '执行',
        steps: [
          { type: 'reasoning', title: '推理', text: '先判断问的是制度类问题' },
          // 上游交错 / 历史半截事件可能把别的步骤的工具塞进匿名泳道：不能算入口的
          { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '年假' }
        ]
      },
      { kind: 'agent', title: '路由到 知识问答', steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }] }
    ],
    streaming: false,
    fallbackName: '智能网关'
  })
  assert.deepEqual(steps[0].steps.map((step) => step.type), ['reasoning'])
  assert.equal(steps[0].steps[0].text, '先判断问的是制度类问题')
  // 专家自己的推理仍在自己块上，没被入口吃掉
  assert.equal(steps[1].steps[0].text, '查制度原文')
})

test('方案分流块只装分流推理：子专家的工具留在子专家块上', () => {
  const lanes: ProcessLane[] = [
    {
      kind: 'agent',
      title: '路由到 企业综合台',
      steps: [
        { type: 'reasoning', title: '推理', text: '需要制度与人一起出力' },
        // 同一条泳道里若混进子专家的工具，也不能画成「方案自己调了工具」
        { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '年假' }
      ]
    },
    {
      kind: 'agent',
      title: '路由到 制度知识专家',
      steps: [
        { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '年假' },
        { type: 'tool_result', title: '工具结果', text: '年假按工龄计算' }
      ]
    },
    {
      kind: 'agent',
      title: '路由到 报告撰写专家',
      steps: [{ type: 'reasoning', title: '推理', text: '按制度起草' }]
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台（enterprise）', '路由到 制度知识专家', '路由到 报告撰写专家'],
    lanes,
    blocks: [
      block('route', '路由到 企业综合台（enterprise）'),
      block('route', '路由到 制度知识专家'),
      block('route', '路由到 报告撰写专家')
    ],
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: MA_TYPES,
    isTopLevelOf: TOP_LEVEL
  })
  const scheme = cards.find((card) => card.roleLabel === '方案')
  assert.ok(scheme)
  // 分流块：只有分流推理 + 分流句，没有工具胶囊
  assert.deepEqual(scheme!.steps.map((step) => step.type), ['reasoning'])
  assert.equal(scheme!.lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((scheme!.children || []).map((card) => card.title), ['制度知识专家', '报告撰写专家'])
  // 子专家的工具仍在子专家块上（没有被搬到方案块，也没有丢）
  const policy = cards.find((card) => card.title === '制度知识专家')
  assert.ok(policy)
  assert.deepEqual(policy!.steps.map((step) => step.type), ['tool_call', 'tool_result'])
  const report = cards.find((card) => card.title === '报告撰写专家')
  assert.deepEqual(report!.steps.map((step) => step.text), ['按制度起草'])
})

test('每一步自己的推理 / 工具按发生顺序排列，结果在它们之后', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家，依据：问年假'],
    lanes: [
      {
        kind: 'agent',
        title: '路由到 制度知识专家',
        steps: [
          { type: 'reasoning', title: '推理', text: '先查制度' },
          { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '年假' },
          { type: 'tool_result', title: '工具结果', text: '年假按工龄计算' }
        ]
      }
    ],
    blocks: [
      block('route', '路由到 制度知识专家，依据：问年假'),
      block('reasoning', '先查制度'),
      { kind: 'tool', text: '调用工具 searchKnowledgeBase（{"q":"年假"}）', detail: '年假按工龄计算' },
      block('assistant', '满一年 5 天年假')
    ],
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  const expert = cards[1]
  // 三拍：思考 → 工具 → 结果，顺序不被打乱（lane 的步骤顺序就是发生顺序）
  assert.deepEqual(expert.steps.map((step) => step.type), ['reasoning', 'tool_call', 'tool_result'])
  assert.equal(expert.answer, '满一年 5 天年假')
  // 入口块不承接这位专家的思考与工具
  assert.deepEqual(cards[0].steps, [])
})

test('嵌套方案的两条汇总各归各的思考，不互相串', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise）'),
    block('route', '路由到 制度知识专家'),
    block('assistant', '制度要点：年假按工龄'),
    block('route', '方案结论：内层方案'),
    block('reasoning', '内层汇总推理'),
    { kind: 'tool', text: '调用工具 writeFile（{"path":"内层.md"}）', detail: '已写入' },
    block('assistant', '内层汇总正文'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '外层汇总推理'),
    block('assistant', '外层汇总正文')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' || name === '内层方案' ? 'MA' : 'AGENT'),
    isTopLevelOf: TOP_LEVEL
  })
  const conclusions = cards.filter((card) => card.kind === 'conclusion')
  const inner = conclusions.find((card) => card.title === '内层方案')
  const outer = conclusions.find((card) => card.title === '企业综合台')
  assert.ok(inner && outer)
  // 内层：自己的推理 + 自己的工具，正文只有内层那一句
  assert.deepEqual(inner.steps.map((step) => step.type), ['reasoning', 'tool_call', 'tool_result'])
  assert.equal(inner.steps[0].text, '内层汇总推理')
  assert.equal(inner.steps[2].text, '已写入')
  assert.equal(inner.answer, '内层汇总正文')
  assert.equal(inner.answer.includes('外层'), false)
  // 外层：只装自己的推理，不借内层的工具，也不复制内层正文
  assert.deepEqual(outer.steps.map((step) => step.text), ['外层汇总推理'])
  assert.equal(outer.steps.some((step) => step.text.includes('内层')), false)
  assert.equal(outer.answer, '外层汇总正文')
  assert.equal(outer.answer.includes('内层'), false)
  // 分流块始终只有分流句，两次汇总都没有回填它
  const scheme = cards.find((card) => card.roleLabel === '方案' && card.kind === 'actor')
  assert.ok(scheme)
  assert.deepEqual(scheme!.steps, [])
  assert.equal(scheme!.answer, '')
})

test('工作流目标：入口只装判定推理，工作流节点自己的过程不并进入口块', () => {
  const steps = buildThinkTimeline({
    routeTexts: ['运行工作流「请假审批」…'],
    lanes: [
      // 判定模型的推理：首条 route 之前，属于入口
      { kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: '这轮该交给请假审批流程' }] },
      // 「运行工作流…」是说明行，之后的过程事件属于工作流节点，不是入口
      { kind: 'note', title: '路由', body: '运行工作流「请假审批」…', steps: [] },
      {
        kind: 'agent',
        title: '执行',
        steps: [
          { type: 'reasoning', title: '推理', text: '节点 A 判断假期余额' },
          { type: 'tool_call', title: '调用工具 queryLeaveBalance', text: '张三' }
        ]
      }
    ],
    streaming: false,
    fallbackName: '智能网关'
  })
  assert.equal(steps.length, 1)
  assert.equal(steps[0].lines[0], '已交由「请假审批」执行')
  assert.deepEqual(steps[0].steps.map((step) => step.text), ['这轮该交给请假审批流程'])
})

test('拼接兜底不是真汇总：不造汇总框，也不复制子专家正文', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise）'),
    block('route', '路由到 制度知识专家'),
    block('assistant', '### 制度知识专家\n年假按工龄计算\n\n### 报告撰写专家\n报告已起草')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: MA_TYPES,
    isTopLevelOf: TOP_LEVEL
  })
  // 没有「方案结论：」也没有汇总口吻 → 一条汇总记录都不该有
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
  // 拼接稿按作者各归各家
  assert.equal(cards.find((card) => card.title === '制度知识专家')!.answer, '年假按工龄计算')
  assert.equal(cards.find((card) => card.title === '报告撰写专家')!.answer, '报告已起草')
})
