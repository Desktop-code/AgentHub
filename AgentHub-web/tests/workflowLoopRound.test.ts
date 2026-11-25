import assert from 'node:assert/strict'
import test from 'node:test'
import { wfNodeKey, wfNodeRoundLabel } from '../src/components/runtime/workbench/chat/workflowNodeRound.ts'

/**
 * 循环节点多轮展示：同一编码的两轮都要能区分。所属域：runtime 的前端卡片。
 */
test('同一节点两轮分别标注，单轮不额外占字', () => {
  const first = { code: 'rewrite', name: '改写', type: 'AGENT', status: 'SUCCESS', attempt: 1 }
  const second = { code: 'rewrite', name: '改写', type: 'AGENT', status: 'SUCCESS', attempt: 2 }
  const alone = { code: 'ask', name: '询问', type: 'AGENT', status: 'SUCCESS' }

  assert.equal(wfNodeRoundLabel(first, [first, second]), '第 1 轮')
  assert.equal(wfNodeRoundLabel(second, [first, second]), '第 2 轮')
  assert.equal(wfNodeRoundLabel(alone, [alone]), '')
  assert.notEqual(wfNodeKey(first, 0), wfNodeKey(second, 1))
})
