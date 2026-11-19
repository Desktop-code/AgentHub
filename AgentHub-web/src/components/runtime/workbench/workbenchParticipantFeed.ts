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
import { attachPreprocessToCard, type PreprocessPresentation, type ProcessStep } from '../trialMessages.ts'
import { parentIndexByBegin } from './beginParent.ts'
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
   * begin 身份 id。有它就说明这位参与者真的开始过，
   * 收尾不能因为正文还空着或只剩状态句就把卡滤掉。
   */
  participantId?: string
  /** begin 帧的 kind。父子与工作流只认它 */
  participantKind?: string
  /** begin 帧的 depth。没有数字就不认父 */
  participantDepth?: number
  /** 这一位自己的终态。停转和结果区展开都看它 */
  closed?: 'done' | 'failed' | 'aborted'
  /**
   * 「方案」；普通专家留空（角标由展示层按 kind 统一给出：入口「对话入口」、专家「Agent」）。
   */
  roleLabel?: string
  /** 内容框里的本步结果句（网关判定 / 方案分流），以及折叠区里的说明 */
  lines: string[]
  /** 思考区里的推理与工具；入口块装判定模型自己的推理，方案块装它自己的分流推理 */
  steps: ProcessStep[]
  /**
   * 内容框里的成文正文（专家结论 / 方案汇总）；网关与只做分发的方案为空。
   *
   * <p>只放真正写出来的正文。空终态说明、文件清单都不写进这个字段。
   */
  answer: string
  /**
   * 这一步已经结束、但没有成文正文时的说明。
   *
   * <p>画在正文框外面。不进四行正文框，也不用它或文件卡把已有正文换掉。
   * 仍有真实正文时留空。
   */
  terminalNote?: string
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
  /**
   * 入口预处理回传。只挂在网关卡上，画在判定句之前。
   *
   * <p>直播与回放都从同一块 preprocess 投影，不再在入口卡片外面另顶一块。
   */
  preprocess?: PreprocessPresentation
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
  /**
   * 按 begin 收进本方案框里的直接子卡（专家、子方案、方案内工作流）。
   *
   * <p>只在 kind=scheme 且 depth 更浅的那张卡上有值。顺序就是 begin 先后，
   * 不是按名字排出来的。页面把这些卡画在方案外框里面，不再跟方案并排。
   */
  children?: ParticipantCard[]
  /**
   * 本方案的汇总记录，画在框内专家之后。
   *
   * <p>汇总原来是顶层的 conclusion 卡。名字就是「方案结论」那条记录自己的名字，
   * 用来放回这张方案卡，不拿它给专家重算归属。
   */
  summary?: ParticipantCard
  /**
   * 整轮收口，跟入口身份走，画在全部方案卡之后。
   *
   * <p>对不上任何方案名的「方案结论」就是这一条。不收进最后一个车间框。
   */
  roundDeliverable?: boolean
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
   * 恒为空。未知 `###` 标题不再补参与者卡，留在当前正文。
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
 * 收尾时这张卡是不是该丢掉的状态空壳。
 *
 * <p>已有 begin 身份的参与者不是空壳：直播里块已经出现，收尾不能因为正文还没写上
 * 或只剩「已由「X」完成作答」就把人滤掉。没有身份、只剩兜底状态句的卡仍然丢掉，
 * 避免断点续作在没有产出时凭当前线路名先弹一块。
 *
 * @param card 参与者卡片
 * @return true 表示收尾不该画出来
 */
export function isFinishedStatusShell(card: ParticipantCard): boolean {
  if ((card.participantId || '').trim()) {
    return false
  }
  if (card.kind !== 'actor' || card.roleLabel === '方案') {
    return false
  }
  // 工作流块的内容是执行卡片本身：即使没有正文 / 文件，它也不是空壳
  if (card.workflow) {
    return false
  }
  if (card.lines.length || card.steps.length || card.errors.length || card.files.length) {
    return false
  }
  if (card.choice && card.choice.options.length) {
    return false
  }
  const answer = (card.answer || '').trim()
  if (!answer) {
    return true
  }
  return answer === expertStatusLine(card.title, true) || answer === expertStatusLine(card.title, false)
}

/**
 * 投影本轮消息流分块。
 *
 * <p>网关、方案、专家是同一套聊天块。方案卡按 begin 先后排在入口后面。
 * 专家不再跟方案并排，而是按 begin 的 kind / depth 收进所属方案的 children。
 * 方案自己的汇总放在 summary，画在框内专家之后。不再把「已把问题分给…」当方案正文。
 *
 * @param input 时间线入参，另加原始块（用来把正文挂回参与者）
 * @return 消息流卡片；时间线为空时为空数组
 */
export function buildParticipantFeed(input: ThinkTimelineInput & { blocks: AssistantBlock[] }): ParticipantCard[] {
  // 历史会话 / 刷新快照可能带半截事件：投影失败只退化成「逐块渲染」，绝不让异常冒到渲染树
  try {
    // 空块（历史快照里的 null）直接跳过，不让投影整轮失败
    const blocks = (Array.isArray(input.blocks) ? input.blocks : []).filter(
      (block) => !!block && typeof block === 'object'
    )
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
      // 判定身份还没到，但抽字已经开始：先给入口一张只有预处理的卡，不把识别顶在网关外面
      return withEntryPreprocess([], blocks, input.entryName, input.lookOf)
    }
    const steps = withoutSchemeShells(
      buildThinkTimeline({
        ...input,
        // 每张工作流卡只挂对得上自己 begin 的快照，不再共用最后一张
        workflowSnapshots: workflowSnapshotsOf(blocks),
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
      return withEntryPreprocess([], blocks, input.entryName, input.lookOf)
    }
    // 「方案块 = 分流空壳」只在它下面真挂子专家时成立；没有子专家说明它自己就是作答方
    const schemeWithChildren = schemesWithChildren(steps)
    const answerableSchemes = new Set(
      steps
        .filter((step) => step.roleLabel === '方案' && !schemeWithChildren.has(step.title))
        .map((step) => step.title)
    )
    // 本轮真实出现过的参与者名：把后端 CONCAT 的「### 专家名」分段发回各自气泡要用
    const knownActors = new Set(steps.filter((step) => step.kind === 'expert').map((step) => step.title))
    const collected = collectAnswers(blocks, answerableSchemes, knownActors)
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
    // 预处理先挂到入口卡上，再决定要不要留这张卡：只有预处理、还没有判定句时也要留
    const withPreprocess = withEntryPreprocess(cards, blocks, input.entryName, input.lookOf)
    const withGateway = withPreprocess.filter(
      (card) =>
        (card.kind !== 'gateway' ||
          card.lines.length > 0 ||
          card.steps.length > 0 ||
          !!card.preprocess) &&
        (input.streaming || !(card.roleLabel === WORKFLOW_ROLE_LABEL && !card.workflow))
    )
    // 方案是否真分了子专家：只看已经出现的子专家泳道，不从拼接稿标题补人
    const dispatchSchemes = new Set(schemeWithChildren)
    // 分了子专家的方案：仍是自己的聊天块，只是内容框里的话从「正文」换成「分给了谁」
    const withSchemes = schemeDispatchBlocks(withGateway, dispatchSchemes)
    /*
     * 汇总另起一条：排在所有子专家之后，不回填分流那条。
     * 分流块在分发完成时就已冻结，后面专家执行、方案汇总都不再改动它。
     */
    const withConclusions = appendConclusionRecords(
      withSchemes,
      collected.conclusions,
      collected.conclusionSteps,
      input.streaming,
      input.lookOf,
      collected.tailFromConcat
    )
    // 自己在作答的方案（入口点名、没有子专家）必须留着，否则网关块之后整段空白
    const visible = dropEmptySchemeShells(withConclusions)
    // 先归属：没人认领的真实正文落到最后一位参与者气泡（盖掉任何兜底句）
    attachLoose(visible, collected)
    // 再补位：确实一句话都没有的块才写「本步结果」，真实正文永远不会被收尾句顶掉
    fillResultFallbacks(visible)
    markFinal(visible, input.streaming === true)
    // 归属在正文、终态都落定之后做：只改父子位置，不重算答案
    return nestByBegin(visible)
  } catch (error) {
    // 只提示排障，不把内部键/正文写进日志；返回空数组让调用方回退到逐块渲染
    console.warn('[工作台消息流] 本轮分块投影失败，已回退到逐块渲染', error)
    return []
  }
}

/**
 * 把入口预处理挂进网关卡，画在判定句之前。
 *
 * <p>已经有网关卡就挂上去，不另起一张。还没有任何参与者时（抽字先于意图），
 * 先出一张只有预处理的入口卡，名字用入口配置名。旁边已经有专家卡却没有网关时不补空壳，
 * 避免断点续作被认成又走了一遍入口。
 *
 * @param cards     已经投影出的参与者；可以是空
 * @param blocks    本轮原始块，从中取 preprocess
 * @param entryName 入口展示名
 * @param lookOf    入口头像，与正式网关卡同一套
 * @return 挂好预处理的卡片；没有预处理块时原样返回
 */
export function withEntryPreprocess(
  cards: ParticipantCard[],
  blocks: AssistantBlock[],
  entryName?: string,
  lookOf?: (name: string) => ExpertLookView
): ParticipantCard[] {
  const block = (Array.isArray(blocks) ? blocks : []).find((item) => item?.kind === 'preprocess' && item.preprocess)
  const folded = attachPreprocessToCard(cards, block?.preprocess, 'gateway')
  if (!folded.shown) {
    return cards
  }
  if (folded.cards !== cards) {
    return folded.cards
  }
  const shown = folded.shown
  // 旁边已经有别的参与者时不凭空补入口，只在「还没人开始」时用预处理把入口卡支起来
  if (cards.length) {
    return cards
  }
  const title = (entryName || '').trim() || '智能网关'
  return [
    {
      key: 'entry-preprocess',
      kind: 'gateway',
      title,
      lines: [],
      steps: [],
      answer: '',
      errors: [],
      files: [],
      pending: shown.running,
      preprocess: shown,
      look: lookOf?.(title)
    }
  ]
}

/**
 * 本轮全部工作流快照，带着 begin 身份。投影按 id 或名字对到对应的卡，不取最后一张通贴。
 *
 * @param blocks 本轮原始块
 * @return 快照列表；没有工作流块时为空
 */
function workflowSnapshotsOf(blocks: AssistantBlock[]): { participantId?: string; view: TrialWorkflowView }[] {
  const list: { participantId?: string; view: TrialWorkflowView }[] = []
  for (const block of blocks) {
    if (block.kind === 'workflow' && block.workflow) {
      list.push({ participantId: block.participantId, view: block.workflow })
    }
  }
  return list
}

/**
 * 工作台不把 assistant 块画成整屏答案。
 *
 * <p>还没有参与者卡时，调试皮肤仍可以显示原文；工作台只等参与者卡把正文收进去。
 *
 * @param isWorkbench 当前是工作台
 * @param hasActor    本轮是否已经有参与者卡
 * @return true 表示可以画那条游离的 assistant
 */
export function showLooseAssistant(isWorkbench: boolean, hasActor: boolean): boolean {
  if (isWorkbench) {
    return false
  }
  return !hasActor
}

/**
 * 按 begin 的 kind / depth / 先后把专家收进所属方案。
 *
 * <p>父的认定与时间线 {@code lanesInBeginGroups} 是同一条 {@code parentIndexByBegin}。
 * begin id 里已经写了父方案键时，按这张卡自己的身份进对应方案；
 * 并行直播里方案 begin 会先到齐，不能再取「当时最后一个更浅方案」。
 * 不按名字排序，也不另写一套归属。
 * 方案框里已经有子卡时，去掉「已把问题分给…」和「正在分发…」——
 * 这两句是把子卡名单再写一遍当正文。
 * 汇总记录没有 depth，按它自己的方案名放回对应方案的 summary，排在子卡之后。
 * 对不上任何方案名的那一条是整轮收口，留在根上，跟在全部方案卡后面。
 *
 * @param cards 已经排好正文、终态和最后一条标记的平铺卡片
 * @return 根上只留入口、方案和没有父的块；专家在方案的 children 里
 */
function nestByBegin(cards: ParticipantCard[]): ParticipantCard[] {
  const conclusions = cards.filter((card) => card.kind === 'conclusion')
  const body = cards.filter((card) => card.kind !== 'conclusion')
  const nodes = body.map((card, index) => ({
    card,
    index,
    participantId: card.participantId,
    depth: typeof card.participantDepth === 'number' ? card.participantDepth : undefined,
    kind: (card.participantKind || '').trim(),
    parent: undefined as number | undefined,
    children: [] as ParticipantCard[]
  }))
  // 父在投影时一次认完。身份里有父键就用父键；没有才退回最近的更浅方案
  for (let i = 0; i < nodes.length; i += 1) {
    nodes[i].parent = parentIndexByBegin(nodes, i)
  }
  for (const node of nodes) {
    if (node.parent !== undefined) {
      nodes[node.parent].children.push(node.card)
    }
  }
  for (const node of nodes) {
    if (node.kind !== 'scheme' || !node.children.length) {
      continue
    }
    node.card.children = node.children
    node.card.lines = node.card.lines.filter(
      (line) => line !== '正在分发…' && !(line || '').includes('已把问题分给')
    )
  }
  const roots = nodes.filter((node) => node.parent === undefined).map((node) => node.card)
  const parked = new Set<ParticipantCard>()
  for (const conclusion of conclusions) {
    const host = findSchemeHost(roots, (conclusion.title || '').trim())
    if (!host) {
      continue
    }
    host.summary = conclusion
    parked.add(conclusion)
  }
  for (const conclusion of conclusions) {
    if (parked.has(conclusion)) {
      continue
    }
    // 对不上方案名的结论是整轮收口：留在根上，跟在全部车间之后，不借最后一个车间的名字
    conclusion.roundDeliverable = true
    conclusion.roleLabel = '最终产物'
    roots.push(conclusion)
  }
  attachRoundFiles(roots)
  return roots
}

/**
 * 把本轮已经归到各卡上的产物文件再列到整轮收口上。
 *
 * <p>文件事件仍归写出它的那位专家，专家卡上的下载行保留。
 * 收口卡再列一份，是为了整轮结束时不用进每个车间框里翻文件名。
 * 同一文件名加同一下载地址只列一次。
 *
 * @param roots 已经嵌套好的根卡
 */
function attachRoundFiles(roots: ParticipantCard[]): void {
  const rounds = roots.filter((card) => card.kind === 'conclusion' && card.roundDeliverable)
  if (!rounds.length) {
    return
  }
  const files: TrialFileView[] = []
  const seen = new Set<string>()
  for (const card of visitParticipantCards(roots)) {
    if (card.roundDeliverable) {
      continue
    }
    for (const file of card.files || []) {
      const key = `${file.fileName || ''}|${file.downloadUrl || ''}`
      if (!file.fileName || seen.has(key)) {
        continue
      }
      seen.add(key)
      files.push(file)
    }
  }
  if (!files.length) {
    return
  }
  for (const round of rounds) {
    round.files = files
  }
}

/**
 * 汇总记录该回到哪张方案卡。
 *
 * <p>只认 begin kind=scheme 且标题与「方案结论」同名的卡。
 * 先看自己再看框内子方案，外层同名不会被内层抢走。
 *
 * @param cards 已经嵌套好的根卡
 * @param title 汇总记录上的方案名
 * @return 要承接这条汇总的方案卡；对不上时为空
 */
function findSchemeHost(cards: readonly ParticipantCard[], title: string): ParticipantCard | undefined {
  if (!title) {
    return undefined
  }
  for (const card of cards) {
    if ((card.participantKind || '').trim() === 'scheme' && card.kind !== 'conclusion'
      && (card.title || '').trim() === title) {
      return card
    }
    if (card.children?.length) {
      const nested = findSchemeHost(card.children, title)
      if (nested) {
        return nested
      }
    }
  }
  return undefined
}

/**
 * 深度优先列出卡片：自己、框内子卡、汇总。
 *
 * <p>页面不靠这份平铺来画。工作流展开和测试要顺着框里的卡往下找时用它。
 *
 * @param cards 根卡或某一层的子卡
 * @return 深度优先的卡片
 */
export function visitParticipantCards(cards: readonly ParticipantCard[]): ParticipantCard[] {
  const out: ParticipantCard[] = []
  for (const card of cards) {
    out.push(card)
    if (card.children?.length) {
      out.push(...visitParticipantCards(card.children))
    }
    if (card.summary) {
      out.push(card.summary)
    }
  }
  return out
}

/**
 * 标记「整轮最后一条真正结论」。直播过程中不打这个标记，内容框一律限高。
 * 整轮结束后才认汇总，或最后一位有正文的参与者。
 *
 * <p>在嵌套之前打标：汇总还在平铺数组末尾，标完再收进方案框，标记跟着那张卡走。
 *
 * @param cards     已排好序、尚未嵌套的卡片
 * @param streaming 本轮是否仍在流式输出
 */
function markFinal(cards: ParticipantCard[], streaming = false): void {
  for (const card of cards) {
    card.isFinal = false
  }
  // 直播中谁都不放开高度。先写完的那一张不是终局，等整轮结束再认最后一条
  if (streaming) {
    return
  }
  // 汇总记录优先：它在数组末尾。没有成文时框外有说明，这里仍把它当作这一轮的收尾卡
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
  if (expert) {
    expert.isFinal = true
  }
}

/**
 * 展示用：去掉重复的方案空壳与匿名执行块。
 *
 * <p>同一方案名被推了多次「路由到」时，归组只把子专家挂到最后一块，前面几块只剩
 * 「正在由／已由「方案名」作答」。消息流和补充轨迹都不该把这些空壳画出来。
 *
 * <p>另一个要丢的是**匿名执行块**：没有 begin 的过程如果漏进专家列表，标题会是「执行」。
 * 它不是参与者。入口思考应留在入口块上，汇总过程只在「方案结论：」那一条记录里。
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
      /*
       * 子专家还没到：已有 begin 身份时用进行态「正在分发…」撑住内容框，
       * 没有身份的历史数据仍留空（由 dropEmptySchemeShells 删掉），不凭空白造一张卡。
       */
      lines: step.pending && (step.participantId || '').trim() ? ['正在分发…'] : []
    })
  }
  return out
}

/**
 * 是否是时间线兜底出来的匿名执行块。
 *
 * <p>没有 begin 的过程不会按名字开卡。若仍漏进时间线，标题是「执行」，不能当成一位专家画出来。
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
 * 哪些方案块下面真的挂了更深一层的子块。
 *
 * <p>只看 begin 留下的 depth：方案之后、depth 更大、还没回到同层或更浅，就算分下去了。
 * 没有 depth 的旧步骤不当子块，也不靠候选池猜。
 *
 * @param steps 时间线步骤（已去掉重复空壳）
 * @return 有子块的方案名
 */
function schemesWithChildren(steps: ThinkTimelineStep[]): Set<string> {
  const titles = new Set<string>()
  for (let index = 0; index < steps.length; index += 1) {
    const step = steps[index]
    if (!step || step.roleLabel !== '方案' || typeof step.participantDepth !== 'number') {
      continue
    }
    if (hasDirectChild(steps, index, step.participantDepth)) {
      titles.add(step.title)
    }
  }
  return titles
}

/**
 * 方案后面是否紧跟着更深的子块（遇到同层或更浅就停）。
 *
 * @param steps 时间线
 * @param index 方案下标
 * @param depth 方案 depth
 * @return true 表示至少有一个直接或间接子块
 */
function hasDirectChild(steps: ThinkTimelineStep[], index: number, depth: number): boolean {
  for (let cursor = index + 1; cursor < steps.length; cursor += 1) {
    const next = steps[cursor]
    if (!next || typeof next.participantDepth !== 'number' || next.participantDepth <= depth) {
      return false
    }
    return true
  }
  return false
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
 * @return 收成聊天块后的卡片
 */
function schemeDispatchBlocks(
  cards: ParticipantCard[],
  dispatchSchemes: Set<string>
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
      for (const line of dispatchLinesOf(card, cards, index)) {
        if (!existing.lines.includes(line)) {
          existing.lines.push(line)
        }
      }
      // 真实分流句已到：撤掉进行态占位，方案卡从「正在分发」切成已发生的事实
      if (existing.lines.some((line) => line !== '正在分发…')) {
        existing.lines = existing.lines.filter((line) => line !== '正在分发…')
        existing.pending = false
      }
      continue
    }
    const lines = dispatchLinesOf(card, cards, index)
    /*
     * 子专家名还没投影出来时，只要这张方案卡已有 begin 身份就先留着：
     * 用户已经看见它开始分发了，等子专家卡出现再补齐分流句，而不是先删卡再补卡。
     * 没有身份的旧数据仍按「拿不到名字不画块」处理，避免凭空造空壳。
     */
    if (!lines.length && !(card.participantId || '').trim()) {
      continue
    }
    const pendingLines = lines.length ? lines : ['正在分发…']
    const block: ParticipantCard = {
      ...card,
      /*
       * key 沿用分流前那张卡（`actor-方案名`）：分流句到达时只换内容框里的话，
       * 不能让 key 跟着变——一变 Vue 就卸载重建，方案的思考框会闪一下重新展开。
       */
      kind: 'actor',
      // 分流句就是这一块内容框里的话：放 lines，不占用 answer 正文位；
      // 方案自己的思考（分流判定模型的推理）留在 steps 里，子专家的思考各自在他们块里
      lines: pendingLines,
      steps: Array.isArray(card.steps) ? card.steps : [],
      answer: '',
      errors: [],
      files: [],
      look: card.look,
      // 还没拿到子专家名的 pending 壳保持进行态；拿到分流句就按已发生的分流结果处理
      pending: !lines.length
    }
    blocks.set(card.title, block)
    out.push(block)
  }
  return out
}

/**
 * 方案分流句的人话：优先用后端已落库的分流句，没有就按真实子专家名拼一句。
 *
 * @param card  方案卡
 * @param cards 全部卡片
 * @param index 该卡位置
 * @return 一行或多行分流说明；没有任何子专家时为空
 */
function dispatchLinesOf(card: ParticipantCard, cards: ParticipantCard[], index: number): string[] {
  const recorded = (Array.isArray(card.lines) ? card.lines : []).filter((line) =>
    (line || '').includes('已把问题分给')
  )
  if (recorded.length) {
    return recorded
  }
  const children = childNamesAfter(cards, index)
  if (!children.length) {
    return []
  }
  // 与后端 schemeChildrenLineOf 同一句式，只是数据来自本轮真实子块
  return [`已把问题分给${children.map((name) => `「${name}」`).join('')}`]
}

/**
 * 方案后面 depth 正好深一层的子块名字。
 *
 * <p>内层方案算进外层的分流句；它自己的子块 depth 更深，不重复算进外层。
 * 没有 depth 就不认子块。
 *
 * @param cards 全部卡片
 * @param index 方案卡位置
 * @return 直接子块名（去重、保持 begin 顺序）
 */
function childNamesAfter(cards: ParticipantCard[], index: number): string[] {
  const self = cards[index]
  const depth = self?.participantDepth
  if (typeof depth !== 'number') {
    return []
  }
  const names: string[] = []
  for (let cursor = index + 1; cursor < cards.length; cursor += 1) {
    const next = cards[cursor]
    if (next.kind === 'conclusion') {
      break
    }
    if (typeof next.participantDepth !== 'number' || next.participantDepth <= depth) {
      break
    }
    if (next.participantDepth !== depth + 1 || next.kind === 'gateway') {
      continue
    }
    const title = (next.title || '').trim()
    if (title && title !== self.title && !names.includes(title)) {
      names.push(title)
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
    /*
     * 已有 begin 身份就说明这张方案卡真的开始了（后端推过 participant 帧）。
     * 与 {@link isFinishedStatusShell} 同一口径：身份本身就是「不是空壳」的证据，
     * 不能因为子专家名还没投影出来就把整块删掉——那是「直播先消失、结束再补」的来源。
     */
    if ((card.participantId || '').trim()) {
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
  /*
   * 兜底承接方永远不能是工作流卡：工作流块的内容是它自己那张执行卡片，
   * 把无主文件 / 正文挂上去，用户就会看到「文字 → 文件卡 → 工作流跑完又变回文字」。
   * 没有可承接的专家时宁可留在 loose，等真实作者卡出现再挂，也不先塞给工作流。
   */
  const host =
    [...cards].reverse().find((card) => card.kind === 'actor' && card.roleLabel !== '方案'
      && !card.workflow && card.roleLabel !== WORKFLOW_ROLE_LABEL) ||
    // 历史数据可能真的没有专家卡：最后才退到方案块，但依然不碰工作流卡
    [...cards].reverse().find((card) => card.kind === 'actor'
      && !card.workflow && card.roleLabel !== WORKFLOW_ROLE_LABEL)
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
  /*
   * 「正在分发…」是方案卡唯一的进行态人话，必须留在内容框里：
   * 以前在这里把它滤掉，`withoutSchemeShells` 又刚把它补上，等于「补了又过滤」，
   * 方案块在子专家到达前就剩一张空壳，最后被 `dropEmptySchemeShells` 整块删掉。
   */
  if (!text || text === '没有额外的思考记录') {
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
 * 标题是否像报告章节（「（一）…」「一、…」「第 1 章」），而不是 CONCAT 作者名。
 *
 * <p>专家报告正文常带这类 ATX 小标题；若按「凡是 ### 就切段」会把一篇报告拆成多张假专家卡。
 * 后端 CONCAT 作者行是参与者展示名，不会长成章节枚举。
 *
 * @param name 已去掉尾部冒号的标题文本
 * @return true 表示应按正文保留，不能当作者分段
 */
export function looksLikeChapterHeading(name: string): boolean {
  const title = (name || '').trim()
  if (!title) {
    return false
  }
  // （一）背景 / (1) 背景 / 【一】…
  if (/^[（(【\[]\s*[一二三四五六七八九十百千零〇两\d]+\s*[）)\]】]/.test(title)) {
    return true
  }
  // 一、制度依据 / 十二、落实建议
  if (/^[一二三四五六七八九十百千零〇两]+、/.test(title)) {
    return true
  }
  // 1. 背景 / 2、建议 / 3．要点
  if (/^\d+\s*[.．、]/.test(title)) {
    return true
  }
  // 第一章 / 第 2 节 / 第三条
  if (/^第\s*[一二三四五六七八九十百千零〇两\d]+\s*[章节部分条款篇]/.test(title)) {
    return true
  }
  return false
}

/**
 * 后端 CONCAT 终答的分段：`### {专家名}` 起一段。
 *
 * <p>并行 / 多子轮次落库时只有一条 ASSISTANT 事件，正文是「### 甲 … ### 乙 …」的拼接稿。
 * 只把它挂给最后一位，就会出现「整段正文堆在一个人身上、别人什么都没有」。
 * 这里按标题切回各自的作者。
 *
 * <p>切段纪律：只有标题能对上本轮已经出现的参与者名，才新开一段。
 * 章节（（一）/一、/第 N 章）和文稿占位（【待确认：…】）留在当前作者正文里，
 * 不再因为「像个标题」就补一张专家卡。对不上名单的标题同样留在正文。
 *
 * @param text        助手正文
 * @param knownActors 本轮已出现的参与者展示名；空则不按标题切作者
 * @return 分段；没有命中参与者的标题行时为空
 */
export function splitNamedSections(
  text: string,
  knownActors?: Set<string>
): { name: string; text: string }[] {
  const lines = (text || '').split('\n')
  const sections: { name: string; text: string }[] = []
  let current: { name: string; text: string[] } | undefined
  for (const line of lines) {
    // ATX 标题才可能是分段标记；名字去掉尾部「：」与空格，便于对上参与者展示名
    const heading = /^\s{0,3}#{1,6}\s+(.+?)\s*$/.exec(line)
    if (heading) {
      const name = (heading[1] || '').replace(/[：:]\s*$/, '').trim()
      const knownAuthor = !!name && !!knownActors && knownActors.has(name) && !looksLikeChapterHeading(name)
      // 不是已落库/已出现的参与者：整行留在当前正文，不新开作者
      if (!knownAuthor) {
        if (current) {
          current.text.push(line)
        }
        continue
      }
      if (current) {
        sections.push({ name: current.name, text: current.text.join('\n').trim() })
      }
      current = { name, text: [] }
      continue
    }
    if (current) {
      current.text.push(line)
    }
  }
  if (current) {
    sections.push({ name: current.name, text: current.text.join('\n').trim() })
  }
  // 文首若有散段（首个作者标题之前的内容），不在这里处理：由调用方按原逻辑归给当前参与者
  return sections.filter((section) => !!section.name)
}

/**
 * 一段多标题正文是否应按 CONCAT 作者稿切开。
 *
 * <p>至少两段标题都对上本轮参与者，才是真 CONCAT。
 * 对不上的标题（章节、【待确认】、没出现过的名字）不参与切卡。
 *
 * @param claimed 对得上 knownActors 的分段
 * @return true 表示按作者切开
 */
function isConcatAuthorDraft(claimed: { name: string; text: string }[]): boolean {
  return claimed.length >= 2
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
 * @param looseAnswers 认不出作者的分段（就地追加，不另造卡）
 */
function distributeNamedSections(
  sections: { name: string; text: string }[],
  knownActors: Set<string>,
  byName: Map<string, string>,
  looseAnswers: string[]
): void {
  for (const section of sections) {
    if (!section.text) {
      continue
    }
    if (knownActors.has(section.name) && !looksLikeChapterHeading(section.name)) {
      byName.set(section.name, (byName.get(section.name) || '') + section.text)
      continue
    }
    // 对不上参与者的标题留在正文，不另造卡
    looseAnswers.push(`### ${section.name}\n${section.text}`)
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
 * @param answerableSchemes 自己在作答的方案名（没有子专家）；这些名字可以接正文
 * @param knownActors   本轮真实出现过的参与者名；分段标题据此认领
 * @return 专家结论与方案汇总结论
 */
function collectAnswers(
  blocks: AssistantBlock[],
  answerableSchemes: Set<string> = new Set(),
  knownActors: Set<string> = new Set()
): CollectedAnswers {
  // 方案 / 工作流只认 begin 写下的 kind，不读候选池
  const kindOf = participantKindOf(blocks)
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
    const who = expertOwner(pinned || owner, lastExpert, kindOf, answerableSchemes)
    /*
     * 工作流文件必须自带身份。
     *
     * <p>没有身份的文件不能靠「最近一位路由对象」落到工作流卡上：工作流一步接一步跑，
     * 后到的专家文件会被先挂到工作流再改挂，用户看到的就是「文件在卡之间跳」。
     * 身份缺失时留作 loose，宁可落到真正的专家块，也不往工作流卡兜底。
     */
    if (!pinned && kindOf(who) === 'workflow') {
      looseFiles.push(file)
      return
    }
    if (!who) {
      looseFiles.push(file)
      return
    }
    const list = filesByName.get(who) || []
    list.push(file)
    filesByName.set(who, list)
  }
  const rememberError = (text: string, pinned = '') => {
    const who = expertOwner(pinned || owner, lastExpert, kindOf, answerableSchemes)
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
      // 只把正文交给「本块自带身份」或「时间线里已经出现的参与者」。对不上的路由行不改当前作者，也不新建卡。
      const name = parseRouteParticipant(text)?.name || parseRouteText(text).name
      const named = (block.participantName || '').trim()
      const identified = !!(block.participantId || named) || (!!name && knownActors.has(name))
      if (!identified) {
        continue
      }
      owner = name || named || ''
      if (owner && kindOf(owner) !== 'scheme') {
        lastExpert = owner
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
      rememberFile(block.file, pinOf(block, kindOf))
      continue
    }
    /*
     * 结构化选项：归属规则与文件 / 报错完全一致（身份帧优先，其次当前路由）。
     * 它不属于「正文」，因此不参与拼接稿分段的任何判断。
     */
    if (block.kind === 'choice' && block.choice && block.choice.options.length) {
      const who = expertOwner(pinOf(block, kindOf) || owner, lastExpert, kindOf, answerableSchemes)
      if (who) {
        choiceByName.set(who, block.choice)
      }
      continue
    }
    if (block.kind === 'error') {
      const text = sanitizeThinkText(block.text)
      if (text) {
        /*
         * 终态错误自带身份时必须按身份归位：方案外壳的失败如果走「最近一位专家」兜底，
         * 就会被甩到它最后一位子专家卡上（用户看到错误的红字挂错了人）。
         * 历史回放的 error 块没有身份，仍按原兜底。
         */
        const pinnedName = (block.participantName || '').trim()
        if (pinnedName) {
          const list = errorsByName.get(pinnedName) || []
          list.push(text)
          errorsByName.set(pinnedName, list)
        } else {
          rememberError(text)
        }
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
      const summarySections = splitNamedSections(piece, knownActors)
      const summaryClaimed = summarySections.filter((section) => knownActors.has(section.name))
      /*
       * 标题对上本轮参与者就不是汇总正文：发回对应专家，不写进结论卡。
       * 对不上的标题（章节、【待确认】、没出现过的名字）留在该专家正文里。
       */
      if (summaryClaimed.length >= 1) {
        distributeNamedSections(summarySections, knownActors, byName, looseAnswers)
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
    const pinnedOwner = pinOf(block, kindOf)
    if (pinnedOwner) {
      lastExpert = pinnedOwner
    }
    const speaker = pinnedOwner || owner
    // 后端 CONCAT 拼接稿：按「### 专家名」分段发回各自的作者，不再整段堆给最后一位。
    // 章节标题（（一）/一、…）已在 splitNamedSections 留在正文内，不再误当作者。
    const sections = splitNamedSections(piece, knownActors)
    const claimed = sections.filter((section) => knownActors.has(section.name) || section.name === speaker)
    if (isConcatAuthorDraft(claimed)) {
      distributeNamedSections(sections, knownActors, byName, looseAnswers)
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
    const ownerTakesAnswer = !!speaker && (kindOf(speaker) !== 'scheme' || answerableSchemes.has(speaker))
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
 * 从 begin 块上收集「展示名 → kind」。先到的为准。
 *
 * @param blocks 本轮块
 * @return 按名字取 kind；没有记录时为 undefined
 */
function participantKindOf(blocks: AssistantBlock[]): (name: string) => string | undefined {
  const map = new Map<string, string>()
  for (const block of blocks) {
    const name = (block.participantName || '').trim()
    const kind = (block.participantKind || '').trim()
    if (name && kind && !map.has(name)) {
      map.set(name, kind)
    }
  }
  return (name: string) => map.get((name || '').trim())
}

/**
 * 块身份 → 该块内容应归属的参与者展示名。
 *
 * <p>只认身份帧写下的名字；方案不接正文 / 文件（否则会篡改分流结论），
 * 仍交给既有兜底（最后一位专家）。
 *
 * @param block  本轮块
 * @param kindOf 展示名 → begin kind
 * @return 参与者名；没有身份或目标是方案时为空串
 */
function pinOf(block: AssistantBlock, kindOf: (name: string) => string | undefined): string {
  const name = (block.participantName || '').trim()
  if (!name) {
    return ''
  }
  return kindOf(name) === 'scheme' ? '' : name
}

/**
 * 文件和报错只挂到专家，不挂到「分了子专家」的方案名上。
 *
 * @param owner             当前路由名
 * @param lastExpert        最近一位非方案参与者
 * @param kindOf            展示名 → begin kind
 * @param answerableSchemes 自己在作答的方案名
 * @return 应接收的专家名；还没有专家时为空
 */
function expertOwner(
  owner: string,
  lastExpert: string,
  kindOf: (name: string) => string | undefined,
  answerableSchemes: Set<string> = new Set()
): string {
  if (owner && (kindOf(owner) !== 'scheme' || answerableSchemes.has(owner))) {
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
  // 停转只看这一位的终态（step.pending 已经是「整轮还在流且这一位没 closed」）
  const pending = step.kind !== 'gateway' && step.pending
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
    participantId: step.participantId,
    participantKind: step.participantKind,
    participantDepth: step.participantDepth,
    closed: step.closed,
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
 * 给仍然**没有任何正文**的参与者补一句终态说明。
 *
 * <p>说明只写进 {@link ParticipantCard.terminalNote}，不写进 {@code answer}。
 * 正文框只画真正的正文；文件在框下一行单独画。用「已由谁完成作答」或「已生成 N 个文件」
 * 填进正文，用户看到的就是正文被一句话或一张文件卡换掉。
 *
 * <p>为什么必须在 {@link attachLoose} 之后单独跑一遍：先写说明再拼没人认领的正文，
 * 真实内容会被说明盖住。先归属再补位，真实正文永远留在 {@code answer}。
 *
 * <p>网关判定句 / 方案分流句 / 已有正文 / 只有选项 / 工作流执行卡片一律不动。
 * 有文件时不补说明：文件行自己就在，不再造一句清单去占正文的位置。
 * 没有助手正文时只在框外留一句终态说明。工具返回留在胶囊里，不填进结果框。
 *
 * @param cards 已完成正文归属的卡片
 */
function fillResultFallbacks(cards: ParticipantCard[]): void {
  for (const card of cards) {
    if (card.kind === 'gateway' || card.roleLabel === '方案') {
      continue
    }
    /*
     * 工作流块的结果就是执行卡片里的正文和文件行。
     * 这里再补一句终态说明，会在卡片下面多出一条没有正文的框。
     */
    if (card.workflow || card.roleLabel === WORKFLOW_ROLE_LABEL) {
      continue
    }
    /*
     * 正文只有一句「承诺 / 收尾」：不拿这句话当正文，也不把思考草稿搬进结果框。
     * 仍在流式时先留白；结束后把说明放到框外。
     */
    if (card.answerPromised) {
      if (!card.pending) {
        card.terminalNote = thinkingCarriesDeliverable(card.steps || [])
          ? ANSWER_CHANNEL_SPLIT_NOTE
          : ANSWER_MISSING_NOTE
      }
      card.answer = ''
      card.answerPromised = false
      continue
    }
    if ((card.answer || '').trim() || card.choice || (card.errors || []).length) {
      continue
    }
    // 文件单独成行。没有终稿时不拿工具返回顶上，也不造一句清单占结果框
    if ((card.files || []).length) {
      continue
    }
    const steps = card.steps || []
    if (card.pending) {
      continue
    }
    const calls = steps.filter((step) => step.type === 'tool_call').length
    // 工具过程已经是胶囊。没有正文时只在框外留一句，不装进正文框
    card.terminalNote = calls > 0 ? `已调用工具 ${calls} 次` : STEP_EMPTY_NOTE
  }
}

/**
 * 汇总记录自己没有助手正文，也不是把子专家分段稿发回去之后留下的空位。
 *
 * <p>只说明这一条记录上没有正文。子专家正文和文件不会被复制到汇总卡上。
 */
export const SUMMARY_EMPTY_NOTE = '本轮汇总没有返回正文。'

/**
 * 标记之后的助手文本被认出是「### 参与者」分段稿，已经发回各卡。
 *
 * <p>这时汇总卡上没有单独成文，不能再说「模型没有返回正文」——正文在上面的卡片里。
 */
export const SUMMARY_REDISTRIBUTED_NOTE = '汇总没有单独成文，正文在上面各位的卡片里。'

/** 专家这一步结束了，投影里没有成文正文、也没有文件。不写成「已由谁完成作答」 */
export const STEP_EMPTY_NOTE = '本步没有返回正文。'

/**
 * 把方案汇总结论落成**另一条独立记录**（不回填分流那条）。
 *
 * <p>为什么必须分开：分流是已经发生过的一步——它把问题分给了子专家，结果就是那句分流。
 * 汇总发生在子专家全部跑完之后，是新的一个动作。把结论塞回分流那条会让那一块
 * 在事后「重新进入思考」，既改了已发生的事实，也让用户以为分流还没结束。
 *
 * <p>位置：排在所有已分出的子专家块之后（本轮最后），不插回分流条与专家块之间。
 * 无真汇总（`conclusions` 为空）时一条都不产出；子专家正文 / 文件不会被复制到这里。
 * 有标记但没正文时：流式中保留记录（思考可见），结束后把说明放在正文框外。
 * 分段稿已经发回各卡时，说明写成「正文在上面」，不再说模型没返回正文。
 * 文件不挂到这条记录上。
 *
 * @param cards           已按发生顺序排好的卡片
 * @param conclusions     汇总结论（按到达顺序）
 * @param conclusionSteps 按方案名分开的汇总过程步（各归各的，不共用一条列表）
 * @param streaming       本轮是否仍在流式输出；决定这条记录是否显示为「仍在汇总」
 * @param lookOf          按名称解析形象；与专家同一套兜底
 * @param tailFromConcat  标记之后的正文是否已按「### 参与者」发回各卡
 * @return 追加汇总记录后的卡片
 */
function appendConclusionRecords(
  cards: ParticipantCard[],
  conclusions: { name: string; text: string }[],
  conclusionSteps: Map<string, ProcessStep[]>,
  streaming: boolean,
  lookOf?: (name: string) => ExpertLookView,
  tailFromConcat = false
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
     * 正文只留真正写在这条记录上的字。
     * 没有正文时：流式中先空着（思考还在走）；结束后说明放在框外。
     * 分段稿已经发回各卡，就不要再说「没有返回正文」。
     */
    const answer = text
    const terminalNote = text || streaming
      ? ''
      : (tailFromConcat ? SUMMARY_REDISTRIBUTED_NOTE : SUMMARY_EMPTY_NOTE)
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
      terminalNote,
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
