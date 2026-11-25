/**
 * 等待回复续跑：旧等待卡被续跑轮接管后的时间线。所属域：runtime（前端展示）。
 *
 * <p>用库里真实的一条会话事件（session 659）当夹具：同一会话里只有一条 workflow_run，
 * 用户点选项后事件流是「用户气泡 → 工作流身份 begin → 同一张卡（询问节点已完成/已取消）」。
 * 这里锁住三件事：
 * <ol>
 *   <li>旧轮那张「停在询问」的卡与它的等待正文被收走，页面上只剩一张执行卡；</li>
 *   <li>续跑轮仍带着完整节点表（含已经结束的「询问实现语言」），不是从 cond_lang 另起一条；</li>
 *   <li>若新卡里等待节点变成 SKIPPED（说明真新开了一条运行并跳过询问），旧卡不许被收走。</li>
 * </ol>
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import type { ChatTurn } from '../src/types/runtime.ts'
import { turnsFromEvents } from '../src/components/runtime/trialMessages.ts'

/** 读取真实事件夹具：只读，不改仓库里的数据 */
function fixture(): any[] {
  return JSON.parse(readFileSync(new URL('../temp/events659.json', import.meta.url), 'utf8'))
}

/** 取助手轮里的工作流块 */
function workflowBlocks(turn: ChatTurn): any[] {
  return (Array.isArray(turn.blocks) ? turn.blocks : []).filter((block) => block?.kind === 'workflow')
}

/** 助手轮列表（跳过用户气泡） */
function assistantTurns(turns: ChatTurn[]): ChatTurn[] {
  return turns.filter((turn) => turn.role === 'assistant')
}

test('续跑：旧等待卡被收走，只剩续跑轮一张卡', () => {
  const turns = turnsFromEvents(fixture())
  const assistants = assistantTurns(turns)
  assert.equal(assistants.length, 2, '一问一答应是两轮助手消息')
  assert.equal(workflowBlocks(assistants[0]).length, 0, '旧轮不应再留等待卡')
  assert.equal(workflowBlocks(assistants[1]).length, 1, '续跑轮保留唯一一张执行卡')
  assert.equal(assistants[1].continuation, true, '续跑轮必须是续作轮')
})

test('续跑：同一张卡带完整节点表，仍看得到「询问实现语言」', () => {
  const turns = turnsFromEvents(fixture())
  const card = workflowBlocks(assistantTurns(turns)[1])[0]?.workflow
  assert.ok(card, '续跑轮应有工作流卡')
  const codes = card.nodes.map((node: any) => node.code)
  assert.deepEqual(codes.slice(0, 3), ['cond_lang', 'ask_language', 'play_design'])
  const ask = card.nodes.find((node: any) => node.code === 'ask_language')
  assert.equal(ask.status, 'SUCCESS', '询问节点在续跑卡里应已结束，而不是消失')
  assert.equal(card.nodes.some((node: any) => node.status === 'WAITING_REPLY'), false)
})

test('续跑：卡片不再带可点选项（旧选项已随旧卡清掉）', () => {
  const turns = turnsFromEvents(fixture())
  for (const turn of assistantTurns(turns)) {
    for (const block of workflowBlocks(turn)) {
      assert.equal(block.workflow.choice, undefined, '续跑后不应还有可点选项')
    }
  }
})

test('新开运行并跳过询问时，旧等待卡不许被收走', () => {
  const events = fixture()
  const resumed = events.find((event) => event.seq === 9)
  const card = JSON.parse(resumed.contentText)
  // 模拟「这句回复被当成新问题、新运行直接跳过询问」：询问节点变 SKIPPED
  card.nodes = card.nodes.map((node: any) =>
    node.code === 'ask_language' ? { ...node, status: 'SKIPPED', note: '已跳过' } : node
  )
  resumed.contentText = JSON.stringify(card)
  const turns = turnsFromEvents(events)
  const assistants = assistantTurns(turns)
  assert.equal(workflowBlocks(assistants[0]).length, 1, '不是同一条运行续跑，旧卡必须留下')
})

test('单专家续作轮（没有工作流卡）不被接管逻辑影响', () => {
  const turns = turnsFromEvents([
    { seq: 1, eventType: 'user', contentText: '帮我写个函数' },
    {
      seq: 2,
      eventType: 'participant',
      contentText: JSON.stringify({ id: 'p-0-agent-9', name: '后端专家', kind: 'agent', depth: 0, begin: true })
    },
    { seq: 3, eventType: 'assistant', contentText: '用什么语言？' }
  ])
  const assistants = assistantTurns(turns)
  assert.equal(assistants.length, 1)
  assert.equal(workflowBlocks(assistants[0]).length, 0)
  assert.ok((assistants[0].blocks || []).length > 0, '专家块必须原样保留')
})
