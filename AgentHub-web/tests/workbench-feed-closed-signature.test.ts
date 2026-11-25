/**
 * 消息流投影闸「参与者终态进签名」的本地用例。所属域：runtime（前端）。
 *
 * 关键回归：`done / failed / aborted` 终态帧到达时，块的数量、正文长度、文件名都不变，
 * 只有 `closed` 变了。签名漏掉它 → 命中缓存 → 已跑完的专家继续按整轮 streaming 转圈，
 * 一直到整轮落屏才和最后一位一起停下来（用户看到的「后面才一起落定」）。
 *
 * 运行：在 AgentHub-web 目录执行 npm test（用例目录不进仓）。
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  appendParticipantBegin,
  appendParticipantEnd,
  assistantTurn,
  processLanes
} from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}
import {
  ParticipantFeedCache,
  turnFeedSignature
} from '../src/components/runtime/workbench/workbenchFeedCache.ts'

/**
 * 造一轮「子专家已开始、正在流式」的助手消息。
 *
 * @return 助手轮
 */
function streamingTurn() {
  const turn = assistantTurn('t-closed', 'streaming')
  appendParticipantBegin(turn, { id: 'a-1', name: '制度知识专家', kind: 'agent', begin: true })
  return turn
}

/**
 * 用真实投影链路把轮次收成卡片（与 ChatPanel 同一入口）。
 *
 * @param turn 助手轮
 * @return 参与者卡片
 */
function cardsOf(turn: ReturnType<typeof streamingTurn>) {
  return buildParticipantFeed({
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    lanes: processLanes(turn.blocks),
    blocks: turn.blocks,
    streaming: turn.status === 'streaming',
    entryName: 'Router',
    fallbackName: '智能助手'
  })
}

test('工作流卡片签名含选项 id：同状态同正文时，选项出现必须重算', () => {
  const base = assistantTurn('t-choice', 'done')
  base.blocks = [{
    id: 'b1',
    kind: 'workflow',
    text: '小游戏研发',
    workflow: {
      name: '小游戏研发',
      code: 'mini_game_dev',
      status: 'WAITING_REPLY',
      answer: '请选择实现语言',
      nodes: [{ code: 'ask', name: '询问', type: 'AGENT', status: 'WAITING_REPLY' }]
    }
  }]
  const withChoice = assistantTurn('t-choice', 'done')
  withChoice.blocks = [{
    id: 'b1',
    kind: 'workflow',
    text: '小游戏研发',
    workflow: {
      name: '小游戏研发',
      code: 'mini_game_dev',
      status: 'WAITING_REPLY',
      answer: '请选择实现语言',
      nodes: [{ code: 'ask', name: '询问', type: 'AGENT', status: 'WAITING_REPLY' }],
      choice: {
        question: '你想用哪种语言？',
        options: [
          { id: 'python', label: 'Python' },
          { id: 'java', label: 'Java' }
        ]
      }
    }
  }]
  assert.notEqual(turnFeedSignature(base, '', ''), turnFeedSignature(withChoice, '', ''))
})

test('终态写入 closed 时签名必须变（块数 / 正文长度都没变）', () => {
  const turn = streamingTurn()
  const before = turnFeedSignature(turn, 'Router', '')

  appendParticipantEnd(turn, { id: 'a-1', name: '制度知识专家', state: 'done', note: '' })

  const after = turnFeedSignature(turn, 'Router', '')
  assert.notEqual(after, before, 'closed 不进签名会让缓存继续返回「进行中」的旧卡片')
})

test('签名只差 closed 也要重算投影：终态帧到达后卡片立刻退出进行态', () => {
  const cache = new ParticipantFeedCache()
  const turn = streamingTurn()

  // 第一拍：还在跑
  const firstSig = cache.signatures([turn], 'Router', '').get(turn.key) ?? ''
  const first = cache.project(turn, firstSig, () => cardsOf(turn))
  assert.equal(first.find((card) => card.title === '制度知识专家')?.pending, true)

  // 第二拍：只收到一条 done 终态帧（无 note，正文一个字都没多）
  appendParticipantEnd(turn, { id: 'a-1', name: '制度知识专家', state: 'done', note: '' })

  const secondSig = cache.signatures([turn], 'Router', '').get(turn.key) ?? ''
  assert.notEqual(secondSig, firstSig)

  const second = cache.project(turn, secondSig, () => cardsOf(turn))
  assert.equal(
    second.find((card) => card.title === '制度知识专家')?.pending,
    false,
    '终态帧之后不得再显示进行态（不能等整轮落屏）'
  )
})

test('投影闸不改其它签名口径：正文长度变化仍然重算', () => {
  const turn = streamingTurn()
  const before = turnFeedSignature(turn, 'Router', '')
  turn.blocks.push({
    kind: 'assistant',
    text: '制度要点整理如下',
    participantId: 'a-1',
    participantName: '制度知识专家'
  })
  assert.notEqual(turnFeedSignature(turn, 'Router', ''), before)
})
