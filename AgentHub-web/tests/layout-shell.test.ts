/**
 * 布局壳契约自检（本地 tests/，不进仓）。所属域：前端布局。
 *
 * 锁死本刀硬约束：无顶栏 class、侧栏通顶宽度令牌、页签高度 ≤45、KeepAlive 协议未改。
 * 运行：在 AgentHub-web 下 `npm test`（有本地用例才跑）。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** 读仓内源文件文本。 */
function readSrc(rel: string) {
  return readFileSync(join(WEB_ROOT, rel), 'utf8')
}

describe('全局布局去顶栏侧栏通顶', () => {
  const layout = readSrc('src/views/layout/AdminLayout.vue')
  const tags = readSrc('src/views/layout/components/TagsView.vue')
  const tokens = readSrc('src/styles/index.css')

  it('AdminLayout 不再渲染顶栏，壳为左右分栏 + 100vh', () => {
    assert.equal(layout.includes('class="topbar"'), false)
    assert.equal(layout.includes('class="shell"'), true)
    assert.equal(layout.includes('brand-zone'), true)
    assert.equal(layout.includes('height: 100vh'), true)
    assert.equal(layout.includes('flex-direction: row'), true)
  })

  it('用户下拉在侧栏底部，折叠并入用户行最右，菜单项仍含退出登录', () => {
    assert.equal(layout.includes('command="logout"'), true)
    assert.equal(layout.includes('退出登录'), true)
    assert.equal(layout.includes('roleLabel'), true)
    assert.equal(layout.includes('collapse-btn'), true)
    // 不再单独占一行「收起侧边栏」文案按钮
    assert.equal(layout.includes('>收起侧边栏</span>'), false)
    // 用户区落在 aside 的 foot 内，而非独立 header
    const footIdx = layout.indexOf('class="foot"')
    const accountIdx = layout.indexOf('class="account"')
    assert.ok(footIdx > 0 && accountIdx > footIdx)
  })

  it('KeepAlive 协议未改：include=cachedViews，key=pageKey', () => {
    assert.match(layout, /<keep-alive\s+:include="cachedViews">/)
    assert.match(layout, /:key="pageKey"/)
    assert.equal(layout.includes('cachedViews'), true)
  })

  it('侧栏展开/折叠宽度令牌为 240 / 64', () => {
    assert.match(tokens, /--ah-sidebar:\s*240px/)
    assert.match(tokens, /--ah-sidebar-collapsed:\s*64px/)
  })

  it('TagsView 定高 40px 且不超过 45px', () => {
    assert.match(tags, /height:\s*40px/)
    assert.match(tags, /max-height:\s*45px/)
  })

  it('body 禁止整页滚动，主内容浅灰底', () => {
    assert.match(tokens, /html,\s*\nbody\s*\{[\s\S]*overflow:\s*hidden/)
    assert.equal(layout.includes('background: #f5f7fa'), true)
  })
})
