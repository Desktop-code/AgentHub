/**
 * 调用追踪入口 query 解析测试（node:test，无浏览器）。所属域：前端 obs。
 *
 * 覆盖验收点：
 * 1) 带 workflowId 的旧链接缺省进工作流，无 workflowId 进 Agent；
 * 2) 显式 type 优先，type=agent 不被 workflowId 拉回工作流；
 * 3) 非法 workflowId 按无过滤处理；
 * 4) 切换类型时 Agent 丢弃工作流条件，工作流保留 defId / 名称。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/traceQuery.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildTraceQuery,
  firstQueryValue,
  parseWorkflowId,
  resolveTraceType,
  TRACE_TYPE_AGENT,
  TRACE_TYPE_WORKFLOW
} from '../src/views/obs/trace/traceQuery.ts'

/** query 值统一去壳：数组取首个、数字转字符串 */
test('firstQueryValue unwraps arrays and numbers', () => {
  assert.equal(firstQueryValue('12'), '12')
  assert.equal(firstQueryValue(['7', '8']), '7')
  assert.equal(firstQueryValue([[5]]), '5')
  assert.equal(firstQueryValue(9), '9')
  assert.equal(firstQueryValue(null), '')
  assert.equal(firstQueryValue(undefined), '')
  assert.equal(firstQueryValue({}), '')
})

/** workflowId 只接受正整数；脏值一律当无过滤 */
test('parseWorkflowId rejects dirty values', () => {
  assert.equal(parseWorkflowId('12'), 12)
  assert.equal(parseWorkflowId(' 12.9 '), 12)
  assert.equal(parseWorkflowId(['7']), 7)
  for (const raw of ['', '   ', 'abc', '0', '-3', 'NaN', 'Infinity', null, undefined]) {
    assert.equal(parseWorkflowId(raw), null)
  }
})

/** 缺省规则：有 workflowId → 工作流，否则 Agent */
test('resolveTraceType defaults by workflowId presence', () => {
  assert.equal(resolveTraceType(undefined, null), TRACE_TYPE_AGENT)
  assert.equal(resolveTraceType('', null), TRACE_TYPE_AGENT)
  assert.equal(resolveTraceType(undefined, 3), TRACE_TYPE_WORKFLOW)
})

/** 显式 type 优先：旧链接带 workflowId 但 type=agent 时仍走 Agent 三栏 */
test('explicit type wins over workflowId', () => {
  assert.equal(resolveTraceType('agent', 3), TRACE_TYPE_AGENT)
  assert.equal(resolveTraceType('WORKFLOW', null), TRACE_TYPE_WORKFLOW)
  assert.equal(resolveTraceType('workflow', 3), TRACE_TYPE_WORKFLOW)
  assert.equal(resolveTraceType('unknown', 3), TRACE_TYPE_WORKFLOW)
  assert.equal(resolveTraceType('unknown', null), TRACE_TYPE_AGENT)
})

/** 切换类型：Agent 清掉工作流条件；工作流保留 defId 与名称 */
test('buildTraceQuery keeps compatible params only', () => {
  assert.deepEqual(
    buildTraceQuery({ type: TRACE_TYPE_AGENT, workflowId: 3, workflowName: '报销流' }),
    { type: 'agent' }
  )
  assert.deepEqual(
    buildTraceQuery({ type: TRACE_TYPE_WORKFLOW, workflowId: 3, workflowName: '报销流' }),
    { type: 'workflow', workflowId: '3', workflowName: '报销流' }
  )
  // 无 defId 进入工作流：只写 type，由页面提示选择或从列表进入
  assert.deepEqual(buildTraceQuery({ type: TRACE_TYPE_WORKFLOW }), { type: 'workflow' })
  // 空名称不写脏参数
  assert.deepEqual(
    buildTraceQuery({ type: TRACE_TYPE_WORKFLOW, workflowId: 3, workflowName: '   ' }),
    { type: 'workflow', workflowId: '3' }
  )
  // 非正 id 视为无过滤
  assert.deepEqual(
    buildTraceQuery({ type: TRACE_TYPE_WORKFLOW, workflowId: 0 }),
    { type: 'workflow' }
  )
})
