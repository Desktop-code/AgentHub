/**
 * MA 运行页三栏视图模型。所属域：runtime（前端展示层）。
 *
 * <p>职责：把「试跑时间线 turns + 每轮 obs trace」归纳成三栏直接可渲染的数据：
 * - 中栏：用户问题 / 模型思考（路由 + 子 Agent 卡）/ 模型回答正文；
 * - 右栏：执行链路（Agent → LLM → Tool 的等宽树形概要）。
 *
 * <p>关键约束：
 * - 纯函数、零组件依赖，便于后续单测；
 * - 不读 LLM 原始思维链、不拼工具长正文；摘要只取 span 的 outputText / brief；
 * - 不修改 RouterTrialExecutor 的任何执行 / 选优语义，只做展示归纳。
 */
import type { ObsSpan } from '@/types/obs'
import type { AssistantBlock, ChatTurn } from '@/types/runtime'
import type { TimelineCardStatus } from '@/components/runtime/routerTimeline'
import { processLanes } from '@/components/runtime/trialMessages'
import {
  LANE_TEMPLATE_COMPETE,
  LANE_TEMPLATE_FIXED_PARALLEL,
  LANE_TEMPLATE_ROUTER_PARALLEL,
  type OrchestrationLaneModel,
  type OrchestrationLaneRow
} from '@/utils/orchestrationLane'

/** 一轮运行对应的追踪数据；spans 为空表示还没采到或没有追踪权限 */
export interface MaTurnTrace {
  /** 本轮 trace 主键；用于左右两栏联动与缓存 */
  traceId: string
  /** 试跑会话主键 */
  sessionId: number
  /** trace 标题，通常是用户原问；用于与用户轮次对齐 */
  title: string
  /** trace 状态：RUNNING / OK / ERROR / CANCELLED */
  status: string
  startTime: string | null
  endTime: string | null
  latencyMs: number
  /** 本轮完整步骤树（含父子关系） */
  spans: ObsSpan[]
  /** 编排泳道模型；没有步骤时为 null */
  model: OrchestrationLaneModel | null
  /** 步骤还在加载 */
  loading: boolean
  /** 该轮追踪加载失败说明；空串表示正常 */
  error: string
}

/** 中栏卡片类型：路由（含汇总）/ 业务子 Agent；正文统一进下方「模型回答」 */
export type MaCardKind = 'router' | 'agent'

/**
 * 中栏一张协作卡。
 *
 * <p>子 Agent 卡只有名称、状态与耗时，不承载任何正文；
 * 路由卡额外说明命中 / 派发名单，汇总步骤（父模型结果合成）也算在路由这张卡上。
 */
export interface MaRunCard {
  /** 稳定 key：优先复用泳道行 key，状态原地变化时不重建 DOM */
  key: string
  kind: MaCardKind
  /** Agent / 节点名称 */
  title: string
  /** 运行状态 */
  status: TimelineCardStatus
  /** 摘要：路由卡＝命中/派发名单；子 Agent 卡恒为空 */
  summary: string
  /** 该卡总耗时（毫秒）；0 表示拿不到时间 */
  durationMs: number
  /** 左侧 4px 色条颜色；与右栏图例、日志节点颜色一致 */
  color: string
}

/** 一轮运行：一条用户提问 + 其后的协作卡 */
export interface MaRunGroup {
  /** 稳定 key：以用户轮 key 为主 */
  key: string
  /** 从 0 开始的轮序；界面展示时 +1 */
  index: number
  /** 用户原问；没有用户轮时给占位说明 */
  question: string
  userTurn?: ChatTurn
  assistant?: ChatTurn
  /** 本轮 obs trace；可能为空（权限缺失 / 还没采到 / 历史旧轮） */
  trace?: MaTurnTrace
  cards: MaRunCard[]
  /** 本轮整体状态 */
  status: 'running' | 'done' | 'error'
  /** 本轮总耗时（毫秒） */
  durationMs: number
  /** 助手终答全文；中栏「模型回答」直接渲染它，不在思考段铺开 */
  answer: string
  /** 本轮 Agent 名 → 唯一角色色；中栏色条、右栏图例与日志共用这一份 */
  colors: Record<string, string>
}

/** 右栏一行的执行状态：只在有明确结果时给值，其余留空不画图标 */
export type MaLineState = '' | 'running' | 'success' | 'error' | 'cancelled'

/** 右栏「链路」一行：树形结构，不含时间列，也不含入参与 brief 摘要 */
export interface MaChainLine {
  key: string
  depth: number
  /** 树形前缀，如 `├── ` / `│   └── `；根节点无前缀 */
  prefix: string
  kind: 'router' | 'agent' | 'llm' | 'tool' | 'other'
  label: string
  /** token 消耗（LLM 才有；已带 k 缩写，不带 tok 单位） */
  tokens: string
  /** 耗时文本，如 `320ms` / `1.2s` */
  duration: string
  /** 执行状态：跑中转圈 / 成功绿勾 / 失败红叉 */
  state: MaLineState
  /** 业务 Agent 的角色色；其它节点为空串，由 CSS 决定 */
  color: string
}

/**
 * 右栏「明细」一行：扁平列表，按执行顺序一行一步。
 * 只说明「这一步是 LLM、还是调了哪个工具、还是哪个 Agent」，不含总结回答。
 */
export interface MaDetailLine {
  key: string
  /** 该步开始时刻 HH:mm:ss；拿不到时为空串 */
  time: string
  kind: 'router' | 'agent' | 'llm' | 'tool' | 'other'
  /** 一步一行的主文案：路由 / Agent 名 / `LLM · 模型` / `工具 · 名称` */
  label: string
  /** token 消耗（LLM 才有；已带 k 缩写，不带 tok 单位） */
  tokens: string
  /** 耗时文本，如 `320ms` / `1.2s` */
  duration: string
  /** 执行状态：跑中转圈 / 成功绿勾 / 失败红叉 */
  state: MaLineState
  /** 所属 Agent 的角色色；能归属到 Agent 时与中栏色条一致，否则空串由 CSS 给语义色 */
  color: string
}

/** Router 固定紫色，与右栏 Legend / 时间线保持同一语义 */
export const MA_ROUTER_COLOR = '#635BFF'
/** LLM 节点固定橙色（右栏 Legend） */
export const MA_LLM_COLOR = '#F79009'
/** Tool 节点固定绿色（右栏 Legend） */
export const MA_TOOL_COLOR = '#12B76A'

/**
 * 业务 Agent 基础调色板。
 *
 * <p>刻意避开 Router 紫、LLM 橙、Tool 绿：右栏日志里这三是固定语义色，
 * 业务 Agent 若与之同色会被误读成节点类型，而不是「哪个角色」。
 */
const AGENT_PALETTE = ['#2E90FA', '#06AED4', '#7A5AF8', '#EE46BC', '#0E9384', '#6172F3']

/** 角色关键词优先命中：客服蓝、制度青、财务青绿等，比纯哈希更符合业务直觉 */
const ROLE_RULES: { pattern: RegExp; color: string }[] = [
  { pattern: /客服|售后|咨询|service|support/i, color: '#2E90FA' },
  { pattern: /制度|合规|政策|policy|compliance/i, color: '#06AED4' },
  { pattern: /财务|账单|计费|finance|billing|cost/i, color: '#0E9384' },
  { pattern: /技术|研发|开发|tech|dev|code/i, color: '#7A5AF8' },
  { pattern: /运营|增长|operation|ops/i, color: '#EE46BC' },
  { pattern: /法务|legal/i, color: '#6172F3' }
]

/**
 * 业务 Agent 角色色。
 *
 * <p>先按名称关键词命中，再回落到稳定哈希：同一个 Agent 在多轮里颜色不变，
 * 中栏卡片与右栏 Agent 节点因此能一一对应。
 *
 * @param name Agent 名称；空值按首个调色板颜色
 * @return 十六进制颜色
 */
export function agentColorOf(name: string): string {
  const text = (name || '').trim()
  if (!text) {
    return AGENT_PALETTE[0]
  }
  for (const rule of ROLE_RULES) {
    if (rule.pattern.test(text)) {
      return rule.color
    }
  }
  let hash = 0
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash * 31 + text.charCodeAt(i)) >>> 0
  }
  return AGENT_PALETTE[hash % AGENT_PALETTE.length]
}

/** 调色板用尽后按黄金角生成色相，保证「有几个 Agent 就有几种颜色」 */
function extraAgentColor(index: number): string {
  const hue = (index * 137.508) % 360
  return `hsl(${hue.toFixed(1)} 68% 48%)`
}

/**
 * 一轮内给每个业务 Agent 分配唯一角色色。
 *
 * <p>优先用 {@link agentColorOf} 的业务直觉色（客服蓝、制度青…）；若与前面某个
 * Agent（或 Router）撞色，就顺延到调色板里第一个未占用的颜色，再用黄金角色相兜底。
 * 目的：中栏每一张 Agent 卡的色条都不重复，右栏图例才能按角色逐条列出、无需合并。
 *
 * @param names 本轮出现的 Agent 名（按出现顺序，可含重复）
 * @return Agent 名 → 颜色；同名重复只分配一次
 */
export function buildAgentColors(names: string[]): Record<string, string> {
  const colors: Record<string, string> = {}
  // Router 紫是本页固定语义色，业务 Agent 不占用
  const used = new Set<string>([MA_ROUTER_COLOR])
  let extra = 0
  for (const name of names) {
    const title = (name || '').trim()
    if (!title || colors[title]) {
      continue
    }
    const preferred = agentColorOf(title)
    let color = preferred
    if (used.has(preferred)) {
      // 从偏好色在调色板中的下一位开始找空位，找不到才生成新色相
      const start = Math.max(AGENT_PALETTE.indexOf(preferred), 0)
      color = ''
      for (let i = 1; i <= AGENT_PALETTE.length; i += 1) {
        const candidate = AGENT_PALETTE[(start + i) % AGENT_PALETTE.length]
        if (!used.has(candidate)) {
          color = candidate
          break
        }
      }
      if (!color) {
        color = extraAgentColor(extra)
        extra += 1
      }
    }
    used.add(color)
    colors[title] = color
  }
  return colors
}

/**
 * 服务端时间串转毫秒；与 orchestrationLane 的解析口径一致。
 *
 * @param raw `yyyy-MM-dd HH:mm:ss[.SSS]` 或 ISO
 * @return 毫秒；缺失 / 非法返回 null
 */
function timeMs(raw?: string | null): number | null {
  if (!raw) {
    return null
  }
  const ms = Date.parse(raw.includes('T') ? raw : raw.replace(' ', 'T'))
  return Number.isNaN(ms) ? null : ms
}

/**
 * 毫秒时长转短文本。
 *
 * @param ms 毫秒
 * @return 如 `320ms` / `1.2s`；无有效值时为空串
 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) {
    return ''
  }
  if (ms < 1000) {
    return `${Math.round(ms)}ms`
  }
  return `${(ms / 1000).toFixed(1)}s`
}

/** 摘要压缩：保留段落，去掉多余空白，避免 3 行额度被空行吃掉 */
function compact(text: string): string {
  return (text || '')
    .replace(/\r\n/g, '\n')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/** 摘要长度上限：卡片只露 3 行，DOM 里没必要留整篇正文 */
const SUMMARY_MAX = 600

/**
 * 卡片摘要清洗：去掉 Markdown 标题 / 加粗 / 行内代码等标记，截断到 {@link SUMMARY_MAX}。
 *
 * <p>只用于中栏「卡片摘要」（路由卡命中名单、兜底摘要）。模型回答正文不走这里，
 * 而是原样交给 Markdown 渲染，否则用户会看到被剥掉标记的纯文本。
 *
 * @param text 原始摘要
 * @return 清洗后的纯文本；空值返回空串
 */
function summaryText(text: string): string {
  const value = compact(text)
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
  return value.length > SUMMARY_MAX ? `${value.slice(0, SUMMARY_MAX)}…` : value
}

/** 取助手终答全文（可能多块）；中栏「模型回答」正文直接渲染它 */
function answerOf(turn?: ChatTurn): string {
  if (!turn) {
    return ''
  }
  return turn.blocks
    .filter((block) => block.kind === 'assistant')
    .map((block) => block.text)
    .join('\n')
    .trim()
}

/** 阶段码转中文；只给「还没有卡片」的进行中轮次占位 */
function phaseText(phase?: string): string {
  if (phase === 'planning') return '任务规划中'
  if (phase === 'vision') return '识别图片文字'
  if (phase === 'reasoning') return '思考中'
  if (phase === 'tool') return '工具执行中'
  if (phase === 'answering') return '回答生成中'
  if (phase === 'error') return '运行失败'
  return '等待运行'
}

/** 泳道行状态归一：与 RouterTimeline 的卡片状态保持同一口径 */
function rowStatus(row: OrchestrationLaneRow): TimelineCardStatus {
  if (row.failed) {
    return 'error'
  }
  if (row.running) {
    return 'running'
  }
  if (row.spans.length && row.spans.every((span) => span.status === 'CANCELLED')) {
    return 'cancelled'
  }
  return 'success'
}

/** 从泳道行里取可读摘要：优先最后一条 outputText，其次 brief，失败时取 errorSummary */
function rowSummary(row: OrchestrationLaneRow): string {
  if (row.failed) {
    for (let i = row.spans.length - 1; i >= 0; i -= 1) {
      const error = (row.spans[i].errorSummary || '').trim()
      if (error) {
        return summaryText(error)
      }
    }
  }
  for (let i = row.spans.length - 1; i >= 0; i -= 1) {
    const span = row.spans[i]
    const text = (span.outputText || span.brief || span.errorSummary || '').trim()
    if (text) {
      return summaryText(text)
    }
  }
  return ''
}

/**
 * 一组步骤的墙钟耗时。
 *
 * <p>并行子会同时开跑，不能把 latency 相加；这里取最早开始到最晚结束的包络。
 * 仍在运行且没有结束时间时，用 now 兜住，让中栏耗时每秒增长；
 * 本轮已收尾时改用 capMs 封顶，残留 RUNNING 的步骤不会一直涨。
 *
 * @param spans 同一组步骤
 * @param now   当前毫秒
 * @param capMs 本轮收尾时刻（已收尾时传入）；null 表示本轮还在跑
 * @return 毫秒；无有效时间时取最大单步 latency
 */
function spanSetDuration(spans: ObsSpan[], now: number, capMs: number | null = null): number {
  let start: number | null = null
  let end: number | null = null
  let latency = 0
  let running = false
  for (const span of spans) {
    if (span.status === 'RUNNING') {
      running = true
    }
    const begin = timeMs(span.startTime)
    const finish = timeMs(span.endTime)
    if (begin != null) {
      start = start == null ? begin : Math.min(start, begin)
    }
    if (finish != null) {
      end = end == null ? finish : Math.max(end, finish)
    }
    if (span.latencyMs != null && span.latencyMs > latency) {
      latency = span.latencyMs
    }
  }
  if (start != null) {
    const stop = end ?? (running ? (capMs ?? now) : null)
    if (stop != null) {
      return Math.max(0, stop - start)
    }
  }
  return latency
}

/**
 * 取单步耗时。
 *
 * <p>运行中用 now 兜住让时间往前走；本轮已收尾时用 capMs 封顶，
 * 避免残留 RUNNING 的步骤（如汇总 LLM）在历史轮次里跑出小时级耗时。
 *
 * @param span  步骤
 * @param now   当前毫秒
 * @param capMs 本轮收尾时刻；null 表示本轮还在跑
 * @return 毫秒
 */
function spanDuration(span: ObsSpan, now: number, capMs: number | null = null): number {
  const begin = timeMs(span.startTime)
  const end = timeMs(span.endTime)
  if (begin != null) {
    const stop = end ?? (span.status === 'RUNNING' ? (capMs ?? now) : null)
    if (stop != null) {
      return Math.max(0, stop - begin)
    }
  }
  return span.latencyMs != null && span.latencyMs > 0 ? span.latencyMs : 0
}

/** 并行模板 / 竞争选优：中栏不再单出 Router 分析卡，只留候选 + 结论 */
function isParallelLike(model: OrchestrationLaneModel): boolean {
  return (
    model.template === LANE_TEMPLATE_COMPETE ||
    model.template === LANE_TEMPLATE_ROUTER_PARALLEL ||
    model.template === LANE_TEMPLATE_FIXED_PARALLEL
  )
}

/**
 * 路由卡摘要：只说明命中 / 派发到哪些 Agent。
 *
 * <p>竞选用「候选」，并行用「派发」，普通路由用「命中」；
 * 名单优先取已出现的子 Agent 名（与中栏卡片一致），子还没起时退回 route_key。
 *
 * @param model    泳道模型
 * @param row      总控行（仅用于极端兜底）
 * @param children 子 Agent 行
 * @return 一句名单说明；不包含判路由 LLM 的任何正文
 */
function routerSummary(model: OrchestrationLaneModel, row: OrchestrationLaneRow, children: OrchestrationLaneRow[]): string {
  const names = children.map((child) => child.title).filter(Boolean)
  const scope = names.length ? names : model.routeKeys
  if (model.template === LANE_TEMPLATE_COMPETE) {
    const count = scope.length || model.childCount
    return scope.length ? `候选 ${count} 个同题并行：${scope.join('、')}` : `候选 ${count} 个同题并行`
  }
  if (isParallelLike(model)) {
    return scope.length ? `派发 ${scope.length} 个：${scope.join('、')}` : `派发 ${model.childCount} 个子 Agent`
  }
  if (scope.length) {
    return `命中：${scope.join('、')}`
  }
  // 名单还没落库时只报子数，不拿判路由正文凑数
  return model.childCount ? `派发 ${model.childCount} 个子 Agent` : rowSummary(row)
}

/** 结论卡状态：助手失败优先；否则按汇总行状态，进行中轮次保持进行态 */
function resultStatus(aggregate: OrchestrationLaneRow, group: MaRunGroup): TimelineCardStatus {
  if (group.assistant?.status === 'error' || aggregate.failed) {
    return 'error'
  }
  if (aggregate.running || group.assistant?.status === 'streaming') {
    return 'running'
  }
  if (aggregate.spans.length && aggregate.spans.every((span) => span.status === 'CANCELLED')) {
    return 'cancelled'
  }
  return 'success'
}

/** 有 obs 数据时：由泳道行生成中栏卡片 */
function cardsFromModel(model: OrchestrationLaneModel, group: MaRunGroup, now: number): MaRunCard[] {
  const rows = model.rows
  const judge = rows.find((row) => row.kind === 'judge')
  const children = rows.filter((row) => row.kind === 'child')
  // 汇总（父模型做结果合成）算路由 Agent 自己的收尾一步，并入路由卡，不单出一张「结论」卡
  const aggregate = rows.find((row) => row.kind === 'aggregate')
  const cards: MaRunCard[] = []
  // 本轮已收尾时用收尾时刻封顶：残留 RUNNING 的步骤不会把耗时一直算下去
  const capMs = isRoundRunning(group) ? null : settleAt(group)
  // 本轮所有业务 Agent 先统一分配颜色，避免两张卡撞色后被图例合并
  const colors = buildAgentColors(children.map((row) => row.title || '子 Agent'))
  // 每轮最上一张固定是「路由」卡：任何模板（含竞争 / 固定并行）都要先说明派发名单
  if (judge || aggregate) {
    // 耗时把判路由与汇总一起算进来，卡上显示的就是路由这一段的总时间
    const routeSpans = [...(judge?.spans ?? []), ...(aggregate?.spans ?? [])]
    // 有汇总行时以汇总终态为准（汇总算路由的一步）；没有汇总就看判路由这一行
    const raw: TimelineCardStatus = aggregate
      ? resultStatus(aggregate, group)
      : judge
        ? rowStatus(judge)
        : 'success'
    cards.push({
      key: judge?.key ?? aggregate?.key ?? 'router',
      kind: 'router',
      title: '路由',
      // 助手已收尾时，残留的进行态按终态兜住，避免卡片一直转圈
      status: settleCardStatus(raw, group, routeSpans),
      summary: judge ? routerSummary(model, judge, children) : '',
      durationMs: spanSetDuration(routeSpans, now, capMs),
      color: MA_ROUTER_COLOR
    })
  }
  for (const row of children) {
    const title = row.title || '子 Agent'
    cards.push({
      key: row.key,
      kind: 'agent',
      title,
      status: settleCardStatus(rowStatus(row), group, row.spans),
      // 子 Agent 卡不展示正文：正文统一进下方「模型回答」，过程明细进右栏
      summary: '',
      durationMs: spanSetDuration(row.spans, now, capMs),
      color: colors[title] ?? agentColorOf(title)
    })
  }
  return cards
}

/** 非判路由模板的子路由前缀；文案来自 RouterTrialExecutor 的 routeLine */
const CHILD_ROUTE_PREFIXES = ['竞争选优', '固定并行', '固定串行', '路由后串行'] as const

/**
 * 清洗路由行里取到的 Agent 名：去掉尾部标点 / 分隔符，内部空格保留。
 *
 * @param raw 捕获到的名称片段
 * @return 可直接展示的名称；空值返回空串
 */
function cleanAgentName(raw: string): string {
  return (raw || '').replace(/[，,。；;·\s]+$/g, '').trim()
}

/**
 * 单行路由文案 → 子 Agent 展示名；不是「某个子开跑」的行返回空串。
 *
 * <p>为什么需要它：判路由模板推「路由到 X（key），依据…」，固定 / 竞争模板推
 * 「竞争选优 1/3 · X（key） · 同题作答」这类前缀文案。后者不会被
 * {@link processLanes} 识别成 agent 泳道，若直接把后续思考落进匿名「执行」泳道，
 * 中栏就会按泳道数刷出一串同名「执行」卡。
 *
 * <p>只认明确的子路由行：总控说明（竞争选优：N 位候选…）、跳过行、评审环角色行
 * 都不当作子 Agent，避免凭空造卡。
 *
 * @param raw 路由块中的一行
 * @return Agent 名；非子路由返回空串
 */
function childNameOfRouteLine(raw: string): string {
  const text = (raw || '').trim()
  if (!text) {
    return ''
  }
  // 判路由：「路由到 X（key），依据：…」；名字取到全角括号 / 逗号前
  if (text.startsWith('路由到')) {
    const rest = text.slice('路由到'.length).trim()
    return cleanAgentName(rest.split(/[（，,]/)[0] ?? '')
  }
  // 固定 / 竞争 / 路由后串行：「前缀 1/3 · X（key）…」
  for (const prefix of CHILD_ROUTE_PREFIXES) {
    if (!text.startsWith(prefix)) {
      continue
    }
    const matched = new RegExp(`^${prefix}\\s*\\d+\\s*/\\s*\\d+\\s*·\\s*([^（·]+)`).exec(text)
    return matched ? cleanAgentName(matched[1]) : ''
  }
  return ''
}

/**
 * 从流式 / 落库块里提取本轮真实子 Agent 名单（按出现顺序去重）。
 *
 * <p>只读 route 块，不读 reasoning / tool：后两者没有 Agent 归属，
 * 不能当成一张子卡渲染，否则就会回到「一堆同名执行卡」的老问题。
 *
 * @param blocks 助手轮的全部块
 * @return 子 Agent 名；空数组表示本轮还没派发或无法从文案识别
 */
function childNamesFromBlocks(blocks: AssistantBlock[]): string[] {
  const names: string[] = []
  const seen = new Set<string>()
  for (const block of blocks) {
    if (block.kind !== 'route') {
      continue
    }
    // 旧数据可能把多条路由行用「；」拼成一段，逐行解析
    for (const line of (block.text || '').split(/[；\n]/)) {
      const name = childNameOfRouteLine(line)
      if (!name || seen.has(name)) {
        continue
      }
      seen.add(name)
      names.push(name)
    }
  }
  return names
}

/**
 * 兜底路径的路由卡摘要：按文案前缀区分「候选 / 派发 / 按序 / 命中」。
 *
 * @param names  已识别的子 Agent 名单（非空）
 * @param blocks 助手轮块，用于识别模板前缀
 * @return 一句名单说明
 */
function fallbackRouterSummary(names: string[], blocks: AssistantBlock[]): string {
  const scope = names.join('、')
  const routeText = blocks
    .filter((block) => block.kind === 'route')
    .map((block) => block.text || '')
    .join('\n')
  if (routeText.includes('竞争选优')) {
    return `候选 ${names.length} 个同题并行：${scope}`
  }
  if (routeText.includes('固定并行')) {
    return `派发 ${names.length} 个：${scope}`
  }
  if (routeText.includes('固定串行') || routeText.includes('路由后串行')) {
    return `按序执行 ${names.length} 个：${scope}`
  }
  return `命中：${scope}`
}

/** 没有 obs 数据时：用落库 / 流式块兜底，仍不展示思维链与工具正文 */
function cardsFromBlocks(group: MaRunGroup, now: number): MaRunCard[] {
  const turn = group.assistant
  if (!turn) {
    return [
      {
        key: `${group.key}:pending`,
        kind: 'router',
        title: '路由',
        status: 'running',
        summary: '等待运行',
        durationMs: 0,
        color: MA_ROUTER_COLOR
      }
    ]
  }
  const status: TimelineCardStatus =
    turn.status === 'streaming' ? 'running' : turn.status === 'error' ? 'error' : 'success'
  const durationMs = turn.startTime ? Math.max(0, now - turn.startTime) : 0
  const cards: MaRunCard[] = []
  const lanes = processLanes(turn.blocks)
  /*
   * 只认路由行里能点名的子 Agent：
   * processLanes 会把「没有归属的思考 / 工具」收进匿名「执行」泳道，
   * 固定 / 竞争模板每次重推子路由都会新开一条这种泳道，直接按泳道出卡就会刷屏。
   */
  const agentTitles = childNamesFromBlocks(turn.blocks)
  // 兜底路径也先统一分配颜色：中栏每张 Agent 卡不撞色，右栏图例才能逐条列出
  const colors = buildAgentColors(agentTitles)
  for (const title of agentTitles) {
    cards.push({
      key: `${turn.key}:agent:${title}`,
      kind: 'agent',
      title,
      status,
      // 兜底路径同样只给名称 / 状态 / 耗时
      summary: '',
      durationMs,
      color: colors[title] ?? agentColorOf(title)
    })
  }
  const decision = lanes.find((lane) => lane.kind === 'decision')
  // 路由卡恒在最上：有子名单就报名单，没有才退回判路由摘要
  const routeSummary = agentTitles.length
    ? fallbackRouterSummary(agentTitles, turn.blocks)
    : decision?.body
      ? summaryText(decision.body)
      : phaseText(turn.phase)
  cards.unshift({
    key: `${turn.key}:router`,
    kind: 'router',
    title: '路由',
    status,
    summary: routeSummary,
    durationMs,
    color: MA_ROUTER_COLOR
  })
  return cards
}

/**
 * 按 trace 与用户轮次对齐。
 *
 * <p>三轮匹配，越往后越保守：
 * 1. 原问精确相等（服务端 trace.title 就是用户原问）；
 * 2. 标题互为前缀（服务端可能截断长问）；
 * 3. 数量恰好相等时才按顺序一一对应。
 *
 * <p>第 3 步不做「从末尾补齐」：新一轮刚发出时 trace 还没落库，
 * 末尾补齐会把上一条旧 trace（甚至上一会话）挂到这一轮上，
 * 中栏与右栏就会先闪一段别人的「路由 / 子 Agent」，随后又变回空。
 * 数量不等说明有轮次还没落 trace，宁可不挂也不串台。
 *
 * @param groups 已按提问顺序排好的轮次（就地写入 trace）
 * @param traces 会话下的全部 trace（顺序不限）
 */
function attachTraces(groups: MaRunGroup[], traces: MaTurnTrace[]): void {
  const ordered = [...traces].sort((a, b) => {
    const ta = timeMs(a.startTime) ?? 0
    const tb = timeMs(b.startTime) ?? 0
    return ta - tb || a.traceId.localeCompare(b.traceId)
  })
  const used = new Set<string>()
  const normalize = (value: string) => (value || '').replace(/\s+/g, ' ').trim()

  // 1) 精确匹配原问
  for (const group of groups) {
    const question = normalize(group.question)
    if (!question) {
      continue
    }
    const hit = ordered.find((trace) => !used.has(trace.traceId) && normalize(trace.title) === question)
    if (hit) {
      group.trace = hit
      used.add(hit.traceId)
    }
  }

  // 2) 截断容错：长问被服务端截成标题时，标题是原问的前缀（或反之）
  for (const group of groups) {
    const question = normalize(group.question)
    if (!question || group.trace) {
      continue
    }
    const hit = ordered.find((trace) => {
      if (used.has(trace.traceId)) {
        return false
      }
      const title = normalize(trace.title)
      return !!title && (question.startsWith(title) || title.startsWith(question))
    })
    if (hit) {
      group.trace = hit
      used.add(hit.traceId)
    }
  }

  // 3) 位置兜底：只有两边数量相等时才一一对应，避免把旧 trace 盖到新一轮上
  const restGroups = groups.filter((group) => !group.trace)
  const restTraces = ordered.filter((trace) => !used.has(trace.traceId))
  if (restGroups.length !== restTraces.length) {
    return
  }
  restGroups.forEach((group, index) => {
    group.trace = restTraces[index]
  })
}

/**
 * 本轮是否仍在执行（展示口径）。
 *
 * <p>以**助手轮**为准：助手还在流式 → 本轮一定在跑；助手已给出终态 → 即使 obs 里
 * 残留 RUNNING 步骤（步骤收尾那一拍没落进缓存 / 轮询已停），也按已收尾处理。
 * 否则中栏卡片一直转圈、右栏「汇总」永远停不下来。
 *
 * @param group 一轮运行
 * @return true 表示本轮仍在跑
 */
function isRoundRunning(group: MaRunGroup): boolean {
  if (group.assistant) {
    return group.assistant.status === 'streaming'
  }
  // 助手轮还没回放出来（刷新后事件未到）：只能参考 trace 自身
  if (group.trace?.status === 'RUNNING') {
    return true
  }
  return !!group.trace?.model?.rows.some((row) => row.running)
}

/**
 * 本轮收尾时刻（毫秒）：给「已收尾但步骤仍标记 RUNNING」的行封顶耗时。
 *
 * <p>不封顶的话，历史轮次里残留的 RUNNING 步骤会用「现在」减开始时刻，
 * 显示成几小时甚至几天。
 *
 * @param group 一轮运行
 * @return 毫秒；拿不到返回 null
 */
function settleAt(group: MaRunGroup): number | null {
  const trace = group.trace
  if (!trace) {
    return null
  }
  const end = timeMs(trace.endTime)
  if (end != null) {
    return end
  }
  const start = timeMs(trace.startTime)
  if (start != null && trace.latencyMs > 0) {
    return start + trace.latencyMs
  }
  let latest: number | null = null
  for (const span of trace.spans) {
    const spanEnd = timeMs(span.endTime)
    if (spanEnd != null) {
      latest = latest == null ? spanEnd : Math.max(latest, spanEnd)
    }
  }
  return latest
}

/**
 * 卡片状态兜底：本轮已收尾时把残留的进行态改成终态。
 *
 * @param status 原始状态（来自泳道行）
 * @param group  一轮运行
 * @param spans  该卡的步骤，用于识别「整组已取消」
 * @return 展示状态
 */
function settleCardStatus(status: TimelineCardStatus, group: MaRunGroup, spans: ObsSpan[] = []): TimelineCardStatus {
  if (status !== 'running' || isRoundRunning(group)) {
    return status
  }
  if (spans.length && spans.every((span) => span.status === 'CANCELLED')) {
    return 'cancelled'
  }
  return group.assistant?.status === 'error' ? 'error' : 'success'
}

/** 本轮整体状态：助手状态优先，obs 只剩残留 RUNNING 行时不判进行中 */
function groupStatus(group: MaRunGroup): 'running' | 'done' | 'error' {
  if (isRoundRunning(group)) {
    return 'running'
  }
  if (group.assistant?.status === 'error' || group.trace?.status === 'ERROR') {
    return 'error'
  }
  return 'done'
}

/** 本轮总耗时：优先 ROUTE 根（覆盖整轮），否则用整条 trace 的包络 */
function groupDuration(group: MaRunGroup, now: number): number {
  // 已收尾时用收尾时刻封顶：残留 RUNNING 的根步骤不会让耗时继续增长
  const capMs = isRoundRunning(group) ? null : settleAt(group)
  const route = group.trace?.model?.routeSpan
  if (route) {
    return spanDuration(route, now, capMs)
  }
  if (group.trace?.spans.length) {
    return spanSetDuration(group.trace.spans, now, capMs)
  }
  if (group.assistant?.status === 'streaming' && group.assistant.startTime) {
    return Math.max(0, now - group.assistant.startTime)
  }
  return 0
}

/**
 * 构建 MA 运行页的三栏视图模型。
 *
 * @param turns  试跑时间线（用户轮 + 助手轮）
 * @param traces 会话下所有轮次的 trace（已按时间升序或乱序均可）
 * @param now    当前毫秒，用于运行中耗时
 * @return 按提问顺序排列的运行轮次
 */
export function buildMaRunGroups(turns: ChatTurn[], traces: MaTurnTrace[], now: number): MaRunGroup[] {
  const groups: MaRunGroup[] = []
  let current: MaRunGroup | undefined
  for (const turn of turns) {
    if (turn.role === 'user') {
      current = {
        key: `user-${turn.key}`,
        index: groups.length,
        question: (turn.text || '').trim() || '（未填写问题）',
        userTurn: turn,
        cards: [],
        status: 'done',
        durationMs: 0,
        answer: '',
        colors: {}
      }
      groups.push(current)
      continue
    }
    // 只有助手轮（刷新后事件还没回放出来）：补一个占位组，中栏不能整块空白
    if (!current) {
      current = {
        key: `assistant-${turn.key}`,
        index: groups.length,
        question: '本轮运行中',
        cards: [],
        status: 'done',
        durationMs: 0,
        answer: '',
        colors: {}
      }
      groups.push(current)
    }
    if (!current.assistant) {
      current.assistant = turn
    }
  }
  attachTraces(groups, traces)
  for (const group of groups) {
    group.answer = answerOf(group.assistant)
    const model = group.trace?.model
    group.cards = model?.hasRouterTrace ? cardsFromModel(model, group, now) : cardsFromBlocks(group, now)
    // 极端情况下模型存在但没有卡片：退回块兜底，避免整轮消失
    if (!group.cards.length) {
      group.cards = cardsFromBlocks(group, now)
    }
    group.status = groupStatus(group)
    group.durationMs = groupDuration(group, now)
    // 颜色以卡片为准：右栏图例与日志都读这一份，左右两栏不会出现同角色不同色
    const colors: Record<string, string> = {}
    for (const card of group.cards) {
      if (card.kind === 'agent') {
        colors[card.title] = card.color
      }
    }
    group.colors = colors
  }
  return groups
}

/** 右栏节点类型归一 */
function chainKind(span: ObsSpan): MaChainLine['kind'] {
  if (span.spanType === 'ROUTE') {
    return 'router'
  }
  if (span.spanType === 'AGENT') {
    return 'agent'
  }
  if (span.spanType === 'LLM') {
    return 'llm'
  }
  if (span.spanType === 'TOOL') {
    return 'tool'
  }
  return 'other'
}

/**
 * 右栏一行的公共上下文：颜色表 + 父链索引 + 汇总节点集合 + 收尾兜底。
 *
 * <p>三个视图共用一份，避免每个节点都重建 Map，也保证「同一节点同一颜色」。
 */
interface LineContext {
  /** 本轮 Agent 名 → 角色色 */
  colors: Record<string, string>
  /** 全量 span 的 id → span，用于向上回溯所属 Agent */
  byId: Map<number, ObsSpan>
  /** 汇总步骤：父模型做的结果合成，归属路由 Agent */
  aggregateIds: Set<number>
  /** 本轮是否已收尾：收尾后残留的 RUNNING 步骤不再画成执行中 */
  settled: boolean
  /** 已收尾时用的兜底状态（按 trace 终态给 success / error / cancelled） */
  settledState: MaLineState
  /** 本轮收尾时刻；已收尾时用于给残留 RUNNING 步骤封顶耗时，未收尾为 null */
  capMs: number | null
}

/**
 * 构建右栏共享上下文。
 *
 * <p>`settled` 以助手轮为准（见 {@link isRoundRunning}），所以哪怕 obs 里
 * 还留着 RUNNING 的汇总步骤，也不会一直转圈。
 *
 * @param group 当前选中的运行轮次
 * @return 右栏三视图共用的上下文
 */
function lineContext(group?: MaRunGroup): LineContext {
  const settled = !!group && !isRoundRunning(group)
  const traceStatus = (group?.trace?.status || '').toUpperCase()
  // trace 终态决定残留 RUNNING 步骤显示成成功还是失败，避免假装一切正常
  const settledState: MaLineState =
    traceStatus === 'ERROR' ? 'error' : traceStatus === 'CANCELLED' ? 'cancelled' : 'success'
  return {
    colors: group?.colors ?? {},
    byId: new Map((group?.trace?.spans ?? []).map((span) => [span.id, span])),
    aggregateIds: aggregateSpanIds(group),
    settled,
    settledState,
    capMs: settled && group ? settleAt(group) : null
  }
}

/** 右栏节点显示名：不拼接任何正文 */
function chainLabel(span: ObsSpan, kind: MaChainLine['kind'], aggregate: boolean): string {
  if (kind === 'router') {
    return '路由'
  }
  if (aggregate) {
    // 汇总由父模型执行，算路由 Agent 自己的一步；只给「汇总」不给结论文本
    return kind === 'llm' && span.modelName ? `汇总 · ${span.modelName}` : '汇总'
  }
  if (kind === 'agent') {
    return (span.agentName || span.name || `Agent #${span.agentId ?? span.id}`).trim()
  }
  if (kind === 'llm') {
    return span.modelName ? `LLM · ${span.modelName}` : 'LLM'
  }
  if (kind === 'tool') {
    return `Tool · ${(span.toolName || span.name || '工具').trim()}`
  }
  return (span.name || span.spanType || '步骤').trim()
}

/**
 * 一步的 token 消耗文本。
 *
 * <p>只有 LLM 有真实 token；Agent 行是聚合值，避免和子步骤重复计数所以不显示。
 * 单位省略：右栏一行很挤，纯数字更好读。
 *
 * @param span 步骤
 * @param kind 节点类型
 * @return 如 `1.2k` / `356`；无消耗返回空串
 */
function tokenText(span: ObsSpan, kind: MaChainLine['kind']): string {
  if (kind !== 'llm' || span.tokenTotal == null || span.tokenTotal <= 0) {
    return ''
  }
  return span.tokenTotal >= 1000 ? `${(span.tokenTotal / 1000).toFixed(1)}k` : String(span.tokenTotal)
}

/**
 * 一步的执行状态：转圈 / 绿勾 / 红叉。
 *
 * <p>工具自报失败（toolSuccess=0）也算失败，与观测页口径一致；
 * 状态为空表示 trace 还没给出终态，此时不画图标，避免假装成功。
 *
 * <p>本轮已收尾（助手已给终态）时，残留 RUNNING 的步骤按本轮终态兜底：
 * 否则「汇总」这类最后一步会一直转圈，哪怕答案早就出来了。
 *
 * @param span 步骤
 * @param kind 节点类型
 * @param ctx  右栏共享上下文（含收尾兜底信息）；缺省按未收尾处理
 * @return 状态枚举
 */
function spanState(span: ObsSpan, kind: MaChainLine['kind'], ctx?: LineContext): MaLineState {
  if (span.status === 'RUNNING') {
    // 收尾那一拍的步骤快照晚于 trace 终态落库时，缓存里会留下 RUNNING；用本轮终态盖掉
    return ctx?.settled ? ctx.settledState : 'running'
  }
  if (span.status === 'ERROR') {
    return 'error'
  }
  if (span.status === 'CANCELLED') {
    return 'cancelled'
  }
  if (kind === 'tool' && span.toolSuccess === 0) {
    return 'error'
  }
  return span.status ? 'success' : ''
}

/** 墙钟 → HH:mm:ss；明细行只到秒，缺失返回空串 */
function clockOf(ms: number | null): string {
  if (ms == null) {
    return ''
  }
  const date = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`
}

/**
 * 单步转控制台一行。
 *
 * @param span   原始步骤
 * @param prefix 树形前缀
 * @param depth  深度
 * @param now    当前毫秒
 * @param ctx    颜色 / 父链 / 汇总集合
 */
function chainLineOf(
  span: ObsSpan,
  prefix: string,
  depth: number,
  now: number,
  ctx: LineContext
): MaChainLine {
  const kind = chainKind(span)
  const aggregate = ctx.aggregateIds.has(span.id)
  return {
    key: String(span.id),
    depth,
    prefix,
    kind,
    label: chainLabel(span, kind, aggregate),
    tokens: tokenText(span, kind),
    duration: formatDuration(spanDuration(span, now, ctx.capMs)),
    state: spanState(span, kind, ctx),
    color: spanColor(span, ctx, aggregate)
  }
}

/** 树形展开的最大层数：异常数据兜底，正常 trace 远小于这个深度 */
const MAX_TREE_DEPTH = 16

/**
 * 构建右栏执行链路。
 *
 * <p>按 span 父子关系画 `├── / └──` 树，顺序即执行顺序；
 * 只输出节点名与耗时，LLM 正文与工具入参都不铺。
 *
 * @param group 当前选中的运行轮次
 * @param now   当前毫秒，用于运行中耗时
 * @return 扁平后的控制台行；无追踪时为空数组
 */
export function buildChainLines(group: MaRunGroup | undefined, now: number): MaChainLine[] {
  /**
   * 汇总 / 结论节点不进右栏：终答属于中栏结论卡的内容，
   * 重复出现在链路里会误导成还有一个执行者。
   */
  const spans = executableSpans(group)
  if (!spans.length) {
    return []
  }
  // 本轮颜色表来自中栏卡片；缺失时按角色名单独取色，不会出现无色节点
  const ctx = lineContext(group)
  /** 已展开的节点：父子关系异常时防止同一节点被无限递归 */
  const visited = new Set<number>()
  const byId = new Map(spans.map((span) => [span.id, span]))
  const children = new Map<number | null, ObsSpan[]>()
  for (const span of spans) {
    // 父节点不在本 trace 里时按根处理，避免整棵子树丢失
    const parent = span.parentId != null && byId.has(span.parentId) ? span.parentId : null
    const list = children.get(parent) ?? []
    list.push(span)
    children.set(parent, list)
  }
  for (const list of children.values()) {
    list.sort((a, b) => a.seq - b.seq || a.id - b.id)
  }
  const route = spans.find((span) => span.spanType === 'ROUTE')
  // 汇总步骤算路由 Agent 自己的一步：挂在根上的汇总 LLM 挪到 ROUTE 下，
  // 否则「只画 ROUTE 一根」时整条汇总步骤会被漏掉。
  if (route) {
    const rootList = children.get(null) ?? []
    const moved = rootList.filter((span) => ctx.aggregateIds.has(span.id))
    if (moved.length) {
      children.set(null, rootList.filter((span) => !ctx.aggregateIds.has(span.id)))
      const routeKids = [...(children.get(route.id) ?? []), ...moved]
      routeKids.sort((a, b) => a.seq - b.seq || a.id - b.id)
      children.set(route.id, routeKids)
    }
  }
  const roots = children.get(null) ?? []
  let effectiveRoots: ObsSpan[]
  if (route && roots.includes(route)) {
    // ROUTE 根能覆盖整轮：只画它，避免把子 Agent 再作为根重复渲染
    effectiveRoots = [route]
  } else if (route) {
    // trace 被服务端包了一层：仍从 ROUTE 开始，同时保留其它根节点
    effectiveRoots = [route, ...roots.filter((span) => span.id !== route.id)]
  } else {
    effectiveRoots = roots
  }
  const lines: MaChainLine[] = []
  /**
   * 递归展开。
   *
   * @param nodes  同层节点
   * @param prefix 当前层前导缩进
   * @param depth  深度
   * @param root   是否根层：单根不画 `└──`，更像日志首行
   */
  const walk = (nodes: ObsSpan[], prefix: string, depth: number, root: boolean) => {
    nodes.forEach((node, index) => {
      // 父子关系异常（自引用 / 环）时直接跳过，避免无限递归把整块视图打空
      if (visited.has(node.id) || depth > MAX_TREE_DEPTH) {
        return
      }
      visited.add(node.id)
      const last = index === nodes.length - 1
      // 单根省略连接符；多根仍用树形，便于区分并列的多个起点
      const connector = root && nodes.length === 1 ? '' : last ? '└── ' : '├── '
      lines.push(chainLineOf(node, prefix + connector, depth, now, ctx))
      const kids = children.get(node.id) ?? []
      walk(kids, prefix + (root && nodes.length === 1 ? '' : last ? '    ' : '│   '), depth + 1, false)
    })
  }
  walk(effectiveRoots, '', 0, true)
  if (lines.length) {
    return lines
  }
  // 兜底：父子关系全是脏数据（自引用 / 环）时退化成扁平列表，绝不把整块视图留白
  return spans.map((span) => chainLineOf(span, '─ ', 0, now, ctx))
}

/**
 * 构建右栏「明细」列表：按执行顺序一行一步。
 *
 * <p>与「链路」的区别只在形态：明细是扁平时序清单，一眼看清每一步是
 * 「路由 / 某 Agent / LLM / 调了哪个工具」；链路保留树形父子关系。
 * 两者都保留汇总步骤（归属路由 Agent），但不输出结论正文。
 *
 * @param group 当前选中的运行轮次
 * @param now   当前毫秒，用于运行中耗时
 * @return 扁平时序明细；无追踪时为空数组
 */
export function buildDetailLines(group: MaRunGroup | undefined, now: number): MaDetailLine[] {
  const spans = executableSpans(group)
  if (!spans.length) {
    return []
  }
  // 颜色要按父链回溯，先把共享上下文建好，避免每行重建一次 Map
  const ctx = lineContext(group)
  return spans.map((span) => {
    const kind = chainKind(span)
    const aggregate = ctx.aggregateIds.has(span.id)
    return {
      key: String(span.id),
      time: clockOf(timeMs(span.startTime)),
      kind,
      label: detailLabel(span, kind, aggregate),
      // 右栏只到「哪一步」为止：工具入参 / LLM 摘要一律不铺
      tokens: tokenText(span, kind),
      duration: formatDuration(spanDuration(span, now, ctx.capMs)),
      state: spanState(span, kind, ctx),
      // 颜色跟着所属 Agent 走，与中栏卡片色条一致
      color: spanColor(span, ctx, aggregate)
    }
  })
}

/** 明细行主文案：类型写在前面，一眼分清是 LLM 还是工具 */
function detailLabel(span: ObsSpan, kind: MaChainLine['kind'], aggregate: boolean): string {
  if (kind === 'router') {
    return '路由'
  }
  if (aggregate) {
    // 汇总＝路由 Agent 自己的合成步骤：算在路由上，只给标签不给结论文本
    return kind === 'llm' && span.modelName ? `汇总 · ${span.modelName}` : '汇总'
  }
  if (kind === 'agent') {
    return (span.agentName || span.name || '子 Agent').trim()
  }
  if (kind === 'llm') {
    const model = (span.modelName || '').trim()
    return model ? `LLM · ${model}` : 'LLM'
  }
  if (kind === 'tool') {
    return `工具 · ${(span.toolName || span.name || '未知工具').trim()}`
  }
  return (span.name || span.spanType || '步骤').trim()
}

/**
 * 一行的颜色：汇总算路由，其它向上找最近的 AGENT 取它的颜色。
 *
 * <p>汇总（父模型做的结果合成）本来就是路由 Agent 自己的一步，按路由紫显示，
 * 不再作为「无主节点」被丢掉；LLM / 工具则和调用它的 Agent 同色，
 * 这样明细里「谁在干活」一眼对得上中栏色条。
 *
 * @param span      当前步骤
 * @param ctx       颜色表 / 父链索引 / 汇总集合
 * @param aggregate 是否汇总步骤
 * @return 颜色；找不到归属时为空串，由 CSS 给中性色
 */
function spanColor(span: ObsSpan, ctx: LineContext, aggregate: boolean): string {
  if (aggregate) {
    return MA_ROUTER_COLOR
  }
  let cursor: ObsSpan | undefined = span
  // 防御父子环：正常深度远小于 12 层
  for (let guard = 0; cursor && guard < 12; guard += 1) {
    const kind = chainKind(cursor)
    if (kind === 'agent') {
      const name = (cursor.agentName || cursor.name || '').trim()
      return ctx.colors[name] ?? agentColorOf(name)
    }
    if (kind === 'router') {
      return MA_ROUTER_COLOR
    }
    cursor = cursor.parentId != null ? ctx.byId.get(cursor.parentId) : undefined
  }
  return ''
}

/**
 * 汇总步骤 id：泳道 aggregate 行里的 span（父模型汇总 / 选优 / 投票）。
 *
 * <p>以前整批排除，结果明细里看不到「汇总」这一步，看起来像少了收尾动作；
 * 现在保留但归属路由 Agent，文案也只给「汇总」，不输出结论正文。
 */
function aggregateSpanIds(group: MaRunGroup | undefined): Set<number> {
  const rows = group?.trace?.model?.rows ?? []
  const ids = new Set<number>()
  for (const row of rows) {
    if (row.kind !== 'aggregate') {
      continue
    }
    for (const span of row.spans) {
      ids.add(span.id)
    }
  }
  return ids
}

/**
 * 检索类步骤 id：知识库检索工具内部的召回 / 融合 / 精排。
 *
 * <p>这些是工具实现细节（`retrieve.vector` / `rrf` / `rerank` …），
 * 对外只保留「工具 · 知识库检索」一行即可，内部记录不铺到右栏。
 */
function retrieveSpanIds(group: MaRunGroup | undefined): Set<number> {
  const ids = new Set<number>()
  for (const span of group?.trace?.spans ?? []) {
    if (span.spanType === 'RETRIEVE') {
      ids.add(span.id)
    }
  }
  // 连带把检索步骤的下级也排除，避免只藏掉父节点留下孤立子步
  if (!ids.size) {
    return ids
  }
  const children = new Map<number, number[]>()
  for (const span of group?.trace?.spans ?? []) {
    if (span.parentId == null) {
      continue
    }
    const list = children.get(span.parentId) ?? []
    list.push(span.id)
    children.set(span.parentId, list)
  }
  const queue = [...ids]
  while (queue.length) {
    const current = queue.pop() as number
    for (const child of children.get(current) ?? []) {
      if (!ids.has(child)) {
        ids.add(child)
        queue.push(child)
      }
    }
  }
  return ids
}

/** 可进右栏的步骤：按 seq 升序；汇总节点保留（归属路由），只排除检索工具内部记录 */
function executableSpans(group: MaRunGroup | undefined): ObsSpan[] {
  const retrieveIds = retrieveSpanIds(group)
  return (group?.trace?.spans ?? [])
    .filter((span) => !retrieveIds.has(span.id))
    .sort((a, b) => a.seq - b.seq || a.id - b.id)
}
