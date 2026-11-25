/**
 * 编排关系校验收敛测试（node:test，无浏览器）。所属域：前端 agent。
 *
 * 覆盖验收点：
 * 1) 零候选 / 零启用候选必须报中文 error（M4）；
 * 2) 停用子、ROUTER 子、非 REACT 子必须拦；
 * 3) 直接自环、route_key 重复必须拦；
 * 4) 最大并行小于启用候选数只提醒，不阻断保存；
 * 5) SUMMARIZE 提醒确认 ROUTER 模型绑定。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/orchestrationValidation.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizeChildRunModes,
  normalizeContextPolicy,
  normalizeCriticMaxRounds,
  supportsVoteTemplate,
  validateOrchestration
} from '../src/utils/orchestrationValidation.ts'
import type { AgentRouteRow } from '../src/types/agent.ts'

/**
 * 造一条候选行。
 *
 * @param patch 需要覆盖的字段
 * @return 路由行
 */
function route(patch: Partial<AgentRouteRow> = {}): AgentRouteRow {
  return {
    id: 1,
    parentAgentId: 100,
    childAgentId: 201,
    childName: '售后专家',
    childCode: 'after_sale',
    childRunMode: 'REACT',
    childStatus: 1,
    routeKey: 'after_sale',
    intent: '退换货售后',
    sortOrder: 10,
    status: 1,
    ...patch
  }
}

/** 基础入参：一个启用候选 */
function input(routes: AgentRouteRow[], patch: Record<string, unknown> = {}) {
  return {
    parentAgentId: 100,
    template: 'ROUTER_PARALLEL',
    aggregate: 'CONCAT',
    maxParallelChildren: 3,
    routes,
    ...patch
  }
}

test('零候选：M4 不允许，给出中文 error', () => {
  const result = validateOrchestration(input([]))
  assert.equal(result.ok, false)
  assert.equal(result.errors.length, 1)
  assert.match(result.errors[0], /没有任何候选子 Agent/)
})

test('全部停用：等同零可用子，给出中文 error', () => {
  const result = validateOrchestration(input([route({ status: 0 })]))
  assert.equal(result.ok, false)
  assert.match(result.errors[0], /没有启用的候选子 Agent/)
  assert.equal(result.warnings.length, 1, '停用行同时给一条提醒')
})

test('停用子：启用候选指向停用的子 Agent 必须拦', () => {
  const result = validateOrchestration(input([route({ childStatus: 0 })]))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('子 Agent 已停用')))
})

test('ROUTER 子：禁止 Router 套 Router', () => {
  const result = validateOrchestration(input([route({ childRunMode: 'ROUTER' })]))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('禁止 Router 套 Router')))
})

test('P&E 子但编排未允许：报 error 并提示去策略区勾选', () => {
  const result = validateOrchestration(input([route({ childRunMode: 'PLAN_AND_EXECUTE' })]))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('未允许子使用计划执行模式')))
})

test('P&E 子且编排已允许：通过校验', () => {
  const result = validateOrchestration(
    input([route({ childRunMode: 'PLAN_AND_EXECUTE' })], {
      allowedChildRunModes: ['REACT', 'PLAN_AND_EXECUTE']
    })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
})

test('允许集合含 P&E 但候选仍是 REACT：不受影响', () => {
  const result = validateOrchestration(
    input([route()], { allowedChildRunModes: ['REACT', 'PLAN_AND_EXECUTE'] })
  )
  assert.equal(result.ok, true)
})

test('允许集合里塞 ROUTER：归一化丢弃，ROUTER 子依旧拦', () => {
  const result = validateOrchestration(
    input([route({ childRunMode: 'ROUTER' })], {
      allowedChildRunModes: ['REACT', 'ROUTER']
    })
  )
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('禁止 Router 套 Router')))
})

test('直接自环：子指向父必须拦', () => {
  const result = validateOrchestration(input([route({ childAgentId: 100 })]))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('形成自环')))
})

test('route_key 重复：大小写不同也算冲突', () => {
  const result = validateOrchestration(
    input([
      route({ id: 1, routeKey: 'after_sale' }),
      route({ id: 2, childAgentId: 202, childName: '物流专家', routeKey: 'AFTER_SALE' })
    ])
  )
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('route_key') && msg.includes('重复')))
})

test('最大并行小于启用候选数：只提醒，不阻断', () => {
  const result = validateOrchestration(
    input(
      [
        route({ id: 1, routeKey: 'a' }),
        route({ id: 2, childAgentId: 202, childName: '物流专家', routeKey: 'b' }),
        route({ id: 3, childAgentId: 203, childName: '话术专家', routeKey: 'c' })
      ],
      { maxParallelChildren: 2 }
    )
  )
  assert.equal(result.ok, true)
  assert.ok(result.warnings.some((msg) => msg.includes('超过并行上限')))
})

test('SUMMARIZE：提醒确认 ROUTER 模型绑定', () => {
  const result = validateOrchestration(input([route()], { aggregate: 'SUMMARIZE' }))
  assert.equal(result.ok, true)
  assert.ok(result.warnings.some((msg) => msg.includes('SUMMARIZE')))
})

test('P0 未知模板：必须拦', () => {
  const result = validateOrchestration(input([route()], { template: 'SHARED_SCRATCH' }))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('未知协作模式')))
})

test('固定串行：允许保存，提示前置摘要与不受并行上限限制', () => {
  const result = validateOrchestration(
    input([route({ id: 1, routeKey: 'a' }), route({ id: 2, childAgentId: 202, routeKey: 'b' })], {
      template: 'FIXED_SEQUENCE'
    })
  )
  assert.equal(result.ok, true)
  assert.ok(result.warnings.some((msg) => msg.includes('终答摘要')))
})

test('固定并行：超上限只提醒会少跑几个，不阻断', () => {
  const result = validateOrchestration(
    input(
      [
        route({ id: 1, routeKey: 'a' }),
        route({ id: 2, childAgentId: 202, routeKey: 'b' }),
        route({ id: 3, childAgentId: 203, routeKey: 'c' })
      ],
      { template: 'FIXED_PARALLEL', maxParallelChildren: 2 }
    )
  )
  assert.equal(result.ok, true)
  assert.ok(result.warnings.some((msg) => msg.includes('其余标「已跳过」')))
})

test('非串行模板带 PRIOR_SUMMARY_ONLY：只提醒会按隔离执行', () => {
  const result = validateOrchestration(
    input([route()], { template: 'FIXED_PARALLEL', context: 'PRIOR_SUMMARY_ONLY' })
  )
  assert.equal(result.ok, true)
  assert.ok(result.warnings.some((msg) => msg.includes('只对串行模板')))
})

test('health：三种无评审模板都能通过基础校验', () => {
  for (const template of ['ROUTER_PARALLEL', 'FIXED_SEQUENCE', 'FIXED_PARALLEL']) {
    const result = validateOrchestration(input([route()], { template }))
    assert.equal(result.ok, true, template)
  }
})

/* ===== 路由后串行（ROUTER_THEN_SEQUENCE）：第五值解禁后的口径 ===== */

test('路由后串行：第五值合法，一个启用候选即可保存', () => {
  const result = validateOrchestration(input([route()], { template: 'ROUTER_THEN_SEQUENCE' }))
  assert.equal(result.ok, true, result.errors.join(' / '))
})

test('路由后串行：不套用评审环「恰好 1 执行者」规则', () => {
  const result = validateOrchestration(
    input(
      [
        route({ id: 1, childAgentId: 201, routeKey: 'a' }),
        route({ id: 2, childAgentId: 202, childName: '物流专家', routeKey: 'b' })
      ],
      { template: 'ROUTER_THEN_SEQUENCE' }
    )
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  // 也不该冒出评审环的默认提醒
  assert.ok(!result.warnings.some((msg) => msg.includes('评审专家')))
})

test('路由后串行：默认上下文＝前置摘要，并说明「先判路由再串行」', () => {
  // 未显式给 context：normalizeContextPolicy 默认给 PRIOR_SUMMARY_ONLY
  assert.equal(normalizeContextPolicy(undefined, 'ROUTER_THEN_SEQUENCE'), 'PRIOR_SUMMARY_ONLY')
  assert.equal(normalizeContextPolicy('ISOLATED', 'ROUTER_THEN_SEQUENCE'), 'ISOLATED')
  // 并行 / 评审环仍强制隔离
  assert.equal(normalizeContextPolicy(undefined, 'ROUTER_PARALLEL'), 'ISOLATED')
  assert.equal(normalizeContextPolicy('PRIOR_SUMMARY_ONLY', 'CRITIC_LOOP'), 'ISOLATED')

  const result = validateOrchestration(input([route()], { template: 'ROUTER_THEN_SEQUENCE' }))
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(
    result.warnings.some((msg) => msg.includes('先判路由') && msg.includes('判定顺序') && msg.includes('串行')),
    result.warnings.join(' / ')
  )
})

test('路由后串行：判定条数超上限只跑判定序前 N，其余标已跳过', () => {
  const result = validateOrchestration(
    input(
      [
        route({ id: 1, routeKey: 'a' }),
        route({ id: 2, childAgentId: 202, routeKey: 'b' }),
        route({ id: 3, childAgentId: 203, routeKey: 'c' })
      ],
      { template: 'ROUTER_THEN_SEQUENCE', maxParallelChildren: 2 }
    )
  )
  assert.equal(result.ok, true)
  assert.ok(result.warnings.some((msg) => msg.includes('判定顺序前 2 个') && msg.includes('已跳过')),
    result.warnings.join(' / '))
})

test('健康关系：一个启用 REACT 子通过，环检测按一层结构通过', () => {
  const result = validateOrchestration(input([route()]))
  assert.equal(result.ok, true)
  assert.equal(result.errors.length, 0)
  assert.equal(result.enabledCount, 1)
})

test('候选子未允许被编排调用：保存前必须报 error', () => {
  const result = validateOrchestration(input([route({ childAllowOrchestrated: 0 })]))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('未允许被编排调用')))
})

test('候选子允许被编排（=1）或字段缺省：不产生该错误', () => {
  for (const flag of [1, undefined]) {
    const result = validateOrchestration(input([route({ childAllowOrchestrated: flag })]))
    assert.equal(result.ok, true, String(flag))
    assert.ok(!result.errors.some((msg) => msg.includes('未允许被编排调用')))
  }
})

test('子运行模式归一：缺省 / 空数组回落仅 REACT', () => {
  assert.deepEqual(normalizeChildRunModes(undefined), ['REACT'])
  assert.deepEqual(normalizeChildRunModes(null), ['REACT'])
  assert.deepEqual(normalizeChildRunModes([]), ['REACT'])
})

test('子运行模式归一：P&E 去重且顺序固定在前 REACT 之后', () => {
  assert.deepEqual(
    normalizeChildRunModes(['PLAN_AND_EXECUTE', 'REACT', 'plan_and_execute']),
    ['REACT', 'PLAN_AND_EXECUTE']
  )
})

test('子运行模式归一：ROUTER 与第四值一律丢弃', () => {
  assert.deepEqual(normalizeChildRunModes(['ROUTER', 'CRITIC', 'react']), ['REACT'])
})

test('子运行模式归一：历史脏值逗号串也能拆开', () => {
  assert.deepEqual(normalizeChildRunModes(['REACT,PLAN_AND_EXECUTE']), ['REACT', 'PLAN_AND_EXECUTE'])
})

test('评审环：1 个执行者 + 已选评审专家通过，并给默认单轮提醒', () => {
  const result = validateOrchestration(input([route()], {
    template: 'CRITIC_LOOP',
    criticAgentId: 301,
    criticMaxRounds: 1
  }))
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('执行者出稿后由评审专家判定')))
})

test('评审环：未选评审专家必须拦', () => {
  const result = validateOrchestration(input([route()], { template: 'CRITIC_LOOP' }))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('评审环需要选择评审专家')))
})

test('评审环：执行者候选 2 个必须拦，文案给出当前个数', () => {
  const result = validateOrchestration(input(
    [
      route({ id: 1, childAgentId: 201, routeKey: 'a' }),
      route({ id: 2, childAgentId: 202, childName: '物流专家', routeKey: 'b' })
    ],
    { template: 'CRITIC_LOOP', criticAgentId: 301 }
  ))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('评审环只允许 1 个执行者候选，当前 2 个')))
})

test('评审环：唯一候选就是评审专家 → 报「不能与执行者相同」而不是「0 个」', () => {
  const result = validateOrchestration(input([route({ childAgentId: 301 })], {
    template: 'CRITIC_LOOP',
    criticAgentId: 301
  }))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('评审专家不能与执行者相同')))
})

test('评审环：评审者也在候选表时从执行者计数中排除，仍可保存', () => {
  const result = validateOrchestration(input(
    [
      route({ id: 1, childAgentId: 201, routeKey: 'a' }),
      route({ id: 2, childAgentId: 301, childName: '评审专家', routeKey: 'critic' })
    ],
    { template: 'CRITIC_LOOP', criticAgentId: 301 }
  ))
  assert.equal(result.ok, true, result.errors.join(' / '))
})

test('评审环：轮次超出 1～3 必须拦', () => {
  for (const rounds of [0, 4, 9]) {
    const result = validateOrchestration(input([route()], {
      template: 'CRITIC_LOOP',
      criticAgentId: 301,
      criticMaxRounds: rounds
    }))
    assert.equal(result.ok, false, String(rounds))
    assert.ok(result.errors.some((msg) => msg.includes('最大评审轮次必须在 1～3 之间')), String(rounds))
  }
})

test('评审环：轮次归一缺省/非法回落 1，继续夹到 3', () => {
  assert.equal(normalizeCriticMaxRounds(undefined), 1)
  assert.equal(normalizeCriticMaxRounds(null), 1)
  assert.equal(normalizeCriticMaxRounds(2), 2)
  assert.equal(normalizeCriticMaxRounds(99), 3)
  assert.equal(normalizeCriticMaxRounds(-3), 1)
})

/* ===== 共享草稿区（SHARED_SCRATCH）：MA-P1 刀7 解禁后的口径 ===== */

test('共享草稿区：归一化对六种模板都保留，不被并行模板洗回隔离', () => {
  for (const template of [
    'ROUTER_PARALLEL',
    'FIXED_SEQUENCE',
    'FIXED_PARALLEL',
    'CRITIC_LOOP',
    'ROUTER_THEN_SEQUENCE',
    'COMPETE'
  ]) {
    assert.equal(normalizeContextPolicy('SHARED_SCRATCH', template), 'SHARED_SCRATCH', template)
  }
  // 未显式选时仍按模板默认，不会偷偷开板
  assert.equal(normalizeContextPolicy(undefined, 'ROUTER_PARALLEL'), 'ISOLATED')
  assert.equal(normalizeContextPolicy(undefined, 'FIXED_SEQUENCE'), 'PRIOR_SUMMARY_ONLY')
})

test('共享草稿区：并行模板可保存，并提示工具口径与拒绝同键双写', () => {
  const result = validateOrchestration(
    input([route()], { template: 'ROUTER_PARALLEL', context: 'SHARED_SCRATCH' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('ma_scratch_get') && msg.includes('拒绝')))
})

test('共享草稿区：串行模板可保存，但提醒更推荐仅前置摘要', () => {
  const result = validateOrchestration(
    input([route()], { template: 'FIXED_SEQUENCE', context: 'SHARED_SCRATCH' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('串行模板推荐「仅前置摘要」')))
  // 不再误报「前置摘要只对串行模板生效」
  assert.ok(!result.warnings.some((msg) => msg.includes('只对串行模板')))
})

test('共享草稿区：未知上下文共享只提醒回落，不阻断保存', () => {
  const result = validateOrchestration(
    input([route()], { template: 'ROUTER_PARALLEL', context: 'SCRATCH_TYPO' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('未知上下文共享') && msg.includes('ISOLATED')))
})

/* ===== 竞争选优（COMPETE）：刀4 新增第六值 ===== */

/** 造第二条竞争候选：route_key / agent 都与默认行不同 */
function secondRoute(patch: Partial<AgentRouteRow> = {}): AgentRouteRow {
  return route({
    id: 2,
    childAgentId: 202,
    childName: '物流专家',
    childCode: 'logistics',
    routeKey: 'logistics',
    intent: '物流时效与派送',
    ...patch
  })
}

test('竞争选优：只有 1 个启用候选必须拦，并点明至少 2 个', () => {
  const result = validateOrchestration(input([route()], { template: 'COMPETE' }))
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('竞争选优至少需要 2 个启用候选')))
})

test('竞争选优：2 个启用候选可保存，默认策略提醒评选模型选优', () => {
  const result = validateOrchestration(input([route(), secondRoute()], { template: 'COMPETE' }))
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('评选模型比较各子终答')))
  assert.ok(result.warnings.some((msg) => msg.includes('降级为第一个成功子')))
})

test('竞争选优：停用行不计入 2 个启用候选', () => {
  const result = validateOrchestration(
    input([route(), secondRoute({ status: 0 })], { template: 'COMPETE' })
  )
  assert.equal(result.ok, false)
  assert.ok(result.errors.some((msg) => msg.includes('竞争选优至少需要 2 个启用候选')))
})

test('竞争选优：FIRST_SUCCESS 提醒不调评选模型', () => {
  const result = validateOrchestration(
    input([route(), secondRoute()], { template: 'COMPETE', competeStrategy: 'FIRST_SUCCESS' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('第一个成功子') && msg.includes('不调用评选模型')))
})

test('竞争选优：未知策略只提醒回落，不阻断保存', () => {
  const result = validateOrchestration(
    input([route(), secondRoute()], { template: 'COMPETE', competeStrategy: 'MAJORITY_VOTE' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('未知选优策略') && msg.includes('JUDGE_PICK')))
})

test('竞争选优：超出并行上限提醒只有前 N 个参与竞争', () => {
  const result = validateOrchestration(
    input([route(), secondRoute()], { template: 'COMPETE', maxParallelChildren: 1 })
  )
  // 并行上限 < 2 与「至少 2 个竞争」冲突：运行时会直接报错，前端提示竞争面被缩小
  assert.ok(result.warnings.some((msg) => msg.includes('只有 sort_order 前') && msg.includes('参与竞争')))
})

/* ===== 投票选优（VOTE）：刀5 新增汇总策略（不是新模板） ===== */

test('投票选优：只对路由并行 / 固定并行 / 路由后串行开放', () => {
  for (const template of ['ROUTER_PARALLEL', 'FIXED_PARALLEL', 'ROUTER_THEN_SEQUENCE']) {
    assert.equal(supportsVoteTemplate(template), true, template)
  }
  // COMPETE 的选优由模板自身负责；CRITIC_LOOP 不汇总；FIXED_SEQUENCE 串行主语义不变
  for (const template of ['COMPETE', 'CRITIC_LOOP', 'FIXED_SEQUENCE', 'UNKNOWN']) {
    assert.equal(supportsVoteTemplate(template), false, template)
  }
})

test('投票选优：路由并行选 VOTE 可保存，提醒「1 路成功不投票、失败降级拼接」', () => {
  const result = validateOrchestration(
    input([route(), secondRoute()], { template: 'ROUTER_PARALLEL', aggregate: 'VOTE' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('投票选优')))
  assert.ok(result.warnings.some((msg) => msg.includes('只有 1 路成功')))
  assert.ok(result.warnings.some((msg) => msg.includes('降级为拼接')))
})

test('投票选优：模板不支持时只提醒会按 CONCAT 执行，不阻断保存', () => {
  const result = validateOrchestration(
    input([route(), secondRoute()], { template: 'FIXED_SEQUENCE', aggregate: 'VOTE' })
  )
  assert.equal(result.ok, true, result.errors.join(' / '))
  assert.ok(result.warnings.some((msg) => msg.includes('当前模板保存后会按拼接执行')))
})
