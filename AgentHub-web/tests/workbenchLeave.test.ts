/**
 * 工作台页签失活停表的判定。本地用例，不进仓。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { inspectClockNeeded } from '../src/components/runtime/workbench/inspectOverlay.ts'
import {
  isStreamAbort,
  shouldResumeAfterReturn
} from '../src/components/runtime/workbench/workbenchLeave.ts'
import { tracePollingAllowed } from '../src/composables/tracePollingGate.ts'

test('SSE abort 才算失活中断，普通失败不是', () => {
  assert.equal(isStreamAbort(new DOMException('The operation was aborted', 'AbortError')), true)
  assert.equal(isStreamAbort(Object.assign(new Error('The user aborted a request'), { name: 'AbortError' })), true)
  assert.equal(isStreamAbort(new Error('发送失败')), false)
  assert.equal(isStreamAbort('连接已结束'), false)
  assert.equal(isStreamAbort(null), false)
})

test('空闲或本轮已结束时不续订', () => {
  assert.equal(shouldResumeAfterReturn({ assistantStreaming: false, turnRunning: false }), false)
})

test('助手仍在流式或登记仍在跑时，激活后要续', () => {
  assert.equal(shouldResumeAfterReturn({ assistantStreaming: true, turnRunning: false }), true)
  assert.equal(shouldResumeAfterReturn({ assistantStreaming: false, turnRunning: true }), true)
})

test('追踪轮询：失活或本轮结束都不开表', () => {
  assert.equal(tracePollingAllowed(false, true), false)
  assert.equal(tracePollingAllowed(true, false), false)
  assert.equal(tracePollingAllowed(true, true), true)
})

test('执行明细时钟：失活停，运行中或紧凑卡打开才走表', () => {
  assert.equal(
    inspectClockNeeded({ pageActive: false, running: true, detailOpen: true, compact: true }),
    false
  )
  // 运行中：浮层关着也要走表，按钮上的耗时才会增长
  assert.equal(
    inspectClockNeeded({ pageActive: true, running: true, detailOpen: false, compact: false }),
    true
  )
  // 已结束且浮层关着：没有要推进的内容
  assert.equal(
    inspectClockNeeded({ pageActive: true, running: false, detailOpen: false, compact: false }),
    false
  )
  // 已结束但用户打开了紧凑卡：留观行与已耗时仍需时钟
  assert.equal(
    inspectClockNeeded({ pageActive: true, running: false, detailOpen: true, compact: true }),
    true
  )
})
