/**
 * 工作流卡片「停等询问选项」的归一化与回放单测。所属域：runtime（前端）。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>卡片快照里的 choice 与 CHOICE 事件同形归一（2~6 项、id / label 非空、id 不重复）；</li>
 *   <li>直播同卡原位覆盖时，新快照没有 choice 不会把已出现的选项清掉（增量只增不减）；</li>
 *   <li>落库回放（WORKFLOW 事件）与直播读同一份字段，选项在回放里照样能画。</li>
 * </ol>
 *
 * 运行：node --experimental-strip-types --test tests/workflowChoice.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { appendWorkflow, normalizeWorkflow, parseWorkflowEvent } from '../src/components/runtime/trialMessages.ts'

/** 造一条最小助手轮。 */
function turn(key: string) {
  return {
    key,
    role: 'assistant' as const,
    text: '',
    blocks: [] as { kind: string; text: string; workflow?: unknown }[],
    phase: 'done',
    status: 'done' as const,
    startTime: 0
  }
}

/** 一张等待回复的卡片快照。 */
function waitingSnapshot(withChoice = true) {
  return {
    name: '小游戏研发',
    code: 'mini_game_dev',
    status: 'WAITING_REPLY',
    answer: '请选择实现语言',
    nodes: [{ code: 'ask', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY' }],
    ...(withChoice
      ? {
          choice: {
            question: '你想用哪种语言？',
            options: [
              { id: 'python', label: 'Python' },
              { id: 'java', label: 'Java' }
            ]
          }
        }
      : {})
  }
}

test('归一化：卡片 choice 与 CHOICE 事件同形，可点项原样保留', () => {
  const view = normalizeWorkflow(waitingSnapshot())
  assert.ok(view)
  assert.equal(view.status, 'WAITING_REPLY')
  assert.equal(view.choice?.question, '你想用哪种语言？')
  assert.deepEqual(
    view.choice?.options.map((item) => item.id),
    ['python', 'java']
  )
})

test('归一化：少于 2 项 / id 重复 整份丢弃，不出半组按钮', () => {
  assert.equal(normalizeWorkflow({ ...waitingSnapshot(), choice: { question: 'x', options: [{ id: 'a', label: 'A' }] } })?.choice, undefined)
  assert.equal(
    normalizeWorkflow({
      ...waitingSnapshot(),
      choice: { question: 'x', options: [{ id: 'a', label: 'A' }, { id: 'A', label: '又A' }] }
    })?.choice,
    undefined
  )
  assert.equal(normalizeWorkflow({ ...waitingSnapshot(), choice: { options: [] } })?.choice, undefined)
})

test('直播：同卡原位覆盖时，新快照没带 choice 也不清掉已出现的选项', () => {
  const item = turn('wb-a-1')
  appendWorkflow(item, waitingSnapshot())
  assert.equal((item.blocks[0].workflow as { choice?: unknown }).choice !== undefined, true)

  // 后续拍（例如只推了节点状态）没带 choice：选项必须还在，不能被清空
  appendWorkflow(item, waitingSnapshot(false))
  const view = item.blocks[0].workflow as { choice?: { options: { id: string }[] } }
  assert.deepEqual(view.choice?.options.map((row) => row.id), ['python', 'java'])
})

test('回放：WORKFLOW 事件正文与直播同字段，选项照样能画出来', () => {
  const view = parseWorkflowEvent(JSON.stringify(waitingSnapshot()))
  assert.ok(view)
  assert.equal(view.choice?.options.length, 2)
  assert.equal(view.choice?.options[1].label, 'Java')
})
