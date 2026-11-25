/**
 * 用户消息附图：粘贴只取图片，回放图在文字字段之外且保持顺序。所属域：runtime。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { turnsFromEvents } from '../src/components/runtime/trialMessages.ts'
import {
  ATTACHMENT_PREVIEW_COUNT,
  MAX_USER_ATTACHMENTS,
  classifyUserFile,
  clipboardAttachments,
  clipboardImageFiles,
  previewAttachments,
  readUserMessage
} from '../src/components/runtime/userMessageImages.ts'
import type { TrialEvent } from '../src/types/runtime.ts'

const FIRST = '/uploads/trial-images/4/a.png'
const SECOND = '/uploads/trial-images/4/b.png'

test('回放把清单里的图按顺序挂到用户轮，文字仍是正文', () => {
  const events: TrialEvent[] = [
    {
      id: 8,
      seq: 1,
      eventType: 'user',
      contentText: '看这两张',
      summary: `ahimg:2:${FIRST}|${SECOND}`
    }
  ]
  const turns = turnsFromEvents(events)
  assert.equal(turns.length, 1)
  assert.equal(turns[0].role, 'user')
  assert.equal(turns[0].text, '看这两张')
  assert.deepEqual(turns[0].images, [FIRST, SECOND])
})

test('只发图时仍有用户轮，文字为空', () => {
  const message = readUserMessage(null, `ahimg:1:${FIRST}`)
  assert.equal(message.text, '')
  assert.deepEqual(message.images, [FIRST])
  const turns = turnsFromEvents([
    { id: 3, seq: 1, eventType: 'user', contentText: '', summary: `ahimg:1:${FIRST}` }
  ])
  assert.equal(turns.length, 1)
  assert.equal(turns[0].text, '')
  assert.deepEqual(turns[0].images, [FIRST])
})

test('普通文字粘贴和旧消息不受清单规则影响', () => {
  assert.deepEqual(readUserMessage('你好', '你好'), { text: '你好', images: [], attachments: [] })
  const textItem = {
    kind: 'string',
    type: 'text/plain',
    getAsFile: () => null
  }
  assert.equal(clipboardImageFiles({ items: [textItem] }).length, 0)
})

test('剪贴板图片按出现顺序取出，文字项跳过', () => {
  const first = new File([new Uint8Array([1])], 'one.png', { type: 'image/png' })
  const second = new File([new Uint8Array([2])], 'two.png', { type: 'image/png' })
  const files = clipboardImageFiles({
    items: [
      { kind: 'string', type: 'text/plain', getAsFile: () => null },
      { kind: 'file', type: 'image/png', getAsFile: () => first },
      { kind: 'file', type: 'image/jpeg', getAsFile: () => second }
    ]
  })
  assert.equal(files[0], first)
  assert.equal(files[1], second)
})

test('资源管理器复制的文件按添加顺序进入待发，图片不降级成普通文件', () => {
  const image = new File([new Uint8Array([1])], 'shot.png', { type: '' })
  const pdf = new File([new Uint8Array([2])], '报表.pdf', { type: '' })
  const exe = new File([new Uint8Array([3])], 'setup.exe', { type: '' })
  const picks = clipboardAttachments({
    items: [
      { kind: 'string', type: 'text/plain', getAsFile: () => null },
      { kind: 'file', type: '', getAsFile: () => image },
      { kind: 'file', type: '', getAsFile: () => pdf },
      { kind: 'file', type: 'application/octet-stream', getAsFile: () => exe }
    ]
  })
  assert.deepEqual(picks.map((item) => item.kind), ['image', 'file', 'blocked'])
  assert.equal(classifyUserFile(pdf), 'file')
  assert.equal(clipboardImageFiles({ items: [{ kind: 'file', type: '', getAsFile: () => pdf }] }).length, 0)
})

test('带回看文件行，图和文件按摘要顺序排在文字之外', () => {
  const summary = 'ahfile:2:i,/uploads/trial-images/4/a.png|f,报表.pdf,2048,/uploads/trial-files/4/b.pdf'
  const message = readUserMessage('看一下', summary)
  assert.equal(message.text, '看一下')
  assert.deepEqual(message.images, ['/uploads/trial-images/4/a.png'])
  assert.equal(message.attachments[0].kind, 'image')
  assert.equal(message.attachments[1].kind, 'file')
  assert.equal(message.attachments[1].name, '报表.pdf')
  assert.equal(message.attachments[1].size, 2048)
  const turns = turnsFromEvents([
    { id: 9, seq: 1, eventType: 'user', contentText: '', summary: 'ahfile:1:f,a.xlsx,100,/uploads/trial-files/4/c.xlsx' }
  ])
  assert.equal(turns.length, 1)
  assert.equal(turns[0].text, '')
  assert.equal(turns[0].attachments?.[0].name, 'a.xlsx')
})

test('压缩清单能还原完整地址，默认只露出 3 个', () => {
  const summary = 'ahfile:4:7:i,a.png|f,报表.pdf,2048,b.pdf|f,表.csv,3,c.csv|f,说明.docx,8,d.docx'
  const message = readUserMessage('', summary)
  assert.equal(message.attachments.length, 4)
  assert.equal(message.attachments[0].url, '/uploads/trial-images/7/a.png')
  assert.equal(message.attachments[1].name, '报表.pdf')
  assert.equal(message.attachments[1].url, '/uploads/trial-files/7/b.pdf')
  const folded = previewAttachments(message.attachments, false)
  assert.equal(folded.shown.length, ATTACHMENT_PREVIEW_COUNT)
  assert.equal(folded.rest, 1)
  assert.equal(previewAttachments(message.attachments, true).shown.length, 4)
  assert.equal(MAX_USER_ATTACHMENTS, 9)
})

test('文件清单损坏时给出失败提示，不把摘要当成正文', () => {
  const message = readUserMessage('', 'ahfile:1:broken')
  assert.equal(message.text, '')
  assert.equal(message.attachments[0].name, '附件加载失败')
  assert.equal(message.attachments[0].url, '')
})

test('资源管理器只放在 files 里的文件仍按顺序收下，百分号文件名不被拆开', () => {
  const pdf = new File([new Uint8Array([2])], 'a.pdf', { type: '' })
  const picks = clipboardAttachments({ files: [pdf] })
  assert.equal(picks.length, 1)
  assert.equal(picks[0].kind, 'file')
  const summary = 'ahfile:1:f,报价%257C表.pdf,12,/uploads/trial-files/4/d.pdf'
  const message = readUserMessage('看报价', summary)
  assert.equal(message.attachments[0].name, '报价%7C表.pdf')
  assert.equal(message.text, '看报价')
})
