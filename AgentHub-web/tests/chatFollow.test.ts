/**
 * 对话区跟滚判定的窄测。所属域：runtime（前端展示）。
 *
 * <p>回归点：流式期「我们自己写 scrollTop」也会派发 scroll 事件，且派发时正文往往又长了。
 * 若把它当成用户上翻，跟滚会被自己关掉，整轮回答停在半截（不动鼠标就再也回不到底部）。
 *
 * 运行：node --experimental-strip-types --test tests/chatFollow.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { resolveFollowAfterScroll } from '../src/components/runtime/chatFollow.ts'

/** 默认一屏 600 高、内容 2000 高：max scrollTop = 1400 */
function base(overrides: Partial<Parameters<typeof resolveFollowAfterScroll>[0]> = {}) {
  return resolveFollowAfterScroll({
    scrollTop: 1400,
    scrollHeight: 2000,
    clientHeight: 600,
    selfScrollTop: -1,
    previous: true,
    threshold: 80,
    ...overrides
  })
}

test('滚到底：继续跟随', () => {
  assert.equal(base(), true)
})

test('阈值内也算在底部（容差 80px）', () => {
  assert.equal(base({ scrollTop: 1400 - 80 }), true)
})

test('用户上翻：停止跟滚', () => {
  assert.equal(base({ scrollTop: 600, selfScrollTop: 1400 }), false)
})

test('自己写的滚动 + 事件派发时正文已增长：保持原状态（关键回归）', () => {
  // 上一帧我们把 scrollTop 写到 1400；等事件派发时内容长到 2400，max 变 1800
  assert.equal(
    base({ scrollTop: 1400, scrollHeight: 2400, selfScrollTop: 1400, previous: true }),
    true
  )
  // 若原状态已经是「不跟」，也不能被一次自己写的滚动反过来打开
  assert.equal(
    base({ scrollTop: 1400, scrollHeight: 2400, selfScrollTop: 1400, previous: false }),
    false
  )
})

test('从未写过 scrollTop 且远离底部：判为用户上翻', () => {
  assert.equal(base({ scrollTop: 200, selfScrollTop: -1 }), false)
})

test('亚像素取整的容差内仍算自己写的', () => {
  assert.equal(base({ scrollTop: 1398, scrollHeight: 2400, selfScrollTop: 1400, previous: true }), true)
})

test('内容不足一屏（scrollTop 与 max 都是 0）：始终跟随', () => {
  assert.equal(base({ scrollTop: 0, scrollHeight: 600, clientHeight: 600 }), true)
})
