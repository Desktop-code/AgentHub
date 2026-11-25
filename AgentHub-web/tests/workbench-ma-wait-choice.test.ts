/**
 * 方案中途停等：问句 / 选项 / 已答复都挂在该子专家卡上（node:test，无浏览器）。
 * 所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 同一轮里多位专家先后询问时，选项块按身份各自保留，不互相覆盖；
 * 2) 已答复的选项块清掉 chips、留下「您的选择」；
 * 3) 投影层：两位专家的卡各自拿到自己的问句 / 选择，问句不会落到方案外壳或入口块。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  appendChoice,
  appendParticipantBegin,
  assistantTurn,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import {
  buildParticipantFeed as projectParticipantFeed,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import type { ChatTurn } from '../src/types/runtime.ts'

/** 两个合法选项（后端要求 2~6 项） */
const TWO = [
  { id: 'a', label: '方案 A' },
  { id: 'b', label: '方案 B' }
]

/**
 * 造一条「方案串行」形状的助手轮：专家甲先问，回复后专家乙接着问。
 *
 * @return 助手轮
 */
function serialTurn(): ChatTurn {
  const turn = assistantTurn('wb-a-test', 'streaming')
  // 方案外壳：即使它先进来，选项也不能挂到它名下
  appendParticipantBegin(turn, { id: 'p-scheme', name: '小游戏研发', kind: 'scheme', depth: 1, begin: true })
  appendParticipantBegin(turn, { id: 'p-a', name: '策划', kind: 'agent', depth: 1, begin: true })
  turn.blocks.push({ kind: 'assistant', text: '我先出三套玩法。' })
  appendChoice(turn, { question: '要几套玩法？', options: TWO, waiting: true }, 'p-a', '策划')
  // 用户答复：同一张卡清选项、记选择
  appendChoice(turn, { question: '要几套玩法？', options: [], reply: '方案 A' }, 'p-a', '策划')
  appendParticipantBegin(turn, { id: 'p-b', name: '前端', kind: 'agent', depth: 1, begin: true })
  turn.blocks.push({ kind: 'assistant', text: '我按方案 A 出界面。' })
  appendChoice(turn, { question: '界面用哪种风格？', options: TWO, waiting: true }, 'p-b', '前端')
  return turn
}

test('同一轮多位专家询问：选项按身份各自保留，不互相覆盖', () => {
  const turn = serialTurn()
  const choices = turn.blocks.filter((block) => block.kind === 'choice')
  assert.equal(choices.length, 2)

  const first = choices.find((block) => block.participantName === '策划')
  const second = choices.find((block) => block.participantName === '前端')
  assert.equal(first?.choice?.reply, '方案 A')
  assert.equal(first?.choice?.options.length, 0)
  assert.equal(!!first?.choice?.waiting, false)
  assert.equal(second?.choice?.waiting, true)
  assert.equal(second?.choice?.options.length, 2)
})

test('投影层：问句与已答复选择留在各自子专家卡里，不落到方案 / 入口块', () => {
  const turn = serialTurn()
  const cards = visitParticipantCards(projectParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  }))

  const planner = cards.find((card) => card.title === '策划')
  const front = cards.find((card) => card.title === '前端')
  const scheme = cards.find((card) => card.title === '小游戏研发')
  assert.ok(planner)
  assert.ok(front)
  // 方案外壳不承接子专家的问句
  assert.equal(scheme?.choice, undefined)

  // 已答复：卡里有这次选择，没有可点选项
  assert.equal(planner.choice?.reply, '方案 A')
  assert.equal(planner.choice?.options.length, 0)
  assert.equal(planner.answer.includes('三套玩法'), true)

  // 下一个询问仍在自己的卡上等待
  assert.equal(front.choice?.waiting, true)
  assert.equal(front.choice?.options.length, 2)
  assert.equal(front.answer.includes('按方案 A'), true)
})

test('重复 begin 不重复出块：续跑接回同一位专家时卡片只有一张', () => {
  const turn = assistantTurn('wb-a-test', 'streaming')
  appendParticipantBegin(turn, { id: 'p-a', name: '策划', kind: 'agent', depth: 1, begin: true })
  // 续跑帧会再推一次同一位的身份（同一个参与者 id）
  appendParticipantBegin(turn, { id: 'p-a', name: '策划', kind: 'agent', depth: 1, begin: true })

  const beginBlocks = turn.blocks.filter((block) => block.kind === 'route' && block.participantId === 'p-a')
  assert.equal(beginBlocks.length, 1)
})
