/**
 * 循环框展示口径单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖硬口径：
 * 1) 循环不再单独占一张步骤卡，体节点被圈进同一个框；
 * 2) 同轮多轮追加在同一个框里，不重复套框、不提前占位；
 * 3) 体还在跑时框是进行中，不会标已完成；
 * 4) 次数用尽 / 通过出循环后框才收口，框外节点留在框外；
 * 5) 体节点一出现就先预露「等待中」框，进体后同一只框改标题；
 * 6) 判断后不进循环时框标「未进入」，不一直挂着等待中。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { TrialWorkflowNodeView } from '../src/types/runtime.ts'
import {
  buildWorkflowTimeline,
  hasTimelineEntries,
  type WfLoopEntry,
  type WfTimelineEntry
} from '../src/components/runtime/workbench/chat/workflowLoopFrame.ts'

/** 拼一份节点视图，缺省字段给空值 */
function node(partial: Partial<TrialWorkflowNodeView>): TrialWorkflowNodeView {
  return {
    code: partial.code ?? '',
    name: partial.name ?? '',
    type: partial.type ?? 'AGENT',
    status: partial.status ?? 'PENDING',
    note: partial.note ?? '',
    attempt: partial.attempt,
    group: partial.group ?? '',
    loopRound: partial.loopRound ?? 0,
    loopTotal: partial.loopTotal ?? 0,
    loopDecision: partial.loopDecision ?? ''
  }
}

/** 收集所有循环框（含嵌套） */
function loopsOf(entries: WfTimelineEntry[]): WfLoopEntry[] {
  const out: WfLoopEntry[] = []
  for (const entry of entries) {
    if (entry.kind === 'loop') {
      out.push(entry, ...loopsOf(entry.entries))
    }
  }
  return out
}

/** 收集所有步骤节点（含嵌套） */
function stepsOf(entries: WfTimelineEntry[]): TrialWorkflowNodeView[] {
  const out: TrialWorkflowNodeView[] = []
  for (const entry of entries) {
    if (entry.kind === 'loop') {
      out.push(...stepsOf(entry.entries))
    } else {
      out.push(entry.node)
    }
  }
  return out
}

test('循环节点不占步骤位：体节点被圈进同一个框，循环节点自己不出卡', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'SUCCESS', group: 'loop1', attempt: 1 }),
    node({ code: 'check', name: '检查', status: 'SUCCESS', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      loopRound: 1, loopTotal: 3, loopDecision: 'BODY' }),
    node({ code: 'review', name: '点评', status: 'RUNNING' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  const steps = stepsOf(timeline).map((item) => item.code)
  assert.deepEqual(steps, ['impl', 'check', 'review'], '循环节点本身不该画成步骤')
  const loops = loopsOf(timeline)
  assert.equal(loops.length, 1)
  assert.equal(loops[0].loop.code, 'loop1')
  assert.equal(loops[0].entries.length, 2, '框内只有本轮的两个体节点')
})

test('框标题写清轮次；体还在跑时是进行中，不能标已完成', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'RUNNING', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'RUNNING', attempt: 1,
      loopRound: 1, loopTotal: 3, loopDecision: 'BODY' })
  ]
  const frame = loopsOf(buildWorkflowTimeline(nodes, undefined, 'RUNNING'))[0].loop
  assert.equal(frame.state, 'running')
  assert.equal(frame.title, '循环 · 第 1/3 轮 · 进行中')
})

test('打回再跑：同一框里追加第 2 轮，上一轮整段保留，不提前出现下一轮空卡', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'SUCCESS', group: 'loop1', attempt: 1 }),
    node({ code: 'check', name: '检查', status: 'SUCCESS', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      loopRound: 1, loopTotal: 3, loopDecision: 'BODY' }),
    node({ code: 'impl', name: '实现', status: 'RUNNING', group: 'loop1', attempt: 2 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'PENDING', attempt: 2 })
  ]
  const loops = loopsOf(buildWorkflowTimeline(nodes, undefined, 'RUNNING'))
  assert.equal(loops.length, 1, '两轮必须落在同一个框里')
  const body = loops[0].entries.filter((entry) => entry.kind === 'step')
  assert.equal(body.length, 3, '第 1 轮两节点 + 第 2 轮已开始的一个节点')
  assert.equal(loops[0].loop.round, 2)
  assert.equal(loops[0].loop.state, 'running')
  assert.equal(loops[0].loop.title, '循环 · 第 2/3 轮 · 进行中')
})

test('通过出循环：框标完成，框外节点接在框后面', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'SUCCESS', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      loopRound: 1, loopTotal: 3, loopDecision: 'BODY' }),
    node({ code: 'review', name: '点评', status: 'SUCCESS' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'SUCCESS')
  const loops = loopsOf(timeline)
  assert.equal(loops[0].loop.state, 'done')
  assert.equal(loops[0].loop.title, '循环 · 共 1 轮 · 已完成')
  // 点评不能在框里
  assert.equal(stepsOf(loops[0].entries).map((item) => item.code).includes('review'), false)
  assert.equal(stepsOf(timeline).map((item) => item.code).includes('review'), true)
})

test('次数用尽：框标「次数用尽」，点评仍在框外', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'SUCCESS', group: 'loop1', attempt: 3 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      loopRound: 3, loopTotal: 3, loopDecision: 'EXIT' }),
    node({ code: 'review', name: '点评', status: 'RUNNING' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  const loops = loopsOf(timeline)
  assert.equal(loops[0].loop.state, 'exhausted')
  assert.equal(loops[0].loop.title, '循环 · 共 3 轮 · 次数用尽')
  assert.equal(stepsOf(loops[0].entries).map((item) => item.code).includes('review'), false)
})

test('还没进循环：体节点一出现在时间线上就预露「等待中」框，不再先平铺', () => {
  const nodes = [
    node({ code: 'design', name: '玩法设计', status: 'RUNNING' }),
    node({ code: 'impl', name: '实现', status: 'PENDING', group: 'loop1', attempt: 1, note: '等待前置节点' }),
    node({ code: 'check', name: '检查', status: 'PENDING', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'PENDING', attempt: 1 }),
    node({ code: 'review', name: '点评', status: 'PENDING' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  const loops = loopsOf(timeline)
  assert.equal(loops.length, 1, '体节点出现时就该有框，不用等真正进循环')
  assert.equal(loops[0].loop.state, 'waiting')
  assert.equal(loops[0].loop.title, '循环 · 等待中')
  // 体节点在框里等待，点评仍在框外
  assert.deepEqual(stepsOf(loops[0].entries).map((item) => item.code), ['impl', 'check'])
  assert.deepEqual(
    timeline.filter((entry) => entry.kind === 'step').map((entry) => entry.node.code),
    ['design', 'review'],
    '框外只剩玩法设计与点评'
  )
})

test('预露后进循环：同一只框改成「第 1/3 轮 · 进行中」，不拆掉重做', () => {
  const waiting = [
    node({ code: 'impl', name: '实现', status: 'PENDING', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'PENDING', attempt: 1 })
  ]
  const running = [
    node({ code: 'impl', name: '实现', status: 'RUNNING', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      loopRound: 1, loopTotal: 3, loopDecision: 'BODY' })
  ]
  const before = loopsOf(buildWorkflowTimeline(waiting, undefined, 'RUNNING'))
  const after = loopsOf(buildWorkflowTimeline(running, undefined, 'RUNNING'))
  assert.equal(before.length, 1)
  assert.equal(after.length, 1)
  assert.equal(before[0].loop.key, after[0].loop.key, '框的键不变，展开状态与已流出正文都不受影响')
  assert.equal(after[0].loop.title, '循环 · 第 1/3 轮 · 进行中')
  assert.deepEqual(after[0].entries.map((entry) => entry.kind), ['step'], '体节点原地留在同一个框里')
})

test('判断后不进循环：预露框标「未进入」，不再一直等待中', () => {
  const nodes = [
    node({ code: 'cond', name: '条件', status: 'SUCCESS' }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SKIPPED', attempt: 1, note: '已跳过' }),
    node({ code: 'impl', name: '实现', status: 'SKIPPED', group: 'loop1', attempt: 1, note: '已跳过' }),
    node({ code: 'review', name: '点评', status: 'RUNNING' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  const loops = loopsOf(timeline)
  assert.equal(loops.length, 1)
  assert.equal(loops[0].loop.state, 'skipped')
  assert.equal(loops[0].loop.title, '循环 · 未进入')
  // 体节点不继续像还在等进循环
  assert.equal(stepsOf(loops[0].entries).every((item) => item.status === 'SKIPPED'), true)
  assert.equal(stepsOf(loops[0].entries).includes(timeline.find((entry) => entry.kind === 'step')!.node), false)
})

test('预露的下一轮空卡仍然跳过：等待中只铺第 1 轮的 PENDING', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'PENDING', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'PENDING', attempt: 1 }),
    node({ code: 'impl', name: '实现', status: 'PENDING', group: 'loop1', attempt: 2 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'PENDING', attempt: 2 })
  ]
  const loops = loopsOf(buildWorkflowTimeline(nodes, undefined, 'RUNNING'))
  assert.equal(loops.length, 1)
  assert.equal(loops[0].loop.state, 'waiting')
  assert.deepEqual(stepsOf(loops[0].entries).map((item) => item.attempt), [1])
})

test('循环节点已跳过、体节点这一拍还没改口：框也不留在等待中', () => {
  const nodes = [
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'SKIPPED', attempt: 1, note: '已跳过' }),
    node({ code: 'impl', name: '实现', status: 'PENDING', group: 'loop1', attempt: 1 })
  ]
  const loops = loopsOf(buildWorkflowTimeline(nodes, undefined, 'RUNNING'))
  assert.equal(loops[0].loop.state, 'skipped')
  assert.equal(loops[0].loop.title, '循环 · 未进入')
})

test('整轮收口仍没进过体：预露框标未进入，不报成已完成', () => {
  const nodes = [
    node({ code: 'impl', name: '实现', status: 'PENDING', group: 'loop1', attempt: 1 }),
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'PENDING', attempt: 1 }),
    node({ code: 'other', name: '别的步骤', status: 'FAILED' })
  ]
  const loops = loopsOf(buildWorkflowTimeline(nodes, undefined, 'FAILED'))
  assert.equal(loops[0].loop.state, 'skipped')
  assert.equal(loops[0].loop.title, '循环 · 未进入')
})

test('嵌套循环：内层框在外层框里，不拍平成一个框', () => {
  const nodes = [
    node({ code: 'outer-body', name: '外层体', status: 'SUCCESS', group: 'outer', attempt: 1 }),
    node({ code: 'outer', name: '外层循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      loopRound: 1, loopTotal: 2, loopDecision: 'BODY' }),
    node({ code: 'inner-body', name: '内层体', status: 'RUNNING', group: 'inner', attempt: 1 }),
    node({ code: 'inner', name: '内层循环', type: 'LOOP', status: 'SUCCESS', attempt: 1,
      group: 'outer', loopRound: 1, loopTotal: 2, loopDecision: 'BODY' }),
    node({ code: 'outer', name: '外层循环', type: 'LOOP', status: 'PENDING', attempt: 2 })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  const outer = loopsOf(timeline).find((entry) => entry.loop.code === 'outer')
  assert.ok(outer, '外层框应存在')
  const inner = outer!.entries.filter((entry) => entry.kind === 'loop').map((entry) => entry.loop.code)
  assert.deepEqual(inner, ['inner'], '内层框应在外层框里面')
  assert.equal(loopsOf(timeline).length, 2, '两个循环各一个框，不合并')
})

test('空框不画：只有循环节点、体节点还没到时时间线为空', () => {
  const nodes = [
    node({ code: 'loop1', name: '循环', type: 'LOOP', status: 'RUNNING', attempt: 1,
      loopRound: 1, loopTotal: 3, loopDecision: 'BODY' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  assert.equal(hasTimelineEntries(timeline), false)
})

test('没有循环图的旧卡片：逐节点平铺，与改动前一致', () => {
  const nodes = [
    node({ code: 'a', name: '甲', status: 'SUCCESS' }),
    node({ code: 'b', name: '乙', status: 'RUNNING' })
  ]
  const timeline = buildWorkflowTimeline(nodes, undefined, 'RUNNING')
  assert.equal(timeline.every((entry) => entry.kind === 'step'), true)
  assert.deepEqual(stepsOf(timeline).map((item) => item.code), ['a', 'b'])
})
