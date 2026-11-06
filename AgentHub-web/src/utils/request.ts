/**
 * HTTP 客户端。所属域：前端基础设施。
 * Token 放在 localStorage 键 agenthub_token，请求头为 Authorization: Bearer。
 * code===0 才算成功；401 清登录态并跳转登录页。
 */
import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import type { Result } from '@/types'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * 关掉全局错误提示，由调用方自行处理。
     * 语音接口用它：合成失败要降级浏览器朗读并给「轻提示」，不适合再弹一条红色报错。
     */
    silent?: boolean
  }
}

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
      // 静默请求（语音）由调用方自己给提示，这里不再重复弹窗
      if (!response.config.silent) {
        ElMessage.error(body.msg || '请求失败')
      }
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
    if (!error.config?.silent) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default request
