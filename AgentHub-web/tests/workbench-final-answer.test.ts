/**
 * 结果框只放参与者自己的终稿。所属域：runtime（前端）。
 *
 * 没有助手正文时，不把工具返回（检索 JSON、写入成功提示）填进结果框。
 * 有终稿又有文件时，文字与文件并列。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import { buildParticipantFeed as projectParticipantFeed, visitParticipantCards } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { stampBeginLanes } from './stampBegin.ts'

function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed({
    ...input,
    lanes: stampBeginLanes(input.lanes, () => 'AGENT', () => true)
  }))
}

const retrieval = '"[{\\"content\\":\\"示例科技日常费用报销制度\\",\\"hitBy\\":\\"vector\\"}]"'

test('终稿与文件并列，工具返回不顶替终稿', () => {
  const lanes: ProcessLane[] = [
    {
      kind: 'agent',
      title: '路由到 制度知识专家',
      steps: [
        { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '报销' },
        { type: 'tool_result', title: '工具结果', text: retrieval }
      ]
    },
    {
      kind: 'agent',
      title: '路由到 报告撰写专家',
      steps: [
        { type: 'tool_call', title: '调用工具 workspaceWriteFile', text: '报告.md' },
        { type: 'tool_result', title: '工具结果', text: '文件已成功写入。你可以通过路径 /workspace/报告.md 引用它。' }
      ]
    }
  ]
  const blocks: AssistantBlock[] = [
    { kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度' },
    { kind: 'assistant', text: '撰写处理原则：\n\n- 不虚构、不补全\n- 缺口单列成章' },
    { kind: 'route', text: '路由到 报告撰写专家（writer）' },
    { kind: 'assistant', text: '报告已起草完成并保存到工作区：/workspace/制度知识要点结构化报告.md' },
    {
      kind: 'file',
      text: '制度知识要点结构化报告.md',
      file: { fileName: '制度知识要点结构化报告.md', size: 9700, downloadUrl: '/files/报告.md' }
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes,
    blocks,
    streaming: false,
    fallbackName: '智能网关'
  })
  const policy = cards.find((card) => card.title === '制度知识专家')
  const report = cards.find((card) => card.title === '报告撰写专家')
  assert.ok(policy)
  assert.ok(report)
  assert.equal(policy.answer.includes('不虚构、不补全'), true)
  assert.equal(policy.answer.includes('hitBy'), false)
  assert.equal(report.answer.includes('报告已起草完成'), true)
  assert.equal(report.answer.includes('文件已成功写入'), false)
  assert.equal(report.files.length, 1)
  assert.equal(report.files[0].fileName, '制度知识要点结构化报告.md')
})

test('没有终稿时不把检索 JSON 或写入成功提示画进结果框', () => {
  const lanes: ProcessLane[] = [
    {
      kind: 'agent',
      title: '路由到 制度知识专家',
      steps: [
        { type: 'tool_call', title: '调用工具 searchKnowledgeBase', text: '报销' },
        { type: 'tool_result', title: '工具结果', text: retrieval }
      ]
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家（policy），依据：查制度'],
    lanes,
    blocks: [{ kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度' }],
    streaming: false,
    fallbackName: '智能网关'
  })
  const policy = cards.find((card) => card.title === '制度知识专家')
  assert.ok(policy)
  assert.equal(policy.answer, '')
  assert.equal((policy.terminalNote || '').includes('已调用工具'), true)
  assert.equal((policy.terminalNote || '').includes('content'), false)
})
