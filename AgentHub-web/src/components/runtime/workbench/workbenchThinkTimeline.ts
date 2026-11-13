/**
 * 工作台「思考过程与路由轨迹」时间线模型。所属域：runtime（前端）。
 *
 * <p>只做纯投影：把现网 SSE 已经给出的 route / reasoning / tool 块整理成两步时间线
 * （智能网关 → 目标专家），不发请求、不新增事件类型、不改提示词。
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
import type { AgentRobotShape } from '@/constants/agentRobotShapes'
import { looksLikeRouteDecisionPayload, looksLikeRouteMachineJson, stripWorkbenchInternalKeys } from './workbenchMessageMeta.ts'

/** 生成中的折叠条标题：带一句克制的进行态，不做进度条与跑马灯 */
export const THINK_TITLE_STREAMING = '正在深度思考...'
/** 结束后的折叠条标题：此时默认收起，点开可回看整条轨迹 */
export const THINK_TITLE_DONE = '查看思考与路由轨迹'

/** 步骤一（网关）的固定展示名 */
export const GATEWAY_STEP_NAME = '智能网关'

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
  /** 步骤标题：网关恒为「智能网关」，专家 / 方案为展示名 */
  title: string
  /** 角色标记：网关「判定」、方案「方案」；普通专家不标（避免把参与者当成流水线工位） */
  roleLabel?: string
  /** 中性说明行（网关的判定句 / 方案的分流句 / 子专家的依据与状态句） */
  lines: string[]
  /** 专家的思考与工具步骤；工作流 / 方案目标为空 */
  steps: ProcessStep[]
  /** 该步是否仍在进行（决定圆点是否闪动） */
  pending: boolean
  /** 专家形象；网关步骤为空 */
  look?: ExpertLookView
}

/** 投影入参 */
export interface ThinkTimelineInput {
  /** 本轮 route 块正文（按到达顺序） */
  routeTexts: string[]
  /** 已按子 Agent 拆好的思考泳道 */
  lanes: ProcessLane[]
  /** 按展示名取目标类型；未知返回 undefined（按普通 Agent 处理） */
  targetTypeOf?: (name: string) => 'AGENT' | 'MA' | 'WORKFLOW' | undefined
  /** 是否仍在生成 */
  streaming: boolean
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
   * 该名字是否为「入口直接分发」的候选（本会话候选池里能对上）。
   *
   * <p>用来把方案内部的子专家与入口判定区分开：网关块只取入口那条路线，方案块只认自己下面那几条。
   * 不传时全部按入口处理，行为与引入多级展示前一致。
   */
  isTopLevelOf?: (name: string) => boolean
}

/**
 * 过滤对终端用户可见的思考文本：内部键 + 指令口吻。
 *
 * <p>只做「整行命中强标记才丢」，宁少丢不多丢：正常业务正文里出现「系统」两个字不会被删。
 *
 * @param raw 思考 / 理由原文
 * @return 可展示文本；全部被过滤时为空串
 */
export function sanitizeThinkText(raw: string): string {
  // 内部键（route_key / routeKey）先在展示层抹掉，避免把定位键打给终端用户
  const source = raw || ''
  if (!source.trim()) {
    return ''
  }
  // 先按原始行判定「整行是机器路由 JSON / 决策对象 / 提示词泄露」，再逐行脱敏：
  // 先脱敏会让 JSON 变成 `[{"sub_query":…}]`，反而更难识别，也不好整段丢弃
  const kept = source
    .split('\n')
    .filter((line) => !looksLikeRouteMachineJson(line)
      && !looksLikeRouteDecisionPayload(line)
      && !looksLikePromptLeak(line))
    .map((line) => stripWorkbenchInternalKeys(line))
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
  // 多意图决策块：只给条数与顺序。新数据是人话（「已判定 N 个意图，按序分给「甲」「乙」」），
  // 历史数据是 [{"route_key":…,"sub_query":…}]；两种都只提取条数，正文一律不外泄。
  if (text.startsWith('路由决策')) {
    const body = text.slice('路由决策'.length)
    const fields = (body.match(/"sub_query"/g) || []).length || (body.match(/"subQuery"/g) || []).length
    const names = countBracketNames(body)
    return { mode: 'decision', name: '', reason: '', count: Math.max(1, fields || names) }
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
    return `判定为多个意图，分发 ${route.count} 位专家`
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
 * 数一段文本里成对的中文引号名字数（「甲」「乙」）。
 *
 * <p>人话决策块用它还原意图条数：后端已不再往正文里写 JSON，
 * 但「判定出几个意图」这层事实仍要能展示。
 *
 * @param text 决策块正文
 * @return 名字个数；没有引号对时为 0
 */
function countBracketNames(text: string): number {
  const matched = (text || '').match(/「[^」]{1,40}」/g)
  return matched ? matched.length : 0
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
 * <p>顺序固定：网关 → 各参与者。参与者一层一块，按真实发生顺序竖线接力：
 * 入口专家、方案（如「企业综合台」）、方案再分下去的各子专家。
 * 方案内部各跳来自后端已经落库的「路由到」事件，前端只做归组，不新造参与者。
 *
 * <p>工作流目标不出专家思考步；方案（MA）块只写自己的分流结论，不把子专家的思考挂到它名下。
 *
 * @param input 投影入参
 * @return 时间线；无任何可展示内容时为空数组（调用方不渲染折叠条）
 */
export function buildThinkTimeline(input: ThinkTimelineInput): ThinkTimelineStep[] {
  const lanes = input.lanes.filter((lane) => lane.kind === 'agent')
  const parsedRoutes = input.routeTexts.map(parseRouteText).filter((item) => item.mode !== 'direct')
  // 网关只认「入口直接分发」的那条：方案内部各跳不能被当成入口判定，否则会写成「分发给子专家」
  const topRoutes = topRoutesOf(parsedRoutes, input.isTopLevelOf)
  const parsed = topRoutes.length ? topRoutes[topRoutes.length - 1] : parseRouteText('')
  // 工作流：只报「已交由…执行」，不生成任何专家思考步（没有 CoT 就不能假装有）
  if (parsed.mode === 'workflow') {
    return [
      {
        kind: 'gateway',
        title: GATEWAY_STEP_NAME,
        roleLabel: '判定',
        lines: [gatewayLineOf(parsed, parsed.name, input.descriptionOf?.(parsed.name))],
        steps: [],
        pending: input.streaming
      }
    ]
  }
  const laneNames = lanes.map((lane) => routeAgentNameOf(lane.title))
  const expertNames = laneNames.filter((name) => !!name)
  const primaryName = expertNames[0] || parsed.name || input.fallbackName
  const steps: ThinkTimelineStep[] = [
    {
      kind: 'gateway',
      title: GATEWAY_STEP_NAME,
      roleLabel: '判定',
      // 判路由命中时现网 reason 只是内部标签，真实依据改用该专家自己的简介
      lines: [gatewayLineOf(parsed, primaryName, input.descriptionOf?.(primaryName))],
      steps: [],
      pending: input.streaming
    }
  ]
  if (!lanes.length) {
    // 没有泳道：仍给一条专家步，只放中性状态，不凭空造推理
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
  // 方案 → 它分下去的子专家：名字不在入口候选池、且前面最近一块是方案的，归到该方案
  const grouping = groupSchemeChildren(laneNames, input)
  const schemeChildren = grouping.children
  for (let index = 0; index < lanes.length; index += 1) {
    const lane = lanes[index]
    const name = laneNames[index] || primaryName || '专家'
    const targetType = input.targetTypeOf?.(name)
    // 工作流候选可能以「路由到」形式出现：只报一句执行状态，不展开
    if (targetType === 'WORKFLOW') {
      steps[0].lines = [gatewayLineOf({ ...parsed, mode: 'workflow', name }, name, input.descriptionOf?.(name))]
      continue
    }
    const children = schemeChildren.get(index) || []
    const isScheme = targetType === 'MA'
    // 方案目标：只展示这一层，内部再下一层的 reasoning 不并进来（它们各自成块）
    const visibleSteps = isScheme
      ? []
      : lane.steps
          .map((step) => ({ ...step, text: sanitizeThinkText(step.text) }))
          .filter((step) => step.type !== 'reasoning' || !!step.text)
    steps.push({
      kind: 'expert',
      title: name,
      roleLabel: isScheme ? '方案' : undefined,
      lines: linesOfStep(lane, name, input.streaming, visibleSteps.length, children, isScheme,
        grouping.childIndexes.has(index)),
      steps: visibleSteps,
      pending: input.streaming,
      look: input.lookOf?.(name)
    })
  }
  return steps
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
    return children.length ? [schemeChildrenLineOf(children)] : [expertStatusLine(name, streaming)]
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
 * 只保留「入口直接分发」的路线；没有回调时原样返回（兼容调试皮肤与旧数据）。
 *
 * @param routes     全部已解析路线
 * @param isTopLevel 名字是否属于入口候选池
 * @return 入口那一层路线；一条都对不上时退回全部（宁少不空）
 */
function topRoutesOf(routes: ParsedRoute[], isTopLevel?: (name: string) => boolean): ParsedRoute[] {
  if (!isTopLevel) {
    return routes
  }
  const top = routes.filter((route) => !route.name || isTopLevel(route.name))
  return top.length ? top : routes
}

/**
 * 把方案内部各跳归到它所属的方案块。
 *
 * <p>判定规则只有一条：名字能在入口候选池对上 → 入口那一层；对不上且前面最近一块是方案 → 归给该方案。
 * 不猜名字以外的东西，也不因为「没匹配到」就隐藏块。
 *
 * @param names 各泳道展示名（按发生顺序）
 * @param input 投影入参（用 isTopLevelOf / targetTypeOf）
 * @return 方案块下标 → 其子专家展示名；以及哪些块属于方案内部
 */
function groupSchemeChildren(
  names: string[],
  input: ThinkTimelineInput
): { children: Map<number, string[]>; childIndexes: Set<number> } {
  const grouped = new Map<number, string[]>()
  const childIndexes = new Set<number>()
  const gate = input.isTopLevelOf
  let schemeIndex = -1
  for (let index = 0; index < names.length; index += 1) {
    const name = (names[index] || '').trim()
    const isTop = !gate || !name ? true : gate(name)
    if (isTop) {
      schemeIndex = input.targetTypeOf?.(name) === 'MA' ? index : -1
      continue
    }
    if (schemeIndex < 0) {
      continue
    }
    childIndexes.add(index)
    const list = grouped.get(schemeIndex) || []
    list.push(name)
    grouped.set(schemeIndex, list)
  }
  return { children: grouped, childIndexes }
}

/**
 * 从泳道标题里抠出展示名（泳道标题形如「路由到 XXX」）。
 *
 * @param title 泳道标题
 * @return 展示名；不是路由标题时为空串
 */
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
