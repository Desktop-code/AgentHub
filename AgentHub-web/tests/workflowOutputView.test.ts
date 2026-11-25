/**
 * 工作流卡片结果区展开规则。所属域：runtime（前端）。
 *
 * 展开只看「所属方案的子块是否都已结束」（调用方把这个事实传成 hold）。
 * 不看整轮是不是还在 streaming，也不看节点快照有没有跑完。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  schemeChildrenSettled,
  shouldShowWorkflowOutput,
  workflowOutputFolded,
  workflowOutputState
} from '../src/components/runtime/workbench/workflowOutputView.ts'

const snapshot = {
  status: 'SUCCESS',
  nodes: [{ status: 'SUCCESS' }]
}

test('没有正文也没有文件时不出现结果区', () => {
  assert.equal(shouldShowWorkflowOutput(snapshot, '', 0, false, true), false)
  assert.equal(workflowOutputState(snapshot, '   ', 0, false, true), 'hidden')
})

test('所属方案还有子块没结束：有正文也只折叠，点开才整屏', () => {
  assert.equal(workflowOutputState(snapshot, '第一段要点', 0, false, true), 'folded')
  assert.equal(shouldShowWorkflowOutput(snapshot, '第一段要点', 0, false, true), false)
  assert.equal(workflowOutputFolded(snapshot, '第一段要点', 0, true), true)
  assert.equal(workflowOutputState(snapshot, '第一段要点', 0, true, true), 'open')
  assert.equal(workflowOutputState(snapshot, '', 1, false, true), 'folded')
})

test('所属方案的子块都已结束：有正文或文件就展开，不看节点状态', () => {
  assert.equal(workflowOutputState({ status: 'RUNNING', nodes: [{ status: 'RUNNING' }] }, '正文', 0, false, false), 'open')
  assert.equal(workflowOutputState(snapshot, '终答', 0, false, false), 'open')
  assert.equal(workflowOutputState({ status: 'SUCCESS' }, '', 2, false, false), 'open')
  assert.equal(shouldShowWorkflowOutput({ status: 'RUNNING' }, '已流出', 0, false, false), true)
})

test('没有卡片快照时不展开', () => {
  assert.equal(shouldShowWorkflowOutput(undefined, '终答', 3), false)
  assert.equal(workflowOutputState(undefined, '终答', 3), 'hidden')
})

test('方案下还有未结束的子块时 schemeChildrenSettled 为 false', () => {
  const cards = [
    { key: 'scheme', participantKind: 'scheme', participantDepth: 1, closed: 'done', roleLabel: '方案' },
    { key: 'expert', participantKind: 'agent', participantDepth: 2 },
    { key: 'flow', participantKind: 'workflow', participantDepth: 2, closed: 'done' }
  ]
  assert.equal(schemeChildrenSettled(cards, cards[2]), false)
  cards[1].closed = 'done'
  assert.equal(schemeChildrenSettled(cards, cards[2]), true)
})

test('没有方案父块时，只看这张卡自己的终态', () => {
  const open = [{ key: 'flow', participantKind: 'workflow', participantDepth: 1 }]
  assert.equal(schemeChildrenSettled(open, open[0]), false)
  const done = [{ key: 'flow', participantKind: 'workflow', participantDepth: 1, closed: 'done' }]
  assert.equal(schemeChildrenSettled(done, done[0]), true)
})

test('空行再密也不按段数折叠：有正文时仍是 folded / open，不存在段数', () => {
  const dense = Array.from({ length: 32 }, () => '一句正文。').join('\n\n')
  assert.equal(workflowOutputState(snapshot, dense, 0, false, true), 'folded')
  assert.equal(workflowOutputFolded(snapshot, dense, 0, true), true)
  assert.equal(workflowOutputState(snapshot, dense, 0, true, true), 'open')
})
