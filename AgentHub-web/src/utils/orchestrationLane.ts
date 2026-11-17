/**
 * Router 编排泳道模型（只读展示）。所属域：前端 runtime/obs 交界。
 *
 * 职责：把**已有 obs span 树**归纳成「总控 / 子 Agent / 汇总」三条泳道
 * 加一份中文事件列表，供试跑面板的编排泳道渲染。
 * 数据主路径：span 树（`/api/obs/sessions/{id}/traces` → `/api/obs/traces/{traceId}/spans`），
 * 不用 trial events 拼时间——events 没有起止时间，画不出并行观感。
 *
 * 约束：
 * - 纯函数、零运行时依赖，便于 node:test 直接跑；时间条百分比由组件复用 traceParallel 计算；
 * - 只读，不改 RouterTrialExecutor 的任何执行语义；
 * - 六种模板：ROUTER_PARALLEL 有判路由 LLM；FIXED_SEQUENCE / FIXED_PARALLEL 无判路由 LLM，
 *   总控行只显示「无判路由 + 参与者」短条；CRITIC_LOOP 也无判路由，执行者 / 评审专家串行兄弟；
 *   ROUTER_THEN_SEQUENCE 有 JUDGE，但命中子按判定序串行，子块时间窗天然不重叠；
 *   COMPETE 无判路由，多个候选同一问题并行，结束后按策略选优取 1 份终答（不拼接）；
 *   汇总策略 VOTE 不改模板拓扑，只在汇总行显示 winner（票池＝各成功子终答，失败降级 CONCAT）。
 */
import type { ObsSpan } from '@/types/obs'

/** 判路由并行模板（现网基线）；旧 trace 没有 attrs 时按它展示 */
export const LANE_TEMPLATE_ROUTER_PARALLEL = 'ROUTER_PARALLEL'
/** 固定串行模板：无判路由，子块串行不重叠 */
export const LANE_TEMPLATE_FIXED_SEQUENCE = 'FIXED_SEQUENCE'
/** 固定并行模板：无判路由，子块同父并行 */
export const LANE_TEMPLATE_FIXED_PARALLEL = 'FIXED_PARALLEL'
/** 评审环模板：无判路由，执行者 ↔ 评审者串行；终答＝最后一版执行稿，不做多段拼接 */
export const LANE_TEMPLATE_CRITIC_LOOP = 'CRITIC_LOOP'
/** 路由后串行模板：有 JUDGE，命中子按判定序串行（不是「无判路由」） */
export const LANE_TEMPLATE_ROUTER_THEN_SEQUENCE = 'ROUTER_THEN_SEQUENCE'
/** 竞争选优模板：无判路由；多候选同题并行，结束后按策略只取 1 份终答 */
export const LANE_TEMPLATE_COMPETE = 'COMPETE'
/** 固定模板集合：用于决定总控行是否画「判路由」 */
export const LANE_FIXED_TEMPLATES = [LANE_TEMPLATE_FIXED_SEQUENCE, LANE_TEMPLATE_FIXED_PARALLEL]
/** 汇总：按序拼接 */
export const LANE_AGGREGATE_CONCAT = 'CONCAT'
/** 汇总：父模型整合 */
export const LANE_AGGREGATE_SUMMARIZE = 'SUMMARIZE'
/** 汇总：评审环无汇总步骤（终答＝最后一版执行稿） */
export const LANE_AGGREGATE_NONE = 'NONE'
/** 汇总：竞争选优选优（不是拼接也不是模型汇总；终答＝winner 子终答） */
export const LANE_AGGREGATE_PICK = 'PICK'
/** 汇总：投票选优（评选模型在成功子终答里选 1 路原文；失败降级 CONCAT） */
export const LANE_AGGREGATE_VOTE = 'VOTE'
/** 上下文策略：本轮子 Agent 共享键控草稿区（由 ROUTE attrs ma.context 识别） */
export const LANE_CONTEXT_SHARED_SCRATCH = 'SHARED_SCRATCH'

/**
 * 外壳种类键，与后端 {@code RouterObs.OBS_ROLE} 同一个键。
 * 值为 workflow 时这一位是工作流外壳，为 scheme 时是另一套方案的外壳；真专家不写该键。
 */
export const LANE_ATTR_ROLE = 'obs.role'
/** {@link LANE_ATTR_ROLE}：工作流外壳 */
export const LANE_ROLE_WORKFLOW = 'workflow'
/** {@link LANE_ATTR_ROLE}：方案外壳 */
export const LANE_ROLE_SCHEME = 'scheme'
/** 方案根标记，与后端 {@code ma.root} 同键；值为 SCHEME 表示这是方案自己的编排根 */
export const LANE_ATTR_MA_ROOT = 'ma.root'
/** {@link LANE_ATTR_MA_ROOT}：方案编排根 */
export const LANE_MA_ROOT_SCHEME = 'SCHEME'

/** 泳道类型：总控判路由 / 子 Agent / 汇总 */
export type LaneRowKind = 'judge' | 'child' | 'aggregate'
/** 事件语气：普通 / 成功 / 提醒 / 失败 */
export type LaneEventTone = 'info' | 'ok' | 'warn' | 'danger'

/** 一条泳道；bars 由组件用 spans 算百分比，这里只给原始步骤 */
export interface OrchestrationLaneRow {
  key: string
  kind: LaneRowKind
  /** 主标题：根 Agent 名 / 子 Agent 名 / 汇总 */
  title: string
  /** 副标题：JUDGE / route_key + 名义 run_mode / 汇总说明 */
  subtitle: string
  /** 该泳道包含的步骤；CONCAT 汇总可为空（无模型调用） */
  spans: ObsSpan[]
  /** 任一在跑 */
  running: boolean
  /** 任一失败 */
  failed: boolean
  /**
   * 层级：0 = 入口 ROUTE 的直接目标（子泳道单列）；>0 = 挂在外壳下的内层 Agent。
   * <p>内层只在自己的外壳行下缩进展示，不单列、不计入 childCount。
   */
  depth: number
  /**
   * 内层 Agent（按 parent_id 归到本行）：方案外壳下的编排根、工作流外壳下的节点 Agent 等。
   * <p>只用于缩进展示与事件；不参与「派发了几个目标」的计数，也不进中栏卡片。
   */
  nested: OrchestrationLaneRow[]
}

/** 一条中文事件；spanIds 供泳道联动高亮 */
export interface OrchestrationLaneEvent {
  key: string
  /** 事件墙钟（毫秒）；缺时间时为 null */
  atMs: number | null
  text: string
  tone: LaneEventTone
  spanIds: number[]
}

/** 整块泳道视图模型 */
export interface OrchestrationLaneModel {
  /** 是否找到 ROUTER 的 ROUTE 根步骤；false 时页面显示「本轮还没有编排追踪数据」 */
  hasRouterTrace: boolean
  /** ROUTE 根步骤；服务端包络后它的区间覆盖整轮，适合画成总控基线而不是 JUDGE 块 */
  routeSpan: ObsSpan | null
  /** 编排模板；来源 ROUTE span attrs，缺失回落 ROUTER_PARALLEL */
  template: string
  /** 汇总策略；来源 attrs，缺失按是否出现汇总 LLM 推断 */
  aggregate: string
  /** 竞争选优赢家 route_key；仅 COMPETE 且已选优时有值，其余为空串 */
  competeWinner: string
  /** 竞争选优实际生效策略（可能已降级为 FIRST_SUCCESS）；非 COMPETE 为空串 */
  competeStrategy: string
  /** 竞争选优是否发生过降级（评选模型不可用 / 未给合法赢家） */
  competeDegraded: boolean
  /** 投票选优赢家 route_key；仅 aggregate=VOTE 且选中时有值，其余为空串 */
  voteWinner: string
  /** 投票选优是否发生过降级（评选失败 → CONCAT） */
  voteDegraded: boolean
  /** 上下文策略；来源 ROUTE attrs 的 ma.context，缺失为空串（旧 trace 不回填） */
  contextPolicy: string
  /** 共享草稿区已写入的键数；只有本轮选了 SHARED_SCRATCH 且后端打过点才有值 */
  scratchKeyCount: number | null
  /** 共享草稿区被拒绝的同键写入次数；无则 null */
  scratchConflictCount: number | null
  /** 行顺序固定：总控 → 各子（判路由顺序）→ 汇总；扁平口径，问答框内时间线与中栏卡片继续读它 */
  rows: OrchestrationLaneRow[]
  /**
   * 执行明细泳道专用：总控 → 入口直接目标（内层 Agent 收在 nested）→ 汇总。
   *
   * <p>为什么和 {@link rows} 并存：执行明细要分层（外壳 ⊃ 编排根 ⊃ 专家），
   * 而问答框内的编排时间线与中栏协作卡是「一位 Agent 一张卡」的扁平口径。
   * 两份数据同源同构，只是分组方式不同，互不改写。
   */
  laneRows: OrchestrationLaneRow[]
  /** 中文事件，时间升序 */
  events: OrchestrationLaneEvent[]
  /**
   * 参与执行的子 Agent 数：只数入口 ROUTE 的直接目标（方案 / 工作流 / 专家各算一个），
   * 不再把同一条 trace 里所有层级的 AGENT 加在一起。
   */
  childCount: number
  /** 汇总说明，直接展示 */
  aggregateNote: string
  /** 判路由给出的 route_key，按判定顺序（COMPETE 为参赛候选顺序） */
  routeKeys: string[]
}

/** 解析时间；库内多为 `yyyy-MM-dd HH:mm:ss[.SSS]`，Date.parse 需 T */
function msOf(raw: string | null | undefined): number | null {
  if (!raw) {
    return null
  }
  const ms = Date.parse(raw.includes('T') ? raw : raw.replace(' ', 'T'))
  return Number.isNaN(ms) ? null : ms
}

/** attrs_json 解析失败按「没有 attrs」处理：旁路信息，不该让整块泳道报错 */
function parseAttrs(raw: string | null | undefined): Record<string, unknown> {
  if (!raw || !raw.trim()) {
    return {}
  }
  try {
    const parsed = JSON.parse(raw) as unknown
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** 判路由决策项：route_key + 切给该子的 sub_query */
interface DecisionItem {
  routeKey: string
  subQuery: string
}

/** 解析 ROUTE.route_decision；老数据可能是对象或非法串，统一按空处理 */
function parseDecision(raw: string | null | undefined): DecisionItem[] {
  if (!raw || !raw.trim()) {
    return []
  }
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed
      .map((item) => {
        const row = (item || {}) as Record<string, unknown>
        return {
          routeKey: typeof row.route_key === 'string' ? row.route_key : '',
          subQuery: typeof row.sub_query === 'string' ? row.sub_query.trim() : ''
        }
      })
      .filter((item) => item.routeKey || item.subQuery)
  } catch {
    return []
  }
}

/** 子泳道展示名：Agent 名 → span 名 → 主键 */
function childLabel(span: ObsSpan): string {
  return (span.agentName || span.name || '').trim() || `#${span.agentId ?? span.id}`
}

/** 评审环实际评审轮次（来自 ROUTE attrs）；缺失按 0 展示 */
function criticRoundsUsed(route: ObsSpan): number {
  const value = parseAttrs(route.attrsJson)['ma.critic_rounds_used']
  // attrs 允许被历史数据写成字符串数字，这里统一转数，避免泳道显示「0 轮」
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : 0
}

/**
 * 读一个可空整数 attrs 字段（如共享草稿的键数 / 冲突数）。
 *
 * @param value attrs 里的原始值
 * @return 有限数字；缺失或非法返回 null，页面据此省略而不是显示 0
 */
function nullableInt(value: unknown): number | null {
  if (value == null || value === '') {
    return null
  }
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : null
}

/** 评审环最终判定中文（来自 ROUTE attrs）；缺失时给保守文案 */
function criticDecisionOf(route: ObsSpan): string {
  const value = parseAttrs(route.attrsJson)['ma.critic_decision']
  if (value === 'REVISE') {
    return '最后一版为回修稿'
  }
  if (value === 'ACCEPT') {
    return '评审通过'
  }
  return '结论未知'
}

/**
 * 评审环子泳道副标题：角色来自子 AGENT span 的 {@code ma.role}。
 *
 * @param attrs 子 span attrs
 * @return 如「执行者 · 初稿」「评审 · 第 1 轮」「执行者 · 回修第 1 轮」；无法识别时为空串
 */
function criticRoleText(attrs: Record<string, unknown>): string {
  const role = attrs['ma.role']
  const roundRaw = attrs['ma.critic_round']
  const round = typeof roundRaw === 'number' ? roundRaw : Number(roundRaw)
  const safeRound = Number.isFinite(round) ? round : 0
  if (role === 'critic') {
    return safeRound > 0 ? `评审专家 · 第 ${safeRound} 轮` : '评审专家'
  }
  if (role === 'executor') {
    return safeRound > 0 ? `执行者 · 回修第 ${safeRound} 轮` : '执行者 · 出稿'
  }
  return ''
}

/** 截断长文本，用于事件里的 sub_query */
function shortText(raw: string | null | undefined, max = 36): string {
  const text = (raw || '').replace(/\s+/g, ' ').trim()
  if (!text) {
    return ''
  }
  return text.length > max ? `${text.slice(0, max)}…` : text
}

/** 步骤状态归一 */
function isRunning(span: ObsSpan): boolean {
  return span.status === 'RUNNING'
}

/** 失败判定只认 ERROR；CANCELLED 是会话取消，不当作子失败 */
function isFailed(span: ObsSpan): boolean {
  return span.status === 'ERROR'
}

/**
 * 构建编排泳道模型。
 *
 * @param spans          同一条 trace 的完整步骤（含父子关系）
 * @param pausedRouteKey 当前待批准的子计划 route_key（可空）；命中时该子泳道标「待批准」
 * @param pausedAgentId  当前待批准的子 Agent id（可空）；评审环没有 route_key，用 agentId 命中
 * @return 泳道 + 事件；没有 ROUTE 根时 hasRouterTrace=false
 */
export function buildOrchestrationLane(
  spans: ObsSpan[],
  pausedRouteKey = '',
  pausedAgentId: number | null = null
): OrchestrationLaneModel {
  const rows: OrchestrationLaneRow[] = []
  const events: OrchestrationLaneEvent[] = []
  const empty: OrchestrationLaneModel = {
    hasRouterTrace: false,
    routeSpan: null,
    template: LANE_TEMPLATE_ROUTER_PARALLEL,
    aggregate: LANE_AGGREGATE_CONCAT,
    competeWinner: '',
    competeStrategy: '',
    competeDegraded: false,
    voteWinner: '',
    voteDegraded: false,
    contextPolicy: '',
    scratchKeyCount: null,
    scratchConflictCount: null,
    rows,
    laneRows: [],
    events,
    childCount: 0,
    aggregateNote: '',
    routeKeys: []
  }
  if (!spans || !spans.length) {
    return empty
  }
  const ordered = [...spans].sort((a, b) => a.seq - b.seq || a.id - b.id)
  const byId = new Map(ordered.map((item) => [item.id, item]))
  const childrenOf = new Map<number, ObsSpan[]>()
  for (const span of ordered) {
    if (span.parentId == null) {
      continue
    }
    const list = childrenOf.get(span.parentId) ?? []
    list.push(span)
    childrenOf.set(span.parentId, list)
  }
  const kids = (id: number) => childrenOf.get(id) ?? []

  // ROUTER 的根 ROUTE（name=judge）；找不到说明这一轮不是 Router 或埋点还没落
  const route = ordered.find((span) => span.spanType === 'ROUTE') ?? null
  if (!route) {
    return empty
  }
  const attrs = parseAttrs(route.attrsJson)
  const template = typeof attrs['ma.template'] === 'string' && attrs['ma.template']
    ? (attrs['ma.template'] as string)
    : LANE_TEMPLATE_ROUTER_PARALLEL
  const decision = parseDecision(route.routeDecision)
  const routeKeys = decision.map((item) => item.routeKey).filter((key) => !!key)
  // 固定模板 / 评审环都没有判路由 LLM：总控行改叫「固定并行/固定串行/评审环」，事件也要说明「跳过判路由」
  const fixedTemplate = LANE_FIXED_TEMPLATES.includes(template as (typeof LANE_FIXED_TEMPLATES)[number])
  const criticTemplate = template === LANE_TEMPLATE_CRITIC_LOOP
  const fixedParallel = template === LANE_TEMPLATE_FIXED_PARALLEL
  // 竞争选优：无判路由、多候选同题并行，收尾是「选优」而不是拼接 / 模型汇总
  const competeTemplate = template === LANE_TEMPLATE_COMPETE
  // 路由后串行：有 JUDGE，但扇出是串行；既不能走固定模板的「无判路由」文案，也不是并行
  const routerThenSequence = template === LANE_TEMPLATE_ROUTER_THEN_SEQUENCE

  const routeKids = kids(route.id)
  const llmKids = routeKids.filter((span) => span.spanType === 'LLM')
  // 整条 trace 的全部 AGENT：只用于「子全部结束时刻」这类时间基准，不再直接铺成同级行
  const agentSpans = ordered.filter((span) => span.spanType === 'AGENT')
  /*
   * 分层口径：AGENT 的层级只由「最近的 AGENT 祖先」决定，不看它挂在哪条 LLM / ROUTE 下。
   *
   * 为什么按 AGENT 祖先而不是按 parent_id 直接判：子专家实际挂在判路由 LLM 上，
   * 方案编排根挂在外壳上，工作流节点专家挂在工作流外壳上；只有「往上第一个 AGENT」
   * 才能统一回答「这一位属于入口直接目标，还是属于某个外壳内部的专家」。
   */
  const agentAncestorOf = (span: ObsSpan): ObsSpan | null => {
    let cursor = span.parentId != null ? byId.get(span.parentId) : undefined
    // 防御脏数据成环：最多走到根
    for (let guard = 0; cursor != null && guard < ordered.length; guard += 1) {
      if (cursor.spanType === 'AGENT') {
        return cursor
      }
      cursor = cursor.parentId != null ? byId.get(cursor.parentId) : undefined
    }
    return null
  }
  // 入口 ROUTE 的直接目标：往上没有任何 AGENT 祖先（外壳 / 工作流 / 专家各算一个）
  const routeChildAgents = agentSpans.filter((span) => agentAncestorOf(span) == null)
  // 子全部结束的时刻：之后的 LLM 只可能是汇总，不可能是判路由
  let childEndMs: number | null = null
  for (const child of agentSpans) {
    const end = msOf(child.endTime)
    if (end != null) {
      childEndMs = childEndMs == null ? end : Math.max(childEndMs, end)
    }
  }
  // 判路由 LLM 通常挂子 AGENT；没有子步时按时间：晚于全部子结束的 LLM 才算汇总
  const judgeRows: ObsSpan[] = []
  const aggregateRows: ObsSpan[] = []
  for (const llm of llmKids) {
    const hasAgentChild = kids(llm.id).some((child) => child.spanType === 'AGENT')
    const startsAfterChildren = childEndMs != null && (msOf(llm.startTime) ?? 0) >= childEndMs - 50
    if (hasAgentChild || !startsAfterChildren) {
      judgeRows.push(llm)
    } else {
      aggregateRows.push(llm)
    }
  }

  const aggregate = criticTemplate
    ? LANE_AGGREGATE_NONE
    : typeof attrs['ma.aggregate'] === 'string' && attrs['ma.aggregate']
      ? (attrs['ma.aggregate'] as string)
      : (aggregateRows.length ? LANE_AGGREGATE_SUMMARIZE : LANE_AGGREGATE_CONCAT)
  const summarize = aggregate === LANE_AGGREGATE_SUMMARIZE
  // 竞争选优：终答只取赢家子正文，没有拼接也没有模型汇总调用
  const pick = aggregate === LANE_AGGREGATE_PICK
  // 投票选优：汇总策略 VOTE，票池是各成功子终答，winner 只在选举成功时有值
  const vote = aggregate === LANE_AGGREGATE_VOTE
  const voteWinner = vote && typeof attrs['ma.vote.winner'] === 'string'
    ? (attrs['ma.vote.winner'] as string).trim()
    : ''
  const voteDegraded = vote && attrs['ma.vote.degraded'] === 'true'
  const voteFailed = vote ? (nullableInt(attrs['ma.vote.fail']) ?? 0) : 0
  const competeWinner = competeTemplate && typeof attrs['ma.compete.winner'] === 'string'
    ? (attrs['ma.compete.winner'] as string).trim()
    : ''
  const competeStrategy = competeTemplate && typeof attrs['ma.compete.strategy_used'] === 'string'
    ? (attrs['ma.compete.strategy_used'] as string).trim()
    : ''
  const competeDegraded = competeTemplate && attrs['ma.compete.degraded'] === 'true'
  const competeFailed = competeTemplate ? (nullableInt(attrs['ma.compete.fail']) ?? 0) : 0

  /*
   * route_key 对齐：先看 sub_query 是否「唯一命中」，不唯一再按下标对齐。
   *
   * 为什么不能像以前那样用 Map 建 sub_query 索引：多问场景下两个子意图的 sub_query
   * 可能完全相同（「同时也按工作流跑一次」会承接上一问全文），`Map.set` 后写覆盖前写，
   * 于是所有 input_text 等于该句的 AGENT（方案外壳、工作流外壳）都被标成同一个 route_key。
   * 现在同名 sub_query 一律视为「不唯一」，退回按执行序下标对齐——route_decision 本身就是
   * 按判定序写的，下标对齐才是无损的；只有条数对不上（历史 trace / 候选被跳过）时，
   * 才接受唯一文本命中，其余留空，不硬贴一个可能错的 key。
   */
  const keyOf = (span: ObsSpan, index: number): string => {
    const text = (span.inputText || '').trim()
    const byText = text ? decision.filter((item) => item.subQuery === text) : []
    if (byText.length === 1) {
      return byText[0].routeKey
    }
    if (decision.length === routeChildAgents.length) {
      return decision[index]?.routeKey || ''
    }
    return ''
  }

  // 总控泳道：判路由并行 = ROUTE + 判路由 LLM；固定模板 = ROUTE 单条（无判路由 LLM）
  const judgeRow: OrchestrationLaneRow = {
    key: 'judge',
    kind: 'judge',
    title: (route.agentName || '').trim() || '总控',
    subtitle: criticTemplate
      ? '评审环 · 无判路由'
      : fixedTemplate
        ? `${fixedParallel ? '固定并行' : '固定串行'} · 无判路由`
        : routerThenSequence
          ? '路由后串行 · JUDGE 后串行'
          : competeTemplate
            ? '竞争选优 · 无判路由'
            : 'JUDGE · 判路由',
    spans: [route, ...judgeRows],
    running: [route, ...judgeRows].some(isRunning),
    failed: [route, ...judgeRows].some(isFailed),
    // 总控是泳道的一级行，从不参与外壳缩进
    depth: 0,
    nested: []
  }
  events.push({
    key: `route-${route.id}`,
    atMs: msOf(route.startTime),
    text: route.status === 'ERROR'
      ? `路由失败：${shortText(route.errorSummary || route.outputText, 48) || '未知原因'}`
      : criticTemplate
        ? `评审环：跳过判路由，执行者出稿 → 评审 ${criticRoundsUsed(route)} 轮（${criticDecisionOf(route)}）`
        : fixedTemplate
          ? `${fixedParallel ? '固定并行：跳过判路由，同时派给' : '固定串行：跳过判路由，按序执行'} ${
            routeKeys.length ? routeKeys.join(fixedParallel ? '、' : ' → ') : `${routeChildAgents.length} 个子`
          }`
          : routerThenSequence
            ? `路由后串行：JUDGE 判定 → ${routeKeys.length ? routeKeys.join(' → ') : `${routeChildAgents.length} 个子`}（按判定序串行）`
            : competeTemplate
              ? `竞争选优：跳过判路由，${routeKeys.length || routeChildAgents.length} 个候选同题并行（策略：${
                competeStrategy === 'FIRST_SUCCESS' ? '首个成功子' : '评选模型选优'
              }）`
              : `路由判定 → ${routeKeys.length ? routeKeys.join('、') : (shortText(route.outputText, 48) || '—')}`,
    tone: route.status === 'ERROR' ? 'danger' : 'info',
    spanIds: [route.id]
  })

  /*
   * 子泳道分层：入口 ROUTE 的直接目标各占一行；外壳内部的 AGENT 收进该外壳行的 nested，
   * 不再和外壳平铺成同级。
   *
   * 为什么必须分层：同一条 trace 里，「方案外壳」与「方案自己的编排根」都叫方案名，
   * 「工作流外壳」与「工作流里每位节点专家」也都是 AGENT。全量平铺会让「几个子 Agent」
   * 变成全 trace 的 AGENT 总数，用户看到两条同名「综合台」并排，却读不出谁在谁里面。
   */
  const innerAgentsOf = new Map<number, ObsSpan[]>()
  for (const span of agentSpans) {
    const owner = agentAncestorOf(span)
    if (owner == null) {
      continue
    }
    const list = innerAgentsOf.get(owner.id) ?? []
    list.push(span)
    innerAgentsOf.set(owner.id, list)
  }

  /**
   * 造一条子泳道行。字段口径与分层前逐字一致（扁平口径，问答框时间线与中栏卡片读它）。
   *
   * @param span        该行对应的 AGENT 步骤
   * @param routeKey    入口判路由给该目标的 route_key；内层 Agent 没有
   * @param serialIndex 串行模板下的序号（1 基）；非串行传 0
   * @param serialTotal 串行模板下的总数；非串行传 0
   */
  const childRowOf = (span: ObsSpan, routeKey: string, serialIndex: number,
                      serialTotal: number): OrchestrationLaneRow => {
    const rowAttrs = parseAttrs(span.attrsJson)
    const baseLabel = childLabel(span)
    // 子运行模式来自 span attrs（P&E 子才会带）；缺省按既有的 REACT 展示
    const runMode = rowAttrs['ma.child_run_mode'] === 'PLAN_AND_EXECUTE'
      ? '计划执行'
      : 'REACT'
    const paused = (!!pausedRouteKey && !!routeKey && routeKey === pausedRouteKey)
      // 评审环的子步骤没有 route_key（无判路由）：用 agentId 命中唯一待批槽
      || (criticTemplate && pausedAgentId != null && span.agentId === pausedAgentId)
    // 评审环：执行者 / 评审专家是串行兄弟，副标题直接写角色与轮次，避免被当成 route_key
    const criticRole = criticTemplate ? criticRoleText(rowAttrs) : ''
    // 路由后串行：把「判定序第几步」写进副标题，和并行模板的并排观感明确区分
    const serialStepText = routerThenSequence && serialTotal > 0
      ? ` · 串行第 ${serialIndex}/${serialTotal} 步`
      : ''
    // 竞争选优 / 投票选优：winner 加星标，一眼看出终答取自哪一位候选
    const winnerMark = routeKey && (
      (competeTemplate && !!competeWinner && routeKey === competeWinner)
      || (vote && !!voteWinner && routeKey === voteWinner)
    ) ? '★ 选中' : ''
    const subtitle = criticTemplate && criticRole
      ? `${criticRole}${paused ? ' · 待批准' : ''}`
      : [
        winnerMark,
        routeKey ? `${routeKey}` : '',
        `${runMode}${serialStepText}`,
        paused ? '待批准' : ''
      ].filter(Boolean).join(' · ')
    return {
      key: `child-${span.id}`,
      kind: 'child',
      title: baseLabel,
      subtitle,
      spans: [span],
      running: isRunning(span),
      failed: isFailed(span),
      depth: 0,
      nested: []
    }
  }

  /*
   * 子泳道事件：开始 / 结束；内层 Agent 用「内层」区分，不与入口目标混成同级。
   *
   * <p>事件只有一份（`events`），问答框与执行明细读到的都是同一批事实；
   * 层级直接由「是否入口直接目标」判定，不依赖某一套分组，避免两套行各推一遍事件。
   *
   * @param row         该行（扁平口径：title 不带「· 编排」，两种视图文案一致）
   * @param entryIndex  入口直接目标的 0 基下标；内层 Agent 传 -1
   */
  const pushChildEvents = (row: OrchestrationLaneRow, entryIndex: number) => {
    const span = row.spans[0]
    const label = row.title
    const isEntry = entryIndex >= 0
    const nestedTag = isEntry ? '子' : '内层'
    // 入口目标的 route_key 按判定序取；内层 Agent 不参与入口判定
    const routeKey = isEntry ? keyOf(span, entryIndex) : ''
    const sub = shortText(span.inputText)
    events.push({
      key: `child-start-${span.id}`,
      atMs: msOf(span.startTime),
      text: `${isEntry && routerThenSequence ? `串行第 ${entryIndex + 1} 步 · ` : ''}${
        routeKey ? `[${routeKey}] ` : ''
      }${nestedTag}「${label}」开始${sub ? ` · ${sub}` : ''}`,
      tone: 'info',
      spanIds: [span.id]
    })
    if (isRunning(span)) {
      return
    }
    if (isFailed(span)) {
      events.push({
        key: `child-end-${span.id}`,
        atMs: msOf(span.endTime),
        text: `${nestedTag}「${label}」失败：${shortText(span.errorSummary, 48) || '未知原因'}`,
        tone: 'danger',
        spanIds: [span.id]
      })
      return
    }
    const latency = span.latencyMs == null ? '' : `（${span.latencyMs}ms）`
    const star = row.subtitle.includes('★ 选中') ? ' · ★ 选中' : ''
    events.push({
      key: `child-end-${span.id}`,
      atMs: msOf(span.endTime),
      text: `${nestedTag}「${label}」成功${latency}${star}`,
      tone: 'ok',
      spanIds: [span.id]
    })
  }

  /*
   * 两套行都由 {childRowOf} 产出，区别只在「怎么摆」与「外壳标注」：
   * - `rows`（扁平）：入口目标 + 全部内层 Agent 按执行序平铺，标题/副标题与分层前逐字一致，
   *   问答框内的编排时间线与中栏协作卡继续读它，行数与文案都不变；
   * - `laneRows`（分层）：入口直接目标单列，内层 Agent 收进所属外壳行的 `nested`，
   *   外壳按 attrs 标「方案 / 工作流」，编排根标「· 编排」，执行明细泳道读它。
   */
  const flatChildren: OrchestrationLaneRow[] = agentSpans.map((span) => {
    const index = routeChildAgents.indexOf(span)
    // 入口直接目标才有 route_key 与串行序号；内层 Agent 不参与入口判定
    const entry = index >= 0
    return childRowOf(
      span,
      entry ? keyOf(span, index) : '',
      entry ? index + 1 : 0,
      entry ? routeChildAgents.length : 0
    )
  })

  /**
   * 泳道行：在扁平行之上补「谁在谁里面」，并按 attrs 标注外壳种类。
   *
   * <p>只在执行明细这一侧改标题与副标题，扁平行（问答框用）保持原样。
   *
   * @param span  该行对应的 AGENT 步骤
   * @param depth 层级：0 = 入口直接目标；>0 = 某个外壳内部
   * @return 泳道行（含 nested 递归）
   */
  const laneRowOf = (span: ObsSpan, depth: number): OrchestrationLaneRow => {
    const index = depth === 0 ? routeChildAgents.indexOf(span) : -1
    const base = childRowOf(
      span,
      index >= 0 ? keyOf(span, index) : '',
      index >= 0 ? index + 1 : 0,
      index >= 0 ? routeChildAgents.length : 0
    )
    const rowAttrs = parseAttrs(span.attrsJson)
    // 外壳种类与编排根标记都读后端既有 attrs，不新造字段，也不改 span 类型
    const role = typeof rowAttrs[LANE_ATTR_ROLE] === 'string' ? (rowAttrs[LANE_ATTR_ROLE] as string) : ''
    const schemeRoot = rowAttrs[LANE_ATTR_MA_ROOT] === LANE_MA_ROOT_SCHEME
    // 同名两行靠这里分开：编排根补「· 编排」；外壳副标题写「方案 / 工作流」
    const title = schemeRoot ? `${base.title} · 编排` : base.title
    const subtitle = schemeRoot
      // 编排根是容器步：只标「编排」，不写名义运行模式（REACT 会与真专家混淆）
      ? '编排'
      : role === LANE_ROLE_SCHEME && !base.subtitle.includes('方案')
        ? ['方案', base.subtitle].filter(Boolean).join(' · ')
        : role === LANE_ROLE_WORKFLOW && !base.subtitle.includes('工作流')
          ? ['工作流', base.subtitle].filter(Boolean).join(' · ')
          : base.subtitle
    return {
      ...base,
      title,
      subtitle,
      depth,
      nested: (innerAgentsOf.get(span.id) ?? []).map((inner) => laneRowOf(inner, depth + 1))
    }
  }
  const entryRows = routeChildAgents.map((span) => laneRowOf(span, 0))

  /*
   * 事件按泳道行产出：内层 Agent 的显示名要用「· 编排」那一份，
   * 否则时间线上仍会出现两条同名「综合台」，与分层后的泳道对不上。
   * 扁平 `rows` 的标题保持原样（问答框不改），事件与泳道共用同一份标注。
   */
  const laneRowBySpanId = new Map<number, OrchestrationLaneRow>()
  const indexLaneRows = (row: OrchestrationLaneRow) => {
    laneRowBySpanId.set(row.spans[0].id, row)
    row.nested.forEach(indexLaneRows)
  }
  entryRows.forEach(indexLaneRows)
  // 按执行序产出事件（外壳先开、内层再开），与右栏树一致
  const eventOrder = [...agentSpans]
    .sort((a, b) => a.seq - b.seq || a.id - b.id)
  eventOrder.forEach((span) => {
    const row = laneRowBySpanId.get(span.id)
    if (row) {
      pushChildEvents(row, routeChildAgents.indexOf(span))
    }
  })
  // 扁平视图同样按执行序排：问答框时间线与中栏卡片的阅读顺序不因分层改变
  flatChildren.sort((a, b) => a.spans[0].seq - b.spans[0].seq || a.spans[0].id - b.spans[0].id)
  // 汇总泳道：CONCAT 无模型调用，只留灰字「拼接」；SUMMARIZE 有 LLM 才画块；
  // 评审环没有汇总步骤：终答＝最后一版执行稿，评审意见默认只进时间线
  const aggregateNote = criticTemplate
    ? '评审环不做多段拼接：终答＝最后一版执行稿'
    : pick
      ? `竞争选优只取 1 份终答：winner=${competeWinner || '未标注'}${
        competeDegraded ? '（评选模型不可用，已降级为第一个成功子）' : ''
      }${competeFailed > 0 ? `；${competeFailed} 位候选失败不影响终答` : ''}`
      : vote
        ? `投票选优只取 1 份终答：winner=${voteWinner || '未标注'}${
          voteDegraded ? '（评选失败，已降级为拼接）' : ''
        }${voteFailed > 0 ? `；${voteFailed} 位子失败不进票池` : ''}`
        : summarize
          ? (aggregateRows.length ? '父模型汇总' : '未找到汇总调用，可能已降级为拼接')
          : routerThenSequence
            ? '按判定顺序串行拼接，无模型调用'
            : '按顺序拼接，无模型调用'
  const aggregateRow: OrchestrationLaneRow = {
    key: 'aggregate',
    kind: 'aggregate',
    title: criticTemplate
      ? '终答（最后一版执行稿）'
      : pick
        ? `选优（PICK${competeWinner ? ` · ${competeWinner}` : ''}）`
        : vote
          ? `投票（VOTE${voteWinner ? ` · ${voteWinner}` : ''}${voteDegraded ? ' · 降级拼接' : ''}）`
          : summarize ? '汇总（SUMMARIZE）' : '汇总（CONCAT）',
    subtitle: criticTemplate
      ? '评审环'
      : pick ? '选优' : vote ? (voteDegraded ? 'VOTE · 降级' : 'VOTE') : summarize ? 'AGGREGATE' : '拼接',
    spans: aggregateRows,
    running: aggregateRows.some(isRunning),
    failed: aggregateRows.some(isFailed),
    // 汇总行同属一级：不参与外壳缩进
    depth: 0,
    nested: []
  }
  const lastAggregate = aggregateRows[aggregateRows.length - 1]
  // 汇总事件的时间基准取「本轮最后一个结束的 AGENT」：内层专家往往晚于外壳收口，
  // 只取入口直接目标会把汇总事件的时刻排到内层专家之前，与真实墙钟不符。
  const lastChild = [...agentSpans]
    .sort((a, b) => (msOf(a.endTime) ?? 0) - (msOf(b.endTime) ?? 0))
    .at(-1)
  if (pick) {
    // 竞争选优：评选 LLM（JUDGE_PICK）挂在汇总行；FIRST_SUCCESS 没有调用，只留一行结论
    events.push({
      key: 'compete-pick',
      atMs: msOf(lastAggregate?.endTime) ?? msOf(lastChild?.endTime) ?? msOf(route.endTime),
      text: competeDegraded
        ? `选优降级：评选模型不可用，已按首个成功子出终答（winner=${competeWinner || '—'}）`
        : `选优：策略 ${competeStrategy === 'FIRST_SUCCESS' ? '首个成功子' : '评选模型选优'}，winner=${
          competeWinner || '—'
        }`,
      tone: competeDegraded ? 'warn' : (isFailed(lastAggregate ?? route) ? 'danger' : 'ok'),
      spanIds: lastAggregate ? [lastAggregate.id] : (lastChild ? [lastChild.id] : [])
    })
  } else if (vote) {
    // 投票选优：选票 LLM 挂汇总行；只有 1 路成功时没有调用，只留一行「跳过投票」
    const skipped = voteWinner && !aggregateRows.length
    events.push({
      key: 'vote-pick',
      atMs: msOf(lastAggregate?.endTime) ?? msOf(lastChild?.endTime) ?? msOf(route.endTime),
      text: voteDegraded
        ? `投票降级：评选失败，已按分段拼接出终答（选中=${voteWinner || '—'}）`
        : skipped
          ? `投票：仅 1 个成功子（winner=${voteWinner || '—'}），跳过投票直接出终答`
          : `投票：winner=${voteWinner || '—'}（终答＝该子原文，不做二次改写）`,
      tone: voteDegraded ? 'warn' : (isFailed(lastAggregate ?? route) ? 'danger' : 'ok'),
      spanIds: lastAggregate ? [lastAggregate.id] : (lastChild ? [lastChild.id] : [])
    })
  } else if (lastAggregate) {
    events.push({
      key: `aggregate-${lastAggregate.id}`,
      atMs: msOf(lastAggregate.endTime) ?? msOf(lastAggregate.startTime),
      text: isFailed(lastAggregate)
        ? `结果合成 SUMMARIZE 失败，已降级为拼接：${shortText(lastAggregate.errorSummary, 40) || '未知原因'}`
        : (isRunning(lastAggregate) ? '结果合成 SUMMARIZE 执行中…' : '结果合成 SUMMARIZE 成功'),
      tone: isFailed(lastAggregate) ? 'danger' : (isRunning(lastAggregate) ? 'info' : 'ok'),
      spanIds: [lastAggregate.id]
    })
  } else if (summarize) {
    events.push({
      key: 'aggregate-missing',
      atMs: msOf(lastChild?.endTime) ?? null,
      text: '结果合成 SUMMARIZE：未找到汇总调用，已按拼接出终答',
      tone: 'warn',
      spanIds: lastChild ? [lastChild.id] : []
    })
  } else if (criticTemplate) {
    // 评审环没有「汇总」事件：终答就是最后一版执行稿，评审意见默认不进用户答复
    events.push({
      key: 'critic-final',
      atMs: msOf(lastChild?.endTime) ?? msOf(route.endTime),
      text: '评审环：终答＝最后一版执行稿（评审意见默认只进时间线）',
      tone: 'info',
      spanIds: lastChild ? [lastChild.id] : []
    })
  } else {
    events.push({
      key: 'aggregate-concat',
      atMs: msOf(lastChild?.endTime) ?? msOf(route.endTime),
      text: fixedTemplate
        ? `结果合成 CONCAT：按${fixedParallel ? '并行' : '串行'}顺序拼接各子终答`
        : routerThenSequence
          ? '结果合成 CONCAT：按判定顺序串行拼接各子终答'
          : '结果合成 CONCAT：按路由顺序拼接各子终答',
      tone: 'info',
      spanIds: lastChild ? [lastChild.id] : []
    })
  }

  // 事件按墙钟排序；同刻保持「开始先于结束、子先于汇总」的写入次序
  const indexed = events.map((item, index) => ({ item, index }))
  indexed.sort((a, b) => {
    const ta = a.item.atMs ?? Number.POSITIVE_INFINITY
    const tb = b.item.atMs ?? Number.POSITIVE_INFINITY
    if (ta !== tb) {
      return ta - tb
    }
    return a.index - b.index
  })

  /*
   * 两份分组同一批行对象，只在 return 时组装：
   * - rows：扁平全集（总控 → 全部 AGENT 按执行序 → 汇总），问答框内时间线与中栏卡片读它；
   * - laneRows：分层（总控 → 入口直接目标，内层收在 nested → 汇总），执行明细泳道读它。
   */
  const flatRows: OrchestrationLaneRow[] = [judgeRow, ...flatChildren, aggregateRow]
  const laneRows: OrchestrationLaneRow[] = [judgeRow, ...entryRows, aggregateRow]

  return {
    hasRouterTrace: true,
    routeSpan: route,
    template,
    aggregate,
    competeWinner,
    competeStrategy,
    competeDegraded,
    voteWinner,
    voteDegraded,
    contextPolicy: typeof attrs['ma.context'] === 'string' ? (attrs['ma.context'] as string) : '',
    scratchKeyCount: nullableInt(attrs['ma.scratch.key_count']),
    scratchConflictCount: nullableInt(attrs['ma.scratch.conflict_count']),
    rows: flatRows,
    laneRows,
    events: indexed.map((entry) => entry.item),
    childCount: routeChildAgents.length,
    aggregateNote,
    routeKeys
  }
}

/**
 * 泳道状态色调：进行中 / 成功 / 失败 / 取消。
 *
 * @param span 步骤
 * @return 组件用的色调键
 */
export function laneToneOf(span: ObsSpan): 'running' | 'ok' | 'error' | 'cancelled' {
  if (span.status === 'ERROR') {
    return 'error'
  }
  if (span.status === 'CANCELLED') {
    return 'cancelled'
  }
  if (span.status === 'RUNNING') {
    return 'running'
  }
  return 'ok'
}

/** 便于测试按 id 找行 */
export { childLabel as laneChildLabel, msOf as laneTimeMs }
