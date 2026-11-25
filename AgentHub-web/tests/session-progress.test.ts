/**
 * 会话终态不再续订、不再转圈。
 * 运行：npm test
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  canStopRun,
  composerButtons,
  rowShowsSpinner,
  runStoppable,
  sessionStillRunning,
  shouldResumeTurn
} from '../src/components/runtime/sessionProgress.ts'

describe('会话终态不再当作运行中', () => {
  it('只有 RUNNING 或还没拿到状态算进行中', () => {
    assert.equal(sessionStillRunning('RUNNING'), true)
    assert.equal(sessionStillRunning(undefined), true)
    assert.equal(sessionStillRunning(''), true)
    assert.equal(sessionStillRunning('CANCELLED'), false)
    assert.equal(sessionStillRunning('FAILED'), false)
    assert.equal(sessionStillRunning('SUCCESS'), false)
  })

  it('取消会话即使末尾还是 user，也不续订、列表不转圈', () => {
    assert.equal(shouldResumeTurn({
      status: 'CANCELLED',
      open: true,
      stale: false,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }), false)
    assert.equal(rowShowsSpinner('CANCELLED', true, false), false)
  })

  it('仍是 RUNNING 且末尾未收尾时继续续订并转圈', () => {
    assert.equal(shouldResumeTurn({
      status: 'RUNNING',
      open: true,
      stale: false,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }), true)
    assert.equal(rowShowsSpinner('RUNNING', true, false), true)
    assert.equal(rowShowsSpinner('RUNNING', false, false), false)
    assert.equal(rowShowsSpinner('RUNNING', true, true), false)
  })

  it('过旧的未收尾一轮不续订；刚建的空 RUNNING 会话在短窗口内仍续订', () => {
    assert.equal(shouldResumeTurn({
      status: 'RUNNING',
      open: true,
      stale: true,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }), false)
    assert.equal(shouldResumeTurn({
      status: 'RUNNING',
      open: false,
      stale: false,
      hasEvents: false,
      pendingMs: 120_000,
      sessionStamp: Date.now() - 1_000,
      sameSession: true
    }), true)
    assert.equal(shouldResumeTurn({
      status: 'CANCELLED',
      open: false,
      stale: false,
      hasEvents: false,
      pendingMs: 120_000,
      sessionStamp: Date.now() - 1_000,
      sameSession: true
    }), false)
  })
})

/**
 * 刀 D：MA / 工作流运行中途刷新，输入区必须仍是停止。
 *
 * <p>刷新后没有在途流（sending=false），运行靠续订接回来。若「可停止」判据不认续订，
 * 停止按钮会消失、发送按钮露出来，下一句就被当成新问题发进还在跑的那一轮。
 */
describe('运行中途刷新仍是停止按钮', () => {
  it('MA 中途刷新（只有续订）→ canStop 为真', () => {
    const activity = { sending: false, resuming: true }
    assert.equal(runStoppable(activity), true)
    assert.equal(canStopRun(activity, false, false, true), true)
    assert.deepEqual(composerButtons(activity), { stop: true, send: false })
  })

  it('MA 本页在途流 → canStop 为真，且不露发送', () => {
    const activity = { sending: true, resuming: false }
    assert.equal(canStopRun(activity, false, false, true), true)
    assert.deepEqual(composerButtons(activity), { stop: true, send: false })
  })

  it('MA 已跑完（无在途流、无续订）→ canStop 为假，恢复发送', () => {
    const activity = { sending: false, resuming: false }
    assert.equal(runStoppable(activity), false)
    assert.equal(canStopRun(activity, false, false, true), false)
    assert.deepEqual(composerButtons(activity), { stop: false, send: true })
  })

  it('已点过停止 / 停止请求在途 / 还没有真实会话 → 停止按钮不可点', () => {
    const activity = { sending: false, resuming: true }
    assert.equal(canStopRun(activity, true, false, true), false, '已停过不再重复发取消')
    assert.equal(canStopRun(activity, false, true, true), false, '停止请求在途')
    assert.equal(canStopRun(activity, false, false, false), false, '草稿没有可中断对象')
  })

  it('工作流停在等待回复 → 停止与发送同时出现（发送即那句答复）', () => {
    const activity = { sending: false, waitingReply: true, resuming: false }
    assert.equal(canStopRun(activity, false, false, true), true)
    assert.deepEqual(composerButtons(activity), { stop: true, send: true })
  })

  it('工作流点选已答复、正在续跑 → 停止在、发送不露（下一句不能当答复发进去）', () => {
    // 刀 C 之后 waitingReply 已为 false，只剩续订信号；这正是刷新后那一拍
    const activity = { sending: false, waitingReply: false, resuming: true }
    assert.equal(canStopRun(activity, false, false, true), true)
    assert.deepEqual(composerButtons(activity), { stop: true, send: false })
  })

  it('跑完后重进（无在途流、无续订、无等待）→ 只有发送，回放不受影响', () => {
    assert.deepEqual(composerButtons({ sending: false, waitingReply: false, resuming: false }), {
      stop: false,
      send: true
    })
  })
})
