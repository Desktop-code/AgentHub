/**
 * 刷新后同一工作流运行只剩一张卡。所属域：runtime（前端回放投影）。
 *
 * <p>钉住的缺陷：跑任务期间刷新时，`/events` 里那条「等待你的回复」骨架是**该 run 在落库
 * 列表里的最后一张**，服务端 `WorkflowCardRebuilder` 会按 `workflow_node_run` 的真实状态
 * 把它校准成「已答复」（询问节点 SUCCESS、卡片级 choice 清掉）。而进行中的那张卡在
 * `/live-events` 里，是另一次请求、另一个列表。
 *
 * <p>前端跨轮接管 {@code absorbWaitingWorkflowCards} 原来只认「旧卡里仍有 WAITING_REPLY 节点」，
 * 校准过的旧卡一个等待节点都不剩，于是收不走：同一次运行在时间线上画出两张卡
 * （上面一张停在旧节点进度、下面一张是新的「运行中」卡片）。
 *
 * <p>这里用库里真实会话 704 的事件形状（run 113，循环体 + 中途刷新）钉住三件事：
 * <ol>
 *   <li>旧骨架被校准成「已答复」后，刷新只留一张工作流卡，且是续跑轮那张；</li>
 *   <li>原始骨架（未被校准）同样只留一张——不能为了修这条把老路径改坏；</li>
 *   <li>真新开一条运行（询问节点变 SKIPPED / PENDING）时，旧卡不许被收走。</li>
 * </ol>
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { ChatTurn, TrialWorkflowView } from '../src/types/runtime.ts'
import { turnsFromEvents } from '../src/components/runtime/trialMessages.ts'

/** 一轮里的全部工作流卡（含框内子卡不涉及，这里只平铺） */
function workflowCards(turn: ChatTurn): TrialWorkflowView[] {
  return (Array.isArray(turn.blocks) ? turn.blocks : [])
    .filter((block) => block?.kind === 'workflow' && !!block.workflow)
    .map((block) => block.workflow as TrialWorkflowView)
}

/** 时间线里所有助手轮的工作流卡总数 */
function cardCount(turns: ChatTurn[]): number {
  return turns
    .filter((turn) => turn.role === 'assistant')
    .reduce((total, turn) => total + workflowCards(turn).length, 0)
}

const RUN_CODE = 'mini_game_dev'
const RUN_NAME = '小游戏研发'
const PID = 'p-0-root-mini_game_dev'

/** 卡头仍写等待、询问节点仍是「等待你的回复」，并带可点选项（刚问出来的原始骨架） */
const WAITING_CARD = {
  name: RUN_NAME,
  code: RUN_CODE,
  runId: 113,
  status: 'WAITING_REPLY',
  answer: '你选定语言后，我就按这门语言往下做。',
  nodes: [
    { code: 'cond_lang', name: '是否已选语言', type: 'CONDITION', status: 'SUCCESS', note: '已完成' },
    { code: 'ask_language', name: '询问实现语言', type: 'AGENT', status: 'WAITING_REPLY', note: '等待你的回复' },
    { code: 'play_design', name: '玩法设计', type: 'AGENT', status: 'PENDING', note: '等待前置节点' },
    { code: 'play_impl', name: '游戏实现', type: 'AGENT', status: 'PENDING', note: '等待前置节点', group: 'loop' },
    { code: 'play_check', name: '体验检查', type: 'AGENT', status: 'PENDING', note: '等待前置节点', group: 'loop' },
    { code: 'play_review', name: '游戏点评', type: 'AGENT', status: 'PENDING', note: '等待前置节点' },
    { code: 'loop', name: '循环', type: 'LOOP', status: 'PENDING', note: '等待前置节点' }
  ],
  choice: {
    question: '这个 2048 小游戏你打算用什么语言实现？',
    options: [
      { id: 'html', label: 'HTML/CSS/JavaScript 单页' },
      { id: 'python', label: 'Python' }
    ]
  },
  v: 2
}

/**
 * 服务端按库里状态校准后的旧骨架：只改 stale 节点（PENDING / WAITING_*），
 * 卡片头 status 有意不改（历史语义），没有节点再等时清掉卡片级 choice。
 */
function calibratedWaitingCard() {
  const card = JSON.parse(JSON.stringify(WAITING_CARD))
  delete card.choice
  card.nodes = card.nodes.map((node: any) => {
    if (node.code === 'ask_language') {
      return { ...node, status: 'SUCCESS', note: '已完成', reply: 'HTML/CSS/JavaScript 单页' }
    }
    if (node.code === 'play_design') {
      return { ...node, status: 'SUCCESS', note: '已完成' }
    }
    if (node.code === 'play_impl') {
      return { ...node, status: 'RUNNING', note: '执行中', group: 'loop' }
    }
    if (node.code === 'cond_lang' && node.status === 'PENDING') {
      return { ...node, status: 'RUNNING', note: '执行中' }
    }
    return node
  })
  return card
}

/** 续跑轮进行中的卡片：循环体第 1 轮在跑，后续节点仍等待前置 */
function runningCard() {
  const card = JSON.parse(JSON.stringify(calibratedWaitingCard()))
  card.status = 'RUNNING'
  card.answer = '问句在前一轮\n\n已按 HTML/CSS/JavaScript 单页开始实现…'
  return card
}

/** 造一条回放事件（只填回放需要的字段） */
function event(seq: number, eventType: string, contentText: string, summary = '') {
  return { id: seq, sessionId: 704, seq, eventType, contentText, summary }
}

/** 前一轮：入口判定把问题交给工作流，工作流问出语言后这一轮结束（停在等待回复） */
function waitingTurnEvents(card: unknown) {
  return [
    event(1, 'user', '帮我做个2048游戏'),
    event(2, 'participant', JSON.stringify({ id: 'gw-ma-3', name: '智能网关', kind: 'gateway', depth: 0, begin: true })),
    event(3, 'participant', JSON.stringify({
      id: PID, name: RUN_NAME, kind: 'workflow', routeKey: RUN_CODE, depth: 0, begin: true
    })),
    event(4, 'route', `路由到 ${RUN_NAME}（${RUN_CODE}），依据：用户明确要做2048小游戏`),
    event(5, 'workflow', JSON.stringify(card), `工作流「${RUN_NAME}」等待你的回复`),
    event(6, 'assistant', '你选定语言后，我就按这门语言往下做。')
  ]
}

/** 续跑轮：工作流身份 begin → 进行中的卡片 → 正文（点选 / 输入框续跑都不落 USER 事件） */
function runningTurnEvents(card: unknown) {
  return [
    event(7, 'participant', JSON.stringify({
      id: PID, name: RUN_NAME, kind: 'workflow', routeKey: RUN_CODE, depth: 0, begin: true
    })),
    event(8, 'workflow', JSON.stringify(card), `工作流「${RUN_NAME}」运行中`),
    event(9, 'assistant', '已按 HTML/CSS/JavaScript 单页开始实现…')
  ]
}

test('刷新（骨架已被服务端校准成已答复）：只留一张工作流卡', () => {
  const turns = turnsFromEvents([...waitingTurnEvents(calibratedWaitingCard()), ...runningTurnEvents(runningCard())])
  assert.equal(cardCount(turns), 1, '同一次运行刷新后只该有一张工作流卡')

  const assistants = turns.filter((turn) => turn.role === 'assistant')
  assert.equal(workflowCards(assistants[0]).length, 0, '旧轮那张已答复卡应被续跑轮接管')
  const kept = workflowCards(assistants[1])[0]
  assert.equal(kept?.status, 'RUNNING', '留下的是续跑轮那张进行中的卡')
  assert.equal(kept?.code, RUN_CODE)
  assert.equal(kept?.nodes.length, 7, '节点表是完整的一份，不是从第一个条件节点另起')
  assert.equal(kept?.nodes.find((node) => node.code === 'ask_language')?.status, 'SUCCESS')
})

test('刷新（原始等待骨架）：老路径仍只留一张卡', () => {
  const turns = turnsFromEvents([...waitingTurnEvents(WAITING_CARD), ...runningTurnEvents(runningCard())])
  assert.equal(cardCount(turns), 1, '未被校准的等待卡同样要被收走')
})

test('真新开一条运行并跳过询问：旧卡不许被收走', () => {
  const fresh = runningCard()
  fresh.nodes = fresh.nodes.map((node) =>
    node.code === 'ask_language' ? { ...node, status: 'SKIPPED', note: '已跳过' } : node
  )
  const turns = turnsFromEvents([...waitingTurnEvents(calibratedWaitingCard()), ...runningTurnEvents(fresh)])
  assert.equal(cardCount(turns), 2, '不是同一条运行的续跑，两张卡都要留下（不能把新运行藏起来）')
})

test('中间夹了新的用户提问：校准过的旧卡不许被收走', () => {
  const turns = turnsFromEvents([
    ...waitingTurnEvents(calibratedWaitingCard()),
    event(7, 'user', '换个话题：再做个贪吃蛇'),
    ...runningTurnEvents(runningCard()).map((item) => ({ ...item, seq: item.seq + 1 }))
  ])
  assert.equal(cardCount(turns), 2, '中间有用户提问说明是新话题，旧卡保持原样')
})
