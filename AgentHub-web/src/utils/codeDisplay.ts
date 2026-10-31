/**
 * 调用追踪正文展示工具。所属域：obs（前端）。
 * 尝试把文本格式化为缩进 JSON，并用轻量 HTML 做键值着色；非 JSON 原样转义。
 */

/**
 * 把任意文本转成可安全塞进 v-html 的高亮片段。
 * @param raw 入参 / 出参 / attrs_json 原文
 * @returns 高亮后的 HTML（已转义）
 */
export function highlightCodeHtml(raw: string | null | undefined): string {
  if (raw == null || raw === '') {
    return '<span class="cd-empty">（空）</span>'
  }
  const pretty = tryPrettyJson(raw)
  if (pretty != null) {
    return colorizeJson(pretty)
  }
  return escapeHtml(raw)
}

/**
 * 尝试解析并美化 JSON；失败返回 null，由调用方走纯文本。
 */
export function tryPrettyJson(raw: string): string | null {
  const trimmed = raw.trim()
  if (!trimmed) {
    return null
  }
  // 仅对对象 / 数组尝试解析，避免把纯数字或布尔误当成 JSON
  if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) {
    return null
  }
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return null
  }
}

function colorizeJson(pretty: string): string {
  // 按 token 替换：字符串 → 键名或值；数字 / 布尔 / null
  return pretty.replace(
    /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
    (match, str: string | undefined, isKey: string | undefined, lit: string | undefined) => {
      if (str != null) {
        if (isKey) {
          return `<span class="cd-key">${escapeHtml(str)}</span>${isKey}`
        }
        return `<span class="cd-str">${escapeHtml(str)}</span>`
      }
      if (lit != null) {
        return `<span class="cd-lit">${lit}</span>`
      }
      return `<span class="cd-num">${match}</span>`
    }
  )
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
