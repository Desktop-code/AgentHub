/**
 * 产物身份贯穿单测（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 直播增量：同名文件不同参与者各自成块，同一参与者重复帧原位覆盖；
 * 2) 回放：FILE 事件自带的 participantId / participantName 优先，不被上一条身份行改写；
 * 3) 旧事件没有身份字段时 fileOwnerOf 返回空，交给调用方走历史口径。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { TrialEvent } from '../src/types/runtime.ts'
import {
  appendFile,
  assistantTurn,
  fileOwnerOf,
  turnsFromEvents
} from '../src/components/runtime/trialMessages.ts'

/** 造一条 FILE 事件；正文与后端落库同形。 */
function fileEvent(
  id: number,
  seq: number,
  payload: Record<string, unknown>
): TrialEvent {
  return {
    id,
    sessionId: 7,
    seq,
    eventType: 'file',
    summary: String(payload.fileName || ''),
    contentText: JSON.stringify(payload)
  }
}

test('直播增量：两位参与者写出同名文件，各挂各的块，不互相覆盖', () => {
  const turn = assistantTurn('e-1', 'streaming')
  appendFile(turn, { fileName: '报告.md', size: 10, downloadUrl: '/f/a' }, 'p-1-张三', '张三')
  appendFile(turn, { fileName: '报告.md', size: 20, downloadUrl: '/f/b' }, 'p-1-李四', '李四')
  assert.equal(turn.blocks.length, 2)
  assert.deepEqual(
    turn.blocks.map((block) => `${block.participantId}:${block.participantName}`),
    ['p-1-张三:张三', 'p-1-李四:李四']
  )
})

test('直播增量：同一参与者的同名文件重复帧原位覆盖，位置与身份不变', () => {
  const turn = assistantTurn('e-2', 'streaming')
  appendFile(turn, { fileName: '报告.md', size: 10, downloadUrl: '/f/a' }, 'p-1-张三', '张三')
  appendFile(turn, { fileName: '其它.md', size: 5, downloadUrl: '/f/c' }, 'p-1-张三', '张三')
  appendFile(turn, { fileName: '报告.md', size: 99, downloadUrl: '/f/b' }, 'p-1-张三', '张三')
  assert.equal(turn.blocks.length, 2)
  assert.equal(turn.blocks[0].file?.size, 99)
  assert.equal(turn.blocks[0].file?.downloadUrl, '/f/b')
  assert.equal(turn.blocks[0].participantId, 'p-1-张三')
})

test('回放：FILE 事件自带身份优先，不被上一条 begin 行改写', () => {
  const turns = turnsFromEvents([
    {
      id: 1,
      sessionId: 7,
      seq: 1,
      eventType: 'participant',
      contentText: JSON.stringify({ id: 'p-0-root-enterprise', name: '企业综合台', kind: 'MA', begin: true })
    },
    fileEvent(2, 2, {
      fileName: '报告.md',
      size: 12,
      participantId: 'p-1-writer',
      participantName: '报告撰写专家'
    })
  ])
  const block = turns[0].blocks.find((item) => item.kind === 'file')
  assert.ok(block)
  assert.equal(block.participantId, 'p-1-writer')
  assert.equal(block.participantName, '报告撰写专家')
})

test('旧事件没有身份字段：fileOwnerOf 返回空，回放块不硬造作者', () => {
  assert.deepEqual(fileOwnerOf('{"fileName":"报告.md","size":12}'), { id: '', name: '' })
  const turns = turnsFromEvents([fileEvent(1, 1, { fileName: '报告.md', size: 12 })])
  const block = turns[0].blocks.find((item) => item.kind === 'file')
  assert.ok(block)
  assert.equal(block.participantId, undefined)
  assert.equal(block.participantName, undefined)
})
