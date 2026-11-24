/**
 * 工作台「思考过程与路由轨迹」时间线模型。所属域：runtime（前端）。
 *
 * <p>只做纯投影：把现网 SSE 已经给出的 route / reasoning / tool 块整理成两步时间线
 * （入口块 → 目标专家），不发请求、不新增事件类型、不改提示词。
 * 入口块展示名读配置（{@link ThinkTimelineInput.entryName}），不写死对外别名。
 * 没有真实思考内容时只给中性状态句，禁止编造推理（CoT）。
 *
 * <p>网关的「为什么选它」只取判路由模型随同一次判定产出的【依据】（后端 route 摘要里的
 * 「依据：…」）。没有依据时降级为「已分发给「某某」」这类事实句：不再拿专家简介在前端
 * 拼一段「根据专家简介…因此分发给…」的假推理。旧数据里遗留的内部标签（父模型 / 兜底话术）
 * 一律视为「没有理由」。
 *
 * <p>与 `processLanes` 的分工：那里负责「按子 Agent 分块」的原始结构与去重，
 * 这里只负责「合成工作台那套两步视图 + 对外脱敏」。
 */
import type { ProcessLane, ProcessStep } from '@/components/runtime/trialMessages'
import type { TrialWorkflowView } from '@/types/runtime'
import type { AgentRobotShape } from '@/constants/agentRobotShapes'
import { parentIndexByBegin } from './beginParent.ts'
import { looksLikeRouteMachineJson, sanitizeVisibleLine } from './workbenchMessageMeta.ts'

/** 生成中的折叠条标题：带一句克制的进行态，不做进度条与跑马灯 */
export const THINK_TITLE_STREAMING = '正在深度思考...'
/** 结束后的折叠条标题：此时默认收起，点开可回看整条轨迹 */
export const THINK_TITLE_DONE = '查看思考与路由轨迹'

/**
 * 步骤一（入口）的**兜底**展示名。
 *
 * <p>只在调用方没注入配置名时使用（历史会话 / 单测）。页面必须把默认接待方案的
 * 配置名（现网 {@code ma_def} 名称，如 Router）经 {@link ThinkTimelineInput.entryName}
 * 传进来，前端不得再维护一套对外别名。
 */
export const GATEWAY_STEP_NAME = '智能网关'

/**
 * 工作流块的角标文案。
 *
 * <p>为什么写在这里而不是展示层各写一份：角标与「这块是不是工作流」是同一件事，
 * 时间线给出的 roleLabel / 展示层的 `actorRoleBadge` 都读这一份，避免两处文案对不上。
 */
export const WORKFLOW_ROLE_LABEL = '工作流 WF'

/**
 * 入口块的展示名：配置名优先，缺失时才退回内置兜底。
 *
 * @param entryName 配置里的入口方案名（可空）
 * @return 展示名；不会返回空串
 */
export function entryTitleOf(entryName?: string): string {
  return (entryName || '').trim() || GATEWAY_STEP_NAME
}

/**
 * 路由判定来源。决定步骤一写哪种事实描述：
 * - judge    判路由模型给出的结论（真实「依据」）
 * - keyword  命中关键词
 * - manual   用户 @ 指定 / 强制线路（未走判路由，不能编造路由思考）
 * - decision 多意图决策块（只有条数与顺序是事实）
 * - workflow 目标为已发布工作流（只报「已交由…执行」）
 * - direct   没有路由过程（REACT 直达），直接交给专家
 */
export type RouteMode = 'judge' | 'keyword' | 'manual' | 'decision' | 'workflow' | 'direct'

/** 路由摘要的解析结果 */
export interface ParsedRoute {
  mode: RouteMode
  /** 目标展示名；decision / 多目标时为空 */
  name: string
  /** 判定依据（已脱敏）；可能为空 */
  reason: string
  /** decision 模式下的意图条数 */
  count: number
  /** decision 模式下的目标展示名（按判定顺序）；没有名字时为空数组 */
  names?: string[]
  /**
   * decision 模式下的块是否来自「一条消息里的多个问题」（句式为「已拆成 N 个问题」）。
   *
   * <p>为什么单独标：只有这种轮次的块序必须严格等于**提问顺序**——两问分头并行，
   * 谁先出 token 都不能换位；普通多意图（同一问题的多位专家协同）保持到达顺序。
   */
  questionSplit?: boolean
}

/** 专家形象（由调用方用 resolveAgentAvatar 解析后传入，组件不做解析） */
export interface ExpertLookView {
  /** 首字兜底 */
  initial: string
  /** 自定义上传图地址 */
  iconSrc?: string
  /** 机器人线稿图元 */
  art?: AgentRobotShape[] | null
  /** 底座色（恒为白底方座） */
  color: string
  /** 线稿 / 首字颜色 */
  initialColor: string
  /** 是否自定义上传图 */
  custom: boolean
}

/** 时间线一步（可直接渲染） */
export interface ThinkTimelineStep {
  /** 1 = 网关；2 = 专家 / 方案 / 方案内子专家（各自独立成块） */
  kind: 'gateway' | 'expert'
  /** 步骤标题：入口块读配置名（{@link ThinkTimelineInput.entryName}），专家 / 方案为展示名 */
  title: string
  /**
   * begin 身份 id。收尾时用来认出「这一步真的开始过」，
   * 不能因为正文还是空的就把这位子专家当成空壳丢掉。
   */
  participantId?: string
  /** begin 帧的 kind。方案 / 工作流 / 专家只认它 */
  participantKind?: string
  /** begin 帧的 depth。没有数字就不参与父子 */
  participantDepth?: number
  /** 这一位自己的终态。停转只看它，不看整轮是否还在流式 */
  closed?: 'done' | 'failed' | 'aborted'
  /**
   * 角色标记：方案「方案」、工作流「工作流 WF」；普通专家不标（避免把参与者当成流水线工位）。
   *
   * <p>入口块的「对话入口」角标不写在这里：它在展示层按 kind=gateway 统一给出，
   * 避免同一件事在模型与视图里各存一份。
   */
  roleLabel?: string
  /** 中性说明行（网关的判定句 / 方案的分流句 / 子专家的依据与状态句） */
  lines: string[]
  /**
   * 该步自己的思考与工具步：入口只有判定推理，方案分流只有分流推理，
   * 专家是自己的推理 + 工具，汇总只有汇总模型自己的推理 + 工具。
   * 任何一步都不承接其它步骤的事件。
   */
  steps: ProcessStep[]
  /** 该步是否仍在进行（决定圆点是否闪动） */
  pending: boolean
  /** 专家形象；网关步骤为空 */
  look?: ExpertLookView
  /**
   * 工作流执行卡片视图（只有工作流步骤会带）。
   *
   * <p>带它＝这一块画「自动化执行流」卡片（节点时间轴 + 产物区），而不是内容框；
   * 不带＝沿用原来「已交由…执行」的一句话块。
   */
  workflow?: TrialWorkflowView
}

/** 投影入参 */
export interface ThinkTimelineInput {
  /** 本轮 route 块正文（按到达顺序） */
  routeTexts: string[]
  /** 已按子 Agent 拆好的思考泳道 */
  lanes: ProcessLane[]
  /** 是否仍在生成 */
  streaming: boolean
  /**
   * 入口块展示名：读默认接待方案的配置名（现网 ma_def 名称，如 Router）。
   *
   * <p>页面必须传；不传时退回 {@link GATEWAY_STEP_NAME}，只为兼容历史调用与单测。
   */
  entryName?: string
  /** 无车道、无路由名时的兜底展示名 */
  fallbackName: string
  /** 按名称解析专家形象 */
  lookOf?: (name: string) => ExpertLookView
  /**
   * 按名称取该候选的简介 / 意图说明（现网 `WorkbenchRouteOption.intentDesc`）。
   *
   * <p>只用于「专家」步骤的说明，不再用于在网关步骤拼「为什么选它」：
   * 那是判路由模型的结论，前端不得用简介编造。
   */
  descriptionOf?: (name: string) => string | undefined
  /**
   * 入口判定是否**仍在进行中**（本轮还没有任何 route / 正文 / 工具 / 产物）。
   *
   * <p>此时入口块只挂判路由模型真实推出的思考，不写任何预设判定句——
   * 判定句要等模型真给出路线再写，避免「先弹块、过一会字自己变了」。
   * 由调用方按原始块判定（`buildParticipantFeed`）；不传时按旧口径（照旧写判定句）。
   */
  entryJudging?: boolean
  /**
   * 入口参与者的身份 id（前端从 SSE `participant` 帧 kind=gateway 记下）。
   *
   * <p>用来按身份把入口判定的推理认回入口块：并行 / 嵌套时它可能被决策行、
   * 说明行挡在中间，只靠「首条 route 之前的匿名泳道」会漏掉。不传时按旧位置口径。
   */
  entryParticipantId?: string
  /**
   * 这一轮是否**期待走入口判定**（新问题＝true；断点续作＝false）。
   *
   * <p>只有在它为 true 且入口还没报身份时，投影才会「先不出块」等身份，
   * 避免把入口判定模型的思考先画成某位专家、再改成入口。
   * 不传时按旧口径（不拦截）：历史回放没有这个标记，不能因为新增规则少画块。
   */
  expectEntry?: boolean
  /**
   * 本轮每一份工作流快照，按参与者 id 或名字对到对应的 begin 卡。
   *
   * <p>不再把「最后一张快照」贴到每一张工作流步骤上。对不上的快照不挂。
   */
  workflowSnapshots?: { participantId?: string; view: TrialWorkflowView }[]
}

/**
 * 过滤对终端用户可见的**正文**：内部键与机器块，但保留 Markdown 的段落结构。
 *
 * <p>为什么不能复用 {@link sanitizeThinkText}：那个函数会把空行一起滤掉，
 * 于是「### 一、组合」与它下面的表格、列表全被粘成一段——正文里的表格直接渲染不出来。
 * 思考可以压成连续行，**正文不行**：Markdown 的段落、表格、列表都靠空行分隔。
 *
 * <p>因此这里只丢弃「这一行本身就是机器路由 JSON / 决策对象 / 提示词泄露」的行，
 * 空行原样保留，只在首尾去掉多余空白。
 *
 * @param raw 正文原文（助手回答）
 * @return 可展示的 Markdown；全部被过滤时为空串
 */
export function sanitizeAnswerText(raw: string): string {
  const source = raw || ''
  if (!source.trim()) {
    return ''
  }
  // 空行原样保留（Markdown 段落靠它）；有字的行走与思考区同一套「抠决策 JSON、留人话」
  const kept = source.split('\n').map((line) => {
    if (!line.trim()) {
      return ''
    }
    if (looksLikePromptLeak(line)) {
      return ''
    }
    return sanitizeVisibleLine(line)
  })
  return kept.join('\n').replace(/^\n+/, '').replace(/\n+$/, '')
}

/**
 * 过滤对终端用户可见的思考文本：内部键 + 指令口吻。
 *
 * <p>口径：
 * <ul>
 *   <li>整行是机器路由 JSON → 整行丢；</li>
 *   <li>人话里夹着决策 JSON → 只抠 JSON，留下周围真实推理（入口判定常把
 *       `route_key` / `sub_query` 写进人话同一行，整行丢会把入口思考框清没）；</li>
 *   <li>提示词泄露口吻 → 整行丢；</li>
 *   <li>其余只抹内部键名。</li>
 * </ul>
 * 宁少丢不多丢：正常业务正文里出现「系统」两个字不会被删。
 *
 * @param raw 思考 / 理由原文
 * @return 可展示文本；全部被过滤时为空串
 */
export function sanitizeThinkText(raw: string): string {
  const source = raw || ''
  if (!source.trim()) {
    return ''
  }
  // 先按原始行处理决策 JSON / 提示词泄露，再脱敏：先抹键会让 JSON 更难识别
  const kept = source
    .split('\n')
    .filter((line) => !looksLikePromptLeak(line))
    .map((line) => sanitizeVisibleLine(line))
    .filter((line) => line.length > 0)
  return kept.join('\n').trim()
}


/** 提示词 / 系统指令泄露口吻的强标记：命中即丢掉整行 */
const PROMPT_LEAK_MARKERS = [
  '根据系统设定',
  '根据系统提示',
  '系统提示词',
  '系统指令要求',
  '作为 AI 模型',
  '作为一个 AI',
  '作为一个AI',
  '我是一个AI',
  '我是一个 AI',
  '人物设定',
  '我的提示词',
  '我被设定为',
  '<|system|>',
  'role: system'
]

/**
 * 内部标签：出现在「依据」位置但只是系统自带说明，不是判给用户看的理由。
 *
 * <p>后端已改为只透传判路由模型产出的自然语言 reason；这里保留过滤是为了兼容
 * 历史会话：早期 {@code RoutedIntent.reason} 在命中时写死「父模型」，兜底分支还会写
 * 「父模型判定失败，使用第一条」这类排障话术。这些都不是「为什么选这个专家」，
 * 必须整条丢弃，改成中性事实句，绝不拿专家简介补一段假推理。
 */
const INTERNAL_REASON_MARKERS = [
  '父模型',
  '子模型',
  'route_key',
  'routeKey',
  'maJudgeModelInstanceId',
  'maJudgeModel',
  'template_key',
  'templateKey',
  '第一条候选',
  '使用第一条'
]

/**
 * 一段「依据」是否只是内部标签 / 排障话术。
 *
 * @param reason 依据原文
 * @return true 表示不能展示给终端用户
 */
export function isInternalReason(reason: string): boolean {
  const text = (reason || '').trim()
  if (!text) {
    return false
  }
  const lower = text.toLowerCase()
  return INTERNAL_REASON_MARKERS.some((marker) => lower.includes(marker.toLowerCase()))
}

/**
 * 单行是否像提示词泄露。
 *
 * @param line 一行思考文本
 * @return true 表示应当丢弃
 */
function looksLikePromptLeak(line: string): boolean {
  const text = (line || '').trim()
  if (!text) {
    return false
  }
  const lower = text.toLowerCase()
  return PROMPT_LEAK_MARKERS.some((marker) => lower.includes(marker.toLowerCase()))
}

/**
 * 解析一条 route 摘要（现网形如「路由到 X（key），依据：…」）。
 *
 * <p>只读现网已有的字符串，不改后端拼装：key 在这里被丢弃，理由进 {@link sanitizeThinkText}。
 *
 * @param raw route 块正文
 * @return 结构化判定；无法识别时按 direct 处理
 */
export function parseRouteText(raw: string): ParsedRoute {
  const text = (raw || '').trim()
  if (!text) {
    return { mode: 'direct', name: '', reason: '', count: 0 }
  }
  // 历史会话可能把机器决策 JSON 直接当 route 事件落库：它不是判定句，按「无路线」处理
  if (looksLikeRouteMachineJson(text)) {
    return { mode: 'direct', name: '', reason: '', count: 0 }
  }
  // 工作流目标：委托方另推的一句状态（先于「路由到」判断，避免被当成专家 CoT）
  const workflow = /^运行工作流\s*「(.+?)」/.exec(text)
  if (workflow) {
    return { mode: 'workflow', name: (workflow[1] || '').trim(), reason: '', count: 0 }
  }
  // 多意图决策块：只给条数与顺序。新数据是人话（「已判定 N 个意图，按序分给「甲」「乙」」
  // 或一条消息多问时的「已拆成 N 个问题，按序交给「甲」「乙」」），
  // 历史数据是 [{"route_key":…,"sub_query":…}]；两种都只提取条数与目标名，正文一律不外泄。
  if (text.startsWith('路由决策')) {
    const body = text.slice('路由决策'.length)
    const fields = (body.match(/"sub_query"/g) || []).length || (body.match(/"subQuery"/g) || []).length
    const names = bracketNames(body)
    // 「已拆成 N 个问题」是条数的权威口径：多问拆分时正文里的引号可能只包目标名
    const splitCount = /已拆成\s*(\d+)\s*个问题/.exec(body)
    const count = splitCount
      ? Math.max(1, Number(splitCount[1]))
      : Math.max(1, fields || names.length)
    return { mode: 'decision', name: '', reason: '', count, names, questionSplit: !!splitCount }
  }
  // 形如「路由到 X（key），依据：…」：先把依据切出去，再从展示名尾部剥掉括号里的 route_key
  const hit = /^路由到\s*([\s\S]+)$/.exec(text)
  if (!hit) {
    return { mode: 'direct', name: '', reason: '', count: 0 }
  }
  let head = hit[1].trim()
  let reason = ''
  const basis = /[,，]\s*依据\s*[：:]\s*([\s\S]*)$/.exec(head)
  if (basis) {
    reason = sanitizeThinkText((basis[1] || '').trim())
    head = head.slice(0, basis.index).trim()
  }
  const name = head.replace(/[（(][^）)]*[）)]\s*$/, '').trim()
  // 「依据」是内部标签（父模型 / 兜底话术）时不算理由：宁可走简介依据，也不给用户看内部词
  if (isInternalReason(reason) || looksLikePromptLeak(reason)) {
    return { mode: 'judge', name, reason: '', count: 0 }
  }
  // 手动换线的理由由后端固定写成「手动换线」：此时不存在判路由思考
  if (reason === '手动换线') {
    return { mode: 'manual', name, reason: '', count: 0 }
  }
  if (reason === '关键字') {
    return { mode: 'keyword', name, reason: '', count: 0 }
  }
  return { mode: 'judge', name, reason, count: 0 }
}

/**
 * 步骤一的说明句：只写现网事实，不推测模型内部过程。
 *
 * <p>依据只认一个来源：判路由模型随同一次判定产出的自然语言 reason
 * （route 摘要里的「依据：…」）。没有 reason 时给中性事实句「已分发给「X」」，
 * 不再引用专家简介拼装推理——简介是候选的职责说明，不是模型这一轮的判定依据。
 *
 * @param route       解析结果
 * @param name        目标展示名（route.name 为空时的兜底）
 * @param description 该专家的简介 / 意图说明；保留入参以兼容旧调用，网关句不再使用
 * @return 一行中文说明
 */
export function gatewayLineOf(route: ParsedRoute, name: string, description?: string): string {
  const target = (route.name || name || '').trim()
  if (route.mode === 'manual') {
    return target ? `已指定「${target}」` : '已按指定线路执行'
  }
  if (route.mode === 'keyword') {
    return target ? `命中关键词，分发给「${target}」` : '命中关键词，已分发'
  }
  if (route.mode === 'decision') {
    const names = route.names || []
    // 只有后端写了「已拆成 N 个问题」才是切问。方案内一个意图分给多人仍是「已判定 N 个意图」
    if (route.questionSplit && names.length) {
      const head = names.length >= route.count
        ? `已拆成 ${route.count} 个问题，分别交给`
        : `已拆成 ${route.count} 个问题，交给`
      return `${head}${names.map((item) => `「${item}」`).join('')}`
    }
    if (names.length) {
      const head = names.length >= route.count
        ? `已判定 ${route.count} 个意图，按序分给`
        : `已判定 ${route.count} 个意图，分给`
      return `${head}${names.map((item) => `「${item}」`).join('')}`
    }
    // 历史数据只有条数：保留「多个意图」的原口径，不把已知事实说丢
    return `判定为多个意图，分发 ${route.count} 路`
  }
  if (route.mode === 'workflow') {
    return target ? `已交由「${target}」执行` : '已交由工作流执行'
  }
  if (route.mode === 'direct') {
    return target ? `已接收问题，交由「${target}」处理` : '已接收问题'
  }
  // 优先：判路由模型给出的依据（同一次模型调用的产出，不额外调用、不编造）
  if (route.reason) {
    const reason = clipText(route.reason, GATEWAY_REASON_MAX)
    return target ? `判定「${reason}」，分发给「${target}」` : `判定「${reason}」`
  }
  // 没有理由时只说事实：分发给谁。禁止用简介在前端补一段「为什么」
  if (target) {
    return `已分发给「${target}」`
  }
  return '已完成路由判定'
}

/** 理由在网关句里的最大字数：一句依据，超出截断，别把一段推理塞进一行 */
const GATEWAY_REASON_MAX = 120

/**
 * 取一段文本里成对的中文引号名字（「甲」「乙」）。
 *
 * <p>人话决策块用它还原「按序分给谁」：后端不写 route_key，只写展示名；
 * 这里顺带把名字抽出来，入口块才能一次列清每个问题交给谁。
 *
 * @param text 决策块正文
 * @return 名字数组（按出现顺序、去重）；没有引号对时为空数组
 */
function bracketNames(text: string): string[] {
  const matched = (text || '').match(/「[^」]{1,40}」/g) || []
  const names: string[] = []
  for (const item of matched) {
    const name = item.slice(1, -1).trim()
    if (name && !names.includes(name)) {
      names.push(name)
    }
  }
  return names
}

/**
 * 统一裁剪并补省略号。
 *
 * @param text   原文
 * @param maxLen 最大字数
 * @return 裁剪后文本
 */
function clipText(text: string, maxLen: number): string {
  const raw = (text || '').trim()
  return raw.length > maxLen ? `${raw.slice(0, maxLen)}…` : raw
}

/**
 * 专家的中性状态句：没有真实思考内容时只说「谁在做」，不编造推导。
 *
 * @param name    专家展示名
 * @param pending 是否仍在生成
 * @return 状态句
 */
export function expertStatusLine(name: string, pending: boolean): string {
  const target = (name || '').trim() || '专家'
  return pending ? `正在由「${target}」作答` : `已由「${target}」完成作答`
}

/**
 * 合成工作台思考时间线。
 *
 * <p>顺序只认 begin：入口第一张；kind=scheme 且 depth 更深的是父块；
 * 子专家和方案内工作流按 begin 先后跟在该方案下面，之后不换位。
 * 不按候选池、不按点名、不把工作流提前或沉底。
 *
 * <p>工作流目标不出专家思考步；方案块只写自己的分流结论，不把子专家的思考挂到它名下。
 *
 * <p><b>每步三拍</b>：先思考（本步推理）→ 再工具（本步自己调用的）→ 最后结果。
 * 投影只做归属，不搬运事件：A 步的思考 / 工具绝不挂到 B 步；
 * 入口与方案分流这一步不调工具，因此它们的 steps 只可能来自该次判定模型的推理。
 *
 * @param input 投影入参
 * @return 时间线；无任何可展示内容时为空数组（调用方不渲染折叠条）
 */
export function buildThinkTimeline(input: ThinkTimelineInput): ThinkTimelineStep[] {
  const agentLanes = input.lanes.filter((lane) => lane.kind === 'agent')
  const entryTitle = entryTitleOf(input.entryName)
  /*
   * 首条 route 之前的过程事件属于**入口判定**：`processLanes` 会把它们落进一条匿名「执行」块
   * （标题没有「路由到」）。判定模型的真实思考就是从这里来的，取出来挂到入口块上，
   * 否则它会被当成一位叫「执行」的参与者或被展示层当无名块丢掉。
   *
   * 只在「本轮真的走判路由 / 真的有入口判定」时做这件事：断点续作与历史纯单 Agent 轮次
   * 既没有 route、也没有入口参与者身份，那条泳道就是专家自己的思考，不能抢到入口块里。
   *
   * 入口块本身也按同一判据出现：有入口参与者身份（实时流 begin 帧）、有 ROUTE 事件，
   * 或判定正在进行。断点续作轮由同一位专家直接接着跑，后端既不推入口参与者、
   * 也不写 ROUTE 事件——这里再补一块「已接收问题，交由「X」处理」就是凭空造了一次入口判定，
   * 页面上看起来像重新分流了一遍。
   */
  /*
   * 入口块出现的三种依据：判定进行中、已有入口身份、或已经有真实路由文案。
   *
   * <p>`expectEntry === false` 是断点续作 / 等待回复续跑：这一轮由原专家或原工作流接着跑，
   * 后端不判路由、不推入口身份。此时即使消息流里混进了 `route` 块（例如参与者 begin 的
   * 占位行，或并行分支自己的路由行），也不能合成「智能网关 → X」——那会让用户以为入口
   * 又分发了一次、新开了一条运行。
   */
  const entryRouted = input.expectEntry === false
    ? false
    : input.entryJudging === true || input.routeTexts.length > 0 || !!input.entryParticipantId
  const split = entryRouted
    ? splitEntrySteps(input.lanes, input.entryParticipantId)
    : { entrySteps: [] as ProcessStep[], lanes: agentLanes }
  const lanes = split.lanes
  const parsedRoutes = input.routeTexts.map(parseRouteText).filter((item) => item.mode !== 'direct')
  // 入口层名字只取 depth=1 的 begin。方案内部的工作流 depth 更深，不会盖住入口判定句
  const topRoutes = topRoutesOf(parsedRoutes)
  /*
   * 取**第一条**入口路线：入口判定一旦发生就固定下来。
   * 并行多子时后端先推一条决策块、随后每个子开跑前再重推自己的「路由到」；
   * 若取最后一条，或按后到的 depth=1 名单把第一条滤掉，入口块都会过一会自己改口。
   */
  const parsed = topRoutes.length ? topRoutes[0] : parseRouteText('')
  // 块序只按 begin 的 depth / kind 收组。第一条是工作流时也不提前返回，后面的卡照样出
  const orderedLanes = lanesInBeginGroups(lanes)
  const laneNames = orderedLanes.map((lane) => routeAgentNameOf(lane.title))
  const expertNames = laneNames.filter((name) => !!name)
  const primaryName = expertNames[0] || parsed.name || input.fallbackName
  // 入口判定进行中：先只出入口块（挂这一步的真实思考），专家块等真实 route 到了再出
  const judging = input.entryJudging === true && split.entrySteps.length > 0
  /*
   * 入口判定块只在本轮**真的有过入口判定**时出现（有路线，或判定正在进行）。
   *
   * <p>断点续作的这一轮由同一位专家直接接着跑：后端不判路由、不写 ROUTE 事件，
   * 这里再补一块「已接收问题，交由「X」处理」就等于凭空造了一次入口判定，
   * 页面上看起来像重新分流了一遍（正是要去掉的假象）。
   */
  const steps: ThinkTimelineStep[] = entryRouted
    ? [
        {
          kind: 'gateway',
          title: entryTitle,
          roleLabel: '判定',
          /*
           * 判定还在跑：入口块只挂真实思考，**不写**预设判定句。
           * 此时写一句「已接收问题，交由 X 处理」就是在判定出来之前先把结果摆上去，
           * 等真正的路线到达还要在同一块里改字——正是要去掉的「先弹块再改字」。
           */
          lines: judging
            ? []
            : [gatewayLineOf(parsed, primaryName, input.descriptionOf?.(primaryName))],
          steps: split.entrySteps,
          pending: input.streaming
        }
      ]
    : []
  if (judging) {
    // 入口判定进行中：先只出入口块（挂真实思考），专家块等真实 route 到了再出
    return steps
  }
  if (!orderedLanes.length) {
    // 有路由文案却没有身份泳道：不按路由名补专家。断点续作没有路由，仍用调用方给出的当前专家名出一块。
    if (input.routeTexts.length > 0) {
      return steps
    }
    steps.push({
      kind: 'expert',
      title: primaryName || '专家',
      lines: [expertStatusLine(primaryName, input.streaming)],
      steps: [],
      pending: input.streaming,
      look: input.lookOf?.(primaryName)
    })
    return steps
  }
  // 子块名单按 depth：方案后面、depth 正好深一层的才算它直接分出去的
  const schemeChildren = directChildNames(orderedLanes)
  const nested = nestedLaneIndexes(orderedLanes)
  for (let index = 0; index < orderedLanes.length; index += 1) {
    const lane = orderedLanes[index]
    let name = laneNames[index]
    if (!name) {
      /*
       * 没有「路由到 {名}」的泳道只有断点续作还在用：这一轮没有路由事件，
       * 名字用调用方给出的当前专家。已经有路由时不再把这条匿名泳道兜成「智能助手」。
       */
      if (input.routeTexts.length > 0) {
        continue
      }
      name = primaryName || '专家'
    }
    const participantId = (lane.participantId || '').trim() || undefined
    const participantKind = (lane.participantKind || '').trim()
    // 停转只看这一位的终态，不跟着整轮 streaming 一起转
    const laneStreaming = input.streaming && !lane.closed
    // 工作流只认 begin 的 kind。正文进自己的结果区，不改入口判定句，也不把快照贴到别的卡上
    if (participantKind === 'workflow') {
      const snapshot = matchedWorkflow(name, participantId, input.workflowSnapshots)
      steps.push({
        kind: 'expert',
        title: name,
        participantId,
        participantKind: 'workflow',
        participantDepth: lane.participantDepth,
        closed: lane.closed,
        roleLabel: WORKFLOW_ROLE_LABEL,
        lines: [],
        steps: [],
        pending: laneStreaming,
        look: input.lookOf?.(name),
        workflow: snapshot
      })
      continue
    }
    const children = schemeChildren.get(index) || []
    const isScheme = participantKind === 'scheme'
    /*
     * 方案块只挂**它自己这一步**的思考（分流判定模型的推理），子专家的思考各自成块：
     * 每个子开跑前都会重推自己的「路由到」，泳道在那里就切走了，不会混进方案块。
     * 原先这里直接给空数组，于是方案分流阶段的真实推理被整块丢掉，页面上就没有思考框。
     *
     * 真分了子专家时只留推理：这一块的结果是分流句，它自己从不调工具。
     * 子专家调的工具必须留在子专家块上（工具胶囊只属于真正调它的那一步），
     * 即便上游交错把工具事件写进了方案泳道，也不能在这里画出来。
     * 方案没有子专家时它自己在作答，工具就是它自己的，照常保留。
     */
    const visibleSteps = isScheme && children.length
      ? reasoningStepsOf(lane)
      : visibleStepsOf(lane)
    steps.push({
      kind: 'expert',
      title: name,
      participantId,
      participantKind: participantKind || undefined,
      participantDepth: lane.participantDepth,
      closed: lane.closed,
      roleLabel: isScheme ? '方案' : undefined,
      lines: linesOfStep(lane, name, laneStreaming, visibleSteps.length, children, isScheme, nested.has(index)),
      steps: visibleSteps,
      pending: laneStreaming,
      look: input.lookOf?.(name)
    })
  }
  return steps
}

/**
 * 把工作流快照对到 begin 卡：先对 id 且名字一致，对不上再按名字或编码。
 * 不把最后一张快照贴到每一张工作流卡上。
 *
 * @param name          begin 上的展示名
 * @param participantId begin 身份
 * @param snapshots     本轮快照
 * @return 对上的快照；没有时为 undefined（卡仍然留着，只是还没有结果区）
 */
function matchedWorkflow(
  name: string,
  participantId: string | undefined,
  snapshots: { participantId?: string; view: TrialWorkflowView }[] | undefined
): TrialWorkflowView | undefined {
  const list = snapshots || []
  const title = (name || '').trim()
  const sameName = (view: TrialWorkflowView) => {
    const viewName = (view.name || '').trim()
    const viewCode = (view.code || '').trim()
    return !!title && (viewName === title || viewCode === title)
  }
  if (participantId) {
    const byId = list.find((item) => item.participantId === participantId && sameName(item.view))
    if (byId) {
      return byId.view
    }
  }
  if (!title) {
    return undefined
  }
  return list.find((item) => sameName(item.view))?.view
}

/**
 * 一条泳道里可展示的思考 / 工具步。
 *
 * <p>推理按展示口径脱敏（内部键、机器路由 JSON、指令口吻都会被滤掉），
 * 脱敏后为空的推理不产出步骤——没有内容就不画这一步。
 *
 * <p>历史半截事件（泳道缺 `steps`）按既有口径处理：交给调用方兜底（整轮退化成逐块渲染），
 * 这里不静默补一个空数组，否则会画出「有块没内容」的假参与者。
 *
 * @param lane 泳道
 * @return 可展示步骤（保持发生顺序）
 */
function visibleStepsOf(lane: ProcessLane): ProcessStep[] {
  return lane.steps
    .map((step) => ({ ...step, text: sanitizeThinkText(step.text) }))
    .filter((step) => step.type !== 'reasoning' || !!step.text)
}

/**
 * 一条泳道里该步**自己**的推理步（不含工具）。
 *
 * <p>入口判定与方案分流都只做一件事：让模型想一次、给一句话结论。它们不挂工具，
 * 因此这两块的思考区只允许出现该次调用的推理。哪怕上游把别的步骤的工具塞进了同一条泳道
 * （并行交错、历史半截事件），也不会被画成「这一步调了工具」——工具胶囊只留给真正调工具的步骤。
 *
 * @param lane 泳道
 * @return 推理步（保持发生顺序）
 */
function reasoningStepsOf(lane: ProcessLane): ProcessStep[] {
  return visibleStepsOf(lane).filter((step) => step.type === 'reasoning')
}

/**
 * 把「首条 route 之前」的泳道拆出来，作为入口判定的思考。
 *
 * <p>判定没有任何泳道可归：`processLanes` 只能把它们落进匿名「执行」块。
 * 这里只吃**最前面连续**的匿名泳道（标题里没有「路由到」），
 * 一旦遇到第一位真实参与者就停——后面的都是各专家自己的思考，绝不能挂到入口块上。
 *
 * <p>遇到决策块 / 说明行（`kind !== 'agent'`，如「路由决策 …」「运行工作流「X」…」
 * 「方案结论：X」）同样停：它们已经把入口那一层收束完毕，之后落进匿名泳道的过程事件
 * 属于别的步骤（例如工作流节点），不能算入口的思考。
 *
 * <p>只取推理：入口这一步的结果是判定句，它自己从不调工具；
 * 匿名泳道里若混进工具步，那是别的步骤抢先推的事件，不能算入口的。
 *
 * <p>两条口径叠加，宁多认领不漏：
 * <ol>
 *   <li><b>位置</b>：最前面连续、标题里没有「路由到」的匿名泳道（历史回放没有身份）；</li>
 *   <li><b>身份</b>：`lane.participantId` 等于入口 id 的泳道（实时流有身份帧）。
 *       入口推理此前只在第一条 route 之前成立；一旦前面出现决策行 / 说明行，
 *       位置口径立刻停住，真实推理就整段丢掉了。按身份认领可把它找回来。</li>
 * </ol>
 *
 * @param lanes 全部泳道（含决策 / 说明行，用来判断入口层的边界）
 * @param entryParticipantId 入口身份 id；可空（历史数据）
 * @return 入口思考步 + 剩余 agent 泳道
 */
function splitEntrySteps(lanes: ProcessLane[], entryParticipantId?: string): {
  entrySteps: ProcessStep[]
  lanes: ProcessLane[]
} {
  const entryId = (entryParticipantId || '').trim()
  const entrySteps: ProcessStep[] = []
  let cursor = 0
  while (cursor < lanes.length) {
    const lane = lanes[cursor]
    if (!lane || lane.kind !== 'agent' || routeAgentNameOf(lane.title)) {
      break
    }
    // 有身份且明确不是入口：这位是别人（例如汇总），不能抢进入口块
    if (entryId && lane.participantId && lane.participantId !== entryId) {
      break
    }
    entrySteps.push(...reasoningStepsOf(lane))
    cursor += 1
  }
  // 身份兜底：入口推理被决策 / 说明行挡在后面时，按 id 认回来（同一泳道不会重复计入）
  const rest: ProcessLane[] = []
  for (const lane of lanes.slice(cursor)) {
    if (entryId && lane.kind === 'agent' && (lane.participantId || '').trim() === entryId) {
      entrySteps.push(...reasoningStepsOf(lane))
      continue
    }
    rest.push(lane)
  }
  // 后续分组只看真实参与者泳道；决策 / 说明行不参与分块
  return { entrySteps, lanes: rest.filter((lane) => lane.kind === 'agent') }
}

/**
 * 一块的说明行。
 *
 * <p>三种口径，都不编造：
 * <ul>
 *   <li>方案：有子专家就写「已把问题分给 A、B」（这是本轮真实发生的事），否则退中性状态句；</li>
 *   <li>方案内部子专家：先写它自己被选中的依据（后端「路由到 …，依据：…」原文），再按有无思考补状态句；</li>
 *   <li>入口专家：保持原样，只在没有思考时补状态句（网关块已写过依据，不重复）。</li>
 * </ul>
 *
 * @param lane         该块泳道
 * @param name         展示名
 * @param streaming    是否仍在生成
 * @param stepCount    本块真实思考 / 工具步数
 * @param children     方案分下去的子专家展示名
 * @param scheme       是否方案块
 * @param nested       是否是方案内部的子专家（决定要不要写它自己那条依据）
 * @return 说明行
 */
function linesOfStep(
  lane: ProcessLane,
  name: string,
  streaming: boolean,
  stepCount: number,
  children: string[],
  scheme: boolean,
  nested: boolean
): string[] {
  if (scheme) {
    if (children.length) {
      return [schemeChildrenLineOf(children)]
    }
    /*
     * 子专家还没投影出来时（仍在流式），优先用后端真实路由行里的「依据」撑住内容框：
     * 方案卡是 begin 帧先到的，这条依据是那一帧之后最早的真实事实，
     * 比凭空补一句状态句更可信，也避免「补了又被过滤」导致整块被判成空壳删掉。
     * 已经结束还没有子专家，说明这一轮它自己在作答，仍退中性状态句（不把入口的分流理由当结果）。
     */
    const basis = streaming ? laneBasisOf(lane) : ''
    if (basis) {
      return [basis]
    }
    /*
     * 有身份的块不许空内容框：分流判定还在思考（stepCount>0）时也要留一句中性进行态，
     * 否则这一拍卡片的内容框就是空的（`[]` → 被判成空壳 → 整块消失 / 等子专家到达才闪出来）。
     * 这句话只说明「谁在做」，不预测分给谁，子专家到了之后仍由真实分流句覆盖。
     */
    return [expertStatusLine(name, streaming)]
  }
  const lines: string[] = []
  // 只有方案内部的子专家才写自己那条依据：入口那层已在网关块写过同一句，重复会显得像两次判定
  const basis = nested ? laneBasisOf(lane) : ''
  if (basis) {
    lines.push(basis)
  }
  if (!stepCount) {
    lines.push(expertStatusLine(name, streaming))
  }
  return lines
}

/**
 * 方案块的分流句：只说实际分给了谁，不推测方案内部怎么想的。
 *
 * @param names 子专家展示名（按实际执行顺序）
 * @return 一行中文
 */
export function schemeChildrenLineOf(names: string[]): string {
  const unique: string[] = []
  for (const raw of names) {
    const name = (raw || '').trim()
    if (name && !unique.includes(name)) {
      unique.push(name)
    }
  }
  if (!unique.length) {
    return ''
  }
  return `已把问题分给${unique.map((name) => `「${name}」`).join('')}`
}

/**
 * 子专家块的分流依据：取后端「路由到 X，依据：…」里那句依据（已脱敏）。
 *
 * <p>只给方案内部的子专家用；入口那层已在网关块写过同一句，重复会显得像两次判定。
 *
 * @param lane 子专家泳道
 * @return 一行「分流依据：…」；没有可用依据时为空串
 */
function laneBasisOf(lane: ProcessLane): string {
  const body = (lane.body || '').trim()
  if (!body) {
    return ''
  }
  const line = body
    .split('\n')
    .map((item) => item.trim())
    .find((item) => item.startsWith('依据'))
  if (!line) {
    return ''
  }
  const reason = sanitizeThinkText(line.replace(/^依据\s*[：:]\s*/, ''))
  if (!reason || isInternalReason(reason) || looksLikePromptLeak(reason)) {
    return ''
  }
  return `分流依据：${clipText(reason, GATEWAY_REASON_MAX)}`
}

/**
 * 入口判定句钉在第一条路线上。
 *
 * <p>后端先推入口判定，子路线排在后面。按后到的泳道名单把第一条滤掉，
 * 入口句就会改成子方案里的人，页面上就是说完又改口。
 *
 * @param routes 全部已解析路线（direct 已剔除）
 * @return 只含第一条；没有路线时为空
 */
function topRoutesOf(routes: ParsedRoute[]): ParsedRoute[] {
  if (!routes.length) {
    return routes
  }
  return [routes[0]]
}

/**
 * 按 begin 先后和 depth 把泳道收成「父块后面紧跟自己的子块」。
 *
 * <p>父与消息流 {@code nestByBegin} 同一条 {@code parentIndexByBegin}：
 * begin id 里有父方案键就进那张方案，没有才退回最近的更浅方案。
 * 子块插在所属方案已有子块的末尾。没有 depth 的块不认父，也不当父。
 *
 * @param lanes begin 先后
 * @return 投影顺序
 */
function lanesInBeginGroups(lanes: ProcessLane[]): ProcessLane[] {
  const nodes = lanes.map((lane, index) => ({
    lane,
    index,
    participantId: lane.participantId,
    depth: typeof lane.participantDepth === 'number' ? lane.participantDepth : undefined,
    kind: (lane.participantKind || '').trim(),
    parent: undefined as number | undefined
  }))
  for (let i = 0; i < nodes.length; i += 1) {
    nodes[i].parent = parentIndexByBegin(nodes, i)
  }
  const out: ProcessLane[] = []
  const emit = (parent: number | undefined) => {
    for (const node of nodes) {
      const mine = parent === undefined ? node.parent === undefined : node.parent === parent
      if (!mine) {
        continue
      }
      out.push(node.lane)
      if (node.kind === 'scheme') {
        emit(node.index)
      }
    }
  }
  emit(undefined)
  return out
}

/**
 * 每个方案的直接子块名字（depth 正好深一层）。供分流句使用。
 *
 * @param lanes 已经按 begin 收组后的泳道
 * @return 方案下标 → 直接子块展示名
 */
function directChildNames(lanes: ProcessLane[]): Map<number, string[]> {
  const map = new Map<number, string[]>()
  for (let i = 0; i < lanes.length; i += 1) {
    const depth = lanes[i].participantDepth
    if ((lanes[i].participantKind || '').trim() !== 'scheme' || typeof depth !== 'number') {
      continue
    }
    const names: string[] = []
    for (let j = i + 1; j < lanes.length; j += 1) {
      const nextDepth = lanes[j].participantDepth
      if (typeof nextDepth !== 'number' || nextDepth <= depth) {
        break
      }
      if (nextDepth === depth + 1) {
        const name = routeAgentNameOf(lanes[j].title)
        if (name) {
          names.push(name)
        }
      }
    }
    map.set(i, names)
  }
  return map
}

/**
 * 哪些泳道挂在某个更浅的方案下面。
 *
 * @param lanes 已经按 begin 收组后的泳道
 * @return 子块下标
 */
function nestedLaneIndexes(lanes: ProcessLane[]): Set<number> {
  const nested = new Set<number>()
  const stack: number[] = []
  for (let i = 0; i < lanes.length; i += 1) {
    const depth = lanes[i].participantDepth
    if (typeof depth !== 'number') {
      stack.length = 0
      continue
    }
    while (stack.length && (lanes[stack[stack.length - 1]].participantDepth as number) >= depth) {
      stack.pop()
    }
    if (stack.length) {
      nested.add(i)
    }
    if ((lanes[i].participantKind || '').trim() === 'scheme') {
      stack.push(i)
    }
  }
  return nested
}

export function routeAgentNameOf(title: string): string {
  const prefix = '路由到 '
  const text = (title || '').trim()
  return text.startsWith(prefix) ? text.slice(prefix.length).trim() : ''
}

/**
 * 折叠条标题：生成中一句进行态，结束后切回可点击的查看态。
 *
 * @param streaming 是否仍在生成
 * @return 标题文案（不含 `>` 前缀，前缀由样式画）
 */
export function thinkTitleOf(streaming: boolean): string {
  return streaming ? THINK_TITLE_STREAMING : THINK_TITLE_DONE
}
