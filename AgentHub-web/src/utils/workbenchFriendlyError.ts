/**
 * 工作台展示收口：硬核错误友好文案 + 人设小节标题剥离。所属域：runtime（前端展示）。
 *
 * 只做展示层映射/裁剪，不改后端错误码、不改落库原文。
 * 硬核不可用才幽默化；校验失败 / 权限 / HUMAN 等待原样返回。
 */

/** 命中硬核不可用后的统一友好文案（灰色系统提示，不用红字） */
export const WORKBENCH_RESTING_HINT = '接待助手正在小憩，请稍后再试或联系管理员'

/**
 * 是否属于「无对话模型」一类硬核不可用。
 *
 * @param raw 原始错误文案
 */
export function isWorkbenchHardUnavailable(raw: string): boolean {
  const text = (raw || '').trim()
  if (!text) {
    return false
  }
  return (
    /没有可用的对话模型/.test(text) ||
    /无可用的?对话模型/.test(text) ||
    /对话模型.*(不可用|未配置|未绑定)/.test(text) ||
    /CHAT.*(不可用|未配置|未绑定)/i.test(text)
  )
}

/**
 * 将后端硬核不可用提示映射为工作台友好中文。
 *
 * @param raw 原始错误文案（接口 msg / Error.message）
 * @return 命中映射则友好文案，否则原样
 */
export function friendlyWorkbenchError(raw: string): string {
  const text = (raw || '').trim()
  if (!text) {
    return text
  }
  if (isWorkbenchHardUnavailable(text)) {
    return WORKBENCH_RESTING_HINT
  }
  return text
}

/**
 * 工作台正文展示：剥掉开头的人设/调试小节标题（Markdown ATX heading），不改落库。
 * 例如「### 通用接待：寒暄、能力介绍…」这类，只留真正对用户说的话。
 * 不误伤正文中间的标题、列表、代码块；也不动折叠行「> 已匹配…」。
 *
 * @param raw 助手正文原文
 * @return 供渲染的展示文本
 */
export function stripWorkbenchLeadHeadings(raw: string): string {
  if (!raw) {
    return raw
  }
  const lines = raw.split('\n')
  let i = 0
  // 连续剥掉文首空白行与 ATX 标题（# ~ ######）
  while (i < lines.length) {
    const line = lines[i]
    if (/^\s*$/.test(line)) {
      i += 1
      continue
    }
    if (/^\s{0,3}#{1,6}\s+\S/.test(line)) {
      i += 1
      continue
    }
    break
  }
  while (i < lines.length && /^\s*$/.test(lines[i])) {
    i += 1
  }
  return lines.slice(i).join('\n')
}
