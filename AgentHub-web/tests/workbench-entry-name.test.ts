/**
 * 工作台入口块／参与者角标的取名口径（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 入口块标题读配置名（现网 Router），不再写死对外别名；
 * 2) 调用方没注入配置名时退回内置兜底，历史会话与单测不受影响；
 * 3) 方案与专家标题仍取各自配置名，不被入口名覆盖。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import {
  GATEWAY_STEP_NAME,
  buildThinkTimeline as projectThinkTimeline,
  entryTitleOf
} from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
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

/** 造一条子 Agent 泳道：标题形如「路由到 X」，与实时 SSE 投影一致 */
function lane(name: string): ProcessLane {
  return { kind: 'agent', title: `路由到 ${name}`, steps: [] }
}

test('入口块标题读配置名：改名后消息流跟着配置走', () => {
  const steps = buildThinkTimeline({
    routeTexts: ['路由到 知识问答，依据：问的是制度'],
    lanes: [lane('知识问答')],
    streaming: false,
    entryName: 'Router',
    fallbackName: '智能助手',
    isTopLevelOf: () => true
  })
  assert.equal(steps[0].kind, 'gateway')
  assert.equal(steps[0].title, 'Router')
  // 专家名取路由事件里的配置名，不被入口名覆盖
  assert.equal(steps[1].title, '知识问答')
})

test('入口名缺失 / 空白时退回内置兜底，不产生空标题', () => {
  assert.equal(entryTitleOf(''), GATEWAY_STEP_NAME)
  assert.equal(entryTitleOf('   '), GATEWAY_STEP_NAME)
  assert.equal(entryTitleOf(undefined), GATEWAY_STEP_NAME)
  assert.equal(entryTitleOf('  结算台  '), '结算台')
  // 不传 entryName：老调用（历史会话 / 既有用例）标题不变
  const steps = buildThinkTimeline({
    routeTexts: ['路由到 知识问答'],
    lanes: [lane('知识问答')],
    streaming: false,
    fallbackName: '智能助手'
  })
  assert.equal(steps[0].title, GATEWAY_STEP_NAME)
})

test('入口判定钉在第一次判定上，后续子专家重推路由不改入口句', () => {
  const decision = '路由决策 [{"sub_query":"年假制度"},{"sub_query":"报告起草"}]'
  const first = buildThinkTimeline({
    routeTexts: [decision],
    lanes: [],
    streaming: true,
    entryName: 'Router',
    fallbackName: '智能助手'
  })
  // 并行子开跑前会重推自己的「路由到」；入口块不能被最后一位覆盖
  const later = buildThinkTimeline({
    routeTexts: [decision, '路由到 制度知识专家', '路由到 报告撰写专家'],
    lanes: [lane('制度知识专家'), lane('报告撰写专家')],
    streaming: true,
    entryName: 'Router',
    fallbackName: '智能助手',
    isTopLevelOf: () => true
  })
  assert.deepEqual(first[0].lines, later[0].lines)
  assert.ok(later[0].lines[0].includes('多个意图'))
})

test('方案块标题取方案自己的名称，入口角标与方案角标各归各位', () => {
  const steps = buildThinkTimeline({
    routeTexts: ['路由到 企业综合台，依据：用户点名了该方案'],
    lanes: [lane('企业综合台'), lane('制度知识专家')],
    streaming: false,
    entryName: 'Router',
    fallbackName: '智能助手',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.equal(steps[0].title, 'Router')
  const scheme = steps.find((step) => step.title === '企业综合台')
  assert.ok(scheme)
  assert.equal(scheme?.roleLabel, '方案')
  const expert = steps.find((step) => step.title === '制度知识专家')
  assert.ok(expert)
  // 单兵专家不写 roleLabel：角标由展示层按类型给「Agent」，不在投影层重复存一份
  assert.equal(expert?.roleLabel, undefined)
})
