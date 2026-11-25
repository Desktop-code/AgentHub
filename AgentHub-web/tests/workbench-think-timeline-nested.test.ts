/**
 * 工作台思考时间线：多级路由（入口 → 方案 → 子专家）分块单测（本地 tests/，不进仓）。
 *
 * 覆盖本刀新口径：
 * 1. 方案内部各子专家各自独立成块，方案块只写自己的分流结论；
 * 2. 网关块只描述入口那一跳，不被方案内部路线覆盖；
 * 3. 单级路由仍是两块，不退化；
 * 4. 不编造：没有子专家路线就不出现子专家块；
 * 5. 方案没有形象配置时给稳定机器人兜底，不再是纯首字。
 *
 * 运行：npm test
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  GATEWAY_STEP_NAME,
  buildThinkTimeline as projectThinkTimeline
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
import { resolveFallbackRobotAvatar } from '../src/constants/agentLooks.ts'

/** 造一条子 Agent 泳道；body 是后端「路由到 …，依据：…」里那句依据 */
const lane = (name: string, body?: string, steps: { type: string; title: string; text: string }[] = []) => ({
  kind: 'agent' as const,
  title: `路由到 ${name}`,
  body,
  steps: steps as never
})

/** 入口候选池：只有入口那一层的名字 */
const topOf = (names: string[]) => (name: string) => names.includes(name)

/** 目标类型：方案 / 专家 */
const typeOf = (schemes: string[]) => (name: string) => (schemes.includes(name) ? ('MA' as const) : ('AGENT' as const))

describe('多级路由：入口 → 方案 → 各子专家', () => {
  const input = {
    routeTexts: [
      '路由到 企业综合台（enterprise），依据：需要制度与成稿两方面协作',
      '路由到 制度知识专家（policy），依据：用户问差旅报销标准',
      '路由到 报告撰写专家（report），依据：需要按模板成稿'
    ],
    lanes: [
      lane('企业综合台', '依据：需要制度与成稿两方面协作'),
      lane('制度知识专家', '依据：用户问差旅报销标准', [
        { type: 'reasoning', title: '推理', text: '先查制度知识库' }
      ]),
      lane('报告撰写专家', '依据：需要按模板成稿', [
        { type: 'reasoning', title: '推理', text: '按公文模板成稿' }
      ])
    ],
    targetTypeOf: typeOf(['企业综合台']),
    isTopLevelOf: topOf(['企业综合台']),
    streaming: false,
    fallbackName: '智能助手'
  }

  it('每层参与者各占一块，顺序为 网关 → 方案 → 子专家 → 子专家', () => {
    const steps = buildThinkTimeline(input)
    assert.equal(steps.length, 4)
    assert.equal(steps[0].title, GATEWAY_STEP_NAME)
    assert.equal(steps[1].title, '企业综合台')
    assert.equal(steps[2].title, '制度知识专家')
    assert.equal(steps[3].title, '报告撰写专家')
  })

  it('网关块只写入口那一跳，不被方案内部路线覆盖', () => {
    const steps = buildThinkTimeline(input)
    assert.equal(steps[0].lines.length, 1)
    assert.ok(steps[0].lines[0].includes('企业综合台'))
    assert.ok(!steps[0].lines[0].includes('制度知识专家'))
    assert.ok(!steps[0].lines[0].includes('报告撰写专家'))
  })

  it('方案块只写自己的分流结论，不把子专家思考挂在自己名下', () => {
    const scheme = buildThinkTimeline(input)[1]
    assert.equal(scheme.roleLabel, '方案')
    assert.deepEqual(scheme.lines, ['已把问题分给「制度知识专家」「报告撰写专家」'])
    assert.deepEqual(scheme.steps, [])
  })

  it('子专家块各自带自己的依据与思考，不被合并成一块', () => {
    const steps = buildThinkTimeline(input)
    const policy = steps[2]
    assert.deepEqual(policy.lines, ['分流依据：用户问差旅报销标准'])
    assert.equal(policy.steps[0].text, '先查制度知识库')
    const report = steps[3]
    assert.deepEqual(report.lines, ['分流依据：需要按模板成稿'])
    assert.equal(report.steps[0].text, '按公文模板成稿')
  })

  it('方案内部依据是内部标签时整条丢弃，不写进块里', () => {
    const steps = buildThinkTimeline({
      ...input,
      lanes: [
        lane('企业综合台', '依据：需要制度与成稿两方面协作'),
        lane('制度知识专家', '依据：父模型', [])
      ]
    })
    assert.deepEqual(steps[2].lines, ['已由「制度知识专家」完成作答'])
  })
})

describe('单级路由不退化', () => {
  it('入口直接分给专家：仍是网关 + 专家两块', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 差旅专家（travel），依据：问差旅报销标准'],
      lanes: [lane('差旅专家', '依据：问差旅报销标准', [
        { type: 'reasoning', title: '推理', text: '先确认城市等级' }
      ])],
      targetTypeOf: typeOf([]),
      isTopLevelOf: topOf(['差旅专家']),
      streaming: true,
      fallbackName: '智能助手'
    })
    assert.equal(steps.length, 2)
    assert.equal(steps[0].title, GATEWAY_STEP_NAME)
    assert.equal(steps[1].title, '差旅专家')
    // 入口那一层不重复写依据：网关块已经写过
    assert.deepEqual(steps[1].lines, [])
    assert.equal(steps[1].steps[0].text, '先确认城市等级')
    assert.equal(steps[1].roleLabel, undefined)
  })

  it('没有 isTopLevelOf 时与改动前一致（全部按入口处理）', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 知识问答（kb），依据：差旅制度咨询'],
      lanes: [lane('知识问答')],
      streaming: false,
      fallbackName: '智能助手'
    })
    assert.equal(steps.length, 2)
    assert.deepEqual(steps[1].lines, ['已由「知识问答」完成作答'])
  })
})

describe('不编造参与者', () => {
  it('方案没有分下去任何子专家时，只写中性状态，不出现子专家块', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 企业综合台（enterprise），依据：需要协作'],
      lanes: [lane('企业综合台', '依据：需要协作')],
      targetTypeOf: typeOf(['企业综合台']),
      isTopLevelOf: topOf(['企业综合台']),
      streaming: false,
      fallbackName: '智能助手'
    })
    assert.equal(steps.length, 2)
    assert.deepEqual(steps[1].lines, ['已由「企业综合台」完成作答'])
  })
})

describe('方案形象兜底', () => {
  it('没有形象配置时给稳定机器人，不是纯首字', () => {
    const view = resolveFallbackRobotAvatar('企业综合台')
    assert.ok(view.art && view.art.length > 0)
    assert.equal(view.initial, '企')
    // 同一名字每次落到同一套，刷新不变脸
    const again = resolveFallbackRobotAvatar('企业综合台')
    assert.deepEqual(again.art, view.art)
    assert.equal(again.initialColor, view.initialColor)
  })
})
