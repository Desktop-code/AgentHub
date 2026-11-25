/**
 * 刀 F3：刷新时「MA / 单 Agent 的执行卡仍在跑」这一路的共享闸。所属域：runtime（前端判定）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li>落库骨架 + 内存快照合路后，末尾 assistant 增量让 {@code hasOpenTurn} 认为已收口，
 *       但快照里还有没终态的参与者（专家还在思考 / 跑工具）；续订闸必须仍认成「还没完」；</li>
 *   <li>纯落库回放（没有内存快照）不能因为「开始帧没有终态帧」而续订已结束的一轮——
 *       终态帧只写进内存快照，落库永远只有开始帧；</li>
 *   <li>反向闸：参与者全部收口、已落「已中断」闭环、卡片头终态的工作流、等待回复都不续订；</li>
 *   <li>展示侧同一份事实：{@code lastAssistantCardRunning} 对 MA / 单 Agent / 工作流给出与卡片一致的结论。</li>
 * </ol>
 *
 * 运行：在 AgentHub-web 目录执行 npm test。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  hasOpenTurn,
  lastAssistantCardRunning,
  lastWorkflowCardRunning,
  markLiveTail,
  participantCardResuming,
  resumeTurnOpen,
  stoppedClosure,
  turnProgressing,
  turnsFromEvents,
  workflowCardResuming
} from '../src/components/runtime/trialMessages.ts'
import type { TrialEvent } from '../src/types/runtime.ts'

/**
 * 造一条事件。
 *
 * @param seq  序号
 * @param type 事件类型
 * @param body 正文
 * @return 事件
 */
function event(seq: number, type: string, body: string): TrialEvent {
  return {
    id: seq,
    sessionId: 1,
    seq,
    eventType: type,
    summary: body.slice(0, 40),
    contentText: body
  } as TrialEvent
}

/**
 * 参与者开始帧（与 TrialLiveBuffer / 落库 PARTICIPANT 同形）。
 *
 * @param id   参与者 id
 * @param name 展示名
 * @param kind begin 类型
 * @return JSON 正文
 */
function begin(id: string, name: string, kind: string): string {
  return JSON.stringify({ id, name, kind, depth: 0, begin: true })
}

/**
 * 参与者终态帧（只写内存快照，begin=false + state）。
 *
 * @param id    参与者 id
 * @param name  展示名
 * @param state done / failed / aborted
 * @return JSON 正文
 */
function end(id: string, name: string, state: string): string {
  return JSON.stringify({ id, name, begin: false, state })
}

/** 合并多段事件并按 seq 升序（模拟 fetchResumeEvents 的合路结果）。 */
function merge(...groups: TrialEvent[][]): TrialEvent[] {
  return groups.flat().sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
}

/** MA 落库骨架：只剩用户与身份开始帧，整轮还没收口事件。 */
function maStored(): TrialEvent[] {
  return [
    event(1, 'user', '搞个2048小游戏玩玩'),
    event(2, 'participant', begin('gw-ma-1', '智能网关', 'gateway')),
    event(3, 'participant', begin('p-0-root-game_team', '游戏研发团队', 'scheme')),
    event(4, 'participant', begin('p-a-frontend', '前端专家', 'agent'))
  ]
}

/** MA 内存快照：专家仍在思考 / 跑工具，末尾已经流出半截正文。 */
function maLive(): TrialEvent[] {
  return [
    event(5, 'reasoning', '先定页面骨架与交互'),
    event(6, 'tool', '调用工具 write_file\n\n{"path":"index.html"}'),
    event(7, 'assistant', '我先搭 4x4 棋盘与滑动合并')
  ]
}

test('F3：MA 合路后专家仍在跑 → 续订闸与卡片信号都认「还没完」', () => {
  const merged = merge(maStored(), maLive())
  // 前置复现：末尾 assistant 增量让 hasOpenTurn 认为这一轮已收口
  assert.equal(hasOpenTurn(merged), false, '前置：合路后末尾是流式正文')
  assert.equal(workflowCardResuming(merged), false, '没有工作流卡，F2 的卡片证据吃不到')

  // 参与者证据：开始帧还没有终态
  assert.equal(participantCardResuming(merged), true, '前端专家只有开始、没有终态')
  assert.equal(resumeTurnOpen(merged, true), true, '合并内存快照时必须续订')
  assert.equal(turnProgressing(merged, true), true, '运行在推进：正常节奏跟进度')

  // 展示侧：与卡片动画同源的整轮 streaming
  assert.equal(lastAssistantCardRunning(markLiveTail(turnsFromEvents(merged), merged)), true,
    '卡片还在动时右上角不能写已完成')
})

test('F3：MA 纯落库回放（无内存快照）→ 不续订（终态帧不落库）', () => {
  const storedReplay = merge(maStored(), [event(5, 'assistant', '本次用的是 HTML/CSS/JavaScript 单页')])
  // 回放里所有参与者都「没有终态」，不能据此续订已结束的一轮
  assert.equal(participantCardResuming(storedReplay), true, '前提：落库永远只有开始帧')
  assert.equal(resumeTurnOpen(storedReplay), false, '没有 live 时参与者证据必须失效')
  assert.equal(turnProgressing(storedReplay), false)
  assert.equal(lastAssistantCardRunning(turnsFromEvents(storedReplay)), false, '已结束：只剩发送')
})

test('F3：MA 所有参与者都已收口（live 仍在收尾）→ 不续订', () => {
  const merged = merge(
    maStored(),
    maLive(),
    [
      event(8, 'participant', end('p-a-frontend', '前端专家', 'done')),
      event(9, 'participant', end('p-0-root-game_team', '游戏研发团队', 'done')),
      event(10, 'participant', end('gw-ma-1', '智能网关', 'done'))
    ]
  )
  assert.equal(participantCardResuming(merged), false)
  assert.equal(resumeTurnOpen(merged, true), false)
  assert.equal(turnProgressing(merged, true), false, '没有可跟的进度：走慢拍等终态')
})

test('F3 反向闸：MA 中途已落「已中断」闭环 → 即使还有未终态参与者也不续订', () => {
  const stopped = merge(maStored(), maLive(), [event(8, 'error', '已中断')])
  assert.equal(participantCardResuming(stopped), true, '前提：快照里的专家还停在运行态')
  assert.equal(stoppedClosure(stopped), true)
  assert.equal(resumeTurnOpen(stopped, true), false, '停止后的卡不能再被续起来')
  assert.equal(turnProgressing(stopped, true), false)
  assert.equal(lastAssistantCardRunning(turnsFromEvents(stopped)), false, '停止后右上角收口')
})

test('F3：单 Agent 合路后仍在跑 → 同一套闸续订；跑完回放不续订', () => {
  const stored = [
    event(1, 'user', '帮我写一个登录页'),
    event(2, 'participant', begin('gw-solo-1', '智能网关', 'gateway')),
    event(3, 'participant', begin('p-a-solo', '前端专家', 'agent'))
  ]
  const live = [
    event(4, 'reasoning', '先确认表单校验项'),
    event(5, 'tool', '调用工具 write_file\n\n{"path":"login.vue"}'),
    event(6, 'assistant', '我正在写表单校验')
  ]
  const merged = merge(stored, live)
  assert.equal(hasOpenTurn(merged), false, '前置：末尾 assistant 增量让 hasOpenTurn 认为已收口')
  assert.equal(resumeTurnOpen(merged, true), true, '单 Agent 的卡片在跑')
  assert.equal(lastAssistantCardRunning(markLiveTail(turnsFromEvents(merged), merged)), true)

  const done = merge(stored, [event(4, 'assistant', '登录页已经写好，见附件')])
  assert.equal(resumeTurnOpen(done), false, '纯回放不认「开始帧没有终态」')
  assert.equal(lastAssistantCardRunning(turnsFromEvents(done)), false, '已结束：只剩发送')

  // 内存快照里参与者都已收口：没有可跟的进度，不能提前续
  const settled = merge(stored, [
    event(4, 'participant', end('p-a-solo', '前端专家', 'done')),
    event(5, 'participant', end('gw-solo-1', '智能网关', 'done')),
    event(6, 'assistant', '登录页已经写好，见附件')
  ])
  assert.equal(resumeTurnOpen(settled, true), false)
  assert.equal(turnProgressing(settled, true), false)
})

test('F3 回归：工作流卡仍是终态 → 不因参与者证据被重新续起来', () => {
  const finishedWorkflow = merge(
    [
      event(1, 'user', '帮我做一个推箱子小游戏'),
      event(2, 'participant', begin('p-0-root-mini', '小游戏研发', 'workflow')),
      event(3, 'workflow', JSON.stringify({
        name: '小游戏研发',
        code: 'mini_game_dev',
        status: 'SUCCESS',
        answer: '做好了',
        nodes: [{ code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }]
      })),
      event(4, 'assistant', '做好了，第一版可以直接打开')
    ]
  )
  // 工作流参与者只有开始帧、没有终态帧；也不能据此把终态卡续起来
  assert.equal(participantCardResuming(finishedWorkflow), false, '工作流参与者不参与该判据')
  assert.equal(resumeTurnOpen(finishedWorkflow, true), false)
  assert.equal(lastAssistantCardRunning(turnsFromEvents(finishedWorkflow)), false)
})

test('F3 回归：工作流卡仍在跑 → F2 判据保持不变', () => {
  const runningWorkflow = merge(
    [
      event(1, 'user', '帮我做一个推箱子小游戏'),
      event(2, 'participant', begin('p-0-root-mini', '小游戏研发', 'workflow')),
      event(3, 'workflow', JSON.stringify({
        name: '小游戏研发',
        code: 'mini_game_dev',
        status: 'RUNNING',
        nodes: [{ code: 'play_impl', name: '实现', type: 'AGENT', status: 'RUNNING' }]
      })),
      event(4, 'assistant', '正在写第一版')
    ]
  )
  assert.equal(resumeTurnOpen(runningWorkflow), true, '工作流证据不依赖 live')
  assert.equal(resumeTurnOpen(runningWorkflow, true), true)
  assert.equal(lastWorkflowCardRunning(turnsFromEvents(runningWorkflow)), true)
})

test('F3 反向闸：等待用户回复 → 仍是停止+发送，不自动续跑', () => {
  const waiting = merge(
    [
      event(1, 'user', '帮我做一个推箱子小游戏'),
      event(2, 'workflow', JSON.stringify({
        name: '小游戏研发',
        code: 'mini_game_dev',
        status: 'WAITING_REPLY',
        nodes: [{ code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY' }],
        choice: {
          question: '你想用哪种语言？',
          options: [{ id: 'python', label: 'Python' }, { id: 'java', label: 'Java' }]
        }
      })),
      event(3, 'assistant', '工作流「小游戏研发」正在等待你的回复。')
    ]
  )
  assert.equal(resumeTurnOpen(waiting, true), false)
  assert.equal(lastAssistantCardRunning(turnsFromEvents(waiting)), false)
})

test('F3 反向闸：本轮已收口（已中断）→ 即使时间线仍被标成 streaming 也不显示执行中', () => {
  const turns = turnsFromEvents(merge(maStored(), maLive(), [event(8, 'error', '已中断')]))
  // applyResumeEvents 会先把末尾标 streaming；markHaltedTurn / 回放补的错误必须让信号失效
  const flagged = turns.map((turn) =>
    turn.role === 'assistant' ? { ...turn, status: 'error' as const } : turn
  )
  assert.equal(lastAssistantCardRunning(flagged), false)
})
