/**
 * 时间展示格式化的单测。所属域：前端基础设施。
 *
 * 锁两件事：① 毫秒与 `T` 必须被裁掉（列表列宽与折行的直接诱因）；
 * ② 空值与异常输入不能抛错，统一回落占位符。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { formatDateTime, formatMinute } from '../src/utils/datetime.ts'

test('秒级：去掉 T 与毫秒', () => {
  assert.equal(formatDateTime('2026-11-08T04:58:12.123'), '2026-11-08 04:58:12')
  assert.equal(formatDateTime('2026-11-08T04:58:12'), '2026-11-08 04:58:12')
  // 后端已用空格分隔、带毫秒的形态同样处理
  assert.equal(formatDateTime('2026-11-08 04:58:12.987'), '2026-11-08 04:58:12')
})

test('秒级：空值与非标准文本不抛错', () => {
  assert.equal(formatDateTime(''), '—')
  assert.equal(formatDateTime(undefined), '—')
  assert.equal(formatDateTime(null), '—')
  // 只有日期的文本原样保留
  assert.equal(formatDateTime('2026-11-08'), '2026-11-08')
})

test('分钟级：裁到分钟，供列宽紧张处使用', () => {
  assert.equal(formatMinute('2026-11-08T04:58:12.123'), '2026-11-08 04:58')
  assert.equal(formatMinute(undefined), '—')
})
