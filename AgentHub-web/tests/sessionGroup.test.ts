/**
 * 试跑左栏「历史会话」分桶的窄测。所属域：runtime（前端）。
 *
 * 覆盖边界：今日 / 7天内 / 30天内 / 更早，含 7 天与 30 天的临界、跨月与脏时间。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  groupSessionsByDay,
  groupSessionsForWorkbench,
  parseSessionTime,
  startOfLocalDay
} from '../src/components/runtime/trial/sessionGroup.ts'

/** 造一条只有时间的最小会话行。 */
function row(id: number, stamp: string) {
  return { id, startTime: stamp, updateTime: stamp }
}

/** 以某个本地日零点为基准，往前推 n 天再取一个「当天中午」的时间字符串。 */
function daysAgo(now: Date, n: number) {
  const d = startOfLocalDay(now)
  d.setDate(d.getDate() - n)
  d.setHours(12, 0, 0, 0)
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 12:00:00`
}

/** 固定「今天」为 2026-03-15 10:00，避免用例随运行日期漂移。 */
const NOW = new Date(2026, 2, 15, 10, 0, 0)

test('分桶：今日 / 7天内 / 30天内 / 更早 各自落到正确的组', () => {
  const groups = groupSessionsByDay(
    [row(1, daysAgo(NOW, 0)), row(2, daysAgo(NOW, 3)), row(3, daysAgo(NOW, 20)), row(4, daysAgo(NOW, 200))],
    NOW
  )
  assert.deepEqual(
    groups.map((g) => g.label),
    ['今日', '7天内', '30天内', '更早']
  )
  assert.deepEqual(
    groups.map((g) => g.items.map((i) => i.id)),
    [[1], [2], [3], [4]]
  )
})

test('分桶：7 天与 30 天的临界值落点', () => {
  const groups = groupSessionsByDay(
    [row(1, daysAgo(NOW, 1)), row(2, daysAgo(NOW, 6)), row(3, daysAgo(NOW, 7)), row(4, daysAgo(NOW, 29)), row(5, daysAgo(NOW, 30))],
    NOW
  )
  const byLabel = new Map(groups.map((g) => [g.label, g.items.map((i) => i.id)]))
  // 昨日到 6 天前属于「7天内」；正好 7 天前已跌到「30天内」
  assert.deepEqual(byLabel.get('7天内'), [1, 2])
  assert.deepEqual(byLabel.get('30天内'), [3, 4])
  assert.deepEqual(byLabel.get('更早'), [5])
})

test('分桶：空组不出现，顺序固定', () => {
  const groups = groupSessionsByDay([row(1, daysAgo(NOW, 0)), row(2, daysAgo(NOW, 100))], NOW)
  assert.deepEqual(
    groups.map((g) => g.label),
    ['今日', '更早']
  )
})

test('分桶：脏时间与缺失时间沉到「更早」，不会被误判成今天', () => {
  const groups = groupSessionsByDay(
    [
      { id: 1 },
      { id: 2, startTime: '', updateTime: '' },
      { id: 3, startTime: 'not-a-date' },
      row(4, daysAgo(NOW, 0))
    ],
    NOW
  )
  const byLabel = new Map(groups.map((g) => [g.label, g.items.map((i) => i.id)]))
  assert.deepEqual(byLabel.get('更早'), [1, 2, 3])
  assert.deepEqual(byLabel.get('今日'), [4])
})

test('分桶：优先用 updateTime（最后活动时间）而不是 startTime', () => {
  // 10 天前创建、今天刚聊过：应按「今日」展示，否则刚用的会话会掉到旧组
  const groups = groupSessionsByDay([{ id: 9, startTime: daysAgo(NOW, 10), updateTime: daysAgo(NOW, 0) }], NOW)
  assert.deepEqual(
    groups.map((g) => g.label),
    ['今日']
  )
})

test('分桶：组内保持传入顺序（父组件已按时间倒序）', () => {
  const groups = groupSessionsByDay([row(5, daysAgo(NOW, 3)), row(4, daysAgo(NOW, 2)), row(6, daysAgo(NOW, 1))], NOW)
  assert.deepEqual(groups[0].items.map((i) => i.id), [5, 4, 6])
})

test('时间解析：支持空格分隔的本地时间，非法值返回 0', () => {
  assert.equal(parseSessionTime('2026-03-15 12:00:00'), new Date(2026, 2, 15, 12, 0, 0).getTime())
  assert.equal(parseSessionTime(''), 0)
  assert.equal(parseSessionTime(undefined), 0)
  assert.equal(parseSessionTime('nope'), 0)
})

test('工作台分桶：今天 / 7天内 / 更早；原 30 天内并入更早且不丢会话', () => {
  const groups = groupSessionsForWorkbench(
    [row(1, daysAgo(NOW, 0)), row(2, daysAgo(NOW, 3)), row(3, daysAgo(NOW, 20)), row(4, daysAgo(NOW, 200))],
    NOW
  )
  assert.deepEqual(
    groups.map((g) => g.label),
    ['今天', '7天内', '更早']
  )
  assert.deepEqual(
    groups.map((g) => g.items.map((i) => i.id)),
    [[1], [2], [3, 4]]
  )
})
