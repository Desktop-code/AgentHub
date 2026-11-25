/**
 * 刷新后方案内头像是否还在转的本地用例。所属域：runtime（前端）。
 *
 * 关键回归：进行中一轮的内存快照现在带参与者终态帧（begin=false + state）。
 * 回放必须把「已结束的那一位」收口——否则刷新后整轮仍被标成 streaming，
 * 方案内所有成员（含早已跑完的）会一起转圈，与直播表现不一致。
 *
 * 同时钉住：整轮落库收口（末尾有 assistant）后，纯回放不处于 streaming / pending。
 *
 * 运行：在 AgentHub-web 目录执行 npm test（用例目录不进仓）。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  hasOpenTurn,
  markLiveTail,
  processLanes,
  turnsFromEvents
} from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import type { ChatTurn, TrialEvent } from '../src/types/runtime.ts'

/** 展开嵌套后的卡片（与 ChatPanel 同一入口） */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}

/**
 * 造一条事件。
 *
 * @param seq   序号
 * @param type  事件类型
 * @param body  正文
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

/** 一位参与者的 begin 帧正文（与出站 / 快照同一形状） */
function begin(id: string, name: string, kind: string, depth: number) {
  return JSON.stringify({ id, name, kind, routeKey: id, depth, begin: true })
}

/** 一位参与者的终态帧正文（begin=false + state） */
function end(id: string, name: string, kind: string, depth: number, state: string, note = '') {
  return JSON.stringify({ id, name, kind, routeKey: id, depth, begin: false, state, note })
}

/**
 * 进行中一轮的快照：入口 + 方案根 + 两位子专家，其中第一位已收口。
 *
 * @return 按 seq 升序的事件
 */
function midRunEvents(): TrialEvent[] {
  return [
    event(1, 'user', '搞个2048小游戏玩玩'),
    event(2, 'participant', begin('gw-ma-3', '智能网关', 'gateway', 0)),
    event(3, 'participant', begin('p-0-root-game_team', '游戏研发团队', 'scheme', 0)),
    event(4, 'participant', begin('p-1-game_team-game_play_design', '游戏玩法设计', 'agent', 1)),
    event(5, 'reasoning', '先定规则与验收标准'),
    event(6, 'assistant', '规则稿如下'),
    event(7, 'participant', end('p-1-game_team-game_play_design', '游戏玩法设计', 'agent', 1, 'done')),
    event(8, 'participant', begin('p-1-game_team-game_implement', '游戏实现', 'agent', 1)),
    event(9, 'reasoning', '开始写单页')
  ]
}

/**
 * 用现网投影出卡（与 ChatPanel 同口径：streaming 只看整轮状态）。
 *
 * @param turn 本轮
 * @return 卡片
 */
function project(turn: ChatTurn) {
  return buildParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    streaming: turn.status === 'streaming',
    entryName: '智能网关',
    fallbackName: '智能助手'
  })
}

test('运行中刷新：已收口的成员不再转圈，正在跑的那位仍转', () => {
  const events = midRunEvents()
  // 刷新这一拍拿的是「内存快照 + 落库」，整轮仍在跑 → 末轮标成 streaming
  const turns = markLiveTail(turnsFromEvents(events), events)
  const last = turns[turns.length - 1]
  assert.equal(last.status, 'streaming', '进行中整轮必须仍是 streaming')

  const cards = project(last)
  const done = cards.find((card) => card.title === '游戏玩法设计')
  const running = cards.find((card) => card.title === '游戏实现')
  assert.ok(done, '已结束的成员卡片必须还在')
  assert.ok(running, '正在跑的成员卡片必须还在')
  assert.equal(done?.pending, false, '已结束成员刷新后不得再转圈')
  assert.equal(running?.pending, true, '正在跑的成员保持进行态')
})

test('运行中刷新：终态只收口对应那一位，不误伤别人', () => {
  const events = midRunEvents()
  const turns = turnsFromEvents(events)
  const last = turns[turns.length - 1]
  const route = last.blocks.find((block) => block.kind === 'route' && block.participantId === 'p-1-game_team-game_play_design')
  assert.equal(route?.closed, 'done')
  const other = last.blocks.find((block) => block.kind === 'route' && block.participantId === 'p-1-game_team-game_implement')
  assert.equal(other?.closed, undefined, '没有终态的成员不能被顺手关掉')
})

test('已结束方案刷新（纯回放）：不在 streaming，也没有任何 pending 头像', () => {
  const events = [
    ...midRunEvents(),
    event(10, 'assistant', '本次用的语言是 HTML/CSS/JavaScript单页'),
    event(11, 'participant', end('p-1-game_team-game_implement', '游戏实现', 'agent', 1, 'done'))
  ]
  assert.equal(hasOpenTurn(events), false, '末尾有正文即视为这一轮已收口')
  const turns = turnsFromEvents(events)
  const last = turns[turns.length - 1]
  assert.equal(last.status, 'done', '纯回放不得处于 streaming')

  const cards = project(last)
  assert.ok(cards.length > 0)
  assert.deepEqual(
    cards.filter((card) => card.pending === true).map((card) => card.title),
    [],
    '已结束方案刷新后不应有任何头像在转'
  )
})
