/**
 * 知识库检索 / 入库参数的表单模型与 JSON 互转。所属域：rag。
 *
 * 职责：
 * <ul>
 *   <li>解析库里的 retrieval_config / ingest_config，缺键回落默认值；</li>
 *   <li>把表单值重新序列化回 JSON，键名保持后端认识的驼峰；</li>
 *   <li>保存前按后端夹紧区间做前端校验，超界不允许提交。</li>
 * </ul>
 *
 * 区间来源（与后端同源，改动必须同步两边）：
 * <ul>
 *   <li>检索：com.agenthub.rag.domain.service.RetrievalConfigResolver</li>
 *   <li>入库：com.agenthub.rag.application.RagIngestAppService.IngestParams#normalized</li>
 * </ul>
 * 本文件只做展示与提交前收敛，不复制后端的检索逻辑（如 max(rrfTopN, topK) 抬高）。
 */

/** 检索参数表单模型；mergedTopK 为 null 表示「按各选中库 topK 之和」 */
export interface RetrievalParams {
  /** 单库最终返回条数，后端夹紧 1–20 */
  topK: number
  /** 单篇文档最多保留的切片数，后端 1–20（≤0 回落 3） */
  maxChunksPerDoc: number
  /** 多库合并后条数；null / 0 表示自动 */
  mergedTopK: number | null
  /** 向量路召回条数，后端 1–100 */
  vectorTopK: number
  /** BM25 路召回条数，后端 1–100 */
  bm25TopK: number
  /** RRF 平滑常数 k，后端 1–1000 */
  rrfK: number
  /** RRF 融合后条数，后端 1–100 */
  rrfTopN: number
  /** RRF 是否启用；缺键按开处理 */
  rrfEnabled: boolean
  /** 向量分下限 0–1 */
  scoreThreshold: number
  /** 本库是否启用重排；需上部 Rerank 下拉选了模型才实际生效 */
  rerankEnabled: boolean
  /** 查询改写开关；本刀只存开关，不改写问句 */
  rewriteEnabled: boolean
}

/** 入库参数表单模型；只影响后续入库 / 重新切片 */
export interface IngestParams {
  /** 目标片长（字符），后端 100–4000 */
  chunkSize: number
  /** 相邻切片重叠，后端 0–片长一半 */
  chunkOverlap: number
  /** 过短切片合并阈值，后端 0–片长 */
  minChunkSize: number
}

/** 解析结果：表单值 + 表单未展示但解析器仍认识的原键，保存时原样带回 */
export interface ParsedRetrievalConfig {
  params: RetrievalParams
  extra: Record<string, unknown>
}

/** 入库参数解析结果，结构同上 */
export interface ParsedIngestConfig {
  params: IngestParams
  extra: Record<string, unknown>
}

/** 检索参数默认值（与后端 RetrievalConfigResolver.defaults() 对齐） */
export function defaultRetrievalParams(): RetrievalParams {
  return {
    topK: 5,
    maxChunksPerDoc: 3,
    mergedTopK: null,
    vectorTopK: 20,
    bm25TopK: 20,
    rrfK: 60,
    rrfTopN: 20,
    rrfEnabled: true,
    scoreThreshold: 0.5,
    rerankEnabled: false,
    rewriteEnabled: false
  }
}

/** 入库参数默认值（与后端 IngestParams 的 450 / 100 / 150 对齐） */
export function defaultIngestParams(): IngestParams {
  return { chunkSize: 450, chunkOverlap: 100, minChunkSize: 150 }
}

/**
 * 数值型检索参数的上下限；仅用于表单校验与提示文案。
 * 与后端常量一一对应，不要在这里另造区间。
 */
export const RETRIEVAL_RANGE = {
  topK: { min: 1, max: 20 },
  maxChunksPerDoc: { min: 1, max: 20 },
  mergedTopK: { min: 1, max: 100 },
  vectorTopK: { min: 1, max: 100 },
  bm25TopK: { min: 1, max: 100 },
  rrfK: { min: 1, max: 1000 },
  rrfTopN: { min: 1, max: 100 },
  scoreThreshold: { min: 0, max: 1 }
} as const

/** 入库参数的绝对值区间；重叠还有「不超过片长一半」的相对上限 */
export const INGEST_RANGE = {
  chunkSize: { min: 100, max: 4000 },
  chunkOverlap: { min: 0, max: 4000 },
  minChunkSize: { min: 0, max: 4000 }
} as const

/** 检索参数里由表单接管的键；其余键解析时进 extra，保存时原样带回 */
const RETRIEVAL_KEYS = [
  'topK',
  'maxChunksPerDoc',
  'mergedTopK',
  'vectorTopK',
  'bm25TopK',
  'rrfK',
  'rrfTopN',
  'rrfEnabled',
  'scoreThreshold',
  'rerankEnabled',
  'rewriteEnabled'
] as const

/** 入库参数里由表单接管的键 */
const INGEST_KEYS = ['chunkSize', 'chunkOverlap', 'minChunkSize'] as const

/**
 * 读 JSON 对象；空串、数组、标量、语法错误一律当空对象，交给调用方回落默认。
 * 与后端 RetrievalConfigResolver.parse 的宽进严出一致：坏配置不阻塞表单打开。
 *
 * @param raw 库里的 JSON 字符串，可空
 * @return 解析出的普通对象
 */
function readObject(raw?: string | null): Record<string, unknown> {
  if (!raw || !raw.trim()) {
    return {}
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
    return {}
  } catch {
    return {}
  }
}

/**
 * 取整并夹到区间内；非法值回落 fallback。
 *
 * @param value    原始 JSON 值
 * @param min      下限
 * @param max      上限
 * @param fallback 缺失或非数值时的默认值
 * @return 已夹紧的整数
 */
function clampInt(value: unknown, min: number, max: number, fallback: number): number {
  // 只认 JSON 数字：null / 字符串 / 布尔在后端 valueOf 里都视为「没配」，这里同样回落默认
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return fallback
  }
  return Math.min(Math.max(Math.round(value), min), max)
}

/**
 * 取小数并夹到区间内；非法值回落 fallback。
 * 保留两位小数：控件精度也是两位，避免「界面显示 0.50、库里还是 0.4999」的口径差。
 *
 * @param value    原始 JSON 值
 * @param min      下限
 * @param max      上限
 * @param fallback 缺失或非数值时的默认值
 * @return 已夹紧的小数
 */
function clampDecimal(value: unknown, min: number, max: number, fallback: number): number {
  // 与 clampInt 同口径：只认 JSON 数字，字符串与 null 一律回落默认
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return fallback
  }
  const clamped = Math.min(Math.max(value, min), max)
  return Math.round(clamped * 100) / 100
}

/**
 * 取布尔值；只有 JSON 里真的是布尔才采信，其余回落默认。
 *
 * @param value    原始 JSON 值
 * @param fallback 缺失时的默认值
 * @return 布尔值
 */
function readBool(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

/**
 * 收集表单未接管的键，保存时原样写回，避免「打开再保存」把其它合法配置清掉。
 *
 * @param source 原始 JSON 对象
 * @param known  表单已接管的键
 * @return 其余键值
 */
function collectExtra(source: Record<string, unknown>, known: readonly string[]): Record<string, unknown> {
  const extra: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(source)) {
    if (!known.includes(key)) {
      extra[key] = value
    }
  }
  return extra
}

/**
 * 解析 retrieval_config。缺失键用默认值显示，不做「空对象覆盖」。
 *
 * @param raw 库里的 retrieval_config，可空
 * @return 表单值 + 未展示的其它键
 */
export function parseRetrievalConfig(raw?: string | null): ParsedRetrievalConfig {
  const src = readObject(raw)
  const defaults = defaultRetrievalParams()
  // maxChunksPerDoc 后端把 ≤0 当「不配」并回落 3，这里镜像同一语义，避免表单显示 0
  const rawMaxChunks = typeof src.maxChunksPerDoc === 'number' ? src.maxChunksPerDoc : undefined
  const maxChunksPerDoc =
    rawMaxChunks === undefined || rawMaxChunks <= 0
      ? defaults.maxChunksPerDoc
      : clampInt(rawMaxChunks, RETRIEVAL_RANGE.maxChunksPerDoc.min, RETRIEVAL_RANGE.maxChunksPerDoc.max, defaults.maxChunksPerDoc)
  // mergedTopK 为 0 / 负数都表示「自动」，表单统一显示为空；有效值夹在 1–100
  const rawMerged = typeof src.mergedTopK === 'number' && Number.isFinite(src.mergedTopK) ? src.mergedTopK : 0
  const mergedTopK =
    rawMerged <= 0
      ? null
      : Math.min(Math.max(Math.round(rawMerged), 1), RETRIEVAL_RANGE.mergedTopK.max)
  return {
    params: {
      topK: clampInt(src.topK, RETRIEVAL_RANGE.topK.min, RETRIEVAL_RANGE.topK.max, defaults.topK),
      maxChunksPerDoc,
      mergedTopK,
      vectorTopK: clampInt(src.vectorTopK, RETRIEVAL_RANGE.vectorTopK.min, RETRIEVAL_RANGE.vectorTopK.max, defaults.vectorTopK),
      bm25TopK: clampInt(src.bm25TopK, RETRIEVAL_RANGE.bm25TopK.min, RETRIEVAL_RANGE.bm25TopK.max, defaults.bm25TopK),
      rrfK: clampInt(src.rrfK, RETRIEVAL_RANGE.rrfK.min, RETRIEVAL_RANGE.rrfK.max, defaults.rrfK),
      rrfTopN: clampInt(src.rrfTopN, RETRIEVAL_RANGE.rrfTopN.min, RETRIEVAL_RANGE.rrfTopN.max, defaults.rrfTopN),
      rrfEnabled: readBool(src.rrfEnabled, defaults.rrfEnabled),
      scoreThreshold: clampDecimal(
        src.scoreThreshold,
        RETRIEVAL_RANGE.scoreThreshold.min,
        RETRIEVAL_RANGE.scoreThreshold.max,
        defaults.scoreThreshold
      ),
      rerankEnabled: readBool(src.rerankEnabled, defaults.rerankEnabled),
      rewriteEnabled: readBool(src.rewriteEnabled, defaults.rewriteEnabled)
    },
    extra: collectExtra(src, RETRIEVAL_KEYS)
  }
}

/**
 * 解析 ingest_config；缺失键用 450 / 100 / 150 显示。
 *
 * @param raw 库里的 ingest_config，可空
 * @return 表单值 + 未展示的其它键
 */
export function parseIngestConfig(raw?: string | null): ParsedIngestConfig {
  const src = readObject(raw)
  const defaults = defaultIngestParams()
  return {
    params: clampIngest({
      // 入库同样走 clampInt：缺失 / null / 非数字都回落默认，避免显示成 0
      chunkSize: clampInt(src.chunkSize, INGEST_RANGE.chunkSize.min, INGEST_RANGE.chunkSize.max, defaults.chunkSize),
      chunkOverlap: clampInt(src.chunkOverlap, INGEST_RANGE.chunkOverlap.min, INGEST_RANGE.chunkOverlap.max, defaults.chunkOverlap),
      minChunkSize: clampInt(
        src.minChunkSize,
        INGEST_RANGE.minChunkSize.min,
        INGEST_RANGE.minChunkSize.max,
        defaults.minChunkSize
      )
    }),
    extra: collectExtra(src, INGEST_KEYS)
  }
}

/**
 * 收紧入库参数之间的相对关系：重叠不超过片长一半、最小片长不超过片长。
 * 与后端 IngestParams#normalized 同规则；片长改动时立即调用，避免控件里出现必然被后端改写的值。
 *
 * @param params 当前表单值
 * @return 收紧后的新对象
 */
export function clampIngest(params: IngestParams): IngestParams {
  const size = Math.min(Math.max(Math.round(params.chunkSize), INGEST_RANGE.chunkSize.min), INGEST_RANGE.chunkSize.max)
  const half = Math.floor(size / 2)
  return {
    chunkSize: size,
    chunkOverlap: Math.min(Math.max(Math.round(params.chunkOverlap), 0), half),
    minChunkSize: Math.min(Math.max(Math.round(params.minChunkSize), 0), size)
  }
}

/**
 * 序列化 retrieval_config。键名保持后端认识的驼峰，mergedTopK 为空则不写这个键。
 *
 * @param params 表单值
 * @param extra  表单未展示的其它键，原样带回
 * @return 可直接提交的 JSON 字符串
 */
export function buildRetrievalConfig(params: RetrievalParams, extra: Record<string, unknown> = {}): string {
  const body: Record<string, unknown> = {
    topK: Math.round(params.topK),
    maxChunksPerDoc: Math.round(params.maxChunksPerDoc),
    vectorTopK: Math.round(params.vectorTopK),
    bm25TopK: Math.round(params.bm25TopK),
    rrfK: Math.round(params.rrfK),
    rrfTopN: Math.round(params.rrfTopN),
    rrfEnabled: params.rrfEnabled,
    scoreThreshold: params.scoreThreshold,
    rerankEnabled: params.rerankEnabled,
    rewriteEnabled: params.rewriteEnabled
  }
  // 只有显式配了正数才写；空 / 0 交给后端按「各选中库 topK 之和」处理
  if (params.mergedTopK != null && params.mergedTopK > 0) {
    body.mergedTopK = Math.round(params.mergedTopK)
  }
  for (const [key, value] of Object.entries(extra)) {
    if (!(key in body)) {
      body[key] = value
    }
  }
  return JSON.stringify(body)
}

/**
 * 序列化 ingest_config。
 *
 * @param params 表单值
 * @param extra  表单未展示的其它键，原样带回
 * @return 可直接提交的 JSON 字符串
 */
export function buildIngestConfig(params: IngestParams, extra: Record<string, unknown> = {}): string {
  const safe = clampIngest(params)
  const body: Record<string, unknown> = {
    chunkSize: safe.chunkSize,
    chunkOverlap: safe.chunkOverlap,
    minChunkSize: safe.minChunkSize
  }
  for (const [key, value] of Object.entries(extra)) {
    if (!(key in body)) {
      body[key] = value
    }
  }
  return JSON.stringify(body)
}

/**
 * 提交前校验：区间与后端解析器一致，超界不允许保存。
 * 控件本身已带 min/max，这里再兜一层，防止手输或旧值越界被悄悄夹紧。
 *
 * @param retrieval 检索参数
 * @param ingest    入库参数
 * @return 错误文案列表；空数组表示通过
 */
export function validateKbParams(retrieval: RetrievalParams, ingest: IngestParams): string[] {
  const errors: string[] = []
  const checkInt = (label: string, value: number, range: { min: number; max: number }) => {
    if (!Number.isFinite(value) || Math.round(value) !== value) {
      errors.push(`${label}需为整数`)
      return
    }
    if (value < range.min || value > range.max) {
      errors.push(`${label}需在 ${range.min}–${range.max} 之间`)
    }
  }
  checkInt('单库最终条数', retrieval.topK, RETRIEVAL_RANGE.topK)
  checkInt('同篇最多几段', retrieval.maxChunksPerDoc, RETRIEVAL_RANGE.maxChunksPerDoc)
  checkInt('向量召回条数', retrieval.vectorTopK, RETRIEVAL_RANGE.vectorTopK)
  checkInt('关键词召回条数', retrieval.bm25TopK, RETRIEVAL_RANGE.bm25TopK)
  checkInt('RRF k', retrieval.rrfK, RETRIEVAL_RANGE.rrfK)
  checkInt('RRF 融合后条数', retrieval.rrfTopN, RETRIEVAL_RANGE.rrfTopN)
  // mergedTopK 允许为空；填了才校验
  if (retrieval.mergedTopK != null) {
    checkInt('多库合并后条数', retrieval.mergedTopK, RETRIEVAL_RANGE.mergedTopK)
  }
  if (
    !Number.isFinite(retrieval.scoreThreshold) ||
    retrieval.scoreThreshold < RETRIEVAL_RANGE.scoreThreshold.min ||
    retrieval.scoreThreshold > RETRIEVAL_RANGE.scoreThreshold.max
  ) {
    errors.push('向量分阈值需在 0–1 之间')
  }
  checkInt('切片长度', ingest.chunkSize, INGEST_RANGE.chunkSize)
  if (ingest.chunkOverlap < 0 || ingest.chunkOverlap > Math.floor(ingest.chunkSize / 2)) {
    errors.push(`相邻切片重叠需在 0–${Math.floor(ingest.chunkSize / 2)} 之间`)
  }
  if (ingest.minChunkSize < 0 || ingest.minChunkSize > ingest.chunkSize) {
    errors.push(`过短切片阈值需在 0–${ingest.chunkSize} 之间`)
  }
  return errors
}
