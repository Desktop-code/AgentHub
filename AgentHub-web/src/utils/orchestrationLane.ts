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
 * - 五种模板：ROUTER_PARALLEL 有判路由 LLM；FIXED_SEQUENCE / FIXED_PARALLEL 无判路由 LLM，
 *   总控行只显示「无判路由 + 参与者」短条；CRITIC_LOOP 也无判路由，执行者 / 评审专家串行兄弟；
 *   ROUTER_THEN_SEQUENCE 有 JUDGE，但命中子按判定序串行，子块时间窗天然不重叠。
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
/** 固定模板集合：用于决定总控行是否画「判路由」 */
export const LANE_FIXED_TEMPLATES = [LANE_TEMPLATE_FIXED_SEQUENCE, LANE_TEMPLATE_FIXED_PARALLEL]
/** 汇总：按序拼接 */
export const LANE_AGGREGATE_CONCAT = 'CONCAT'
/** 汇总：父模型整合 */
export const LANE_AGGREGATE_SUMMARIZE = 'SUMMARIZE'
/** 汇总：评审环无汇总步骤（终答＝最后一版执行稿） */
export const LANE_AGGREGATE_NONE = 'NONE'
/** 上下文策略：本轮子 Agent 共享键控草稿区（由 ROUTE attrs ma.context 识别） */
export const LANE_CONTEXT_SHARED_SCRATCH = 'SHARED_SCRATCH'

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
  /** 上下文策略；来源 ROUTE attrs 的 ma.context，缺失为空串（旧 trace 不回填） */
  contextPolicy: string
  /** 共享草稿区已写入的键数；只有本轮选了 SHARED_SCRATCH 且后端打过点才有值 */
  scratchKeyCount: number | null
  /** 共享草稿区被拒绝的同键写入次数；无则 null */
  scratchConflictCount: number | null
  /** 行顺序固定：总控 → 各子（判路由顺序）→ 汇总 */
  rows: OrchestrationLaneRow[]
  /** 中文事件，时间升序 */
  events: OrchestrationLaneEvent[]
  /** 参与执行的子 Agent 数 */
  childCount: number
  /** 汇总说明，直接展示 */
  aggregateNote: string
  /** 判路由给出的 route_key，按判定顺序 */
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
    contextPolicy: '',
    scratchKeyCount: null,
    scratchConflictCount: null,
    rows,
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
  // 路由后串行：有 JUDGE，但扇出是串行；既不能走固定模板的「无判路由」文案，也不是并行
  const routerThenSequence = template === LANE_TEMPLATE_ROUTER_THEN_SEQUENCE

  const routeKids = kids(route.id)
  const llmKids = routeKids.filter((span) => span.spanType === 'LLM')
  const childSpans = ordered.filter((span) => span.spanType === 'AGENT')
  // 子全部结束的时刻：之后的 LLM 只可能是汇总，不可能是判路由
  let childEndMs: number | null = null
  for (const child of childSpans) {
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

  // route_key：优先用 sub_query 与子 AGENT span 的 inputText 精确对齐；对齐不上再按判定顺序兜底
  const keyBySub = new Map<string, string>()
  for (const item of decision) {
    if (item.subQuery && item.routeKey) {
      keyBySub.set(item.subQuery, item.routeKey)
    }
  }
  const keyOf = (span: ObsSpan, index: number): string => {
    // 固定模板的所有子吃同一份用户原问，按 sub_query 对齐会全部命中同一条，必须按序对齐
    if (fixedTemplate) {
      return decision[index]?.routeKey || ''
    }
    const matched = keyBySub.get((span.inputText || '').trim())
    if (matched) {
      return matched
    }
    if (decision.length === childSpans.length) {
      return decision[index]?.routeKey || ''
    }
    return ''
  }

  // 总控泳道：判路由并行 = ROUTE + 判路由 LLM；固定模板 = ROUTE 单条（无判路由 LLM）
  rows.push({
    key: 'judge',
    kind: 'judge',
    title: (route.agentName || '').trim() || '总控',
    subtitle: criticTemplate
      ? '评审环 · 无判路由'
      : fixedTemplate
        ? `${fixedParallel ? '固定并行' : '固定串行'} · 无判路由`
        : routerThenSequence
          ? '路由后串行 · JUDGE 后串行'
          : 'JUDGE · 判路由',
    spans: [route, ...judgeRows],
    running: [route, ...judgeRows].some(isRunning),
    failed: [route, ...judgeRows].some(isFailed)
  })
  events.push({
    key: `route-${route.id}`,
    atMs: msOf(route.startTime),
    text: route.status === 'ERROR'
      ? `路由失败：${shortText(route.errorSummary || route.outputText, 48) || '未知原因'}`
      : criticTemplate
        ? `评审环：跳过判路由，执行者出稿 → 评审 ${criticRoundsUsed(route)} 轮（${criticDecisionOf(route)}）`
        : fixedTemplate
          ? `${fixedParallel ? '固定并行：跳过判路由，同时派给' : '固定串行：跳过判路由，按序执行'} ${
            routeKeys.length ? routeKeys.join(fixedParallel ? '、' : ' → ') : `${childSpans.length} 个子`
          }`
          : routerThenSequence
            ? `路由后串行：JUDGE 判定 → ${routeKeys.length ? routeKeys.join(' → ') : `${childSpans.length} 个子`}（按判定序串行）`
            : `路由判定 → ${routeKeys.length ? routeKeys.join('、') : (shortText(route.outputText, 48) || '—')}`,
    tone: route.status === 'ERROR' ? 'danger' : 'info',
    spanIds: [route.id]
  })

  // 子泳道：按步骤顺序一行一个；时间窗并排即可看出并行，不画成假串行瀑布
  childSpans.forEach((span, index) => {
    const attrs = parseAttrs(span.attrsJson)
    const routeKey = keyOf(span, index)
    const label = childLabel(span)
    // 子运行模式来自 span attrs（P&E 子才会带）；缺省按刀3 前的 REACT 展示
    const runMode = attrs['ma.child_run_mode'] === 'PLAN_AND_EXECUTE'
      ? '计划执行'
      : 'REACT'
    const paused = !!pausedRouteKey && !!routeKey && routeKey === pausedRouteKey
      // 评审环的子步骤没有 route_key（无判路由）：用 agentId 命中唯一待批槽
      || (criticTemplate && pausedAgentId != null && span.agentId === pausedAgentId)
    // 评审环：执行者 / 评审专家是串行兄弟，副标题直接写角色与轮次，避免被当成 route_key
    const criticRole = criticTemplate ? criticRoleText(attrs) : ''
    // 路由后串行：把「判定序第几步」写进副标题，和并行模板的并排观感明确区分
    const serialStepText = routerThenSequence ? ` · 串行第 ${index + 1}/${childSpans.length} 步` : ''
    const subtitle = criticTemplate && criticRole
      ? `${criticRole}${paused ? ' · 待批准' : ''}`
      : `${routeKey ? `${routeKey} · ` : ''}${runMode}${serialStepText}${paused ? ' · 待批准' : ''}`
    rows.push({
      key: `child-${span.id}`,
      kind: 'child',
      title: label,
      subtitle,
      spans: [span],
      running: isRunning(span),
      failed: isFailed(span)
    })
    const sub = shortText(span.inputText)
    events.push({
      key: `child-start-${span.id}`,
      atMs: msOf(span.startTime),
      text: `${routerThenSequence ? `串行第 ${index + 1} 步 · ` : ''}${
        routeKey ? `[${routeKey}] ` : ''
      }子「${label}」开始${sub ? ` · ${sub}` : ''}`,
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
        text: `子「${label}」失败：${shortText(span.errorSummary, 48) || '未知原因'}`,
        tone: 'danger',
        spanIds: [span.id]
      })
      return
    }
    const latency = span.latencyMs == null ? '' : `（${span.latencyMs}ms）`
    events.push({
      key: `child-end-${span.id}`,
      atMs: msOf(span.endTime),
      text: `子「${label}」成功${latency}`,
      tone: 'ok',
      spanIds: [span.id]
    })
  })

  // 汇总泳道：CONCAT 无模型调用，只留灰字「拼接」；SUMMARIZE 有 LLM 才画块；
  // 评审环没有汇总步骤：终答＝最后一版执行稿，评审意见默认只进时间线
  const aggregateNote = criticTemplate
    ? '评审环不做多段拼接：终答＝最后一版执行稿'
    : summarize
      ? (aggregateRows.length ? '父模型汇总' : '未找到汇总调用，可能已降级为拼接')
      : routerThenSequence
        ? '按判定顺序串行拼接，无模型调用'
        : '按顺序拼接，无模型调用'
  rows.push({
    key: 'aggregate',
    kind: 'aggregate',
    title: criticTemplate ? '终答（最后一版执行稿）' : summarize ? '汇总（SUMMARIZE）' : '汇总（CONCAT）',
    subtitle: criticTemplate ? '评审环' : summarize ? 'AGGREGATE' : '拼接',
    spans: aggregateRows,
    running: aggregateRows.some(isRunning),
    failed: aggregateRows.some(isFailed)
  })
  const lastAggregate = aggregateRows[aggregateRows.length - 1]
  const lastChild = childSpans[childSpans.length - 1]
  if (lastAggregate) {
    events.push({
      key: `aggregate-${lastAggregate.id}`,
      atMs: msOf(lastAggregate.endTime) ?? msOf(lastAggregate.startTime),
      text: isFailed(lastAggregate)
        ? `汇总策略 SUMMARIZE 失败，已降级为拼接：${shortText(lastAggregate.errorSummary, 40) || '未知原因'}`
        : (isRunning(lastAggregate) ? '汇总策略 SUMMARIZE 执行中…' : '汇总策略 SUMMARIZE 成功'),
      tone: isFailed(lastAggregate) ? 'danger' : (isRunning(lastAggregate) ? 'info' : 'ok'),
      spanIds: [lastAggregate.id]
    })
  } else if (summarize) {
    events.push({
      key: 'aggregate-missing',
      atMs: msOf(lastChild?.endTime) ?? null,
      text: '汇总策略 SUMMARIZE：未找到汇总调用，已按拼接出终答',
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
        ? `汇总策略 CONCAT：按${fixedParallel ? '并行' : '串行'}顺序拼接各子终答`
        : routerThenSequence
          ? '汇总策略 CONCAT：按判定顺序串行拼接各子终答'
          : '汇总策略 CONCAT：按路由顺序拼接各子终答',
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

  return {
    hasRouterTrace: true,
    routeSpan: route,
    template,
    aggregate,
    contextPolicy: typeof attrs['ma.context'] === 'string' ? (attrs['ma.context'] as string) : '',
    scratchKeyCount: nullableInt(attrs['ma.scratch.key_count']),
    scratchConflictCount: nullableInt(attrs['ma.scratch.conflict_count']),
    rows,
    events: indexed.map((entry) => entry.item),
    childCount: childSpans.length,
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
