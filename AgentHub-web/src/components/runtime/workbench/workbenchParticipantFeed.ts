/**
 * 工作台对话消息流的参与者分块。所属域：runtime（前端）。
 *
 * <p>入口块、方案（如企业综合台）、各位专家统一成**同一套聊天块**：
 * 左侧头像 + 名称（角标：入口「对话入口」、方案「方案」、单兵专家「Agent」）+ 一块内容框（气泡），
 * 思考默认折叠在名称与内容框之间。名称一律取配置 / 路由事件里的展示名，投影层不写死别名。
 * 本步结果写在内容框里，不做框外提示行；不再输出居中判定条 / 居中路由条。
 * 数据仍是已有轨迹，不另造过程事件：入口块写判定句，方案写分给了谁，
 * 专家写自己的正文 / 文件清单 / 工具返回摘要。思考只进折叠，不当唯一内容。
 *
 * <p>方案的**分流**与**汇总**是两条独立记录：分流的块在分发完成后就冻结，
 * 事后到来的汇总另起一条（排在所有子专家之后），不会回到分流那条里再思考。
 *
 * <p><b>每步三拍、互不串块</b>：该步真正开始才出块，然后先思考（本步推理）→ 再本步自己的工具胶囊
 * → 最后本步结果。入口块只有判定推理与判定句，方案分流块只有分流推理与分流句（出完冻结），
 * 专家块只有自己的推理 / 工具 / 正文，真汇总只有汇总模型自己的推理与结论；
 * 任何一块都不复制其它步骤的事件或正文。
 */
import type { AssistantBlock, TrialChoiceOption, TrialChoiceView, TrialFileView, TrialWorkflowView } from '@/types/runtime'
import type { ProcessStep } from '@/components/runtime/trialMessages'
import { parseRouteParticipant } from './workbenchRouteText.ts'
import {
  buildThinkTimeline,
  expertStatusLine,
  parseRouteText,
  sanitizeAnswerText,
  sanitizeThinkText,
  WORKFLOW_ROLE_LABEL,
  type ExpertLookView,
  type ThinkTimelineInput,
  type ThinkTimelineStep
} from './workbenchThinkTimeline.ts'

/** 方案自己的汇总结论在路由事件上的前缀；后面是方案名 */
export const SCHEME_CONCLUSION_MARK = '方案结论：'

/**
 * 可点选项机器块的围栏语言标记（仅历史会话兼容）。与后端 {@code WorkbenchChoiceJson.FENCE_LANG}
 * 同一字面量，改一边必须同步改另一边（它是前后端契约，不是内部字段）。
 *
 * <p>本刀起新写入不再产生机器块：选项走后端结构化产出（工具 / 整理）并随 CHOICE 事件下发，
 * 这里只为「老会话回放」保留解析。
 */
export const CHOICE_FENCE_LANG = 'agenthub-choice'
/** 选项数量边界：与后端校验一致，越界整块作废（宁可不给选项，也不放一组看不懂的按钮） */
const CHOICE_MIN_OPTIONS = 2
const CHOICE_MAX_OPTIONS = 6

/** 一个可点选项（与 {@link TrialChoiceOption} 同一定义，前端只有一套类型） */
export type ParticipantChoiceOption = TrialChoiceOption

/** 投影后的选项块（结构化选项事件，或历史会话里从正文摘掉的机器块） */
export type ParticipantChoiceView = TrialChoiceView

/**
 * 从助手正文里摘出 {@code agenthub-choice} 机器块（**仅历史会话兼容**）。
 *
 * <p>本刀起选项由后端结构化产出并随 CHOICE 事件下发，正文里不再有机器块；
 * 这个解析只为「老会话回放」保留：机器块必须从展示正文里去掉，否则用户会看到一段裸 JSON；
 * 同时只有结构合法的块才渲染成 chips（与后端同一套边界：围栏语言必须精确匹配、
 * 有闭合围栏、选项 2~6 个、id / label 非空且 id 不重复）。
 *
 * <p>半截块（仍在流式 / 被截断）一律不当选项：正文保持原样，交给 Markdown 渲染。
 *
 * @param raw 助手正文（Markdown）
 * @return 去掉机器块后的正文；命中合法块时附带选项
 */
export function parseChoiceBlock(raw: string): { text: string; choice?: ParticipantChoiceView } {
  const source = raw || ''
  const open = source.indexOf('```' + CHOICE_FENCE_LANG)
  if (open < 0) {
    return { text: source }
  }
  // 语言标记后必须紧跟换行（允许尾随空白）：```` ```agenthub-choiceXYZ ```` 这类同前缀块不能被误认
  const bodyStart = source.indexOf('\n', open)
  if (bodyStart < 0) {
    return { text: source }
  }
  const info = source.slice(open + 3 + CHOICE_FENCE_LANG.length, bodyStart)
  if (info.trim().length > 0) {
    return { text: source }
  }
  const close = source.indexOf('```', bodyStart + 1)
  if (close < 0) {
    return { text: source }
  }
  const choice = readChoiceJson(source.slice(bodyStart + 1, close))
  if (!choice) {
    return { text: source }
  }
  // 机器块摘掉；块后若还有正文，按原顺序接上（通常是空，块要求放在最后）
  const text = (source.slice(0, open) + source.slice(close + 3)).trim()
  return { text, choice }
}

/**
 * 解析块内 JSON 并做边界校验。
 *
 * @param body 围栏内的原文
 * @return 合法选项；任何一项不合格都返回 undefined（整块作废）
 */
function readChoiceJson(body: string): ParticipantChoiceView | undefined {
  let parsed: unknown
  try {
    parsed = JSON.parse((body || '').trim())
  } catch {
    // 模型偶发输出非法 JSON：整块忽略，正文照常展示
    return undefined
  }
  if (!parsed || typeof parsed !== 'object') {
    return undefined
  }
  const raw = parsed as { question?: unknown; options?: unknown }
  if (!Array.isArray(raw.options)) {
    return undefined
  }
  const options: ParticipantChoiceOption[] = []
  for (const item of raw.options) {
    if (!item || typeof item !== 'object') {
      return undefined
    }
    const one = item as { id?: unknown; label?: unknown }
    const id = typeof one.id === 'string' ? one.id.trim() : ''
    const label = typeof one.label === 'string' ? one.label.trim() : ''
    if (!id || !label) {
      return undefined
    }
    // id 重复会让续作点位歧义：整块作废，不给一组点了会对错人的按钮
    if (options.some((row) => row.id.toLowerCase() === id.toLowerCase())) {
      return undefined
    }
    options.push({ id, label })
  }
  if (options.length < CHOICE_MIN_OPTIONS || options.length > CHOICE_MAX_OPTIONS) {
    return undefined
  }
  const question = typeof raw.question === 'string' ? raw.question.trim() : ''
  return { question, options }
}

/** 消息流里的一块参与者（网关 / 方案 / 专家同一种聊天块，只有角标与结果来源不同） */
export interface ParticipantCard {
  /** 渲染 key */
  key: string
  /**
   * gateway＝入口块（判定句是它内容框里的话），角标「对话入口」；
   * actor＝专家 / 自己在作答的方案 / 已分给子专家的方案（分流句是它内容框里的话）；
   * conclusion＝方案**汇总**那一条独立记录（与分流那条分开，排在子专家之后）
   */
  kind: 'gateway' | 'actor' | 'conclusion'
  /** 展示名（取配置 / 路由事件里的名字，投影层不写死别名） */
  title: string
  /**
   * 「方案」；普通专家留空（角标由展示层按 kind 统一给出：入口「对话入口」、专家「Agent」）。
   */
  roleLabel?: string
  /** 内容框里的本步结果句（网关判定 / 方案分流），以及折叠区里的说明 */
  lines: string[]
  /** 思考区里的推理与工具；入口块装判定模型自己的推理，方案块装它自己的分流推理 */
  steps: ProcessStep[]
  /** 内容框里的成文正文（专家结论 / 方案汇总）；网关与只做分发的方案为空 */
  answer: string
  /**
   * 需要用户拍板时的可点选项。
   *
   * <p>来源只有后端结构化产出（工具 / 后端整理）下发的 CHOICE 事件；老会话里
   * 正文中的机器块仍按历史兼容解析。只在「单专家 / 入口直分的叶子」上出现：
   * 多专家并行、串行交接、方案目标都不出 chips。
   */
  choice?: ParticipantChoiceView
  /** 归到这个人气泡里的工具失败说明和 error 块 */
  errors: string[]
  /** 归到这个人气泡里的文件卡 */
  files: TrialFileView[]
  /**
   * 工作流执行卡片视图（只有工作流块会带）。
   *
   * <p>带它时这一块**不画内容框**，画的是「自动化执行流」卡片（节点时间轴 + 产物区）；
   * 终答文本也在卡片的产物区里，不再重复进气泡。
   */
  workflow?: TrialWorkflowView
  /** 形象；入口块走 lookOf(配置名)，与专家同一套兜底 */
  look?: ExpertLookView
  /** 还没有结论、仍在生成 */
  pending: boolean
  /**
   * 是否是整轮的**最后一条**记录。
   *
   * <p>只有它完成后不限高、完整展开；前面所有块即使写完了也保持四行。
   * 由投影按最终顺序标记（有真汇总时是汇总那条；无汇总时是最后一位作答者）。
   */
  isFinal?: boolean
  /**
   * 本轮正文只有一句「承诺 / 收尾」，不是交付物（方案被写进了思考通道）。
   *
   * <p>投影层据此先不把这句话当结果画出来；由补位阶段决定给哪一种明确说明。
   * 真实正文一旦落到这块上（{@link attachLoose}），标记立刻清掉，说明不会顶掉正文。
   */
  answerPromised?: boolean
}

interface CollectedAnswers {
  /** 专家名 → 该参与者自己的成文结论 */
  byName: Map<string, string>
  /**
   * 专家名 → 该参与者这一轮的结构化选项。
   *
   * <p>为什么按名字归集：选项随 CHOICE 事件到达，本身不带「挂在谁的块上」的语义；
   * 用与文件 / 报错同一套归属规则（身份帧优先，其次当前路由）落到具体参与者，
   * chips 才会出现在真正让用户拍板的那一位气泡下面。
   */
  choiceByName: Map<string, ParticipantChoiceView>
  /** 按出现顺序的方案汇总结论（另起一条记录用） */
  conclusions: { name: string; text: string }[]
  /**
   * 汇总阶段这一条记录自己的思考与工具步，按**方案名**分开。
   *
   * <p>后端在汇总时会先推「方案结论：X」，随后才是汇总模型的 reasoning / tool 增量。
   * 这些增量属于**汇总这条记录**，不属于分流那条——分流已经在分发完成时冻结。
   *
   * <p>为什么按名字分组而不是一条公共列表：嵌套方案（方案里再跑一套方案）会先后推两条
   * 「方案结论：内层」「方案结论：外层」。用一条公共列表会让内层的思考 / 工具同时出现在
   * 外层汇总记录上——那正是「把 A 步的思考挂到 B 步」。
   */
  conclusionSteps: Map<string, ProcessStep[]>
  /**
   * 本轮是否真的见过「方案结论：X」标记。
   *
   * <p>落库回放常常没有这个标记（后端只落各子的「路由到」与最终合并正文），
   * 需要据此判断末尾那段正文是不是汇总，而不是最后一位子专家自己的话。
   */
  sawConclusionMark: boolean
  /**
   * 末尾那段正文是否来自「拼接稿按 `### 作者` 分段」。
   *
   * <p>这类正文是各位子专家自己的话，已经被各归各家，**不是**方案汇总；
   * 回放兜底必须据此放手，否则会把最后一位子专家的正文错认成汇总正文。
   */
  tailFromConcat: boolean
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
 * 入口判定是否仍在进行中：本轮还没有任何 route，也没有正文 / 工具 / 产物。
 *
 * <p>判路由模型现在会把真实推理推出来，因此「入口判定进行中」时消息流里已经有内容可画，
 * 但还没有任何判定结果。这一段必须画成**入口块自己的思考**，且不能预先写一句判定句。
 *
 * <p>只要出现 route、正文、工具或产物，就说明判定已经落地（或这条链路根本不判路由），
 * 交回既有投影口径处理；一个推理块都没有时同样不算「进行中」——没有真实思考就不该占位。
 *
 * @param blocks 本轮原始块
 * @return true 表示入口判定还在跑
 */
function entryJudgingOf(blocks: AssistantBlock[]): boolean {
  let thinking = false
  for (const block of Array.isArray(blocks) ? blocks : []) {
    if (!block) {
      continue
    }
    if (block.kind === 'route') {
      return false
    }
    if (block.kind === 'assistant' && (block.text || '').trim()) {
      return false
    }
    if (block.kind === 'tool' || block.kind === 'file') {
      return false
    }
    // 只有真有推理增量才算「判定进行中」：空的思考块不画框，也不提前占位
    if (block.kind === 'reasoning' && (block.text || '').trim()) {
      thinking = true
    }
  }
  return thinking
}

/**
 * 这一轮是不是「已有真实结果」：正文 / 工具 / 产物 / 选项任一出现，就说明某一步真的开始产出了。
 *
 * <p>用来区分「入口判定模型还在推思考」与「某位参与者已经在干活」；
 * 只有前者才允许先不出块（见 {@link buildParticipantFeed} 的拦截）。
 *
 * @param blocks 本轮原始块
 * @return true 表示已有真实结果
 */
function hasRealResult(blocks: AssistantBlock[]): boolean {
  return (Array.isArray(blocks) ? blocks : []).some((block) => {
    if (!block) {
      return false
    }
    if (block.kind === 'assistant') {
      return !!(block.text || '').trim()
    }
    return block.kind === 'tool' || block.kind === 'file' || block.kind === 'choice'
  })
}

/**
 * 是否正处于「等入口报身份」的阶段。
 *
 * <p>只有满足全部条件才会拦：
 * <ol>
 *   <li>调用方说这一轮期待走入口（`expectEntry`，新问题为 true；断点续作为 false）；</li>
 *   <li>还没有任何 ROUTE 事件；</li>
 *   <li>还没有入口身份帧（实时流 `gw-` 的 begin）；</li>
 *   <li>还没有任何真实结果（正文 / 工具 / 产物 / 选项）。</li>
 * </ol>
 *
 * <p>此时匿名泳道里的推理只能是入口判定模型的思考：先画成一位「专家」再改成入口，
 * 就是用户看到的「先闪一下某专家」。宁可晚一拍出块，也不先挂错身份再改。
 *
 * @param input  投影入参
 * @param blocks 本轮原始块
 * @return true 表示暂不出块
 */
function awaitingEntryIdentity(
  input: ThinkTimelineInput & { blocks: AssistantBlock[] },
  blocks: AssistantBlock[]
): boolean {
  return feedWaitsForEntry(input, blocks)
}

/**
 * 「等入口报身份」这一拍的公开判定。
 *
 * <p>为什么还要导出：这一拍消息流里**故意没有块**，页面若按「没有块」回落到逐块渲染，
 * 行首就会挂上上一位专家的大头像与小标题——用户看到的仍是「先闪一下某专家」。
 * 页面据此把行首身份也一并收起来，等入口块真正出现再画。
 *
 * @param input  投影入参（只要有 blocks / routeTexts / entryParticipantId / expectEntry）
 * @param blocks 本轮原始块；不传时取 `input.blocks`
 * @return true 表示此刻应「先不出块、也不出头像」
 */
export function feedWaitsForEntry(
  input: { blocks?: AssistantBlock[]; routeTexts?: string[]; entryParticipantId?: string; expectEntry?: boolean },
  blocks?: AssistantBlock[]
): boolean {
  if (input.expectEntry !== true) {
    return false
  }
  if ((input.routeTexts || []).length > 0) {
    return false
  }
  if ((input.entryParticipantId || '').trim()) {
    return false
  }
  return !hasRealResult(blocks ?? input.blocks ?? [])
}

/**
 * 投影本轮消息流分块。
 *
 * <p>网关、方案、专家是同一套聊天块，按真实发生顺序排队：
 * 网关块（判定句在内容框里）→ 方案块（分流句在内容框里）→ 各专家块；
 * 有真汇总时汇总另起一条排在最后。不再产出居中判定条 / 居中路由条。
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
    /*
     * 等入口报身份：这一轮是新问题（可能要走入口判定），但既没有路线、也没有入口身份帧，
     * 而且还没有任何真实结果（正文 / 工具 / 产物 / 选项）。
     *
     * <p>此时那些匿名泳道里的推理**只可能**是入口判定模型正在推的思考。
     * 若照常出块，它会先被画成「某位专家（按当前线路名兜底）正在思考」，
     * 等 route / 身份帧到达再变成「入口判定」——就是用户看到的「先闪一下前端技术专家」。
     * 按「块要等这一步真的开始」的口径，这里先不出块，等身份到了再画。
     *
     * <p>断点续作轮不会走到这里（`expectEntry=false`）：那种轮次本来就没有入口判定。
     */
    if (awaitingEntryIdentity(input, blocks)) {
      return []
    }
    const steps = withoutSchemeShells(
      buildThinkTimeline({
        ...input,
        // 工作流卡片快照：只有它在时，工作流才会单独成一块并画执行卡片
        workflow: latestWorkflowOf(blocks),
        /*
         * 判定还在跑的前提是：既没有路线，也没有正文 / 工具 / 产物，
         * 而且**本轮确实有位入口参与者**（实时流由 gw- 身份帧给出）。
         * 断点续作的专家也会先推推理，但它不是入口判定——没有入口身份就不能这么判，
         * 否则专家自己的思考会被抢到一块凭空出现的「入口判定」里。
         */
        entryJudging: input.routeTexts.length === 0 && !!input.entryParticipantId && entryJudgingOf(blocks)
      })
    )
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
    /*
     * 回放兜底：后端落库通常只写各子的「路由到」与最终合并正文，
     * **不会**落「方案结论：X」标记。这种情况下如果方案确实分给了子专家、且末尾有一段
     * 没被任何子专家认领的正文，那段就是汇总——不补的话它会落到最后一位子专家头上，
     * 用户就会看到「报告撰写专家被放大成终局、综合台汇总那条消失」。
     */
    const dispatchedSchemeTitle = steps.find((step) => step.roleLabel === '方案')?.title || ''
    /*
     * 先按「方案自己名下的正文」认回结论：回放里 `### 企业综合台` 那一段就是它自己的汇总，
     * 不是子专家的话（子专家各有各的标题）。这是最硬的证据，不需要靠语气猜。
     */
    recoverSchemeSectionAsConclusion(
      collected,
      knownActors,
      dispatchedSchemeTitle,
      schemeWithChildren.size > 0
    )
    recoverConclusionFromTail(
      collected,
      knownActors,
      dispatchedSchemeTitle,
      schemeWithChildren.size > 0
    )
    const cards = steps.map((step) => toCard(step, collected))
    // 入口也是参与者：头像与专家同源走 lookOf（未配置时由 lookOf 内部给固定机器人），不另造一套
    for (const card of cards) {
      if (card.kind === 'gateway' && !card.look) {
        card.look = input.lookOf?.(card.title)
      }
    }
    // 没有判定句也没有思考的入口块不画：只有头像和名字、框里没字的块就是个空壳。
    // 反过来，判定还在跑时它**只有**思考（没有判定句），必须留着，否则入口思考看不见。
    // 工作流占位块同理：本轮已收尾却仍没收到卡片快照（工作流根本没起来），占位也不再留。
    const withGateway = cards.filter(
      (card) =>
        (card.kind !== 'gateway' || card.lines.length > 0 || card.steps.length > 0) &&
        (input.streaming || !(card.roleLabel === WORKFLOW_ROLE_LABEL && !card.workflow))
    )
    // 只在拼接稿里出现过、本轮却没有泳道的作者（历史会话常见）：补一条参与者消息
    const withExtra = appendExtraActors(withGateway, collected, input.lookOf)
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
    // 分了子专家的方案：仍是自己的聊天块，只是内容框里的话从「正文」换成「分给了谁」
    const withSchemes = schemeDispatchBlocks(withExtra, dispatchSchemes, input.isTopLevelOf)
    /*
     * 汇总另起一条：排在所有子专家之后，不回填分流那条。
     * 分流块在分发完成时就已冻结，后面专家执行、方案汇总都不再改动它。
     */
    const withConclusions = appendConclusionRecords(
      withSchemes,
      collected.conclusions,
      collected.conclusionSteps,
      input.streaming,
      input.lookOf
    )
    // 自己在作答的方案（入口点名、没有子专家）必须留着，否则网关块之后整段空白
    const visible = dropEmptySchemeShells(withConclusions)
    // 先归属：没人认领的真实正文落到最后一位参与者气泡（盖掉任何兜底句）
    attachLoose(visible, collected)
    // 再补位：确实一句话都没有的块才写「本步结果」，真实正文永远不会被收尾句顶掉
    fillResultFallbacks(visible)
    markFinal(visible, input.streaming === true)
    return visible
  } catch (error) {
    // 只提示排障，不把内部键/正文写进日志；返回空数组让调用方回退到逐块渲染
    console.warn('[工作台消息流] 本轮分块投影失败，已回退到逐块渲染', error)
    return []
  }
}

/**
 * 本轮最新一份工作流卡片快照。
 *
 * <p>取最后一条而不是第一条：同一轮里 `workflow` 块是**原位覆盖**的，
 * 正常只会有一条；即便出现两条（历史数据 / 并行两个工作流），也以最后到达的为准，
 * 页面与「当时看到的」保持一致。
 *
 * @param blocks 本轮原始块
 * @return 卡片视图；没有工作流块时为 undefined
 */
function latestWorkflowOf(blocks: AssistantBlock[]): TrialWorkflowView | undefined {
  for (let index = blocks.length - 1; index >= 0; index -= 1) {
    const workflow = blocks[index]?.workflow
    if (workflow) {
      return workflow
    }
  }
  return undefined
}

/**
 * 标记整轮的**最后一条**记录：只有它完成后不限高。
 *
 * <p>口径：
 * <ul>
 *   <li>有真汇总时：最后一条是汇总记录（有正文或带过程的那条），它就是终局；</li>
 *   <li>没有汇总时：最后一位**有正文**的参与者才是终局——通常是入口直分的单专家，
 *       或只分给子专家但没有汇总的那条末尾专家；</li>
 *   <li>找不到任何有正文的块（例如只报了一句工作流状态）时，不标记，谁都不放开高度。</li>
 * </ul>
 *
 * <p>为什么不直接取数组末尾：末尾可能是只做分流的方案块（它的内容就是分流句，
 * 不该被放大成终局），也可能是空壳。必须按「有没有成文正文」这一步筛。
 *
 * @param cards 已排好序的卡片（就地打标）
 */
/**
 * 标记「整轮最后一条真正结论」，只有它的内容框从头就铺开。
 *
 * <p>**流式中的判定更严**：只认数组末尾那一条。理由：并行的另一位专家还在跑时，
 * 前面那位刚写完的专家只是「先交了卷」，不是终局；若此刻把它标成终局，它会先铺开、
 * 等后面那条出正文再收缩，用户看到的是高度来回跳。结束之后仍按「最后一条有正文的参与者」兜底
 * （后端偶尔不会给最末位补正文）。
 *
 * @param cards     已排好序的卡片
 * @param streaming 本轮是否仍在流式输出
 */
function markFinal(cards: ParticipantCard[], streaming = false): void {
  for (const card of cards) {
    card.isFinal = false
  }
  // 汇总记录优先：它在数组末尾且一定有正文（appendConclusionRecords 已滤掉空记录）
  const conclusion = [...cards].reverse().find((card) => card.kind === 'conclusion')
  if (conclusion) {
    conclusion.isFinal = true
    return
  }
  // 没有汇总：最后一位真有正文的参与者才是终局。
  // 只有一组可点选项、没有正文的块同样是「本步结果」，不能因为 answer 为空就不放开高度
  const expert = [...cards]
    .reverse()
    .find((card) => card.kind === 'actor' && (!!card.answer.trim() || !!card.choice))
  // 流式中只认末尾那一条：前面刚写完的专家还在等别人，不能提前铺开
  if (expert && (!streaming || expert === cards[cards.length - 1])) {
    expert.isFinal = true
  }
}

/**
 * 展示用：去掉重复的方案空壳与匿名执行块。
 *
 * <p>同一方案名被推了多次「路由到」时，归组只把子专家挂到最后一块，前面几块只剩
 * 「正在由／已由「方案名」作答」。消息流和补充轨迹都不该把这些空壳画出来。
 *
 * <p>另一个要丢的是**匿名执行块**：汇总阶段的过程事件（reasoning / tool）在时间线里
 * 没有对应的参与者，会被兜成一条标题为「执行」的匿名专家块。它既不是网关也不是专家，
 * 汇总过程已单独收进「汇总」那条记录，画出来等于多一条无主人的参与者。
 *
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
    // 匿名执行块：汇总阶段的过程事件，汇总记录已经承载，不另画一条无名参与者
    if (isAnonymousExecutionStep(step)) {
      continue
    }
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
 * 是否是时间线兜底出来的匿名执行块。
 *
 * <p>`processLanes` 在「没有当前泳道」时会把推理 / 工具落进一条标题为「执行」的匿名车道。
 * 汇总阶段正是这种情况（「方案结论：」不是参与者，其后的过程事件就没有归属）。
 *
 * @param step 时间线步骤
 * @return true 表示这条块没有真实参与者
 */
function isAnonymousExecutionStep(step: ThinkTimelineStep): boolean {
  return step.kind === 'expert' && step.title === '执行'
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
 * 删掉就会让网关块之后整段空白。
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
    // 已经有同名参与者（专家或方案）时不重复造块
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
 * 把「分了子专家的方案」收成同一套聊天块，内容框里的话换成「分给了谁」。
 *
 * <p>为什么不能删：方案这一块是真实事件——它确实把问题分下去了，
 * 用户也要能看到「企业综合台分给了谁」。但它和网关、专家一样是一位参与者，
 * 所以保留「头像 + 名称 + 内容框」的块形态，只把框里的话从「正文」换成「分流句」。
 * 分流句优先用后端已落库的「已把问题分给…」原句，没有就按真实子专家名拼一句。
 *
 * <p>同一方案被推了多行泳道时合并成一块（并子专家名），不出两块壳。
 * 拿不到任何子专家名时不画块：宁可不出，也不要一块内容框里没字的空壳。
 *
 * @param cards             按发生顺序排好的卡片
 * @param dispatchSchemes   真的分了子专家的方案名
 * @param isTopLevelOf      是否入口候选；用于确定这条方案的子专家范围
 * @return 收成聊天块后的卡片
 */
function schemeDispatchBlocks(
  cards: ParticipantCard[],
  dispatchSchemes: Set<string>,
  isTopLevelOf?: (name: string) => boolean
): ParticipantCard[] {
  if (!dispatchSchemes.size) {
    return cards
  }
  const blocks = new Map<string, ParticipantCard>()
  const out: ParticipantCard[] = []
  for (let index = 0; index < cards.length; index += 1) {
    const card = cards[index]
    if (card.kind !== 'actor' || card.roleLabel !== '方案' || !dispatchSchemes.has(card.title)) {
      out.push(card)
      continue
    }
    const existing = blocks.get(card.title)
    if (existing) {
      // 同名方案的第二行泳道：只并子专家名，不再出第二块
      for (const line of dispatchLinesOf(card, cards, index, isTopLevelOf)) {
        if (!existing.lines.includes(line)) {
          existing.lines.push(line)
        }
      }
      continue
    }
    const lines = dispatchLinesOf(card, cards, index, isTopLevelOf)
    // 连子专家名都拿不到就不画块：宁可不出，也不要一块内容框里没字的空壳
    if (!lines.length) {
      continue
    }
    const block: ParticipantCard = {
      ...card,
      /*
       * key 沿用分流前那张卡（`actor-方案名`）：分流句到达时只换内容框里的话，
       * 不能让 key 跟着变——一变 Vue 就卸载重建，方案的思考框会闪一下重新展开。
       */
      kind: 'actor',
      // 分流句就是这一块内容框里的话：放 lines，不占用 answer 正文位；
      // 方案自己的思考（分流判定模型的推理）留在 steps 里，子专家的思考各自在他们块里
      lines,
      steps: Array.isArray(card.steps) ? card.steps : [],
      answer: '',
      errors: [],
      files: [],
      look: card.look,
      pending: false
    }
    blocks.set(card.title, block)
    out.push(block)
  }
  return out
}

/**
 * 方案分流句的人话：优先用后端已落库的分流句，没有就按真实子专家名拼一句。
 *
 * @param card          方案卡
 * @param cards         全部卡片
 * @param index         该卡位置
 * @param isTopLevelOf  是否入口候选
 * @return 一行或多行分流说明；没有任何子专家时为空
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
 * <p>嵌套方案：遇到「另一位方案」就把它算进来并停——那一层的内层子专家归它自己那块，
 * 不能重复算进外层方案的分流结果。
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
  const selfTitle = cards[index]?.title
  for (let cursor = index + 1; cursor < cards.length; cursor += 1) {
    const next = cards[cursor]
    // 网关与已回填的结论块不是「这一位分下去的子专家」，跳过继续找
    if (next.kind === 'gateway' || next.kind === 'conclusion') {
      continue
    }
    if (!isTopLevelOf || isTopLevelOf(next.title)) {
      break
    }
    // 内层方案：外层确实把它分下去了，名字要算；但它自己的子专家归它那块，到此为止
    if (next.roleLabel === '方案' && next.title !== selfTitle) {
      if (!names.includes(next.title)) {
        names.push(next.title)
      }
      break
    }
    if (!names.includes(next.title)) {
      names.push(next.title)
    }
  }
  return names
}

/**
 * 去掉「什么都没拿到」的方案空壳。
 *
 * <p>方案块有两种合法形态，都要保留：
 * 自己在作答（有正文 / 工具 / 报错 / 文件），或已分给子专家（内容框里有分流句）。
 * 历史上同一方案被推了多行泳道、又没有任何内容时，不能留一块只有名字的空壳。
 *
 * @param cards 已收成分流块的卡片
 * @return 消息流实际要画的卡片
 */
function dropEmptySchemeShells(cards: ParticipantCard[]): ParticipantCard[] {
  return cards.filter((card) => {
    if (card.kind !== 'actor' || card.roleLabel !== '方案') {
      return true
    }
    return (
      !!card.answer.trim() ||
      card.lines.length > 0 ||
      card.steps.length > 0 ||
      card.errors.length > 0 ||
      card.files.length > 0
    )
  })
}

/**
 * 还没对上专家的文件和报错，接到最后一位专家或方案结论上，避免再堆在消息流底部。
 *
 * <p>承接顺序要避开「只做分发的方案块」：那一块内容框里的话就是分流句，
 * 把子专家正文挂上去会变成「方案把子专家的话复述了一遍」。
 *
 * @param cards     已去掉空壳的卡片
 * @param collected 归集结果
 */
function attachLoose(cards: ParticipantCard[], collected: CollectedAnswers) {
  const looseFiles = Array.isArray(collected.looseFiles) ? collected.looseFiles : []
  const looseErrors = Array.isArray(collected.looseErrors) ? collected.looseErrors : []
  const looseAnswers = Array.isArray(collected.looseAnswers) ? collected.looseAnswers : []
  if (!looseFiles.length && !looseErrors.length && !looseAnswers.length) {
    return
  }
  /*
   * 承接方必须是一条「专家」块：
   * - 网关块与只做分发的方案块不接内容（它们的内容框里的话是本步结果）；
   * - 汇总记录也不接（那是方案汇总自己的结论，混进子专家的散稿会篡改它）；
   * - 已带执行卡片的工作流块优先让位：它的正文在卡片产物区里，再接一次就是同一段话出现两遍。
   */
  const host =
    [...cards].reverse().find((card) => card.kind === 'actor' && card.roleLabel !== '方案' && !card.workflow) ||
    [...cards].reverse().find((card) => card.kind === 'actor' && card.roleLabel !== '方案')
  if (!host) {
    return
  }
  host.files.push(...looseFiles)
  host.errors.push(...looseErrors)
  /*
   * 工作流卡片的终答已经在产物区里展示：同一段话再进气泡就是重复。
   * 只有在卡片确实带了终答时才放手，卡片没有正文（运行中 / 失败前）仍照常承接。
   */
  const answers = host.workflow && (host.workflow.answer || '').trim() ? [] : looseAnswers
  if (answers.length) {
    /*
     * 分段标题对不上任何参与者时仍要让人看到正文：接到最后一位参与者，不整段丢掉。
     * 仍要去掉机器块：兜底路径也不能把一段裸 JSON 漏到气泡里（选项已在原块上渲染过了）。
     *
     * 这里**不保留**任何已有的兜底状态句：那只是「还没拿到正文」时的占位，
     * 真正文一到就必须让位，不能出现「已由 X 完成作答 + 半截正文」。
     *
     * <p>只承诺、不给内容的收尾句（「点选后我会给出…」）不进气泡：它不是交付物。
     * 若这一段**整段**都只是这种句子，就把这块标成「只有承诺」，交给补位阶段
     * 给一句明确说明——绝不把思考草稿搬进结果框冒充终答。
     */
    const parts = answers
      .map((part) => parseChoiceBlock(part).text)
      .filter((part) => !!part)
    const extras = parts.filter((part) => !isPromiseOnlyAnswer(part))
    const existing = isFillerLine(host.answer || '', host.title) ? '' : (host.answer || '')
    host.answer = [existing, ...extras].filter((part) => !!part.trim()).join('\n\n')
    // 真实正文已经落到这块上：之前的「只有承诺句」标记必须清掉，否则补位说明会顶掉正文
    if (extras.length) {
      host.answerPromised = false
    } else if (parts.length) {
      host.answerPromised = true
    }
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
 * 方案的说明行里哪些只是状态空壳。
 *
 * <p>与专家的差别：方案的「已把问题分给…」是内容框里的本步结果，必须保留；
 * 只有「正在分发…」「已由「X」完成作答」这类不带结果的壳才丢掉。
 *
 * @param line  一行说明
 * @param title 当前方案名
 * @return true 表示这行该丢
 */
function isSchemeShellLine(line: string, title: string): boolean {
  const text = (line || '').trim()
  if (!text || text === '正在分发…' || text === '没有额外的思考记录') {
    return true
  }
  return text === expertStatusLine(title, true) || text === expertStatusLine(title, false)
}

/**
 * 工具失败说明：只把**第一次**失败的原因放进气泡，后续同类失败不再重复堆红字。
 *
 * <p>原先每一条失败工具都会往气泡里塞一段结果（往往是同一句「撞限：consecutive_fail_abort」），
 * 并行扇出几路之后气泡里就是一片重复的熔断文案，真实根因反而看不见。
 * 现在：只取首条失败、优先取后端附带的「首次失败原因」段、
 * 其余次数只记一条汇总（{@code 同名工具另失败 N 次}），详细过程仍在胶囊详情里。
 *
 * @param steps 该参与者的思考与工具步
 * @return 失败说明；没有失败调用时为空
 */
function toolFailureLines(steps: ProcessStep[]): string[] {
  const lines: string[] = []
  let failedCount = 0
  let firstLine = ''
  for (let index = 0; index < steps.length; index += 1) {
    const step = steps[index]
    const title = (step?.title || '').trim()
    const isFailure = step?.type === 'tool_call' && (step.state === 'error' || title.includes('失败'))
    if (!step || !isFailure) {
      continue
    }
    failedCount += 1
    if (firstLine) {
      continue
    }
    const next = steps[index + 1]
    const detail = next && next.type === 'tool_result' ? (next.text || '').trim() : ''
    firstLine = firstFailureLineOf(detail) || shortFailureOf(detail || title)
  }
  if (failedCount > 0) {
    lines.push(firstLine)
    if (failedCount > 1) {
      lines.push(`同名工具另失败 ${failedCount - 1} 次`)
    }
  }
  return lines.filter((line) => !!line)
}

/**
 * 从工具结果里摘出后端附带的「首次失败原因」。
 *
 * @param text 工具结果正文
 * @return 一句话；没有该段时为空串
 */
function firstFailureLineOf(text: string): string {
  const matched = /首次失败原因（([^）]*)）：([\s\S]+)$/.exec((text || '').trim())
  if (!matched) {
    return ''
  }
  const kind = (matched[1] || '').trim()
  const body = (matched[2] || '').trim().split('\n')[0].trim()
  const short = body.length > 140 ? `${body.slice(0, 140)}…` : body
  return `首次失败（${kind}）：${short}`
}

/**
 * 没有首错段时，把一段失败正文压成一句短说明（撞限文案原样保留，不改写）。
 *
 * @param raw 失败正文
 * @return 一句说明；空正文返回「未记录到首错」
 */
function shortFailureOf(raw: string): string {
  const text = (raw || '').trim()
  if (!text) {
    return '工具调用失败（未记录到首错）'
  }
  const first = text.split('\n')[0].trim()
  return first.length > 140 ? `${first.slice(0, 140)}…` : first
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
 * 汇总阶段一段 reasoning / tool 块 → 思考步。
 *
 * <p>为什么不直接调 `trialMessages.simpleProcessSteps`：那个模块依赖 `@/` 别名，
 * 而本文件要能在 node 里被单测直接加载（`npm test` 用 strip-types 直跑 .ts）。
 * 这里按同一套口径重写最小版本：推理一段、工具调用的标题与参数拆开、结果单独一步。
 *
 * @param blocks 汇总阶段的块（通常只有一块）
 * @return 思考步；空正文不产出步骤
 */
function summarizePhaseSteps(blocks: AssistantBlock[]): ProcessStep[] {
  const steps: ProcessStep[] = []
  for (const block of blocks) {
    const text = (block.text || '').trim()
    const detail = (block.detail || '').trim()
    if (block.kind === 'reasoning') {
      if (text) {
        steps.push({ type: 'reasoning', title: '推理', text })
      }
      continue
    }
    if (block.kind !== 'tool') {
      continue
    }
    if (text) {
      // 与专家块同一口径：标题只取工具名，参数正文单独一格
      const failed = /·\s*失败/.test(text)
      const matched = /^调用工具\s+([^\s，,·]+)/.exec(text)
      const name = matched ? `调用工具 ${matched[1]}` : '调用工具'
      const body = text.replace(/^调用工具\s+[^\s，,·]+\s*/, '').replace(/^\s*，参数\s*/, '').trim()
      steps.push({
        type: 'tool_call',
        title: failed ? `${name} · 失败` : name,
        text: body || '（无参数）'
      })
    }
    if (detail) {
      steps.push({ type: 'tool_result', title: '工具结果', text: detail })
    }
  }
  return steps
}

/**
 * 把「### 作者名」分段稿按标题发回各自的作者。
 *
 * <p>两处共用：普通轮次的 CONCAT 拼接稿，以及**汇总失败降级成拼接**的那段稿
 * （标记已推过、正文其实是子专家拼起来的）。后者若被当汇总，最下会多一条内容对不上的
 * 「综合台汇总」块。
 *
 * @param sections     分段
 * @param knownActors  本轮真实出现过的参与者名
 * @param byName       专家正文归集（就地追加）
 * @param extraAnswers 本轮没有泳道的作者（就地追加）
 * @param looseAnswers 认不出作者的分段（就地追加）
 */
function distributeNamedSections(
  sections: { name: string; text: string }[],
  knownActors: Set<string>,
  byName: Map<string, string>,
  extraAnswers: { name: string; text: string }[],
  looseAnswers: string[]
): void {
  for (const section of sections) {
    if (!section.text) {
      continue
    }
    if (knownActors.has(section.name)) {
      byName.set(section.name, (byName.get(section.name) || '') + section.text)
      continue
    }
    if (sections.length >= 2) {
      // 多段拼接稿：标题就是作者声明，本轮没有它的泳道也补一条，不能整段消失
      const existing = extraAnswers.find((item) => item.name === section.name)
      if (existing) {
        existing.text += `\n\n${section.text}`
      } else {
        extraAnswers.push({ name: section.name, text: section.text })
      }
      continue
    }
    looseAnswers.push(section.text)
  }
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
  const choiceByName = new Map<string, ParticipantChoiceView>()
  const conclusions: { name: string; text: string }[] = []
  const conclusionSteps = new Map<string, ProcessStep[]>()
  const filesByName = new Map<string, TrialFileView[]>()
  const errorsByName = new Map<string, string[]>()
  const looseFiles: TrialFileView[] = []
  const looseErrors: string[] = []
  const looseAnswers: string[] = []
  const extraAnswers: { name: string; text: string }[] = []
  let owner = ''
  let conclusionName = ''
  let lastExpert = ''
  let sawConclusionMark = false
  let tailFromConcat = false
  const rememberFile = (file: TrialFileView, pinned = '') => {
    const who = expertOwner(pinned || owner, lastExpert, targetTypeOf, answerableSchemes)
    if (!who) {
      looseFiles.push(file)
      return
    }
    const list = filesByName.get(who) || []
    list.push(file)
    filesByName.set(who, list)
  }
  const rememberError = (text: string, pinned = '') => {
    const who = expertOwner(pinned || owner, lastExpert, targetTypeOf, answerableSchemes)
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
        sawConclusionMark = true
        conclusionName = text.slice(SCHEME_CONCLUSION_MARK.length).trim() || '方案'
        owner = ''
        // 标记一出现就占好位置：汇总的思考/工具可能先于正文到达，
        // 这一条记录必须从「开始汇总」起就存在，不能等正文才冒出
        if (!conclusions.some((item) => item.name === conclusionName)) {
          conclusions.push({ name: conclusionName, text: '' })
        }
        continue
      }
      // 汇总阶段结束（或根本没进汇总）：后续事件不再算进汇总那条记录
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
    /*
     * 汇总这条记录自己的过程：后端「方案结论：X」之后紧跟汇总模型的 reasoning / tool 增量。
     * 它们属于汇总那条新记录，绝不能记回分流那条（否则分流块会二次进入思考）；
     * 按方案名分开存，嵌套方案的两次汇总各归各的。
     */
    if (conclusionName && (block.kind === 'reasoning' || block.kind === 'tool')) {
      const bucket = conclusionSteps.get(conclusionName) || []
      bucket.push(...summarizePhaseSteps([block]))
      conclusionSteps.set(conclusionName, bucket)
      continue
    }
    if (block.kind === 'file' && block.file) {
      rememberFile(block.file, pinOf(block, targetTypeOf))
      continue
    }
    /*
     * 结构化选项：归属规则与文件 / 报错完全一致（身份帧优先，其次当前路由）。
     * 它不属于「正文」，因此不参与拼接稿分段的任何判断。
     */
    if (block.kind === 'choice' && block.choice && block.choice.options.length) {
      const who = expertOwner(pinOf(block, targetTypeOf) || owner, lastExpert, targetTypeOf, answerableSchemes)
      if (who) {
        choiceByName.set(who, block.choice)
      }
      continue
    }
    if (block.kind === 'error') {
      const text = sanitizeThinkText(block.text)
      if (text) {
        rememberError(text, pinOf(block, targetTypeOf))
      }
      continue
    }
    if (block.kind !== 'assistant' || !(block.text || '').trim()) {
      continue
    }
    const piece = sanitizeAnswerText(block.text)
    if (!piece) {
      continue
    }
    if (conclusionName) {
      /*
       * 拼接兜底不是真汇总。
       *
       * <p>后端汇总失败时会降级成 CONCAT（「### 甲 … ### 乙 …」的拼接稿），但「方案结论：X」
       * 标记可能已经先推过。若把这段拼接稿记成汇总结论，最下就会出现一条「综合台汇总」，
       * 内容其实是子专家各说各话——用户说的「汇总块没有最终回答」正是这种。
       * 多段且至少两段对得上本轮参与者时，按「### 作者」发回各自气泡，汇总这条不落正文。
       */
      const summarySections = splitNamedSections(piece)
      const summaryClaimed = summarySections.filter((section) => knownActors.has(section.name))
      /*
       * 判据与普通拼接稿一致：多段 + 至少一段对得上本轮参与者，就是「### 作者」拼接稿。
       * 汇总这条记录的归属本身就是方案（MA），这种稿只可能是它分下去的子专家写的。
       */
      if (summarySections.length >= 2 && summaryClaimed.length >= 1) {
        distributeNamedSections(summarySections, knownActors, byName, extraAnswers, looseAnswers)
        tailFromConcat = true
        continue
      }
      const last = conclusions[conclusions.length - 1]
      if (last && last.name === conclusionName) {
        last.text += piece
      } else {
        conclusions.push({ name: conclusionName, text: piece })
      }
      continue
    }
    /*
     * 有身份帧的轮次：这段正文属于谁由 identity 直接决定，不看「最后一条 route 文案」。
     * 交错时（A 还在说、B 的 route 已到）按文案会把 A 的话挂到 B 名下，正是要修的串块。
     */
    const pinnedOwner = pinOf(block, targetTypeOf)
    if (pinnedOwner) {
      lastExpert = pinnedOwner
    }
    const speaker = pinnedOwner || owner
    // 后端 CONCAT 拼接稿：按「### 专家名」分段发回各自的作者，不再整段堆给最后一位。
    // 只有「至少两段能对上本轮参与者 / 当前归属」时才当拼接稿：
    // 专家自己写的报告常带「### 一、制度依据」这类小标题，绝不能被当成多人分段。
    const sections = splitNamedSections(piece)
    const claimed = sections.filter((section) => knownActors.has(section.name) || section.name === speaker)
    // 方案自己不接正文：这种轮次里的多段稿只可能是它分下去的子专家写的，按标题补作者
    const schemeOwned = !!speaker && targetTypeOf?.(speaker) === 'MA'
    const isConcat = claimed.length >= 2 || (sections.length >= 2 && (schemeOwned || claimed.length === 1))
    if (isConcat) {
      distributeNamedSections(sections, knownActors, byName, extraAnswers, looseAnswers)
      // 这一段是「### 作者」分段稿：各归各家，不能当方案汇总
      tailFromConcat = true
      continue
    }
    /*
     * 不是多人拼接稿，就是「刚被钉住路由的那一位」的本步结果。
     *
     * 这里不能再按「还有几位没交正文」整段丢弃：并行轮次所有「路由到」都先到、
     * 各自结论后到，丢弃会让两位专家同时只剩折叠条。正文只属于当前 owner，
     * 不会被复制给别的人；方案自己有子专家时不接正文，落回最后一位参与者兜底。
     */
    const ownerTakesAnswer = !!speaker && (targetTypeOf?.(speaker) !== 'MA' || answerableSchemes.has(speaker))
    if (ownerTakesAnswer && speaker) {
      byName.set(speaker, (byName.get(speaker) || '') + piece)
    } else if (claimed.length === 1) {
      // 当前归属是方案、但这一段自带子专家标题：按标题归给那位，不落到方案
      byName.set(claimed[0].name, (byName.get(claimed[0].name) || '') + claimed[0].text)
      tailFromConcat = true
    } else {
      // 认不出作者也不整段丢：接到最后一位参与者气泡里
      looseAnswers.push(piece)
    }
  }
  return { byName, choiceByName, conclusions, conclusionSteps, sawConclusionMark, tailFromConcat, filesByName, errorsByName, looseFiles, looseErrors, looseAnswers, extraAnswers }
}

/**
 * 回放兜底：把「方案自己名下那段正文」认成它的汇总结论（没有结论记录时新建，有则补正文）。
 *
 * <p>为什么这条最可靠：后端多问并跑的落库正文是「### 作者 + 正文」拼接稿，
 * 方案自己那一段用的就是方案的展示名。这一段只可能是方案自己写的汇总，
 * 不是任何子专家的话（子专家各自有各自的标题），因此不需要靠语气猜。
 *
 * <p>两种落库形状都要接住：
 * <ul>
 *   <li>有「方案结论：X」标记、但正文只在末尾拼接稿里：把那段补进已有的结论记录，
 *       否则汇总那条会只剩一句「没写出正文」；</li>
 *   <li>没有标记（旧会话 / 落库漏写）：按这段正文新建一条结论记录。</li>
 * </ul>
 *
 * <p>不认的情形：方案体里自己还是一份「### 子专家 … ### 子专家 …」拼接稿——
 * 说明方案内部走的是 CONCAT，不是真汇总，不假造一块结论。
 *
 * @param collected        归集结果（就地修正）
 * @param knownActors      本轮真实出现过的参与者名
 * @param schemeTitle      入口方案的展示名；没有方案时为空串
 * @param schemeDispatched 本轮方案是否真的分给了子专家
 */
function recoverSchemeSectionAsConclusion(
  collected: CollectedAnswers,
  knownActors: Set<string>,
  schemeTitle: string,
  schemeDispatched: boolean
): void {
  if (!schemeDispatched) {
    return
  }
  const scheme = schemeTitle.trim()
  if (!scheme) {
    return
  }
  /*
   * 标记一出现，正文可能已经被上面按「汇总这段」收进去了；这里只处理「还在 byName 里」的情况。
   * 没有 byName 正文时什么都不做，绝不拿子专家的话拼一条结论。
   */
  const own = (collected.byName.get(scheme) || '').trim()
  if (!own) {
    return
  }
  /*
   * 方案这一段里面若还套着两位以上子专家的标题，它本身就是拼接稿：
   * 记成「汇总」就等于把子专家的话搬成一结论，正是要避免的假汇总。
   */
  const innerSections = splitNamedSections(own)
  const innerClaimed = innerSections.filter((section) => section.name !== scheme && knownActors.has(section.name))
  if (innerClaimed.length >= 2) {
    return
  }
  collected.byName.delete(scheme)
  // chips 必须跟着真正出选项的那一段走，不能留在被搬空的方案块上
  collected.choiceByName.delete(scheme)
  /*
   * 已经有结论记录（真汇总开始过、标记已落库）：说明这段是那条记录的正文，
   * 补进原记录，别另起一条。已有正文时保持原样，避免同一段话出现两次。
   */
  const record = collected.conclusions.find((item) => item.name === scheme)
  if (record) {
    if (!record.text.trim()) {
      record.text = own
    }
    return
  }
  collected.conclusions.push({ name: scheme, text: own })
}

/**
 * 回放兜底：没有「方案结论：X」标记时，把末尾那段汇总正文认回来。
 *
 * <p>后端落库形状（Router 汇总轮）：`路由到 方案` → `路由到 子甲` → `路由到 子乙`
 * → 一条 ASSISTANT（合并后的汇总正文）。**没有**汇总标记，也没有各子的独立正文。
 * 旧逻辑会把这条 ASSISTANT 归给「当前 owner」＝最后一位子专家，于是：
 *
 * <ul>
 *   <li>最后那位子专家被放大成整轮结论（正文还不属于它）；</li>
 *   <li>方案汇总这条记录永远不会出现 —— 用户说的「总结完那条没了」正是这个。</li>
 * </ul>
 *
 * <p>为什么必须保守：多数轮次里「最后一位子专家的正文」本来就是它自己的话，
 * 把它认成汇总会让子专家凭空丢掉结果（实测会让 6 个既有用例全红）。
 * 因此只有**正文里明确出现汇总口吻**（或带汇总专属小标题）时才认回，
 * 认不出就宁可不动 —— 少认一次只是少一条汇总，错认一次会篡改子专家的话。
 *
 * @param collected         归集结果（就地修正）
 * @param knownActors       本轮真实出现过的参与者名
 * @param schemeTitle       入口方案的展示名；没有方案时为空串
 * @param schemeDispatched  本轮方案是否真的分给了子专家
 */
function recoverConclusionFromTail(
  collected: CollectedAnswers,
  knownActors: Set<string>,
  schemeTitle: string,
  schemeDispatched: boolean
): void {
  if (collected.sawConclusionMark || !schemeDispatched || collected.conclusions.length) {
    return
  }
  const scheme = schemeTitle.trim()
  if (!scheme) {
    return
  }
  /*
   * 拼接稿不能当汇总：`### 甲 … ### 乙 …` 是各子自己的话，已经被各归各家。
   * 不排除它的话，最后一位子专家的正文会被错认成汇总，汇总那条正文也就跟着错。
   */
  if (collected.tailFromConcat) {
    return
  }
  // 只认「最后一位子专家名下的正文」：它就是可能被错认成终局的那段
  const lastExpert = [...knownActors].reverse().find((name) => (collected.byName.get(name) || '').trim())
  if (!lastExpert) {
    return
  }
  const text = (collected.byName.get(lastExpert) || '').trim()
  if (!looksLikeSchemeSummary(text, scheme)) {
    return
  }
  collected.byName.delete(lastExpert)
  // 正文搬到汇总那条记录后，选项不能再留在空掉的专家块上（chips 必须跟在真正出选项的那段下面）
  collected.choiceByName.delete(lastExpert)
  collected.conclusions.push({ name: scheme, text })
}

/**
 * 一段正文是否「读起来像方案汇总」。
 *
 * <p>只认两类明确线索，不做模糊猜测：
 * <ul>
 *   <li>正文里出现方案自己的展示名（「企业综合台汇总 …」这类落库文案）；</li>
 *   <li>正文带汇总专属小标题（`### 汇总` / `## 综合结论` / `一、总体结论` 之类）。</li>
 * </ul>
 *
 * @param text   正文
 * @param scheme 方案展示名
 * @return true 表示可以当作汇总
 */
function looksLikeSchemeSummary(text: string, scheme: string): boolean {
  const body = (text || '').trim()
  if (!body) {
    return false
  }
  // 第一行带方案名（后端落库时会把「X 汇总：…」作为首行）
  const head = body.split('\n')[0] || ''
  if (scheme && head.includes(scheme)) {
    return true
  }
  // 汇总口吻的小标题
  return /^\s{0,3}#{1,6}\s*(汇总|综合结论|总体结论|总结)/m.test(body)
}

/**
 * 块身份 → 该块内容应归属的参与者展示名。
 *
 * <p>只认身份帧写下的名字；方案（MA）不接正文 / 文件（否则会篡改分流结论），
 * 仍交给既有兜底（最后一位专家）。
 *
 * @param block         本轮块
 * @param targetTypeOf  展示名 → 目标类型
 * @return 参与者名；没有身份或目标是方案时为空串
 */
function pinOf(
  block: AssistantBlock,
  targetTypeOf?: (name: string) => 'AGENT' | 'MA' | 'WORKFLOW' | undefined
): string {
  const name = (block.participantName || '').trim()
  if (!name) {
    return ''
  }
  return targetTypeOf?.(name) === 'MA' ? '' : name
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
 * 时间线一步 → 聊天块。网关 / 方案 / 专家同一种块，只是内容框里的东西不同。
 *
 * @param step      时间线步骤
 * @param collected 正文 / 文件 / 报错的归属结果
 * @return 卡片
 */
function toCard(step: ThinkTimelineStep, collected: CollectedAnswers): ParticipantCard {
  // 网关只出判定句；专家折叠区丢掉状态空壳，失败工具句另外放进气泡
  const stepLines = Array.isArray(step.lines) ? step.lines : []
  const stepSteps = Array.isArray(step.steps) ? step.steps : []
  const title = step.title || '专家'
  /*
   * 正文里的历史机器块先从展示正文里摘出来：用户只该看到自然语言 + 可点 chips，不该看到裸 JSON。
   * 本刀起选项走结构化事件，{@code choiceByName} 里那份是权威来源，优先于历史机器块。
   */
  const parsedAnswer = parseChoiceBlock(step.kind === 'gateway' ? '' : (collected.byName.get(title) || ''))
  /*
   * 只承诺、不给内容的收尾句不算正文。
   *
   * <p>用户手点过的坑：点选续作时模型把整份方案写进了思考通道，正文只剩
   * 「点选后我会按该路径给出代码与配置」。这句是承诺而不是交付物，绝不能当结果画出来
   * 顶替方案；这里先摘掉，等 {@link fillResultFallbacks} 按「是否真的写进了思考」给一句
   * 明确说明，且**绝不**把思考草稿搬进结果框冒充终答。
   */
  const answerPromised = step.kind !== 'gateway' && !collected.choiceByName.get(title)
    && isPromiseOnlyAnswer(parsedAnswer.text)
  const answer = answerPromised ? '' : parsedAnswer.text
  const choice = (step.kind === 'gateway' ? undefined : collected.choiceByName.get(title)) || parsedAnswer.choice
  // 网关的判定句、方案的分流句就是内容框里的话，只滤掉状态空壳，不把结果句当填充句丢掉
  const lines = step.kind === 'gateway'
    ? stepLines
    : step.roleLabel === '方案'
      ? stepLines.filter((line) => !isSchemeShellLine(line, title))
      : stepLines.filter((line) => !isFillerLine(line, title))
  const errors = step.kind === 'gateway' ? [] : [...(collected.errorsByName.get(title) || []), ...toolFailureLines(stepSteps)]
  const files = step.kind === 'gateway' ? [] : (collected.filesByName.get(title) || [])
  // 网关永远不是「正在作答」的一方：它有判定句就是结果，不能再补一句进行态。
  // 只有一组可点选项的块同样已经是「本步结果」，不能判成还在跑
  const pending = step.kind !== 'gateway' && step.pending && !answer && !choice && !errors.length && !files.length
  /*
   * 这里**只放真实正文**，不再顺手补状态句。
   *
   * <p>补位必须等「没人认领的正文」落到气泡之后（{@link fillResultFallbacks}）：
   * 先补一句「已由「X」完成作答」再去拼 loose 正文，气泡就变成「收尾句 + 半截正文」，
   * 用户看到的正是「完整方案被一句收尾盖住了」。顺序反过来才保证真实正文永远在上、且完整。
   */
  return {
    /*
     * key 只用「种类 + 展示名」，**不带数组下标**。
     *
     * <p>带下标会出事：新增一位参与者时，它之后所有块的下标整体位移，Vue 会把这些
     * 块的 key 判成新 key，于是先卸载再重建 —— 页面上就是「块先消失再重新出现」。
     * 同一轮里同名同种类的参与者只会有一条（方案分流与方案汇总是 kind 不同），
     * 因此「种类 + 名字」在本轮内唯一且稳定。
     */
    key: `${step.kind}-${title}`,
    kind: step.kind === 'gateway' ? 'gateway' : 'actor',
    title,
    roleLabel: step.roleLabel,
    lines,
    /*
     * 网关自己的思考也要留着：入口判定模型的真实推理就是这一块的折叠内容。
     * 原先这里把网关步骤整段丢掉，入口因此永远没有「查看思考过程与工具调用」。
     */
    steps: stepSteps,
    answer,
    // 选项只挂在真正给出选项的块上：网关判定句与方案分流句不参与拍板
    choice,
    errors,
    files,
    look: step.look,
    workflow: step.workflow,
    pending,
    answerPromised
  }
}

/** 参与者在气泡里可见的最短结论的最大字数：折叠区仍有完整过程 */
const STEP_RESULT_MAX = 200

/** 「承诺 / 收尾句」判定的长度上限：真正的方案不会短到这个程度 */
const PROMISE_ONLY_MAX = 160

/** 纯完成态收尾：说了「做完了」，但没有任何交付内容 */
const COMPLETION_ONLY_PATTERNS: RegExp[] = [
  /^已完成作答/,
  /^已按要求完成/,
  /^已按上述(要求)?完成/,
  /^本轮(已)?完成作答/
]

/**
 * 承诺句式：把「给内容」推到下一轮，本身什么也没交付。
 *
 * <p>点选续作最容易出现：模型把方案写进思考通道，正文只留一句
 * 「点选后我会按该路径给出代码与配置」。
 */
const PROMISE_ONLY_PATTERNS: RegExp[] = [
  /^点选(后|之后)?[，,、]?我(会|将)/,
  /^我(会|将)按/,
  /^我(会|将)(为你)?(给出|整理|输出|提供)/,
  /^接下来我(会|将)/
]

/**
 * 这段正文是不是只有一句「承诺 / 收尾」，没有可交付内容。
 *
 * <p>判据收得很窄，宁可漏判也不误杀真方案：
 * <ul>
 *   <li>必须**短**（≤ {@link PROMISE_ONLY_MAX} 字）；</li>
 *   <li>完成态句子直接命中；</li>
 *   <li>承诺句式还要求**没有列出任何内容**——无换行、无冒号、无顿号，且 ≤ 60 字。
 *       所以「我会给出以下三步：建工程、配依赖、写接口」这种**真在给内容**的短答不会被误判。</li>
 * </ul>
 *
 * @param text 正文（已摘掉机器块）
 * @return true 表示这句不是交付物
 */
export function isPromiseOnlyAnswer(text: string): boolean {
  const body = (text || '').trim()
  if (!body || body.length > PROMISE_ONLY_MAX) {
    return false
  }
  if (COMPLETION_ONLY_PATTERNS.some((pattern) => pattern.test(body))) {
    return true
  }
  // 有换行 / 冒号 / 顿号：说明后面确实列了东西，这是正文，不是承诺
  if (/[\n：:、]/.test(body)) {
    return false
  }
  return body.length <= 60 && PROMISE_ONLY_PATTERNS.some((pattern) => pattern.test(body))
}

/** 思考通道里确实有方案时，给用户看的明确说明（不搬草稿，只说明去哪了）。 */
export const ANSWER_CHANNEL_SPLIT_NOTE =
  '本轮未产出正文：可交付内容被写进了思考通道，请再发一次。'

/** 正文只有一句收尾、思考里也没有方案时的明确说明。 */
export const ANSWER_MISSING_NOTE = '本轮未产出正文，请再发一次。'

/** 判定「思考通道里确实有方案」的字数下限：低于这个量只是一句推理，不该引导用户去看 */
const CHANNEL_SPLIT_THINKING_MIN = 200

/**
 * 思考通道里是否真的装着可交付内容。
 *
 * <p>只在「正文只有收尾句」时用来说明去向：推理字数过少说明模型什么都没写，
 * 这时不能告诉用户「内容在思考里」，否则是把人引到一个空框。
 *
 * @param steps 该参与者自己的过程步骤（含推理）
 * @return true 表示思考通道里有成段内容
 */
function thinkingCarriesDeliverable(steps: ProcessStep[]): boolean {
  const chars = (Array.isArray(steps) ? steps : [])
    .filter((step) => step.type === 'reasoning')
    .reduce((total, step) => total + (step.text || '').length, 0)
  return chars >= CHANNEL_SPLIT_THINKING_MIN
}

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
    // 工具过程已经做成胶囊（在内容框上方），这里不再指路「见折叠」
    return pending ? '' : `已调用工具 ${calls} 次`
  }
  // 什么都没有：用现网一致的状态句，不造「没有额外的思考记录」
  return pending ? '' : expertStatusLine(title, false)
}

/**
 * 给仍然**没有任何正文**的参与者补一句「本步结果」。
 *
 * <p>为什么必须在 {@link attachLoose} 之后单独跑一遍：
 * <ul>
 *   <li>先说结论的话，气泡会先写一句「已由「X」完成作答」，再把没人认领的正文拼在后面——
 *       用户看到的就是「完整方案被一句收尾盖住」，这正是要修掉的现象；</li>
 *   <li>先归属再补位，则真实正文永远是这块的主内容，补位句只出现在**真的一句话都没有**的块上。</li>
 * </ul>
 *
 * <p>补位口径不变：文件清单 → 最后一条工具返回（截断）→ 现网状态句；
 * 网关判定句 / 方案分流句 / 已有正文 / 只有选项的块一律不动。
 *
 * @param cards 已完成正文归属的卡片
 */
function fillResultFallbacks(cards: ParticipantCard[]): void {
  for (const card of cards) {
    if (card.kind === 'gateway' || card.roleLabel === '方案') {
      continue
    }
    /*
     * 工作流块的结果就是那张执行卡片（节点时间轴 + 产物区）：
     * 这里再补一句「已由 X 完成作答」会在卡片下面多出一条没有信息量的框。
     *
     * <p>还没收到卡片快照的占位块（工作流正在起 run）同样不补：它不是「作答方」，
     * 补一句完成态就是在编造一次已经结束的执行。
     */
    if (card.workflow || card.roleLabel === WORKFLOW_ROLE_LABEL) {
      continue
    }
    /*
     * 正文只有一句「承诺 / 收尾」：绝不拿这句话当结果，也**绝不**把思考草稿搬进结果框。
     * 仍在流式时先留白（思考框还在走，用户看得到过程）；结束后给一句明确说明，
     * 并区分「方案确实被写进了思考」与「什么都没有」，不无中生有。
     */
    if (card.answerPromised) {
      if (card.pending) {
        continue
      }
      card.answer = thinkingCarriesDeliverable(card.steps || [])
        ? ANSWER_CHANNEL_SPLIT_NOTE
        : ANSWER_MISSING_NOTE
      card.answerPromised = false
      continue
    }
    if ((card.answer || '').trim() || card.choice || (card.errors || []).length) {
      continue
    }
    card.answer = stepResultText(card.title, card.steps || [], card.files || [], card.pending)
  }
}

/**
 * 汇总没有写出正文时的明确说明。
 *
 * <p>为什么需要：后端「方案结论：X」标记可能先到（汇总真的开始了），但模型最终没有返回正文
 * （超时 / 空响应 / 降级）。这种块如果什么都不写，就是一个只有头像和名字的空壳；
 * 挂一句明确说明比留白更诚实，也不假装有结论。
 */
export const SUMMARY_EMPTY_NOTE = '汇总未产出结论：本轮汇总模型没有返回正文。'

/**
 * 把方案汇总结论落成**另一条独立记录**（不回填分流那条）。
 *
 * <p>为什么必须分开：分流是已经发生过的一步——它把问题分给了子专家，结果就是那句分流。
 * 汇总发生在子专家全部跑完之后，是新的一个动作。把结论塞回分流那条会让那一块
 * 在事后「重新进入思考」，既改了已发生的事实，也让用户以为分流还没结束。
 *
 * <p>位置：排在所有已分出的子专家块之后（本轮最后），不插回分流条与专家块之间。
 * 无真汇总（`conclusions` 为空）时一条都不产出；子专家正文 / 文件不会被复制到这里。
 * 有标记但没正文时：流式中保留记录（思考可见），结束后改成一句明确失败说明，
 * 绝不挂空结果框。
 *
 * @param cards           已按发生顺序排好的卡片
 * @param conclusions     汇总结论（按到达顺序）
 * @param conclusionSteps 按方案名分开的汇总过程步（各归各的，不共用一条列表）
 * @param streaming       本轮是否仍在流式输出；决定这条记录是否显示为「仍在汇总」
 * @param lookOf          按名称解析形象；与专家同一套兜底
 * @return 追加汇总记录后的卡片
 */
function appendConclusionRecords(
  cards: ParticipantCard[],
  conclusions: { name: string; text: string }[],
  conclusionSteps: Map<string, ProcessStep[]>,
  streaming: boolean,
  lookOf?: (name: string) => ExpertLookView
): ParticipantCard[] {
  if (!conclusions.length) {
    return cards
  }
  const out = cards.slice()
  conclusions.forEach((item) => {
    const name = (item.name || '').trim() || '方案'
    const text = (item.text || '').trim()
    // 只取这一位方案自己的汇总过程：别的方案（嵌套）的思考不能借过来
    const steps = conclusionSteps.get(name) || []
    // 没有正文也没有过程：这一条什么都没发生，不要造空壳
    if (!text && !steps.length) {
      return
    }
    /*
     * 有真汇总但模型没写出结论：
     * - 仍在流式：先留记录（思考框可见），正文等真实增量，不预挂占位结论；
     * - 已结束：写一句明确失败说明，避免留下一个只有名字的空框。
     */
    const answer = text || (streaming ? '' : SUMMARY_EMPTY_NOTE)
    out.push({
      // 同样不带下标：结论晚到时不重挂，避免重建闪动
      key: `conclusion-${name}`,
      kind: 'conclusion',
      title: name,
      roleLabel: '方案',
      // 汇总这条记录不带分流句：分流已经在上面的分流块里冻结，不在这里重复
      lines: [],
      // 汇总过程（思考默认折叠；没有过程就不出折叠条）
      steps: [...steps],
      answer,
      errors: [],
      files: [],
      // 形象与其它参与者一致：仍走 lookOf，不在这里另造兜底
      look: lookOf?.(name),
      // 还在汇总中：思考区据此默认展开、内容框按中间过程限高跟滚
      pending: streaming
    })
  })
  return out
}
