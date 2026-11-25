/**
 * 工作台 @ 换线纯函数单测（本地 tests/，不进仓）。
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  detectWorkbenchAtTrigger,
  filterWorkbenchAtCandidates,
  stripWorkbenchAtFragment
} from '../src/utils/workbenchAtRoute.ts'

describe('workbenchAtRoute', () => {
  it('filters by agentName only, never by route_key', () => {
    const list = [
      { routeKey: 'reception', agentName: '接待助手' },
      { routeKey: 'billing_expert', agentName: '计费专家' },
      { routeKey: 'hidden_key', agentName: '其它' }
    ]
    const hit = filterWorkbenchAtCandidates(list, '计费')
    assert.equal(hit.length, 1)
    assert.equal(hit[0].agentName, '计费专家')
    assert.equal(filterWorkbenchAtCandidates(list, 'billing_expert').length, 0)
  })

  it('detects trailing @ query and strips it from body', () => {
    const raw = '帮我看看 @计'
    const hit = detectWorkbenchAtTrigger(raw)
    assert.ok(hit)
    assert.equal(hit!.query, '计')
    assert.equal(stripWorkbenchAtFragment(raw, hit!.start), '帮我看看')
  })

  it('returns null when @ is mid-sentence with trailing text', () => {
    assert.equal(detectWorkbenchAtTrigger('说 @甲 再继续'), null)
  })
})
