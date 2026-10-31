/**
 * HTTP 客户端。所属域：前端基础设施。
 * Token 放在 localStorage 键 agenthub_token，请求头为 Authorization: Bearer。
 * code===0 才算成功；401 清登录态并跳转登录页。
 */
import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from '@/types'

/** 与 Pinia 共用的本地键名，不要改成别的，否则刷新后对不上 */
export const TOKEN_KEY = 'agenthub_token'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE,
  timeout: 15000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(body)
    }
    return response
  },
  (error: AxiosError<Result<unknown>>) => {
    const status = error.response?.status
    const msg = error.response?.data?.msg || '网络异常'
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (!location.pathname.endsWith('/login')) {
        location.href = '/login'
      }
    }
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
