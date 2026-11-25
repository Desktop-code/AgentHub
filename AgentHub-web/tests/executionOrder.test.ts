/**
 * 执行序排序窄测（node:test，无浏览器）。所属域：前端 obs + workflow。
 *
 * 覆盖验收点：
 * 1) step_no 自然序：3 < 3.1 < 3.1.1 < 3.2，且 2 < 10（不能走字典序）；
 * 2) trace 步骤优先 step_no，同号再 seq，最后 id；
 * 3) 线性 START → AGENT → END：即使图数组把 END 排在 AGENT 前（主键更小），也按开工 / 结束时间排成执行序；
 * 4) 未开工（start_time 为空）排最后。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/executionOrder.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  compareNodeRunExecOrder,
  compareSpanExecOrder,
  compareStepNoNatural,
  type ExecutionOrderRow
} from '../src/utils/executionOrder.ts'

/**
 * 造一个 trace 步骤排序键；只填排序关心的字段。
 */
function spanKey(stepNo: string | null, seq: number, id: number) {
  return { stepNo, seq, id }
}

/**
 * 造一个工作流节点运行行；只填排序关心的字段。
 */
function nodeRun(id: number, startTime: string | null, endTime: string | null): ExecutionOrderRow {
  return { id, startTime, endTime }
}

test('step_no 自然序：按点分层级逐段比较数字', () => {
  const ordered = ['3.2', '3.1.1', '10', '3', '2', '3.1'].sort(compareStepNoNatural)
  assert.deepEqual(ordered, ['2', '3', '3.1', '3.1.1', '3.2', '10'])
})

test('trace 执行序：step_no 优先，同号再 seq', () => {
  // seq 与 step_no 故意冲突：step_no 小的必须排前
  const rows = [spanKey('2', 10, 1), spanKey('1', 20, 2)].sort(compareSpanExecOrder)
  assert.deepEqual(rows.map((row) => row.stepNo), ['1', '2'])

  // 同 step_no：seq 小的排前
  const sameStep = [spanKey('3.1', 9, 5), spanKey('3.1', 4, 6)].sort(compareSpanExecOrder)
  assert.deepEqual(sameStep.map((row) => row.seq), [4, 9])

  // step_no 缺失（历史数据）：退回 seq
  const legacy = [spanKey(null, 7, 1), spanKey(null, 2, 2)].sort(compareSpanExecOrder)
  assert.deepEqual(legacy.map((row) => row.seq), [2, 7])
})

test('线性 START → AGENT → END：图顺序把 END 排在 AGENT 前也按执行序展示', () => {
  // 主键 = 预建（图数组）顺序：start(1) → end(2) → agent(3)
  // 同秒开工，Agent 先于 END 结束；END 与 START 同秒结束但 START 先开工
  const graphOrder = [
    nodeRun(1, '2026-11-04 16:00:00', '2026-11-04 16:00:00'),
    nodeRun(2, '2026-11-04 16:00:01', '2026-11-04 16:00:01'),
    nodeRun(3, '2026-11-04 16:00:00', '2026-11-04 16:00:01')
  ]
  const ordered = graphOrder.slice().sort(compareNodeRunExecOrder)
  assert.deepEqual(ordered.map((row) => row.id), [1, 3, 2])
})

test('未开工节点排最后，保持相对主键序', () => {
  const rows = [
    nodeRun(1, null, null),
    nodeRun(2, '2026-11-04 16:00:00', '2026-11-04 16:00:01'),
    nodeRun(3, null, null)
  ]
  const ordered = rows.slice().sort(compareNodeRunExecOrder)
  assert.deepEqual(ordered.map((row) => row.id), [2, 1, 3])
})
