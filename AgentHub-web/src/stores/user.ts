/**
 * 登录态与权限。所属域：前端会话。
 * token 与 utils/request 使用同一个 localStorage 键。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchMe, logout as logoutApi } from '@/api/auth'
import { TOKEN_KEY } from '@/utils/request'
import { TRIAL_MENU_PATH } from '@/composables/agentTrial'
import type { MeInfo, MenuNode } from '@/types'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const profile = ref<MeInfo | null>(null)
  const loaded = ref(false)

  /** 写入 token，并同步到本地，刷新后请求拦截器还能带上。 */
  function setToken(value: string) {
    token.value = value
    localStorage.setItem(TOKEN_KEY, value)
  }

  /** 清本地登录态。拉取 /me 失败与主动注销共用，保证不留下半截 token。 */
  function clearSession() {
    token.value = ''
    profile.value = null
    loaded.value = false
    localStorage.removeItem(TOKEN_KEY)
  }

  /**
   * 拉取 /me，写入用户信息与权限。
   * 失败说明当前 token 已不可用：先清掉本地登录态再抛错，
   * 调用方（登录页 / 路由守卫）无需再自己回滚 token。
   */
  async function loadMe() {
    try {
      const { data } = await fetchMe()
      profile.value = data.data
      loaded.value = true
      return profile.value
    } catch (error) {
      clearSession()
      throw error
    }
  }

  /** 按钮是否显示。权限来自 /me，不在前端另造一套码。 */
  function hasPerm(code: string) {
    return profile.value?.permissions?.includes(code) ?? false
  }

  /** 找到侧栏第一个可打开的页面路径。 */
  function firstPath(nodes: MenuNode[] = profile.value?.menus ?? []): string | null {
    for (const node of nodes) {
      if (node.children && node.children.length > 0) {
        const child = firstPath(
          node.children.filter((item) => item.visible !== 0 && item.path !== TRIAL_MENU_PATH)
        )
        if (child) {
          return child
        }
      }
      if (node.path && node.path !== '/system' && node.path !== TRIAL_MENU_PATH) {
        return node.path
      }
    }
    return null
  }

  /** 服务端注销失败也清本地，避免卡在坏 token 上。 */
  async function logout() {
    try {
      if (token.value) {
        await logoutApi()
      }
    } finally {
      clearSession()
    }
  }

  return { token, profile, loaded, setToken, loadMe, hasPerm, firstPath, logout }
})
