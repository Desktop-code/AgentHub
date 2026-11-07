/**
 * 记忆条目和策略。所属域：memory。时间字段是 createTime / updateTime / expiredTime。
 */

export interface MemoryItem {
  id: number
  agentId?: number | null
  userId?: number | null
  appId?: number | null
  sessionId?: number | null
  scope: string
  title?: string | null
  /** LONG 稳定事实键；同键合并；SHORT/AGENT 多为空 */
  factKey?: string | null
  content: string
  importance?: number
  status: number
  expiredTime?: string | null
  remark?: string | null
  /** 向量同步状态：PENDING 待同步 / SYNCED 已同步 / FAILED 同步失败；只读 */
  vectorSyncStatus?: string | null
  /** 连续同步失败次数；FAILED 时有展示意义 */
  vectorSyncFailCount?: number | null
  /** 最近一次失败原因（后端已脱敏、截断） */
  vectorSyncError?: string | null
  createTime?: string
  updateTime?: string
}

/**
 * Agent 记忆策略。摘要/抽取模型实例列仍可能有库值，但控制台两端都不再编辑；
 * 运行时按策略列 → 平台默认该用途 → 环境默认 →（可降）CHAT 解析。
 */
export interface MemoryPolicy {
  id?: number | null
  agentId: number
  enableShort: number
  enableLong: number
  maxShortItems: number
  maxLongItems: number
  ctxMaxTurns: number
  ctxMaxChars: number
  ctxIdleTtlSec: number
  shortDefaultTtlSec: number
  /** 遗留列；UI 不露出，保存不写死新值 */
  summaryModelInstanceId?: number | null
  enableLongExtract: number
  /** 遗留列；UI 不露出，保存不写死新值 */
  longExtractModelInstanceId?: number | null
  /** 空闲/删除时批抽开关（字段名历史遗留） */
  longExtractOnSessionEnd: number
  longExtractOnExplicit: number
  status?: number
  remark?: string | null
}

/**
 * 平台级 Embedding / Rerank 换绑预检。所属域：memory。
 * action 决定前端弹几级确认：
 * - SET_EMBEDDING / SET_RERANK：首次设置，一级确认；
 * - REBIND_SAME_DIM / REBIND_RERANK：同维换绑或重排换绑，一级确认；
 * - REBIND_REQUIRES_REBUILD：异维换绑，换绑后再走「受控重建」危险确认；
 * - BLOCKED / NOT_LOCKABLE：直接拒绝，展示 reason。
 */
export interface MemoryVectorRebindPreview {
  targetInstanceId: number
  targetDisplayName: string
  targetCapability: string
  targetEmbeddingDim?: number | null
  currentInstanceId?: number | null
  currentDisplayName?: string | null
  currentEmbeddingDim?: number | null
  collectionName: string
  collectionDimension?: number | null
  syncing: boolean
  rebuilding: boolean
  allowed: boolean
  reason: string
  action: string
  rebuildRequired: boolean
  warnings: string[]
}

/** 记忆向量就绪状态。只读排障用；未命中缓存时会触发一次幂等 ensure。 */
export interface MemoryVectorStatus {
  ready: boolean
  collectionName: string
  /** 平台级 Embedding 的维度快照 */
  dimension: number
  /** 现网集合真实维度；读不到为 null */
  collectionDimension?: number | null
  /** 集合维度 ≠ 快照维度：需要受控重建 */
  dimensionConflict: boolean
  /** 待补同步条数；-1 表示读取失败（此时不应据此判断"已补齐"） */
  pendingSync: number
  embeddingInstanceId?: number | null
  rerankInstanceId?: number | null
  rerankReady: boolean
  syncing: boolean
  rebuilding: boolean
  /**
   * 重建未完成期间服务端是否自动续补。true 时前端不再给「继续补同步」按钮，
   * 只显示自动进度；刷新 / 切页面回来后仍按 rebuilding + pendingSync 显示进行中。
   */
  autoDrain?: boolean
  /** 当前是否有续补请求（重建未完成，或平台级换绑后仍有存量待补） */
  draining?: boolean
  /** 当前进程是否真的在执行重建（DROP/ensure/requeue/首轮补同步）；刷新后据此继续显示执行中 */
  rebuildRunning?: boolean
  /** 重建开始时刻（毫秒，0=未在重建）。仅用于展示已持续时间 */
  rebuildStartedAt?: number
  reason: string
}

/** 受控重建结果。rebuilding=true 表示仍有存量待补同步，期间禁止改平台级 Embedding。 */
export interface MemoryVectorRebuildResult {
  collectionName: string
  dimension: number
  embeddingInstanceId: number
  previousCollectionDimension?: number | null
  requeued: number
  scanned: number
  synced: number
  failed: number
  pendingAfter: number
  ready: boolean
  reason: string
  rebuilding: boolean
  batchLimit: number
}
