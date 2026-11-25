/**
 * Agent 形象（机器人 8 套）解析测试（node:test，无浏览器）。所属域：agent（前端）。
 *
 * 覆盖验收点：
 * 1) 8 套机器人的键 / 推荐色 / 图元齐全，且推荐色不重复；
 * 2) 键命中直接给出对应线稿图元，底座恒为白底 #ffffff，线稿色默认取该套推荐色；
 * 3) 显式 color 只改线稿色，绝不改背景；
 * 4) 旧 look-* 数据按兼容映射落位，升级后不退回首字；
 * 5) 键对不上 / 未配置走名称首字兜底，首字色用推荐色兜底；
 * 6) 自定义上传图优先于线稿。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/agentLooks.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  AGENT_AVATAR_BASE,
  AGENT_DEFAULT_LOOK_COLOR,
  AGENT_ROBOT_LOOKS,
  resolveAgentAvatar,
  resolveFallbackRobotAvatar,
  resolveFallbackRobotConfig,
  resolveRobotLook
} from '../src/constants/agentLooks.ts'

test('8 套机器人：键齐全、图元非空、推荐色互不重复', () => {
  assert.equal(AGENT_ROBOT_LOOKS.length, 8)
  assert.deepEqual(
    AGENT_ROBOT_LOOKS.map((item) => item.key),
    ['classic', 'explorer', 'geek', 'cyclops', 'armored', 'broadcast', 'friendly', 'matrix']
  )
  for (const look of AGENT_ROBOT_LOOKS) {
    assert.ok(look.art.length >= 2, `${look.key} 至少有 2 个图元`)
    assert.match(look.color, /^#[0-9a-f]{6}$/, `${look.key} 的推荐色必须是十六进制`)
    assert.ok(look.label && look.desc, `${look.key} 必须有中文名与用途说明`)
  }
  assert.equal(new Set(AGENT_ROBOT_LOOKS.map((item) => item.color)).size, 8, '8 套推荐色不能重复')
})

test('推荐色与设计稿绑定：顺序、色值一字不差', () => {
  assert.deepEqual(
    AGENT_ROBOT_LOOKS.map((item) => item.color),
    ['#38bdf8', '#a78bfa', '#4ade80', '#fbbf24', '#f87171', '#2dd4bf', '#e879f9', '#94a3b8']
  )
})

test('键命中：给出对应图元，白底，线稿色默认取该套推荐色', () => {
  const view = resolveAgentAvatar({ type: 'preset', icon: 'geek' }, '研发助手')
  assert.equal(view.background, AGENT_AVATAR_BASE)
  assert.equal(AGENT_AVATAR_BASE, '#ffffff', '底座必须是白底')
  assert.equal(view.imgSrc, '', '机器人形象走线稿，不再用 img')
  assert.ok(Array.isArray(view.art) && view.art.length >= 2)
  assert.equal(view.isRobot, true)
  assert.equal(view.isCustom, false)
  assert.equal(view.strokeColor, '#4ade80')
})

test('显式 color：只改线稿色，不改背景', () => {
  const view = resolveAgentAvatar({ type: 'preset', icon: 'classic', color: '#EF4444' }, 'A')
  assert.equal(view.strokeColor, '#EF4444')
  assert.equal(view.background, '#ffffff', '选颜色不能把背景染色')
  assert.equal(view.initialColor, '#EF4444')
})

test('旧 look-* 数据按兼容映射落位，不退回首字', () => {
  const cases: Array<[string, string]> = [
    ['look-guide', 'classic'],
    ['look-research', 'explorer'],
    ['look-coder', 'geek'],
    ['look-analyst', 'cyclops'],
    ['look-ops', 'armored'],
    ['look-butler', 'broadcast'],
    ['look-support', 'friendly'],
    ['look-scholar', 'matrix']
  ]
  for (const [legacy, key] of cases) {
    assert.equal(resolveRobotLook(legacy)?.key, key, `${legacy} 应映射到 ${key}`)
    const view = resolveAgentAvatar({ type: 'preset', icon: legacy }, 'A')
    assert.ok(view.art, `${legacy} 必须能渲染出线稿`)
  }
})

test('键对不上 / 未配置：走名称首字兜底', () => {
  assert.equal(resolveRobotLook('demo_unknown'), undefined)
  const unknown = resolveAgentAvatar({ type: 'preset', icon: 'demo_unknown' }, '支付助手')
  assert.equal(unknown.art, null)
  assert.equal(unknown.initial, '支')
  assert.equal(unknown.background, '#ffffff')
  assert.equal(unknown.initialColor, AGENT_DEFAULT_LOOK_COLOR)

  const empty = resolveAgentAvatar(null, 'ops-agent')
  assert.equal(empty.art, null)
  assert.equal(empty.initial, 'O')
  assert.equal(empty.isRobot, false)
})

test('方案未配形象：按名称稳定落到 8 套机器人，不回首字方座', () => {
  const config = resolveFallbackRobotConfig('企业综合台')
  assert.equal(config.type, 'preset')
  assert.ok(config.icon, '兜底必须命中一套预置机器人')
  // 同一名字每次都是同一套，刷新不会变脸
  assert.deepEqual(resolveFallbackRobotConfig('企业综合台'), config)

  const view = resolveFallbackRobotAvatar('企业综合台')
  assert.equal(view.isRobot, true)
  assert.equal(view.art !== null, true)
  assert.equal(view.background, '#ffffff')
  assert.equal(view.strokeColor, config.color)
})

test('自定义上传图优先于线稿', () => {
  const custom = resolveAgentAvatar(
    { type: 'custom', url: 'https://cdn.example.com/a.png', icon: 'classic' },
    '带图 Agent'
  )
  assert.equal(custom.imgSrc, 'https://cdn.example.com/a.png')
  assert.equal(custom.isCustom, true)
  assert.equal(custom.background, '#ffffff')
})
