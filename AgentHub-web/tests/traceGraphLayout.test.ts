/**
 * Trace 拓扑语义并组窄测（node:test，无浏览器）。所属域：前端 obs。
 *
 * 覆盖验收点：
 * 1) FIXED_SEQUENCE：多 AGENT 按执行序串链，不扇成并排；
 * 2) ROUTER_PARALLEL / FIXED_PARALLEL / COMPETE：多 AGENT 允许并排（唯一并行来源）；
 * 3) 模板缺失：多 AGENT 默认串行，不靠墙钟猜；
 * 4) 同一 LLM 下多 TOOL 一律按 seq 串行（用户 2026-11-04 拍板取消短并）；
 * 5) AGENT 下 LLM 与 TOOL 混合时永远串行；
 * 6) 汇总类（晚于全部 AGENT 结束）排到 AGENT 之后，并带 aggregate 标记。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/traceGraphLayout.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ancestorIdsOf,
  buildTraceTree,
  groupSiblingSpans,
  resolveTraceLayoutContext,
  resolveTraceTemplate,
  treeBlocksOf,
  type TraceLayoutContext
} from '../src/utils/traceGraphLayout.ts'
import type { ObsSpan } from '../src/types/obs.ts'

/** 造一个步骤；只填布局关心的字段 */
function span(patch: Partial<ObsSpan> = {}): ObsSpan {
  return {
    id: 1,
    parentId: null,
    stepNo: '1',
    seq: 1,
    spanType: 'AGENT',
    name: 'agent',
    agentId: 201,
    agentName: '专家',
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

/** 造 ROUTE 根，attrs 里写模板 */
function route(template: string | null, id = 1): ObsSpan {
  return span({
    id,
    seq: 1,
    stepNo: '1',
    spanType: 'ROUTE',
    name: template ? template.toLowerCase() : 'judge',
    parentId: null,
    attrsJson: template ? JSON.stringify({ 'ma.template': template }) : null
  })
}

/** 由模板直接得到上下文，省掉造整条 trace */
function ctxOf(template: string | null): TraceLayoutContext {
  return resolveTraceLayoutContext(template ? [route(template)] : [])
}

/** 多个子 AGENT：同父，执行号递增，时间窗故意全部重叠（旧算法会误判并行） */
function agentSiblings(count: number, parentId: number): ObsSpan[] {
  return Array.from({ length: count }, (_, i) =>
    span({
      id: 10 + i,
      parentId,
      stepNo: `2.${i + 1}`,
      seq: 10 + i,
      spanType: 'AGENT',
      name: `子${i + 1}`,
      startTime: '2026-11-04 10:00:00',
      endTime: '2026-11-04 10:00:05'
    })
  )
}

test('FIXED_SEQUENCE：多 AGENT 串链，时间窗重叠也不并', () => {
  const ctx = ctxOf('FIXED_SEQUENCE')
  assert.equal(ctx.agentParallel, false)

  const items = groupSiblingSpans(agentSiblings(3, 1), route('FIXED_SEQUENCE'), ctx)
  assert.equal(items.length, 3)
  assert.ok(items.every((item) => item.kind === 'single'))
  // 串行顺序必须是执行序（step_no 自然序）
  assert.deepEqual(
    items.map((item) => (item.kind === 'single' ? item.span.stepNo : '')),
    ['2.1', '2.2', '2.3']
  )
})

test('并行模板：多 AGENT 收成一个并行组', () => {
  for (const template of ['ROUTER_PARALLEL', 'FIXED_PARALLEL', 'COMPETE']) {
    const ctx = ctxOf(template)
    assert.equal(ctx.agentParallel, true)

    const items = groupSiblingSpans(agentSiblings(3, 1), route(template), ctx)
    assert.equal(items.length, 1)
    const only = items[0]
    assert.equal(only.kind, 'parallel')
    if (only.kind === 'parallel') {
      assert.deepEqual(only.spans.map((item) => item.id), [10, 11, 12])
    }
  }
})

test('竞争选优：ROUTE 名称 compete 且 attrs 缺失时，仍按并行组画（不靠墙钟）', () => {
  // 现网 COMPETE 的 ROUTE span 可能没写 attrs_json，只剩名称 compete；
  // 名称兜底必须能认出并行模板，否则四个候选会被画成串行。
  const routeSpan = span({ id: 1, seq: 1, stepNo: '1', spanType: 'ROUTE', name: 'compete', attrsJson: null })
  const ctx = resolveTraceLayoutContext([routeSpan])
  assert.equal(resolveTraceTemplate([routeSpan]), 'COMPETE')
  assert.equal(ctx.agentParallel, true)

  const items = groupSiblingSpans(agentSiblings(4, 1), routeSpan, ctx)
  assert.equal(items.length, 1)
  assert.equal(items[0].kind, 'parallel')
  if (items[0].kind === 'parallel') {
    assert.equal(items[0].spans.length, 4)
  }
})

test('模板缺失：多 AGENT 默认串行', () => {
  const ctx = ctxOf(null)
  assert.equal(ctx.agentParallel, false)

  // 没有 ROUTE / attrs：ctx.template 为 null
  const items = groupSiblingSpans(agentSiblings(2, 1), route(null), ctx)
  assert.equal(items.length, 2)
  assert.ok(items.every((item) => item.kind === 'single'))
})

test('同 LLM 多 TOOL：一律按 seq 串行（取消同轮短并）', () => {
  const ctx = ctxOf('ROUTER_PARALLEL')

  // 同一 LLM 下 3 个工具：全部单步，按 step_no 自然序横串
  const llm = span({ id: 100, stepNo: '3', seq: 3, spanType: 'LLM', parentId: 10 })
  const tools = [101, 102, 103].map((id, i) =>
    span({
      id,
      parentId: llm.id,
      stepNo: `3.${i + 1}`,
      seq: 10 + i,
      spanType: 'TOOL',
      name: `tool-${i + 1}`,
      startTime: '2026-11-04 10:00:01',
      endTime: '2026-11-04 10:00:02'
    })
  )
  // 时间窗完全相同也要串：布局只看 seq / step_no，不看墙钟
  const sameTurn = groupSiblingSpans(tools, llm, ctx)
  assert.equal(sameTurn.length, 3)
  assert.ok(sameTurn.every((item) => item.kind === 'single'))
  assert.deepEqual(
    sameTurn.map((item) => (item.kind === 'single' ? item.span.id : -1)),
    [101, 102, 103]
  )

  // 挂 AGENT 的混合兄弟：[LLM1, TOOL1, TOOL2, LLM2]：全部按执行序串行，无并行块
  const llm1 = span({ id: 200, stepNo: '4', seq: 4, spanType: 'LLM', parentId: 10 })
  const llm2 = span({ id: 203, stepNo: '5', seq: 7, spanType: 'LLM', parentId: 10 })
  const tool1 = span({ id: 201, stepNo: '4.1', seq: 5, spanType: 'TOOL', parentId: 10 })
  const tool2 = span({ id: 202, stepNo: '4.2', seq: 6, spanType: 'TOOL', parentId: 10 })
  const mixed = groupSiblingSpans([llm1, tool1, tool2, llm2], span({ id: 10 }), ctx)
  assert.deepEqual(
    mixed.map((item) => (item.kind === 'single' ? item.span.id : item.spans.map((s) => s.id))),
    [200, 201, 202, 203]
  )
})

test('AGENT 下 LLM 与 TOOL 混合：不扇成两翼', () => {
  const ctx = ctxOf('ROUTER_PARALLEL')
  const agent = span({ id: 10, stepNo: '2', seq: 2, spanType: 'AGENT' })
  const llm = span({ id: 20, stepNo: '2.1', seq: 3, spanType: 'LLM', parentId: agent.id })
  const tool = span({ id: 21, stepNo: '2.2', seq: 4, spanType: 'TOOL', parentId: agent.id })
  const items = groupSiblingSpans([tool, llm], agent, ctx)
  // 顺序按执行序，且两项都是单步，绝不出现 LLM∥TOOL
  assert.deepEqual(
    items.map((item) => (item.kind === 'single' ? item.span.id : item.spans.map((s) => s.id))),
    [20, 21]
  )
})

test('同挂 AGENT 的纯 TOOL：没有前序 LLM 时按串行，不擅自并', () => {
  const ctx = ctxOf('ROUTER_PARALLEL')
  const agent = span({ id: 10, stepNo: '2', seq: 2, spanType: 'AGENT' })
  const tools = [11, 12].map((id, i) =>
    span({
      id,
      parentId: agent.id,
      stepNo: `2.${i + 1}`,
      seq: 3 + i,
      spanType: 'TOOL',
      name: `tool-${i + 1}`
    })
  )
  const items = groupSiblingSpans(tools, agent, ctx)
  assert.equal(items.length, 2)
  assert.ok(items.every((item) => item.kind === 'single'))
})

test('汇总 LLM 晚于全部 AGENT：排到 AGENT 之后', () => {
  const ctx = ctxOf('FIXED_SEQUENCE')
  const parent = span({ id: 1, spanType: 'ROUTE', seq: 1, stepNo: '1' })
  // 故意让汇总 LLM 的执行号比 AGENT 小，只有时间能说明它在后
  const aggregate = span({
    id: 30,
    parentId: parent.id,
    stepNo: '2',
    seq: 30,
    spanType: 'LLM',
    startTime: '2026-11-04 10:00:06',
    endTime: '2026-11-04 10:00:07',
    latencyMs: 1000
  })
  const agents = agentSiblings(2, parent.id).map((item) => ({
    ...item,
    stepNo: '1.1',
    endTime: '2026-11-04 10:00:05'
  }))
  const items = groupSiblingSpans([aggregate, ...agents], parent, ctx)
  const last = items[items.length - 1]
  assert.equal(last.kind, 'single')
  if (last.kind === 'single') {
    assert.equal(last.span.spanType, 'LLM')
    // 必须带 aggregate 标记：拓扑据此画「汇合后单独一步」，不能接某个 AGENT 的末尾工具
    assert.equal(last.aggregate, true)
  }
  // 普通 AGENT 步不标 aggregate
  assert.ok(
    items.slice(0, -1).every((item) => item.kind === 'single' && item.aggregate !== true)
  )
})

test('并行多 AGENT + 汇总 LLM：AGENT 仍并排，汇总标记 aggregate 且只一个', () => {
  const ctx = ctxOf('ROUTER_PARALLEL')
  const parent = span({ id: 1, spanType: 'ROUTE', seq: 1, stepNo: '1' })
  const agents = agentSiblings(2, parent.id)
  const summary = span({
    id: 40,
    parentId: parent.id,
    stepNo: '9',
    seq: 40,
    spanType: 'LLM',
    startTime: '2026-11-04 10:00:06',
    endTime: '2026-11-04 10:00:07'
  })
  const items = groupSiblingSpans([summary, ...agents], parent, ctx)
  const last = items[items.length - 1]
  assert.equal(last.kind, 'single')
  if (last.kind === 'single') {
    assert.equal(last.span.id, summary.id)
    assert.equal(last.aggregate, true)
  }
  // 关键回归：同层夹着汇总 LLM 时，连续多 AGENT 仍要收成一个并行块，
  // 否则并行退化成串行、汇总只剩一条链尾线（用户手点到「只接部分支」）
  assert.equal(items.length, 2)
  assert.equal(items[0].kind, 'parallel')
  if (items[0].kind === 'parallel') {
    assert.deepEqual(items[0].spans.map((item) => item.id), [10, 11])
  }
})

test('串行模板下同层 AGENT + 汇总 LLM：AGENT 不并排，汇总仍标 aggregate', () => {
  const ctx = ctxOf('FIXED_SEQUENCE')
  const parent = span({ id: 1, spanType: 'ROUTE', seq: 1, stepNo: '1' })
  const agents = agentSiblings(3, parent.id)
  const summary = span({
    id: 41,
    parentId: parent.id,
    stepNo: '9',
    seq: 41,
    spanType: 'LLM',
    startTime: '2026-11-04 10:00:06',
    endTime: '2026-11-04 10:00:07'
  })
  const items = groupSiblingSpans([summary, ...agents], parent, ctx)
  // 串行模板：3 个 AGENT 各一块 + 汇总一块，全部 single（拓扑据此画单线接入）
  assert.equal(items.length, 4)
  assert.ok(items.every((item) => item.kind === 'single'))
  const last = items[items.length - 1]
  assert.ok(last.kind === 'single' && last.aggregate === true)
})

test('模板读取：优先 attrs，缺失按 ROUTE 名称兜底', () => {
  assert.equal(resolveTraceTemplate([route('FIXED_SEQUENCE')]), 'FIXED_SEQUENCE')
  // 旧 trace 无 attrs：名称含 fixed-sequence
  const legacy = span({ id: 1, spanType: 'ROUTE', name: 'fixed-sequence', attrsJson: null })
  assert.equal(resolveTraceTemplate([legacy]), 'FIXED_SEQUENCE')
  // 名称 judge：判路由并行基线
  const judge = span({ id: 1, spanType: 'ROUTE', name: 'judge', attrsJson: null })
  assert.equal(resolveTraceTemplate([judge]), 'ROUTER_PARALLEL')
  // 认不出：null，调用方按串行处理
  const unknown = span({ id: 1, spanType: 'ROUTE', name: '某步骤', attrsJson: null })
  assert.equal(resolveTraceTemplate([unknown]), null)
})

/* ============ 列表区树索引（TraceListView 递归渲染的输入） ============ */

test('树索引：按 parentId 归子，父缺失的步骤按根处理', () => {
  const root = route('ROUTER_PARALLEL')
  const child = span({ id: 10, parentId: root.id, stepNo: '2', seq: 2 })
  // 脏数据：parentId 指向不存在的步骤，不能让它整条消失
  const orphan = span({ id: 99, parentId: 888, stepNo: '3', seq: 3 })

  const tree = buildTraceTree([child, root, orphan])
  assert.deepEqual(
    tree.roots.map((item) => item.id),
    [root.id, 99]
  )
  assert.deepEqual(
    tree.childMap.get(root.id)?.map((item) => item.id),
    [10]
  )
  assert.equal(tree.childMap.has(99), false)
  // 模板上下文一并带出：列表与拓扑必须同口径
  assert.equal(tree.ctx.template, 'ROUTER_PARALLEL')
  assert.equal(tree.ctx.agentParallel, true)
})

test('树索引：根层取块只出 ROUTE 单步，并行包裹在 ROUTE 的 Body 里', () => {
  const root = route('ROUTER_PARALLEL')
  const agents = agentSiblings(2, root.id)
  const summary = span({
    id: 40,
    parentId: root.id,
    stepNo: '3',
    seq: 40,
    spanType: 'LLM',
    startTime: '2026-11-04 10:00:06',
    endTime: '2026-11-04 10:00:07'
  })

  const tree = buildTraceTree([root, ...agents, summary])
  const rootBlocks = treeBlocksOf(null, tree)
  assert.equal(rootBlocks.length, 1)
  assert.equal(rootBlocks[0].kind, 'single')

  // 子层：并行块在前、汇总单步在后 → 对应「ROUTE 在并行块前、汇总在块后」
  const childBlocks = treeBlocksOf(root, tree)
  assert.equal(childBlocks.length, 2)
  assert.equal(childBlocks[0].kind, 'parallel')
  if (childBlocks[0].kind === 'parallel') {
    assert.deepEqual(childBlocks[0].spans.map((item) => item.id), [10, 11])
    assert.equal(childBlocks[0].wallMs, 5000)
  }
  assert.equal(childBlocks[1].kind, 'single')
  if (childBlocks[1].kind === 'single') {
    assert.equal(childBlocks[1].span.id, 40)
  }
})

test('树索引：串行模板下子层全部单步（不出现并行容器）', () => {
  const root = route('FIXED_SEQUENCE')
  const agents = agentSiblings(3, root.id)
  const tree = buildTraceTree([root, ...agents])
  const childBlocks = treeBlocksOf(root, tree)
  assert.equal(childBlocks.length, 3)
  assert.ok(childBlocks.every((item) => item.kind === 'single'))
  // 叶子节点没有子块
  assert.deepEqual(treeBlocksOf(agents[0], tree), [])
})

/* ============ 拓扑点选 → 列表定位所需的祖先链 ============ */

test('祖先链：定位子 LLM 时要把 ROUTE 与 AGENT 都展开', () => {
  const root = route('ROUTER_PARALLEL')
  const agent = span({ id: 10, parentId: root.id, stepNo: '2', seq: 2 })
  const llm = span({ id: 20, parentId: agent.id, stepNo: '2.1', seq: 3, spanType: 'LLM' })
  const tool = span({ id: 21, parentId: llm.id, stepNo: '2.1.1', seq: 4, spanType: 'TOOL' })

  const tree = buildTraceTree([root, agent, llm, tool])
  // 不含自身：目标卡自己会展开；
  assert.deepEqual([...ancestorIdsOf(tool.id, tree)].sort((a, b) => a - b), [root.id, 10, 20])
  assert.deepEqual([...ancestorIdsOf(agent.id, tree)], [root.id])
  // 根节点没有祖先
  assert.equal(ancestorIdsOf(root.id, tree).size, 0)
  // 不存在的 id 不能抛，也不能返回脏数据
  assert.equal(ancestorIdsOf(9999, tree).size, 0)
})

test('祖先链：脏数据成环时不死循环', () => {
  // 互相指向对方：旧 trace / 手工改库可能出现，定位功能不能因此卡死浏览器
  const a = span({ id: 1, parentId: 2, stepNo: '1', seq: 1 })
  const b = span({ id: 2, parentId: 1, stepNo: '2', seq: 2 })
  const tree = buildTraceTree([a, b])
  // 能正常返回即达标（visited 集合兜住环）；这里最多只会收到环上的两个 id
  const chain = ancestorIdsOf(1, tree)
  assert.ok(chain.size <= 2)
})
