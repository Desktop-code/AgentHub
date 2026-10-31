/**
 * 模型供应商、凭证和实例。所属域：model。
 * 接口只返回掩码；创建、更新、重置 Key 都不会把明文放进响应，前端也不再依赖明文做提示。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { ConnectivityResult, CredentialRow, InstanceRow, ProviderCard } from '@/types/model'

/** 供应商卡片。 */
export function listProviders() {
  return request.get<Result<ProviderCard[]>>('/api/model/providers')
}

/** 供应商详情，含凭证掩码。 */
export function getProvider(id: number) {
  return request.get<Result<ProviderCard>>(`/api/model/providers/${id}`)
}

/** 新建供应商（可不带凭证；凭证后置到凭证弹窗）。 */
export function createProvider(data: Record<string, unknown>) {
  return request.post<Result<ProviderCard>>('/api/model/providers', data)
}

/** 修改供应商资料，不提交 Key。 */
export function updateProvider(id: number, data: Record<string, unknown>) {
  return request.put<Result<ProviderCard>>(`/api/model/providers/${id}`, data)
}

/** 逻辑删除供应商。仍有实例时后端会拒绝。 */
export function deleteProvider(id: number) {
  return request.delete<Result<null>>(`/api/model/providers/${id}`)
}

/** 追加凭证。 */
export function createCredential(providerId: number, data: Record<string, unknown>) {
  return request.post<Result<CredentialRow>>(`/api/model/providers/${providerId}/credentials`, data)
}

/** 修改凭证名称或启停。 */
export function updateCredential(providerId: number, credentialId: number, data: Record<string, unknown>) {
  return request.put<Result<CredentialRow>>(`/api/model/providers/${providerId}/credentials/${credentialId}`, data)
}

/** 删除未被实例引用的凭证。 */
export function deleteCredential(providerId: number, credentialId: number) {
  return request.delete<Result<null>>(`/api/model/providers/${providerId}/credentials/${credentialId}`)
}

/** 重置 Key。读超时略长于探活，避免前端先断开。 */
export function resetCredentialKey(providerId: number, credentialId: number, apiKey: string) {
  return request.post<Result<CredentialRow>>(
    `/api/model/providers/${providerId}/credentials/${credentialId}/reset-key`,
    { apiKey }
  )
}

/** 实例分页。未选供应商时不传 providerId，表示全部。 */
export function pageInstances(params: {
  current: number
  size: number
  providerId?: number
  capability?: string
  keyword?: string
}) {
  return request.get<Result<PageResult<InstanceRow>>>('/api/model/instances', { params })
}

/** 实例详情。计费页补名称时用。 */
export function getInstance(id: number) {
  return request.get<Result<InstanceRow>>(`/api/model/instances/${id}`)
}

/** 新建实例。 */
export function createInstance(data: Record<string, unknown>) {
  return request.post<Result<InstanceRow>>('/api/model/instances', data)
}

/** 编辑实例。 */
export function updateInstance(id: number, data: Record<string, unknown>) {
  return request.put<Result<InstanceRow>>(`/api/model/instances/${id}`, data)
}

/** 启停实例。 */
export function changeInstanceStatus(id: number, status: number) {
  return request.put<Result<InstanceRow>>(`/api/model/instances/${id}/status`, { status })
}

/** 删除实例。 */
export function deleteInstance(id: number) {
  return request.delete<Result<null>>(`/api/model/instances/${id}`)
}

/** 连通测试。超时要盖住服务端 15 秒读超时。 */
export function testInstance(id: number) {
  return request.post<Result<ConnectivityResult>>(`/api/model/instances/${id}/test`, null, { timeout: 25000 })
}
