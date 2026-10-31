/**
 * 执行链路并行重组。所属域：obs（前端）。
 * 树键只认 id + parentId；同父、同类型、墙钟重叠（或近起点）的兄弟才收成并行组。
 * 跨类型禁止并组；同父下先后接续（B.start ≥ A.end−ε）任意类型禁止并组。
 * 布局侧：未进并组按 seq 串成链，仅 |并组|≥2 才扇出。
 */
import type { ObsSpan } from '@/types/obs'

/** 启动时间差小于此值（ms）且同父同类型，视为同批并行 */
const BATCH_START_SLACK_MS = 120

/** 先后接续夹：后步在前步结束后 ε 内开工则视为串行，禁止并组 */
const CAUSAL_EPS_MS = 50

/** 瀑布流一项：串行单步或并行组 */
export type TraceFlowItem =
  | { kind: 'single'; span: ObsSpan }
  | {
      kind: 'parallel'
      spans: ObsSpan[]
      /** 并行段墙钟耗时 max(end)−min(start)，缺时间时回退 max(latencyMs) */
      wallMs: number
      parentId: number | null
    }

/** 整条 trace 的时间轴基准，用于甘特条定位 */
export interface TraceTimeRange {
  startMs: number
  endMs: number
  durationMs: number
}

export interface SpanGantt {
  leftPct: number
  widthPct: number
}

/** 包络后的精确时间窗（毫秒），用于甘特偏移 */
export interface SpanWindow {
  startMs: number
  endMs: number
}

export interface TraceEnvelope {
  /** latencyMs 已扩成能包住子孙的墙钟 */
  spans: ObsSpan[]
  windows: Map<number, SpanWindow>
  childrenOf: Map<number, ObsSpan[]>
  byId: Map<number, ObsSpan>
}

/** 整条链路的时间轴：最早起点，以及最晚结束减去该起点 */
export interface GanttAxis {
  baseStartMs: number
  totalDuration: number
}

/** 单个节点在统一时间轴上的一条纯色进度条 */
export interface SpanBar {
  leftPct: number
  widthPct: number
  /** ERROR 红；latency>3000 橙；其余绿 */
  tone: 'ok' | 'warn' | 'danger'
  tip: string
}

/** 展示号优先 stepNo，否则 seq；排序次键 */
function stepLabel(span: ObsSpan): string {
  return span.stepNo && span.stepNo.trim() ? span.stepNo : String(span.seq)
}

/**
 * 同父兄弟主序：seq → step_no → id。
 * 未进并组时布局严格按此序串成链。
 */
function bySeqThenId(a: ObsSpan, b: ObsSpan): number {
  if (a.seq !== b.seq) {
    return a.seq - b.seq
  }
  const stepA = a.stepNo?.trim() ?? ''
  const stepB = b.stepNo?.trim() ?? ''
  if (stepA !== stepB) {
    return stepA < stepB ? -1 : 1
  }
  return a.id - b.id
}

/**
 * 将 spans 重组为串行项 + 并行组，保持 seq 顺序。
 * 仅沿「允许成对」边做传递闭包；组 ≥2 → 并行容器。
 *
 * @param spans 后端按 seq 返回的步骤
 */
export function buildTraceFlow(spans: ObsSpan[]): TraceFlowItem[] {
  if (!spans.length) {
    return []
  }
  const sorted = [...spans].sort(bySeqThenId)
  const used = new Set<number>()
  const items: TraceFlowItem[] = []

  for (const seed of sorted) {
    if (used.has(seed.id)) {
      continue
    }
    // 同父兄弟中，与已入组任一节点「可并」则并入（传递闭包）
    const cluster: ObsSpan[] = [seed]
    used.add(seed.id)
    let grew = true
    while (grew) {
      grew = false
      for (const candidate of sorted) {
        if (used.has(candidate.id)) {
          continue
        }
        if (!sameParent(seed, candidate)) {
          continue
        }
        if (cluster.some((member) => isParallelPair(member, candidate))) {
          cluster.push(candidate)
          used.add(candidate.id)
          grew = true
        }
      }
    }
    cluster.sort(bySeqThenId)

    if (cluster.length >= 2) {
      items.push({
        kind: 'parallel',
        spans: cluster,
        wallMs: parallelWallMs(cluster),
        parentId: seed.parentId
      })
    } else {
      items.push({ kind: 'single', span: seed })
    }
  }

  return items
}

/**
 * 计算整条链路时间范围；无有效时间时返回 null（不画甘特）。
 */
export function computeTraceRange(spans: ObsSpan[]): TraceTimeRange | null {
  let start = Number.POSITIVE_INFINITY
  let end = Number.NEGATIVE_INFINITY
  for (const span of spans) {
    const iv = spanInterval(span)
    if (!iv) {
      continue
    }
    start = Math.min(start, iv.start)
    end = Math.max(end, iv.end)
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    return null
  }
  return { startMs: start, endMs: end, durationMs: end - start }
}

/**
 * 单步在总时长中的位置与宽度（百分比）。
 * @returns 无时间基准时 null
 */
export function spanGantt(span: ObsSpan, range: TraceTimeRange | null): SpanGantt | null {
  if (!range || range.durationMs <= 0) {
    return null
  }
  const iv = spanInterval(span)
  if (!iv) {
    return null
  }
  const left = ((iv.start - range.startMs) / range.durationMs) * 100
  const width = (Math.max(iv.end - iv.start, 1) / range.durationMs) * 100
  return {
    leftPct: clamp(left, 0, 100),
    widthPct: clamp(width, 0.4, 100 - clamp(left, 0, 100))
  }
}

function sameParent(a: ObsSpan, b: ObsSpan): boolean {
  // 双方都无父也算「同层」；父子永不走此路径（parent 不同）
  return (a.parentId ?? null) === (b.parentId ?? null)
}

/**
 * 并行成对：须同父、同 span_type，且（时间窗相交 ∨ |Δstart|≤120ms），
 * 并排除同父先后接续（任意 span_type：B.start ≥ A.end−ε）。
 */
function isParallelPair(a: ObsSpan, b: ObsSpan): boolean {
  if (!sameParent(a, b) || a.id === b.id) {
    return false
  }
  // 类型夹：仅同类型可并（挡住路由 LLM∥子 AGENT、出 call 的 LLM∥TOOL）
  if (!sameSpanType(a, b)) {
    return false
  }
  // 先后夹：通用串行（含同型 LLM→…→LLM、原 LLM→TOOL）；秒截断取较早结束以免假重叠
  if (isTemporallySequential(a, b) || isTemporallySequential(b, a)) {
    return false
  }

  const ia = pairInterval(a)
  const ib = pairInterval(b)
  if (!ia || !ib) {
    // 缺有效时间则不可并；禁止 start_time 原始字符串全等兜底
    return false
  }
  // a) 时间窗相交（半开：端点相接不算并行）——真并行两子 AGENT / 同轮多 TOOL
  if (ia.start < ib.end && ib.start < ia.end) {
    return true
  }
  // b) 双方都能算出有效时间，且 |Δstart| ≤ 120ms
  if (Math.abs(ia.start - ib.start) <= BATCH_START_SLACK_MS) {
    return true
  }
  return false
}

/** 同 span_type（含皆空视为同型，避免无类型噪声互并时误伤） */
function sameSpanType(a: ObsSpan, b: ObsSpan): boolean {
  return (a.spanType ?? '') === (b.spanType ?? '')
}

/**
 * 同父先后：B 在 A 结束后才开或几乎接上（ε=50ms）→ 禁止并组。
 * 结束时刻取 end_time 与 start+latency 中较早者，减轻 datetime 秒截断把串行判成重叠。
 */
function isTemporallySequential(a: ObsSpan, b: ObsSpan): boolean {
  const aStart = parseTime(a.startTime)
  const bStart = parseTime(b.startTime)
  if (aStart == null || bStart == null) {
    return false
  }
  const ends: number[] = []
  const fromEnd = parseTime(a.endTime)
  if (fromEnd != null) {
    ends.push(fromEnd)
  }
  if (a.latencyMs != null && a.latencyMs > 0) {
    ends.push(aStart + a.latencyMs)
  }
  if (!ends.length) {
    return false
  }
  // 取较早结束：截断 end_time 偏早时仍能认出「B 接在 A 后」
  const aEndEarly = Math.min(...ends)
  return bStart >= aEndEarly - CAUSAL_EPS_MS
}

/**
 * 并组成对用的时间窗：end 优先 end_time，否则 start+latency_ms。
 * 与甘特 rawWindow（可取较晚者）刻意分开，避免秒截断把因果步并成并行。
 */
function pairInterval(span: ObsSpan): { start: number; end: number } | null {
  const start = parseTime(span.startTime)
  if (start == null) {
    return null
  }
  const fromEnd = parseTime(span.endTime)
  let end: number | null = fromEnd
  if (end == null && span.latencyMs != null && span.latencyMs > 0) {
    end = start + span.latencyMs
  }
  if (end == null || end < start) {
    end = start
  }
  return { start, end }
}

function parallelWallMs(cluster: ObsSpan[]): number {
  let minStart = Number.POSITIVE_INFINITY
  let maxEnd = Number.NEGATIVE_INFINITY
  let any = false
  for (const span of cluster) {
    const iv = pairInterval(span)
    if (!iv) {
      continue
    }
    any = true
    minStart = Math.min(minStart, iv.start)
    maxEnd = Math.max(maxEnd, iv.end)
  }
  if (any && maxEnd >= minStart) {
    return Math.max(0, maxEnd - minStart)
  }
  return Math.max(0, ...cluster.map((s) => s.latencyMs ?? 0))
}

/**
 * 父区间扩到包住全部子孙。datetime 常无毫秒，结束时刻取 end 与 start+latency 的较晚者。
 * 返回拷贝，不改入参。树只认 id + parentId。
 */
export function envelopSpans(input: ObsSpan[]): TraceEnvelope {
  const spans = input.map((item) => ({ ...item }))
  const byId = new Map(spans.map((item) => [item.id, item]))
  const childrenOf = new Map<number, ObsSpan[]>()
  const roots: ObsSpan[] = []
  for (const span of spans) {
    const parentId = span.parentId
    if (parentId != null && byId.has(parentId)) {
      const list = childrenOf.get(parentId) ?? []
      list.push(span)
      childrenOf.set(parentId, list)
    } else {
      roots.push(span)
    }
  }
  for (const list of childrenOf.values()) {
    list.sort(bySeqThenId)
  }
  const windows = new Map<number, SpanWindow>()
  const walk = (span: ObsSpan) => {
    for (const child of childrenOf.get(span.id) ?? []) {
      walk(child)
    }
    let window = rawWindow(span)
    for (const child of childrenOf.get(span.id) ?? []) {
      const childWindow = windows.get(child.id)
      if (!childWindow) {
        continue
      }
      window = window
        ? {
            startMs: Math.min(window.startMs, childWindow.startMs),
            endMs: Math.max(window.endMs, childWindow.endMs)
          }
        : { ...childWindow }
    }
    if (window) {
      windows.set(span.id, window)
      span.latencyMs = Math.max(0, Math.round(window.endMs - window.startMs))
    }
  }
  for (const root of roots) {
    walk(root)
  }
  return { spans, windows, childrenOf, byId }
}

/**
 * 统一时间轴。baseStartTime 取全部 start_time 的最小值；
 * totalDuration 取全部 (start_time + latency_ms) 的最大值减去 baseStartTime。
 */
export function ganttAxis(spans: ObsSpan[]): GanttAxis | null {
  let baseStartMs = Number.POSITIVE_INFINITY
  let maxEnd = Number.NEGATIVE_INFINITY
  for (const span of spans) {
    const start = parseTime(span.startTime)
    if (start == null) {
      continue
    }
    const latency = Math.max(0, span.latencyMs ?? 0)
    baseStartMs = Math.min(baseStartMs, start)
    maxEnd = Math.max(maxEnd, start + latency)
  }
  const totalDuration = maxEnd - baseStartMs
  if (!Number.isFinite(baseStartMs) || totalDuration <= 0) {
    return null
  }
  return { baseStartMs, totalDuration }
}

/**
 * 当前节点相对整条链路的偏移和宽度。
 * left = (start - baseStartTime) / totalDuration * 100
 * width = latency_ms / totalDuration * 100
 */
export function spanBar(span: ObsSpan, axis: GanttAxis): SpanBar | null {
  const start = parseTime(span.startTime)
  if (start == null || axis.totalDuration <= 0) {
    return null
  }
  const latency = Math.max(0, span.latencyMs ?? 0)
  const leftPct = ((start - axis.baseStartMs) / axis.totalDuration) * 100
  const widthPct = (latency / axis.totalDuration) * 100
  return {
    leftPct,
    widthPct,
    tone: barTone(span),
    tip: `${formatMs(start)} ~ ${formatMs(start + latency)} (${latency}ms)`
  }
}

/** ERROR 纯红，耗时超过 3000ms 纯橙，其余纯绿。 */
function barTone(span: ObsSpan): SpanBar['tone'] {
  if (span.status === 'ERROR') {
    return 'danger'
  }
  if ((span.latencyMs ?? 0) > 3000) {
    return 'warn'
  }
  return 'ok'
}

function formatMs(ms: number): string {
  const date = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  const frac = String(date.getMilliseconds()).padStart(3, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}.${frac}`
}

/**
 * 甘特/包络用：end 取 end_time 与 start+latency 的较晚者（补偿秒截断）。
 */
function rawWindow(span: ObsSpan): SpanWindow | null {
  const start = parseTime(span.startTime)
  if (start == null) {
    return null
  }
  let end = parseTime(span.endTime)
  if (span.latencyMs != null && span.latencyMs > 0) {
    const byLatency = start + span.latencyMs
    if (end == null || byLatency > end) {
      end = byLatency
    }
  }
  if (end == null || end < start) {
    end = start
  }
  return { startMs: start, endMs: end }
}

/** 解析 start/end；若 latency 比截断后的 end 更晚，以 latency 为准。 */
export function spanInterval(span: ObsSpan): { start: number; end: number } | null {
  const window = rawWindow(span)
  return window ? { start: window.startMs, end: window.endMs } : null
}

function parseTime(raw: string | null | undefined): number | null {
  if (!raw) {
    return null
  }
  // 后端多为 yyyy-MM-dd HH:mm:ss[.SSS]，Date.parse 需 T
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T')
  const ms = Date.parse(normalized)
  return Number.isNaN(ms) ? null : ms
}

function clamp(n: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, n))
}

export { stepLabel }
