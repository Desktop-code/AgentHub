/**
 * Trace 拓扑「语义并组」。所属域：obs（前端展示）。
 *
 * 职责：按编排模板 + span_type + 执行序（step_no → seq → id）决定同父兄弟
 * 是串成一条链，还是扇成并排块；结果只服务 {@link TraceTopology} 的「点 + 线」图。
 *
 * 关键约束：
 * 1) 只有 ROUTER_PARALLEL / FIXED_PARALLEL 下的多个 AGENT 允许并排（同层夹着汇总 LLM 时，
 *    并排的「连续多 AGENT」也要保住，否则并行退化成串行、汇总只剩一条链尾线）；
 * 2) FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE / CRITIC_LOOP 以及模板缺失时，多 AGENT 一律串行；
 * 3) 除上述并行多 AGENT 外**全部串行**：同挂 LLM 的多个 TOOL 也按 seq/step_no 横串
 *    （用户 2026-11-04 拍板取消同轮短并）；
 * 4) 同挂在 AGENT 下的 LLM 与 TOOL 永远串行，禁止当两翼扇开；
 * 5) 汇总类（晚于全部 AGENT 结束的非 AGENT 步）统一排到所有 AGENT 之后，并带 aggregate 标记：
 *    拓扑要有分叉时画 N 路汇合，禁止当某个 AGENT 最后一步的兄弟续跑；
 * 6) 每个 AGENT 子树单独按自己的子节点分组，不用根层算法扫叶节点。
 *
 * 本文件不改 span 落库 parent_id、不改 MA 执行语义，也不参与右栏瀑布流。
 * 为便于 node:test 直接跑，这里只保留类型导入，执行序比较用本地等价实现。
 */
import type { ObsSpan } from '@/types/obs'

/** 并行模板：只有这两种模板的多 AGENT 才允许并排 */
const PARALLEL_AGENT_TEMPLATES = ['ROUTER_PARALLEL', 'FIXED_PARALLEL']

/** ROUTE 名称兜底：旧 trace 没有 ma.template 时按名字识别模板，避免固定串行被画成并行 */
const ROUTE_NAME_TEMPLATES: Array<{ keyword: string; template: string }> = [
  { keyword: 'fixed-parallel', template: 'FIXED_PARALLEL' },
  { keyword: 'fixed-sequence', template: 'FIXED_SEQUENCE' },
  { keyword: 'router-then-sequence', template: 'ROUTER_THEN_SEQUENCE' },
  { keyword: 'critic-loop', template: 'CRITIC_LOOP' }
]

/** 先后判定容差：后步在前步结束后 ε 内开工仍算串行，不并（与甘特口径一致） */
const CAUSAL_EPS_MS = 50

/** 布局上下文：整条 trace 共用一个模板判定 */
export interface TraceLayoutContext {
  /** ma.template；缺失为 null */
  template: string | null
  /** 多 AGENT 是否允许并排 */
  agentParallel: boolean
  /** 整条 trace 全部 AGENT 的最晚结束时刻；用于识别挂在 ROUTE 下的汇总 LLM */
  agentEndMs: number | null
}

/** 布局的一项：单个步骤或一个并行组 */
export type TraceLayoutItem =
  | {
      kind: 'single'
      span: ObsSpan
      /**
       * 是否为「汇总 / 收束」步：同层存在 AGENT 兄弟时，开工不早于全部 AGENT 结束。
       * <p>只供拓扑画线用：汇总步要等全部 AGENT 子树收完后单独一步进，
       * 不能挂在某个 AGENT 内部最后一次 TOOL 旁边当兄弟续跑。
       */
      aggregate?: boolean
    }
  | {
      kind: 'parallel'
      spans: ObsSpan[]
      /** 并行组墙钟跨度；仅用于与瀑布流同构，拓扑不展示 */
      wallMs: number
      parentId: number | null
    }

/**
 * 解析整条 trace 的布局上下文。
 *
 * @param spans 同一条 trace 的步骤
 * @return 模板与「多 AGENT 是否并排」
 */
export function resolveTraceLayoutContext(spans: ObsSpan[]): TraceLayoutContext {
  const template = resolveTraceTemplate(spans)
  return {
    template,
    agentParallel: template != null && PARALLEL_AGENT_TEMPLATES.includes(template),
    agentEndMs: latestAgentEndMs(spans)
  }
}

/**
 * 解析编排模板。
 * <p>优先读任意 ROUTE 的 attrs `ma.template`；没有 attrs 时按 ROUTE 名称兜底。
 * 多个 ROUTE（嵌套编排）取写入序最早、且能识别出模板的那个。
 *
 * @param spans 同一条 trace 的步骤
 * @return 模板码；无法识别返回 null（调用方按「多 AGENT 默认串」处理）
 */
export function resolveTraceTemplate(spans: ObsSpan[]): string | null {
  const routes = spans
    .filter((span) => span.spanType === 'ROUTE')
    .slice()
    .sort((a, b) => a.seq - b.seq || a.id - b.id)
  for (const route of routes) {
    const raw = parseAttrs(route.attrsJson)['ma.template']
    if (typeof raw === 'string' && raw.trim()) {
      return raw.trim().toUpperCase()
    }
  }
  // attrs 全缺（旧 trace）：用 ROUTE 名称兜底，名称也认不出则返回 null
  for (const route of routes) {
    const inferred = templateFromRouteName(route.name)
    if (inferred) {
      return inferred
    }
  }
  return null
}

/**
 * 同父兄弟分组：默认全部按执行序串成一条链。
 * <p>唯一例外是并行模板下的多个 AGENT，收成一个并行块；
 * 同挂 LLM 的多个 TOOL 不再短并（用户 2026-11-04 拍板）。
 *
 * @param siblings 同父的全部步骤（顺序不限，内部会重排）
 * @param parent   父步骤；根层传 null
 * @param ctx      整条 trace 的布局上下文
 * @return 串行 / 并行块序列
 */
export function groupSiblingSpans(
  siblings: ObsSpan[],
  parent: ObsSpan | null,
  ctx: TraceLayoutContext
): TraceLayoutItem[] {
  if (!siblings.length) {
    return []
  }
  const ordered = orderSiblings(siblings, ctx)
  // 汇总步标记：只影响拓扑画线（汇合后单独一步），不影响执行序本身
  const aggregateIds = resolveAggregateIds(ordered, ctx)
  const single = (span: ObsSpan): TraceLayoutItem =>
    toSingle(span, aggregateIds.has(span.id))

  // 多 AGENT：仅并行模板允许扇出；其余模板 / 模板缺失一律按执行序串链
  if (ordered.every(isAgent)) {
    if (ctx.agentParallel && ordered.length > 1) {
      return [parallelItem(ordered, parent?.id ?? null)]
    }
    return ordered.map(single)
  }

  // 多 TOOL：一律按 seq/step_no 横串（用户 2026-11-04 拍板，取消同轮短并）
  if (ordered.every(isTool)) {
    return ordered.map(single)
  }

  /*
   * 混合类型（同层既有 AGENT 又有汇总 LLM 等）：
   * 并行模板下的「连续多 AGENT」仍必须收成并行块，否则并行被退化成串行、
   * 汇总也只剩一条链尾线（用户手点到的「只接部分支」）。其余一律串行。
   */
  const items: TraceLayoutItem[] = []
  let agentRun: ObsSpan[] = []
  const flushAgentRun = () => {
    if (!agentRun.length) {
      return
    }
    if (agentRun.length > 1) {
      items.push(parallelItem(agentRun, parent?.id ?? null))
    } else {
      items.push(single(agentRun[0]))
    }
    agentRun = []
  }
  for (const span of ordered) {
    if (ctx.agentParallel && isAgent(span)) {
      agentRun.push(span)
      continue
    }
    flushAgentRun()
    items.push(single(span))
  }
  flushAgentRun()
  return items
}

/**
 * 判断一个跨类型兄弟是否属于「汇总类」。
 * <p>口径：父层有 AGENT 兄弟时，开始时间不早于全部 AGENT 结束（留 ε）的非 AGENT 步。
 * 用于把汇总 LLM 排到所有 AGENT 之后，避免被画在分叉前。
 *
 * @param span  待判定步骤
 * @param agentEndMs 同层 AGENT 的最晚结束时刻；没有 AGENT 时为 null
 * @return 是否按汇总类处理
 */
export function isAggregateSibling(span: ObsSpan, agentEndMs: number | null): boolean {
  if (agentEndMs == null || isAgent(span)) {
    return false
  }
  const start = parseTime(span.startTime)
  return start != null && start >= agentEndMs - CAUSAL_EPS_MS
}

/**
 * 同层 AGENT 的最晚结束时刻。
 *
 * @param siblings 同父兄弟
 * @return epoch 毫秒；没有可解析的 AGENT 时返回 null
 */
export function latestAgentEndMs(siblings: ObsSpan[]): number | null {
  let latest: number | null = null
  for (const span of siblings) {
    if (!isAgent(span)) {
      continue
    }
    const end = endMsOf(span)
    if (end != null) {
      latest = latest == null ? end : Math.max(latest, end)
    }
  }
  return latest
}

/**
 * 同层排序：执行序（step_no → seq → id），再把汇总类挪到所有 AGENT 之后。
 * <p>只调整展示顺序，不改 step_no / seq 本身。
 * 同层没有 AGENT 时（如 ROUTE 下只有判路由 LLM 与汇总 LLM）退回整条 trace 的 AGENT 结束时刻，
 * 这样汇总 LLM 仍能排在全部 AGENT 之后。
 *
 * @param siblings 同父兄弟
 * @param ctx      整条 trace 的布局上下文
 * @return 新数组
 */
function orderSiblings(siblings: ObsSpan[], ctx: TraceLayoutContext): ObsSpan[] {
  const ordered = [...siblings].sort(compareExecOrder)
  const agentEnd = resolveAgentEndMs(ordered, ctx)
  if (agentEnd == null) {
    return ordered
  }
  const normal: ObsSpan[] = []
  const aggregates: ObsSpan[] = []
  for (const span of ordered) {
    if (isAggregateSibling(span, agentEnd)) {
      aggregates.push(span)
    } else {
      normal.push(span)
    }
  }
  return aggregates.length ? [...normal, ...aggregates] : ordered
}

/**
 * 同层「汇总判定」用的 AGENT 结束时刻：优先本层 AGENT，缺失时退回整条 trace。
 *
 * @param siblings 同父兄弟
 * @param ctx      整条 trace 的布局上下文
 * @return epoch 毫秒；都取不到时 null
 */
function resolveAgentEndMs(siblings: ObsSpan[], ctx: TraceLayoutContext): number | null {
  return latestAgentEndMs(siblings) ?? ctx.agentEndMs
}

/**
 * 挑出同层的汇总 / 收束步。
 * <p>与 {@link orderSiblings} 同一口径，保证「排序到最后」和「标记为汇总」永远一致：
 * 一个步骤只要被排到 AGENT 之后，就必须按汇总画线，不能两种口径打架。
 *
 * @param siblings 已排序的同层兄弟
 * @param ctx      整条 trace 的布局上下文
 * @return 汇总步主键集合；无汇总时为空集
 */
function resolveAggregateIds(siblings: ObsSpan[], ctx: TraceLayoutContext): Set<number> {
  const ids = new Set<number>()
  const agentEnd = resolveAgentEndMs(siblings, ctx)
  if (agentEnd == null) {
    return ids
  }
  for (const span of siblings) {
    if (isAggregateSibling(span, agentEnd)) {
      ids.add(span.id)
    }
  }
  return ids
}

/**
 * 执行序比较：step_no 自然序 → seq → id。
 * <p>与 {@code utils/executionOrder.ts} 的 compareSpanExecOrder 保持一致；
 * 这里内联是为了让本模块零别名依赖、可被 node:test 直接加载。
 *
 * @param a 步骤
 * @param b 步骤
 * @return 负数表示 a 在前
 */
function compareExecOrder(a: ObsSpan, b: ObsSpan): number {
  const stepA = a.stepNo?.trim() ?? ''
  const stepB = b.stepNo?.trim() ?? ''
  if (stepA && stepB) {
    const byStep = compareStepNo(stepA, stepB)
    if (byStep !== 0) {
      return byStep
    }
  }
  if (a.seq !== b.seq) {
    return a.seq - b.seq
  }
  if (stepA !== stepB) {
    return stepA < stepB ? -1 : 1
  }
  return a.id - b.id
}

/** step_no 自然序：按点分层逐段比较数字，3 < 3.1 < 3.1.1 < 10 */
function compareStepNo(a: string, b: string): number {
  const left = a.split('.')
  const right = b.split('.')
  const len = Math.max(left.length, right.length)
  for (let i = 0; i < len; i += 1) {
    const l = left[i]
    const r = right[i]
    if (l === undefined) {
      return -1
    }
    if (r === undefined) {
      return 1
    }
    if (/^\d+$/.test(l) && /^\d+$/.test(r)) {
      const diff = Number(l) - Number(r)
      if (diff !== 0) {
        return diff
      }
      continue
    }
    if (l !== r) {
      return l < r ? -1 : 1
    }
  }
  return 0
}

/**
 * 执行链路树的索引：父 → 有序子、根列表、模板上下文。
 *
 * <p>列表区（{@code TraceSpanFlow}）与拓扑共用同一份语义分组，
 * 保证「图上怎么并、列表就怎么并」，不再各算一套。
 */
export interface TraceTreeIndex {
  /** 父 id → 子步骤（已按执行序排） */
  childMap: Map<number, ObsSpan[]>
  /** 顶层步骤（parent 缺失或指向不存在的 id） */
  roots: ObsSpan[]
  /** 整条 trace 的模板判定 */
  ctx: TraceLayoutContext
  /** id → 步骤，方便按 id 取 */
  byId: Map<number, ObsSpan>
}

/**
 * 由整条 trace 的 spans 建树索引。
 *
 * @param spans 同一条 trace 的全部步骤
 * @return 树索引；空输入返回空表
 */
export function buildTraceTree(spans: ObsSpan[]): TraceTreeIndex {
  const sorted = [...spans].sort(compareExecOrder)
  const byId = new Map(sorted.map((item) => [item.id, item]))
  const childMap = new Map<number, ObsSpan[]>()
  const roots: ObsSpan[] = []
  for (const span of sorted) {
    // parent 指向不存在的 id 时按根处理，避免脏数据让整棵子树消失
    if (span.parentId != null && byId.has(span.parentId)) {
      const list = childMap.get(span.parentId) ?? []
      list.push(span)
      childMap.set(span.parentId, list)
    } else {
      roots.push(span)
    }
  }
  return { childMap, roots, ctx: resolveTraceLayoutContext(sorted), byId }
}

/**
 * 取某个父节点下的布局块（串行单块 / 并行块）。
 *
 * @param parent   父步骤；根层传 null
 * @param index    树索引
 * @return 串行 / 并行块序列；无子节点返回空数组
 */
export function treeBlocksOf(
  parent: ObsSpan | null,
  index: TraceTreeIndex
): TraceLayoutItem[] {
  const siblings = parent == null ? index.roots : index.childMap.get(parent.id) ?? []
  return groupSiblingSpans(siblings, parent, index.ctx)
}

/**
 * 取某个步骤的全部祖先 id（不含自身）。
 *
 * <p>列表里的 AGENT 卡默认折叠，父链没展开时目标卡根本不可见，
 * 所以「图点节点 → 定位」必须先按这条链把祖先全部展开。
 *
 * @param spanId 目标步骤 id
 * @param index  树索引
 * @return 祖先 id 集合；根节点返回空集合
 */
export function ancestorIdsOf(spanId: number, index: TraceTreeIndex): Set<number> {
  const chain = new Set<number>()
  let current = index.byId.get(spanId)
  // 防御脏数据成环：最多走到根，遇到已收集的 id 立即停
  while (current?.parentId != null && !chain.has(current.parentId)) {
    const parent = index.byId.get(current.parentId)
    if (!parent) {
      break
    }
    chain.add(parent.id)
    current = parent
  }
  return chain
}

function isAgent(span: ObsSpan): boolean {
  return span.spanType === 'AGENT'
}

function isTool(span: ObsSpan): boolean {
  return span.spanType === 'TOOL'
}

/**
 * 组装单步项。
 *
 * @param span      步骤
 * @param aggregate 是否汇总 / 收束步；true 时拓扑按「汇合后单独一步」画线
 */
function toSingle(span: ObsSpan, aggregate = false): TraceLayoutItem {
  return aggregate ? { kind: 'single', span, aggregate } : { kind: 'single', span }
}

/** 组装并行块：成员按执行序排，墙钟按最早的开工到最晚的结束 */
function parallelItem(spans: ObsSpan[], parentId: number | null): TraceLayoutItem {
  const ordered = [...spans].sort(compareExecOrder)
  return {
    kind: 'parallel',
    spans: ordered,
    wallMs: wallMsOf(ordered),
    parentId
  }
}

/** 并行组墙钟跨度；缺时间时退回最大 latency */
function wallMsOf(spans: ObsSpan[]): number {
  let minStart = Number.POSITIVE_INFINITY
  let maxEnd = Number.NEGATIVE_INFINITY
  for (const span of spans) {
    const start = parseTime(span.startTime)
    const end = endMsOf(span)
    if (start == null || end == null) {
      continue
    }
    minStart = Math.min(minStart, start)
    maxEnd = Math.max(maxEnd, end)
  }
  if (Number.isFinite(minStart) && Number.isFinite(maxEnd) && maxEnd >= minStart) {
    return maxEnd - minStart
  }
  return Math.max(0, ...spans.map((span) => span.latencyMs ?? 0))
}

/** 步骤结束时刻：end_time 与 start+latency 取较晚者，补偿秒截断 */
function endMsOf(span: ObsSpan): number | null {
  const start = parseTime(span.startTime)
  let end = parseTime(span.endTime)
  if (start != null && span.latencyMs != null && span.latencyMs > 0) {
    const byLatency = start + span.latencyMs
    if (end == null || byLatency > end) {
      end = byLatency
    }
  }
  return end
}

/** 解析后端时间串；yyyy-MM-dd HH:mm:ss[.SSS] 或 ISO */
function parseTime(raw: string | null | undefined): number | null {
  if (!raw) {
    return null
  }
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T')
  const ms = Date.parse(normalized)
  return Number.isNaN(ms) ? null : ms
}

/** 解析 attrs_json；失败按「没有 attrs」处理，旁路信息不该让布局报错 */
function parseAttrs(raw: string | null | undefined): Record<string, unknown> {
  if (!raw || !raw.trim()) {
    return {}
  }
  try {
    const parsed = JSON.parse(raw) as unknown
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {}
  } catch {
    return {}
  }
}

/** 从 ROUTE 名称识别模板 */
function templateFromRouteName(name: string | null | undefined): string | null {
  const text = (name || '').toLowerCase()
  if (!text) {
    return null
  }
  for (const item of ROUTE_NAME_TEMPLATES) {
    if (text.includes(item.keyword)) {
      return item.template
    }
  }
  // 名称就是 judge：判路由并行模板（现网基线）
  return text.includes('judge') ? 'ROUTER_PARALLEL' : null
}
