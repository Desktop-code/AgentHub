/**
 * 工具调用名 → 目录标题的共享缓存。所属域：runtime（前端展示）。
 *
 * <p>工作台对话区与工作流节点胶囊都读这一份：两边各自拉一次目录会发两次请求，
 * 也会出现「同一次运行里两处工具名不一致」。这里只缓存 title，不缓存权限或调用结论。
 *
 * <p>失败（无权限 / 网络）只留空表，调用方回退到中性「工具」文案，不在前端写死英文名对照。
 */
import { reactive } from 'vue'
import { listAllItems } from '@/api/tool'

/** 调用名（英文函数名）→ 工具项标题（中文）。 */
export const toolTitleByName = reactive<Record<string, string>>({})

/** 是否已经成功拉过一次目录；只是避免同一次打开里重复请求，失败不写标记。 */
let loaded = false
/** 正在请求的 Promise：并发调用共享同一次请求。 */
let loading: Promise<void> | null = null

/**
 * 拉一次工具项目录并写入共享缓存。
 *
 * @param enabled 当前是否为工作台皮肤；非工作台不请求，保持管理端运行原行为
 * @return 请求完成的 Promise（失败时也不 reject，只在缓存里留空）
 */
export async function loadToolTitles(enabled: boolean): Promise<void> {
  if (!enabled || loaded) {
    return
  }
  if (loading) {
    return loading
  }
  loading = (async () => {
    try {
      const rows = await listAllItems()
      for (const row of rows) {
        const name = (row.name || '').trim()
        const title = (row.title || '').trim()
        if (name && title) {
          toolTitleByName[name] = title
        }
      }
      loaded = true
    } catch {
      // 工作台账号不一定有工具目录权限：没有标题就不显示函数名
    } finally {
      loading = null
    }
  })()
  return loading
}
