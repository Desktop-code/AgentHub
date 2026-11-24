/**
 * 结果框复制。所属域：runtime（前端展示）。
 *
 * <p>复制的是用户看见的正文（已剥路由 JSON / 已把检索结构收成引用文本），
 * 不是卡片上的原始 JSON；复制内容与展示口径保持一致。
 */
import { ElMessage } from 'element-plus'
import { displayAnswerText } from '@/components/runtime/workbench/chat/chatText'

/**
 * 复制结果框里用户看见的正文。
 *
 * @param raw       卡片上的原文
 * @param workbench 是否工作台皮肤；调试皮肤复制原文
 */
export async function copyAnswerText(raw: string, workbench = true): Promise<void> {
  const text = displayAnswerText(raw, workbench)
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}
