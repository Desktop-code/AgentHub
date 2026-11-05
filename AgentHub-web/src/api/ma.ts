/**
 * 多智能体方案接口。所属域：前端 agent。
 *
 * <p>路径沿用 `/api/agent/multi-agents`：后端已从「ROUTER 只读聚合」演进为
 * ma_def 方案 CRUD，前端入口不换，避免菜单与深链大改。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { MaDefDetail, MaDefRow, MaDefSave } from '@/types/ma'

/**
 * 分页查询方案。
 *
 * @param params 页码/条数/关键字/启停/模板/是否绑根
 * @return 方案列表行
 */
export function pageMaDefs(params: {
  current: number
  size: number
  keyword?: string
  enabled?: number
  template?: string
  hasRoot?: boolean
}) {
  return request.get<Result<PageResult<MaDefRow>>>('/api/agent/multi-agents', { params })
}

/**
 * 方案详情，含配置与候选。
 *
 * @param id 方案主键
 * @return 详情
 */
export function getMaDef(id: number) {
  return request.get<Result<MaDefDetail>>(`/api/agent/multi-agents/${id}`)
}

/**
 * 按 ROUTER 反查绑定中的启用方案；没有返回 data=null。
 *
 * @param agentId ROUTER 主键
 * @return 绑定方案或 null
 */
export function getMaDefByRoot(agentId: number) {
  return request.get<Result<MaDefRow | null>>(`/api/agent/multi-agents/by-root/${agentId}`)
}

/**
 * 新建方案。
 *
 * @param data 方案 + 配置 + 候选
 * @return 新建后的列表行
 */
export function createMaDef(data: MaDefSave) {
  return request.post<Result<MaDefRow>>('/api/agent/multi-agents', data)
}

/**
 * 更新方案；候选整份替换。
 *
 * @param id   方案主键
 * @param data 方案 + 配置 + 候选
 * @return 更新后的列表行
 */
export function updateMaDef(id: number, data: MaDefSave) {
  return request.put<Result<MaDefRow>>(`/api/agent/multi-agents/${id}`, data)
}

/**
 * 启停方案。
 *
 * @param id      方案主键
 * @param enabled 1 启用 / 0 停用
 */
export function changeMaDefEnabled(id: number, enabled: number) {
  return request.put<Result<void>>(`/api/agent/multi-agents/${id}/enabled`, { enabled })
}

/**
 * 删除方案（物理删除，候选一并清理；绑定的 ROUTER 保持现状）。
 *
 * @param id 方案主键
 */
export function deleteMaDef(id: number) {
  return request.delete<Result<void>>(`/api/agent/multi-agents/${id}`)
}
