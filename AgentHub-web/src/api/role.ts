/**
 * 角色接口。所属域：admin。
 */
import request from '@/utils/request'
import type { PageResult, Result, RoleRow } from '@/types'

/** 分页查询角色。 */
export function pageRoles(params: { current: number; size: number; code?: string; name?: string }) {
  return request.get<Result<PageResult<RoleRow>>>('/api/system/roles', { params })
}

/** 角色详情，含菜单和自定义部门。 */
export function getRole(id: number) {
  return request.get<Result<RoleRow>>(`/api/system/roles/${id}`)
}

/** 新建角色。 */
export function createRole(data: Record<string, unknown>) {
  return request.post<Result<RoleRow>>('/api/system/roles', data)
}

/** 修改角色资料，不改编码。 */
export function updateRole(id: number, data: Record<string, unknown>) {
  return request.put<Result<RoleRow>>(`/api/system/roles/${id}`, data)
}

/** 删除角色。 */
export function deleteRole(id: number) {
  return request.delete<Result<null>>(`/api/system/roles/${id}`)
}

/** 分配菜单。服务端会补祖先节点。 */
export function assignRoleMenus(id: number, menuIds: number[]) {
  return request.put<Result<null>>(`/api/system/roles/${id}/menus`, { menuIds })
}

/** 设置数据范围。自定义部门才提交 deptIds。 */
export function updateRoleScope(id: number, dataScope: number, deptIds: number[]) {
  return request.put<Result<null>>(`/api/system/roles/${id}/data-scope`, { dataScope, deptIds })
}
