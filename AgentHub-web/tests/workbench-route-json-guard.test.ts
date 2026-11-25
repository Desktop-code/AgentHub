/**
 * 路由机器 JSON 不得进用户可见正文 + 时间线兜底形象单测（本地 tests/，不进仓）。
 *
 * 覆盖本刀三项收口：
 * 1. 新数据：人话决策块（「已判定 N 个意图，按序分给…」）能算出条数，且正文不外泄；
 * 2. 历史数据：`[{"route_key":…,"sub_query":…}]` 一律不进网关句、依据、思考文本；
 * 3. 兜底形象：方案 / 子专家没配形象时也必须给机器人图元，不能只剩首字方座。
 *
 * 运行：npm test
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { buildThinkTimeline, parseRouteText, sanitizeThinkText } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
import {
  looksLikeRouteMachineJson,
  stripRouteDecisionJson,
  stripWorkbenchInternalKeys
} from '../src/components/runtime/workbench/workbenchMessageMeta.ts'
import { resolveFallbackRobotAvatar } from '../src/constants/agentLooks.ts'

/** 造一条子 Agent 泳道 */
const lane = (name: string, body?: string, steps: { type: string; title: string; text: string }[] = []) => ({
  kind: 'agent' as const,
  title: `路由到 ${name}`,
  body,
  steps: steps as never
})

describe('路由决策 JSON 不外泄', () => {
  it('识别机器 JSON（数组 / 对象 / 大小写键）', () => {
    assert.equal(looksLikeRouteMachineJson('[{"route_key":"policy","sub_query":"差旅标准"}]'), true)
    assert.equal(looksLikeRouteMachineJson('{"routeKey":"policy","subQuery":"差旅标准"}'), true)
    assert.equal(looksLikeRouteMachineJson('路由到 制度知识专家'), false)
    assert.equal(looksLikeRouteMachineJson('已判定 2 个意图，按序分给「甲」「乙」'), false)
    // 半截 JSON（流式中）不算：交给其它清洗规则，别把业务正文误杀
    assert.equal(looksLikeRouteMachineJson('[{"route_key":"pol'), false)
    assert.equal(looksLikeRouteMachineJson(''), false)
  })

  it('历史 JSON 决策块不产生网关句里的 JSON，也不进思考文本', () => {
    const json = '[{"route_key":"policy","sub_query":"差旅标准","reason":"用户问制度"}]'
    const parsed = parseRouteText(`路由决策\n${json}`)
    assert.equal(parsed.mode, 'decision')
    // 条数从 JSON 字段还原，但正文（name/reason）一律为空
    assert.equal(parsed.count, 1)
    assert.equal(parsed.name, '')
    assert.equal(parsed.reason, '')

    const steps = buildThinkTimeline({
      routeTexts: [`路由决策\n${json}`, '路由到 制度知识专家（policy），依据：用户问制度'],
      lanes: [
        lane('制度知识专家', '依据：用户问制度', [
          { type: 'reasoning', title: '推理', text: `先看看 ${json}` }
        ])
      ],
      targetTypeOf: () => 'AGENT',
      isTopLevelOf: () => true,
      streaming: false,
      fallbackName: '智能助手'
    })
    const rendered = JSON.stringify(steps)
    assert.equal(rendered.includes('route_key'), false, '时间线里不得出现 route_key')
    assert.equal(rendered.includes('sub_query'), false, '时间线里不得出现 sub_query')
  })

  it('新数据人话决策块能算出条数', () => {
    const parsed = parseRouteText('路由决策\n已判定 2 个意图，按序分给「制度知识专家」「报告撰写专家」')
    assert.equal(parsed.mode, 'decision')
    assert.equal(parsed.count, 2)
    assert.equal(parsed.name, '')
  })

  it('裸机器 JSON 当成无路线，不参与网关句', () => {
    const parsed = parseRouteText('[{"route_key":"policy","sub_query":"差旅标准"}]')
    assert.equal(parsed.mode, 'direct')
  })

  it('气泡正文兜底：历史落库的决策 JSON 不打给用户', () => {
    // 助手正文块整段是旧决策 JSON：一个字都不留
    assert.equal(stripRouteDecisionJson('[{"route_key":"policy","sub_query":"差旅标准"}]'), '')
    // JSON 混在正文里：只丢那几行，业务成文仍保留
    const mixed = '先按制度回答。\n[{"route_key":"policy","sub_query":"差旅标准"}]\n共 3 条依据。'
    assert.equal(stripRouteDecisionJson(mixed), '先按制度回答。\n共 3 条依据。')
    // 正常成文不受影响
    assert.equal(stripRouteDecisionJson('## 结论\n差旅标准见附件。'), '## 结论\n差旅标准见附件。')
  })

  it('脱敏函数仍保留业务正文，只抹内部键', () => {
    assert.equal(
      stripWorkbenchInternalKeys('调用了 searchKnowledgeBase，参数 {"route_key":"policy","q":"差旅"}'),
      '调用了 searchKnowledgeBase，参数 {"q":"差旅"}'
    )
    // 模型把决策对象复述进推理句：只抠 JSON，留下周围人话（入口判定常这样写）
    assert.equal(
      sanitizeThinkText('先看看 [{"route_key":"policy","sub_query":"差旅标准"}] 再答'),
      '先看看 再答'
    )
    // 整行就是决策 JSON：仍一个字都不留
    assert.equal(sanitizeThinkText('[{"route_key":"policy","sub_query":"差旅标准"}]'), '')
    // 普通业务句里的内部键仍只抹键名，正文保留（抹键后多空格收成单空格）
    assert.equal(sanitizeThinkText('调用专家 route_key=billing_expert 处理账单'), '调用专家 处理账单')
  })
})

describe('时间线兜底形象', () => {
  it('方案与子专家没配形象时都给机器人图元（不是首字方座）', () => {
    const scheme = resolveFallbackRobotAvatar('企业综合台')
    const child = resolveFallbackRobotAvatar('制度知识专家')
    const other = resolveFallbackRobotAvatar('报告撰写专家')
    for (const view of [scheme, child, other]) {
      assert.ok(Array.isArray(view.art) && view.art!.length > 0, '必须命中机器人图形')
      assert.equal(view.isRobot, true)
    }
    // 稳定：同名每次同一套
    assert.deepEqual(resolveFallbackRobotAvatar('企业综合台').art, scheme.art)
  })

  it('时间线把形象接到 lookOf 上（方案 / 子专家都有 look）', () => {
    const lookOf = (name: string) => {
      const view = resolveFallbackRobotAvatar(name)
      return {
        initial: view.initial,
        iconSrc: view.imgSrc || undefined,
        art: view.art,
        color: view.background,
        initialColor: view.initialColor,
        custom: view.isCustom
      }
    }
    const steps = buildThinkTimeline({
      routeTexts: ['路由到 企业综合台（enterprise），依据：需要制度与成稿'],
      lanes: [lane('企业综合台', '依据：制度与成稿'), lane('制度知识专家', '依据：问制度'), lane('报告撰写专家', '依据：要成稿')],
      targetTypeOf: (name: string) => (name === '企业综合台' ? 'MA' : 'AGENT'),
      isTopLevelOf: (name: string) => name === '企业综合台',
      streaming: false,
      fallbackName: '智能助手',
      lookOf
    })
    const experts = steps.filter((step) => step.kind === 'expert')
    assert.equal(experts.length, 3)
    for (const step of experts) {
      assert.ok(step.look && Array.isArray(step.look.art) && step.look.art.length > 0, `${step.title} 必须有头像图元`)
    }
  })
})
