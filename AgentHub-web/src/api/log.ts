/**
 * 审计日志只读接口。所属域：audit。
 */
import request from '@/utils/request'
import type { LoginLogRow, OperLogRow, PageResult, Result } from '@/types'

/** 登录日志分页。 */
export function pageLoginLogs(params: { current: number; size: number; username?: string }) {
  return request.get<Result<PageResult<LoginLogRow>>>('/api/system/login-logs', { params })
}

/** 操作日志分页。 */
export function pageOperLogs(params: { current: number; size: number; module?: string; username?: string }) {
  return request.get<Result<PageResult<OperLogRow>>>('/api/system/oper-logs', { params })
}
