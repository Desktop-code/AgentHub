/**
 * 知识库检索中间态（Trace 子步）的前端展示模型。所属域：obs（前端展示）。
 *
 * <p>职责：把 {@code parentId = 知识库 TOOL} 的 RETRIEVE 子 span 整理成「抽屉里自上而下」的
 * 分段结构，并把命中明细正文解析成片段表。只读现有 span 数据，不改埋点口径、不改后端。
 *
 * <p>关键约束：
 * 1) 步骤顺序以**执行序**（step_no → seq → id）为真源：后端按 tool.input → rewrite →
 *    每库 recall → 合并后 rerank → tool.output 顺序写入，按执行序排出来就是链路顺序；
 *    仅在同一段内部再用稳定步骤名排序，抵消个别乱序写入。
 * 2) 多库时每库一组（vector / bm25 / rrf / 该库 rerank 落在同一段），run 级步骤各自成段。
 * 3) 片段表按后端 {@code RagObsSpans.formatHits} 的文本格式解析；解析不到时表格不渲染，
 *    原文仍在「出参」里可见，展示层不做二次编造。
 */
import type { ObsSpan } from '@/types/obs'

/** 检索链路的稳定步骤名：顺序即抽屉里的期望顺序 */
export const RETRIEVE_STEP_ORDER = [
  'tool.input',
  'rewrite',
  'retrieve.vector',
  'retrieve.bm25',
  'rrf',
  'rerank',
  'tool.output'
] as const

/** 检索子步名（后端写死的七个） */
export type RetrieveStepName = (typeof RETRIEVE_STEP_ORDER)[number]

/** 步骤元信息：标题 + 一句话说明，供抽屉标题行使用 */
export interface RetrieveStepMeta {
  /** 中文标题 */
  title: string
  /** 这步做什么；写「为什么看它」而不是复述步骤名 */
  hint: string
}

/** 七个步骤的中文口径；未知步骤名走兜底，不隐藏 */
const STEP_META: Record<string, RetrieveStepMeta> = {
  'tool.input': { title: '工具入参', hint: '本次检索的原始问题、库范围与改写/重排开关' },
  rewrite: { title: '查询改写', hint: '改写前后 query；未开启时只留跳过原因' },
  'retrieve.vector': { title: '向量召回', hint: '向量相似度召回的片段与分数' },
  'retrieve.bm25': { title: 'BM25 召回', hint: '关键词召回的片段与分数；单路检索时跳过' },
  rrf: { title: 'RRF 融合', hint: '双路融合分与排序后列表' },
  rerank: { title: '精排', hint: '重排分与最终进入上下文的片段' },
  'tool.output': { title: '工具出参', hint: '返回给 Agent 的正文（与工具真实返回一致）' }
}

/** 跳过原因的机器码 → 人话；未知码原样展示，便于发现后端新增口径 */
const SKIP_REASONS: Record<string, string> = {
  rewrite_disabled: '未开启查询改写',
  rewrite_not_executed: '改写已开，但运行时未执行',
  rrf_disabled: '未开启双路召回',
  provider_no_bm25: '向量库不支持 BM25',
  bm25_empty: 'BM25 无命中，保留向量结果',
  empty_bm25: 'BM25 零命中',
  empty_vector: '向量零命中',
  all_filtered_by_threshold: '召回结果全部被分数阈值过滤',
  empty_fusion: '融合后为空',
  rerank_disabled: '未开启精排',
  rerank_model_missing: '精排已开但未绑定模型',
  rerank_client_missing: '精排客户端不可用',
  no_candidates: '没有可重排的候选'
}

/** 检索子步的 attrs 摘要：后端用 observe 侧扁平键写入 */
export interface RetrieveStepAttrs {
  /** 人话摘要 */
  brief: string | null
  /** 本步是否被跳过 */
  skipped: boolean
  /** 跳过原因机器码 */
  skipReason: string | null
  /** 零命中原因 */
  emptyReason: string | null
  /** 命中条数 */
  hitCount: number | null
  /** 过滤前原始条数 */
  rawCount: number | null
  /** tool.output 的结果词：ok / no_hit / no_binding / unavailable / error */
  outcome: string | null
}

/** 片段表一行；列名与评测检索链口径一致 */
export interface ChunkRow {
  /** 展示序号（后端输出的行号） */
  seq: string
  chunkId: string
  docId: string
  kbId: string
  score: string
  vector: string
  bm25: string
  rrf: string
  rerank: string
  hitBy: string
  title: string
  snippet: string
}

/** 抽屉里的一段：run 级步骤自成一段，多库按库分段 */
export interface RetrieveSegment {
  /** 段稳定键：库号 + 首步 id，避免 Vue 复用错行 */
  key: string
  /** 所属知识库；run 级步骤为 null */
  kbId: number | null
  /** 段内步骤，已按稳定步骤名排序 */
  steps: ObsSpan[]
}

/** 是否为检索子步 */
export function isRetrieveSpan(span: ObsSpan): boolean {
  return span.spanType === 'RETRIEVE'
}

/**
 * 取挂在某个 TOOL 下的检索子步。
 *
 * @param toolSpan TOOL 步骤
 * @param childMap 父 id → 子步骤（trace 树索引）
 * @return 按执行序排好的检索子步；没有则空数组
 */
export function retrieveStepsOf(
  toolSpan: ObsSpan,
  childMap: Map<number, ObsSpan[]>
): ObsSpan[] {
  const kids = childMap.get(toolSpan.id) ?? []
  return kids.filter(isRetrieveSpan).slice().sort(compareExecOrder)
}

/**
 * 稳定步骤名序号：tool.input=0 … tool.output=6；未知排最后。
 *
 * @param name 步骤名
 * @return 序号
 */
export function retrieveStepRank(name: string | null | undefined): number {
  const index = RETRIEVE_STEP_ORDER.indexOf((name ?? '').trim() as RetrieveStepName)
  return index < 0 ? RETRIEVE_STEP_ORDER.length : index
}

/**
 * 步骤中文标题。
 *
 * @param name 步骤名
 * @return 中文标题；未知步骤名原样返回
 */
export function retrieveStepTitle(name: string | null | undefined): string {
  const key = (name ?? '').trim()
  return STEP_META[key]?.title ?? (key || '检索步骤')
}

/**
 * 步骤说明。
 *
 * @param name 步骤名
 * @return 一句话说明；未知步骤名为空串
 */
export function retrieveStepHint(name: string | null | undefined): string {
  return STEP_META[(name ?? '').trim()]?.hint ?? ''
}

/**
 * 跳过原因人话。
 *
 * @param reason 机器码
 * @return 人话；未知码原样返回，空值返回空串
 */
export function skipReasonText(reason: string | null | undefined): string {
  const key = (reason ?? '').trim()
  if (!key) {
    return ''
  }
  return SKIP_REASONS[key] ?? key
}

/**
 * 解析 attrs_json 里的检索摘要字段。解析失败按「没有 attrs」处理。
 *
 * @param attrsJson attrs 原文
 * @return 结构化摘要
 */
export function retrieveAttrs(attrsJson: string | null | undefined): RetrieveStepAttrs {
  const raw = parseObject(attrsJson)
  return {
    brief: text(raw, 'brief'),
    skipped: raw['skipped'] === true,
    skipReason: text(raw, 'skipReason'),
    emptyReason: text(raw, 'emptyReason'),
    hitCount: num(raw, 'hitCount'),
    rawCount: num(raw, 'rawCount'),
    outcome: text(raw, 'outcome')
  }
}

/** 片段行：chunkId / 四类分数 + 摘要，缺列显示 - */
const CHUNK_LINE =
  /^\s*(\d+)\.\s+chunkId=(\S+)\s+docId=(\S+)\s+kbId=(\S+)\s+score=(\S+)\s+vector=(\S+)\s+bm25=(\S+)\s+rrf=(\S+)\s+rerank=(\S+)\s+hitBy=(\S+)\s*$/

/**
 * 从子步出参正文解析片段表。
 * <p>只认后端 formatHits 的固定行格式；一行都解析不到时返回空数组，让调用方退回原文展示。
 *
 * @param outputText 子步 output 正文
 * @return 片段行；顺序与后端输出一致
 */
export function parseChunkRows(outputText: string | null | undefined): ChunkRow[] {
  if (!outputText || !outputText.trim()) {
    return []
  }
  const rows: ChunkRow[] = []
  let current: ChunkRow | null = null
  for (const line of outputText.split('\n')) {
    const hit = CHUNK_LINE.exec(line)
    if (hit) {
      current = {
        seq: hit[1],
        chunkId: hit[2],
        docId: hit[3],
        kbId: hit[4],
        score: hit[5],
        vector: hit[6],
        bm25: hit[7],
        rrf: hit[8],
        rerank: hit[9],
        hitBy: hit[10],
        title: '',
        snippet: ''
      }
      rows.push(current)
      continue
    }
    if (!current) {
      continue
    }
    // title / snippet 是片段行的续行，缩进两格；其它行结束当前片段的附属信息
    const title = /^\s{2,}title:\s?(.*)$/.exec(line)
    if (title) {
      current.title = title[1].trim()
      continue
    }
    const snippet = /^\s{2,}snippet:\s?(.*)$/.exec(line)
    if (snippet) {
      current.snippet = snippet[1].trim()
    }
  }
  return rows
}

/**
 * 把检索子步切成抽屉里的段：run 级步骤各自成段，同库的连续步骤合成一段。
 *
 * @param spans 检索子步（顺序不限）
 * @return 分段结果，顺序即执行顺序
 */
export function segmentRetrieveSteps(spans: ObsSpan[]): RetrieveSegment[] {
  const ordered = [...spans].filter(isRetrieveSpan).sort(compareExecOrder)
  const segments: RetrieveSegment[] = []
  for (const span of ordered) {
    const kbId = span.retrieveKbId ?? null
    const last = segments[segments.length - 1]
    if (kbId != null && last != null && last.kbId === kbId) {
      last.steps.push(span)
      continue
    }
    segments.push({
      key: `${kbId ?? 'run'}-${span.id}`,
      kbId,
      steps: [span]
    })
  }
  // 段内按稳定步骤名收口：即便个别步写入乱序，展示也保持 vector → bm25 → rrf → rerank
  for (const segment of segments) {
    segment.steps.sort(
      (a, b) => retrieveStepRank(a.name) - retrieveStepRank(b.name) || compareExecOrder(a, b)
    )
  }
  return segments
}

/**
 * 子步状态三态：跳过 / 失败 / 正常，供抽屉标色。
 *
 * @param span 检索子步
 * @return 状态词
 */
export function retrieveStepState(span: ObsSpan): 'skip' | 'error' | 'ok' | 'running' {
  const attrs = retrieveAttrs(span.attrsJson)
  if (span.status === 'RUNNING') {
    return 'running'
  }
  if (attrs.skipped) {
    return 'skip'
  }
  if (span.status === 'ERROR') {
    return 'error'
  }
  return 'ok'
}

/** 解析 JSON 对象；非法 / 非对象按空对象处理 */
function parseObject(raw: string | null | undefined): Record<string, unknown> {
  if (!raw || !raw.trim()) {
    return {}
  }
  try {
    const parsed = JSON.parse(raw) as unknown
    return parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {}
  } catch {
    return {}
  }
}

/** 取字符串字段；空串按缺失处理 */
function text(raw: Record<string, unknown>, key: string): string | null {
  const value = raw[key]
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

/** 取整数字段；非数字按缺失处理 */
function num(raw: Record<string, unknown>, key: string): number | null {
  const value = raw[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/**
 * 执行序：step_no 自然序 → seq → id。
 * <p>与 {@code utils/executionOrder.ts} 同口径；此处内联是为了让本模块零别名依赖、
 * 可被 node:test 直接加载（与 {@code traceGraphLayout} 的处理一致）。
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
