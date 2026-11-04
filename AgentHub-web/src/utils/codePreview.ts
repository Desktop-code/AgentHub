/**
 * 入出参短预览判定。所属域：obs（前端展示）。
 *
 * <p>纯函数、零 DOM 依赖，便于 node:test 直接跑：组件只负责渲染，
 * 「这段文本要不要出『展开全文』按钮」由这里统一算，避免各页各写一套阈值。
 */

/** 12px 字号 × 1.55 行高，再留一点余量（px） */
export const CODE_LINE_HEIGHT_PX = 18.6

/** 预览区上下 padding 合计（px） */
const PREVIEW_PADDING_PX = 20

/** 预览高度下限（px）：防止调用方传 0 / 极小值导致「任何内容都算溢出」 */
const MIN_PREVIEW_HEIGHT_PX = 60

/**
 * 短预览能容纳的行数。
 *
 * @param previewHeight 预览高度（px）；过小会按下限高度处理
 * @return 至少 2 行，避免高度过小时把按钮判没
 */
export function previewLineBudget(previewHeight: number): number {
  const height = Math.max(MIN_PREVIEW_HEIGHT_PX, previewHeight)
  return Math.max(2, Math.floor((height - PREVIEW_PADDING_PX) / CODE_LINE_HEIGHT_PX))
}

/**
 * 这段文本是否会超出短预览。
 *
 * <p>行数或字符数任一超标即算超出：超长单行（压缩过的 JSON / 整段 Markdown）
 * 行数只有 1，但视觉上同样会被截断，必须给出展开入口。
 *
 * @param text          原文
 * @param previewHeight 预览高度（px）；过小会按下限高度处理
 * @return 是否需要「展开全文」按钮
 */
export function isCodeOverflow(text: string | null | undefined, previewHeight: number): boolean {
  const raw = text ?? ''
  if (!raw) {
    return false
  }
  if (raw.split('\n').length > previewLineBudget(previewHeight)) {
    return true
  }
  return raw.length > Math.max(MIN_PREVIEW_HEIGHT_PX, previewHeight) * 4
}
