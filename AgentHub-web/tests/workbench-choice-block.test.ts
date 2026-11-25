/**
 * 工作台可点选项机器块（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 合法块摘出正文、选项可读；
 * 2) 结构不合法（JSON 坏 / 选项过少过多 / id 重复 / 缺字段 / 无闭合 / 同前缀语言标记）整块作废，正文原样；
 * 3) 投影层：只有选项没有正文时仍是一块「本步结果」，不被判空、且能成为终局；
 * 4) 正文里的机器块不会漏到 answer 里。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import {
  ANSWER_CHANNEL_SPLIT_NOTE,
  ANSWER_MISSING_NOTE,
  buildParticipantFeed as projectParticipantFeed,
  isPromiseOnlyAnswer,
  parseChoiceBlock,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'

/** 断言仍按发生顺序看卡；嵌套之后专家在方案的 children 里，这里展开再对 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0]) {
  return visitParticipantCards(projectParticipantFeed(input))
}

/** 造一个合法选项块的 Markdown 文本 */
function choiceBlock(question: string, options: { id: string; label: string }[]): string {
  return ['```agenthub-choice', JSON.stringify({ question, options }), '```'].join('\n')
}

const TWO = [
  { id: 'vue3', label: 'Vue3 + Vite + TS' },
  { id: 'react', label: 'React + Vite + TS' }
]

function block(kind: AssistantBlock['kind'], text: string): AssistantBlock {
  return { kind, text }
}

test('合法块：正文被摘干净，选项按原顺序返回', () => {
  const raw = `请先确认技术栈。\n\n${choiceBlock('你希望用哪个技术栈？', TWO)}`
  const parsed = parseChoiceBlock(raw)
  assert.equal(parsed.text, '请先确认技术栈。')
  assert.equal(parsed.choice?.question, '你希望用哪个技术栈？')
  assert.deepEqual(parsed.choice?.options, TWO)
})

test('块后的正文接回原顺序', () => {
  const raw = `开头的说明\n${choiceBlock('选一个？', TWO)}\n补一句收尾`
  const parsed = parseChoiceBlock(raw)
  assert.equal(parsed.text, '开头的说明\n\n补一句收尾')
})

test('没有机器块：正文原样返回，不出选项', () => {
  const raw = '这就是一段普通回答，含 ```js 代码块```'
  const parsed = parseChoiceBlock(raw)
  assert.equal(parsed.text, raw)
  assert.equal(parsed.choice, undefined)
})

test('非法 JSON：整块作废，正文原样（不把裸块吞掉）', () => {
  const raw = ['说明', '```agenthub-choice', '{不是 JSON', '```'].join('\n')
  const parsed = parseChoiceBlock(raw)
  assert.equal(parsed.text, raw)
  assert.equal(parsed.choice, undefined)
})

test('选项少于 2 个：整块作废', () => {
  const raw = choiceBlock('只有一个？', [TWO[0]])
  assert.equal(parseChoiceBlock(raw).choice, undefined)
})

test('选项多于 6 个：整块作废', () => {
  const many = Array.from({ length: 7 }, (_, index) => ({ id: `op${index}`, label: `选项${index}` }))
  assert.equal(parseChoiceBlock(choiceBlock('太多？', many)).choice, undefined)
})

test('id 重复：整块作废（点位会歧义）', () => {
  const dup = [
    { id: 'a', label: '甲' },
    { id: 'A', label: '乙' }
  ]
  assert.equal(parseChoiceBlock(choiceBlock('重复？', dup)).choice, undefined)
})

test('缺 label / 空 id：整块作废', () => {
  const bad = [
    { id: '', label: '甲' },
    { id: 'b', label: '' }
  ]
  assert.equal(parseChoiceBlock(choiceBlock('空？', bad)).choice, undefined)
})

test('没有闭合围栏（还在流式 / 被截断）：不当选项', () => {
  const raw = ['说明', '```agenthub-choice', '{"question":"选","options":[{"id":"a","label":"甲"}]}'].join('\n')
  const parsed = parseChoiceBlock(raw)
  assert.equal(parsed.text, raw)
  assert.equal(parsed.choice, undefined)
})

test('同前缀语言标记（agenthub-choiceXYZ）不能被误认', () => {
  const raw = ['说明', '```agenthub-choiceXYZ', JSON.stringify({ question: '选', options: TWO }), '```'].join('\n')
  const parsed = parseChoiceBlock(raw)
  assert.equal(parsed.choice, undefined)
})

test('投影层：只有选项没有正文的专家块也是一块结果，并成为终局', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 技术顾问'),
    block('assistant', choiceBlock('你希望用哪个技术栈？', TWO))
  ]
  const lanes: ProcessLane[] = [{ kind: 'agent', title: '路由到 技术顾问', steps: [] }]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes,
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  assert.deepEqual(
    cards.map((card) => card.title),
    ['智能网关', '技术顾问']
  )
  const expert = cards[1]
  // 正文不再含裸机器块
  assert.equal(expert.answer.includes('agenthub-choice'), false)
  assert.equal(expert.choice?.options.length, 2)
  // 没有正文也不算「还在跑」，更不是空壳
  assert.equal(expert.pending, false)
  assert.equal(expert.isFinal, true)
})

test('投影层：正文与选项并存时两者都在，正文只有自然语言', () => {  const text = '我建议 Vue3，理由如下：生态成熟。'
  const blocks: AssistantBlock[] = [
    block('route', '路由到 技术顾问'),
    block('assistant', `${text}\n\n${choiceBlock('确认用 Vue3 吗？', TWO)}`)
  ]
  const lanes: ProcessLane[] = [{ kind: 'agent', title: '路由到 技术顾问', steps: [] }]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 技术顾问'],
    lanes,
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  const expert = cards[1]
  assert.equal(expert.answer, text)
  assert.equal(expert.choice?.question, '确认用 Vue3 吗？')
})

test('断点续作轮：不出入口判定块，完整回答直接落回同一位专家', () => {
  const blocks: AssistantBlock[] = [
    block('assistant', '按你选的 Vue3 + Vite + TS，完整落地方案如下：……')
  ]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [],
    blocks,
    streaming: false,
    fallbackName: '前端技术专家',
    isTopLevelOf: () => true
  })
  // 续作由同一位专家直接接着跑：后端不写 ROUTE 事件，前端也不能凭空补一块「已接收问题…」
  assert.deepEqual(cards.map((card) => card.kind), ['actor'])
  assert.equal(cards[0].title, '前端技术专家')
  assert.equal(cards[0].answer.includes('完整落地方案'), true)
})

test('续作终答：完整方案不被「已完成作答」收尾句顶掉，Markdown 空行保留', () => {
  // 真实续作轮形状：没有 ROUTE、没有参与者身份帧，只有专家推理 + 一长段带表格的正文
  const plan = [
    '## 前端技术栈方案',
    '',
    '| 项 | 选择 | 理由 |',
    '|---|---|---|',
    '| 框架 | Vue3 | 组合式 API 成熟 |',
    '| 构建 | Vite | 冷启动快 |',
    '',
    '### 一、组合',
    'Vue3 + Vite + TS + Element Plus。',
    '',
    '### 二、理由',
    '生态成熟、招聘容易、迁移成本低。',
    '',
    '### 三、落地',
    '1. 初始化项目',
    '2. 配置路由与权限'
  ].join('\n')
  const blocks: AssistantBlock[] = [
    block('reasoning', '用户选了 Vue3，我要给出完整方案'),
    block('assistant', plan)
  ]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [{ kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: '用户选了 Vue3，我要给出完整方案' }] }],
    blocks,
    streaming: false,
    fallbackName: '前端技术专家',
    isTopLevelOf: () => true
  })
  const answer = cards[0].answer
  // 收尾句不能出现在正文里（旧实现会先补一句状态句再把正文拼在后面）
  assert.equal(answer.includes('完成作答'), false)
  assert.equal(answer.startsWith('## 前端技术栈方案'), true)
  assert.equal(answer.includes('| 框架 | Vue3 |'), true)
  // 表格与标题之间必须还有空行，否则 Markdown 渲染不出表格
  assert.equal(answer.includes('| 构建 | Vite | 冷启动快 |\n\n### 一、组合'), true)
  assert.equal(cards[0].isFinal, true)
})

test('等入口报身份：新问题只有入口推理时先不出块（不先挂专家再改成入口）', () => {
  const blocks: AssistantBlock[] = [block('reasoning', '用户在问后端，应该转给后端专家')]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [{ kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: '用户在问后端，应该转给后端专家' }] }],
    blocks,
    streaming: true,
    fallbackName: '前端技术专家',
    expectEntry: true,
    isTopLevelOf: () => true
  })
  // 身份还没到：宁可晚一拍，也不要先画成「前端技术专家」再改成入口
  assert.deepEqual(cards, [])
})

test('续作轮不等入口身份：专家推理直接出块，不被拦截', () => {
  const blocks: AssistantBlock[] = [block('reasoning', '按用户所选方向展开方案')]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [{ kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: '按用户所选方向展开方案' }] }],
    blocks,
    streaming: true,
    fallbackName: '前端技术专家',
    expectEntry: false,
    isTopLevelOf: () => true
  })
  assert.equal(cards.length > 0, true)
  assert.equal(cards[0].title, '前端技术专家')
})

test('流式中只有末尾那一条是终局：前面刚写完的专家不提前铺开', () => {
  // 两位专家并行：甲已写完、乙还在跑。甲此刻只是「先交卷」，不是终局
  const lanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 前端技术专家', steps: [] },
    { kind: 'agent', title: '路由到 后端技术专家', steps: [] }
  ]
  const blocks: AssistantBlock[] = [
    block('route', '路由到 前端技术专家'),
    block('assistant', '前端方案全文'),
    block('route', '路由到 后端技术专家')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((b) => b.kind === 'route').map((b) => b.text),
    lanes,
    blocks,
    streaming: true,
    fallbackName: '助手',
    isTopLevelOf: () => true
  })
  const front = cards.find((card) => card.title.includes('前端'))
  assert.equal(front?.isFinal, false, '流式中前面的专家不能是终局')
})

test('流式中末尾专家已经开始出正文：它一开始就不限高', () => {
  const lanes: ProcessLane[] = [{ kind: 'agent', title: '路由到 后端技术专家', steps: [] }]
  const blocks: AssistantBlock[] = [block('route', '路由到 后端技术专家'), block('assistant', '后端方案开头…')]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 后端技术专家'],
    lanes,
    blocks,
    streaming: true,
    fallbackName: '助手',
    isTopLevelOf: () => true
  })
  const back = cards[cards.length - 1]
  assert.equal(back.isFinal, false, '直播中不把最后一张有正文的卡当成终局')
})

test('承诺 / 收尾句判定：短且整段是承诺才算，真方案不会误判', () => {
  assert.equal(isPromiseOnlyAnswer('点选后我会按该路径给出代码与配置。'), true)
  assert.equal(isPromiseOnlyAnswer('已完成作答'), true)
  assert.equal(isPromiseOnlyAnswer('已按要求完成，稍后可继续'), true)
  // 真正文即使开头带过渡句也整段很长：不能判成空结果
  const real = `## 方案\n\n${'具体配置与步骤。'.repeat(30)}`
  assert.equal(isPromiseOnlyAnswer(real), false)
  // 普通短结论里没有承诺句式
  assert.equal(isPromiseOnlyAnswer('年假按工龄计算，满一年 5 天'), false)
  // 短答但确实在给内容（有冒号 / 顿号）：不能被误判成承诺句
  assert.equal(isPromiseOnlyAnswer('我会给出以下三步：建工程、配依赖、写接口'), false)
  assert.equal(isPromiseOnlyAnswer(''), false)
})

test('续作把方案写进思考通道：结果框不搬草稿，也不留承诺句', () => {
  // 用户手点的形状：思考里有整份方案（含代码 / 步骤），正文只有一句「点选后我会给出…」
  const thinking = `先定组合：Java 17 + Spring Boot 3。\n${'落地步骤：建工程、配依赖、写接口。'.repeat(20)}`
  const blocks: AssistantBlock[] = [
    block('reasoning', thinking),
    block('assistant', '点选后我会按该路径给出代码与配置。')
  ]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [{ kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: thinking }] }],
    blocks,
    streaming: false,
    fallbackName: '前端技术专家',
    isTopLevelOf: () => true
  })
  assert.equal(cards.length, 1)
  const answer = cards[0].answer
  // 承诺句不能顶替方案
  assert.equal(answer.includes('点选后我会'), false)
  // 思考草稿不能被整段搬进结果框冒充终答
  assert.equal(answer.includes('落地步骤'), false)
  assert.equal(answer, '')
  assert.equal(cards[0].terminalNote, ANSWER_CHANNEL_SPLIT_NOTE)
})

test('正文只有收尾句且思考里也没内容：给一句明确说明，不假装完成', () => {
  const blocks: AssistantBlock[] = [block('assistant', '已完成作答')]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [],
    blocks,
    streaming: false,
    fallbackName: '前端技术专家',
    isTopLevelOf: () => true
  })
  assert.equal(cards[0].answer, '')
  assert.equal(cards[0].terminalNote, ANSWER_MISSING_NOTE)
})

test('真方案落到结果框时不受影响：不被说明句顶掉', () => {
  const plan = `## Java 17 + Spring Boot 3 落地方案\n\n1. 建工程\n2. 配依赖\n\n${'细节说明。'.repeat(30)}`
  const blocks: AssistantBlock[] = [
    block('reasoning', '先梳理要点 '.repeat(40)),
    block('assistant', plan)
  ]
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [{ kind: 'agent', title: '执行', steps: [{ type: 'reasoning', title: '推理', text: '先梳理要点 '.repeat(40) }] }],
    blocks,
    streaming: false,
    fallbackName: '前端技术专家',
    isTopLevelOf: () => true
  })
  assert.equal(cards[0].answer.startsWith('## Java 17 + Spring Boot 3 落地方案'), true)
  assert.equal(cards[0].answer.includes(ANSWER_CHANNEL_SPLIT_NOTE), false)
})
