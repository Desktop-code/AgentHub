/**
 * 菜单接口。所属域：admin。
 */
import request from '@/utils/request'
import type { MenuRow, Result } from '@/types'

/** 管理端菜单树，含按钮。 */
export function menuTree() {
  return request.get<Result<MenuRow[]>>('/api/system/menus')
}

/** 新建菜单或按钮。 */
export function createMenu(data: Record<string, unknown>) {
  return request.post<Result<MenuRow>>('/api/system/menus', data)
}

/** 修改菜单。 */
export function updateMenu(id: number, data: Record<string, unknown>) {
  return request.put<Result<MenuRow>>(`/api/system/menus/${id}`, data)
}

/** 删除菜单。有子节点时后端会拒绝。 */
export function deleteMenu(id: number) {
  return request.delete<Result<null>>(`/api/system/menus/${id}`)
}
