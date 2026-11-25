/**
 * 管理端对话：工具前的助手正文归入思考，终答最多一块。
 * 工作台投影不读这套折叠。
 *
 * 运行：node --experimental-strip-types --test tests/interim-answer.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import {
  DEBUG_THINK_TITLE,
  finalAnswerIndex,
  foldInterimAnswers,
  lanesForDebugSkin
} from '../src/components/runtime/trialMessages.ts'

test('工具前的中途说明进思考，终答只认最后一段', () => {
  const blocks: AssistantBlock[] = [
    { kind: 'assistant', text: '改去官网' },
    { kind: 'tool', text: '调用工具 browserUse' },
    { kind: 'assistant', text: '搜索页没有正文' },
    { kind: 'tool', text: '调用工具 browserUse' },
    { kind: 'assistant', text: '预约已开放' }
  ]
  assert.equal(finalAnswerIndex(blocks), 4)
  const lanes = lanesForDebugSkin(foldInterimAnswers(blocks))
  const think = lanes.find((lane) => lane.title === DEBUG_THINK_TITLE)
  assert.ok(think)
  assert.equal(think?.steps.filter((step) => step.type === 'reasoning').length, 2)
  assert.equal(
    think?.steps.some((step) => step.text.includes('改去官网')),
    true
  )
  assert.equal(
    think?.steps.some((step) => step.text.includes('预约已开放')),
    false
  )
})

test('后面还有工具时还没有终答', () => {
  const blocks: AssistantBlock[] = [
    { kind: 'reasoning', text: '先看官网' },
    { kind: 'assistant', text: '碰到验证码' },
    { kind: 'tool', text: '调用工具 browserUse' }
  ]
  assert.equal(finalAnswerIndex(blocks), -1)
  const folded = foldInterimAnswers(blocks)
  assert.equal(folded[1].kind, 'reasoning')
  assert.equal(folded[1].text, '碰到验证码')
})
