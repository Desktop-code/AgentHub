/**
 * 请求失败原因提取。所属域：前端基础设施（utils）。
 *
 * 为什么单独写：拦截器已统一弹一次 ElMessage，但页面内的失败态还要给人一个「原因」。
 * 业务错误（code !== 0）被 reject 的是 Result 体，原因在 `msg`；
 * HTTP / 网络错误被 reject 的是 AxiosError，后端文案在 `response.data.msg`，`message` 是英文兜底。
 */

/**
 * 从请求失败对象里取可读原因。
 *
 * @param error     catch 到的任意失败对象
 * @param fallback  取不到任何文案时的兜底句（默认提示检查网络）
 * @return 可读的短原因，用于失败态第二行；不会返回空串
 */
export function requestErrorText(
  error: unknown,
  fallback = '请检查网络或服务状态后重试'
): string {
  if (error && typeof error === 'object') {
    const body = error as {
      msg?: unknown
      message?: unknown
      response?: { data?: { msg?: unknown } }
    }
    // 业务错误体（{ code, msg }）优先；其次 AxiosError.response.data.msg
    const msg = body.msg ?? body.response?.data?.msg
    if (typeof msg === 'string' && msg.trim()) {
      return msg.trim()
    }
    // AxiosError.message 多为「Request failed with status code 500」，仅在无后端文案时使用
    if (typeof body.message === 'string' && body.message.trim()) {
      return body.message.trim()
    }
  }
  return fallback
}
