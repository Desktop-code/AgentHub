/**
 * 「其它 / 自填」提交去处的纯逻辑用例（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * <p>钉住本刀的核心判定：单 Agent 的「其它 / 自填」必须走 /choice 的 text 续作，
 * 不能落回普通发送（那会重新判路由）；工作流 / 方案等待仍走原来的发送续跑。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { routeWorkbenchAnswer } from '../src/components/runtime/workbench/chat/workbenchAnswerText.ts'

test('单 Agent 自填：有可点选项时走 /choice 的 text 续作', () => {
  const route = routeWorkbenchAnswer({
    customAnswer: true,
    waitingReply: false,
    agentChoiceOpen: true,
    hasAttachments: false
  })
  assert.equal(route, 'agent-choice')
})

test('工作流 / 方案等待优先：即使点过「其它」也仍走普通发送续跑', () => {
  const route = routeWorkbenchAnswer({
    customAnswer: true,
    waitingReply: true,
    agentChoiceOpen: true,
    hasAttachments: false
  })
  assert.equal(route, 'reply-waiting')
})

test('没点「其它」的普通提问不走续作', () => {
  const route = routeWorkbenchAnswer({
    customAnswer: false,
    waitingReply: false,
    agentChoiceOpen: true,
    hasAttachments: false
  })
  assert.equal(route, 'new-turn')
})

test('带附件的自填退回普通发送：续作端点不收附件，不能静默丢掉', () => {
  const route = routeWorkbenchAnswer({
    customAnswer: true,
    waitingReply: false,
    agentChoiceOpen: true,
    hasAttachments: true
  })
  assert.equal(route, 'new-turn')
})

test('已经没有可续选项时退回普通发送', () => {
  const route = routeWorkbenchAnswer({
    customAnswer: true,
    waitingReply: false,
    agentChoiceOpen: false,
    hasAttachments: false
  })
  assert.equal(route, 'new-turn')
})
