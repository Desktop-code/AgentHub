/**
 * Token 用量接口类型。所属域：billing。金额为空表示只计量没计价。
 */

export interface LedgerRow {
  id: number
  bizDate: string
  createTime?: string
  agentId?: number | null
  /** 列表接口带回；软删也会带回真名，真的查无行时才为空 */
  agentName?: string | null
  /** Agent 已软删；有真名时旁挂「已删除」（删优先于停用） */
  agentDeleted?: boolean | null
  /** Agent 未删且停用；旁挂「已停用」（已删时不返回） */
  agentDisabled?: boolean | null
  modelInstanceId?: number | null
  modelInstanceName?: string | null
  providerName?: string | null
  /** 已软删或不存在；有真名时旁挂「已删除」，无行才整格占位 */
  modelDeleted?: boolean | null
  /** 未删且停用；旁挂「已停用」（删优先时不返回） */
  modelDisabled?: boolean | null
  channel: string
  tokenPrompt: number
  tokenCompletion: number
  tokenTotal: number
  amount?: number | null
  currency?: string | null
  status: number
  remark?: string | null
}

export interface DailyRow {
  id: number
  bizDate: string
  agentId?: number | null
  /** 列表接口带回；软删也会带回真名，真的查无行时才为空 */
  agentName?: string | null
  /** Agent 已软删；有真名时旁挂「已删除」（删优先于停用） */
  agentDeleted?: boolean | null
  /** Agent 未删且停用；旁挂「已停用」（已删时不返回） */
  agentDisabled?: boolean | null
  modelInstanceId?: number | null
  modelInstanceName?: string | null
  providerName?: string | null
  /** 已软删或不存在；有真名时旁挂「已删除」，无行才整格占位 */
  modelDeleted?: boolean | null
  /** 未删且停用；旁挂「已停用」（删优先时不返回） */
  modelDisabled?: boolean | null
  channel: string
  tokenPrompt: number
  tokenCompletion: number
  tokenTotal: number
  amount?: number | null
  currency?: string | null
  status: number
}

/**
 * 趋势点。后端已按业务日把各维度加总，并补齐无数据的日期（token 记 0）。
 */
export interface TrendRow {
  bizDate: string
  tokenPrompt: number
  tokenCompletion: number
  tokenTotal: number
}

/** 趋势图渲染用的点；组件与页面共用，避免在 SFC 里导出类型。 */
export interface TrendPoint {
  /** yyyy-MM-dd */
  date: string
  prompt: number
  completion: number
  total: number
}

export interface PriceRow {
  id: number
  modelInstanceId?: number | null
  name: string
  pricePrompt: number
  priceCompletion: number
  currency: string
  effectiveFrom: string
  effectiveTo?: string | null
  status: number
  remark?: string | null
}

export interface PriceSave {
  modelInstanceId?: number | null
  name: string
  pricePrompt: number
  priceCompletion: number
  currency?: string
  effectiveFrom: string
  effectiveTo?: string | null
  remark?: string
}

export interface BillingFilter {
  dateFrom?: string
  dateTo?: string
  agentId?: number
  modelInstanceId?: number
  channel?: string
}
