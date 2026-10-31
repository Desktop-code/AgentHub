/**
 * L3 模板名称目录（按用途缓存）。所属域：agent（前端共享状态）。
 * <p>
 * 页面上的「摘要行」只需要把 `templateId` 翻成「模板名 · vN」，
 * 若每行各调一次模板详情接口，一个 Agent 的 5 个用途槽就要发 5 次请求。
 * 这里按 purpose 拉一次「已发布版本列表」并缓存 id → 名称，
 * 摘要行只读缓存，弹窗内的正式下拉仍走 PromptL3Form 自己的加载逻辑。
 * <p>
 * 失败语义：拉取失败时不重试、不抛错，摘要退化为「模板 #id · vN」，
 * 配置入口依然可用（弹窗内会重新拉一次列表）。
 */
import { reactive } from 'vue'
import { listPromptTemplates } from '@/api/agentPrompt'

/** id → 模板名；模块级共享，跨组件复用，避免切换 Agent 时重复请求 */
const nameById = reactive<Record<number, string>>({})
/** purpose → 加载 Promise；同一用途并发调用只发一次请求 */
const pending = new Map<string, Promise<void>>()
/** 已完成的用途，命中后不再请求 */
const loaded = new Set<string>()

/**
 * 确保该用途的模板名已加载（幂等、并发安全）。
 *
 * @param purpose 用途码（CHAT / ROUTER / REWRITE / SUMMARY / MEMORY）
 * @param locale  语言；默认 zh-CN
 * @return 加载完成的 Promise，永不 reject
 */
function ensureLoaded(purpose: string, locale = 'zh-CN'): Promise<void> {
  if (!purpose) {
    return Promise.resolve()
  }
  const key = `${purpose}@${locale}`
  if (loaded.has(key)) {
    return Promise.resolve()
  }
  const running = pending.get(key)
  if (running) {
    return running
  }
  const task = listPromptTemplates({ purpose, locale })
    .then(({ data }) => {
      for (const item of data.data || []) {
        nameById[item.id] = item.name || item.templateKey || ''
      }
      loaded.add(key)
    })
    .catch(() => {
      // 拉不到就保持「未知」：不标记 loaded，下次摘要变化时会再尝试
    })
    .finally(() => {
      pending.delete(key)
    })
  pending.set(key, task)
  return task
}

/**
 * 摘要行用的名称解析。缓存未命中时返回空串，由调用方给「模板 #id」兜底。
 *
 * @param templateId 模板主键
 * @return 模板名；未知时为空串
 */
export function promptTemplateName(templateId?: number | null): string {
  if (!templateId) {
    return ''
  }
  return nameById[templateId] || ''
}

/** 供组件引用：拉取入口 + 解析入口（nameById 是 reactive，模板里读它会自动追踪） */
export function usePromptTemplateCatalog() {
  return { ensureLoaded, promptTemplateName }
}
