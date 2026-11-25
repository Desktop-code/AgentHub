/**
 * 工作台消息展示文案纯函数单测（本地 tests/，不进仓）。
 *
 * 覆盖：名称缺省、思考区内部键脱敏。
 * 运行：npm test（或 node --experimental-strip-types --test tests/workbench-message-meta.test.ts）
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  WORKBENCH_AGENT_NAME_FALLBACK,
  resolveWorkbenchAgentName,
  stripWorkbenchInternalKeys
} from '../src/components/runtime/workbench/workbenchMessageMeta.ts'

describe('工作台 AI 名称缺省', () => {
  it('有真实展示名时原样使用（多专家各条自己的名称）', () => {
    assert.equal(resolveWorkbenchAgentName('文稿起草'), '文稿起草')
    assert.equal(resolveWorkbenchAgentName('  计费专家  '), '计费专家')
  })

  it('空值与线路状态占位统一成「智能助手」', () => {
    assert.equal(resolveWorkbenchAgentName(''), WORKBENCH_AGENT_NAME_FALLBACK)
    assert.equal(resolveWorkbenchAgentName(undefined), WORKBENCH_AGENT_NAME_FALLBACK)
    assert.equal(resolveWorkbenchAgentName('助手'), WORKBENCH_AGENT_NAME_FALLBACK)
    assert.equal(resolveWorkbenchAgentName('当前线路'), WORKBENCH_AGENT_NAME_FALLBACK)
    assert.equal(resolveWorkbenchAgentName('线路已失效'), WORKBENCH_AGENT_NAME_FALLBACK)
    assert.equal(WORKBENCH_AGENT_NAME_FALLBACK, '智能助手')
  })
})

describe('工作台思考区内部键脱敏', () => {
  it('路由决策 JSON 去掉 route_key，保留 sub_query', () => {
    const raw = '[{"route_key":"wengao","sub_query":"写一份放假通知"}]'
    const out = stripWorkbenchInternalKeys(raw)
    assert.ok(!out.includes('route_key'))
    assert.ok(!out.includes('wengao'))
    assert.ok(out.includes('写一份放假通知'))
  })

  it('非 JSON 形态的 route_key=… 也一并抹掉', () => {
    const out = stripWorkbenchInternalKeys('调用专家 route_key=billing_expert 处理账单')
    assert.ok(!out.includes('route_key'))
    assert.ok(!out.includes('billing_expert'))
    assert.ok(out.includes('处理账单'))
  })

  it('散落的内部键名本身也不出现在工作台', () => {
    const out = stripWorkbenchInternalKeys('route_key 等内部键不对外')
    assert.ok(!out.includes('route_key'))
    assert.ok(out.includes('等内部键不对外'))
  })

  it('只剩内部键的决策项不会留下空对象噪音', () => {
    assert.equal(stripWorkbenchInternalKeys('[{"route_key":"a"}]'), '')
    assert.equal(stripWorkbenchInternalKeys(''), '')
  })
})
