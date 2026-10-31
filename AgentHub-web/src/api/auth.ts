/**
 * 登录与当前用户。所属域：auth。
 */
import request from '@/utils/request'
import type { MeInfo, Result } from '@/types'

/** 登录成功返回裸 token，由调用方写入本地存储。 */
export function login(username: string, password: string) {
  return request.post<Result<{ token: string; username: string; nickname: string }>>('/api/auth/login', {
    username,
    password
  })
}

/** 注销服务端 Token。 */
export function logout() {
  return request.post<Result<null>>('/api/auth/logout')
}

/** 当前用户、权限码和侧栏菜单。 */
export function fetchMe() {
  return request.get<Result<MeInfo>>('/api/auth/me')
}
