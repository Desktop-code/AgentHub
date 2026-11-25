/**
 * 「旧系统提示词」提交收敛验证（node:test，无浏览器）。所属域：agent。
 *
 * 背景：挂上 L3 模板后，后端禁止 system_prompt 与模板双轨（会直接 400）。
 * 因此前端提交前必须把残留正文并入「追加要求」并清空正文；本测试锁住这几条口径：
 * 1) 未挂模板：正文与追加要求原样返回；
 * 2) 已挂模板且有正文：正文并入追加要求，systemPrompt 置空；
 * 3) 已挂模板但正文为空/全空白：不做任何拼接，不乱加空行；
 * 4) 追加要求为空时直接取正文，不出现前导空行。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/promptSafety.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { checkUserPrompt, settleLegacyPrompt } from '../src/utils/promptSafety.ts'

test('未挂模板时原样返回', () => {
  const settled = settleLegacyPrompt('你是业务助手', '补充要求', null)
  assert.equal(settled.systemPrompt, '你是业务助手')
  assert.equal(settled.promptExtra, '补充要求')
})

test('已挂模板时正文并入追加要求并清空正文', () => {
  const settled = settleLegacyPrompt('你是业务助手', '补充要求', 12)
  assert.equal(settled.systemPrompt, '')
  assert.equal(settled.promptExtra, '补充要求\n\n你是业务助手')
})

test('已挂模板但追加要求为空时不出现前导空行', () => {
  const settled = settleLegacyPrompt('  你是业务助手  ', '', 12)
  assert.equal(settled.systemPrompt, '')
  assert.equal(settled.promptExtra, '你是业务助手')
})

test('已挂模板但正文全空白时保持原正文，不污染追加要求', () => {
  const settled = settleLegacyPrompt('   \n ', '补充要求', 12)
  assert.equal(settled.systemPrompt, '   \n ')
  assert.equal(settled.promptExtra, '补充要求')
})

test('正文安全校验仍按原口径生效', () => {
  const report = checkUserPrompt('sk-abcdefghijklmnopqrstuvwxyz')
  assert.equal(report.errors.length, 1)
  assert.match(report.errors[0], /密钥/)
})
