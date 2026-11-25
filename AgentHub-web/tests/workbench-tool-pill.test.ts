/**
 * 工具胶囊聚合的单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖拍板的两条：
 * 1) 同一参与者、同一工具名并行多路 → 只出一条胶囊，计数正确；
 * 2) 首错优先：后续「撞限：consecutive_fail_abort」不得覆盖第一次真实异常。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  aggregateToolPills,
  firstFailureOf,
  maskToolCallNames,
  presentToolDetail,
  toolArgsOf,
  toolFaceName,
  toolNameOf
} from '../src/components/runtime/workbench/workbenchToolPill.ts'
import type { ToolStepLike } from '../src/components/runtime/workbench/workbenchToolPill.ts'

/** 造一条成功的工具调用 + 结果。 */
function okCall(name: string, args: string, result: string): ToolStepLike[] {
  return [
    { type: 'tool_call', title: `调用工具 ${name}`, text: args, state: 'done' },
    { type: 'tool_result', title: '工具结果', text: result }
  ]
}

/**
 * 造一条失败的工具调用 + 结果。
 *
 * @param limit true 表示这条是熔断文案，而非真实首错
 */
function failCall(name: string, args: string, detail: string, limit = false): ToolStepLike[] {
  return [
    {
      type: 'tool_call',
      title: `调用工具 ${name}${limit ? '' : ' · 失败'}`,
      text: args,
      state: 'error'
    },
    { type: 'tool_result', title: '工具结果', text: detail }
  ]
}

test('同名工具并行多路：只出一条胶囊，计数正确', () => {
  const steps: ToolStepLike[] = [
    ...okCall('searchKnowledgeBase', '{"query":"年假"}', '命中 1 条'),
    ...okCall('searchKnowledgeBase', '{"query":"年假规定"}', '命中 2 条'),
    ...okCall('searchKnowledgeBase', '{"query":"员工考勤"}', '命中 3 条')
  ]
  const pills = aggregateToolPills('card-1', steps)
  assert.equal(pills.length, 1, '同名只应一条')
  assert.equal(pills[0].name, 'searchKnowledgeBase')
  assert.equal(pills[0].total, 3)
  assert.equal(pills[0].ok, 3)
  assert.equal(pills[0].failed, 0)
  assert.equal(pills[0].state, 'done')
  // 每一次的入参出参都留在详情里，不丢结果
  assert.equal(pills[0].detail.includes('第 3 次'), true)
  assert.equal(pills[0].detail.includes('命中 3 条'), true)
})

test('不同工具名分开成多条胶囊', () => {
  const steps: ToolStepLike[] = [
    ...okCall('searchKnowledgeBase', '{}', 'a'),
    ...okCall('workspaceListFiles', '{}', 'b')
  ]
  const pills = aggregateToolPills('card-1', steps)
  assert.deepEqual(pills.map((pill) => pill.name), ['searchKnowledgeBase', 'workspaceListFiles'])
})

test('还有一路在跑时，整条显示 running 而不是并列多条蓝条', () => {
  const steps: ToolStepLike[] = [
    { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '', state: 'running' },
    { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '', state: 'running' }
  ]
  const pills = aggregateToolPills('card-1', steps)
  assert.equal(pills.length, 1)
  assert.equal(pills[0].state, 'running')
  assert.equal(pills[0].total, 2)
  assert.equal(pills[0].running, 2)
})

test('首错优先：后续「撞限」不得覆盖第一次真实异常', () => {
  const steps: ToolStepLike[] = [
    ...failCall('searchKnowledgeBase', '{"query":"年假"}', '首次失败原因（FATAL）：IllegalArgumentException: 集合不存在'),
    ...failCall('searchKnowledgeBase', '{"query":"年假"}', '撞限：consecutive_fail_abort（连续失败 3 次）', true),
    ...failCall('searchKnowledgeBase', '{"query":"年假"}', '撞限：consecutive_fail_abort（连续失败 3 次）', true)
  ]
  const pills = aggregateToolPills('card-1', steps)
  assert.equal(pills.length, 1)
  assert.equal(pills[0].failed, 3)
  assert.equal(pills[0].state, 'error')
  // 首错必须是真实异常，而不是熔断文案
  assert.equal(pills[0].firstError.includes('集合不存在'), true)
  assert.equal(pills[0].firstError.includes('consecutive_fail_abort'), false)
})

test('混合成功与失败：胶囊写清成功 / 失败次数', () => {
  const steps: ToolStepLike[] = [
    ...okCall('searchKnowledgeBase', '{}', 'ok-1'),
    ...failCall('searchKnowledgeBase', '{}', '首次失败原因（RETRYABLE）：SocketTimeoutException: timeout'),
    ...okCall('searchKnowledgeBase', '{}', 'ok-2')
  ]
  const pills = aggregateToolPills('card-1', steps)
  assert.equal(pills[0].total, 3)
  assert.equal(pills[0].ok, 2)
  assert.equal(pills[0].failed, 1)
  assert.equal(pills[0].state, 'error')
  assert.equal(pills[0].firstError.includes('timeout'), true)
})

test('没有首错段时，用第一条失败正文压成一句，而不是留空', () => {
  const steps: ToolStepLike[] = [...failCall('searchKnowledgeBase', '{}', '工具执行失败：参数非法')]
  const pills = aggregateToolPills('card-1', steps)
  assert.equal(pills[0].firstError, '工具执行失败：参数非法')
})

test('firstFailureOf 只取「首次失败原因」段的第一行', () => {
  const text = '撞限：consecutive_fail_abort\n\n首次失败原因（FATAL）：IllegalStateException: 集合不存在\nat com.x.Y(z.java:1)'
  assert.equal(firstFailureOf(text), 'IllegalStateException: 集合不存在')
  assert.equal(firstFailureOf('没有任何首错段'), '')
})

test('toolNameOf 从标题里取工具名，参数段与失败后缀都不算名字', () => {
  assert.equal(toolNameOf('调用工具 searchKnowledgeBase'), 'searchKnowledgeBase')
  assert.equal(toolNameOf('调用工具 searchKnowledgeBase · 失败'), 'searchKnowledgeBase')
  // 后端真实格式：带「，参数 …」——参数不同也必须认出是同一个工具
  assert.equal(toolNameOf('调用工具 searchKnowledgeBase，参数 {"query":"年假"}'), 'searchKnowledgeBase')
  assert.equal(toolNameOf('调用工具 searchKnowledgeBase，参数 {"query":"考勤"} · 失败'), 'searchKnowledgeBase')
  assert.equal(toolNameOf(''), '工具')
})

test('不同参数的同一工具必须合并成一条（原先胶囊堆成一片的根因）', () => {
  const steps: ToolStepLike[] = [
    { type: 'tool_call', title: '调用工具 searchKnowledgeBase，参数 {"query":"年假"}', text: '{"query":"年假"}', state: 'done' },
    { type: 'tool_result', title: '工具结果', text: '命中 A' },
    { type: 'tool_call', title: '调用工具 searchKnowledgeBase，参数 {"query":"考勤"}', text: '{"query":"考勤"}', state: 'done' },
    { type: 'tool_result', title: '工具结果', text: '命中 B' }
  ]
  const pills = aggregateToolPills('card-1', steps)
  assert.equal(pills.length, 1, '参数不同也是同一个工具，只应一条')
  assert.equal(pills[0].total, 2)
  // 两次的入参与出参都要在详情里，不丢结果
  assert.equal(pills[0].detail.includes('年假'), true)
  assert.equal(pills[0].detail.includes('考勤'), true)
  assert.equal(pills[0].detail.includes('命中 A'), true)
  assert.equal(pills[0].detail.includes('命中 B'), true)
})

test('toolArgsOf 取「，参数 …」之后的正文', () => {
  assert.equal(toolArgsOf('调用工具 searchKnowledgeBase，参数 {"query":"年假"}'), '{"query":"年假"}')
  assert.equal(toolArgsOf('调用工具 searchKnowledgeBase'), '')
})

test('展示名用工具项标题，没有标题时不回英文函数名', () => {
  const titles = { searchKnowledgeBase: '知识库检索', workspaceWriteFile: '工作区写入' }
  assert.equal(toolFaceName('searchKnowledgeBase', titles), '知识库检索')
  assert.equal(toolFaceName('workspaceWriteFile', titles), '工作区写入')
  assert.equal(toolFaceName('知识检索', titles), '知识检索')
  assert.equal(toolFaceName('workspaceReadFile', titles), '工具')
  assert.equal(toolFaceName('searchKnowledgeBase'), '工具')
})

test('正文里的调用名换成目录标题，不写死某一组函数名', () => {
  const titles = {
    tableSummary: '表格汇总',
    sensitiveWordScan: '敏感词扫描',
    templateFill: '模板填空'
  }
  const text = '先调用 tableSummary，再调用 sensitiveWordScan 与 templateFill。tableSummaryExtra 不动。'
  assert.equal(
    maskToolCallNames(text, titles),
    '先调用 表格汇总，再调用 敏感词扫描 与 模板填空。tableSummaryExtra 不动。'
  )
  assert.equal(maskToolCallNames(text, {}), text)
})

test('工具详情只把字面换行收成换行，不改已有换行', () => {
  const shown = presentToolDetail('第 1 次\n出参：# 标题\\n部门：{{部门}}\\n')
  assert.equal(shown, '第 1 次\n出参：# 标题\n部门：{{部门}}\n')
  assert.equal(presentToolDetail('已经是\n换行'), '已经是\n换行')
})
