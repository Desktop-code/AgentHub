/**
 * Skill 触发关键词的前端归一工具。所属域：skill（纯函数，可被 node:test 直接引用）。
 *
 * 口径必须与后端 `SkillTriggerMatcher` / `SkillChecks` 一致：
 * - 库内只存一条英文逗号串；前端编辑用 Tag 数组；
 * - 用户可一次输入/粘贴多词，中英文逗号、换行都当分隔符；
 * - 去空白、去空段、忽略大小写去重（保留首次出现的写法）；
 * - 单项 ≤32 字符、最多 20 个、合并后 ≤255 字符（VARCHAR(255) 列宽）。
 *
 * 注意：这里只做录入 UX 的切割与拦截，不改后端硬触发匹配语义（仍为 contains 任一非空词）。
 */

/** 单个关键词最大字符数，对齐后端 SkillTriggerMatcher.KEYWORD_MAX */
export const KEYWORD_MAX = 32
/** 关键词个数上限，对齐后端 SkillTriggerMatcher.KEYWORD_COUNT_MAX */
export const KEYWORD_COUNT_MAX = 20
/** 库内 trigger_keywords 列宽，对齐后端 SkillChecks.KEYWORDS_COLUMN_MAX */
export const KEYWORDS_COLUMN_MAX = 255

/** 关键词数组 → 去重后的干净数组；保留首次出现的原始写法。 */
export function dedupeKeywords(words: Iterable<string>): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const word of words) {
    if (word == null) {
      continue
    }
    const text = String(word).trim()
    if (!text) {
      continue
    }
    // 大小写不同视为同一个 Tag，避免出现「OCR」「ocr」两条
    const key = text.toLowerCase()
    if (!seen.has(key)) {
      seen.add(key)
      out.push(text)
    }
  }
  return out
}

/**
 * 把一段原文按分隔符切成关键词。
 * 支持中英文逗号与换行（从表格/文档粘贴时常带换行）。
 *
 * @param raw 用户输入或粘贴的原文
 * @return 去空白、去空段后的片段（尚未去重）
 */
export function splitKeywordText(raw: string): string[] {
  if (!raw) {
    return []
  }
  return raw
    .split(/[,，\n\r]/)
    .map((word) => word.trim())
    .filter(Boolean)
}

/** 关键词数组 → 库内逗号串；与后端 join 口径一致（无有效项时返回空串）。 */
export function joinKeywords(words: Iterable<string>): string {
  return dedupeKeywords(words).join(',')
}

/** 合并结果：Tags 与首条中文提示（无异常时 error 为空串）。 */
export interface KeywordMergeResult {
  /** 合并后的 Tag 列表（已去重、已截到合法范围） */
  tags: string[]
  /** 首条超限提示；正常追加时为空串 */
  error: string
}

/**
 * 把一段原文并入已有 Tag 列表。
 *
 * 重复词静默跳过；超长/超个数/合计超列宽时停止追加并给出中文提示，
 * 不做无提示的静默截断。
 *
 * @param existing 当前 Tag 列表
 * @param raw      新输入或粘贴的原文（可含多个逗号分隔词）
 * @return 合并后的 Tag 列表与提示文案
 */
export function addKeywords(existing: readonly string[], raw: string): KeywordMergeResult {
  const tags = dedupeKeywords(existing)
  let error = ''

  for (const part of splitKeywordText(raw)) {
    // 单项超长：跳过该词，但保留其余合法词并提示
    if (part.length > KEYWORD_MAX) {
      error = error || `单个触发关键词最长 ${KEYWORD_MAX} 个字符`
      continue
    }
    // 重复词静默忽略，不打扰用户
    const key = part.toLowerCase()
    if (tags.some((tag) => tag.toLowerCase() === key)) {
      continue
    }
    // 个数顶：后续词不再接收，提示后结束
    if (tags.length >= KEYWORD_COUNT_MAX) {
      error = error || `触发关键词最多 ${KEYWORD_COUNT_MAX} 个`
      break
    }
    // 合计顶：按 join 后的字符数估算列宽，超出则不追加该词
    if (joinKeywords([...tags, part]).length > KEYWORDS_COLUMN_MAX) {
      error = error || `触发关键词合计最长 ${KEYWORDS_COLUMN_MAX} 个字符`
      break
    }
    tags.push(part)
  }

  return { tags, error }
}
