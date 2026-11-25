/**
 * 左栏历史会话模糊搜索纯函数单测（本地 tests/，不进仓）。
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  filterSessionGroups,
  matchesSessionKeyword,
  splitSessionKeyword
} from '../src/components/runtime/trial/sessionSearch.ts'

/** 造一条最小会话；只带搜索会读到的展示字段 */
function session(id: number, title: string, preview = '') {
  return { id, sessionTitle: title, lastMsgPreview: preview }
}

const groups = [
  { label: '今天', items: [session(1, '放假通知起草', '写一份国庆放假通知'), session(2, '报销流程咨询', '差旅费怎么报销')] },
  { label: '7天内', items: [session(3, 'Billing 账单核对', '核对九月账单金额')] }
]

describe('splitSessionKeyword', () => {
  it('按空白切词并统一小写', () => {
    assert.deepEqual(splitSessionKeyword('  Billing  账单 '), ['billing', '账单'])
    assert.deepEqual(splitSessionKeyword(''), [])
  })
})

describe('matchesSessionKeyword', () => {
  it('多词之间是与关系，且大小写不敏感', () => {
    assert.equal(matchesSessionKeyword('Billing 账单核对', ['billing', '账单']), true)
    assert.equal(matchesSessionKeyword('Billing 账单核对', ['billing', '报销']), false)
  })

  it('空检索词恒命中', () => {
    assert.equal(matchesSessionKeyword('任意', []), true)
  })
})

describe('filterSessionGroups', () => {
  const textOf = (item: { sessionTitle?: string; lastMsgPreview?: string }) =>
    `${item.sessionTitle || ''} ${item.lastMsgPreview || ''}`

  it('按标题模糊命中，并丢掉变空的组', () => {
    const hit = filterSessionGroups(groups, textOf, '放假')
    assert.equal(hit.length, 1)
    assert.equal(hit[0].label, '今天')
    assert.deepEqual(hit[0].items.map((item) => item.id), [1])
  })

  it('按预览内容也能搜到（用户常按正文回忆会话）', () => {
    const hit = filterSessionGroups(groups, textOf, '差旅费')
    assert.deepEqual(hit[0].items.map((item) => item.id), [2])
  })

  it('多词命中同一行', () => {
    const hit = filterSessionGroups(groups, textOf, 'billing 九月')
    assert.deepEqual(hit[0].items.map((item) => item.id), [3])
  })

  it('无命中返回空数组；空关键字原样返回', () => {
    assert.deepEqual(filterSessionGroups(groups, textOf, '不存在的词'), [])
    assert.equal(filterSessionGroups(groups, textOf, '   '), groups)
  })
})
