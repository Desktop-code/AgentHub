/**
 * 用户接口。所属域：admin。
 */
import request from '@/utils/request'
import type { PageResult, Result, UserRow } from '@/types'

/** 分页查询用户。 */
export function pageUsers(params: { current: number; size: number; username?: string; status?: number }) {
  return request.get<Result<PageResult<UserRow>>>('/api/system/users', { params })
}

/** 新建用户。初始密码由服务端配置决定。 */
export function createUser(data: Record<string, unknown>) {
  return request.post<Result<UserRow>>('/api/system/users', data)
}

/** 修改资料，不含密码和登录名。 */
export function updateUser(id: number, data: Record<string, unknown>) {
  return request.put<Result<UserRow>>(`/api/system/users/${id}`, data)
}

/** 逻辑删除用户。 */
export function deleteUser(id: number) {
  return request.delete<Result<null>>(`/api/system/users/${id}`)
}

/** 重置为开发环境初始密码。 */
export function resetUserPassword(id: number) {
  return request.put<Result<null>>(`/api/system/users/${id}/password`)
}

/** 覆盖该用户的角色。 */
export function assignUserRoles(id: number, roleIds: number[]) {
  return request.put<Result<null>>(`/api/system/users/${id}/roles`, { roleIds })
}
