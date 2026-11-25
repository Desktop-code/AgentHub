/**
 * 工作台硬核错误友好映射 + 文首标题剥离窄测。所属域：runtime（前端）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  WORKBENCH_RESTING_HINT,
  friendlyWorkbenchError,
  isWorkbenchHardUnavailable,
  stripWorkbenchLeadHeadings
} from '../src/utils/workbenchFriendlyError.ts'

test('无对话模型 → 接待助手正在小憩…', () => {
  assert.equal(friendlyWorkbenchError('Agent 没有可用的对话模型'), WORKBENCH_RESTING_HINT)
  assert.equal(friendlyWorkbenchError('对话模型未配置'), WORKBENCH_RESTING_HINT)
  assert.equal(isWorkbenchHardUnavailable('Agent 没有可用的对话模型'), true)
})

test('校验 / 权限类文案原样返回，不做幽默替换', () => {
  assert.equal(friendlyWorkbenchError('无权访问'), '无权访问')
  assert.equal(friendlyWorkbenchError('参数校验失败：query 不能为空'), '参数校验失败：query 不能为空')
  assert.equal(friendlyWorkbenchError('等待人工审批'), '等待人工审批')
  assert.equal(isWorkbenchHardUnavailable('无权访问'), false)
})

test('空串原样', () => {
  assert.equal(friendlyWorkbenchError(''), '')
  assert.equal(friendlyWorkbenchError('   '), '')
})

test('剥文首人设小节标题，保留正文', () => {
  const raw =
    '### 通用接待：寒暄、能力介绍、需求不清时先追问一句\n\n你好，我是平台的接待助手。'
  assert.equal(stripWorkbenchLeadHeadings(raw), '你好，我是平台的接待助手。')
})

test('正文中间的标题不剥', () => {
  const raw = '先说结论。\n\n## 下一步\n\n1. 打开工作台'
  assert.equal(stripWorkbenchLeadHeadings(raw), raw)
})

test('无标题时原样', () => {
  assert.equal(stripWorkbenchLeadHeadings('你好'), '你好')
})
