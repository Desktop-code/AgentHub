/**
 * 调用方应用接口。所属域：admin。
 */
import request from '@/utils/request'
import type { AppRow, PageResult, Result } from '@/types'

/** 分页查询应用。 */
export function pageApps(params: { current: number; size: number; name?: string }) {
  return request.get<Result<PageResult<AppRow>>>('/api/system/apps', { params })
}

/** 新建应用。响应里的 appSecret 只此一次。 */
export function createApp(data: Record<string, unknown>) {
  return request.post<Result<AppRow>>('/api/system/apps', data)
}

/** 修改应用资料。 */
export function updateApp(id: number, data: Record<string, unknown>) {
  return request.put<Result<AppRow>>(`/api/system/apps/${id}`, data)
}

/** 删除应用。 */
export function deleteApp(id: number) {
  return request.delete<Result<null>>(`/api/system/apps/${id}`)
}

/** 重置密钥，明文只在本次响应出现。 */
export function resetAppSecret(id: number) {
  return request.post<Result<AppRow>>(`/api/system/apps/${id}/secret`)
}

/** 覆盖 Agent 授权。本期不校验 Agent 是否存在。 */
export function grantAppAgents(id: number, agentIds: number[]) {
  return request.put<Result<null>>(`/api/system/apps/${id}/agents`, { agentIds })
}
