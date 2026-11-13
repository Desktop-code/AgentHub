/**
 * 工作台输入框 @ 换线：纯函数辅助。所属域：runtime（前端）。
 *
 * <p>候选过滤与「@片段」剥离不依赖 Vue 组件，便于本地单测；
 * 真正的 PUT / sticky / force_route_key 仍由 useWorkbenchChat 调现网 API。
 */

import type { WorkbenchRouteOption } from '@/types/workbench'

/**
 * 按展示名（agentName）模糊过滤候选；永不按 route_key 匹配（不暴露、不检索键）。
 *
 * @param candidates 本会话快照方案启用项
 * @param query      @ 后的检索片段；空则原样返回
 * @return 过滤后的候选（保持原序）
 */
export function filterWorkbenchAtCandidates(
  candidates: WorkbenchRouteOption[] | undefined | null,
  query: string
): WorkbenchRouteOption[] {
  const list = candidates ?? []
  const q = (query || '').trim().toLowerCase()
  if (!q) {
    return list
  }
  return list.filter((item) => (item.agentName || '').toLowerCase().includes(q))
}

/**
 * 从输入正文末尾识别未闭合的 @ 检索片段。
 *
 * @param value 输入框全文
 * @return start=@ 下标、query=检索词；无则 null
 */
export function detectWorkbenchAtTrigger(
  value: string
): { start: number; query: string } | null {
  const match = /(^|[\s\n])@([^\s@]*)$/.exec(value || '')
  if (!match) {
    return null
  }
  const query = match[2]
  const start = (value || '').length - query.length - 1
  return { start, query }
}

/**
 * 去掉输入框里的 @片段，得到纯正文（发消息用；不把 @展示名拼进 query）。
 *
 * @param value 原文
 * @param start @ 起始下标；&lt;0 则原样返回
 * @return 剥离后的正文
 */
export function stripWorkbenchAtFragment(value: string, start: number): string {
  if (start < 0 || !value) {
    return value || ''
  }
  const before = value.slice(0, start).replace(/\s+$/, '')
  const after = value.slice(start).replace(/^@[^\s@]*/, '').replace(/^\s+/, '')
  return before && after ? `${before} ${after}` : `${before}${after}`
}
