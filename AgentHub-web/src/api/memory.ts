/**
 * 记忆条目与策略 API。所属域：memory。
 * 全局记忆页只调条目查询/审计接口；策略读写留给 Agent 配置侧。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { MemoryItem, MemoryPolicy } from '@/types/memory'

/**
 * 分页查询。控制台应传 agentId + scope 限制维度；
 * 分页参数使用 pageNum / pageSize（后端别名写入 current / size）。
 */
export function pageMemoryItems(params: {
  pageNum: number
  pageSize: number
  agentId?: number
  scope?: string
  sessionId?: number
  userId?: number
  /** NORMAL=启用未过期；EXPIRED=已过期；DELETED=逻辑删除行 */
  lifeStatus?: 'NORMAL' | 'EXPIRED' | 'DELETED'
  createTimeFrom?: string
  createTimeTo?: string
  /** 兼容旧调用：库内 1 正常 / 0 停用 */
  status?: number
}) {
  return request.get<Result<PageResult<MemoryItem>>>('/api/memory/items', { params })
}

/** 新建。SHORT 会双写 Redis，Redis 不可用时接口失败。 */
export function createMemoryItem(data: Record<string, unknown>) {
  return request.post<Result<MemoryItem>>('/api/memory/items', data)
}

/** 编辑。范围保持原值。 */
export function updateMemoryItem(id: number, data: Record<string, unknown>) {
  return request.put<Result<MemoryItem>>(`/api/memory/items/${id}`, data)
}

/** 逻辑删除。 */
export function deleteMemoryItem(id: number) {
  return request.delete<Result<null>>(`/api/memory/items/${id}`)
}

/** 启停。1 启用，0 停用。 */
export function changeMemoryStatus(id: number, status: number) {
  return request.put<Result<MemoryItem>>(`/api/memory/items/${id}/status`, { status })
}

/** 读取策略。还没保存时 id 为空，数值是平台默认。 */
export function getMemoryPolicy(agentId: number) {
  return request.get<Result<MemoryPolicy>>('/api/memory/policies', { params: { agentId } })
}

/** 保存策略。每个 Agent 只保留一条。 */
export function saveMemoryPolicy(data: MemoryPolicy) {
  return request.put<Result<MemoryPolicy>>('/api/memory/policies', data)
}
