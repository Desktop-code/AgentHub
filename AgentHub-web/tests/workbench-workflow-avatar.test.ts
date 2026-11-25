/**
 * 工作流头像口径单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖用户拍板的两条：
 * 1) 工作流卡（执行卡片 / 占位块）一律走固定齿轮头像，不按名称或形象决定；
 * 2) 入口、方案、专家、方案结论文档都不走齿轮——它们的头像保持原样。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { WORKFLOW_ROLE_LABEL } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
import {
  WORKFLOW_AVATAR_COLOR,
  isWorkflowAvatarCard
} from '../src/components/runtime/workbench/workbenchAvatar.ts'

test('工作流执行卡片：头像走固定齿轮（不读 lookOf）', () => {
  assert.equal(
    isWorkflowAvatarCard({
      workflow: { name: '制度要点与报告', code: 'wf_report', status: 'RUNNING', answer: '', nodes: [] }
    }),
    true
  )
})

test('工作流占位块（快照还没到）：同样走固定齿轮', () => {
  assert.equal(isWorkflowAvatarCard({ roleLabel: WORKFLOW_ROLE_LABEL }), true)
})

test('入口 / 方案 / 专家 / 方案结论：都不走齿轮头像', () => {
  const others = [
    { kind: 'gateway', title: '企业综合台' },
    { kind: 'actor', title: '企业综合台', roleLabel: '方案' },
    { kind: 'actor', title: '制度知识专家' },
    { kind: 'conclusion', title: '企业综合台', roleLabel: '方案' }
  ]
  for (const card of others) {
    assert.equal(isWorkflowAvatarCard(card), false, `${card.kind}:${card.title} 不该被换成齿轮`)
  }
})

test('齿轮线稿色固定为近黑（与名称文字同色系，不用形象色）', () => {
  assert.equal(WORKFLOW_AVATAR_COLOR, '#1f2430')
})
