/**
 * 工具源与工具项的页面类型。所属域：tool。
 * 接口只返回掩码 authHeaderMask，任何响应都不含完整鉴权头。
 */
export interface ToolSource {
  id: number
  name: string
  type: string
  transport?: string
  baseUrl?: string
  authHeaderMask?: string
  status: number
  remark?: string
  lastSyncTime?: string
  lastSyncOk?: number
  lastSyncMsg?: string
}

export interface ToolItemRow {
  id: number
  sourceId: number
  sourceName?: string
  sourceType?: string
  name: string
  title?: string
  description?: string
  inputSchemaJson?: string
  status: number
}

export interface SyncResult {
  success: boolean
  latencyMs: number
  upserted: number
  created: number
  updated: number
  /** 本次对齐下架的条数（远端已删 + 同源同名重复行） */
  retired: number
  message: string
}

/** MCP 弹窗「测试链接」结果；message 已脱敏。 */
export interface ProbeResult {
  success: boolean
  latencyMs: number
  toolCount: number
  message: string
}

/** 源内批量启停结果。status 为目标状态，updated 为实际更新行数。 */
export interface ItemBatchStatusResult {
  sourceId: number
  status: number
  updated: number
}
