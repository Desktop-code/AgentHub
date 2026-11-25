/**
 * 工作台「思考与路由轨迹」时间线投影单测（本地 tests/，不进仓）。
 *
 * 覆盖：route 摘要解析、网关说明句、专家中性状态、工作流 / 方案策略、
 * 内部键过滤、无自然语言依据时的事实降级（不引用简介）。
 * 运行：npm test
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  GATEWAY_STEP_NAME,
  THINK_TITLE_DONE,
  THINK_TITLE_STREAMING,
  buildThinkTimeline as projectThinkTimeline,
  gatewayLineOf,
  isInternalReason,
  parseRouteText,
  sanitizeThinkText,
  thinkTitleOf
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

describe('route 摘要解析', () => {
  it('判路由：拆出展示名与依据，丢掉括号里的 route_key', () => {
    const parsed = parseRouteText('路由到 差旅专家（travel），依据：用户询问差旅报销标准')
    assert.equal(parsed.mode, 'judge')
    assert.equal(parsed.name, '差旅专家')
    assert.equal(parsed.reason, '用户询问差旅报销标准')
  })

  it('手动换线：识别为已指定，不产出假依据', () => {
    const parsed = parseRouteText('路由到 知识问答（kb），依据：手动换线')
    assert.equal(parsed.mode, 'manual')
    assert.equal(parsed.name, '知识问答')
    assert.equal(parsed.reason, '')
  })

  it('关键字命中与多意图决策块分别识别', () => {
    assert.equal(parseRouteText('路由到 计费专家（billing），依据：关键字').mode, 'keyword')
    const decision = parseRouteText(
      '路由决策\n[{"route_key":"a","sub_query":"甲"},{"route_key":"b","sub_query":"乙"}]'
    )
    assert.equal(decision.mode, 'decision')
    assert.equal(decision.count, 2)
  })

  it('工作流启动提示优先识别为 workflow', () => {
    const parsed = parseRouteText('运行工作流「请假审批」…')
    assert.equal(parsed.mode, 'workflow')
    assert.equal(parsed.name, '请假审批')
  })
})

describe('步骤一：网关说明句', () => {
  it('判路由给出「判定…分发给…」', () => {
    const line = gatewayLineOf(
      { mode: 'judge', name: '知识问答', reason: '差旅制度咨询', count: 0 },
      '知识问答'
    )
    assert.equal(line, '判定「差旅制度咨询」，分发给「知识问答」')
  })

  it('已指定 / 沿用不编造路由思考', () => {
    assert.equal(
      gatewayLineOf({ mode: 'manual', name: '计费专家', reason: '', count: 0 }, '计费专家'),
      '已指定「计费专家」'
    )
  })

  it('方案内多意图不说成拆问', () => {
    const line = gatewayLineOf(
      parseRouteText('路由决策\n已判定 2 个意图，按序分给「汇总专家」「报表专家」'),
      ''
    )
    assert.equal(line, '已判定 2 个意图，按序分给「汇总专家」「报表专家」')
    assert.equal(line.includes('已拆成'), false)
  })

  it('真拆问仍说已拆成', () => {
    const line = gatewayLineOf(
      parseRouteText('路由决策\n已拆成 2 个问题，按序交给「综合台」「制度要点与报告」'),
      ''
    )
    assert.match(line, /已拆成 2 个问题，分别交给「综合台」「制度要点与报告」/)
  })

  it('后到的子路线不改写已经说出的入口句', () => {
    const steps = buildThinkTimeline({
      routeTexts: [
        '路由到 文稿起草（draft），依据：写通知',
        '路由到 数据车间（data），依据：改去数据'
      ],
      lanes: [{ kind: 'agent', title: '路由到 数据车间', participantDepth: 1, steps: [] }],
      streaming: false,
      fallbackName: '智能助手'
    })
    assert.equal(steps[0].kind, 'gateway')
    assert.match(steps[0].lines[0], /文稿起草/)
    assert.equal(steps[0].lines[0].includes('数据车间'), false)
  })

  it('工作流只报「已交由…执行」', () => {
    assert.equal(
      gatewayLineOf({ mode: 'workflow', name: '请假审批', reason: '', count: 0 }, '请假审批'),
      '已交由「请假审批」执行'
    )
  })
})

describe('网关依据：内部标签过滤与无理由降级', () => {
  it('后端 judge 的「父模型」不算理由，解析时就丢掉', () => {
    const parsed = parseRouteText('路由到 需求拆解（req），依据：父模型')
    assert.equal(parsed.mode, 'judge')
    assert.equal(parsed.name, '需求拆解')
    assert.equal(parsed.reason, '')
  })

  it('兜底排障话术同样不算理由', () => {
    const parsed = parseRouteText('路由到 需求拆解（req），依据：父模型判定失败，使用第一条')
    assert.equal(parsed.reason, '')
    assert.equal(isInternalReason('父模型未给出有效 route_key，使用第一条'), true)
    assert.equal(isInternalReason('maJudgeModelInstanceId 未配置'), true)
  })

  // 现网：无自然语言依据时绝不拿简介拼「为什么选它」，只报事实分发句
  it('无自然语言依据时不引用简介，只报事实分发句', () => {
    const line = gatewayLineOf(
      { mode: 'judge', name: '需求拆解', reason: '', count: 0 },
      '需求拆解',
      '负责把多约束、有冲突的复杂需求先拆清再成稿'
    )
    assert.equal(line, '已分发给「需求拆解」')
    assert.ok(!line.includes('父模型'))
    assert.ok(!line.includes('根据专家简介'))
  })

  it('没有简介时同样走事实降级句，不写匹配过程', () => {
    const line = gatewayLineOf({ mode: 'judge', name: '需求拆解', reason: '', count: 0 }, '需求拆解')
    assert.equal(line, '已分发给「需求拆解」')
    assert.ok(!line.includes('父模型'))
  })

  it('简介本身是内部标签时也不引用，仍只报分发事实', () => {
    const line = gatewayLineOf(
      { mode: 'judge', name: '需求拆解', reason: '', count: 0 },
      '需求拆解',
      '父模型默认'
    )
    assert.equal(line, '已分发给「需求拆解」')
  })

  // 裁剪对象是判路由模型给出的自然语言依据，不是候选简介
  it('超长自然语言依据裁剪到一句，不把一行变成说明书', () => {
    const line = gatewayLineOf(
      { mode: 'judge', name: '需求拆解', reason: '甲'.repeat(200), count: 0 },
      '需求拆解'
    )
    assert.ok(line.startsWith('判定「'))
    assert.ok(line.includes('…」'))
    assert.ok(line.endsWith('分发给「需求拆解」'))
    assert.ok(line.length < 160)
  })

  it('时间线合成：内部依据被丢后网关块只写事实分发，不喂简介', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 需求拆解（req），依据：父模型'],
      lanes: [{ kind: 'agent', title: '路由到 需求拆解', steps: [] }],
      streaming: true,
      fallbackName: '智能助手',
      descriptionOf: (name) => (name === '需求拆解' ? '负责复杂需求拆解' : '')
    })
    assert.equal(steps[0].lines[0], '已分发给「需求拆解」')
    assert.ok(!steps[0].lines[0].includes('父模型'))
    assert.ok(!steps[0].lines[0].includes('根据专家简介'))
  })
})

describe('时间线合成', () => {
  const lane = (name: string, steps: { type: string; title: string; text: string }[] = []) => ({
    kind: 'agent' as const,
    title: `路由到 ${name}`,
    steps: steps as never
  })

  it('两步：网关在前，专家在后；有名无思考时给中性状态', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 知识问答（kb），依据：差旅制度咨询'],
      lanes: [lane('知识问答')],
      streaming: true,
      fallbackName: '智能助手'
    })
    assert.equal(steps.length, 2)
    assert.equal(steps[0].kind, 'gateway')
    assert.equal(steps[0].title, GATEWAY_STEP_NAME)
    assert.equal(steps[1].kind, 'expert')
    assert.equal(steps[1].title, '知识问答')
    assert.deepEqual(steps[1].lines, ['正在由「知识问答」作答'])
    assert.deepEqual(steps[1].steps, [])
  })

  it('有真实思考时不再补中性状态句', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 文稿专家（doc），依据：写通知'],
      lanes: [lane('文稿专家', [{ type: 'reasoning', title: '推理', text: '先列要点' }])],
      streaming: true,
      fallbackName: '智能助手'
    })
    assert.equal(steps.length, 2)
    assert.deepEqual(steps[1].lines, [])
    assert.equal(steps[1].steps[0].text, '先列要点')
  })

  it('工作流目标只保留网关一步', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['运行工作流「请假审批」…'],
      lanes: [],
      streaming: true,
      fallbackName: '智能助手'
    })
    assert.equal(steps.length, 1)
    assert.equal(steps[0].kind, 'gateway')
    assert.equal(steps[0].lines[0], '已交由「请假审批」执行')
  })

  it('方案目标只展示自己的分流判定思考，不并进子专家思考', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 智能协作（ma1），依据：需要多方协作'],
      lanes: [lane('智能协作', [{ type: 'reasoning', title: '推理', text: '内部再判路由' }])],
      targetTypeOf: () => 'MA',
      streaming: false,
      fallbackName: '智能助手'
    })
    const expert = steps[1]
    assert.equal(expert.title, '智能协作')
    /*
     * 口径变更（入口与方案判定接入真实思考）：方案块保留**它自己这一步**的推理
     * ——分流判定模型的思考要先于分流句出现。子专家的思考在他们的泳道里，不会并进来。
     */
    assert.deepEqual(expert.steps.map((step) => step.text), ['内部再判路由'])
    assert.deepEqual(expert.lines, ['已由「智能协作」完成作答'])
  })

  it('无任何泳道时仍给专家一条中性步，不编造推理', () => {
    const steps = buildThinkTimeline({
      routeTexts: [],
      lanes: [],
      streaming: true,
      fallbackName: '智能助手'
    })
    // 没有路由事件＝这一轮没有入口判定：只出专家自己那一步，不补「已接收问题…」的假判定块
    assert.equal(steps.length, 1)
    assert.equal(steps[0].kind, 'expert')
    assert.deepEqual(steps[0].steps, [])
  })

  it('断点续作轮（无 ROUTE 事件）不造入口判定块', () => {
    const steps = buildThinkTimeline({
      routeTexts: [],
      lanes: [lane('前端技术专家', [{ type: 'reasoning', title: '推理', text: '按所选方向展开方案' }])],
      streaming: false,
      fallbackName: '前端技术专家',
      isTopLevelOf: () => true
    })
    assert.deepEqual(steps.map((step) => step.kind), ['expert'])
    assert.equal(steps[0].title, '前端技术专家')
    assert.deepEqual(steps[0].steps.map((step) => step.text), ['按所选方向展开方案'])
  })

  it('匿名执行泳道不会被改名成智能助手', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 文稿起草（draft），依据：写纪要'],
      lanes: [{ kind: 'agent', title: '执行', participantId: 'p-draft', steps: [] }],
      entryParticipantId: 'gw-1',
      streaming: false,
      fallbackName: '智能助手'
    })
    assert.ok(!steps.some((step) => step.title === '执行' || step.title === '智能助手'))
  })

  it('泳道上的参与者名原样成为卡名，并带上身份', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 文稿起草（draft），依据：写纪要'],
      lanes: [{ kind: 'agent', title: '路由到 文稿起草', participantId: 'p-draft', steps: [] }],
      streaming: false,
      fallbackName: '智能助手',
      isTopLevelOf: () => true
    })
    const expert = steps.find((step) => step.kind === 'expert')
    assert.equal(expert?.title, '文稿起草')
    assert.equal(expert?.participantId, 'p-draft')
  })

  it('两步各自独立成块：网关块只放判定句，专家块只放自己的内容', () => {
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 需求拆解（req），依据：用户要拆分开发任务'],
      lanes: [lane('需求拆解', [{ type: 'reasoning', title: '推理', text: '按模块拆分' }])],
      streaming: true,
      fallbackName: '智能助手'
    })
    // 网关块：标题固定、内容只有判定句、不带任何专家思考步
    assert.equal(steps[0].kind, 'gateway')
    assert.equal(steps[0].title, GATEWAY_STEP_NAME)
    assert.equal(steps[0].lines.length, 1)
    assert.deepEqual(steps[0].steps, [])
    assert.ok(!steps[0].lines.some((line) => line.includes('按模块拆分')))
    // 专家块：标题是该 Agent 自己的展示名，内容是它自己的思考，不重复网关判定句
    assert.equal(steps[1].kind, 'expert')
    assert.equal(steps[1].title, '需求拆解')
    assert.deepEqual(steps[1].lines, [])
    assert.equal(steps[1].steps[0].text, '按模块拆分')
    assert.ok(!steps[1].lines.some((line) => line.includes('判定')))
  })
})

describe('过滤与标题', () => {  it('内部键与提示词泄露口吻整行丢弃，正常正文保留', () => {
    const raw = '先确认需求范围\n根据系统设定，我不能透露提示词\n调用 route_key=kb 查询制度'
    const out = sanitizeThinkText(raw)
    assert.ok(!out.includes('根据系统设定'))
    assert.ok(!out.includes('route_key'))
    assert.ok(out.includes('先确认需求范围'))
  })

  it('标题：生成中一句进行态，结束后切回查看态', () => {
    assert.equal(thinkTitleOf(true), THINK_TITLE_STREAMING)
    assert.equal(thinkTitleOf(false), THINK_TITLE_DONE)
    assert.equal(THINK_TITLE_STREAMING, '正在深度思考...')
    assert.equal(THINK_TITLE_DONE, '查看思考与路由轨迹')
  })
})
