/**
 * 工作流处理人规则窄测。所属域：workflow 的展示层。
 * <p>与后端同口径：ROLE 命中/未命中、USER 命中/未命中、空规则兼容、非法规则给中文原因。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  describeAssigneeRule,
  matchesAssigneeRule,
  parseAssigneeRule
} from '../src/utils/workflowAssignee.ts'

test('空规则：任何人（有处理权限）都命中', () => {
  assert.equal(parseAssigneeRule(null).kind, 'EMPTY')
  assert.equal(parseAssigneeRule('   ').kind, 'EMPTY')
  assert.equal(matchesAssigneeRule(null, 7, []), true)
  assert.equal(matchesAssigneeRule('  ', 7, ['ops']), true)
  assert.match(describeAssigneeRule(null), /任何人/)
})

test('USER：命中名单任一通过，未命中拒绝', () => {
  assert.equal(matchesAssigneeRule('USER:1, 7 ,9', 7, []), true)
  assert.equal(matchesAssigneeRule('USER:1,9', 7, []), false)
  assert.equal(matchesAssigneeRule('user:1,2', 2, []), true)
  assert.deepEqual(parseAssigneeRule('USER:3,3').userIds, [3])
})

test('ROLE：角色码命中（忽略大小写）通过，未命中拒绝', () => {
  assert.equal(matchesAssigneeRule('ROLE:ops_admin', 7, ['ops_admin']), true)
  assert.equal(matchesAssigneeRule('ROLE:ops_admin', 7, ['OPS_ADMIN']), true)
  assert.equal(matchesAssigneeRule('ROLE:ops_admin', 7, ['viewer']), false)
  assert.equal(matchesAssigneeRule('ROLE:ops_admin', 7, []), false)
})

test('非法规则：给中文原因且不命中', () => {
  assert.match(parseAssigneeRule('boss').problem ?? '', /只能写/)
  assert.match(parseAssigneeRule('USER:').problem ?? '', /至少/)
  assert.match(parseAssigneeRule('USER:abc').problem ?? '', /不是数字/)
  assert.match(parseAssigneeRule('USER:0').problem ?? '', /正整数/)
  assert.match(parseAssigneeRule('ROLE:').problem ?? '', /必须写角色码/)
  assert.match(parseAssigneeRule('ROLE:bad code').problem ?? '', /不合法/)
  assert.equal(matchesAssigneeRule('boss', 7, ['ops_admin']), false)
  assert.match(describeAssigneeRule('boss'), /规则非法/)
})

test('展示文案', () => {
  assert.equal(describeAssigneeRule('USER:1,2'), '指定用户：1,2')
  assert.equal(describeAssigneeRule('ROLE:ops'), '角色：ops')
})
