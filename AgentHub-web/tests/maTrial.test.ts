/**
 * 多智能体独立试跑页入口口径的窄测。所属域：前端 runtime。
 *
 * <p>覆盖三件事：
 * 1) 路径拼装固定为 /orchestration/multi-agent/:id/trial；
 * 2) 进入校验的三种拦截（缺 id / 无权限 / 非 Router）与放行；
 * 3) 试跑落点分流：只有 ROUTER 才给独立页，ReAct 与计划执行仍走原抽屉。
 *
 * 运行：node --experimental-strip-types --test tests/maTrial.test.ts
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  MA_TRIAL_BASE_PATH,
  MA_TRIAL_RUN_MODE,
  isMaTrialPath,
  maTrialEntryError,
  maTrialPath,
  maTrialPathOrEmpty
} from '../src/utils/maTrial.ts'

test('路径实符：/orchestration/multi-agent/:agentId/trial', () => {
  assert.equal(MA_TRIAL_BASE_PATH, '/orchestration/multi-agent')
  assert.equal(maTrialPath(12), '/orchestration/multi-agent/12/trial')
})

test('缺 Agent 标识：中文提示，不放行', () => {
  const reason = maTrialEntryError({ agentId: 0, canTrial: true, runMode: 'ROUTER' })
  assert.equal(reason, '缺少 Agent 标识，已返回多智能体列表')
})

test('无运行权限：中文提示，不放行', () => {
  const reason = maTrialEntryError({ agentId: 3, canTrial: false, runMode: 'ROUTER' })
  assert.equal(reason, '没有运行权限，已返回多智能体列表')
})

test('非 Router 模式：拒绝进入（ReAct / 计划执行）', () => {
  for (const runMode of ['REACT', 'PLAN_AND_EXECUTE', '']) {
    const reason = maTrialEntryError({ agentId: 3, canTrial: true, runMode })
    assert.equal(reason, '只有多智能体（Router）编排根可以进入独立运行页')
  }
})

test('模式还没取到：先放行，等详情回来再判', () => {
  assert.equal(maTrialEntryError({ agentId: 3, canTrial: true }), '')
  assert.equal(maTrialEntryError({ agentId: 3, canTrial: true, runMode: undefined }), '')
})

test('Router + 有权限 + 合法 id：放行', () => {
  assert.equal(maTrialEntryError({ agentId: 3, canTrial: true, runMode: MA_TRIAL_RUN_MODE }), '')
})

test('试跑路由判定：切到 Agent 管理后不再把本页当成试跑页', () => {
  // 仍在试跑页（含非法 id）：必须识别，非法 id 才能被页面守卫提示并回列表
  assert.equal(isMaTrialPath(maTrialPath(12)), true)
  assert.equal(isMaTrialPath('/orchestration/multi-agent/abc/trial'), true)
  // 列表页与侧栏 Agent 管理都不是试跑页；失活后的 watch 靠它提前返回
  assert.equal(isMaTrialPath(MA_TRIAL_BASE_PATH), false)
  assert.equal(isMaTrialPath('/agent/list'), false)
  assert.equal(isMaTrialPath('/agent/list/3'), false)
  // 多出来的层级不属于试跑路由
  assert.equal(isMaTrialPath('/orchestration/multi-agent/12/trial/x'), false)
})

test('试跑落点分流：Router 进独立页，其它模式返回空串走原抽屉', () => {
  assert.equal(maTrialPathOrEmpty(9, 'ROUTER'), '/orchestration/multi-agent/9/trial')
  assert.equal(maTrialPathOrEmpty(9, 'REACT'), '')
  assert.equal(maTrialPathOrEmpty(9, 'PLAN_AND_EXECUTE'), '')
  // id 非法时不编造地址，交回调用方走原路径
  assert.equal(maTrialPathOrEmpty(0, 'ROUTER'), '')
})
