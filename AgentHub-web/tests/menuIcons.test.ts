/**
 * 菜单图标解析契约的窄测。所属域：前端壳。
 *
 * <p>锁三件事：
 * 1) 同一图标名必须返回**同一个组件引用**（模板每次渲染都会调它，返回新对象会让图标反复重挂）；
 * 2) 空值 / 未登记名称走兜底，不返回 undefined（否则图标位空白）；
 * 3) 「首屏常驻图标」与「需要懒加载的图标」划分正确，预载只对后者发请求。
 *
 * 运行：node --experimental-strip-types --test tests/menuIcons.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  isLazyMenuIcon,
  preloadMenuIcons,
  resolveMenuIcon
} from '../src/components/menu/menuIcons.ts'

test('常驻图标：同一名字返回同一引用（不重挂）', () => {
  const first = resolveMenuIcon('User')
  assert.ok(first)
  assert.equal(resolveMenuIcon('User'), first)
  // 前后带空白也认同一个名字
  assert.equal(resolveMenuIcon(' User '), first)
})

test('空值回退兜底图标；未登记名称也一定会返回组件（可渲染，不空白）', () => {
  const fallback = resolveMenuIcon()
  assert.ok(fallback)
  assert.equal(resolveMenuIcon(undefined), fallback)
  assert.equal(resolveMenuIcon(''), fallback)
  // 从未登记过的名字走懒加载分支，同样返回稳定组件；加载失败时由 loader 内部回退兜底图标
  const unknown = resolveMenuIcon('NotAnIconName')
  assert.ok(unknown)
  assert.equal(resolveMenuIcon('NotAnIconName'), unknown)
  assert.equal(isLazyMenuIcon('NotAnIconName'), true)
})

test('常驻表覆盖默认菜单种子里的图标名（抽查，防退化）', () => {
  const seeded = [
    'Setting',
    'User',
    'UserFilled',
    'Menu',
    'OfficeBuilding',
    'Connection',
    'Cpu',
    'Document',
    'DataAnalysis',
    'Coin',
    'Collection',
    'Finished',
    'Notebook',
    'Guide',
    'Monitor',
    'Tickets',
    'Link',
    'Aim',
    'MagicStick',
    'Management'
  ]
  for (const name of seeded) {
    assert.equal(isLazyMenuIcon(name), false, `${name} 应在首屏常驻表里`)
  }
})

test('候选集独有的图标判为懒加载；空值不算懒加载', () => {
  assert.equal(isLazyMenuIcon('PieChart'), true)
  assert.equal(isLazyMenuIcon('Trophy'), true)
  assert.equal(isLazyMenuIcon(''), false)
  assert.equal(isLazyMenuIcon(undefined), false)
})

test('全为常驻图标时不发候选集请求', () => {
  assert.equal(preloadMenuIcons(['User', 'Setting', '']), undefined)
  assert.equal(preloadMenuIcons([]), undefined)
})

test('懒加载名字只创建一次异步组件（引用稳定）', () => {
  const first = resolveMenuIcon('PieChart')
  assert.ok(first)
  assert.equal(resolveMenuIcon('PieChart'), first)
})
