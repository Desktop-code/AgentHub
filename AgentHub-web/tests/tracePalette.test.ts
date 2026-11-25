/**
 * Trace 类型配色窄测（node:test，无浏览器）。所属域：前端 obs。
 *
 * 守住验收点「列表左侧色条与上方拓扑同类型同色」：
 * 拓扑与列表都从本模块取色，因此这里把四个主类型的色值钉死，
 * 任何人另起一套色值都会先在这里失败。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/tracePalette.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { traceTypeAccent, traceTypePalette, withAlpha } from '../src/utils/tracePalette.ts'

test('四个主类型色值与产品口径一致（拓扑 / 列表共用）', () => {
  assert.deepEqual(traceTypePalette('ROUTE'), { border: '#A5B4FC', fill: '#F8FAFF' })
  assert.deepEqual(traceTypePalette('AGENT'), { border: '#93C5FD', fill: '#F8FBFF' })
  assert.deepEqual(traceTypePalette('LLM'), { border: '#FCD34D', fill: '#FFFCF5' })
  assert.deepEqual(traceTypePalette('TOOL'), { border: '#6EE7B7', fill: '#F6FDF9' })
})

test('未收录类型回中性灰，不抢主色', () => {
  const neutral = { border: '#CBD5E1', fill: '#FFFFFF' }
  for (const spanType of ['SYSTEM', 'VISION', 'RETRIEVE', 'PLAN', null, undefined, '']) {
    assert.deepEqual(traceTypePalette(spanType), neutral, `类型 ${String(spanType)} 应走中性灰`)
  }
})

test('四种主类型互不同色（避免列表里分不出节点种类）', () => {
  const borders = ['ROUTE', 'AGENT', 'LLM', 'TOOL'].map((t) => traceTypePalette(t).border)
  assert.equal(new Set(borders).size, borders.length)
})

test('列表色条：与拓扑同色相但半透明', () => {
  // 色条不是纯 hex，而是同色相的 rgba；卡片底色由样式表固定为白，不再取 fill
  assert.equal(traceTypeAccent('TOOL'), 'rgba(110, 231, 183, 0.6)')
  assert.equal(traceTypeAccent('ROUTE'), 'rgba(165, 180, 252, 0.6)')
  assert.equal(traceTypeAccent('AGENT'), 'rgba(147, 197, 253, 0.6)')
  assert.equal(traceTypeAccent('LLM'), 'rgba(252, 211, 77, 0.6)')
  // 未收录类型同样半透明，不会突然变实色
  assert.equal(traceTypeAccent('SYSTEM'), 'rgba(203, 213, 225, 0.6)')
})

test('withAlpha：6 位 hex 正确拆通道，异常输入原样返回', () => {
  assert.equal(withAlpha('#FF0000', 0.5), 'rgba(255, 0, 0, 0.5)')
  assert.equal(withAlpha('#000000', 1), 'rgba(0, 0, 0, 1)')
  // 非法输入不能让整条样式失效
  assert.equal(withAlpha('red', 0.5), 'red')
  assert.equal(withAlpha('#FFF', 0.5), '#FFF')
})
