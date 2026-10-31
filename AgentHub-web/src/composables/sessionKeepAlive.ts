/**
 * 登录态保活。所属域：前端 auth。
 * 已登录且页签可见时，每隔 10 分钟轻量请求 /api/auth/me，触发后端 Sa-Token 滑动续期。
 * 不做 refreshToken 双票；401 仍由 request 拦截器清票并跳 /login。
 */
import { onMounted, onUnmounted, watch } from 'vue'
import { fetchMe } from '@/api/auth'
import { useUserStore } from '@/stores/user'

/** 保活间隔：≤ 会话窗口一半，闲置满 20 分钟才会掉线 */
const KEEP_ALIVE_INTERVAL_MS = 10 * 60 * 1000

/**
 * 在已登录壳（如 AdminLayout）挂载：有 token 且 document 可见时定时打 /me。
 * 页签隐藏则停表；重新可见时立刻补一次再恢复定时。
 */
export function useSessionKeepAlive() {
  const user = useUserStore()
  let timer: ReturnType<typeof setInterval> | null = null

  /** 停掉定时器，避免隐藏页签或登出后继续打接口。 */
  function clearTimer() {
    if (timer != null) {
      clearInterval(timer)
      timer = null
    }
  }

  /**
   * 轻量保活：仅在有 token 且页签可见时请求 /me。
   * 失败（含 401）交给 request 拦截器，本处不另清票。
   */
  async function ping() {
    if (!user.token || document.visibilityState !== 'visible') {
      return
    }
    try {
      await fetchMe()
    } catch {
      // 401 已由拦截器处理；其它错误不打断壳
    }
  }

  /** 有 token 时按间隔开表；登出或无票则停。 */
  function startTimer() {
    clearTimer()
    if (!user.token) {
      return
    }
    timer = setInterval(() => {
      void ping()
    }, KEEP_ALIVE_INTERVAL_MS)
  }

  /**
   * 可见性变化：隐藏停表；回来立刻 ping 一次并重开定时，避免长挂后台后 idle 过期。
   */
  function onVisibilityChange() {
    if (document.visibilityState !== 'visible') {
      clearTimer()
      return
    }
    if (!user.token) {
      return
    }
    void ping()
    startTimer()
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange)
    if (user.token && document.visibilityState === 'visible') {
      startTimer()
    }
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    clearTimer()
  })

  // 登录写入 / 注销清空 token 时同步开停保活
  watch(
    () => user.token,
    (token) => {
      if (token && document.visibilityState === 'visible') {
        startTimer()
      } else {
        clearTimer()
      }
    }
  )
}
