/**
 * 工作台对话消息流的参与者分块。所属域：runtime（前端）。
 *
 * <p>结构对齐分组会话：居中一条网关判定，其后每位专家一条消息（思考默认由调用方收起），
 * 方案真有「方案结论」时把结论回填到自己那一块。数据仍是已有轨迹，不另造过程事件。
 * 每个参与者都要能直接看到「这一步的结果」：判定条写分发给谁，方案写分给了谁，
 * 专家写自己的正文 / 文件清单 / 工具返回摘要。思考只进折叠，不当唯一内容。
 */
import type { AssistantBlock, TrialFileView } from '@/types/runtime'
import type { ProcessStep } from '@/components/runtime/trialMessages'
import { parseRouteParticipant } from './workbenchRouteText.ts'
import {
  buildThinkTimeline,
  expertStatusLine,
  parseRouteText,
  sanitizeThinkText,
  type ExpertLookView,
  type ThinkTimelineInput,
  type ThinkTimelineStep
} from './workbenchThinkTimeline.ts'

/** 方案自己的汇总结论在路由事件上的前缀；后面是方案名 */
export const SCHEME_CONCLUSION_MARK = '方案结论：'

/** 消息流里的一块参与者 */
export interface ParticipantCard {
  /** 渲染 key */
  key: string
  /**
   * gateway＝入口判定条；route＝方案分发条（紧凑居中，不是参与者消息）；
   * actor＝专家 / 自己在作答的方案；conclusion＝方案自己的汇总
   */
  kind: 'gateway' | 'route' | 'actor' | 'conclusion'
  /** 展示名 */
  title: string
  /** 「判定」或「方案」；普通专家不标 */
  roleLabel?: string
  /** 判定条 / 分发条的人话，或参与者折叠区里的说明 */
  lines: string[]
  /** 思考区里的推理与工具；判定条与分发条为空 */
  steps: ProcessStep[]
  /** 结果气泡正文；判定条与分发条为空 */
  answer: string
  /** 归到这个人气泡里的工具失败说明和 error 块 */
  errors: string[]
  /** 归到这个人气泡里的文件卡 */
  files: TrialFileView[]
  /** 形象；判定条为空 */
  look?: ExpertLookView
  /** 还没有结论、仍在生成 */
  pending: boolean
}

interface CollectedAnswers {
  /** 专家名 → 该参与者自己的成文结论 */
  byName: Map<string, string>
  /** 按出现顺序的方案汇总结论 */
  conclusions: { name: string; text: string }[]
  /** 专家名 → 其作答期间的文件 */
  filesByName: Map<string, TrialFileView[]>
  /** 专家名 → 其作答期间的报错 */
  errorsByName: Map<string, string[]>
  /** 当时还没有专家可挂的文件，调用方接到最后一位参与者上 */
  looseFiles: TrialFileView[]
  /** 当时还没有专家可挂的报错 */
  looseErrors: string[]
  /** 分段标题对不上任何参与者时的正文，接到最后一位参与者上，避免整段消失 */
  looseAnswers: string[]
  /**
   * 拼接稿里认得出作者、但本轮没有对应泳道的分段。
   *
   * <p>历史会话可能只落了 CONCAT 终答、没有子专家路由行；分段标题本身就是作者声明，
   * 据此补一条参与者消息，才不会出现「执行明细有子专家、消息流看不见」。
   * 只在「至少两段且有一段能对上本轮参与者」时启用，避免普通正文里的小标题被当成专家。
   */
  extraAnswers: { name: string; text: string }[]
}

/**
 * 投影本轮消息流分块。
 *
 * <p>网关是判定条。专家按调用顺序各一条。方案分流块不出现，只有「方案结论」才在子专家之后出一块。
 *
 * @param input 时间线入参，另加原始块（用来把正文挂回参与者）
 * @return 消息流卡片；时间线为空时为空数组
 */
export function buildParticipantFeed(input: ThinkTimelineInput & { blocks: AssistantBlock[] }): ParticipantCard[] {
  // 历史会话 / 刷新快照可能带半截事件：投影失败只退化成「逐块渲染」，绝不让异常冒到渲染树
  try {
    const blocks = Array.isArray(input.blocks) ? input.blocks : []
    if (!Array.isArray(input.lanes) || !Array.isArray(input.routeTexts)) {
      return []
    }
    const steps = withoutSchemeShells(buildThinkTimeline(input))
    if (!steps.length) {
      return []
    }
    // 「方案块 = 分流空壳」只在它下面真挂子专家时成立；没有子专家说明它自己就是作答方
    const schemeWithChildren = schemesWithChildren(steps, input.isTopLevelOf)
    const answerableSchemes = new Set(
      steps
        .filter((step) => step.roleLabel === '方案' && !schemeWithChildren.has(step.title))
        .map((step) => step.title)
    )
    // 本轮真实出现过的参与者名：把后端 CONCAT 的「### 专家名」分段发回各自气泡要用
    const knownActors = new Set(steps.filter((step) => step.kind === 'expert').map((step) => step.title))
    const collected = collectAnswers(blocks, input.targetTypeOf, answerableSchemes, knownActors)
    const cards = steps.map((step, index) => toCard(step, index, collected))
    // 只在拼接稿里出现过、本轮却没有泳道的作者（历史会话常见）：补一条参与者消息
    const withExtra = appendExtraActors(cards, collected, input.lookOf)
    // 方案是否真分了子专家：有子专家泳道，或子专家只出现在拼接终答的分段里
    const dispatchSchemes = new Set(schemeWithChildren)
    if ((collected.extraAnswers || []).length) {
      for (const card of withExtra) {
        // 方案自己没有成文正文、却补出了下一位作者：说明它这一轮只是往下分
        const ownAnswer = (collected.byName.get(card.title) || '').trim()
        if (card.kind === 'actor' && card.roleLabel === '方案' && !ownAnswer) {
          dispatchSchemes.add(card.title)
        }
      }
    }
    // 分了子专家的方案：不再占一条参与者消息，改成一条居中的「方案分发」判定条
    const withBars = schemeRouteBars(withExtra, dispatchSchemes, input.isTopLevelOf)
    const placed = placeConclusions(withBars, collected.conclusions, input.isTopLevelOf)
    // 自己在作答的方案（入口点名、没有子专家）必须留着，否则网关条之后整段空白
    const visible = dropEmptySchemeShells(placed)
    attachLoose(visible, collected)
    return visible
  } catch (error) {
    // 只提示排障，不把内部键/正文写进日志；返回空数组让调用方回退到逐块渲染
    console.warn('[工作台消息流] 本轮分块投影失败，已回退到逐块渲染', error)
    return []
  }
}

/**
 * 展示用：去掉重复的方案空壳。
 *
 * <p>同一方案名被推了多次「路由到」时，归组只把子专家挂到最后一块，前面几块只剩
 * 「正在由／已由「方案名」作答」。消息流和补充轨迹都不该把这些空壳画出来。
 * 不改 {@link buildThinkTimeline} 的返回值，已有时间线用例的口径保持不变。
 *
 * @param steps 时间线步骤
 * @return 去掉空壳后的步骤；尚无子专家的那一块改成「正在分发…」
 */
export function withoutSchemeShells(steps: ThinkTimelineStep[]): ThinkTimelineStep[] {
  // 兜住半截历史：缺 lines / steps 的步骤补成空数组，后面的过滤与渲染只面对数组
  const safeSteps = (Array.isArray(steps) ? steps : []).map((step) => ({
    ...step,
    lines: Array.isArray(step.lines) ? step.lines : [],
    steps: Array.isArray(step.steps) ? step.steps : []
  }))
  const dispatchTitles = new Set(
    safeSteps
      .filter((step) => step.roleLabel === '方案' && step.lines.some((line) => line.includes('已把问题分给')))
      .map((step) => step.title)
  )
  const keptShell = new Set<string>()
  const out: ThinkTimelineStep[] = []
  for (const step of safeSteps) {
    if (step.roleLabel !== '方案' || !isStatusShell(step)) {
      out.push(step)
      continue
    }
    // 已经有分流句，或同名空壳已经留过一块：其余空壳直接丢掉
    if (dispatchTitles.has(step.title) || keptShell.has(step.title)) {
      continue
    }
    keptShell.add(step.title)
    out.push({
      ...step,
      // 子专家还没到：用进行态，不用「正在由／已由「方案名」作答」
      lines: step.pending ? ['正在分发…'] : []
    })
  }
  return out
}

/**
 * 这一步是不是只有方案状态空壳，没有分流人话。
 *
 * @param step 时间线步骤
 * @return true 表示整步都是「正在由／已由「该名」作答」
 */
function isStatusShell(step: ThinkTimelineStep): boolean {
  if (!step.lines.length) {
    return false
  }
  const pending = expertStatusLine(step.title, true)
  const done = expertStatusLine(step.title, false)
  return step.lines.every((line) => line === pending || line === done)
}

/**
 * 哪些方案块下面真的挂了子专家。
 *
 * <p>判定只用已有事实：方案块之后出现了「不在入口候选池」的参与者。
 * 只有这类块才是分流空壳。入口点名的方案若没有子专家，说明它自己在作答，
 * 删掉就会让网关判定条之后整段空白。
 *
 * @param steps        时间线步骤（已去掉重复空壳）
 * @param isTopLevelOf 是否入口候选；不传时无法判定，按「没有子专家」处理
 * @return 有子专家的方案名
 */
function schemesWithChildren(
  steps: ThinkTimelineStep[],
  isTopLevelOf?: (name: string) => boolean
): Set<string> {
  const titles = new Set<string>()
  if (!isTopLevelOf) {
    return titles
  }
  for (let index = 0; index < steps.length; index += 1) {
    const step = steps[index]
    if (!step || step.roleLabel !== '方案') {
      continue
    }
    // 方案之后只要出现一位非入口候选，就说明它确实把问题分了下去
    const hasChild = steps
      .slice(index + 1)
      .some((next) => !!next && next.roleLabel !== '方案' && isTopLevelOf(next.title) === false)
    if (hasChild) {
      titles.add(step.title)
    }
  }
  return titles
}

/**
 * 把拼接稿里认得出作者、但本轮没有泳道的分段补成参与者消息。
 *
 * <p>历史会话可能只落了 CONCAT 终答、没有子专家路由行（或路由行是更早的写法）。
 * 分段标题本身就是后端写的作者声明，据此补一条，内容才不会只剩一个人。
 * 只处理「至少两段且有一段能对上本轮参与者」的拼接稿，普通正文里的标题不在此列。
 *
 * @param cards     已投影的卡片
 * @param collected 归集结果
 * @param lookOf    按名称解析形象；可空
 * @return 补上额外参与者后的卡片
 */
function appendExtraActors(
  cards: ParticipantCard[],
  collected: CollectedAnswers,
  lookOf?: (name: string) => ExpertLookView
): ParticipantCard[] {
  const extras = Array.isArray(collected.extraAnswers) ? collected.extraAnswers : []
  if (!extras.length) {
    return cards
  }
  const out = cards.slice()
  for (const extra of extras) {
    const name = (extra.name || '').trim()
    const text = (extra.text || '').trim()
    if (!name || !text) {
      continue
    }
    // 已经有同名参与者 / 分发条时不重复造块
    if (out.some((card) => card.title === name && card.kind !== 'gateway')) {
      const existing = out.find((card) => card.kind === 'actor' && card.title === name)
      if (existing && !existing.answer) {
        existing.answer = text
      }
      continue
    }
    out.push({
      key: `extra-${name}`,
      kind: 'actor',
      title: name,
      lines: [],
      steps: [],
      answer: text,
      errors: [],
      files: [],
      look: lookOf?.(name),
      pending: false
    })
  }
  return out
}

/**
 * 把「分了子专家的方案」改成一条居中的分发判定条。
 *
 * <p>为什么不再直接删：方案这一块是真实事件——它确实把问题分下去了，
 * 用户也要能看到「企业综合台分给了谁」。但它不是一条参与者消息，不能带头像与空气泡。
 * 所以换成紧凑条，并把子专家名写清楚（优先用后端已落库的「已把问题分给…」原句）。
 *
 * <p>同一方案被推了多行泳道时只留一条条（合并子专家名），不出两块标题壳。
 *
 * @param cards             按发生顺序排好的卡片
 * @param schemeWithChildren 真的分了子专家的方案名
 * @param isTopLevelOf      是否入口候选；用于确定这条方案的子专家范围
 * @return 替换成分发条后的卡片
 */
function schemeRouteBars(
  cards: ParticipantCard[],
  schemeWithChildren: Set<string>,
  isTopLevelOf?: (name: string) => boolean
): ParticipantCard[] {
  if (!schemeWithChildren.size) {
    return cards
  }
  const bars = new Map<string, ParticipantCard>()
  const out: ParticipantCard[] = []
  for (let index = 0; index < cards.length; index += 1) {
    const card = cards[index]
    if (card.kind !== 'actor' || card.roleLabel !== '方案' || !schemeWithChildren.has(card.title)) {
      out.push(card)
      continue
    }
    const existing = bars.get(card.title)
    if (existing) {
      // 同名方案的第二行泳道：只并子专家名，不再出一条条
      for (const line of dispatchLinesOf(card, cards, index, isTopLevelOf)) {
        if (!existing.lines.includes(line)) {
          existing.lines.push(line)
        }
      }
      continue
    }
    const lines = dispatchLinesOf(card, cards, index, isTopLevelOf)
    // 连子专家名都拿不到就不画条：宁可不出，也不要一条空空的分发条
    if (!lines.length) {
      continue
    }
    const bar: ParticipantCard = {
      ...card,
      key: `route-${card.title}`,
      kind: 'route',
      // 分发条只写分给谁：方案自己的思考与结果属于子专家，不在这里重复
      steps: [],
      answer: '',
      errors: [],
      files: [],
      look: card.look,
      pending: false,
      lines
    }
    bars.set(card.title, bar)
    out.push(bar)
  }
  return out
}

/**
 * 方案分发条的人话：优先用后端已落库的分流句，没有就按真实子专家名拼一句。
 *
 * @param card          方案卡
 * @param cards         全部卡片
 * @param index         该卡位置
 * @param isTopLevelOf  是否入口候选
 * @return 一行或多行分发说明；没有任何子专家时为空
 */
function dispatchLinesOf(
  card: ParticipantCard,
  cards: ParticipantCard[],
  index: number,
  isTopLevelOf?: (name: string) => boolean
): string[] {
  const recorded = (Array.isArray(card.lines) ? card.lines : []).filter((line) =>
    (line || '').includes('已把问题分给')
  )
  if (recorded.length) {
    return recorded
  }
  const children = childNamesAfter(cards, index, isTopLevelOf)
  if (!children.length) {
    return []
  }
  // 与后端 schemeChildrenLineOf 同一句式，只是数据来自本轮真实子专家
  return [`已把问题分给${children.map((name) => `「${name}」`).join('')}`]
}

/**
 * 某条方案之后、下一位入口候选之前的专家名（方案真正分下去的那几位）。
 *
 * @param cards        全部卡片
 * @param index        方案卡位置
 * @param isTopLevelOf 是否入口候选
 * @return 子专家名（去重、保持顺序）
 */
function childNamesAfter(
  cards: ParticipantCard[],
  index: number,
  isTopLevelOf?: (name: string) => boolean
): string[] {
  const names: string[] = []
  for (let cursor = index + 1; cursor < cards.length; cursor += 1) {
    const next = cards[cursor]
    if (next.kind === 'gateway' || next.kind === 'route' || next.kind === 'conclusion') {
      continue
    }
    if (!isTopLevelOf || isTopLevelOf(next.title)) {
      break
    }
    if (!names.includes(next.title)) {
      names.push(next.title)
    }
  }
  return names
}

/**
 * 去掉「什么都没拿到」的方案空壳；自己在作答、有正文 / 工具 / 报错 / 文件的方案保留。
 *
 * <p>分发条已经在上一步单独产出，这里只处理剩下的方案参与者块：
 * 历史上同一方案被推了多行泳道、又没有任何内容时，不能留一行空标题。
 *
 * @param cards 已替换分发条的卡片
 * @return 消息流实际要画的卡片
 */
function dropEmptySchemeShells(cards: ParticipantCard[]): ParticipantCard[] {
  return cards.filter((card) => {
    if (card.kind !== 'actor' || card.roleLabel !== '方案') {
      return true
    }
    return !!card.answer.trim() || card.steps.length > 0 || card.errors.length > 0 || card.files.length > 0
  })
}

/**
 * 还没对上专家的文件和报错，接到最后一位专家或方案结论上，避免再堆在消息流底部。
 *
 * @param cards     已去掉分流空壳的卡片
 * @param collected 归集结果
 */
function attachLoose(cards: ParticipantCard[], collected: CollectedAnswers) {
  const looseFiles = Array.isArray(collected.looseFiles) ? collected.looseFiles : []
  const looseErrors = Array.isArray(collected.looseErrors) ? collected.looseErrors : []
  const looseAnswers = Array.isArray(collected.looseAnswers) ? collected.looseAnswers : []
  if (!looseFiles.length && !looseErrors.length && !looseAnswers.length) {
    return
  }
  // 承接方必须是一条能放气泡的参与者；判定条 / 分发条本身不挂内容
  const host = [...cards].reverse().find((card) => card.kind === 'actor' || card.kind === 'conclusion')
  if (!host) {
    return
  }
  host.files.push(...looseFiles)
  host.errors.push(...looseErrors)
  if (looseAnswers.length) {
    // 分段标题对不上任何参与者时仍要让人看到正文：接到最后一位参与者，不整段丢掉
    host.answer = [host.answer, ...looseAnswers].filter((part) => !!part).join('\n\n')
  }
}

/**
 * 思考区里不该出现的占位句：状态空壳、分流人话、空记录。
 *
 * @param line  一行说明
 * @param title 当前参与者名
 * @return true 表示这行不进折叠思考
 */
function isFillerLine(line: string, title: string): boolean {
  const text = (line || '').trim()
  if (!text || text === '正在分发…' || text === '这是方案自己的汇总结论' || text === '没有额外的思考记录') {
    return true
  }
  if (text.startsWith('已把问题分给')) {
    return true
  }
  return text === expertStatusLine(title, true) || text === expertStatusLine(title, false)
}

/**
 * 工具调用标题里已经标了失败的，把结果句提出来放进气泡。折叠区仍保留完整步骤。
 *
 * @param steps 该参与者的思考与工具步
 * @return 失败说明；没有失败调用时为空
 */
function toolFailureLines(steps: ProcessStep[]): string[] {
  const errors: string[] = []
  for (let index = 0; index < steps.length; index += 1) {
    const step = steps[index]
    if (!step || step.type !== 'tool_call' || !(step.title || '').includes('失败')) {
      continue
    }
    const next = steps[index + 1]
    const detail = next && next.type === 'tool_result' ? (next.text || '').trim() : ''
    const line = detail || (step.title || '').trim()
    if (line) {
      errors.push(line)
    }
  }
  return errors
}

/**
 * 后端 CONCAT 终答的分段：`### {专家名}` 起一段。
 *
 * <p>并行 / 多子轮次落库时只有一条 ASSISTANT 事件，正文是「### 甲 … ### 乙 …」的拼接稿。
 * 只把它挂给最后一位，就会出现「整段正文堆在一个人身上、别人什么都没有」。
 * 这里按标题切回各自的作者。
 *
 * @param text 助手正文
 * @return 分段；不是拼接稿（没有标题行）时为空
 */
export function splitNamedSections(text: string): { name: string; text: string }[] {
  const lines = (text || '').split('\n')
  const sections: { name: string; text: string }[] = []
  let current: { name: string; text: string[] } | undefined
  for (const line of lines) {
    // ATX 标题就是分段标记；名字去掉尾部「：」与空格，便于对上参与者展示名
    const heading = /^\s{0,3}#{1,6}\s+(.+?)\s*$/.exec(line)
    if (heading) {
      if (current) {
        sections.push({ name: current.name, text: current.text.join('\n').trim() })
      }
      current = { name: (heading[1] || '').replace(/[：:]\s*$/, '').trim(), text: [] }
      continue
    }
    if (current) {
      current.text.push(line)
    }
  }
  if (current) {
    sections.push({ name: current.name, text: current.text.join('\n').trim() })
  }
  // 文首若有散段（首个标题之前的内容），不在这里处理：由调用方按原逻辑归给当前参与者
  return sections.filter((section) => !!section.name)
}

/**
 * 按块顺序把正文归到「当前路由的参与者」或「方案结论」。
 *
 * <p>方案名不计入「还没交正文的专家」。连续两位专家都还没有自己的正文时，
 * 后面那一整段是拼接终答，不挂到最后一位。
 *
 * <p>真正多子并行时后端只落一条 ASSISTANT（CONCAT 拼接稿），正文里带「### 专家名」分段。
 * 这类正文按标题发回各自的作者，不再整段堆在最后一位身上。
 *
 * @param blocks        本轮块
 * @param targetTypeOf  展示名 → 目标类型；方案不占用专家正文名额
 * @param answerableSchemes 自己在作答的方案名（没有子专家）；这些名字可以接正文
 * @param knownActors   本轮真实出现过的参与者名；分段标题据此认领
 * @return 专家结论与方案汇总结论
 */
function collectAnswers(
  blocks: AssistantBlock[],
  targetTypeOf?: (name: string) => 'AGENT' | 'MA' | 'WORKFLOW' | undefined,
  answerableSchemes: Set<string> = new Set(),
  knownActors: Set<string> = new Set()
): CollectedAnswers {
  const byName = new Map<string, string>()
  const conclusions: { name: string; text: string }[] = []
  const filesByName = new Map<string, TrialFileView[]>()
  const errorsByName = new Map<string, string[]>()
  const looseFiles: TrialFileView[] = []
  const looseErrors: string[] = []
  const looseAnswers: string[] = []
  const extraAnswers: { name: string; text: string }[] = []
  let owner = ''
  let conclusionName = ''
  let lastExpert = ''
  const rememberFile = (file: TrialFileView) => {
    const who = expertOwner(owner, lastExpert, targetTypeOf, answerableSchemes)
    if (!who) {
      looseFiles.push(file)
      return
    }
    const list = filesByName.get(who) || []
    list.push(file)
    filesByName.set(who, list)
  }
  const rememberError = (text: string) => {
    const who = expertOwner(owner, lastExpert, targetTypeOf, answerableSchemes)
    if (!who) {
      looseErrors.push(text)
      return
    }
    const list = errorsByName.get(who) || []
    list.push(text)
    errorsByName.set(who, list)
  }
  for (const block of Array.isArray(blocks) ? blocks : []) {
    if (!block) {
      continue
    }
    if (block.kind === 'route') {
      const text = (block.text || '').trim()
      if (text.startsWith(SCHEME_CONCLUSION_MARK)) {
        conclusionName = text.slice(SCHEME_CONCLUSION_MARK.length).trim() || '方案'
        owner = ''
        continue
      }
      conclusionName = ''
      // 参与者名必须同时认「路由到 X」与固定模板的「固定并行 1/2 · X」，
      // 否则固定模板跑出来的子专家拿不到自己的正文与文件
      const name = parseRouteParticipant(text)?.name || parseRouteText(text).name
      owner = name || ''
      if (name && targetTypeOf?.(name) !== 'MA') {
        lastExpert = name
      }
      continue
    }
    if (block.kind === 'file' && block.file) {
      rememberFile(block.file)
      continue
    }
    if (block.kind === 'error') {
      const text = sanitizeThinkText(block.text)
      if (text) {
        rememberError(text)
      }
      continue
    }
    if (block.kind !== 'assistant' || !(block.text || '').trim()) {
      continue
    }
    const piece = sanitizeThinkText(block.text)
    if (!piece) {
      continue
    }
    if (conclusionName) {
      const last = conclusions[conclusions.length - 1]
      if (last && last.name === conclusionName) {
        last.text += piece
      } else {
        conclusions.push({ name: conclusionName, text: piece })
      }
      continue
    }
    // 后端 CONCAT 拼接稿：按「### 专家名」分段发回各自的作者，不再整段堆给最后一位。
    // 只有「至少两段能对上本轮参与者 / 当前归属」时才当拼接稿：
    // 专家自己写的报告常带「### 一、制度依据」这类小标题，绝不能被当成多人分段。
    const sections = splitNamedSections(piece)
    const claimed = sections.filter((section) => knownActors.has(section.name) || section.name === owner)
    // 方案自己不接正文：这种轮次里的多段稿只可能是它分下去的子专家写的，按标题补作者
    const schemeOwned = !!owner && targetTypeOf?.(owner) === 'MA'
    const isConcat = claimed.length >= 2 || (sections.length >= 2 && (schemeOwned || claimed.length === 1))
    if (isConcat) {
      for (const section of sections) {
        if (!section.text) {
          continue
        }
        if (knownActors.has(section.name)) {
          byName.set(section.name, (byName.get(section.name) || '') + section.text)
        } else if (sections.length >= 2) {
          // 多段拼接稿：标题就是作者声明，本轮没有它的泳道也补一条，不能整段消失
          const existing = extraAnswers.find((item) => item.name === section.name)
          if (existing) {
            existing.text += `\n\n${section.text}`
          } else {
            extraAnswers.push({ name: section.name, text: section.text })
          }
        } else {
          looseAnswers.push(section.text)
        }
      }
      continue
    }
    /*
     * 不是多人拼接稿，就是「刚被钉住路由的那一位」的本步结果。
     *
     * 这里不能再按「还有几位没交正文」整段丢弃：并行轮次所有「路由到」都先到、
     * 各自结论后到，丢弃会让两位专家同时只剩折叠条。正文只属于当前 owner，
     * 不会被复制给别的人；方案自己有子专家时不接正文，落回最后一位参与者兜底。
     */
    const ownerTakesAnswer = !!owner && (targetTypeOf?.(owner) !== 'MA' || answerableSchemes.has(owner))
    if (ownerTakesAnswer && owner) {
      byName.set(owner, (byName.get(owner) || '') + piece)
    } else if (claimed.length === 1) {
      // 当前归属是方案、但这一段自带子专家标题：按标题归给那位，不落到方案
      byName.set(claimed[0].name, (byName.get(claimed[0].name) || '') + claimed[0].text)
    } else {
      // 认不出作者也不整段丢：接到最后一位参与者气泡里
      looseAnswers.push(piece)
    }
  }
  return { byName, conclusions, filesByName, errorsByName, looseFiles, looseErrors, looseAnswers, extraAnswers }
}

/**
 * 文件和报错只挂到专家，不挂到「分了子专家」的方案名上。
 *
 * @param owner             当前路由名
 * @param lastExpert        最近一位非方案参与者
 * @param targetTypeOf      展示名 → 目标类型
 * @param answerableSchemes 自己在作答的方案名
 * @return 应接收的专家名；还没有专家时为空
 */
function expertOwner(
  owner: string,
  lastExpert: string,
  targetTypeOf?: (name: string) => 'AGENT' | 'MA' | 'WORKFLOW' | undefined,
  answerableSchemes: Set<string> = new Set()
): string {
  if (owner && (targetTypeOf?.(owner) !== 'MA' || answerableSchemes.has(owner))) {
    return owner
  }
  return lastExpert
}

/**
 * 时间线一步 → 消息流卡片。方案分流块不挂结果，避免把子专家正文再抄一遍。
 *
 * @param step   时间线步骤
 * @param index  顺序
 * @param byName 专家结论
 * @return 卡片
 */
function toCard(step: ThinkTimelineStep, index: number, collected: CollectedAnswers): ParticipantCard {
  // 网关只出判定句。专家折叠区丢掉状态空壳，失败工具句另外放进气泡。
  const stepLines = Array.isArray(step.lines) ? step.lines : []
  const stepSteps = Array.isArray(step.steps) ? step.steps : []
  const title = step.title || '专家'
  const answer = step.kind === 'gateway' ? '' : (collected.byName.get(title) || '')
  // 方案块的说明行就是「已把问题分给…」：改成下发条后还要用，不能当占位句滤掉
  const lines = step.kind === 'gateway' || step.roleLabel === '方案'
    ? stepLines
    : stepLines.filter((line) => !isFillerLine(line, title))
  const errors = step.kind === 'gateway' ? [] : [...(collected.errorsByName.get(title) || []), ...toolFailureLines(stepSteps)]
  const files = step.kind === 'gateway' ? [] : (collected.filesByName.get(title) || [])
  const pending = step.pending && !answer && !errors.length && !files.length
  /*
   * 每位参与者都要能直接看到「这一步的结果」。
   * 正文/报错/文件都有各自的展示位；只有当什么都没拿到时，才用真实事件补一句最短结论：
   * 文件清单 → 最后一条工具返回（截断，不摊 JSON）→ 现网状态句。都不编造。
   */
  const result = step.kind === 'gateway' || answer || errors.length
    ? ''
    : stepResultText(title, stepSteps, files, pending)
  return {
    key: `${step.kind}-${index}-${title}`,
    kind: step.kind === 'gateway' ? 'gateway' : 'actor',
    title,
    roleLabel: step.roleLabel,
    lines,
    steps: step.kind === 'gateway' ? [] : stepSteps,
    answer: answer || result,
    errors,
    files,
    look: step.look,
    pending
  }
}

/** 参与者在气泡里可见的最短结论的最大字数：折叠区仍有完整过程 */
const STEP_RESULT_MAX = 200

/**
 * 没有成文正文时，按真实事件给这一位补一句「本步结果」。
 *
 * <ul>
 *   <li>有产物：写生成了哪些文件（文件卡仍单独展示，文字不能缺）；</li>
 *   <li>有工具返回：截断最后一条返回（机器 JSON 不摊到气泡里）；</li>
 *   <li>只在跑：交给调用方显示进行态；</li>
 *   <li>以上都没有：用现网已有一致的状态句，不再造「没有额外的思考记录」。</li>
 * </ul>
 *
 * @param steps   该参与者自己的工具 / 推理步
 * @param files   归到该参与者的产物
 * @param pending 是否仍在生成
 * @return 可见结果句；不会返回空串
 */
function stepResultText(
  title: string,
  steps: ProcessStep[],
  files: TrialFileView[],
  pending: boolean
): string {
  if (files.length) {
    const names = files
      .map((file) => (file?.fileName || '').trim())
      .filter(Boolean)
      .slice(0, 3)
    const tail = files.length > names.length ? ' 等' : ''
    return names.length
      ? `已生成 ${files.length} 个文件：${names.join('、')}${tail}`
      : `已生成 ${files.length} 个文件`
  }
  const last = [...steps].reverse().find((step) => step.type === 'tool_result' && (step.text || '').trim())
  const body = (last?.text || '').trim()
  // 机器 JSON / 键值串不进气泡：它属于折叠区里的工具过程
  if (body && !/^[[{]/.test(body)) {
    return body.length > STEP_RESULT_MAX ? `${body.slice(0, STEP_RESULT_MAX)}…` : body
  }
  const calls = steps.filter((step) => step.type === 'tool_call').length
  if (calls > 0) {
    return `本步已调用工具 ${calls} 次，工具过程见折叠`
  }
  // 什么都没有：用现网一致的状态句，不造「没有额外的思考记录」
  return pending ? '' : expertStatusLine(title, false)
}

/**
 * 把方案汇总结论插到该方案最后一个子块之后。
 *
 * <p>子块的判定：不是入口候选（isTopLevelOf 为 false）的后续卡片，以及已经插进去的内层结论。
 * 下一位入口候选开始，就不再算这个方案的内部。
 *
 * @param cards       已按发生顺序排好的卡片
 * @param conclusions 汇总结论
 * @param isTopLevelOf 是否入口候选
 * @return 插入结论后的卡片
 */
function placeConclusions(
  cards: ParticipantCard[],
  conclusions: { name: string; text: string }[],
  isTopLevelOf?: (name: string) => boolean
): ParticipantCard[] {
  const out = cards.slice()
  conclusions.forEach((item, index) => {
    // 方案可能已被换成「分发条」，也可能仍在作答（没有子专家）
    const schemeIndex = out.findIndex(
      (one) => one.title === item.name && (one.kind === 'route' || (one.kind === 'actor' && one.roleLabel === '方案'))
    )
    const scheme = schemeIndex >= 0 ? out[schemeIndex] : undefined
    /*
     * 同一方案只出一块：有结论时把结论放回它自己那块的结果位，
     * 分流句原样保留成这一块的可见结果。绝不并排再插一块「企业综合台」。
     */
    if (scheme) {
      out.splice(schemeIndex, 1, {
        ...scheme,
        key: `conclusion-${index}-${item.name}`,
        kind: 'conclusion',
        roleLabel: '方案',
        // 分发条原本不带头像；块化后仍用它自己的形象
        answer: item.text.trim(),
        errors: [],
        files: [],
        pending: false
      })
      return
    }
    // 结论对应不上任何方案块（历史数据）：排在本轮最后一位参与者之后
    let after = out.length - 1
    for (let cursor = 0; cursor < out.length; cursor += 1) {
      const next = out[cursor]
      if (next.kind === 'gateway' || next.kind === 'conclusion' || next.kind === 'route') {
        continue
      }
      // 下一位入口候选开始，本方案的内部到此结束
      if (isTopLevelOf?.(next.title) === true) {
        break
      }
      after = cursor
    }
    out.splice(after + 1, 0, {
      key: `conclusion-${index}-${item.name}`,
      kind: 'conclusion',
      title: item.name,
      roleLabel: '方案',
      lines: [],
      steps: [],
      answer: item.text.trim(),
      errors: [],
      files: [],
      pending: false
    })
  })
  return out
}
