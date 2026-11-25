/**
 * 定高窗口化计算的单测。所属域：前端壳。
 *
 * 锁四件事：
 * 1) 窗口区间要覆盖「视口内可见行 + 上下缓冲」，且永不越界；
 * 2) 占位总高度要扣掉最后一行的尾隙（否则列表底部多一段空白）；
 * 3) 参数非法（行数 0 / 行距未实测）时返回空窗口而不是抛错或 NaN；
 * 4) `scrollTopForIndex` 能把任意行夹到合法滚动范围（深链/飞卡定位依赖它）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { computeVirtualWindow, scrollTopForIndex } from '../src/composables/virtualWindow.ts'

const ROW = 100
const GAP = 10

test('顶部：渲染窗口覆盖视口并带上缓冲，偏移为 0', () => {
  const win = computeVirtualWindow({
    scrollTop: 0,
    viewport: 500,
    rowSize: ROW,
    gap: GAP,
    count: 1000,
    overscan: 4
  })
  assert.equal(win.start, 0)
  // 视口 5 行 + 1 + 下缓冲 4
  assert.equal(win.end, 10)
  assert.equal(win.offset, 0)
  assert.equal(win.total, 1000 * ROW - GAP)
})

test('中段：start 与 offset 一致（translateY 不会错位）', () => {
  const win = computeVirtualWindow({
    scrollTop: 5000,
    viewport: 500,
    rowSize: ROW,
    gap: GAP,
    count: 1000,
    overscan: 4
  })
  assert.equal(win.start, 46)
  assert.equal(win.offset, win.start * ROW)
  // 窗口必须覆盖视口首行（50）与末行（55）
  assert.ok(win.start <= 50)
  assert.ok(win.end >= 56)
})

test('底部：end 被夹到 count，不越界', () => {
  const win = computeVirtualWindow({
    scrollTop: 1000 * ROW,
    viewport: 500,
    rowSize: ROW,
    gap: GAP,
    count: 1000,
    overscan: 6
  })
  assert.equal(win.end, 1000)
  assert.ok(win.start < 1000)
  // 夹紧后仍要能看到最后一行
  assert.equal(win.end - win.start >= 1, true)
})

test('行数不足一屏：全部渲染且总高度只算实际行数', () => {
  const win = computeVirtualWindow({
    scrollTop: 0,
    viewport: 500,
    rowSize: ROW,
    gap: GAP,
    count: 3
  })
  assert.deepEqual(win, { start: 0, end: 3, offset: 0, total: 3 * ROW - GAP })
})

test('参数非法：行数 0 / 行距未实测，返回空窗口且不出现 NaN', () => {
  assert.deepEqual(computeVirtualWindow({ scrollTop: 0, viewport: 500, rowSize: ROW, count: 0 }), {
    start: 0,
    end: 0,
    offset: 0,
    total: 0
  })
  assert.deepEqual(computeVirtualWindow({ scrollTop: 0, viewport: 500, rowSize: 0, count: 10 }), {
    start: 0,
    end: 0,
    offset: 0,
    total: 0
  })
  // 负滚动（回弹）被夹到 0，不会算出负下标
  const bounce = computeVirtualWindow({ scrollTop: -300, viewport: 500, rowSize: ROW, count: 50 })
  assert.equal(bounce.start, 0)
  assert.ok(Number.isFinite(bounce.offset))
})

test('视口为 0（图层隐藏）时仍渲染出内容，切层后不空一拍', () => {
  const win = computeVirtualWindow({ scrollTop: 900, viewport: 0, rowSize: ROW, count: 100 })
  assert.ok(win.end - win.start >= 1)
  // 窗口仍要盖住当前滚动位置对应的行
  assert.ok(win.start <= 9 && win.end > 9)
})

test('scrollTopForIndex：任意行都能定位且夹在合法范围', () => {
  const base = { viewport: 500, rowSize: ROW, gap: GAP, count: 1000 }
  // 顶部行：0
  assert.equal(scrollTopForIndex(0, base), 0)
  // 中段行：目标行大致落在视口中间
  const middle = scrollTopForIndex(500, base)
  assert.ok(middle > 0 && middle < 1000 * ROW)
  assert.ok(Math.abs(middle - (500 * ROW - (500 - ROW) / 2)) < 1)
  // 末尾行：夹到最大滚动位置，不会滚出内容
  const maxScroll = 1000 * ROW - GAP - 500
  assert.equal(scrollTopForIndex(999, base), maxScroll)
  assert.equal(scrollTopForIndex(5000, base), maxScroll)
  // 非法参数不抛错
  assert.equal(scrollTopForIndex(3, { viewport: 500, rowSize: 0, count: 10 }), 0)
})
