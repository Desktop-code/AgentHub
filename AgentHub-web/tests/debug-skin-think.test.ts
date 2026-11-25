/**
 * 调试皮肤思考过程与工作台建卡边界。所属域：runtime（前端投影）。
 *
 * 单 Agent 没有参与者身份帧。调试皮肤必须把无身份推理 / 工具收成一条「思考过程」。
 * 工作台仍走严格 processLanes：无身份过程不建匿名「执行」卡。
 *
 * 运行：node --experimental-strip-types --test tests/debug-skin-think.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import {
  DEBUG_THINK_TITLE,
  lanesForDebugSkin,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 单 Agent 回放 / 直播的典型块：推理和工具都不带 participantId，终答另起一块。 */
function reactBlocks(): AssistantBlock[] {
  return [
    { kind: 'reasoning', text: '先核对清单范围' },
    { kind: 'tool', text: '调用工具 list_items，参数 {"q":"1"}', detail: '三条' },
    { kind: 'assistant', text: '最终这三条可以执行' }
  ]
}

test('调试皮肤无身份推理仍出「思考过程」', () => {
  const blocks = reactBlocks()
  const lanes = lanesForDebugSkin(blocks)
  const think = lanes.find((lane) => lane.title === DEBUG_THINK_TITLE)
  assert.equal(DEBUG_THINK_TITLE, '思考过程')
  assert.ok(think, '调试皮肤应有一条思考过程')
  assert.equal(think?.kind, 'note')
  assert.equal(lanes.filter((lane) => lane.title === '执行').length, 0)
  assert.equal(
    think?.steps.some((step) => step.type === 'reasoning' && step.text.includes('先核对清单范围')),
    true
  )
  assert.equal(
    think?.steps.some((step) => step.type === 'tool_call' && step.title.includes('list_items')),
    true
  )
  assert.equal(
    think?.steps.some((step) => step.type === 'tool_result' && step.text === '三条'),
    true
  )
  // 终答不进思考泳道
  assert.equal(think?.steps.some((step) => step.text.includes('最终这三条')), false)
})

test('已挂到参与者身上的推理不在调试皮肤再收一条', () => {
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: '路由到 清单专家（list）', participantId: 'a-1', participantName: '清单专家' },
    { kind: 'reasoning', text: '已经归到这位专家' }
  ]
  const lanes = lanesForDebugSkin(blocks)
  assert.equal(lanes.some((lane) => lane.title === DEBUG_THINK_TITLE), false)
  const owner = lanes.find((lane) => lane.title === '路由到 清单专家')
  assert.ok(owner)
  assert.equal(owner?.steps.filter((step) => step.text.includes('已经归到这位专家')).length, 1)
})

test('工作台无 begin 仍不建匿名「执行」卡', () => {
  const blocks = reactBlocks()
  const lanes = processLanes(blocks)
  assert.equal(lanes.length, 0)
  assert.equal(lanes.some((lane) => lane.title === '执行'), false)
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes,
    blocks,
    streaming: false,
    fallbackName: '清单专家',
    expectEntry: false
  })
  assert.equal(cards.some((card) => card.title === '执行'), false)
  assert.equal(cards.some((card) => card.title === DEBUG_THINK_TITLE), false)
})
