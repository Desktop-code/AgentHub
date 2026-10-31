/**
 * Token 用量接口。所属域：billing。
 * 流水只读。重算和日汇总查询共用 daily:list。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { BillingFilter, DailyRow, LedgerRow, PriceRow, PriceSave, TrendRow } from '@/types/billing'

/** 流水分页。筛选都可空。 */
export function pageLedgers(params: BillingFilter & { current: number; size: number }) {
  return request.get<Result<PageResult<LedgerRow>>>('/api/billing/ledgers', { params })
}

/** 日汇总分页。 */
export function pageDaily(params: BillingFilter & { current: number; size: number }) {
  return request.get<Result<PageResult<DailyRow>>>('/api/billing/daily', { params })
}

/** 趋势聚合：按天返回 token 消耗，缺数据的日子后端补 0 点。 */
export function pageDailyTrend(params: BillingFilter) {
  return request.get<Result<TrendRow[]>>('/api/billing/daily/trend', { params })
}

/** 按闭区间覆盖同维日汇总。 */
export function rebuildDaily(data: { dateFrom: string; dateTo: string }) {
  return request.post<Result<number>>('/api/billing/daily/rebuild', data)
}

/** 单价分页。 */
export function pagePrices(params: { current: number; size: number }) {
  return request.get<Result<PageResult<PriceRow>>>('/api/billing/prices', { params })
}

/** 新建单价，默认启用。 */
export function createPrice(data: PriceSave) {
  return request.post<Result<PriceRow>>('/api/billing/prices', data)
}

/** 编辑单价，不改启停。 */
export function updatePrice(id: number, data: PriceSave) {
  return request.put<Result<PriceRow>>(`/api/billing/prices/${id}`, data)
}

/** 逻辑删除单价。 */
export function deletePrice(id: number) {
  return request.delete<Result<null>>(`/api/billing/prices/${id}`)
}

/** 启停。1 正常，0 停用。 */
export function changePriceStatus(id: number, status: number) {
  return request.put<Result<PriceRow>>(`/api/billing/prices/${id}/status`, { status })
}
