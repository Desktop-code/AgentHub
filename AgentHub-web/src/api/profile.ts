/**
 * 个人设置接口。所属域：auth（前端）。
 * 只改当前登录人自己的显示名与头像，不走用户管理权限码。
 */
import request from '@/utils/request'
import type { Result } from '@/types'

/** 本人资料 */
export interface ProfileInfo {
  userId: number
  username: string
  nickname?: string
  avatarUrl?: string | null
  email?: string | null
  phone?: string | null
}

/** 拉取本人资料 */
export function fetchProfile() {
  return request.get<Result<ProfileInfo>>('/api/auth/profile')
}

/**
 * 改显示名。
 *
 * @param nickname 新显示名
 */
export function updateProfile(nickname: string) {
  return request.put<Result<ProfileInfo>>('/api/auth/profile', { nickname })
}

/**
 * 上传头像。
 *
 * @param file 图片文件（jpg/png/webp，≤2MB）
 */
export function uploadAvatar(file: File) {
  const body = new FormData()
  body.append('file', file)
  return request.post<Result<{ avatarUrl: string }>>('/api/auth/profile/avatar', body, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 30000
  })
}

/** 清除头像 */
export function clearAvatar() {
  return request.delete<Result<ProfileInfo>>('/api/auth/profile/avatar')
}
