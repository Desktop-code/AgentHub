/**
 * 入口预处理回放与附件分类。所属域：runtime（前端本地用例，不进仓）。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  attachPreprocessToCard,
  orderPreprocessItems,
  parsePreprocessEvent,
  presentPreprocess,
  splitPreprocessItems,
  turnsFromEvents
} from '../src/components/runtime/trialMessages.ts'
import { withEntryPreprocess } from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { classifyUserFile } from '../src/components/runtime/userMessageImages.ts'
import type { TrialEvent } from '../src/types/runtime.ts'

test('parsePreprocessEvent 还原进行中与完成快照', () => {
  const running = parsePreprocessEvent(
    JSON.stringify({ status: 'running', tasks: ['file'], hint: '正在抽文件', summary: '', text: '' })
  )
  assert.equal(running?.status, 'running')
  assert.equal(running?.hint, '正在抽文件')
  const done = parsePreprocessEvent(
    JSON.stringify({ status: 'done', tasks: ['vision'], hint: '', summary: '已识图并回传：发票', text: '发票' })
  )
  assert.equal(done?.text, '发票')
  assert.equal(parsePreprocessEvent('not-json'), undefined)
})

test('turnsFromEvents 把 preprocess 收成独立块', () => {
  const events: TrialEvent[] = [
    { id: 1, sessionId: 9, seq: 1, eventType: 'user', contentText: '', summary: 'ahfile:1|file|a.txt|12|/f/a.txt' },
    {
      id: 2,
      sessionId: 9,
      seq: 2,
      eventType: 'preprocess',
      summary: '已抽字并回传：独特句子',
      contentText: JSON.stringify({
        status: 'done',
        tasks: ['file'],
        hint: '',
        summary: '已抽字并回传：独特句子',
        text: '独特句子'
      })
    }
  ]
  const turns = turnsFromEvents(events)
  const assistant = turns.find((item) => item.role === 'assistant')
  assert.ok(assistant)
  const block = assistant?.blocks.find((item) => item.kind === 'preprocess')
  assert.ok(block)
  assert.equal(block?.preprocess?.status, 'done')
  assert.match(block?.text || '', /独特句子/)
})

test('识别正文按附件名拆开，并按发送顺序排', () => {
  const raw = [
    '帮我看看',
    '',
    '【附件正文】',
    '文件「后到.pdf」：',
    '备',
    '注',
    '',
    '文件「先到.pdf」：',
    '金额 12',
    '',
    '[图1内容]: 发票抬头'
  ].join('\n')
  const items = splitPreprocessItems(raw)
  assert.deepEqual(items.map((item) => item.name), ['后到.pdf', '先到.pdf', '图片1'])
  assert.match(items[0].body, /备注/)
  assert.equal(items[0].body.includes('【附件正文】'), false)
  const ordered = orderPreprocessItems(items, [
    { kind: 'file', name: '先到.pdf' },
    { kind: 'image', name: '截图.png' },
    { kind: 'file', name: '后到.pdf' }
  ])
  assert.deepEqual(ordered.map((item) => item.name), ['先到.pdf', '截图.png', '后到.pdf'])
  const shown = presentPreprocess({
    status: 'done',
    tasks: ['file'],
    hint: '',
    summary: '已抽字并回传',
    text: raw
  })
  assert.equal(shown?.items.length, 3)
  assert.equal(presentPreprocess(undefined), undefined)
})

test('抽字预览把单字行收成横排，长文只露前段', () => {
  const shown = presentPreprocess({
    status: 'done',
    tasks: ['file'],
    hint: '',
    summary: '已抽字并回传：发票',
    text: `【附件正文】\n开票人：牛雪艳\n备\n注\n销\n售\n方\n${'甲'.repeat(400)}`
  })
  assert.equal(shown?.title, '已抽字并回传')
  assert.match(shown?.full || '', /备注销售方/)
  assert.equal((shown?.full || '').includes('【附件正文】'), false)
  assert.equal(shown?.expandable, true)
  assert.ok((shown?.preview.length || 0) < (shown?.full.length || 0))
  assert.equal(presentPreprocess(undefined), undefined)
})

test('预处理挂进网关卡，不在入口外面再起一块', () => {
  const gateway = {
    key: 'gw',
    kind: 'gateway' as const,
    title: '智能网关',
    lines: ['判定「报销」分发给「智能助手」'],
    steps: [],
    answer: '',
    errors: [],
    files: [],
    pending: false
  }
  const cards = withEntryPreprocess(
    [gateway],
    [
      {
        kind: 'preprocess',
        text: '发票正文',
        preprocess: {
          status: 'done',
          tasks: ['file'],
          hint: '',
          summary: '已抽字并回传',
          text: '发票正文'
        }
      }
    ],
    '智能网关'
  )
  assert.equal(cards.length, 1)
  assert.equal(cards[0].kind, 'gateway')
  assert.equal(cards[0].preprocess?.title, '已抽字并回传')
  assert.equal(cards[0].preprocess?.full, '发票正文')
  assert.match(cards[0].lines[0], /判定/)
  assert.equal(cards[0].files.length, 0)
})

test('MA 路由卡与首页网关卡挂的是同一份预处理投影', () => {
  const view = {
    status: 'done',
    tasks: ['file'],
    hint: '',
    summary: '已抽字并回传',
    text: '备\n注'
  }
  const folded = attachPreprocessToCard([{ kind: 'router', summary: '判定「报销」分发给「智能助手」' }], view, 'router')
  assert.equal(folded.cards.length, 1)
  assert.equal(folded.cards[0].kind, 'router')
  assert.equal(folded.cards[0].preprocess?.title, '已抽字并回传')
  assert.equal(folded.cards[0].preprocess?.full, '备注')
  assert.match(folded.cards[0].summary, /判定/)
  const missing = attachPreprocessToCard([], undefined, 'router')
  assert.equal(missing.cards.length, 0)
  assert.equal(missing.shown, undefined)
})

test('没有预处理块时不补入口空壳', () => {
  const cards = withEntryPreprocess([], [], '智能网关')
  assert.equal(cards.length, 0)
})

test('录音扩展名按文件收进待发列表', () => {
  const audio = { name: 'voice.webm', type: 'audio/webm' } as File
  assert.equal(classifyUserFile(audio), 'file')
  const exe = { name: 'setup.exe', type: 'application/x-msdownload' } as File
  assert.equal(classifyUserFile(exe), 'blocked')
})
