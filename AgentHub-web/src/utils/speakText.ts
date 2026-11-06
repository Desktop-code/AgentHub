/**
 * 把助手回答的 Markdown 转成「适合朗读」的纯文本。所属域：前端展示工具。
 *
 * 为什么不能直接把 Markdown 丢给 TTS：`**重点**`、`###`、表格分隔线都会被逐字念出来，
 * 听感极差。这里只做朗读前的轻量清洗，不追求完整 Markdown 语义——
 * 页面上仍用 renderSimpleMarkdown 渲染，两者互不替代。
 *
 * 取舍：
 * - 代码块整段略过（读一大段 JSON / 代码没有意义），用「代码块略」占位；
 * - 图片只读 alt，链接只读链接文字；
 * - 表格保留单元格文字，但去掉 | --- | 这类分隔行。
 *
 * @param markdown 助手回答原文
 * @return 适合朗读的纯文本；原文为空时返回空串
 */
export function toSpeakText(markdown: string): string {
  if (!markdown) {
    return ''
  }
  return markdown
    // 代码块：整段替换成一句提示，避免念出缩进与符号
    .replace(/```[\s\S]*?```/g, ' 代码块略 ')
    .replace(/~~~[\s\S]*?~~~/g, ' 代码块略 ')
    // 图片：只保留 alt
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    // 链接：只保留文字
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    // HTML 标签
    .replace(/<[^>]+>/g, ' ')
    // 标题、引用、列表符号
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/^\s*>\s?/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+\.\s+/gm, '')
    // 表格分隔行（| --- | :---: |）
    .replace(/^\s*\|?[\s:|-]+\|[\s:|-]*$/gm, '')
    .replace(/\|/g, ' ')
    // 强调、行内代码、删除线
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(\*|_)(.*?)\1/g, '$2')
    .replace(/~~(.*?)~~/g, '$1')
    .replace(/`([^`]*)`/g, '$1')
    // 转义字符与多余空白
    .replace(/\\([\\`*_{}[\]()#+\-.!>])/g, '$1')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{2,}/g, '\n')
    .trim()
}
