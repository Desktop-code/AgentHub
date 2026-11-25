/**
 * 刀 C：刷新落在「点选已答复、运行正在续跑」这一拍时的续订判定。所属域：runtime（前端）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li>已答复续跑（卡片头仍 WAITING_REPLY、节点已非等待、choice 已清、询问节点带 reply、
 *       后面还有节点在跑）必须判成「这一轮还没收口」，从而继续续订直播；</li>
 *   <li>未点选、库里确实还在等（节点 WAITING_REPLY + 仍带 choice）行为不变：不续订这一轮，
 *       下一句仍按「答复」处理；</li>
 *   <li>MA / 方案停等的事件不会被当成工作流续跑——收口口径保持原样；</li>
 *   <li>节点已全部结束（只等终态卡落库）不算续跑，不再无谓轮询。</li>
 * </ol>
 *
 * 运行：在 AgentHub-web 目录执行 npm test。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  hasOpenTurn,
  workflowReplyAnswered,
  workflowReplyProgressing,
  workflowReplyResuming,
  workflowStillWaitingForReply
} from '../src/components/runtime/trialMessages.ts'
import type { TrialEvent, TrialWorkflowView } from '../src/types/runtime.ts'
import { shouldResumeTurn } from '../src/components/runtime/sessionProgress.ts'

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

/** 已答复、正在续跑：询问节点已 SUCCESS 且带 reply，后续实现节点 RUNNING。 */
function answeredRunningCard(withChoice = false): string {
  return card(
    'WAITING_REPLY',
    [
      { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
      { code: 'play_impl', name: '实现', type: 'AGENT', status: 'RUNNING' }
    ],
    withChoice
  )
}

/** 仍停在等待：询问节点 WAITING_REPLY 且卡片带选项。 */
function stillWaitingCard(): string {
  return card(
    'WAITING_REPLY',
    [{ code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY' }],
    true
  )
}

/**
 * 造一段「点选续跑中途」的落库事件：末尾是那条「等待你的回复」assistant。
 *
 * @param workflowCard 工作流卡片正文
 * @return 事件列表
 */
function resumeEvents(workflowCard: string): TrialEvent[] {
  return [
    event(1, 'user', '帮我做一个推箱子小游戏'),
    event(2, 'participant', JSON.stringify({ id: 'p-0-root-mini', name: '小游戏研发', kind: 'workflow', begin: true })),
    event(3, 'workflow', workflowCard),
    event(4, 'assistant', '工作流「小游戏研发」正在等待你的回复；请直接在本会话下方输入框接着说。')
  ]
}

test('刀 C：已答复、正在续跑 → 认成未收口（hasOpenTurn 原本判成已收口）', () => {
  const events = resumeEvents(answeredRunningCard())
  // 复现刀 C 之前的判定：末尾 assistant 让 hasOpenTurn 认为这一轮写完了
  assert.equal(hasOpenTurn(events), false, '前置：hasOpenTurn 只认 assistant / error 收口')
  assert.equal(workflowReplyResuming(events), true, '已答复续跑必须被识别出来')
})

test('刀 C：未点选、库里确实还在等 → 不续订（与改前一致）', () => {
  const events = resumeEvents(stillWaitingCard())
  assert.equal(workflowReplyResuming(events), false, '还在等用户点选时不算续跑')
  assert.equal(hasOpenTurn(events), false, '等待卡后面那条 assistant 本就是收口写法')
  const view = JSON.parse(stillWaitingCard()) as TrialWorkflowView
  assert.equal(workflowStillWaitingForReply(view), true, '仍要按「等回复」处理，下一句是答复')
})

test('刀 C：已答复续跑的卡不再被当成「等回复」（下一句按新问题走）', () => {
  const view = JSON.parse(answeredRunningCard()) as TrialWorkflowView
  assert.equal(workflowStillWaitingForReply(view), false)
  assert.equal(workflowReplyAnswered(view), true)
})

test('刀 C：节点已全部结束、终态卡还没落库 → 仍续订（不能停死在「等待输入」）', () => {
  const done = card('WAITING_REPLY', [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
    { code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }
  ])
  assert.equal(
    workflowReplyResuming(resumeEvents(done)),
    true,
    '库里的骨架仍是等待卡（终态卡还没写），提前停轮询会让页面停在「等待输入」'
  )

  // 终态卡落库后卡片头不再是 WAITING_REPLY：本条判据自然失效，不再多轮询
  const settled = card('SUCCESS', [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
    { code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }
  ])
  assert.equal(workflowReplyResuming(resumeEvents(settled)), false)
})

test('刀 C：没有 reply 证据（询问节点仍等待）不算续跑', () => {
  const half = card('WAITING_REPLY', [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY' },
    { code: 'play_impl', name: '实现', type: 'AGENT', status: 'PENDING' }
  ])
  assert.equal(workflowReplyResuming(resumeEvents(half)), false)
})

test('刀 C：卡级 choice 还在就不算已答复', () => {
  assert.equal(workflowReplyAnswered(JSON.parse(answeredRunningCard(true)) as TrialWorkflowView), false)
})

test('刀 C：只看最后一条 user 之后的工作流卡，更早轮次的旧卡不误判', () => {
  const events = [
    event(1, 'user', '上一轮的问题'),
    event(2, 'workflow', answeredRunningCard()),
    event(3, 'assistant', '上一轮的终答'),
    event(4, 'user', '这一轮的新问题'),
    event(5, 'assistant', '这一轮已经答完')
  ]
  assert.equal(workflowReplyResuming(events), false, '旧轮次的卡不能把新一轮判成续跑')
})

test('刀 C 回归：MA / 方案停等事件不被当成工作流续跑', () => {
  // 方案停等：CHOICE 带 waiting，没有任何工作流卡
  const maEvents = [
    event(1, 'user', '做个 2048'),
    event(2, 'participant', JSON.stringify({ id: 'p-0-root-game_team', name: '游戏研发团队', kind: 'scheme', begin: true })),
    event(3, 'choice', JSON.stringify({ question: '用哪种语言？', options: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }], waiting: true })),
    event(4, 'assistant', '「游戏玩法设计」正在等待你的回复。')
  ]
  assert.equal(workflowReplyResuming(maEvents), false, 'MA 收口口径不受刀 C 影响')
  assert.equal(hasOpenTurn(maEvents), false)
})

test('刀 C：卡片头改口口径（纯函数断言；组件层文案由手点覆盖）', () => {
  // 卡片头文案/样式在 workflowCardView.ts，那里用 @/ 别名做运行时 import，
  // node --experimental-strip-types 解析不到别名，因此这里只钉判定函数本身。
  const running = JSON.parse(answeredRunningCard()) as TrialWorkflowView
  assert.equal(workflowReplyAnswered(running), true, '显示层据此把「等待输入」改口为「运行中」')

  const waiting = JSON.parse(stillWaitingCard()) as TrialWorkflowView
  assert.equal(workflowReplyAnswered(waiting), false, '真实等待卡必须保持「等待输入」')
})

test('刀 C：残缺卡（卡片头等待但无节点表）仍按等待处理，不当成已答复', () => {
  const degraded = { name: '小游戏研发', code: 'mini_game_dev', status: 'WAITING_REPLY', nodes: [] } as TrialWorkflowView
  assert.equal(workflowStillWaitingForReply(degraded), true)
  assert.equal(workflowReplyAnswered(degraded), false)
})

test('刀 C：等待 assistant 落了很久也不影响续订（静默上限只作用在常规分支）', () => {
  const events = resumeEvents(answeredRunningCard())
  // 模拟「等待你的回复」那条 assistant 是 40 分钟前落的
  const old = new Date(Date.now() - 40 * 60_000)
  const stamp = `${old.getFullYear()}-${String(old.getMonth() + 1).padStart(2, '0')}-${String(old.getDate()).padStart(2, '0')} ` +
    `${String(old.getHours()).padStart(2, '0')}:${String(old.getMinutes()).padStart(2, '0')}:${String(old.getSeconds()).padStart(2, '0')}`
  const aged = events.map((item) => ({ ...item, createTime: stamp }))

  assert.equal(hasOpenTurn(aged), false)
  assert.equal(workflowReplyResuming(aged), true, '证据在节点与 reply 上，与那条 assistant 的时长无关')
  assert.equal(
    shouldResumeTurn({
      status: 'RUNNING',
      open: workflowReplyResuming(aged),
      // 续跑分支不套静默上限（见 useWorkbenchChat.shouldResume）
      stale: false,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }),
    true
  )
})

test('刀 C：轮询节奏——仍在推进 vs 刚跑完待终态卡', () => {
  // 询问已答、后续节点还在跑：必须按常规节奏跟进度
  assert.equal(workflowReplyProgressing(resumeEvents(answeredRunningCard())), true)

  // 节点已全部结束：这条运行已经跑完，只在等终态卡落库，轮询应放慢
  const settled = card('WAITING_REPLY', [
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'SUCCESS', reply: 'Python' },
    { code: 'play_impl', name: '实现', type: 'AGENT', status: 'SUCCESS' }
  ])
  assert.equal(workflowReplyResuming(resumeEvents(settled)), true, '仍要续订，不能停在「等待输入」')
  assert.equal(workflowReplyProgressing(resumeEvents(settled)), false, '但没有可跟的进度，放慢节奏')

  // 未答复：既不算续跑也不算推进
  assert.equal(workflowReplyProgressing(resumeEvents(stillWaitingCard())), false)
})

test('回归：MA 运行中途刷新仍续订，回放框架与收口口径不变', () => {
  // MA 一轮中途：入口身份 + 方案根 + 子专家，末尾没有终答 → hasOpenTurn 仍是唯一判据
  const midRun = [
    event(1, 'user', '搞个2048小游戏玩玩'),
    event(2, 'participant', JSON.stringify({ id: 'gw-ma-3', name: '智能网关', kind: 'gateway', depth: 0, begin: true })),
    event(3, 'participant', JSON.stringify({ id: 'p-0-root-game_team', name: '游戏研发团队', kind: 'scheme', depth: 0, begin: true })),
    event(4, 'reasoning', '先定规则与验收标准')
  ]
  assert.equal(hasOpenTurn(midRun), true, 'MA 中途刷新仍要续订')
  assert.equal(workflowReplyResuming(midRun), false, '没有工作流卡，不参与刀 C 的新判据')
  assert.equal(
    shouldResumeTurn({
      status: 'RUNNING',
      open: hasOpenTurn(midRun) || workflowReplyResuming(midRun),
      stale: false,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }),
    true,
    '与刀 C 之前的 shouldResume 结果一致'
  )

  // MA 一轮跑完：末尾有终答 → 不续订，回放框架按原样走
  const done = [...midRun, event(5, 'assistant', '本次用的语言是 HTML/CSS/JavaScript 单页')]
  assert.equal(hasOpenTurn(done), false)
  assert.equal(workflowReplyResuming(done), false)
  assert.equal(
    shouldResumeTurn({
      status: 'RUNNING',
      open: hasOpenTurn(done) || workflowReplyResuming(done),
      stale: false,
      hasEvents: true,
      pendingMs: 120_000,
      sessionStamp: Date.now(),
      sameSession: true
    }),
    false,
    'MA 收口后不续订，与改前一致'
  )
})
