/**
 * 会话终态不再续订、不再转圈。
 * 运行：npm test
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  rowShowsSpinner,
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
