/**
 * 输入区线路徽标口径单测（本地 tests/，不进仓）。所属域：runtime（前端）。
 *
 * 覆盖用户手点的坑：「已指定××」曾在普通续聊 / 点选续作后一直挂着，
 * 让人以为下一句被钉死、不再走入口。口径是**只有本次 @ 才写「已指定××」**。
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  AUTO_ROUTE_BADGE,
  INVALID_ROUTE_BADGE,
  workbenchRouteBadge,
  workbenchRouteBadgeTitle
} from '../src/utils/workbenchRouteBadge.ts'

describe('workbenchRouteBadge', () => {
  it('本次 @ 了某位：显示「已指定××」', () => {
    assert.equal(workbenchRouteBadge('后端技术专家'), '已指定后端技术专家')
  })

  it('没 @：即使会话还沿用上一位专家，也只显示「自动匹配专家」', () => {
    // 入参只有 @ 的名字与失效标记：sticky 的专家名不再参与判定
    assert.equal(workbenchRouteBadge(''), AUTO_ROUTE_BADGE)
    assert.equal(workbenchRouteBadge(undefined), AUTO_ROUTE_BADGE)
    assert.equal(workbenchRouteBadge(null), AUTO_ROUTE_BADGE)
    // 只有空白的名字同样不算指定（@ 面板取消后可能留下空白）
    assert.equal(workbenchRouteBadge('   '), AUTO_ROUTE_BADGE)
  })

  it('没有 @、但沿用线路已失效：如实写「线路已失效」，不冒充指定', () => {
    assert.equal(workbenchRouteBadge('', true), INVALID_ROUTE_BADGE)
  })

  it('@ 优先于失效标记：用户刚指定的人就是要执行的那位', () => {
    assert.equal(workbenchRouteBadge('计费专家', true), '已指定计费专家')
  })

  it('悬停提示与文案同口径', () => {
    assert.equal(workbenchRouteBadgeTitle(AUTO_ROUTE_BADGE), '网关模式：由接待方案自动匹配专家')
    assert.ok(workbenchRouteBadgeTitle(INVALID_ROUTE_BADGE).includes('失效'))
    const specified = workbenchRouteBadgeTitle('已指定计费专家')
    assert.ok(specified.includes('计费专家'))
    assert.equal(specified.includes('已指定'), false, '提示里不重复「已指定」三字')
  })
})
