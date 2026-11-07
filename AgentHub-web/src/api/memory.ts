/**
 * 记忆条目与策略 API。所属域：memory。
 * 全局记忆页只调条目查询/审计接口；策略读写留给 Agent 配置侧。
 * 向量运维（换绑预检 / 受控重建 / 就绪状态）也挂在本模块，权限分别由后端注解控制。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  MemoryItem,
  MemoryPolicy,
  MemoryVectorRebindPreview,
  MemoryVectorRebuildResult,
  MemoryVectorStatus,
} from '@/types/memory'

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

/** 详情。列表已带同步状态三字段，详情用于展示完整条目。 */
export function getMemoryItem(id: number) {
  return request.get<Result<MemoryItem>>(`/api/memory/items/${id}`)
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

/**
 * 换绑预检。只读：返回当前/目标实例、双方维度、现网集合维度、同步/重建状态与建议动作。
 * 设平台级前必须先调它，按 action 决定弹一级还是两级确认。
 *
 * @param instanceId 目标实例 id
 */
export function previewPlatformRebind(instanceId: number) {
  return request.get<Result<MemoryVectorRebindPreview>>(`/api/memory/vector/rebind-preview/${instanceId}`)
}

/** 记忆向量就绪状态（含维度冲突与重建中标记）。 */
export function getMemoryVectorStatus() {
  return request.get<Result<MemoryVectorStatus>>('/api/memory/vector/status')
}

/** 手动触发一轮补同步（有界）。重建后仍有待补时用它继续推进。 */
export function reconcileMemoryVector() {
  return request.post<Result<Record<string, unknown>>>('/api/memory/vector/reconcile')
}

/**
 * 受控重建记忆向量集合：DROP 配置集合 → 按快照维重建 → 有效 LONG 打回 PENDING → 有界一轮补同步。
 * 不可撤销，必须由危险确认弹窗显式置 confirmRebuild=true。
 *
 * @param collectionName 必须等于后端配置的记忆集合名（预检/状态接口返回）
 */
export function rebuildMemoryVector(data: { confirmRebuild: boolean; collectionName: string }) {
  return request.post<Result<MemoryVectorRebuildResult>>('/api/memory/vector/rebuild', data, { timeout: 60000 })
}
