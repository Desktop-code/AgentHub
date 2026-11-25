/**
 * 工作台「入口补思考 + 思考框可点开」回归单测（node:test，无浏览器）。
 * 所属域：runtime（前端展示）。
 *
 * 覆盖两条被用户手点出来的问题：
 * 1) 入口判定的真实推理没归到入口块 → 入口只有判定句、没有「查看思考过程与工具调用」；
 * 2) 折叠覆盖值用 hasOwnProperty 判存在 → 首写新键不触发重渲染，轮次结束后点了没反应。
 *
 * 注意：这里不直接 import `trialMessages.ts`（它引用 `@/` 别名，node 直跑解析不了）。
 * 泳道按投影层的真实结构手写，口径与 `processLanes` 一致（匿名块带参与者身份）。
 *
 * 运行：npm test
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { effect, reactive } from 'vue'
import type { AssistantBlock } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import { buildThinkTimeline } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}
import {
  foldOpenOf,
  nextFoldValue
} from '../src/components/runtime/workbench/workbenchContentBox.ts'

test('折叠覆盖：没点过用默认态，点过以覆盖为准，再点取反', () => {
  const overrides: Record<string, boolean> = {}
  // 未操作：跟默认态走（结束时默认收起）
  assert.equal(foldOpenOf(overrides, 't1:actor-think:gw', false), false)
  // 首次点击：取反成展开
  overrides['t1:actor-think:gw'] = nextFoldValue(overrides, 't1:actor-think:gw', false)
  assert.equal(overrides['t1:actor-think:gw'], true)
  assert.equal(foldOpenOf(overrides, 't1:actor-think:gw', false), true)
  // 再点一次：收起（覆盖值优先于默认态）
  overrides['t1:actor-think:gw'] = nextFoldValue(overrides, 't1:actor-think:gw', false)
  assert.equal(foldOpenOf(overrides, 't1:actor-think:gw', false), false)
  // 流式中默认展开时点一次就是收起
  assert.equal(nextFoldValue({}, 't2:actor-think:x', true), false)
  // 不同轮 / 不同区段互不影响
  assert.equal(foldOpenOf(overrides, 't9:actor-think:gw', false), false)
})

test('折叠覆盖：首次写入新键必须触发重渲染（不能再用 hasOwnProperty 判存在）', () => {
  const overrides = reactive<Record<string, boolean>>({})
  let renders = 0
  effect(() => {
    renders += 1
    // 与 ChatPanel.isOpen 同一口径：直接读值
    void foldOpenOf(overrides, 't1:actor-think:gw', false)
  })
  const before = renders
  overrides['t1:actor-think:gw'] = nextFoldValue(overrides, 't1:actor-think:gw', false)
  assert.ok(renders > before, '首写覆盖值必须让视图重新求值，否则点了没反应')
})

/** 入口推理被判定决策行挡在后面：匿名块仍带着入口身份 */
const blockedLanes: ProcessLane[] = [
  {
    kind: 'agent',
    title: '执行',
    participantId: 'gw-1',
    steps: [{ type: 'reasoning', title: '推理', text: '用户问年假，先判断是制度类问题' }]
  },
  { kind: 'decision', title: '路由决策', body: '已判定 1 个意图', steps: [] },
  { kind: 'agent', title: '路由到 企业综合台', participantId: 'ma-9', steps: [] },
  { kind: 'agent', title: '路由到 制度知识专家', participantId: 'policy', steps: [] }
]

const blockedBlocks: AssistantBlock[] = [
  { kind: 'reasoning', text: '用户问年假，先判断是制度类问题', participantId: 'gw-1' },
  { kind: 'route', text: '路由决策 已判定 1 个意图，分发给「企业综合台」' },
  { kind: 'route', text: '路由到 企业综合台（enterprise），依据：问的是制度类问题', participantId: 'ma-9' },
  { kind: 'route', text: '路由到 制度知识专家（policy）', participantId: 'policy' },
  { kind: 'assistant', text: '年假按工龄计算', participantId: 'policy' }
]

test('入口推理被决策行挡在后面：按身份仍归到入口块', () => {
  const steps = buildThinkTimeline({
    routeTexts: blockedBlocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: blockedLanes,
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router',
    entryParticipantId: 'gw-1'
  })
  const gateway = steps.find((step) => step.kind === 'gateway')
  assert.ok(gateway, '应有入口块')
  assert.deepEqual(gateway!.steps.map((step) => step.text), ['用户问年假，先判断是制度类问题'])
  // 入口推理不能被算进任何专家块
  for (const step of steps.filter((item) => item.kind === 'expert')) {
    assert.equal(step.steps.filter((item) => item.text === '用户问年假，先判断是制度类问题').length, 0)
  }
})

test('入口推理被决策行挡在后面：消息流入口块出现思考，专家块不串', () => {
  const cards = buildParticipantFeed({
    routeTexts: blockedBlocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: blockedLanes,
    blocks: blockedBlocks,
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router',
    entryParticipantId: 'gw-1',
    targetTypeOf: (name: string) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name: string) => name === '企业综合台'
  })
  const gateway = cards.find((card) => card.kind === 'gateway')
  assert.ok(gateway, '应有入口块')
  assert.equal(gateway!.steps.length, 1, '入口块应挂判定推理（否则画不出思考行）')
  assert.equal(gateway!.steps[0].text, '用户问年假，先判断是制度类问题')
  // 判定句仍在内容框里，不受影响
  assert.ok(gateway!.lines.length > 0)
})

test('没有身份（历史回放）：入口仍只认首条 route 之前的推理，不抢别人的思考', () => {  const steps = buildThinkTimeline({
    routeTexts: ['路由决策 已判定 1 个意图'],
    lanes: [
      { kind: 'decision', title: '路由决策', body: '已判定 1 个意图', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }] }
    ],
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router'
  })
  const gateway = steps.find((step) => step.kind === 'gateway')
  assert.ok(gateway)
  assert.equal(gateway!.steps.length, 0, '没有真实入口推理时不许编造')
})

test('入口就在首位（常规实时流）：位置口径照旧把推理挂到入口块', () => {
  const steps = buildThinkTimeline({
    routeTexts: ['路由到 制度知识专家（policy）'],
    lanes: [
      { kind: 'agent', title: '执行', participantId: 'gw-1', steps: [{ type: 'reasoning', title: '推理', text: '先判断问的是制度类问题' }] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }] }
    ],
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router',
    entryParticipantId: 'gw-1'
  })
  assert.equal(steps[0].kind, 'gateway')
  assert.deepEqual(steps[0].steps.map((step) => step.text), ['先判断问的是制度类问题'])
  assert.equal(steps[1].steps[0].text, '查制度原文')
})

test('方案 / 专家思考行：有推理就有可点开的折叠内容', () => {
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '执行', participantId: 'gw-1', steps: [{ type: 'reasoning', title: '推理', text: '入口先想' }] },
    {
      kind: 'agent',
      title: '路由到 企业综合台',
      participantId: 'ma-9',
      steps: [{ type: 'reasoning', title: '推理', text: '方案分流先想' }]
    },
    {
      kind: 'agent',
      title: '路由到 制度知识专家',
      participantId: 'policy',
      steps: [{ type: 'reasoning', title: '推理', text: '专家先查知识库' }]
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台（enterprise）', '路由到 制度知识专家（policy）'],
    lanes,
    blocks: [
      { kind: 'reasoning', text: '入口先想', participantId: 'gw-1' },
      { kind: 'route', text: '路由到 企业综合台（enterprise）', participantId: 'ma-9' },
      { kind: 'reasoning', text: '方案分流先想', participantId: 'ma-9' },
      { kind: 'route', text: '路由到 制度知识专家（policy）', participantId: 'policy' },
      { kind: 'reasoning', text: '专家先查知识库', participantId: 'policy' },
      { kind: 'assistant', text: '年假按工龄计算', participantId: 'policy' }
    ],
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router',
    entryParticipantId: 'gw-1',
    targetTypeOf: (name: string) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name: string) => name === '企业综合台'
  })
  // 每一块都有自己的推理，展示层据此画出「查看思考过程与工具调用」
  const gateway = cards.find((card) => card.kind === 'gateway')
  const scheme = cards.find((card) => card.title === '企业综合台')
  const expert = cards.find((card) => card.title === '制度知识专家')
  assert.equal(gateway?.steps[0]?.text, '入口先想')
  assert.equal(scheme?.steps[0]?.text, '方案分流先想')
  assert.equal(expert?.steps[0]?.text, '专家先查知识库')
})

test('入口判定人话夹着决策 JSON：脱敏后仍保留推理，入口块仍有思考行', () => {
  // 判定模型常把 route_key / sub_query 写进人话同一行；旧口径整行丢 → 入口思考框消失
  const think =
    '用户问年假制度。应交给制度知识专家。最终输出 [{"route_key":"policy","sub_query":"年假天数"}]'
  const lanes: ProcessLane[] = [
    {
      kind: 'agent',
      title: '执行',
      participantId: 'gw-1',
      steps: [{ type: 'reasoning', title: '推理', text: think }]
    },
    { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家（policy），依据：制度类问题'],
    lanes,
    blocks: [
      { kind: 'reasoning', text: think, participantId: 'gw-1' },
      { kind: 'route', text: '路由到 制度知识专家（policy），依据：制度类问题' },
      { kind: 'assistant', text: '年假按工龄计算' }
    ],
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router',
    entryParticipantId: 'gw-1',
    targetTypeOf: () => 'AGENT',
    isTopLevelOf: () => true
  })
  const gateway = cards.find((card) => card.kind === 'gateway')
  assert.ok(gateway, '应有入口块')
  assert.ok(gateway!.steps.length > 0, '入口块应有思考行')
  assert.ok(gateway!.steps[0].text.includes('用户问年假制度'), '人话推理必须保留')
  assert.equal(gateway!.steps[0].text.includes('route_key'), false, '不得把 route_key 打给用户')
  assert.equal(gateway!.steps[0].text.includes('sub_query'), false, '不得把 sub_query 打给用户')
})

test('历史回放无身份：首条 route 前的判定推理仍进入口块', () => {
  const lanes: ProcessLane[] = [
    {
      kind: 'agent',
      title: '执行',
      steps: [{ type: 'reasoning', title: '推理', text: '先判断是制度类问题' }]
    },
    {
      kind: 'agent',
      title: '路由到 制度知识专家',
      steps: [{ type: 'reasoning', title: '推理', text: '查制度原文' }]
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家（policy）'],
    lanes,
    blocks: [
      { kind: 'reasoning', text: '先判断是制度类问题' },
      { kind: 'route', text: '路由到 制度知识专家（policy）' },
      { kind: 'reasoning', text: '查制度原文' },
      { kind: 'assistant', text: '年假按工龄计算' }
    ],
    streaming: false,
    fallbackName: 'Router',
    entryName: 'Router',
    targetTypeOf: () => 'AGENT',
    isTopLevelOf: () => true
  })
  const gateway = cards.find((card) => card.kind === 'gateway')
  const expert = cards.find((card) => card.title === '制度知识专家')
  assert.equal(gateway?.steps[0]?.text, '先判断是制度类问题')
  assert.equal(expert?.steps[0]?.text, '查制度原文')
})

test('断点续作：专家推理不会被当成入口判定，也不出入口块', () => {
  // 续作轮后端不判路由、不推入口参与者：只有专家的推理与正文
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: '按用户所选方向展开完整方案' }] }
  ]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes,
    blocks: [
      { kind: 'reasoning', text: '按用户所选方向展开完整方案' },
      { kind: 'assistant', text: '按你选的 Vue3 + Vite + TS，完整落地方案如下……' }
    ],
    streaming: false,
    fallbackName: '前端技术专家'
  })
  assert.deepEqual(cards.map((card) => card.kind), ['actor'])
  // 推理挂在这位专家自己的折叠区，没有被搬去「入口」
  assert.equal(cards[0].steps[0]?.text, '按用户所选方向展开完整方案')
  assert.equal(cards[0].answer.includes('完整落地方案'), true)
})
