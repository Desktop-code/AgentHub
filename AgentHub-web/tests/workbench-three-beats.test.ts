/**
 * 工作台「三拍时序 + 底部步骤提示」的单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 思考这一拍还在流式时，工具与结果都不提前出现；
 * 2) 工具 / 结果一到，思考框立刻收起（三拍不并行）；
 * 3) 底部提示严格跟真实事件：规划中 / 思考中 / 调用工具中 / 作答中；
 * 4) 本轮结束、出错、待批准时提示消失，不编造状态。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { isThinkOpenByDefault } from '../src/components/runtime/workbench/workbenchContentBox.ts'
import {
  STEP_HINT_ANSWERING,
  STEP_HINT_PLANNING,
  STEP_HINT_THINKING,
  STEP_HINT_TOOL,
  liveStepHintOf
} from '../src/components/runtime/workbench/workbenchStepHint.ts'

test('三拍第一拍：还在思考且没有工具 / 结果时，思考框展开', () => {
  const expert = { kind: 'actor' as const, answer: '' }
  assert.equal(isThinkOpenByDefault(expert, true, true), true)
})

test('三拍第一拍结束：工具一出现，思考框立刻收起（不并行提前）', () => {
  const expert = { kind: 'actor' as const, answer: '' }
  // 工具已经出现：即便这一位还在跑，思考这一拍也结束了
  assert.equal(isThinkOpenByDefault(expert, true, true, true), false)
})

test('三拍第一拍结束：结果一出现，思考框立刻收起', () => {
  const expert = { kind: 'actor' as const, answer: '年假可顺延' }
  assert.equal(isThinkOpenByDefault(expert, true, true, false, true), false)
})

test('本轮结束：无论有没有工具与结果，思考框一律收起', () => {
  const expert = { kind: 'actor' as const, answer: '' }
  assert.equal(isThinkOpenByDefault(expert, false, true, false, false), false)
  assert.equal(isThinkOpenByDefault(expert, false, true, true, true), false)
})

test('底部提示：一条过程事件都没有时是规划中', () => {
  assert.equal(liveStepHintOf([], true), STEP_HINT_PLANNING)
  assert.equal(liveStepHintOf(undefined, true), STEP_HINT_PLANNING)
})

test('底部提示：尾块是推理 → 思考中', () => {
  assert.equal(liveStepHintOf([{ kind: 'route' }, { kind: 'reasoning' }], true), STEP_HINT_THINKING)
})

test('底部提示：有工具正在跑 → 调用工具中（优先于其它判断）', () => {
  const blocks = [{ kind: 'reasoning' }, { kind: 'tool', toolState: 'running' }]
  assert.equal(liveStepHintOf(blocks, true), STEP_HINT_TOOL)
})

test('底部提示：工具已回、正文未开始 → 等模型继续思考', () => {
  assert.equal(liveStepHintOf([{ kind: 'tool', toolState: 'done' }], true), STEP_HINT_THINKING)
  assert.equal(liveStepHintOf([{ kind: 'tool', toolState: 'error' }], true), STEP_HINT_THINKING)
})

test('底部提示：尾块是正文 → 作答中', () => {
  assert.equal(liveStepHintOf([{ kind: 'reasoning' }, { kind: 'assistant' }], true), STEP_HINT_ANSWERING)
})

test('底部提示：本轮结束 / 出错 / 待批准时消失', () => {
  assert.equal(liveStepHintOf([{ kind: 'reasoning' }], false), '')
  assert.equal(liveStepHintOf([{ kind: 'reasoning' }], true, true), '')
  assert.equal(liveStepHintOf([{ kind: 'assistant' }, { kind: 'error' }], true), '')
})

test('底部提示：计划 / 步骤有自己的卡片，不在这里猜拍', () => {
  assert.equal(liveStepHintOf([{ kind: 'plan' }], true), '')
  assert.equal(liveStepHintOf([{ kind: 'step' }], true), '')
})

test('底部提示：产物 / 图片文字等素材不改变当前拍，继续往前找', () => {
  const blocks = [{ kind: 'reasoning' }, { kind: 'file' }, { kind: 'vision' }]
  assert.equal(liveStepHintOf(blocks, true), STEP_HINT_THINKING)
})

test('底部提示：工作流有自己的运行状态，不假装思考中', () => {
  assert.equal(liveStepHintOf([{ kind: 'route', text: '运行工作流「报告流水线」…' }], true), '')
  // 普通「路由到」仍然是等这位参与者开口
  assert.equal(liveStepHintOf([{ kind: 'route', text: '路由到 制度知识专家（policy）' }], true), STEP_HINT_THINKING)
})
