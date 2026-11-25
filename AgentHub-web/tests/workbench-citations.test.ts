import test from 'node:test'
import assert from 'node:assert/strict'
import {
  arrangeCitations,
  citationsFromToolSteps
} from '../src/components/runtime/workbench/workbenchCitations.ts'

/** 造一次检索的工具步骤 */
function call(hits: object[]): any[] {
  return [
    { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '{}', state: 'done' },
    { type: 'tool_result', title: '工具结果', text: JSON.stringify(hits) }
  ]
}

test('同一轮多次检索命中同一段：只出现一次，留高分', () => {
  const steps = [
    ...call([{ title: '制度A', source: 's', content: '年假五天', score: 0.5, hitBy: 'vector' }]),
    ...call([{ title: '制度A', source: 's', content: '年假五天', score: 0.9, hitBy: 'rerank' }])
  ]
  const items = citationsFromToolSteps(steps)
  assert.equal(items.length, 1)
  assert.equal(items[0].score, 0.9)
  const board = arrangeCitations(items)
  assert.equal(board.documents.length, 1)
  assert.equal(board.summary, '制度A')
})

test('无分数时保留先落卡的那次', () => {
  const steps = [
    ...call([{ title: '制度A', source: 's1', content: '年假五天', hitBy: 'v' }]),
    ...call([{ title: '制度A', source: 's2', content: '年假五天', hitBy: 'v' }])
  ]
  const items = citationsFromToolSteps(steps)
  assert.equal(items.length, 1)
  assert.equal(items[0].source, 's1')
})

test('同篇 5 段：全部列出，摘要段数与展开一致，不再有隐藏段', () => {
  const hits = [0.1, 0.2, 0.3, 0.95, 0.4].map((score, index) => ({
    title: 'zhidu-ku-合集.md', source: 's', content: `片段${index}`, score, hitBy: 'v'
  }))
  const board = arrangeCitations(citationsFromToolSteps(call(hits)))
  assert.equal(board.documents.length, 1)
  const doc = board.documents[0]
  assert.equal(doc.snippets.length, 5)
  assert.equal('hidden' in doc, false, '不应再有 hidden 计数')
  assert.deepEqual(doc.snippets.map((s) => s.content), ['片段3', '片段4', '片段2', '片段1', '片段0'])
  assert.equal(board.summary, 'zhidu-ku-合集.md · 5 段')
})

test('同篇多段都没有分数：按落卡顺序全部列出', () => {
  const hits = [0, 1, 2, 3].map((index) => ({
    title: '制度A', source: 's', content: `片段${index}`, hitBy: 'v'
  }))
  const board = arrangeCitations(citationsFromToolSteps(call(hits)))
  const doc = board.documents[0]
  assert.deepEqual(doc.snippets.map((s) => s.content), ['片段0', '片段1', '片段2', '片段3'])
  assert.equal(board.summary, '制度A · 4 段')
})

test('多篇摘要按去重后的文档数', () => {
  const steps = [
    ...call([{ title: 'A', source: 'a', content: 'x1', score: 1, hitBy: 'v' }]),
    ...call([
      { title: 'A', source: 'a', content: 'x1', score: 2, hitBy: 'v' },
      { title: 'B', source: 'b', content: 'y1', score: 1, hitBy: 'v' }
    ])
  ]
  const board = arrangeCitations(citationsFromToolSteps(steps))
  assert.equal(board.summary, '引用 · 2 篇文档')
  assert.equal(board.documents.length, 2)
  assert.equal(board.documents[0].snippets.length, 1)
})

test('多篇时每组列出该篇去重后的全部片段', () => {
  const steps = [
    ...call([
      { title: 'A', source: 'a', content: 'a1', score: 0.9, hitBy: 'v' },
      { title: 'B', source: 'b', content: 'b1', score: 0.8, hitBy: 'v' }
    ]),
    ...call([
      { title: 'A', source: 'a', content: 'a2', score: 0.7, hitBy: 'v' },
      { title: 'A', source: 'a', content: 'a1', score: 0.6, hitBy: 'v' },
      { title: 'B', source: 'b', content: 'b2', score: 0.5, hitBy: 'v' }
    ])
  ]
  const board = arrangeCitations(citationsFromToolSteps(steps))
  assert.equal(board.summary, '引用 · 2 篇文档')
  assert.deepEqual(board.documents[0].snippets.map((s) => s.content), ['a1', 'a2'])
  assert.deepEqual(board.documents[1].snippets.map((s) => s.content), ['b1', 'b2'])
})

test('展示层收到未合并列表也去重，同一段只画一次', () => {
  const items = [
    { key: 'k1', label: '制度A', title: '制度A', content: '年假五天', source: 's', score: 0.3 },
    { key: 'k2', label: '制度A · 年假五天', title: '制度A', content: '年假五天', source: 's', score: 0.8 }
  ]
  const board = arrangeCitations(items)
  assert.equal(board.documents.length, 1)
  assert.equal(board.documents[0].snippets.length, 1)
  assert.equal(board.documents[0].snippets[0].score, 0.8)
})

test('不同文档同一段正文不误合并', () => {
  const items = [
    { key: 'k1', label: '制度A', title: '制度A', content: '同样的一段', source: 's', score: 1 },
    { key: 'k2', label: '制度B', title: '制度B', content: '同样的一段', source: 's', score: 1 }
  ]
  const board = arrangeCitations(items)
  assert.equal(board.documents.length, 2)
  assert.equal(board.summary, '引用 · 2 篇文档')
})

test('没有出处：空列表，不画引用区', () => {
  const board = arrangeCitations(citationsFromToolSteps([
    { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '{}', state: 'done' },
    { type: 'tool_result', title: '工具结果', text: '未找到相关内容。' }
  ]))
  assert.equal(board.documents.length, 0)
  assert.equal(board.summary, '')
})
