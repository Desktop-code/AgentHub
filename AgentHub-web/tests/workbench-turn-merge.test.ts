/**
 * 收尾轮次合并单测。所属域：runtime（前端）。
 *
 * <p>这条逻辑直接决定「终答落地时整行会不会卸载重建」（行 key / 块 id 是否沿用），
 * 是纯数据口径，不需要 DOM / Vue 响应式即可覆盖。
 *
 * 运行：node --experimental-strip-types --test tests/workbench-turn-merge.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { mergeTurnsAfterRun } from '../src/components/runtime/trialMerge.ts'
import type { ChatTurn } from '../src/types/runtime.ts'

/**
 * 造一个用户轮。
 * @param key 行 key
 * @param text 气泡正文
 */
function userTurn(key: string, text: string): ChatTurn {
  return { key, role: 'user', text, blocks: [], phase: 'done', status: 'done', startTime: 0 }
}

/**
 * 造一个助手轮。
 * @param key    行 key
 * @param blocks 块列表
 * @param extra  额外字段
 */
function assistantTurn(
  key: string,
  blocks: ChatTurn['blocks'] = [],
  extra: Partial<ChatTurn> = {}
): ChatTurn {
  return {
    key,
    role: 'assistant',
    text: '',
    blocks,
    phase: 'done',
    status: 'done',
    startTime: 0,
    ...extra
  }
}

test('收尾合并：沿用本地行 key，终答落地不卸载整行', () => {
  const local = [userTurn('wb-u-1', '问题'), assistantTurn('wb-a-1')]
  const fresh = [userTurn('u-10', '问题'), assistantTurn('e-11')]

  const merged = mergeTurnsAfterRun(local, fresh)

  assert.deepEqual(
    merged.map((turn) => turn.key),
    ['wb-u-1', 'wb-a-1']
  )
  // 内容仍以落库回放为准
  assert.equal(merged[0].text, '问题')
})

test('收尾合并：块序沿用本地，配对上的块沿用旧 id，只把内容换成回放的', () => {
  const local = [
    assistantTurn('wb-a-1', [
      { id: 'wb-a-1#b1', kind: 'reasoning', text: '想' },
      { id: 'wb-a-1#b2', kind: 'assistant', text: '答' }
    ])
  ]
  const fresh = [
    assistantTurn('e-11', [
      { id: 'e-11#b1', kind: 'reasoning', text: '想更久' },
      { id: 'e-11#b2', kind: 'tool', text: '调用' },
      { id: 'e-11#b3', kind: 'assistant', text: '答完整' }
    ])
  ]

  const merged = mergeTurnsAfterRun(local, fresh)

  // 本地已有顺序不动：推理 → 正文；回放多出来的工具块按落库相邻关系补在它前面（推理之后）
  assert.deepEqual(
    merged[0].blocks.map((block) => block.kind),
    ['reasoning', 'tool', 'assistant']
  )
  assert.equal(merged[0].blocks[0].id, 'wb-a-1#b1', '配对上的块沿用作旧 id')
  assert.equal(merged[0].blocks[2].id, 'wb-a-1#b2', '配对上的块沿用作旧 id')
  assert.equal(merged[0].blocks[2].text, '答完整', '内容一律以回放为准')
  assert.equal(merged[0].blockSeq, 3, '自增序号不得小于已有块数')
})

test('收尾合并：落库把工作流写在后、流式已把它画在前——合并后位置不换', () => {
  // 流式：决策 → 工作流路由 → 工作流卡 → 方案路由 → 子专家正文
  const local = [
    assistantTurn('wb-a-1', [
      { id: 'wb-a-1#d', kind: 'route', text: '路由决策\n已拆成 2 个问题，按序交给「企业综合台」「制度要点与报告」' },
      { id: 'wb-a-1#wf-route', kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
      {
        id: 'wb-a-1#wf',
        kind: 'workflow',
        text: '制度要点与报告',
        workflow: { name: '制度要点与报告', code: 'wf_report', status: 'RUNNING', answer: '', nodes: [] }
      },
      { id: 'wb-a-1#ma', kind: 'route', text: '路由到 企业综合台（enterprise）' },
      { id: 'wb-a-1#child', kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度' }
    ])
  ]
  // 落库：分段顺序是方案在前，工作流事件写在方案分段之后
  const fresh = [
    assistantTurn('e-11', [
      { id: 'e-11#d', kind: 'route', text: '路由决策\n已拆成 2 个问题，按序交给「企业综合台」「制度要点与报告」' },
      { id: 'e-11#ma', kind: 'route', text: '路由到 企业综合台（enterprise）' },
      { id: 'e-11#child', kind: 'route', text: '路由到 制度知识专家（policy），依据：查制度' },
      { id: 'e-11#ans', kind: 'assistant', text: '制度要点：年假按工龄计算。' },
      { id: 'e-11#wf-route', kind: 'route', text: '路由到 制度要点与报告（wf_report）' },
      {
        id: 'e-11#wf',
        kind: 'workflow',
        text: '制度要点与报告',
        workflow: { name: '制度要点与报告', code: 'wf_report', status: 'SUCCESS', answer: '报告已产出。', nodes: [] }
      }
    ])
  ]

  const merged = mergeTurnsAfterRun(local, fresh)
  const blocks = merged[0].blocks
  const names = blocks.map((block) => `${block.kind}:${block.text.split('\n').slice(-1)[0]}`)

  // 工作流卡仍在方案之前（本地位置），内容已换成终态；回放多出来的子专家正文补在它对应的路由之后
  assert.deepEqual(
    blocks.map((block) => block.kind),
    ['route', 'route', 'workflow', 'route', 'route', 'assistant']
  )
  assert.equal(blocks[2].id, 'wb-a-1#wf', '工作流块沿用本地 id，不重建')
  assert.equal(blocks[2].workflow?.status, 'SUCCESS', '终态由回放补上')
  assert.equal(blocks[2].workflow?.answer, '报告已产出。')
  assert.equal(blocks[5].text, '制度要点：年假按工龄计算。', '多出来的正文按回放相邻关系补位')
  assert.equal(names.length, 6)
})

test('收尾合并：结构对不上时整表以回放为准，不做错配', () => {
  // 本地少了一轮（例如 SSE 丢过事件）：角色序列不一致
  const local = [userTurn('wb-u-1', '问题一')]
  const fresh = [userTurn('u-10', '问题一'), assistantTurn('e-11')]

  const merged = mergeTurnsAfterRun(local, fresh)

  assert.deepEqual(
    merged.map((turn) => turn.key),
    ['u-10', 'e-11'],
    '结构不一致时不能沿用本地 key'
  )
})

test('收尾合并：本地独有语义（入口身份 / 续作 / 待批）不丢', () => {
  const local = [
    assistantTurn('wb-a-1', [], {
      entryParticipantId: 'gw-1',
      continuation: true,
      planPending: true,
      planId: 'p-1'
    })
  ]
  const fresh = [assistantTurn('e-11')]

  const merged = mergeTurnsAfterRun(local, fresh)

  assert.equal(merged[0].entryParticipantId, 'gw-1')
  assert.equal(merged[0].continuation, true)
  assert.equal(merged[0].planPending, true)
  assert.equal(merged[0].planId, 'p-1')
})

test('收尾合并：回放已经带上的身份盖过本地旧戳', () => {
  const local = [
    assistantTurn('wb-a-1', [
      { id: 'wb-a-1#b1', kind: 'assistant', text: '半截', participantId: 'p-old', participantName: '智能助手' }
    ])
  ]
  const fresh = [
    assistantTurn('e-11', [
      { id: 'e-11#b1', kind: 'assistant', text: '### 文稿起草\n正文', participantId: 'p-draft', participantName: '文稿起草' }
    ])
  ]

  const merged = mergeTurnsAfterRun(local, fresh)

  assert.equal(merged[0].blocks[0].id, 'wb-a-1#b1')
  assert.equal(merged[0].blocks[0].participantId, 'p-draft')
  assert.equal(merged[0].blocks[0].participantName, '文稿起草')
})

test('收尾合并：两侧都为空时安全返回空数组', () => {
  assert.deepEqual(mergeTurnsAfterRun([], []), [])
})
