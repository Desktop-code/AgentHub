/**
 * Markdown 渲染槽缓存的行为测试（node:test，无浏览器）。所属域：前端 runtime。
 *
 * 覆盖验收点：
 * 1) 缓存结果与无缓存渲染逐字一致（不改变算法语义）；
 * 2) 同一槽内容变化后重算，绝不返回旧 HTML；
 * 3) 流式与终态分开缓存，互不污染（半截表不能被终态 HTML 顶掉，反之亦然）；
 * 4) 槽位不同不串用；超过容量淘汰后重算仍正确。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/markdownMemo.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  clearMarkdownMemo,
  renderMarkdownMemo,
  renderSimpleMarkdown,
  renderStreamingMarkdown
} from '../src/utils/simpleMarkdown.ts'

/** 一张带表与列表的样本，覆盖终态多条分支 */
const SAMPLE = [
  '# 标题',
  '',
  '说明一句 **加粗**。',
  '',
  '- 一',
  '- 二',
  '',
  '| a | b |',
  '| --- | --- |',
  '| 1 | 2 |'
].join('\n')

test('缓存结果与无缓存渲染逐字一致', () => {
  clearMarkdownMemo()
  assert.equal(renderMarkdownMemo('slot-a', SAMPLE, false), renderSimpleMarkdown(SAMPLE))
  // 第二次命中缓存，结果必须仍然一致
  assert.equal(renderMarkdownMemo('slot-a', SAMPLE, false), renderSimpleMarkdown(SAMPLE))
})

test('同一槽内容变化即重算，不回旧值', () => {
  clearMarkdownMemo()
  const first = renderMarkdownMemo('slot-b', '第一段', false)
  const second = renderMarkdownMemo('slot-b', '第一段后面还有字', false)
  assert.equal(first, renderSimpleMarkdown('第一段'))
  assert.equal(second, renderSimpleMarkdown('第一段后面还有字'))
})

test('长度相同但内容不同也要重算（签名含哈希）', () => {
  clearMarkdownMemo()
  assert.equal(renderMarkdownMemo('slot-c', 'abcd', false), renderSimpleMarkdown('abcd'))
  // 同长度、换一个字：长度相同，必须靠哈希区分出来
  assert.equal(renderMarkdownMemo('slot-c', 'abce', false), renderSimpleMarkdown('abce'))
})

test('流式与终态分开缓存，互不污染', () => {
  clearMarkdownMemo()
  // 半截表：流式给渐进占位，终态把半行当普通正文，两者 HTML 必须各自正确
  const partial = '| a | b |\n| --- | --- |\n| 1 |'
  assert.equal(renderMarkdownMemo('slot-d', partial, true), renderStreamingMarkdown(partial))
  assert.equal(renderMarkdownMemo('slot-d', partial, false), renderSimpleMarkdown(partial))
  // 反序再取一次，两个方向都不串
  assert.equal(renderMarkdownMemo('slot-d', partial, true), renderStreamingMarkdown(partial))
  assert.equal(renderMarkdownMemo('slot-d', partial, false), renderSimpleMarkdown(partial))
})

test('槽位不同不串用', () => {
  clearMarkdownMemo()
  assert.equal(renderMarkdownMemo('block:1', 'AAA', false), renderSimpleMarkdown('AAA'))
  assert.equal(renderMarkdownMemo('block:2', 'BBB', false), renderSimpleMarkdown('BBB'))
  assert.equal(renderMarkdownMemo('block:1', 'AAA', false), renderSimpleMarkdown('AAA'))
})

test('超过容量淘汰后重算仍正确', () => {
  clearMarkdownMemo()
  for (let i = 0; i < 600; i += 1) {
    assert.equal(renderMarkdownMemo(`bulk-${i}`, `第 ${i} 段`, false), renderSimpleMarkdown(`第 ${i} 段`))
  }
  // 最早的槽已被淘汰，再取一次必须重新算出同样的结果
  assert.equal(renderMarkdownMemo('bulk-0', '第 0 段', false), renderSimpleMarkdown('第 0 段'))
})
