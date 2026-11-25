/**
 * 编排泳道模型收敛测试（node:test，无浏览器）。所属域：前端 runtime。
 *
 * 覆盖验收点：
 * 1) 多意图 + SUMMARIZE：总控 / 两子 / 汇总三行都在，两子时间窗重叠（并行观感）；
 * 2) route_key 优先按 sub_query 对齐，对不上再按判定顺序兜底；
 * 3) CONCAT 无模型调用：汇总行不画块，事件给「按序拼接」；
 * 4) 子失败不打断汇总：失败事件 tone=danger，汇总额外成行；
 * 5) SUMMARIZE 声明了却找不到汇总调用：给 warn 事件，不假装成功；
 * 6) 没有 ROUTE 根 / 空 spans：hasRouterTrace=false，不抛错。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/orchestrationLane.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildOrchestrationLane } from '../src/utils/orchestrationLane.ts'
import type { ObsSpan } from '../src/types/obs.ts'

/**
 * 造一个步骤；只填测试关心的字段。
 *
 * @param patch 覆盖项
 * @return 完整 ObsSpan
 */
function span(patch: Partial<ObsSpan> = {}): ObsSpan {
  return {
    id: 1,
    parentId: null,
    stepNo: '1',
    seq: 1,
    spanType: 'AGENT',
    name: 'agent',
    agentId: 201,
    agentName: '售后专家',
    status: 'OK',
    startTime: '2026-11-04 10:00:00',
    endTime: '2026-11-04 10:00:01',
    latencyMs: 1000,
    inputText: null,
    outputText: null,
    errorSummary: null,
    attrsJson: null,
    routeDecision: null,
    depth: 0,
    ...patch
  }
}

/** 判定 JSON 便捷构造 */
function decision(rows: { routeKey: string; subQuery: string }[]): string {
  return JSON.stringify(rows.map((row) => ({ route_key: row.routeKey, sub_query: row.subQuery })))
}

/** 多意图 + SUMMARIZE 的完整链路 */
function multiIntentSpans(): ObsSpan[] {
  return [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'judge',
      agentName: '客服总控',
      agentId: 100,
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:01',
      latencyMs: 1000,
      attrsJson: JSON.stringify({ 'ma.template': 'ROUTER_PARALLEL', 'ma.aggregate': 'SUMMARIZE' }),
      routeDecision: decision([
        { routeKey: 'after_sale', subQuery: '我要退货' },
        { routeKey: 'logistics', subQuery: '查物流' }
      ])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      agentId: 100,
      agentName: '客服总控',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:01',
      latencyMs: 1000
    }),
    span({
      id: 3,
      seq: 3,
      parentId: 2,
      agentId: 201,
      agentName: '售后专家',
      inputText: '我要退货',
      // 两子同刻开工、同时长窗重叠：并行观感必须来自时间窗而不是排序
      startTime: '2026-11-04 10:00:01',
      endTime: '2026-11-04 10:00:04',
      latencyMs: 3000
    }),
    span({
      id: 4,
      seq: 4,
      parentId: 2,
      agentId: 202,
      agentName: '物流专家',
      inputText: '查物流',
      startTime: '2026-11-04 10:00:01',
      endTime: '2026-11-04 10:00:03',
      latencyMs: 2000
    }),
    span({
      id: 5,
      seq: 5,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      agentId: 100,
      agentName: '客服总控',
      startTime: '2026-11-04 10:00:04',
      endTime: '2026-11-04 10:00:05',
      latencyMs: 1000
    })
  ]
}

test('多意图 SUMMARIZE：总控 / 两子并行 / 汇总成行，route_key 对齐', () => {
  const model = buildOrchestrationLane(multiIntentSpans())

  assert.equal(model.hasRouterTrace, true)
  assert.equal(model.template, 'ROUTER_PARALLEL')
  assert.equal(model.aggregate, 'SUMMARIZE')
  assert.equal(model.childCount, 2)
  assert.equal(model.routeSpan?.spanType, 'ROUTE')
  assert.deepEqual(model.routeKeys, ['after_sale', 'logistics'])

  // 行顺序固定：总控 → 子（判定顺序）→ 汇总
  assert.deepEqual(model.rows.map((row) => row.kind), ['judge', 'child', 'child', 'aggregate'])
  assert.equal(model.rows[0].title, '客服总控')
  assert.equal(model.rows[0].spans.length, 2)
  assert.equal(model.rows[1].title, '售后专家')
  assert.equal(model.rows[1].subtitle, 'after_sale · REACT')
  assert.equal(model.rows[2].subtitle, 'logistics · REACT')
  assert.equal(model.rows[3].spans.length, 1)
  assert.equal(model.rows[3].running, false)

  // 并行：两子开工时刻相同、时间窗重叠（比任何排序都更能说明并行）
  const first = model.rows[1].spans[0]
  const second = model.rows[2].spans[0]
  const startA = Date.parse(first.startTime as string)
  const endA = Date.parse(first.endTime as string)
  const startB = Date.parse(second.startTime as string)
  assert.equal(startA, startB)
  assert.ok(endA > startB)
})

test('事件列表：中文可读，含路由判定 / 子开始 / 子成功 / 汇总成功', () => {
  const model = buildOrchestrationLane(multiIntentSpans())
  const texts = model.events.map((event) => event.text)

  assert.ok(texts.some((text) => text.includes('路由判定 → after_sale、logistics')))
  assert.ok(texts.some((text) => text.includes('子「售后专家」开始') && text.includes('我要退货')))
  assert.ok(texts.some((text) => text.includes('子「售后专家」成功（3000ms）')))
  assert.ok(texts.some((text) => text.includes('子「物流专家」成功（2000ms）')))
  assert.ok(texts.some((text) => text.includes('结果合成 SUMMARIZE 成功')))

  // 事件按墙钟升序：路由判定在最前，汇总最后
  assert.ok(model.events[0].text.startsWith('路由判定'))
  assert.ok(model.events[model.events.length - 1].text.includes('结果合成 SUMMARIZE'))
})

test('route_key 对不上 sub_query 时按判定顺序兜底', () => {
  const spans = multiIntentSpans()
  // 子 span 的展示子问与判定里不一致（例如判路由改写过），仍要能标出 route_key
  spans[2] = { ...spans[2], inputText: '用户想退货' }
  const model = buildOrchestrationLane(spans)

  assert.equal(model.rows[1].subtitle, 'after_sale · REACT')
  assert.equal(model.rows[2].subtitle, 'logistics · REACT')
})

test('CONCAT：汇总行不画块，事件说明按序拼接', () => {
  const model = buildOrchestrationLane([
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'judge',
      agentName: '客服总控',
      attrsJson: JSON.stringify({ 'ma.template': 'ROUTER_PARALLEL', 'ma.aggregate': 'CONCAT' }),
      routeDecision: decision([{ routeKey: 'after_sale', subQuery: '我要退货' }])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:01'
    }),
    span({
      id: 3,
      seq: 3,
      parentId: 2,
      agentName: '售后专家',
      inputText: '我要退货',
      startTime: '2026-11-04 10:00:01',
      endTime: '2026-11-04 10:00:02'
    })
  ])

  assert.equal(model.aggregate, 'CONCAT')
  assert.equal(model.childCount, 1)
  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.ok(aggregateRow)
  assert.equal(aggregateRow?.spans.length, 0)
  assert.ok(model.aggregateNote.includes('无模型调用'))
  assert.ok(model.events.some((event) => event.text.includes('结果合成 CONCAT')))
})

test('子失败：失败事件标红，汇总仍成行（部分失败不打断）', () => {
  const spans = multiIntentSpans()
  spans[3] = {
    ...spans[3],
    status: 'ERROR',
    errorSummary: '子超时',
    latencyMs: 4000,
    endTime: '2026-11-04 10:00:05'
  }
  const model = buildOrchestrationLane(spans)

  const failedRow = model.rows[2]
  assert.equal(failedRow.failed, true)
  assert.equal(failedRow.running, false)
  const failedEvent = model.events.find((event) => event.text.includes('子「物流专家」失败'))
  assert.ok(failedEvent)
  assert.equal(failedEvent?.tone, 'danger')
  // 汇总行仍在，且未失败
  assert.equal(model.rows[3].kind, 'aggregate')
  assert.equal(model.rows[3].failed, false)
})

test('SUMMARIZE 但没有汇总调用：给 warn，不假装成功', () => {
  const spans = multiIntentSpans().filter((item) => item.id !== 5)
  const model = buildOrchestrationLane(spans)

  assert.equal(model.aggregate, 'SUMMARIZE')
  assert.equal(model.aggregateNote, '未找到汇总调用，可能已降级为拼接')
  const warn = model.events.find((event) => event.text.includes('未找到汇总调用'))
  assert.ok(warn)
  assert.equal(warn?.tone, 'warn')
})

test('淘汰旧数据：没有 attrs 时模板回落 ROUTER_PARALLEL，汇总按是否存在 LLM 推断', () => {
  const spans = multiIntentSpans()
  spans[0] = { ...spans[0], attrsJson: null }
  const model = buildOrchestrationLane(spans)

  assert.equal(model.template, 'ROUTER_PARALLEL')
  assert.equal(model.aggregate, 'SUMMARIZE')
})

test('子 P&E：span attrs 带 ma.child_run_mode 时泳道标「计划执行」', () => {
  const spans = multiIntentSpans()
  spans[2] = {
    ...spans[2],
    attrsJson: JSON.stringify({ 'ma.child_run_mode': 'PLAN_AND_EXECUTE' })
  }
  const model = buildOrchestrationLane(spans)

  assert.equal(model.rows[1].subtitle, 'after_sale · 计划执行')
  // 未带该属性的子仍按 REACT 展示，避免旧 trace 被误标
  assert.equal(model.rows[2].subtitle, 'logistics · REACT')
})

test('待批准子计划：命中 route_key 的子泳道追加「待批准」，其余不动', () => {
  const model = buildOrchestrationLane(multiIntentSpans(), 'logistics')

  assert.equal(model.rows[1].subtitle, 'after_sale · REACT')
  assert.equal(model.rows[2].subtitle, 'logistics · REACT · 待批准')
  // 空 key 或对不上时不标记任何行
  const none = buildOrchestrationLane(multiIntentSpans(), 'missing')
  assert.equal(none.rows[2].subtitle, 'logistics · REACT')
})

test('没有 ROUTE 根 / 空数组：hasRouterTrace=false，不抛错', () => {
  assert.equal(buildOrchestrationLane([]).hasRouterTrace, false)
  const noRoute = buildOrchestrationLane([
    span({ id: 1, seq: 1, spanType: 'LLM', parentId: null })
  ])
  assert.equal(noRoute.hasRouterTrace, false)
  assert.equal(noRoute.rows.length, 0)
  assert.equal(noRoute.events.length, 0)
})

/**
 * 造固定模板链路：无判路由 LLM，子 AGENT 直接挂 ROUTE，sub_query 都是同一份用户原问。
 *
 * @param template  FIXED_PARALLEL / FIXED_SEQUENCE
 * @param starts    两子的开始时刻（串行传不重叠的时间）
 * @param ends      两子的结束时刻
 */
function fixedSpans(template: string, starts: string[], ends: string[]): ObsSpan[] {
  return [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: template === 'FIXED_SEQUENCE' ? 'fixed-sequence' : 'fixed-parallel',
      agentName: '客服总控',
      agentId: 100,
      attrsJson: JSON.stringify({
        'ma.template': template,
        'ma.aggregate': 'CONCAT',
        'ma.skip_judge': 'true'
      }),
      routeDecision: decision([
        { routeKey: 'a', subQuery: '用户原问' },
        { routeKey: 'b', subQuery: '用户原问' }
      ])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      agentId: 201,
      agentName: '子A',
      inputText: '用户原问',
      startTime: starts[0],
      endTime: ends[0]
    }),
    span({
      id: 3,
      seq: 3,
      parentId: 1,
      agentId: 202,
      agentName: '子B',
      inputText: '用户原问',
      startTime: starts[1],
      endTime: ends[1]
    })
  ]
}

test('固定并行：总控标「无判路由」，两子同刻开工且 route_key 按序对齐', () => {
  const model = buildOrchestrationLane(
    fixedSpans('FIXED_PARALLEL', ['2026-11-04 10:00:01', '2026-11-04 10:00:01'],
      ['2026-11-04 10:00:04', '2026-11-04 10:00:03'])
  )

  assert.equal(model.template, 'FIXED_PARALLEL')
  assert.equal(model.rows[0].subtitle, '固定并行 · 无判路由')
  // 无判路由 LLM：总控行只有 ROUTE 一条
  assert.equal(model.rows[0].spans.length, 1)
  // 同一份用户原问不能把两子都对齐到同一个 key，必须按序
  assert.equal(model.rows[1].subtitle, 'a · REACT')
  assert.equal(model.rows[2].subtitle, 'b · REACT')
  assert.ok(model.events[0].text.includes('固定并行：跳过判路由'))
  const first = model.rows[1].spans[0]
  const second = model.rows[2].spans[0]
  assert.equal(Date.parse(first.startTime as string), Date.parse(second.startTime as string))
})

test('固定串行：子块时间窗不重叠，事件按序用箭头', () => {
  const model = buildOrchestrationLane(
    fixedSpans('FIXED_SEQUENCE', ['2026-11-04 10:00:01', '2026-11-04 10:00:03'],
      ['2026-11-04 10:00:03', '2026-11-04 10:00:05'])
  )

  assert.equal(model.template, 'FIXED_SEQUENCE')
  assert.equal(model.rows[0].subtitle, '固定串行 · 无判路由')
  assert.ok(model.events[0].text.includes('固定串行：跳过判路由，按序执行'))
  assert.ok(model.events[0].text.includes('a → b'))
  const first = model.rows[1].spans[0]
  const second = model.rows[2].spans[0]
  // 串行：后一步开始不早于前一步结束，泳道不得画成并排假并行
  assert.ok(Date.parse(second.startTime as string) >= Date.parse(first.endTime as string))
})

/**
 * 造评审环链路：无判路由 LLM，执行者 / 评审专家串行挂在 ROUTE 下。
 *
 * @param roles  各阶段角色：executor / critic
 * @param rounds 各阶段轮次：初稿 0，其余按评审计数
 * @param times  各阶段 [start, end]，调用方需保证串行不重叠
 * @return 步骤数组
 */
function criticSpans(
  roles: string[],
  rounds: number[],
  times: [string, string][]
): ObsSpan[] {
  const stages = roles.map((role, index) => span({
    id: index + 2,
    seq: index + 2,
    parentId: 1,
    agentId: role === 'critic' ? 301 : 201,
    agentName: role === 'critic' ? '评审专家' : '售后专家',
    inputText: '我要退货',
    startTime: times[index][0],
    endTime: times[index][1],
    attrsJson: JSON.stringify({ 'ma.role': role, 'ma.critic_round': rounds[index] })
  }))
  return [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'critic-loop',
      agentName: '客服总控',
      agentId: 100,
      startTime: times[0][0],
      endTime: times[times.length - 1][1],
      attrsJson: JSON.stringify({
        'ma.template': 'CRITIC_LOOP',
        'ma.skip_judge': 'true',
        'ma.critic_rounds_used': rounds.filter((round) => round > 0).length,
        'ma.critic_decision': 'ACCEPT'
      })
    }),
    ...stages
  ]
}

test('评审环 ACCEPT：总控「无判路由」，执行者 → 评审串行，终答不做多段拼接', () => {
  const model = buildOrchestrationLane(criticSpans(
    ['executor', 'critic'],
    [0, 1],
    [['2026-11-04 10:00:00', '2026-11-04 10:00:02'], ['2026-11-04 10:00:02', '2026-11-04 10:00:03']]
  ))

  assert.equal(model.template, 'CRITIC_LOOP')
  // 评审环没有汇总调用：aggregate 固定 NONE，避免被误读成 CONCAT / SUMMARIZE
  assert.equal(model.aggregate, 'NONE')
  assert.equal(model.rows[0].subtitle, '评审环 · 无判路由')
  // 总控行只有 ROUTE 本身，不画 JUDGE LLM
  assert.equal(model.rows[0].spans.length, 1)
  assert.equal(model.rows[1].subtitle, '执行者 · 出稿')
  assert.equal(model.rows[2].subtitle, '评审专家 · 第 1 轮')
  // 串行：评审开始不早于执行者结束
  assert.ok(Date.parse(model.rows[2].spans[0].startTime as string)
    >= Date.parse(model.rows[1].spans[0].endTime as string))

  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.ok(aggregateRow)
  assert.equal(aggregateRow?.title, '终答（最后一版执行稿）')
  assert.equal(aggregateRow?.spans.length, 0)
  assert.ok(model.aggregateNote.includes('终答＝最后一版执行稿'))
  assert.ok(model.events.some((event) => event.text.includes('评审环：跳过判路由')))
  assert.ok(model.events.some((event) => event.text.includes('终答＝最后一版执行稿')))
  // 不得出现结果合成事件，防止旧文案误导
  assert.ok(!model.events.some((event) => event.text.includes('结果合成')))
})

test('评审环 REVISE：初稿 → 评审 → 回修串行，第三段标「回修第 1 轮」', () => {
  const model = buildOrchestrationLane(criticSpans(
    ['executor', 'critic', 'executor'],
    [0, 1, 1],
    [
      ['2026-11-04 10:00:00', '2026-11-04 10:00:02'],
      ['2026-11-04 10:00:02', '2026-11-04 10:00:03'],
      ['2026-11-04 10:00:03', '2026-11-04 10:00:05']
    ]
  ))

  assert.equal(model.rows[1].subtitle, '执行者 · 出稿')
  assert.equal(model.rows[2].subtitle, '评审专家 · 第 1 轮')
  assert.equal(model.rows[3].subtitle, '执行者 · 回修第 1 轮')
  // 回修稿是终答：仍不做二次评审，也不新增汇总块
  assert.equal(model.rows.filter((row) => row.kind === 'child').length, 3)
  assert.equal(model.aggregate, 'NONE')
})

test('评审环 P&E 执行者待批准：无 route_key 时按 agentId 命中唯一待批槽', () => {
  const spans = criticSpans(
    ['executor', 'critic'],
    [0, 1],
    [['2026-11-04 10:00:00', '2026-11-04 10:00:02'], ['2026-11-04 10:00:02', '2026-11-04 10:00:03']]
  )
  // 执行者 id=201；评审专家 id=301
  const paused = buildOrchestrationLane(spans, '', 201)
  assert.equal(paused.rows[1].subtitle, '执行者 · 出稿 · 待批准')
  assert.equal(paused.rows[2].subtitle, '评审专家 · 第 1 轮')
  // 命中不存在的 agentId 时不得误标
  const none = buildOrchestrationLane(spans, '', 999)
  assert.equal(none.rows[1].subtitle, '执行者 · 出稿')
})

/**
 * 造路由后串行链路：有 JUDGE LLM，但两子按判定序串行（时间窗不重叠）。
 *
 * @param starts 两子的开始时刻（串行传不重叠的时间）
 * @param ends   两子的结束时刻
 * @return 步骤数组
 */
function routerThenSequenceSpans(starts: string[], ends: string[]): ObsSpan[] {
  return [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'router-then-sequence',
      agentName: '客服总控',
      agentId: 100,
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:01',
      // 有 JUDGE：绝不带 ma.skip_judge
      attrsJson: JSON.stringify({ 'ma.template': 'ROUTER_THEN_SEQUENCE', 'ma.aggregate': 'CONCAT' }),
      routeDecision: decision([
        { routeKey: 'a', subQuery: '我要退货' },
        { routeKey: 'b', subQuery: '查物流' }
      ])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      agentId: 100,
      agentName: '客服总控',
      inputText: '用户原问',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:01'
    }),
    span({
      id: 3,
      seq: 3,
      parentId: 2,
      agentId: 201,
      agentName: '售后专家',
      inputText: '我要退货',
      startTime: starts[0],
      endTime: ends[0]
    }),
    span({
      id: 4,
      seq: 4,
      parentId: 2,
      agentId: 202,
      agentName: '物流专家',
      inputText: '查物流',
      startTime: starts[1],
      endTime: ends[1]
    })
  ]
}

test('路由后串行：有 JUDGE，子按判定序串行且文案区分于路由并行', () => {
  const model = buildOrchestrationLane(routerThenSequenceSpans(
    ['2026-11-04 10:00:01', '2026-11-04 10:00:03'],
    ['2026-11-04 10:00:03', '2026-11-04 10:00:05']
  ))

  assert.equal(model.template, 'ROUTER_THEN_SEQUENCE')
  // 总控行：ROUTE + 判路由 LLM（不是「无判路由」）
  assert.equal(model.rows[0].subtitle, '路由后串行 · JUDGE 后串行')
  assert.equal(model.rows[0].spans.length, 2)
  // 子行带「串行第 k/N 步」，route_key 仍按 sub_query 对齐
  assert.equal(model.rows[1].subtitle, 'a · REACT · 串行第 1/2 步')
  assert.equal(model.rows[2].subtitle, 'b · REACT · 串行第 2/2 步')
  // 串行：后一步开始不早于前一步结束，泳道不得画成假并行扇出
  assert.ok(Date.parse(model.rows[2].spans[0].startTime as string)
    >= Date.parse(model.rows[1].spans[0].endTime as string))
  // 文案：JUDGE 判定 → a → b，且全篇不得出现「无判路由」
  const texts = model.events.map((event) => event.text)
  assert.ok(texts[0].includes('路由后串行：JUDGE 判定'))
  assert.ok(texts[0].includes('a → b'))
  assert.ok(texts.some((text) => text.includes('串行第 1 步')))
  assert.ok(texts.some((text) => text.includes('按判定顺序串行拼接')))
  assert.ok(!texts.some((text) => text.includes('无判路由')))
  assert.ok(!texts.some((text) => text.includes('跳过判路由')))
})

test('路由后串行：SUMMARIZE 仍识别汇总 LLM，降级/成功事件与并行模板同口径', () => {
  const spans = routerThenSequenceSpans(
    ['2026-11-04 10:00:01', '2026-11-04 10:00:03'],
    ['2026-11-04 10:00:03', '2026-11-04 10:00:05']
  )
  // ROUTE attrs 声明 SUMMARIZE，并在全部子结束后追加一个汇总 LLM
  spans[0] = {
    ...spans[0],
    attrsJson: JSON.stringify({ 'ma.template': 'ROUTER_THEN_SEQUENCE', 'ma.aggregate': 'SUMMARIZE' })
  }
  spans.push(span({
    id: 5,
    seq: 5,
    parentId: 1,
    spanType: 'LLM',
    name: 'router-model',
    agentId: 100,
    agentName: '客服总控',
    startTime: '2026-11-04 10:00:05',
    endTime: '2026-11-04 10:00:06'
  }))

  const model = buildOrchestrationLane(spans)
  assert.equal(model.template, 'ROUTER_THEN_SEQUENCE')
  assert.equal(model.aggregate, 'SUMMARIZE')
  // 判路由 LLM 与汇总 LLM 必须分开：总控行只挂判路由那条
  assert.equal(model.rows[0].spans.length, 2)
  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.equal(aggregateRow?.spans.length, 1)
  assert.ok(model.events.some((event) => event.text.includes('结果合成 SUMMARIZE 成功')))
})


/* ===== 共享草稿区（SHARED_SCRATCH）：MA-P1 刀7 只读徽标数据 ===== */

test('共享草稿区：ROUTE attrs 带 ma.context 与键数 / 冲突数时，模型原样带出', () => {
  const model = buildOrchestrationLane([
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'judge',
      agentId: 100,
      attrsJson: JSON.stringify({
        'ma.template': 'ROUTER_PARALLEL',
        'ma.aggregate': 'CONCAT',
        'ma.context': 'SHARED_SCRATCH',
        'ma.scratch.key_count': 3,
        'ma.scratch.conflict_count': 1
      })
    })
  ])

  assert.equal(model.contextPolicy, 'SHARED_SCRATCH')
  assert.equal(model.scratchKeyCount, 3)
  assert.equal(model.scratchConflictCount, 1)
})

test('共享草稿区：旧 trace 没有打点时不编造数字（null 而非 0）', () => {
  const model = buildOrchestrationLane([
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'judge',
      agentId: 100,
      attrsJson: JSON.stringify({ 'ma.template': 'ROUTER_PARALLEL', 'ma.aggregate': 'CONCAT' })
    })
  ])

  assert.equal(model.contextPolicy, '')
  assert.equal(model.scratchKeyCount, null)
  assert.equal(model.scratchConflictCount, null)
})

/* ===== 竞争选优（COMPETE）：刀4 第六模板的泳道口径 ===== */

/**
 * 竞争选优 trace：ROUTE（无判路由）→ 两子同题并行 → 评选 LLM 选优。
 *
 * @param winner 赢家 route_key；不传＝没有选中标记
 * @param degraded 是否降级为首个成功子
 * @return 步骤数组
 */
function competeSpans(winner = 'a', degraded = false): ObsSpan[] {
  return [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'compete',
      agentName: '客服总控',
      agentId: 100,
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:06',
      latencyMs: 6000,
      attrsJson: JSON.stringify({
        'ma.template': 'COMPETE',
        'ma.aggregate': 'PICK',
        'ma.skip_judge': 'true',
        'ma.compete.strategy': 'JUDGE_PICK',
        'ma.compete.strategy_used': degraded ? 'FIRST_SUCCESS' : 'JUDGE_PICK',
        'ma.compete.winner': winner,
        'ma.compete.degraded': degraded ? 'true' : 'false',
        'ma.compete.ok': '2',
        'ma.compete.fail': '0'
      }),
      // 同题竞争：每个候选的 sub_query 都是用户原问全文
      routeDecision: decision([
        { routeKey: 'a', subQuery: '我要退货' },
        { routeKey: 'b', subQuery: '我要退货' }
      ])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      agentId: 201,
      agentName: '售后专家',
      inputText: '我要退货',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:04',
      latencyMs: 4000
    }),
    span({
      id: 3,
      seq: 3,
      parentId: 1,
      agentId: 202,
      agentName: '物流专家',
      inputText: '我要退货',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:05',
      latencyMs: 5000
    }),
    span({
      id: 4,
      seq: 4,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      agentId: 100,
      agentName: '客服总控',
      // 评选调用晚于全部子结束：只能归到汇总行
      startTime: '2026-11-04 10:00:05',
      endTime: '2026-11-04 10:00:06'
    })
  ]
}

test('竞争选优：无判路由、同题并行，winner 标在对应子行', () => {
  const model = buildOrchestrationLane(competeSpans('b'))

  assert.equal(model.template, 'COMPETE')
  assert.equal(model.aggregate, 'PICK')
  assert.equal(model.competeWinner, 'b')
  assert.equal(model.competeDegraded, false)
  // 总控行只有 ROUTE（无判路由 LLM），副标题明确「无判路由」
  assert.equal(model.rows[0].subtitle, '竞争选优 · 无判路由')
  assert.equal(model.rows[0].spans.length, 1)
  // 两子同题并行：sub_query 全等，route_key 只能按候选顺序对齐
  assert.equal(model.rows[1].subtitle, 'a · REACT')
  assert.ok(model.rows[2].subtitle.includes('★ 选中'), model.rows[2].subtitle)
  const winnerEvent = model.events.find((event) => event.text.includes('★ 选中'))
  assert.ok(winnerEvent?.text.includes('物流专家'), winnerEvent?.text)
})

test('竞争选优：汇总行＝选优（PICK），日期在子结束之后，不写成拼接', () => {
  const model = buildOrchestrationLane(competeSpans('a'))
  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.ok(aggregateRow?.title.includes('选优'), aggregateRow?.title)
  assert.equal(aggregateRow?.title, '选优（PICK · a）')
  assert.equal(aggregateRow?.spans.length, 1, '评选 LLM 归到汇总行')
  assert.ok(model.aggregateNote.includes('只取 1 份终答'))
  const texts = model.events.map((event) => event.text)
  assert.ok(texts.some((text) => text.includes('竞争选优：跳过判路由') && text.includes('同题并行')))
  assert.ok(texts.some((text) => text.includes('选优：策略 评选模型选优') && text.includes('winner=a')))
  assert.ok(!texts.some((text) => text.includes('结果合成 CONCAT')))
})

test('竞争选优：降级为首个成功子时给 warn 事件并说明原因', () => {
  const model = buildOrchestrationLane(competeSpans('a', true))
  assert.equal(model.competeDegraded, true)
  assert.equal(model.competeStrategy, 'FIRST_SUCCESS')
  const degraded = model.events.find((event) => event.text.includes('选优降级'))
  assert.equal(degraded?.tone, 'warn')
  assert.ok(model.aggregateNote.includes('降级'))
})

/* ===== 投票选优（VOTE）：刀5 汇总策略的泳道口径 ===== */

/**
 * 投票选优 trace：ROUTE（判路由）→ 两子各自 sub_query 并行 → 投票 LLM。
 *
 * @param winner   选中 route_key；空串＝未选出（降级）
 * @param degraded 是否降级为 CONCAT
 * @param withLlm  是否有投票调用（只有 1 路成功时为 false）
 * @return 步骤数组
 */
function voteSpans(winner = 'b', degraded = false, withLlm = true): ObsSpan[] {
  const spans: ObsSpan[] = [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'judge',
      agentName: '客服总控',
      agentId: 100,
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:01',
      latencyMs: 1000,
      attrsJson: JSON.stringify({
        'ma.template': 'ROUTER_PARALLEL',
        'ma.aggregate': 'VOTE',
        'ma.vote.winner': winner,
        'ma.vote.degraded': degraded ? 'true' : 'false',
        'ma.vote.ok': '2',
        'ma.vote.fail': '0'
      }),
      routeDecision: decision([
        { routeKey: 'after_sale', subQuery: '我要退货' },
        { routeKey: 'logistics', subQuery: '查物流' }
      ])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      agentId: 201,
      agentName: '售后专家',
      inputText: '我要退货',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:03',
      latencyMs: 3000
    }),
    span({
      id: 3,
      seq: 3,
      parentId: 1,
      agentId: 202,
      agentName: '物流专家',
      inputText: '查物流',
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:04',
      latencyMs: 4000
    })
  ]
  if (withLlm) {
    spans.push(span({
      id: 4,
      seq: 4,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      agentId: 100,
      agentName: '客服总控',
      // 投票调用晚于全部子结束：只能归到汇总行
      startTime: '2026-11-04 10:00:04',
      endTime: '2026-11-04 10:00:05'
    }))
  }
  return spans
}

test('投票选优：winner 标在对应子行，汇总行写成「投票（VOTE · winner）」', () => {
  const model = buildOrchestrationLane(voteSpans('logistics'))

  assert.equal(model.aggregate, 'VOTE')
  assert.equal(model.voteWinner, 'logistics')
  assert.equal(model.voteDegraded, false)
  // 子按 sub_query 对齐各自 route_key，跨子问题也能标星
  assert.equal(model.rows[1].subtitle, 'after_sale · REACT')
  assert.ok(model.rows[2].subtitle.includes('★ 选中'), model.rows[2].subtitle)
  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.equal(aggregateRow?.title, '投票（VOTE · logistics）')
  assert.equal(aggregateRow?.spans.length, 1, '投票 LLM 归到汇总行')
  assert.ok(model.aggregateNote.includes('投票选优只取 1 份终答'))
  const texts = model.events.map((event) => event.text)
  assert.ok(texts.some((text) => text.includes('投票：winner=logistics') && text.includes('不做二次改写')))
  assert.ok(!texts.some((text) => text.includes('结果合成 CONCAT')))
})

test('投票选优：降级为拼接时给 warn 事件并说明原因', () => {
  const model = buildOrchestrationLane(voteSpans('', true))
  assert.equal(model.voteDegraded, true)
  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.ok(aggregateRow?.title.includes('降级拼接'), aggregateRow?.title)
  const degraded = model.events.find((event) => event.text.includes('投票降级'))
  assert.equal(degraded?.tone, 'warn')
  assert.ok(model.aggregateNote.includes('已降级为拼接'))
})

test('投票选优：仅 1 路成功时没有投票调用，事件说明跳过投票', () => {
  const model = buildOrchestrationLane(voteSpans('after_sale', false, false))
  const aggregateRow = model.rows.find((row) => row.kind === 'aggregate')
  assert.equal(aggregateRow?.spans.length, 0, '跳过投票时没有 LLM 调用')
  const texts = model.events.map((event) => event.text)
  assert.ok(texts.some((text) => text.includes('跳过投票') && text.includes('after_sale')))
})

/* ===== 分层：入口直接目标单列，内层 AGENT 收进外壳（不改 span、不改执行语义） ===== */

/**
 * 造「入口 → 方案外壳 → 编排根 → 专家」链路。
 *
 * <p>形状对照现网：外壳挂在入口判路由 LLM 下，编排根挂在外壳下，
 * 专家挂在编排根自己的判路由 LLM 下。外壳与编排根同名（都取方案名）。
 *
 * @param workflowName 第二个目标（工作流外壳）名；不传＝只有方案这一路
 * @return 步骤数组
 */
function nestedSchemeSpans(workflowName = ''): ObsSpan[] {
  const spans: ObsSpan[] = [
    span({
      id: 1,
      seq: 1,
      spanType: 'ROUTE',
      name: 'judge',
      agentName: '智能网关',
      agentId: 100,
      startTime: '2026-11-16 14:45:55',
      endTime: '2026-11-16 14:45:56',
      latencyMs: 1000,
      attrsJson: JSON.stringify({ 'ma.template': 'ENTRY_ROUTER', 'ma.aggregate': 'CONCAT' }),
      // 两问的 sub_query 完全相同：承接规则把「也按工作流跑一次」换成了上一问全文
      routeDecision: decision([
        { routeKey: 'ma_from_agent_001', subQuery: '请综合台整理知识要点并起草报告' },
        { routeKey: 'policy_brief_report', subQuery: '请综合台整理知识要点并起草报告' }
      ])
    }),
    span({
      id: 2,
      seq: 2,
      parentId: 1,
      spanType: 'LLM',
      name: 'router-model',
      agentId: 100,
      agentName: '智能网关',
      startTime: '2026-11-16 14:45:55',
      endTime: '2026-11-16 14:45:56',
      latencyMs: 1000
    }),
    // 方案外壳：父＝入口判路由 LLM，带 obs.role=scheme
    span({
      id: 3,
      seq: 3,
      parentId: 2,
      agentId: null,
      agentName: '企业综合台',
      inputText: '请综合台整理知识要点并起草报告',
      startTime: '2026-11-16 14:45:56',
      endTime: '2026-11-16 14:46:40',
      latencyMs: 44000,
      attrsJson: JSON.stringify({ 'obs.role': 'scheme' })
    }),
    // 方案编排根：父＝外壳，带 ma.root=SCHEME，input 为空
    span({
      id: 4,
      seq: 4,
      parentId: 3,
      agentId: null,
      agentName: '企业综合台',
      inputText: null,
      startTime: '2026-11-16 14:45:56',
      endTime: '2026-11-16 14:46:40',
      latencyMs: 44000,
      attrsJson: JSON.stringify({ 'ma.root': 'SCHEME', 'ma.template': 'ROUTER_PARALLEL' })
    }),
    // 内层判路由 LLM
    span({
      id: 5,
      seq: 5,
      parentId: 4,
      spanType: 'LLM',
      name: 'router-model',
      agentName: '企业综合台',
      startTime: '2026-11-16 14:45:56',
      endTime: '2026-11-16 14:45:57',
      latencyMs: 1000
    }),
    // 内层专家：父＝内层判路由 LLM，往上第一个 AGENT 祖先就是编排根
    span({
      id: 6,
      seq: 6,
      parentId: 5,
      agentId: 201,
      agentName: '制度知识专家',
      inputText: '整理制度要点',
      startTime: '2026-11-16 14:45:57',
      endTime: '2026-11-16 14:46:20',
      latencyMs: 23000
    }),
    span({
      id: 7,
      seq: 7,
      parentId: 5,
      agentId: 202,
      agentName: '文稿起草',
      inputText: '起草报告',
      startTime: '2026-11-16 14:45:57',
      endTime: '2026-11-16 14:46:35',
      latencyMs: 38000
    })
  ]
  if (workflowName) {
    spans.push(span({
      id: 8,
      seq: 8,
      parentId: 2,
      agentId: null,
      agentName: workflowName,
      inputText: '请综合台整理知识要点并起草报告',
      // 工作流比综合台早收口：这正是页面上「同时开跑却更短」的真实原因
      startTime: '2026-11-16 14:45:56',
      endTime: '2026-11-16 14:46:20',
      latencyMs: 24000,
      attrsJson: JSON.stringify({ 'obs.role': 'workflow' })
    }))
  }
  return spans
}

test('分层：执行明细读 laneRows，入口直接目标单列，内层 AGENT 归到外壳的 nested', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans('制度要点与报告'))

  // 子行只有两个入口目标（方案外壳 + 工作流外壳），不再把内层专家平铺成同级
  const childRows = model.laneRows.filter((row) => row.kind === 'child')
  assert.equal(childRows.length, 2)
  assert.equal(model.childCount, 2, 'childCount 只数入口直接目标')

  // 方案外壳下收着编排根，编排根下再收着两位专家
  const schemeRow = childRows[0]
  assert.equal(schemeRow.title, '企业综合台')
  assert.equal(schemeRow.depth, 0)
  assert.equal(schemeRow.nested.length, 1)
  const rootRow = schemeRow.nested[0]
  assert.equal(rootRow.depth, 1)
  assert.equal(rootRow.title, '企业综合台 · 编排', '编排根补「· 编排」，同名两行可区分')
  assert.deepEqual(rootRow.nested.map((row) => row.title), ['制度知识专家', '文稿起草'])
  assert.equal(rootRow.nested[0].depth, 2)

  // 工作流外壳没有内层 Agent（节点步在另一条 WORKFLOW trace）
  const workflowRow = childRows[1]
  assert.equal(workflowRow.nested.length, 0)
})

test('扁平口径不变：rows 仍含全部 AGENT，行文案与分层前一致', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans())

  // rows 是扁平全集：外壳 + 编排根 + 两位专家 = 4 个 child 行
  const flatChildRows = model.rows.filter((row) => row.kind === 'child')
  assert.equal(flatChildRows.length, 4)
  // 扁平视图里每一行都是 depth=0、nested 为空，行为与分层前一致
  assert.ok(flatChildRows.every((row) => row.depth === 0))
  assert.ok(flatChildRows.every((row) => row.nested.length === 0))
  // 行顺序仍是执行序：外壳 → 编排根 → 两位专家
  assert.deepEqual(
    flatChildRows.map((row) => row.spans[0].id),
    [3, 4, 6, 7]
  )
  // 扁平视图不改标题：同名两行靠右栏树的层级区分，问答框内文案与分层前逐字一致
  assert.equal(flatChildRows[1].title, '企业综合台')
})

test('扁平与分层是两个视图：同名条只在执行明细补「· 编排」，不动问答框那份', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans())
  const flatRoot = model.rows.filter((row) => row.kind === 'child')[1]
  const laneRoot = model.laneRows.find((row) => row.kind === 'child')?.nested[0]

  assert.equal(flatRoot.title, '企业综合台')
  assert.equal(laneRoot?.title, '企业综合台 · 编排')
  // 两个视图指向同一条 span，只是摆法不同；不得为了展示合并成一条
  assert.equal(flatRoot.spans[0].id, laneRoot?.spans[0].id)
})

test('分层：事件里内层 Agent 标「内层」，不与入口目标混成同级', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans())
  const texts = model.events.map((event) => event.text)

  assert.ok(texts.some((text) => text.startsWith('子「企业综合台」开始')))
  assert.ok(texts.some((text) => text.includes('内层「企业综合台 · 编排」开始')))
  assert.ok(texts.some((text) => text.includes('内层「制度知识专家」开始')))
  // 内层专家不是入口派发对象：不得出现「子「制度知识专家」」
  assert.ok(!texts.some((text) => text.startsWith('子「制度知识专家」')))
})

test('keyOf：两问 sub_query 相同也不串键，按执行序下标对齐', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans('制度要点与报告'))
  const childRows = model.laneRows.filter((row) => row.kind === 'child')

  // 外壳 1 → 第一个 route_key；外壳 2 → 第二个。以前两张都会被标成后写的 policy_brief_report
  assert.ok(childRows[0].subtitle.includes('ma_from_agent_001'), childRows[0].subtitle)
  assert.ok(childRows[1].subtitle.includes('policy_brief_report'), childRows[1].subtitle)
  assert.ok(!childRows[0].subtitle.includes('policy_brief_report'), '外壳不得被标成别的目标')
})

test('同名条区分：方案外壳写「方案」、工作流外壳写「工作流」、编排根写「编排」', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans('制度要点与报告'))
  const childRows = model.laneRows.filter((row) => row.kind === 'child')

  // 方案外壳：obs.role=scheme → 副标题带「方案」
  assert.ok(childRows[0].subtitle.includes('方案'), childRows[0].subtitle)
  // 工作流外壳：obs.role=workflow → 副标题带「工作流」，与右栏树的标签同口径
  assert.ok(childRows[1].subtitle.includes('工作流'), childRows[1].subtitle)
  // 编排根：ma.root=SCHEME → 显示名带「· 编排」，副标题写「编排」，不再只写 REACT
  const rootRow = childRows[0].nested[0]
  assert.equal(rootRow.subtitle, '编排')
  assert.ok(!rootRow.subtitle.includes('REACT'), '编排根是容器步，不标名义运行模式')
})

test('分层不合并：外壳与编排根是两条独立 span，行 key 各不相同', () => {
  const model = buildOrchestrationLane(nestedSchemeSpans())
  const schemeRow = model.laneRows.find((row) => row.kind === 'child')
  assert.ok(schemeRow)
  const rootRow = schemeRow?.nested[0]
  assert.ok(rootRow)
  assert.notEqual(schemeRow?.key, rootRow?.key)
  assert.equal(schemeRow?.spans[0].id, 3)
  assert.equal(rootRow?.spans[0].id, 4)
})

test('分层：单层多专家（无外壳）保持原样，不因分层把专家收进某个父里', () => {
  const model = buildOrchestrationLane(multiIntentSpans())

  // 两位专家往上都没有 AGENT 祖先：仍各自单列一行，nested 为空
  const childRows = model.laneRows.filter((row) => row.kind === 'child')
  assert.equal(childRows.length, 2)
  assert.ok(childRows.every((row) => row.nested.length === 0))
  assert.ok(childRows.every((row) => row.depth === 0))
  assert.equal(model.childCount, 2)
  // 扁平口径与分层口径一致（没有内层时两者等价）
  assert.equal(model.rows.filter((row) => row.kind === 'child').length, 2)
})


