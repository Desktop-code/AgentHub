/**
 * 执行链路时间轴 / 包络。所属域：obs（前端）。
 * <p>并行分组的唯一来源是 {@code traceGraphLayout.groupSiblingSpans}（按编排模板 + 执行序），
 * 本文件只做「包络 + 统一时间轴」，不再自带一套时间重叠并组，避免图与列表口径打架。
 */
import type { ObsSpan } from '@/types/obs'
import { compareSpanExecOrder } from '@/utils/executionOrder'

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

/**
 * 同父兄弟主序：step_no（真实执行号）→ seq（写入序）→ id。
 * 包络、补时序都按此序，禁用 span_type / 名称等展示字段排序。
 */
function byExecOrder(a: ObsSpan, b: ObsSpan): number {
  return compareSpanExecOrder(a, b)
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
    list.sort(byExecOrder)
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
