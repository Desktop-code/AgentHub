/**
 * 「配置提示词」入口可见性 + soft 段取值语义自测（无测试框架，Node 直接跑）。
 * 所属域：agent（前端工具自测）。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types scripts/promptL3.selftest.ts
 *
 * 覆盖本轮三处修复里能纯函数化的两条：
 * 1) soft 输入框回显：override 优先，否则回显模板默认正文；清空/改回默认 = 未覆写；
 * 2) 非 CHAT 入口可见：REWRITE / SUMMARY / MEMORY / ROUTER 在主模型行稳定可见，
 *    CHAT 与多模态/音频等槽不出现在模型槽入口；入口不依赖「是否已绑模板」。
 */
import assert from 'node:assert/strict'
import { purposeSupportsPromptEntry, shouldShowPromptEntry } from '../src/utils/promptEntry.ts'
import { isSegmentOverridden, nextOverride, overrideHint, softDisplayValue } from '../src/utils/promptSoftValue.ts'

/* ============ 一、soft 段取值语义 ============ */

const seg = { segKey: 'role_tone', content: '你是一名电商售后顾问，语气简洁。' }

// 1) 未覆写 → 回显模板默认正文（不靠 placeholder 冒充）
assert.equal(softDisplayValue(seg, {}), seg.content, '未覆写应回显模板默认正文')
assert.equal(softDisplayValue(seg, undefined), seg.content, 'override 为 undefined 时也应回显模板默认正文')
assert.equal(softDisplayValue(seg, { role_tone: '   ' }), seg.content, '空白 override 视为未覆写')
assert.equal(softDisplayValue({ segKey: 'tone', content: '' }, {}), '', '模板正文为空时返回空串')

// 2) 已存 override → 优先显示 override
assert.equal(softDisplayValue(seg, { role_tone: '你是售后专家。' }), '你是售后专家。', '应显示已存 override')

// 3) 清空 / 改回默认 = 删键，其它段保持原值
const cleared = nextOverride(seg, { role_tone: '旧值', other: '别的段' }, '   ')
assert.equal(cleared.role_tone, undefined, '清空应删除该段键')
assert.equal(cleared.other, '别的段', '不应影响其它段')
const reverted = nextOverride(seg, { role_tone: '旧值' }, `  ${seg.content}  `)
assert.equal(reverted.role_tone, undefined, '改回模板默认应删除该段键')
const changed = nextOverride(seg, {}, '新的业务要求')
assert.equal(changed.role_tone, '新的业务要求', '真实修改应写入 override')

// 4) 只读：显示值计算不改入参
const input = { role_tone: '已存值' }
softDisplayValue(seg, input)
assert.deepEqual(input, { role_tone: '已存值' }, '显示值计算不应修改入参')

// 5) 状态说明与覆写判定一致
assert.equal(isSegmentOverridden(seg, {}), false)
assert.equal(isSegmentOverridden(seg, { role_tone: 'x' }), true)
assert.ok(overrideHint(seg, {}).includes('跟随模板默认'), '未覆写应提示跟随默认')
assert.ok(overrideHint(seg, { role_tone: 'x' }).includes('已覆盖'), '已覆写应提示已覆盖')

// 6) 切换模板后旧 override 的键不影响其它段取值
assert.equal(softDisplayValue({ segKey: 'tone', content: '默认语气' }, { role_tone: '旧' }), '默认语气')

/* ============ 二、非 CHAT 入口可见性 ============ */

// 7) 有模板维度的非 CHAT 用途均可见入口
for (const purpose of ['ROUTER', 'REWRITE', 'SUMMARY', 'MEMORY']) {
  assert.equal(purposeSupportsPromptEntry(purpose), true, `${purpose} 应展示配置提示词入口`)
}

// 8) CHAT 不在模型槽出现入口（避免与基本信息双入口）
assert.equal(purposeSupportsPromptEntry('CHAT'), false, 'CHAT 不应在模型槽出现入口')

// 9) 无模板维度的槽不出现入口
for (const purpose of ['VISION', 'ASR', 'TTS', 'GUARDRAIL', 'EMBEDDING', 'RERANK']) {
  assert.equal(purposeSupportsPromptEntry(purpose), false, `${purpose} 不应出现入口`)
}

// 10) 父级显式关闭时整块不展示
assert.equal(purposeSupportsPromptEntry('REWRITE', false), false, '父级 promptSupported=false 应关闭入口')

// 11) 「未指定」必须等同于允许：这是线上「配了模型却看不到入口」的根因，
//     Vue 会把它声明为 Boolean prop 的未传值强转成 false，故组件侧必须用 default: undefined 兜住。
assert.equal(purposeSupportsPromptEntry('REWRITE', undefined), true, 'undefined（未传）应视为允许')
assert.equal(purposeSupportsPromptEntry('SUMMARY', undefined), true, 'undefined（未传）应视为允许')
assert.equal(purposeSupportsPromptEntry('MEMORY', undefined), true, 'undefined（未传）应视为允许')
assert.equal(purposeSupportsPromptEntry('ROUTER', undefined), true, 'undefined（未传）应视为允许')

// 11) 只有主模型（index 0）承载入口，降级行不展示
assert.equal(shouldShowPromptEntry(0, 'REWRITE'), true, '主模型行应展示入口')
assert.equal(shouldShowPromptEntry(1, 'REWRITE'), false, '降级行不展示入口')
assert.equal(shouldShowPromptEntry(0, 'SUMMARY'), true, 'SUMMARY 主模型行应展示入口')
assert.equal(shouldShowPromptEntry(0, 'CHAT'), false, 'CHAT 主模型行不展示入口')

// 12) 入口与「是否已绑模板」无关：未绑模板也必须能点开选模板
//     （函数不接受绑定态参数，此断言即锁定「入口不随绑定态消失」这一契约）
assert.equal(purposeSupportsPromptEntry('MEMORY'), true, 'MEMORY 未绑模板时入口同样可见')

console.log('promptL3 selftest: 全部通过')
