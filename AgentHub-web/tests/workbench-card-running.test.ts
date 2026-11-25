/**
 * 刷新后「认卡片仍在跑」（F1）。所属域：runtime（前端判定）。
 *
 * <p>钉住的缺陷：刷新正在跑的工作流后，本页对上一轮的接管必然归零
 * （{@code sending} / {@code resuming} 都为假），而右上角状态与输入区停止 / 发送只看这两个信号，
 * 于是卡片还在按节点状态转圈、按钮却报「已完成 · 4.5s」并只留发送。
 *
 * <p>本刀补一个 {@code cardRunning}：最后一张工作流卡看起来还在跑时，
 * {@code runStoppable} / {@code runBlocksSending} 都算它为真。
 *
 * <p>四条必须同时成立：
 * <ol>
 *   <li>卡片在跑（含「已答复、正在续跑」这一拍）→ 停止在、发送不露；</li>
 *   <li>卡片已收口 → 恢复发送；</li>
 *   <li>真在等用户回复 → 停止与发送同现（本信号为假，不把答复一起拦掉）；</li>
 *   <li>没有工作流块（MA / 单 Agent）→ 恒为假，既有行为一字不变。</li>
 * </ol>
 *
 * 运行：在 AgentHub-web 目录执行 npm test。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  lastWorkflowCardRunning,
  workflowCardRunning
} from '../src/components/runtime/trialMessages.ts'
import {
  composerButtons,
  runBlocksSending,
  runStoppable
} from '../src/components/runtime/sessionProgress.ts'
import { inspectTriggerLabel } from '../src/components/runtime/workbench/inspectOverlay.ts'
import type { ChatTurn, TrialWorkflowView } from '../src/types/runtime.ts'

/**
 * 造一张工作流卡视图。
 *
 * @param status 卡片头状态
 * @param nodes  节点表
 * @param extra  额外字段（choice 等）
 * @return 卡片视图
 */
function card(
  status: string,
  nodes: Array<Partial<TrialWorkflowView['nodes'][number]>>,
  extra: Partial<TrialWorkflowView> = {}
): TrialWorkflowView {
  return {
    name: '小游戏研发',
    code: 'mini_game_dev',
    status,
    answer: '',
    nodes: nodes as TrialWorkflowView['nodes'],
    ...extra
  } as TrialWorkflowView
}

/** 造一条助手轮，挂给定的工作流卡 */
function assistantTurnWith(wf: TrialWorkflowView | undefined, status: ChatTurn['status'] = 'done'): ChatTurn {
  return {
    key: 'a-1',
    role: 'assistant',
    blocks: wf ? [{ id: 'b-1', kind: 'workflow', text: '', workflow: wf }] : [],
    phase: 'done',
    status,
    startTime: 0
  } as ChatTurn
}

/** 续跑中的卡：询问已答（带 reply）、实现节点在跑、无卡级选项 */
const runningAnswered = card(
  'WAITING_REPLY',
  [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'HTML/CSS/JavaScript 单页' },
    { code: 'play_impl', name: '游戏实现', type: 'AGENT', status: 'RUNNING', group: 'loop' }
  ]
)

/** 真的还在等用户答：询问节点仍是 WAITING_REPLY，且仍带选项 */
const stillWaiting = card(
  'WAITING_REPLY',
  [{ code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY' }],
  {
    choice: {
      question: '这个 2048 小游戏你打算用什么语言实现？',
      options: [
        { id: 'html', label: 'HTML/CSS/JavaScript 单页' },
        { id: 'python', label: 'Python' }
      ]
    }
  }
)

/* ===== 1. workflowCardRunning：卡片自己的判断 ===== */

test('卡片在跑：已答复、实现节点 RUNNING → 算在跑', () => {
  assert.equal(workflowCardRunning(runningAnswered), true)
})

test('卡片在跑：骨架卡片头仍写 WAITING_REPLY、节点已 SUCCESS 但带 reply → 算在跑', () => {
  const settled = card('WAITING_REPLY', [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
    { code: 'play_impl', name: '游戏实现', type: 'AGENT', status: 'SUCCESS' }
  ])
  assert.equal(workflowCardRunning(settled), true, '终态卡还没落库，运行仍在推进')
})

test('卡片在跑：卡片头 RUNNING（直播 / 内存快照那一份）→ 算在跑', () => {
  assert.equal(
    workflowCardRunning(card('RUNNING', [{ code: 'play_impl', type: 'AGENT', status: 'RUNNING' }])),
    true
  )
})

test('卡片在跑：卡片头停在等待但节点已有 RUNNING（快照落后一拍）→ 算在跑', () => {
  assert.equal(
    workflowCardRunning(card('WAITING_REPLY', [{ code: 'play_impl', type: 'AGENT', status: 'RUNNING' }])),
    true
  )
})

test('卡片已收口：终态卡片头一律不算在跑', () => {
  for (const status of ['SUCCESS', 'FAILED', 'CANCELLED', 'UNKNOWN']) {
    assert.equal(
      workflowCardRunning(card(status, [{ code: 'play_impl', type: 'AGENT', status: 'SUCCESS' }])),
      false,
      `${status} 不该被当成还在跑`
    )
  }
})

test('真在等用户回复 → 不算「在跑」（归 waitingReply，否则答复会被拦掉）', () => {
  assert.equal(workflowCardRunning(stillWaiting), false)
})

test('空视图不算在跑；未知状态与卡片口径一致（卡片显示「运行中」→ 也算在跑）', () => {
  assert.equal(workflowCardRunning(undefined), false)
  /*
   * 未知状态必须与 {@code wfStatusText} 同口径：卡片对认不出的状态一律显示「运行中」，
   * 这里若判成已结束，就会出现「卡片在转、按钮说已完成」——正是本刀要消掉的分叉。
   * 真正「详情不可用」的残缺卡由 placeholderWorkflow 落成 UNKNOWN，那一种已在终态闸里挡掉。
   */
  assert.equal(workflowCardRunning(card('', [])), true)
  assert.equal(workflowCardRunning(card('UNKNOWN', [])), false, '占位卡（详情不可用）不能再占停止按钮')
})

/* ===== 2. lastWorkflowCardRunning：只看最后一条助手轮 ===== */

test('最后一轮的工作流卡在跑 → 时间线判为在跑', () => {
  assert.equal(lastWorkflowCardRunning([assistantTurnWith(runningAnswered)]), true)
})

test('本轮已收口（status=error，含用户停止补的「已中断」）→ 不算在跑', () => {
  assert.equal(lastWorkflowCardRunning([assistantTurnWith(runningAnswered, 'error')]), false)
})

test('跑完的会话 → 不算在跑', () => {
  const settled = card('SUCCESS', [{ code: 'play_impl', type: 'AGENT', status: 'SUCCESS' }])
  assert.equal(lastWorkflowCardRunning([assistantTurnWith(settled)]), false)
})

test('只看最后一条助手轮：更早轮次里已收口的卡不影响判定', () => {
  const stale = assistantTurnWith(runningAnswered)
  const latest = assistantTurnWith(card('SUCCESS', [{ code: 'play_impl', type: 'AGENT', status: 'SUCCESS' }]))
  assert.equal(lastWorkflowCardRunning([stale, latest]), false)
})

test('没有工作流块（MA / 单 Agent 轮）→ 恒为假', () => {
  assert.equal(lastWorkflowCardRunning([assistantTurnWith(undefined)]), false)
  assert.equal(lastWorkflowCardRunning([]), false)
})

/* ===== 3. runStoppable / runBlocksSending 把 cardRunning 算进去 ===== */

test('刷新丢了接管但卡片仍在跑 → 停止在、发送不露', () => {
  const activity = { sending: false, waitingReply: false, resuming: false, cardRunning: true }
  assert.equal(runStoppable(activity), true)
  assert.equal(runBlocksSending(activity), true, '卡片在跑时不能把下一句当新问题发出去')
  assert.deepEqual(composerButtons(activity), { stop: true, send: false })
})

test('卡片已收口且无其它信号 → 恢复发送', () => {
  const activity = { sending: false, waitingReply: false, resuming: false, cardRunning: false }
  assert.equal(runStoppable(activity), false)
  assert.deepEqual(composerButtons(activity), { stop: false, send: true })
})

test('真在等用户回复（cardRunning 为假）→ 停止与发送同现，答复照常能发', () => {
  const activity = { sending: false, waitingReply: true, resuming: false, cardRunning: false }
  assert.equal(runStoppable(activity), true)
  assert.equal(runBlocksSending(activity), false, '等待回复时那句就是答复，必须能发出去')
  assert.deepEqual(composerButtons(activity), { stop: true, send: true })
})

test('卡片在跑 + 同时也在等回复 → 仍以「等待回复」为准（发送保留）', () => {
  // waitingReply 为真时 cardRunning 通常也是假（workflowStillWaitingForReply 已排除），
  // 但嵌套方案里可能出现「方案在等某位子专家回答、另一个子工作流仍在跑」；
  // 这一拍发送必须可用，不能被卡片信号拦掉。
  const activity = { sending: false, waitingReply: true, resuming: false, cardRunning: true }
  assert.deepEqual(composerButtons(activity), { stop: true, send: true })
})

test('等回复的同时整轮仍在流式（sender 未收尾）→ 发送仍被拦', () => {
  // 多成员方案：卡片先出等待节点、整轮还在 SSE。此时露发送会把下一句当新问题发进去
  const activity = { sending: true, waitingReply: true, resuming: false, cardRunning: true }
  assert.equal(runBlocksSending(activity), true, '在途流不能被 waitingReply 放过')
  assert.deepEqual(composerButtons(activity), { stop: true, send: false })
})

test('无工作流块的轮（MA / 单 Agent）行为不变：三个旧信号照旧', () => {
  assert.deepEqual(composerButtons({ sending: true, resuming: false, cardRunning: false }), {
    stop: true,
    send: false
  })
  assert.deepEqual(composerButtons({ sending: false, resuming: true, cardRunning: false }), {
    stop: true,
    send: false
  })
  assert.deepEqual(composerButtons({ sending: false, resuming: false, cardRunning: false }), {
    stop: false,
    send: true
  })
  // 不传 cardRunning（MA 运行页现状）时按 false 处理
  assert.deepEqual(composerButtons({ sending: false, resuming: false }), { stop: false, send: true })
})

/* ===== 4. 右上角按钮文案：卡片信号撑起的「进行中」不带假耗时 ===== */

test('按钮文案：本页在跑 → 执行中 + 耗时；卡片信号撑起 → 只写执行中', () => {
  assert.equal(inspectTriggerLabel(true, 12_340), '执行中 · 12.3s')
  assert.equal(inspectTriggerLabel(false, 4_513), '已完成 · 4.5s')
  // 刷新后仅卡片在跑：chat trace 早已收口，耗时只能是假的，宁可不写数字
  assert.equal(inspectTriggerLabel(true, 4_513, true), '执行中')
  assert.equal(inspectTriggerLabel(true, null, true), '执行中')
})
