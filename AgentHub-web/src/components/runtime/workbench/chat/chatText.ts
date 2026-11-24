/**
 * 对话正文的展示文本。所属域：runtime（前端展示）。
 *
 * <p>职责：把「数据层的原文」转成「终端用户看见的正文 / 思考 / 错误」，
 * 只做渲染层脱敏与 Markdown 渲染，不改 turns 落库内容，直播与回放共用同一套口径。
 *
 * <p>为什么单独成模块：对话壳层、参与者卡与工作流卡都要渲染同一段 Markdown；
 * 收在一处后，三处不会各自决定「要不要剥路由 JSON / 要不要把工具名换成中文」。
 * 管理端调试皮肤仍原样展示，因此所有函数都显式接收 `workbench` 布尔，不读全局状态。
 */
import { renderMarkdownMemo } from '@/utils/simpleMarkdown'
import { stripWorkbenchLeadHeadings } from '@/utils/workbenchFriendlyError'
import { friendlyWorkbenchError } from '@/utils/workbenchFriendlyError'
import { stripRouteDecisionJson, stripWorkbenchInternalKeys } from '@/components/runtime/workbench/workbenchMessageMeta'
import { maskToolCallNames } from '@/components/runtime/workbench/workbenchToolPill'
import { presentVisibleResult } from '@/components/runtime/workbench/workbenchVisibleText'
import { toolTitleByName } from '@/components/runtime/workbench/chat/toolTitles'

/**
 * Markdown 渲染（带缓存槽）。所有正文 / 思考 / 节点文字都走这一条。
 *
 * @param slot      缓存槽键；同槽内容不变时复用上一帧 HTML
 * @param value     原文
 * @param streaming 是否仍在流式（半截表按源码展示，避免闪烁）
 * @return 可直接 v-html 的 HTML
 */
export function markdownText(slot: string, value: string, streaming = false): string {
  return renderMarkdownMemo(slot, value, streaming)
}

/**
 * 工作台思考区文本：抹掉 route_key 等内部键；调试形态原样保留排障信息。
 *
 * @param raw       思考正文 / 工具行 / 路由决策 JSON
 * @param workbench 是否工作台皮肤
 * @return 可展示文本
 */
export function thinkTextOf(raw: string, workbench: boolean): string {
  return workbench ? stripWorkbenchInternalKeys(raw) : raw
}

/**
 * 工作台正文展示：剥文首人设/调试小节标题；调试形态原样。
 *
 * <p>直播终态和回放共用这一条，只影响渲染，不改 turns 落库内容。
 *
 * @param raw       结果框原文（助手正文或工作流终答）
 * @param workbench 是否工作台皮肤
 * @return 交给 Markdown 的可见正文
 */
export function displayAnswerText(raw: string, workbench: boolean): string {
  // 气泡正文是用户可见终稿：路由 JSON 与检索结构都不原样铺出来
  if (!workbench) {
    return raw
  }
  // 调用名换成目录标题：胶囊已是中文，正文不再重复函数名。目录未到时原样
  return maskToolCallNames(
    presentVisibleResult(stripRouteDecisionJson(stripWorkbenchLeadHeadings(raw))),
    toolTitleByName
  )
}

/**
 * 工作台错误展示：硬核不可用 → 友好灰字；其它原样。调试形态原样红字。
 *
 * @param raw       错误原文
 * @param workbench 是否工作台皮肤
 * @return 可展示文案
 */
export function displayErrorTextOf(raw: string, workbench: boolean): string {
  return workbench ? friendlyWorkbenchError(raw) : raw
}
