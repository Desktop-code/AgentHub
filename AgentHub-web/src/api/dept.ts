/**
 * 部门接口。所属域：admin。
 */
import request from '@/utils/request'
import type { DeptRow, Result } from '@/types'

/** 当前用户可见的部门树。 */
export function deptTree() {
  return request.get<Result<DeptRow[]>>('/api/system/depts')
}

/** 新建部门。 */
export function createDept(data: Record<string, unknown>) {
  return request.post<Result<DeptRow>>('/api/system/depts', data)
}

/** 修改部门。父节点变化时后端改写 path。 */
export function updateDept(id: number, data: Record<string, unknown>) {
  return request.put<Result<DeptRow>>(`/api/system/depts/${id}`, data)
}

/** 删除部门。 */
export function deleteDept(id: number) {
  return request.delete<Result<null>>(`/api/system/depts/${id}`)
}
