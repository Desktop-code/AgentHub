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
