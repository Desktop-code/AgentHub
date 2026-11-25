/**
 * 树表懒加载投影单测。所属域：前端共用。
 * 锁住：表格行不含子孙、展开只给下一层、刷新还原先祖先后子孙。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  indexTreeChildren,
  lazyChildrenOf,
  sortIdsByTreeDepth,
  toLazyTreeRow
} from '../src/composables/lazyTreeTable.ts'

interface Node {
  id: number
  name: string
  children?: Node[]
}

const tree: Node[] = [
  {
    id: 1,
    name: '智能体中心',
    children: [
      {
        id: 2,
        name: 'Agent 管理',
        children: [
          { id: 3, name: '新增' },
          { id: 4, name: '编辑' }
        ]
      },
      { id: 5, name: '运行' }
    ]
  },
  { id: 6, name: '管理' }
]

test('根行去掉 children，只标记还能展开', () => {
  const roots = tree.map((node) => toLazyTreeRow(node))
  assert.equal(roots.length, 2)
  assert.equal(roots[0].hasChildren, true)
  assert.equal('children' in roots[0], false)
  assert.equal(roots[1].hasChildren, false)
  assert.equal(roots[0].name, '智能体中心')
})

test('展开只返回直接子节点，按钮留在再下一层', () => {
  const index = indexTreeChildren(tree)
  const level1 = lazyChildrenOf(index, 1)
  assert.deepEqual(level1.map((row) => row.id), [2, 5])
  assert.equal(level1[0].hasChildren, true)
  assert.equal(level1[1].hasChildren, false)
  assert.equal('children' in level1[0], false)
  const buttons = lazyChildrenOf(index, 2)
  assert.deepEqual(buttons.map((row) => row.name), ['新增', '编辑'])
  assert.deepEqual(lazyChildrenOf(index, 5), [])
})

test('还原展开时祖先排在子孙前面', () => {
  assert.deepEqual(sortIdsByTreeDepth(tree, [4, 1, 2]), [1, 2, 4])
})
