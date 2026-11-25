/**
 * 刀 F2：刷新时「工作流卡仍在跑」这一路的续订闸。所属域：runtime（前端判定）。
 *
 * <p>钉住五件事：
 * <ol>
 *   <li>落库骨架（卡片头 WAITING_REPLY、节点带 reply）与内存快照（卡片头 RUNNING、节点在跑）
 *       合路后，旧的 reply 判据会脱空；放宽后的 {@code resumeTurnOpen} 必须仍认成「还在跑」；</li>
 *   <li>只有 /events 的已答复续跑保持原判据，不回退刀 C；</li>
 *   <li>反向闸：终态卡、已落「已中断」闭环、等待用户回复都不续订；</li>
 *   <li>MA / 单 Agent 轮没有工作流卡，收口口径与改前一致；</li>
 *   <li>轮询调速：卡片头在跑但节点已全停（等终态卡落库）走慢拍，节点在跑走正常拍。</li>
 * </ol>
 *
 * 运行：在 AgentHub-web 目录执行 npm test。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  hasOpenTurn,
  lastWorkflowCardRunning,
  resumeTurnOpen,
  stoppedClosure,
  turnsFromEvents,
  workflowCardProgressing,
  workflowCardResuming,
  workflowReplyProgressing,
  workflowReplyResuming
} from '../src/components/runtime/trialMessages.ts'
import { shouldResumeTurn } from '../src/components/runtime/sessionProgress.ts'
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
 * 工作流卡片正文（与落库 WORKFLOW 事件 / 刀 B 校准后的骨架同形）。
 *
 * @param headStatus 卡片头状态
 * @param nodes      节点表
 * @param withChoice 是否带卡级选项
 * @return JSON 字符串
 */
function card(headStatus: string, nodes: unknown[], withChoice = false): string {
  return JSON.stringify({
    name: '小游戏研发',
    code: 'mini_game_dev',
    status: headStatus,
    answer: '已确认：你在回复里点名了 Python',
    nodes,
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
  })
}

/** 落库骨架：卡片头保留等待时的 WAITING_REPLY，询问节点已带 reply，后续节点还没开始。 */
function storedSkeleton(): TrialEvent[] {
  return [
    event(1, 'user', '帮我做一个推箱子小游戏'),
    event(2, 'participant', JSON.stringify({ id: 'p-0-root-mini', name: '小游戏研发', kind: 'workflow', begin: true })),
    event(3, 'workflow', card('WAITING_REPLY', [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
      { code: 'play_impl', name: '实现', type: 'AGENT', status: 'PENDING' }
    ])),
    event(4, 'assistant', '工作流「小游戏研发」正在等待你的回复；请直接在本会话下方输入框接着说。')
  ]
}

/** 内存快照：续跑中的直播帧，卡片头是 RUNNING，节点正在跑，末尾已流出一段正文。 */
function liveRunning(): TrialEvent[] {
  return [
    event(5, 'workflow', card('RUNNING', [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
      { code: 'play_impl', name: '实现', type: 'AGENT', status: 'RUNNING' }
    ])),
    event(6, 'reasoning', '先搭页面骨架'),
    event(7, 'assistant', '我先写第一版 HTML')
  ]
}

/**
 * 按 seq 合并，模拟 {@code fetchResumeEvents} 的合路结果。
 *
 * @param groups 多段事件
 * @return 升序事件
 */
function merge(...groups: TrialEvent[][]): TrialEvent[] {
  return groups.flat().sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
}

test('F2：/events + /live-events 合路后卡片仍在跑 → 续订（旧 reply 判据会脱空）', () => {
  const merged = merge(storedSkeleton(), liveRunning())
  // 前置复现：合路后末尾是流式正文，卡头是 RUNNING，两条旧判据都认为已收口
  assert.equal(hasOpenTurn(merged), false, '前置：末尾 assistant 让 hasOpenTurn 认为这一轮写完了')
  assert.equal(workflowReplyResuming(merged), false, '旧判据只认卡片头 WAITING_REPLY + reply，合路后脱空')

  // 放宽后的判据：卡片自己在跑
  assert.equal(workflowCardResuming(merged), true, '最后一张卡头是 RUNNING，必须在跑')
  assert.equal(resumeTurnOpen(merged), true, '续订闸必须认成未收口')
  assert.equal(lastWorkflowCardRunning(turnsFromEvents(merged)), true, '与 F1 的 cardRunning 同源同结论')
  assert.equal(workflowCardProgressing(merged), true, '节点在跑：正常节奏跟进度')
})

test('F2：合路后 shouldResumeTurn 进入续订（状态 RUNNING、不套静默上限）', () => {
  const merged = merge(storedSkeleton(), liveRunning())
  assert.equal(
    shouldResumeTurn({
      status: 'RUNNING',
      open: resumeTurnOpen(merged),
      stale: false,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }),
    true
  )
})

test('F2：只有 /events 的已答复续跑保持原判据（不回退刀 C）', () => {
  const stored = storedSkeleton()
  assert.equal(hasOpenTurn(stored), false)
  assert.equal(workflowReplyResuming(stored), true, '刀 C 的 reply 证据仍然有效')
  assert.equal(resumeTurnOpen(stored), true)
})

test('F2：跑完落终态卡 → 不续订，回放按已收口走', () => {
  const finished = merge(storedSkeleton(), [
    event(5, 'workflow', card('SUCCESS', [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
      { code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }
    ])),
    event(6, 'assistant', '做好了，第一版可以直接打开')
  ])
  assert.equal(hasOpenTurn(finished), false)
  assert.equal(workflowCardResuming(finished), false, '卡头已是终态')
  assert.equal(resumeTurnOpen(finished), false)
  assert.equal(workflowCardProgressing(finished), false)
  assert.equal(lastWorkflowCardRunning(turnsFromEvents(finished)), false, 'F1 的停止按钮也要收掉')
})

test('F2 反向闸：已落「已中断」闭环 → 即使卡头还是 RUNNING 也不续订', () => {
  const stopped = merge(storedSkeleton(), liveRunning(), [event(8, 'error', '已中断')])
  assert.equal(workflowCardResuming(stopped), true, '前置：快照里的卡头仍可能是 RUNNING')
  assert.equal(stoppedClosure(stopped), true, '停止必须落「已中断」闭环')
  assert.equal(resumeTurnOpen(stopped), false, '停止后的卡不能再被续起来')
  assert.equal(lastWorkflowCardRunning(turnsFromEvents(stopped)), false, 'F1 侧同样收口')
})

test('F2 反向闸：上一轮的「已中断」不挡下一轮仍在跑的工作流', () => {
  const events = merge(
    [event(1, 'user', '上一轮'), event(2, 'workflow', card('RUNNING', [{ code: 'a', name: 'A', type: 'AGENT', status: 'RUNNING' }]))],
    [event(3, 'error', '已中断')],
    [event(4, 'user', '这一轮接着做'), event(5, 'workflow', card('RUNNING', [{ code: 'b', name: 'B', type: 'AGENT', status: 'RUNNING' }]))],
    [event(6, 'assistant', '正在写第二版')]
  )
  assert.equal(stoppedClosure(events), false, '只认最后一条 user 之后的闭环')
  assert.equal(resumeTurnOpen(events), true)
})

test('F2 反向闸：还在等用户回复 → 不自动续订（输入区由 waitingReply 给停止+发送）', () => {
  const waiting = merge(
    [
      event(1, 'user', '帮我做一个推箱子小游戏'),
      event(2, 'workflow', card('WAITING_REPLY', [
        { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY' }
      ], true)),
      event(3, 'assistant', '工作流「小游戏研发」正在等待你的回复。')
    ],
    [event(4, 'reasoning', '等待用户选择')]
  )
  assert.equal(hasOpenTurn(waiting), false)
  assert.equal(workflowCardResuming(waiting), false, '等回复不算「卡在跑」')
  assert.equal(resumeTurnOpen(waiting), false)
  assert.equal(workflowCardProgressing(waiting), false)
})

test('F2：人工暂停且节点都不在跑 → 不自动续订', () => {
  const paused = merge(
    [
      event(1, 'user', '帮我发布一版'),
      event(2, 'workflow', card('PAUSED_HUMAN', [
        { code: 'approve', name: '人工确认', type: 'HUMAN', status: 'SUCCESS' }
      ])),
      event(3, 'assistant', '等待人工确认。')
    ]
  )
  assert.equal(workflowCardResuming(paused), false)
  assert.equal(resumeTurnOpen(paused), false)
})

test('F2：卡头在跑但节点已全停（终态卡未落库）→ 仍续订，但放慢轮询', () => {
  const tailPending = merge(
    [
      event(1, 'user', '帮我做一个推箱子小游戏'),
      event(2, 'workflow', card('RUNNING', [
        { code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }
      ])),
      event(3, 'assistant', '第一版完成')
    ]
  )
  assert.equal(resumeTurnOpen(tailPending), true, '卡头还没收口，不能提前停轮询')
  assert.equal(workflowCardProgressing(tailPending), false, '没有可跟的进度：走慢拍等终态卡')
})

test('F2 回归：workflowReplyProgressing 与放宽后的卡片调速同口径', () => {
  const repliedRunning = merge(storedSkeleton(), [
    event(5, 'workflow', card('WAITING_REPLY', [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
      { code: 'play_impl', name: '实现', type: 'AGENT', status: 'RUNNING' }
    ])),
    event(6, 'assistant', '继续写实现')
  ])
  assert.equal(workflowReplyProgressing(repliedRunning), true)

  const repliedSettled = merge(storedSkeleton(), [
    event(5, 'workflow', card('WAITING_REPLY', [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
      { code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }
    ])),
    event(6, 'assistant', '做完了，等终态卡')
  ])
  assert.equal(workflowReplyProgressing(repliedSettled), false)
})

test('F2 回归：旧轮次的 RUNNING 卡不会把新一轮判成续订', () => {
  const events = merge(
    [
      event(1, 'user', '上一轮的问题'),
      event(2, 'workflow', card('RUNNING', [{ code: 'a', name: 'A', type: 'AGENT', status: 'RUNNING' }]))
    ],
    [event(3, 'error', '已中断')],
    [event(4, 'user', '这一轮的新问题'), event(5, 'assistant', '这一轮已经答完')]
  )
  assert.equal(hasOpenTurn(events), false)
  assert.equal(workflowCardResuming(events), false, '只看最后一条 user 之后的卡')
  assert.equal(resumeTurnOpen(events), false)
})

test('F2 回归：MA 中途刷新仍续订、跑完不续订（没有工作流卡）', () => {
  const midRun = [
    event(1, 'user', '搞个2048小游戏玩玩'),
    event(2, 'participant', JSON.stringify({ id: 'gw-ma-3', name: '智能网关', kind: 'gateway', depth: 0, begin: true })),
    event(3, 'participant', JSON.stringify({ id: 'p-0-root-game_team', name: '游戏研发团队', kind: 'scheme', depth: 0, begin: true })),
    event(4, 'reasoning', '先定规则与验收标准')
  ]
  assert.equal(hasOpenTurn(midRun), true, 'MA 中途仍靠 hasOpenTurn 续订')
  assert.equal(workflowCardResuming(midRun), false, '没有工作流卡，不参与放宽判据')
  assert.equal(resumeTurnOpen(midRun), true)

  const done = [...midRun, event(5, 'assistant', '本次用的语言是 HTML/CSS/JavaScript 单页')]
  assert.equal(hasOpenTurn(done), false)
  assert.equal(workflowCardResuming(done), false)
  assert.equal(resumeTurnOpen(done), false, 'MA 收口后不续订，与改前一致')
})

test('F2 回归：单 Agent 一轮跑完 → 不续订', () => {
  const events = [
    event(1, 'user', '帮我写一句 slogan'),
    event(2, 'reasoning', '想三个方向'),
    event(3, 'assistant', '让智能，更简单。')
  ]
  assert.equal(hasOpenTurn(events), false)
  assert.equal(resumeTurnOpen(events), false)
})
