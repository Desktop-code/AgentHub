/**
 * L3 soft 段的取值语义（纯函数，无 Vue 依赖）。所属域：agent（前端工具）。
 * <p>
 * 为什么单独抽出来：soft 输入框要同时满足三件事——
 * 1. 打开弹窗时**回显**模板默认正文或已存 override（不能只靠 placeholder 冒充）；
 * 2. 用户清空 / 改回模板默认后，语义是「未覆写」，库里不应留下空串或副本；
 * 3. 模板升级后仍读新版默认，而不是被一份陈旧的副本钉住。
 * <p>
 * 与后端 {@code PromptOverrideReader}「空白 = 未覆盖」的口径一致；
 * 因此这里所有「未覆写」判定都按 trim 后比较。
 */

/** soft 段的最小结构（与 PromptTemplateSegment 对齐，只取用到的字段） */
export interface SoftSegmentLike {
  /** 提交键；只作 key，不展示 */
  segKey: string
  /** 模板默认正文 */
  content?: string
}

/**
 * 输入框应显示的正文。
 * <p>
 * 优先级：override 里有非空值 → 用覆写；否则 → 用模板默认正文。
 * 只读，不产生副作用：不会把模板默认值写回 override。
 *
 * @param segment  当前钉死版的 soft 段
 * @param override seg_key → 文案
 * @return 输入框显示值
 */
export function softDisplayValue(
  segment: SoftSegmentLike,
  override?: Record<string, string> | null
): string {
  const overridden = override?.[segment.segKey]
  if (overridden !== undefined && overridden !== null && overridden.trim() !== '') {
    return overridden
  }
  return segment.content || ''
}

/**
 * 该段当前是否真的被覆写（override 里有非空值）。
 *
 * @param segment  当前钉死版的 soft 段
 * @param override seg_key → 文案
 * @return true 表示已覆写
 */
export function isSegmentOverridden(
  segment: SoftSegmentLike,
  override?: Record<string, string> | null
): boolean {
  const value = override?.[segment.segKey]
  return value !== undefined && value !== null && value.trim() !== ''
}

/**
 * 计算输入后的新 override 对象（不修改入参）。
 * <p>
 * 「清空」与「改回模板默认」都视为未覆写，直接删键：
 * 存空串会把 soft 段整段抹掉，存副本则会让模板升级后仍读旧正文。
 *
 * @param segment  该行对应的 soft 段
 * @param override 当前 override
 * @param value    输入框新值
 * @return 新的 override（键为空时可能删掉了该段）
 */
export function nextOverride(
  segment: SoftSegmentLike,
  override: Record<string, string> | undefined,
  value: string
): Record<string, string> {
  const next = { ...(override || {}) }
  const trimmed = (value || '').trim()
  const isDefault = trimmed === '' || trimmed === (segment.content || '').trim()
  if (isDefault) {
    delete next[segment.segKey]
  } else {
    next[segment.segKey] = value
  }
  return next
}

/**
 * soft 段下方的一行状态说明。
 *
 * @param segment  当前钉死版的 soft 段
 * @param override seg_key → 文案
 * @return 中文提示
 */
export function overrideHint(segment: SoftSegmentLike, override?: Record<string, string> | null): string {
  return isSegmentOverridden(segment, override)
    ? '已覆盖本段：保存后以本框内容为准。'
    : '跟随模板默认：清空或不改即用模板正文，改过才算覆写。'
}
