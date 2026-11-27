/**
 * 调试跟随纯逻辑的窄测。所属域：workflow（前端）。
 *
 * 覆盖：当前步骤选择（单步 / 等待 / 并行 / 失败 / 历史）、去重键、可见矩形与视口反算。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  FOLLOW_RESERVED_BOTTOM,
  FOLLOW_RESERVED_TOP,
  followAttemptOf,
  followKeyOf,
  followTimeOf,
  followViewportOf,
  isTerminalDetail,
  isTerminalRunStatus,
  pickFollowTarget,
  pickLastEndedTarget,
  visibleFollowBox
} from '../src/views/workflow/editor/workflowRunFollow.ts'

/**
 * 造一行节点运行态。
 *
 * @param code 节点编码
 * @param status 状态
 * @param extra 额外字段（startTime / endTime / attempt / nodeType / id）
 * @return 行
 */
function row(code, status, extra = {}) {
  return { id: 1, nodeCode: code, nodeType: 'AGENT', attempt: 1, status, ...extra }
}

test('单步执行跟唯一 RUNNING 的节点', () => {
  const target = pickFollowTarget(
    [row('a', 'SUCCESS'), row('b', 'RUNNING'), row('c', 'PENDING')],
    'RUNNING'
  )
  assert.deepEqual(target, { code: 'b', attempt: 1 })
})

test('不跟 START / END 边界节点', () => {
  const target = pickFollowTarget(
    [row('start', 'RUNNING', { nodeType: 'START' }), row('end', 'PENDING', { nodeType: 'END' })],
    'RUNNING'
  )
  assert.equal(target, null)
})

test('等待回复 / 等待人工跟等待节点，多行取最晚开始', () => {
  const target = pickFollowTarget(
    [
      row('ask', 'WAITING_REPLY', { startTime: '2026-11-27 10:00:00.000' }),
      row('check', 'WAITING_HUMAN', { startTime: '2026-11-27 10:00:05.000' })
    ],
    'PAUSED_HUMAN'
  )
  assert.deepEqual(target, { code: 'check', attempt: 1 })
})

test('并行多个 RUNNING 跟最早开始的那个，不来回跳', () => {
  const rows = [
    row('later', 'RUNNING', { id: 9, startTime: '2026-11-27 10:00:02.000' }),
    row('earlier', 'RUNNING', { id: 8, startTime: '2026-11-27 10:00:01.000' })
  ]
  assert.equal(pickFollowTarget(rows, 'RUNNING')?.code, 'earlier')
  // 再拍一次结果不变（同一批仍未结束）
  assert.equal(pickFollowTarget(rows, 'RUNNING')?.code, 'earlier')
  // 最早那个结束后才切到下一个
  const next = rows.map((item) => (item.nodeCode === 'earlier' ? { ...item, status: 'SUCCESS' } : item))
  assert.equal(pickFollowTarget(next, 'RUNNING')?.code, 'later')
})

test('RUNNING 优先于 FAILED，只有 run 失败且没有 RUNNING 才跟失败节点', () => {
  const running = [
    row('bad', 'FAILED', { id: 2, endTime: '2026-11-27 10:00:03.000' }),
    row('next', 'RUNNING', { id: 3, startTime: '2026-11-27 10:00:04.000' })
  ]
  assert.equal(pickFollowTarget(running, 'RUNNING')?.code, 'next')
  // run 收成 FAILED 后不会再有 RUNNING 行，此时才跟失败节点
  assert.equal(pickFollowTarget([running[0]], 'FAILED')?.code, 'bad')
  assert.equal(pickFollowTarget([running[0]], 'RUNNING'), null)
})

test('步骤间隙不跟已完成的节点', () => {
  assert.equal(pickFollowTarget([row('done', 'SUCCESS')], 'RUNNING'), null)
  assert.equal(pickFollowTarget([row('skip', 'SKIPPED')], 'SUCCESS'), null)
})

test('子图内部节点不抢镜头：跟随停在父图 SUBGRAPH 节点', () => {
  const rows = [
    row('call', 'RUNNING', {
      nodeType: 'SUBGRAPH',
      id: 1,
      startTime: '2026-11-27 10:00:00.000'
    }),
    row('x1a2b3c4d_intake', 'RUNNING', {
      id: 2,
      startTime: '2026-11-27 10:00:01.000'
    })
  ]
  assert.deepEqual(pickFollowTarget(rows, 'RUNNING'), { code: 'call', attempt: 1 })
  // 失败收口也只跟父图节点，不去定位画布上不存在的子图节点
  const failed = [
    row('call', 'FAILED', { nodeType: 'SUBGRAPH', id: 1, endTime: '2026-11-27 10:00:03.000' }),
    row('x1a2b3c4d_reply', 'FAILED', { id: 2, endTime: '2026-11-27 10:00:02.000' })
  ]
  assert.deepEqual(pickFollowTarget(failed, 'FAILED'), { code: 'call', attempt: 1 })
  // 历史记录同样跳过子图内部节点
  assert.deepEqual(
    pickLastEndedTarget([
      row('call', 'SUCCESS', { nodeType: 'SUBGRAPH', id: 1, endTime: '2026-11-27 10:00:03.000' }),
      row('x1a2b3c4d_reply', 'SUCCESS', { id: 2, endTime: '2026-11-27 10:00:04.000' })
    ]),
    { code: 'call', attempt: 1 }
  )
})

test('历史记录取最后结束的非 START/END、非 SKIPPED 节点', () => {
  const target = pickLastEndedTarget([
    row('start', 'SUCCESS', {
      nodeType: 'START',
      endTime: '2026-11-27 10:00:00.000'
    }),
    row('first', 'SUCCESS', { endTime: '2026-11-27 10:00:01.000' }),
    row('other', 'SKIPPED', { endTime: '2026-11-27 10:00:09.000' }),
    row('last', 'SUCCESS', { endTime: '2026-11-27 10:00:05.000' })
  ])
  assert.deepEqual(target, { code: 'last', attempt: 1 })
})

test('循环同 code 新 attempt 视为一次步骤切换', () => {
  assert.equal(followKeyOf(7, { code: 'loop', attempt: 1 }), '7:loop:1')
  assert.notEqual(
    followKeyOf(7, { code: 'loop', attempt: 1 }),
    followKeyOf(7, { code: 'loop', attempt: 2 })
  )
  assert.equal(followAttemptOf({ nodeCode: 'a', attempt: null }), 1)
  assert.equal(followAttemptOf({ nodeCode: 'a', attempt: 3 }), 3)
})

test('终态判断：成功 / 失败 / 取消，首帧据此判回看', () => {
  assert.equal(isTerminalRunStatus('SUCCESS'), true)
  assert.equal(isTerminalRunStatus('RUNNING'), false)
  assert.equal(isTerminalRunStatus('WAITING_REPLY'), false)
  assert.equal(isTerminalDetail({ id: 1, status: 'FAILED', nodeRuns: [] }), true)
  assert.equal(isTerminalDetail({ id: 1, status: 'RUNNING', nodeRuns: [] }), false)
  assert.equal(isTerminalDetail(null), false)
})

test('时间串兼容空格分隔并可比较', () => {
  assert.ok(followTimeOf('2026-11-27 10:00:00.500') > followTimeOf('2026-11-27 10:00:00.100'))
  assert.equal(followTimeOf(''), 0)
  assert.equal(followTimeOf('not-a-time'), 0)
})

test('可见矩形：右侧调试框只从右边扣，上下让出图标与缩放胶囊', () => {
  const box = visibleFollowBox(1000, 600, [{ left: 400, top: 0, right: 1200, bottom: 600 }])
  assert.equal(box.left, 12)
  assert.equal(box.right, 388)
  assert.equal(box.top, FOLLOW_RESERVED_TOP)
  assert.equal(box.bottom, 600 - FOLLOW_RESERVED_BOTTOM)
  assert.equal(box.width, 376)
})

test('可见矩形：贴右边缘之外的遮挡不改变宽度', () => {
  const box = visibleFollowBox(1000, 600, [{ left: 1100, top: 0, right: 1400, bottom: 600 }])
  assert.equal(box.right, 988)
})

test('可见矩形：盖满整块画布时返回零宽，调用方放弃跟随', () => {
  const box = visibleFollowBox(1000, 600, [{ left: 0, top: 0, right: 1000, bottom: 600 }])
  assert.equal(box.width, 0)
  assert.equal(box.height, 0)
  // 调试框比画布还宽：左边留不下可见区，同样放弃
  const narrow = visibleFollowBox(600, 600, [{ left: -50, top: 0, right: 800, bottom: 600 }])
  assert.equal(narrow.width, 0)
})

test('可见矩形：左侧面板从左边扣', () => {
  const box = visibleFollowBox(1000, 600, [{ left: 0, top: 0, right: 222, bottom: 600 }])
  assert.equal(box.left, 234)
  assert.equal(box.width, 754)
})

test('视口反算：节点中心落到可见矩形中心，缩放原样', () => {
  const box = visibleFollowBox(1000, 600, [{ left: 400, top: 0, right: 1200, bottom: 600 }])
  const transform = followViewportOf(box, 300, 200, 0.8)
  // 可见中心 x = (0 + 400) / 2 = 200；y = (64 + 536) / 2 = 300
  assert.equal(transform.x, 200 - 300 * 0.8)
  assert.equal(transform.y, 300 - 200 * 0.8)
})
