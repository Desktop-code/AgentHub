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

  /** 拉取 /me。失败时清掉半截登录态。 */
  async function loadMe() {
    const { data } = await fetchMe()
    profile.value = data.data
    loaded.value = true
    return profile.value
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
      token.value = ''
      profile.value = null
      loaded.value = false
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  return { token, profile, loaded, setToken, loadMe, hasPerm, firstPath, logout }
})
