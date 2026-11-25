/**
 * 工作台消息流按参与者分块（node:test，无浏览器）。所属域：runtime（前端）。
 *
 * 覆盖：
 * 1) 网关判定条在前，子专家各自带结论，方案只在有汇总结论时排在最后；
 * 2) 没有汇总结论时不出现方案块，也不复制子专家正文；
 * 3) 拼接终答不挂到最后一位；
 * 4) 嵌套方案同样不先出空壳；
 * 5) 重复方案路由不连出空壳；文件和工具失败跟到对应专家。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import type { AssistantBlock } from '../src/types/runtime.ts'
import type { ProcessLane } from '../src/components/runtime/trialMessages.ts'
import {
  buildParticipantFeed as projectParticipantFeed,
  isFinishedStatusShell,
  visitParticipantCards
} from '../src/components/runtime/workbench/workbenchParticipantFeed.ts'
import { stampBeginLanes } from './stampBegin.ts'

/** 旧用例用 targetType / isTopLevel 描述 begin 形状；投影本身不再读这两个回调 */
function buildParticipantFeed(input: Parameters<typeof projectParticipantFeed>[0] & {
  targetTypeOf?: (name: string) => string | undefined
  isTopLevelOf?: (name: string) => boolean
}) {
  return visitParticipantCards(projectParticipantFeed({
    ...input,
    lanes: stampBeginLanes(input.lanes, input.targetTypeOf, input.isTopLevelOf)
  }))
}
import { WORKFLOW_ROLE_LABEL, buildThinkTimeline } from '../src/components/runtime/workbench/workbenchThinkTimeline.ts'
import { parseRouteParticipant } from '../src/components/runtime/workbench/workbenchRouteText.ts'

function block(kind: AssistantBlock['kind'], text: string, detail?: string): AssistantBlock {
  return { kind, text, detail }
}

const lanes: ProcessLane[] = [
  { kind: 'agent', title: '路由到 企业综合台', steps: [] },
  {
    kind: 'agent',
    title: '路由到 知识专家',
    body: '依据：查制度',
    steps: [{ type: 'tool_result', title: '工具结果', text: '制度条款' }]
  },
  {
    kind: 'agent',
    title: '路由到 报告专家',
    steps: [{ type: 'reasoning', title: '推理', text: '按结论写稿' }]
  }
]

test('专家收进所属方案框，方案正文不再列专家名单', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 知识专家（kb），依据：查制度'),
    block('assistant', '制度结论：年假按工龄计算'),
    block('route', '路由到 报告专家'),
    block('assistant', '报告正文已根据制度写完'),
    block('route', '方案结论：企业综合台'),
    block('assistant', '综合台汇总：年假规则已写入报告')
  ]
  const cards = projectParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: stampBeginLanes(lanes, (name) => (name === '企业综合台' ? 'MA' : 'AGENT'), (name) => name === '企业综合台'),
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  // 根上只留入口和方案。专家不跟方案并排
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:企业综合台'
  ])
  const scheme = cards[1]
  assert.deepEqual((scheme.children || []).map((card) => card.title), ['知识专家', '报告专家'])
  assert.equal(scheme.lines.some((line) => line.includes('已把问题分给')), false)
  assert.equal(scheme.summary?.kind, 'conclusion')
  assert.equal(scheme.summary?.answer.includes('综合台汇总'), true)
  assert.equal(scheme.summary?.answer.includes('报告正文已根据制度写完'), false)
  assert.equal(scheme.children?.[0].answer.includes('年假按工龄计算'), true)
  assert.equal(scheme.children?.[1].answer.includes('报告正文'), true)
})

test('并行直播时专家按自己的 begin 身份进所属方案，刷新回放同一套投影', () => {
  // 直播到达序：四个方案先全部 begin，专家才到。回放落库序：方案后面紧跟自己的专家
  const schemes: ProcessLane[] = [
    actorLane('文件车间', 'p-0-root-ws_file', 'scheme', 0),
    actorLane('数据车间', 'p-0-root-ws_data', 'scheme', 0),
    actorLane('合规车间', 'p-0-root-ws_audit', 'scheme', 0),
    actorLane('短键车间', 'p-0-root-ws', 'scheme', 0),
    actorLane('文书车间', 'p-0-root-ws-doc', 'scheme', 0)
  ]
  const inventory = actorLane('清单专家', 'p-1-ws_file-inventory', 'agent', 1)
  const rollup = actorLane('汇总专家', 'p-1-ws_data-rollup', 'agent', 1)
  const clerk = actorLane('文书专家', 'p-1-ws-doc-clerk', 'agent', 1)
  const liveLanes = [...schemes, inventory, rollup, clerk]
  const replayLanes = [schemes[0], inventory, schemes[1], rollup, schemes[2], schemes[3], schemes[4], clerk]
  const blocks: AssistantBlock[] = [
    {
      kind: 'assistant',
      text: '目录清单表已列出',
      participantId: 'p-1-ws_file-inventory',
      participantName: '清单专家'
    },
    {
      kind: 'assistant',
      text: '数据汇总已完成',
      participantId: 'p-1-ws_data-rollup',
      participantName: '汇总专家'
    }
  ]
  const live = projectWorkshops(liveLanes, blocks, true)
  const replay = projectWorkshops(replayLanes, blocks, false)
  assert.deepEqual(live.titles, replay.titles)
  assert.deepEqual(live.titles, ['智能网关', '文件车间', '数据车间', '合规车间', '短键车间', '文书车间'])
  assert.deepEqual(live.children.get('文件车间'), ['清单专家'])
  assert.deepEqual(live.children.get('数据车间'), ['汇总专家'])
  assert.deepEqual(live.children.get('合规车间'), [])
  assert.deepEqual(live.children.get('短键车间'), [])
  assert.deepEqual(live.children.get('文书车间'), ['文书专家'])
  assert.deepEqual(replay.children.get('文件车间'), ['清单专家'])
  assert.deepEqual(replay.children.get('数据车间'), ['汇总专家'])
  assert.deepEqual(replay.children.get('文书车间'), ['文书专家'])
  const file = live.cards.find((card) => card.title === '文件车间')
  const audit = live.cards.find((card) => card.title === '合规车间')
  assert.equal(file?.children?.[0].answer.includes('目录清单表'), true)
  assert.equal(audit?.answer.includes('目录清单表'), false)
  assert.equal((audit?.children || []).some((card) => card.title === '清单专家' || card.title === '汇总专家'), false)
  // 时间线收组也走同一条认父：名单写在所属方案上，不写进当时最后一个车间
  const steps = buildThinkTimeline({
    routeTexts: liveLanes.map((lane) => lane.title),
    lanes: liveLanes,
    streaming: true,
    fallbackName: '智能网关'
  })
  const fileStep = steps.find((step) => step.title === '文件车间')
  const auditStep = steps.find((step) => step.title === '合规车间')
  assert.equal(fileStep?.lines.some((line) => line.includes('清单专家')), true)
  assert.equal(auditStep?.lines.some((line) => line.includes('清单专家') || line.includes('汇总专家')), false)
})

test('整轮收口跟入口走，排在车间卡之后，并列出本轮文件', () => {
  const lanes: ProcessLane[] = [
    actorLane('文件车间', 'p-0-root-ws_file', 'scheme', 0),
    actorLane('数据车间', 'p-0-root-ws_data', 'scheme', 0),
    actorLane('合规车间', 'p-0-root-ws_audit', 'scheme', 0),
    actorLane('文书车间', 'p-0-root-ws_doc', 'scheme', 0),
    actorLane('清单专家', 'p-1-ws_file-inventory', 'agent', 1),
    actorLane('汇总专家', 'p-1-ws_data-rollup', 'agent', 1)
  ]
  const blocks: AssistantBlock[] = [
    {
      kind: 'assistant',
      text: '目录清单表已列出。',
      participantId: 'p-1-ws_file-inventory',
      participantName: '清单专家'
    },
    {
      kind: 'file',
      text: '目录清单.md',
      participantId: 'p-1-ws_file-inventory',
      participantName: '清单专家',
      file: { fileName: '目录清单.md', size: 20, downloadUrl: '/f/list' }
    },
    {
      kind: 'assistant',
      text: '数据汇总已完成。',
      participantId: 'p-1-ws_data-rollup',
      participantName: '汇总专家'
    },
    { kind: 'route', text: '方案结论：文件车间' },
    { kind: 'assistant', text: '文件车间已把目录整理完。' },
    { kind: 'route', text: '方案结论：企业综合台' },
    { kind: 'assistant', text: '文件车间\n目录清单已列出。\n\n数据车间\n汇总表已写出。' }
  ]
  const cards = projectParticipantFeed({
    routeTexts: lanes.map((lane) => lane.title),
    lanes,
    blocks,
    streaming: false,
    entryName: '企业综合台',
    fallbackName: '企业综合台'
  })
  assert.deepEqual(cards.map((card) => card.title), [
    '企业综合台',
    '文件车间',
    '数据车间',
    '合规车间',
    '文书车间',
    '企业综合台'
  ])
  const round = cards[cards.length - 1]
  assert.equal(round.kind, 'conclusion')
  assert.equal(round.roundDeliverable, true)
  assert.equal(round.roleLabel, '最终产物')
  assert.equal(round.answer.includes('目录清单已列出'), true)
  assert.equal(round.answer.includes('汇总表已写出'), true)
  assert.equal(round.answer.includes('###'), false)
  assert.equal(round.terminalNote || '', '')
  assert.deepEqual((round.files || []).map((file) => file.fileName), ['目录清单.md'])
  assert.equal(round.files[0].downloadUrl, '/f/list')
  const fileShop = cards.find((card) => card.title === '文件车间')
  const dataShop = cards.find((card) => card.title === '数据车间')
  const auditShop = cards.find((card) => card.title === '合规车间')
  assert.deepEqual((fileShop?.children || []).map((card) => card.title), ['清单专家'])
  assert.deepEqual((dataShop?.children || []).map((card) => card.title), ['汇总专家'])
  assert.equal((auditShop?.children || []).length, 0)
  assert.equal(fileShop?.summary?.answer.includes('文件车间已把目录整理完'), true)
  assert.equal(fileShop?.children?.[0].files[0].fileName, '目录清单.md')
  assert.equal(auditShop?.summary, undefined)
  assert.equal(cards.some((card) => (card.children || []).some((child) => child.kind === 'conclusion')), false)
})

/**
 * 四个车间并行时的投影。直播、回放只换泳道先后，投影函数是同一个。
 *
 * @param lanes     begin 先后
 * @param blocks    专家正文
 * @param streaming 是否仍在直播
 * @return 根标题与各方案框内的专家名
 */
function projectWorkshops(lanes: ProcessLane[], blocks: AssistantBlock[], streaming: boolean) {
  const cards = projectParticipantFeed({
    routeTexts: lanes.map((lane) => lane.title),
    lanes,
    blocks,
    streaming,
    fallbackName: '智能网关'
  })
  const children = new Map<string, string[]>()
  for (const card of cards) {
    if (card.participantKind === 'scheme') {
      children.set(card.title, (card.children || []).map((child) => child.title))
    }
  }
  return { cards, titles: cards.map((card) => card.title), children }
}

/**
 * 一条已经带好 begin 身份的泳道。测试不靠名字猜父。
 *
 * @param title 展示名
 * @param id    begin id
 * @param kind  scheme / agent
 * @param depth begin depth
 * @return 泳道
 */
function actorLane(title: string, id: string, kind: string, depth: number): ProcessLane {
  return {
    kind: 'agent',
    title: `路由到 ${title}`,
    participantId: id,
    participantKind: kind,
    participantDepth: depth,
    steps: []
  }
}

test('网关判定条在前，子专家各自带结论，方案汇总另起一条（不回填分流那条）', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 知识专家（kb），依据：查制度'),
    block('assistant', '制度结论：年假按工龄计算'),
    block('route', '路由到 报告专家'),
    block('assistant', '报告正文已根据制度写完'),
    block('route', '方案结论：企业综合台'),
    block('assistant', '综合台汇总：年假规则已写入报告')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes,
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  const titles = cards.map((card) => `${card.kind}:${card.title}`)
  // 分流一条在最上（冻结），专家依次，汇总再单独一条压在最下
  assert.deepEqual(titles, [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:知识专家',
    'actor:报告专家',
    'conclusion:企业综合台'
  ])
  assert.ok(cards[0].lines.some((line) => line.includes('已分发给') && line.includes('企业综合台')))
  // 分流那条：只保留分流结果，事后没有被汇总改写
  const dispatch = cards[1]
  assert.equal(dispatch.roleLabel, '方案')
  // 专家已经在方案框里，不再把名单写进方案正文
  assert.equal(dispatch.lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((dispatch.children || []).map((card) => card.title), ['知识专家', '报告专家'])
  assert.equal(dispatch.answer, '')
  assert.equal(dispatch.steps.length, 0)
  // 汇总那条：正文只有汇总，不复述子专家 / 分流句
  const summary = cards[4]
  assert.equal(summary.kind, 'conclusion')
  assert.equal(summary.roleLabel, '方案')
  assert.deepEqual(summary.lines, [])
  assert.equal(summary.answer.includes('综合台汇总'), true)
  assert.equal(summary.answer.includes('报告正文已根据制度写完'), false)
  assert.equal(cards.some((card) => card.lines.some((line) => line.includes('没有额外的思考记录') || line.includes('route_key'))), false)
  assert.equal(cards[2].answer.includes('年假按工龄计算'), true)
  assert.equal(cards[3].answer.includes('报告正文'), true)
  assert.equal(cards[2].roleLabel, undefined)
})

test('并行落库的拼接终答按「### 专家名」分段发回各自的作者', () => {
  // 真实回放：一轮只有一条 ASSISTANT，正文是后端 CONCAT 的「### 甲 … ### 乙 …」
  const blocks: AssistantBlock[] = [
    block('route', '路由到 知识专家'),
    block('route', '路由到 报告专家'),
    block('assistant', '### 知识专家\n制度：年假按工龄\n\n### 报告专家\n报告：待补全稿')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 知识专家', '路由到 报告专家'],
    lanes: [
      { kind: 'agent', title: '路由到 知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  const titles = cards.map((card) => card.title)
  assert.deepEqual(titles, ['智能网关', '知识专家', '报告专家'])
  assert.equal(cards[1].answer, '制度：年假按工龄')
  assert.equal(cards[2].answer, '报告：待补全稿')
})

test('历史回放：方案名下的单篇报告带「### （一）」章节，不得拆成多张专家卡', () => {
  // 复现：直播正常；离开后再点回来，报告被拆成标题为「（一）…」「（二）…」的假 Agent 卡
  const report = [
    '### （一）制度要点',
    '',
    '- 年假按工龄计算',
    '- 满一年 5 天',
    '',
    '### （二）落实建议',
    '',
    '1. 人事备案',
    '2. 系统同步'
  ].join('\n')
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 报告撰写专家（writer）'),
    block('assistant', report),
    {
      kind: 'file',
      text: '年假说明.md',
      file: { fileName: '年假说明.md', size: 128, downloadUrl: '/files/年假说明.md' }
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  const titles = cards.map((card) => card.title)
  assert.equal(titles.includes('（一）制度要点'), false)
  assert.equal(titles.includes('（二）落实建议'), false)
  assert.deepEqual(
    cards.map((card) => `${card.kind}:${card.title}`),
    ['gateway:智能网关', 'actor:企业综合台', 'actor:报告撰写专家']
  )
  const expert = cards.find((card) => card.title === '报告撰写专家')
  assert.ok(expert)
  assert.equal(expert.answer.includes('### （一）制度要点'), true)
  assert.equal(expert.answer.includes('### （二）落实建议'), true)
  assert.equal(expert.answer.includes('年假按工龄计算'), true)
  assert.equal(expert.files.length, 1)
})

test('CONCAT 子正文里嵌套章节标题时，章节必须留在作者段内', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 知识专家'),
    block('route', '路由到 报告专家'),
    block(
      'assistant',
      [
        '### 知识专家',
        '制度摘要。',
        '',
        '### 报告专家',
        '### （一）背景',
        '',
        '说明一段。',
        '',
        '### （二）建议',
        '',
        '- 第一点',
        '- 第二点'
      ].join('\n')
    )
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 知识专家', '路由到 报告专家'],
    lanes: [
      { kind: 'agent', title: '路由到 知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  assert.deepEqual(
    cards.map((card) => card.title),
    ['智能网关', '知识专家', '报告专家']
  )
  assert.equal(cards[1].answer, '制度摘要。')
  assert.equal(cards[2].answer.includes('### （一）背景'), true)
  assert.equal(cards[2].answer.includes('### （二）建议'), true)
  assert.equal(cards[2].answer.includes('第一点'), true)
  assert.equal(cards.some((card) => card.title.startsWith('（一）') || card.title.startsWith('（二）')), false)
})

test('并行轮次：路由全部先到、结论各自后到，两位专家都要拿到自己的正文', () => {
  // 真实现场：子开跑时各推一次「路由到」；结束后按参与者重钉路由行再推整段结论
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（ma_from_agent_001），依据：用户点名了该方案'),
    block('route', '路由到 制度知识专家（policy）'),
    block('route', '路由到 报告撰写专家（writer）'),
    block('route', '路由到 制度知识专家（policy）'),
    block('assistant', '制度要点：年假按工龄计算，满一年 5 天。'),
    block('route', '路由到 报告撰写专家（writer）'),
    block('assistant', '## 报告\n\n一、制度依据\n\n二、落实建议'),
    block('assistant', '### 制度知识专家\n制度要点：年假按工龄计算，满一年 5 天。\n\n### 报告撰写专家\n报告已完成')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:报告撰写专家'
  ])
  assert.equal(cards[2].answer.includes('年假按工龄计算'), true)
  // 专家自己的报告带 Markdown 小标题，不能被当成「多人拼接稿」切碎或丢掉
  assert.equal(cards[3].answer.includes('一、制度依据'), true)
  assert.equal(cards[3].answer.includes('二、落实建议'), true)
  // 方案没有汇总，就不复制任何子专家正文；分流句是它内容框里的话
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
  assert.equal(cards[1].answer, '')
  assert.equal(cards[1].lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((cards[1].children || []).map((card) => card.title), ['制度知识专家', '报告撰写专家'])
})

test('只有文件没有正文：文件留在卡上，不用清单句占正文', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 报告撰写专家（writer）'),
    {
      kind: 'file',
      text: '报告.md',
      file: { fileName: '报告.md', size: 1600, downloadUrl: '/files/报告.md' }
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 报告撰写专家'],
    lanes: [{ kind: 'agent', title: '路由到 报告撰写专家', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  const expert = cards.find((card) => card.title === '报告撰写专家')
  assert.ok(expert)
  assert.equal(expert!.answer, '')
  assert.equal(expert!.answer.includes('已生成'), false)
  assert.equal(expert!.files.length, 1)
  assert.equal(expert!.files[0].fileName, '报告.md')
})

test('网关与方案的分流句是可见结果，不额外挂空壳', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台', '路由到 制度知识专家'],
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
    ],
    blocks: [block('route', '路由到 企业综合台'), block('route', '路由到 制度知识专家')],
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  // 三块同款聊天块：网关块、方案块、专家块
  assert.deepEqual(cards.map((card) => card.kind), ['gateway', 'actor', 'actor'])
  // 两块的可见结果都靠 lines（内容框里的话），不靠正文；也不出现空壳
  assert.ok(cards[0].lines.length > 0)
  assert.ok((cards[1].children || []).length > 0)
  assert.equal(cards[0].answer, '')
  assert.equal(cards[1].answer, '')
  assert.equal(cards[1].roleLabel, '方案')
  assert.equal(cards.filter((card) => card.title === '企业综合台').length, 1)
  // 专家块不挂分流句，也不标身份
  assert.equal(cards[2].roleLabel, undefined)
  assert.deepEqual(cards[2].lines, [])
})

test('没有汇总结论时方案块不复制子专家正文', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 知识专家（kb），依据：查制度'),
    block('assistant', '制度结论：年假按工龄计算'),
    block('route', '路由到 报告专家'),
    block('assistant', '报告正文已根据制度写完')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes,
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  // 没有汇总：方案块仍在，内容框里只有分流句，不出结论块，也不复制子专家正文
  assert.equal(cards.find((card) => card.title === '企业综合台')?.kind, 'actor')
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
  assert.equal(cards.find((card) => card.title === '企业综合台')?.answer, '')
  assert.equal(cards.find((card) => card.title === '报告专家')?.answer.includes('报告正文'), true)
  assert.equal(cards.some((card) => card.answer.includes('年假按工龄计算') && card.title === '企业综合台'), false)
})

test('嵌套方案不先出空壳，只列出实际作答的专家', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 售后方案'),
    block('route', '路由决策\n已判定 1 个意图，按序分给「知识专家」'),
    block('route', '路由到 知识专家'),
    block('assistant', '制度结论'),
    block('route', '路由到 报告专家'),
    block('assistant', '报告正文')
  ]
  const nestedLanes: ProcessLane[] = [
    { kind: 'agent', title: '路由到 企业综合台', steps: [] },
    { kind: 'agent', title: '路由到 售后方案', steps: [] },
    { kind: 'agent', title: '路由到 知识专家', steps: [] },
    { kind: 'agent', title: '路由到 报告专家', steps: [] }
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: nestedLanes,
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' || name === '售后方案' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  const expertTitles = cards
    .filter((card) => card.kind === 'actor' && card.roleLabel !== '方案')
    .map((card) => card.title)
  assert.deepEqual(expertTitles, ['知识专家', '报告专家'])
  // 两层方案各自出一块同款聊天块（内容框里是分流结果），不留空壳、不重复
  const schemes = cards.filter((card) => card.roleLabel === '方案')
  assert.deepEqual(schemes.map((card) => card.title), ['企业综合台', '售后方案'])
  assert.ok(schemes.every((card) => card.kind === 'actor' && ((card.children || []).length > 0 || card.lines.length > 0)))
  assert.equal(cards.filter((card) => card.title === '企业综合台').length, 1)
  assert.equal(cards.some((card) => card.lines.some((line) => line.includes('route_key') || line.includes('没有额外的思考记录'))), false)
  assert.equal(cards.find((card) => card.title === '报告专家')?.answer, '报告正文')
})

test('并行结论各归各家，不串块', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 甲'),
    block('assistant', '甲只回答了甲的问题'),
    block('route', '路由到 乙'),
    block('assistant', '乙只回答了乙的问题')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 甲', '路由到 乙'],
    lanes: [
      { kind: 'agent', title: '路由到 甲', steps: [] },
      { kind: 'agent', title: '路由到 乙', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  const actors = cards.filter((card) => card.kind === 'actor')
  assert.equal(actors[0].title, '甲')
  assert.equal(actors[0].answer, '甲只回答了甲的问题')
  assert.equal(actors[1].answer, '乙只回答了乙的问题')
  assert.equal(actors[0].answer.includes('乙'), false)
})

test('重复的方案路由不出现空壳，子专家按顺序各一条', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '路由到 企业综合台'),
    block('route', '路由到 企业综合台（enterprise）'),
    block('route', '路由到 制度知识专家（policy），依据：查制度'),
    block('assistant', '制度要点已整理'),
    block('route', '路由到 报告撰写专家'),
    block('assistant', '报告草稿已写完')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', body: '依据：查制度', steps: [{ type: 'reasoning', title: '推理', text: '先对制度' }] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [{ type: 'tool_call', title: '调用工具', text: '起草' }] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  const titles = cards.map((card) => `${card.kind}:${card.title}`)
  assert.deepEqual(titles, [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:报告撰写专家'
  ])
  assert.ok(cards[0].lines.some((line) => line.includes('企业综合台')))
  // 三行同名方案泳道只出一块（不是三块空壳）
  assert.equal(cards.filter((card) => card.title === '企业综合台').length, 1)
  assert.equal(cards[1].roleLabel, '方案')
  assert.equal(cards[1].lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((cards[1].children || []).map((card) => card.title), ['制度知识专家', '报告撰写专家'])
  assert.equal(cards.some((card) => card.lines.concat(card.errors).some((line) => line.includes('正在由') || line.includes('已由') || line.includes('route_key') || line.includes('enterprise'))), false)
  assert.equal(cards[2].answer.includes('制度要点'), true)
  assert.equal(cards[2].steps.some((step) => step.text.includes('先对制度')), true)
  assert.equal(cards[3].answer.includes('报告草稿'), true)
})

test('子专家还没到时只留网关块，不连出方案空壳', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台', '路由到 企业综合台', '路由到 企业综合台'],
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 企业综合台', steps: [] }
    ],
    blocks: [
      block('route', '路由到 企业综合台'),
      block('route', '路由到 企业综合台'),
      block('route', '路由到 企业综合台')
    ],
    streaming: true,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.deepEqual(cards.map((card) => card.title), ['智能网关'])
  assert.ok(cards[0].lines.some((line) => line.includes('企业综合台')))
  assert.equal(cards.some((card) => card.lines.some((line) => line.includes('正在由') || line.includes('已由') || line.includes('没有额外的思考记录') || line.includes('正在分发'))), false)
})

test('已有 begin 身份的方案壳在子专家到达前就上卡，不靠终答回补', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台'],
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', participantId: 'p-0-root-enterprise', steps: [] }
    ],
    blocks: [
      {
        kind: 'route',
        text: '路由到 企业综合台',
        participantId: 'p-0-root-enterprise',
        participantName: '企业综合台'
      }
    ],
    streaming: true,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  // 身份帧先到：方案卡立刻出现，内容框给出进行态，而不是等子专家 / 终答才闪出来
  assert.deepEqual(cards.map((card) => card.title), ['智能网关', '企业综合台'])
  assert.deepEqual(cards[1].lines, ['正在分发…'])
  assert.equal(cards[1].participantId, 'p-0-root-enterprise')
  // 有身份的 pending 壳不能被收尾空壳判定当成「已完成状态句」
  assert.equal(isFinishedStatusShell(cards[1]), false)
})

test('工作流卡不接没有身份的散落文件（文件只挂写出它的参与者）', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度要点与报告'],
    lanes: [
      {
        kind: 'agent',
        title: '路由到 制度要点与报告',
        roleLabel: WORKFLOW_ROLE_LABEL,
        steps: []
      }
    ],
    blocks: [
      {
        kind: 'route',
        text: '路由到 制度要点与报告',
        participantName: '制度要点与报告',
        participantKind: 'workflow'
      },
      {
        kind: 'file',
        text: '制度要点.md',
        file: { fileName: '制度要点.md', size: 12, downloadUrl: '/f/1' }
      }
    ],
    streaming: true,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT'),
    isTopLevelOf: () => false
  })
  const flow = cards.find((card) => card.roleLabel === WORKFLOW_ROLE_LABEL)
  assert.ok(flow)
  // 没有身份的文件不兜底挂到工作流卡上，避免「先挂工作流、再改挂专家」
  assert.deepEqual(flow.files, [])
})

test('带身份的工作流文件挂在工作流卡自己的名下', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度要点与报告'],
    lanes: [
      {
        kind: 'agent',
        title: '路由到 制度要点与报告',
        roleLabel: WORKFLOW_ROLE_LABEL,
        participantId: 'p-0-root-wf-report',
        steps: []
      }
    ],
    blocks: [
      {
        kind: 'route',
        text: '路由到 制度要点与报告',
        participantId: 'p-0-root-wf-report',
        participantName: '制度要点与报告'
      },
      {
        kind: 'file',
        text: '制度要点.md',
        participantId: 'p-0-root-wf-report',
        participantName: '制度要点与报告',
        file: { fileName: '制度要点.md', size: 12, downloadUrl: '/f/1' }
      }
    ],
    streaming: true,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '制度要点与报告' ? 'WORKFLOW' : 'AGENT'),
    isTopLevelOf: () => false
  })
  const flow = cards.find((card) => card.roleLabel === WORKFLOW_ROLE_LABEL)
  assert.ok(flow)
  assert.deepEqual(flow.files.map((file) => file.fileName), ['制度要点.md'])
})

test('单级直分专家仍然是网关加这一位，结论挂在本人块下', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 知识问答，依据：问的是制度'],
    lanes: [
      { kind: 'agent', title: '路由到 知识问答', steps: [{ type: 'reasoning', title: '推理', text: '查年假' }] }
    ],
    blocks: [
      block('route', '路由到 知识问答，依据：问的是制度'),
      block('assistant', '年假按工龄计算')
    ],
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  assert.deepEqual(cards.map((card) => card.title), ['智能网关', '知识问答'])
  assert.equal(cards[1].roleLabel, undefined)
  assert.equal(cards[1].answer, '年假按工龄计算')
  assert.equal(cards.some((card) => card.title === '企业综合台'), false)
  assert.equal(cards[0].kind, 'gateway')
  assert.ok(cards[0].lines.some((line) => line.includes('知识问答')))
})

test('文件和报错跟到当时的专家，不挂到方案或消息流底部', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 制度知识专家'),
    block('error', '知识库暂时不可用，请稍后再试。'),
    {
      kind: 'file',
      text: '报告.md',
      file: { fileName: '报告.md', size: 1600, downloadUrl: '/files/报告.md' }
    },
    block('assistant', '检索没有拿到原文')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台', '路由到 制度知识专家'],
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      {
        kind: 'agent',
        title: '路由到 制度知识专家',
        steps: [
          { type: 'tool_call', title: '调用工具 searchKnowledgeBase · 失败', text: '公司制度' },
          { type: 'tool_result', title: '工具结果', text: '知识库暂时不可用' }
        ]
      }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:制度知识专家'
  ])
  const expert = cards[2]
  assert.equal(cards[1].roleLabel, '方案')
  assert.equal(cards[1].lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((cards[1].children || []).map((card) => card.title), ['制度知识专家'])
  assert.equal(expert.answer.includes('检索没有拿到原文'), true)
  assert.ok(expert.errors.some((line) => line.includes('知识库暂时不可用')))
  assert.equal(expert.files.length, 1)
  assert.equal(expert.files[0].fileName, '报告.md')
  assert.equal(expert.steps.some((step) => step.type === 'tool_call'), true)
})

test('入口点名的方案没有子专家时自己作答，网关块下面不再空白', () => {
  // 用户点名方案、方案没有再分下去：它自己就是作答方，不能按「分流空壳」删掉
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('assistant', '年假按工龄计算，制度要点已整理好')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [{ kind: 'agent', title: '路由到 企业综合台', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), ['gateway:智能网关', 'actor:企业综合台'])
  assert.equal(cards[1].roleLabel, '方案')
  assert.equal(cards[1].answer, '年假按工龄计算，制度要点已整理好')
  assert.equal(cards[1].pending, false)
})

test('方案真分了子专家时：方案自己一块（内容框里是分流），后面才是子专家', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('route', '路由到 知识专家'),
    block('assistant', '制度结论')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 知识专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:知识专家'
  ])
  // 方案块不再把分流名单当正文，也不复制子专家正文
  assert.equal(cards[1].lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((cards[1].children || []).map((card) => card.title), ['知识专家'])
  assert.equal(cards[1].answer, '')
  assert.equal(cards[2].answer, '制度结论')
})

test('方案名下只有章节标题的报告：整篇留给兜底，不按（一）（二）补假专家', () => {
  // 无子专家路由时 owner=方案；旧逻辑会因 schemeOwned 把章节切成 extraAnswers
  const report = '### （一）概述\n\n要点 A\n\n### （二）结论\n\n要点 B'
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('assistant', report)
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 企业综合台'],
    lanes: [{ kind: 'agent', title: '路由到 企业综合台', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.equal(cards.some((card) => card.title.startsWith('（一）') || card.title.startsWith('（二）')), false)
  const body = cards.map((card) => card.answer).join('\n')
  assert.equal(body.includes('### （一）概述'), true)
  assert.equal(body.includes('### （二）结论'), true)
  assert.equal(body.includes('要点 A'), true)
})

test('没有对应参与者的 ### 标题不补专家卡', () => {
  // 身份未落库、泳道里也没有这两位时，不再凭标题猜作者
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台'),
    block('assistant', '### 制度知识专家\n年假按工龄计算\n\n### 报告撰写专家\n报告草稿已完成')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [{ kind: 'agent', title: '路由到 企业综合台', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.equal(cards.some((card) => card.title === '制度知识专家' || card.title === '报告撰写专家'), false)
  const body = cards.map((card) => card.answer).join('\n')
  assert.equal(body.includes('年假按工龄计算'), true)
  assert.equal(body.includes('报告草稿已完成'), true)
})

test('文稿里的【待确认】标题留在作者正文，不切无主卡', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 文稿起草（draft）'),
    block('assistant', '### 文稿起草\n开场\n\n### 【待确认：会议名称】会议纪要\n\n一、议题\n')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 文稿起草（draft）'],
    lanes: [{ kind: 'agent', title: '路由到 文稿起草', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: () => 'AGENT',
    isTopLevelOf: () => true
  })
  assert.equal(cards.some((card) => card.title.includes('待确认') || card.title.includes('会议纪要')), false)
  const expert = cards.find((card) => card.title === '文稿起草')
  assert.ok(expert)
  assert.equal(expert?.answer.includes('### 【待确认：会议名称】会议纪要'), true)
  assert.equal(expert?.answer.includes('一、议题'), true)
})

test('历史半截事件不会把投影写崩：lanes 缺 steps 时退化成空数组', () => {
  // 旧快照里的车道可能没有 steps：投影失败只能返回空数组，绝不能让异常冒到渲染树
  const brokenLanes = [{ kind: 'agent', title: '路由到 知识专家' }] as unknown as ProcessLane[]
  const originalWarn = console.warn
  console.warn = () => {}
  try {
    const cards = buildParticipantFeed({
      routeTexts: ['路由到 知识专家'],
      lanes: brokenLanes,
      blocks: [],
      streaming: false,
      fallbackName: '智能网关'
    })
    assert.deepEqual(cards, [])
  } finally {
    console.warn = originalWarn
  }
})

test('blocks 非数组 / 含空块时不抛，卡片字段始终是数组', () => {
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 知识问答'],
    lanes: [{ kind: 'agent', title: '路由到 知识问答', steps: [] }],
    blocks: [null, { kind: 'assistant' }] as unknown as AssistantBlock[],
    streaming: true,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  assert.ok(cards.length >= 2)
  for (const card of cards) {
    assert.equal(Array.isArray(card.lines), true)
    assert.equal(Array.isArray(card.steps), true)
    assert.equal(Array.isArray(card.errors), true)
    assert.equal(Array.isArray(card.files), true)
  }
  // blocks 整个缺失时仍按车道出块，不抛；本轮没有路由事件，因此不出入口判定块
  assert.deepEqual(
    buildParticipantFeed({
      routeTexts: [],
      lanes: [],
      blocks: undefined as unknown as AssistantBlock[],
      streaming: false,
      fallbackName: '智能网关'
    }).map((card) => card.kind),
    ['actor']
  )
})

test('固定模板的子块标题要认成参与者，不能当说明句吞掉', () => {
  // 执行明细里有子专家、消息流里却没有，就是因为这类标题没被认成「谁在作答」
  assert.deepEqual(parseRouteParticipant('固定并行 1/2 · 制度知识专家（policy）'), {
    name: '制度知识专家',
    key: 'policy'
  })
  assert.deepEqual(parseRouteParticipant('路由后串行 2/3 · 报告撰写专家（writer）'), {
    name: '报告撰写专家',
    key: 'writer'
  })
  assert.deepEqual(parseRouteParticipant('竞争选优 1/2 · 方案甲（a） · 同题作答'), {
    name: '方案甲',
    key: 'a'
  })
  assert.deepEqual(parseRouteParticipant('路由到 知识专家（kb），依据：查制度'), {
    name: '知识专家',
    key: 'kb'
  })
  // 决策块与方案结论不是参与者
  assert.equal(parseRouteParticipant('路由决策\n已判定 2 个意图'), undefined)
  assert.equal(parseRouteParticipant('方案结论：企业综合台'), undefined)
})

test('方案分给两位子专家：方案一块（内容框里是分流），子专家各一块，正文与文件各归各家', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（ma_from_agent_001），依据：用户点名了该方案，由方案再分发给对应专家'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    { kind: 'tool', text: '调用工具 searchKnowledgeBase（{"query":"年假"}）', detail: '年假按工龄计算' },
    block('route', '固定并行 2/2 · 报告撰写专家（writer）'),
    { kind: 'tool', text: '调用工具 writeFile（{"path":"报告.md"}）', detail: '已写入' },
    {
      kind: 'file',
      text: '报告.md',
      file: { fileName: '报告.md', size: 2048, downloadUrl: '/files/报告.md' }
    },
    block('assistant', '### 制度知识专家\n年假按工龄计算，满一年 5 天\n\n### 报告撰写专家\n报告草稿已完成')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', body: '', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', body: '', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:报告撰写专家'
  ])
  assert.equal(cards[1].lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((cards[1].children || []).map((card) => card.title), ['制度知识专家', '报告撰写专家'])
  assert.equal(cards[2].answer.includes('年假按工龄计算'), true)
  assert.equal(cards[3].answer.includes('报告草稿已完成'), true)
  // 文件属于写出它的报告撰写专家，不再漂到方案或消息流底部
  assert.equal(cards[3].files.length, 1)
  assert.equal(cards[2].files.length, 0)
  assert.equal(cards[1].files.length, 0)
  assert.equal(cards[1].answer, '')
})

test('网关、方案、专家统一成聊天块：每块都有头像与内容框里的话，没有居中条', () => {
  const look = (name: string) => ({
    initial: name.slice(0, 1),
    color: '#fff',
    initialColor: '#635bff',
    custom: false
  })
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    block('route', '固定并行 2/2 · 报告撰写专家（writer）'),
    block('assistant', '### 制度知识专家\n年假按工龄计算\n\n### 报告撰写专家\n报告草稿已完成')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台',
    lookOf: look
  })
  // 旧的居中条类型已不存在：只可能是 gateway / actor / conclusion 三种
  assert.equal(cards.every((card) => card.kind === 'gateway' || card.kind === 'actor' || card.kind === 'conclusion'), true)
  for (const card of cards) {
    assert.ok(card.title.trim().length > 0)
    // 头像：网关也走 lookOf，与专家同一套兜底
    assert.ok(card.look != null, `${card.title} 缺头像`)
    // 内容框里必须有话：网关/方案在 lines，专家在 answer
    assert.ok(
      card.lines.length > 0 || card.answer.length > 0 || (card.children || []).length > 0,
      `${card.title} 的内容框里没有字`
    )
  }
  // 网关与方案的话都不占用 answer（answer 留给成文结论）
  assert.equal(cards[0].answer, '')
  assert.equal(cards[1].answer, '')
  assert.equal(cards[1].roleLabel, '方案')
  // 顺序：网关 → 方案 → 子专家（按真实调用序）
  assert.deepEqual(cards.map((card) => card.title), ['智能网关', '企业综合台', '制度知识专家', '报告撰写专家'])
})

test('只问年假制度：只有网关块 + 该专家块，不冒出企业综合台', () => {  const blocks: AssistantBlock[] = [
    block('route', '路由到 制度知识专家（policy），依据：问的是年假制度'),
    block('assistant', '年假按工龄计算，满一年 5 天')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家（policy），依据：问的是年假制度'],
    lanes: [{ kind: 'agent', title: '路由到 制度知识专家', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:制度知识专家'
  ])
  assert.ok(cards[0].lines[0].includes('制度知识专家'))
  assert.equal(cards[1].answer, '年假按工龄计算，满一年 5 天')
  assert.equal(cards.some((card) => card.title === '企业综合台'), false)
  assert.equal(cards.some((card) => card.roleLabel === '方案'), false)
  // 两块都要能在内容框里看到结果
  assert.ok(cards[0].lines.length > 0)
  assert.ok(cards[1].answer.length > 0)
})

test('没有入口判定时不出入口块：断点续作与纯单专家轮次只有专家自己', () => {
  const cards = buildParticipantFeed({
    routeTexts: [],
    lanes: [{ kind: 'agent', title: '路由到 知识问答', steps: [] }],
    blocks: [block('assistant', '年假按工龄计算')],
    streaming: false,
    fallbackName: '知识问答',
    isTopLevelOf: () => true
  })
  // 本轮没有 ROUTE 事件就是没有入口判定：再补一块「已接收问题…」等于凭空造一次分流
  assert.deepEqual(cards.map((card) => card.kind), ['actor'])
  for (const card of cards) {
    assert.ok(card.lines.length > 0 || card.answer.length > 0, `${card.title} 不该是空壳`)
  }
  assert.equal(cards[0].answer.includes('年假按工龄计算'), true)
})

test('汇总另起一条：分流那条冻结不变，汇总在最下且带自己的思考', () => {
  // 后端真实序列：分发 → 子专家各自作答 → 「方案结论：X」→ 汇总模型的 reasoning / tool → 汇总正文
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    block('assistant', '制度要点：年假按工龄计算'),
    block('route', '固定并行 2/2 · 报告撰写专家（writer）'),
    block('assistant', '报告草稿已完成'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '把两位专家的结论合成一份要点'),
    { kind: 'tool', text: '调用工具 writeFile（{"path":"汇总.md"}）', detail: '已写入' },
    block('assistant', '综合台汇总：年假规则与报告已就绪')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  // 顺序：网关 → 分流（冻结）→ 两位专家 → 汇总单独一条压在最下
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:企业综合台',
    'actor:制度知识专家',
    'actor:报告撰写专家',
    'conclusion:企业综合台'
  ])
  // 分流那条：只有分流句，没有正文、没有过程——事后没有被汇总「重新进入思考」
  const dispatch = cards[1]
  assert.equal(dispatch.lines.some((line) => line.includes('已把问题分给')), false)
  assert.deepEqual((dispatch.children || []).map((card) => card.title), ['制度知识专家', '报告撰写专家'])
  assert.equal(dispatch.answer, '')
  assert.equal(dispatch.steps.length, 0)
  assert.equal(dispatch.pending, false)
  // 汇总那条：自己的思考 / 工具 + 汇总正文，不复述分流句、不抄子专家正文
  const summary = cards[4]
  assert.deepEqual(summary.lines, [])
  assert.ok(summary.steps.length >= 2, '汇总应带自己的思考与工具步')
  assert.equal(summary.answer.includes('综合台汇总'), true)
  assert.equal(summary.answer.includes('年假按工龄计算'), false)
  assert.equal(summary.answer.includes('报告草稿'), false)
  // 只有一条汇总记录，不会出现两条「综合台汇总」
  assert.equal(cards.filter((card) => card.kind === 'conclusion').length, 1)
})

test('汇总过程中（流式）另起那条标记为仍在汇总，分流那条不受影响', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    block('assistant', '制度要点：年假按工龄计算'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '正在汇总两份结论')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
    ],
    blocks,
    streaming: true,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  const dispatch = cards.find((card) => card.kind === 'actor' && card.roleLabel === '方案')
  const summary = cards.find((card) => card.kind === 'conclusion')
  assert.ok(dispatch)
  assert.ok(summary)
  // 分流那条不因汇总在跑而变成进行态
  assert.equal(dispatch!.pending, false)
  // 汇总那条仍在生成：思考区据此默认展开、内容框按中间过程限高跟滚
  assert.equal(summary!.pending, true)
})

test('无真汇总时不出第二条综合台', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    block('assistant', '制度要点：年假按工龄计算')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
  assert.equal(cards.filter((card) => card.title === '企业综合台').length, 1)
})

test('汇总过程不会画出无主人的匿名「执行」块', () => {
  // 汇总阶段的 reasoning / tool 在时间线里没有对应参与者，会被兜成标题「执行」的匿名块
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    block('assistant', '制度要点'),
    block('route', '方案结论：企业综合台'),
    block('reasoning', '汇总推理'),
    block('assistant', '汇总正文')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.equal(cards.some((card) => card.title === '执行'), false)
  // 汇总过程收在汇总那条记录里，不另成块
  const summary = cards.find((card) => card.kind === 'conclusion')
  assert.ok(summary)
  assert.ok(summary!.steps.some((step) => step.text.includes('汇总推理')))
})

test('卡片 key 不带下标：新增参与者不会让前面的块换 key（开场不闪重）', () => {
  const candidates = [{ routeKey: 'enterprise', agentName: '企业综合台', targetType: 'MA' as const }]
  const project = (blocks: AssistantBlock[], lanes: ProcessLane[]) =>
    buildParticipantFeed({
      routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
      lanes,
      blocks,
      streaming: true,
      fallbackName: '企业综合台',
      targetTypeOf: (name) => candidates.find((c) => c.agentName === name)?.targetType,
      isTopLevelOf: (name) => !!candidates.find((c) => c.agentName === name)
    })

  const routeA = block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案')
  const routeB = block('route', '固定并行 1/2 · 制度知识专家（policy）')
  const routeC = block('route', '固定并行 2/2 · 报告撰写专家（writer）')
  const laneA: ProcessLane = { kind: 'agent', title: '路由到 企业综合台', steps: [] }
  const laneB: ProcessLane = { kind: 'agent', title: '路由到 制度知识专家', steps: [] }
  const laneC: ProcessLane = { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }

  const first = project([routeA], [laneA])
  const second = project([routeA, routeB], [laneA, laneB])
  const third = project([routeA, routeB, routeC], [laneA, laneB, laneC])

  // 逐帧对比同名块的 key：一旦某块的 key 变了，Vue 会「卸载再重建」，页面上就是闪
  const keyOf = (cards: ReturnType<typeof project>, title: string) =>
    cards.find((card) => card.title === title)?.key
  assert.equal(keyOf(first, '智能网关'), keyOf(second, '智能网关'))
  assert.equal(keyOf(first, '智能网关'), keyOf(third, '智能网关'))
  assert.equal(keyOf(second, '企业综合台'), keyOf(third, '企业综合台'))
  assert.equal(keyOf(second, '制度知识专家'), keyOf(third, '制度知识专家'))
})

test('没有「方案结论」标记时，不把末尾正文猜成汇总', () => {
  // 参与者泳道已在；缺标记就不是结论卡，正文留在最后一位已出现的参与者
  const blocks: AssistantBlock[] = [
    block('route', '路由到 企业综合台（enterprise），依据：用户点名了该方案'),
    block('route', '固定并行 1/2 · 制度知识专家（policy）'),
    block('route', '固定并行 2/2 · 报告撰写专家（writer）'),
    block('assistant', '企业综合台汇总：年假规则与报告已就绪')
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 企业综合台', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', steps: [] },
      { kind: 'agent', title: '路由到 报告撰写专家', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '企业综合台',
    targetTypeOf: (name) => (name === '企业综合台' ? 'MA' : 'AGENT'),
    isTopLevelOf: (name) => name === '企业综合台'
  })
  assert.equal(cards.some((card) => card.kind === 'conclusion'), false)
  assert.equal(cards.find((card) => card.title === '报告撰写专家')?.answer.includes('企业综合台汇总'), true)
})

test('只有路由文案、没有参与者泳道时，不按路由名或 ### 标题建卡', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 制度知识专家（policy），依据：问制度'),
    block('assistant', '### 制度知识专家\n年假按工龄\n\n### 【待确认：会议名称】会议纪要\n一、议题')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家（policy），依据：问制度'],
    lanes: [],
    blocks,
    streaming: false,
    fallbackName: '制度知识专家',
    targetTypeOf: () => 'AGENT'
  })
  assert.equal(cards.some((card) => card.kind === 'actor' || card.kind === 'conclusion'), false)
  assert.equal(cards.some((card) => card.title === '制度知识专家' || card.title.includes('待确认')), false)
})

test('只问年假制度：该专家就是整轮最后一步，结束后不限高', () => {
  const blocks: AssistantBlock[] = [
    block('route', '路由到 制度知识专家（policy），依据：问的是年假制度'),
    block('assistant', '年假按工龄计算，满一年 5 天')
  ]
  const cards = buildParticipantFeed({
    routeTexts: ['路由到 制度知识专家（policy），依据：问的是年假制度'],
    lanes: [{ kind: 'agent', title: '路由到 制度知识专家', steps: [] }],
    blocks,
    streaming: false,
    fallbackName: '智能网关',
    isTopLevelOf: () => true
  })
  assert.deepEqual(cards.map((card) => `${card.kind}:${card.title}`), [
    'gateway:智能网关',
    'actor:制度知识专家'
  ])
  // 网关是中间过程，专家是终局
  assert.equal(cards[0].isFinal, false)
  assert.equal(cards[1].isFinal, true)
})

test('CONCAT 按卡名把两人正文拆开，章节和待确认留在正文里', () => {
  const blocks: AssistantBlock[] = [
    {
      kind: 'route',
      text: '路由到 文稿起草（draft）',
      participantId: 'p-draft',
      participantName: '文稿起草'
    },
    {
      kind: 'route',
      text: '路由到 制度知识专家（policy）',
      participantId: 'p-policy',
      participantName: '制度知识专家'
    },
    {
      kind: 'assistant',
      text: '### 文稿起草\n周例会纪要\n### （一）议题\n说过了\n\n### 制度知识专家\n年假按工龄\n【待确认：会议名称】',
      participantId: 'p-policy',
      participantName: '制度知识专家'
    }
  ]
  const cards = buildParticipantFeed({
    routeTexts: blocks.filter((item) => item.kind === 'route').map((item) => item.text),
    lanes: [
      { kind: 'agent', title: '路由到 文稿起草', participantId: 'p-draft', steps: [] },
      { kind: 'agent', title: '路由到 制度知识专家', participantId: 'p-policy', steps: [] }
    ],
    blocks,
    streaming: false,
    fallbackName: '智能助手',
    targetTypeOf: () => 'AGENT',
    isTopLevelOf: () => true
  })
  const draft = cards.find((card) => card.title === '文稿起草')
  const policy = cards.find((card) => card.title === '制度知识专家')
  assert.equal(cards.some((card) => card.title === '智能助手'), false)
  assert.equal(draft?.participantId, 'p-draft')
  assert.ok(draft?.answer.includes('周例会纪要'))
  assert.ok(draft?.answer.includes('（一）议题'))
  assert.ok(policy?.answer.includes('年假按工龄'))
  assert.ok(policy?.answer.includes('【待确认：会议名称】'))
  assert.equal(policy?.answer.includes('周例会纪要'), false)
})

test('已有 begin 身份的专家收尾不是空壳', () => {
  const kept = isFinishedStatusShell({
    key: 'expert-文稿起草',
    kind: 'actor',
    title: '文稿起草',
    participantId: 'p-draft',
    lines: [],
    steps: [],
    answer: '已由「文稿起草」完成作答',
    errors: [],
    files: [],
    pending: false
  })
  const dropped = isFinishedStatusShell({
    key: 'expert-智能助手',
    kind: 'actor',
    title: '智能助手',
    lines: [],
    steps: [],
    answer: '已由「智能助手」完成作答',
    errors: [],
    files: [],
    pending: false
  })
  assert.equal(kept, false)
  assert.equal(dropped, true)
})

