/**
 * 工作流卡节点展示口径单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) type=AGENT 才标 Agent；
 * 2) 只有 type=TOOL 才出工具胶囊，AGENT 即使有名字也不冒充工具调用；
 * 3) 胶囊文案随真实状态变化，单次不写次数；
 * 4) 同名工具合并成一条并带真实次数，详情按次列出结果（对齐方案参与者卡）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { TrialWorkflowNodeView } from '../src/types/runtime.ts'
import {
  aggregateWorkflowToolPills,
  isWorkflowAgentNode,
  workflowNodeToolLabel,
  workflowNodeToolPill
} from '../src/components/runtime/workbench/workbenchWorkflowNode.ts'

/** 拼一份节点快照，缺省字段给空串，避免用例里重复写样板。 */
function node(partial: Partial<TrialWorkflowNodeView>): TrialWorkflowNodeView {
  return {
    code: partial.code ?? '',
    name: partial.name ?? '',
    type: partial.type ?? '',
    status: partial.status ?? 'PENDING',
    note: partial.note
  }
}

test('type=AGENT 才标明 Agent', () => {
  assert.equal(isWorkflowAgentNode(node({ type: 'AGENT', name: '知识问答' })), true)
  assert.equal(isWorkflowAgentNode(node({ type: 'agent', name: '知识问答' })), true)
  assert.equal(isWorkflowAgentNode(node({ type: 'TOOL', name: '检索' })), false)
  assert.equal(isWorkflowAgentNode(node({ type: 'CONDITION', name: '分支' })), false)
  assert.equal(isWorkflowAgentNode(undefined), false)
})

test('只有 TOOL 节点出工具胶囊；AGENT 不出（快照没有内部工具列表）', () => {
  assert.equal(workflowNodeToolPill(node({ type: 'AGENT', name: '知识问答', status: 'SUCCESS' })), null)
  const pill = workflowNodeToolPill(node({ type: 'TOOL', code: 'kb_search', name: '知识检索', status: 'RUNNING' }))
  assert.ok(pill)
  assert.equal(pill.key, 'kb_search')
  assert.equal(pill.name, '知识检索')
  assert.equal(pill.state, 'running')
})

test('TOOL 胶囊文案跟真实状态，不编造调用次数，也不露出英文函数名', () => {
  const base = { type: 'TOOL', code: 't1', name: 'searchKnowledgeBase' }
  const running = workflowNodeToolPill(node({ ...base, status: 'RUNNING' }))!
  assert.equal(workflowNodeToolLabel(running, 'RUNNING', '知识库检索'), '正在调用【知识库检索】…')
  assert.equal(workflowNodeToolLabel(running, 'RUNNING'), '正在调用【工具】…')
  const done = workflowNodeToolPill(node({ ...base, status: 'SUCCESS' }))!
  assert.equal(workflowNodeToolLabel(done, 'SUCCESS', '知识库检索'), '已调用【知识库检索】')
  assert.equal(workflowNodeToolLabel(done, 'SUCCESS'), '已调用【工具】')
  assert.equal(
    workflowNodeToolLabel(workflowNodeToolPill(node({ ...base, status: 'FAILED' }))!, 'FAILED', '知识库检索'),
    '【知识库检索】：调用失败'
  )
  assert.equal(
    workflowNodeToolLabel(workflowNodeToolPill(node({ ...base, status: 'PENDING' }))!, 'PENDING'),
    '将调用【工具】'
  )
  const named = workflowNodeToolPill(node({ type: 'TOOL', code: 'kb', name: '知识检索', status: 'SUCCESS' }))!
  assert.equal(workflowNodeToolLabel(named, 'SUCCESS'), '已调用【知识检索】')
})

test('同名工具合并成一条并带真实次数，详情按次列出每次结果', () => {
  const pills = aggregateWorkflowToolPills([
    { name: 'workspaceReadFile', content: '第 1 份内容', state: 'done' },
    { name: 'workspaceReadFile', content: '第 2 份内容', state: 'done' },
    { name: 'workspaceReadFile', content: '第 3 份内容', state: 'done' }
  ])
  assert.equal(pills.length, 1, '同名工具只应一条')
  assert.equal(pills[0].total, 3)
  assert.equal(pills[0].state, 'done')
  // 与方案参与者卡同一套计数口径：单次不写，多次写「（N 次）」
  assert.equal(workflowNodeToolLabel(pills[0], 'SUCCESS', '工作区读取'), '已调用【工作区读取】（3 次）')
  // 详情不能只剩最后一次，每一次的结果都要在
  const detail = pills[0].detail || ''
  assert.equal(detail.includes('第 1 次'), true)
  assert.equal(detail.includes('第 3 次'), true)
  assert.equal(detail.includes('第 1 份内容'), true)
  assert.equal(detail.includes('第 3 份内容'), true)
})

test('只有一次调用时不写「（1 次）」', () => {
  const pills = aggregateWorkflowToolPills([{ name: 'workspaceReadFile', content: 'a', state: 'done' }])
  assert.equal(pills[0].total, 1)
  assert.equal(workflowNodeToolLabel(pills[0], 'SUCCESS', '工作区读取'), '已调用【工作区读取】')
})

test('思考步不是工具调用，不参与次数', () => {
  const pills = aggregateWorkflowToolPills([
    { type: 'REASONING', content: '先看目录里有什么', state: 'done' },
    { type: 'TOOL', name: 'workspaceReadFile', content: 'a', state: 'done' }
  ])
  assert.equal(pills.length, 1, '思考步不该冒出一颗胶囊')
  assert.equal(pills[0].name, 'workspaceReadFile')
  assert.equal(pills[0].total, 1)
})

test('还有一路在跑时整条 running 并带次数，不并列多条蓝条', () => {
  const pills = aggregateWorkflowToolPills([
    { name: 'workspaceReadFile', content: 'a', state: 'done' },
    { name: 'workspaceReadFile', content: '', state: 'running' }
  ])
  assert.equal(pills.length, 1)
  assert.equal(pills[0].state, 'running')
  assert.equal(pills[0].total, 2)
  assert.equal(workflowNodeToolLabel(pills[0], 'RUNNING', '工作区读取'), '正在调用【工作区读取】（2）…')
})

test('合并后混有失败：写清成功 / 失败次数', () => {
  const pills = aggregateWorkflowToolPills([
    { name: 'workspaceReadFile', content: 'ok-1', state: 'done' },
    { name: 'workspaceReadFile', content: '文件不存在', state: 'error' },
    { name: 'workspaceReadFile', content: 'ok-2', state: 'done' }
  ])
  assert.equal(pills[0].state, 'error')
  assert.equal(pills[0].ok, 2)
  assert.equal(pills[0].failed, 1)
  assert.equal(workflowNodeToolLabel(pills[0], 'SUCCESS', '工作区读取'), '【工作区读取】：成功 2 / 失败 1')
})

test('没有任何结果摘录时不出现「详情」入口，但次数照记', () => {
  const pills = aggregateWorkflowToolPills([
    { name: 'workspaceReadFile', content: '', state: 'done' },
    { name: 'workspaceReadFile', content: '', state: 'done' }
  ])
  assert.equal(pills[0].total, 2)
  assert.equal(pills[0].detail, '')
})

test('整轮已中断：残留 running 落到已完成，不再转圈', () => {
  const pills = aggregateWorkflowToolPills(
    [
      { name: 'workspaceReadFile', content: '', state: 'running' },
      { name: 'workspaceReadFile', content: 'a', state: 'running' }
    ],
    'aborted'
  )
  assert.equal(pills[0].state, 'done')
  assert.equal(pills[0].total, 2)
  // 步级胶囊按自身三态翻译成 SUCCESS，句子落到「已调用」并带次数
  assert.equal(workflowNodeToolLabel(pills[0], 'SUCCESS', '工作区读取'), '已调用【工作区读取】（2 次）')
})

test('不同工具名分开成多条，顺序按首次出现', () => {
  const pills = aggregateWorkflowToolPills([
    { name: 'workspaceReadFile', content: 'a', state: 'done' },
    { name: 'webSearch', content: 'b', state: 'done' },
    { name: 'workspaceReadFile', content: 'c', state: 'done' }
  ])
  assert.deepEqual(pills.map((pill) => pill.name), ['workspaceReadFile', 'webSearch'])
  assert.equal(pills[0].total, 2)
  assert.equal(pills[1].total, 1)
})
