/**
 * JSONLogic 表达式的一句话摘要。所属域：workflow 前端展示层。
 * <p>调用方：画布节点卡片（条件节点摘要）、未来的属性面板只读回显。
 * <p>边界：只做「看得懂」的浅层拼装，不校验语义、不求值；表达式不合法时原样截断返回，
 * 让用户在画布上仍能看出写了什么，而不是显示「已配置」这类无信息文案。
 */

/** 摘要最大长度：节点卡片宽度有限，超出由 CSS 省略号截断 */
const MAX_LEN = 48

/** 视为「叶子」的字面量类型，直接按 JSON 输出 */
const OPERATOR_HINTS: Record<string, string> = {
  '==': '=',
  '===': '=',
  '!=': '≠',
  '!==': '≠',
  '>': '>',
  '>=': '≥',
  '<': '<',
  '<=': '≤',
  and: '且',
  or: '或'
}

/**
 * 生成 JSONLogic 的简读摘要。
 *
 * @param value config.condition，可能是对象、字符串（历史脏数据）或 null
 * @return 形如 `type = refund`、`$.input.amount ≥ 100`；空表达式返回空串
 */
export function summarizeJsonLogic(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  // 历史数据可能是手写字符串：直接截断展示，不要二次解析成对象
  if (typeof value === 'string') {
    return truncate(value.trim())
  }
  const text = render(value)
  return truncate(text)
}

/** 递归渲染：单键对象视为操作符，其余按字面量 */
function render(node: unknown): string {
  if (node === null || node === undefined) {
    return ''
  }
  if (Array.isArray(node)) {
    return node.map((item) => render(item)).filter(Boolean).join('，')
  }
  if (typeof node !== 'object') {
    return String(node)
  }
  const entries = Object.entries(node as Record<string, unknown>)
  if (entries.length !== 1) {
    // 多键对象不是合法 JSONLogic 操作符，按紧凑 JSON 展示，避免误读
    return safeJson(node)
  }
  const [op, rawArgs] = entries[0]
  const args = Array.isArray(rawArgs) ? rawArgs : [rawArgs]
  const left = args.map((item) => render(item)).filter(Boolean)
  if (op === 'var' && left.length) {
    return left[0]
  }
  if (op === 'if' && left.length) {
    return `if ${left.join('，')}`
  }
  const hint = OPERATOR_HINTS[op]
  if (hint) {
    if (left.length === 1) {
      return `${hint}${left[0]}`
    }
    return left.join(` ${hint} `)
  }
  // 未知操作符（如 in / cat）保留原名，便于对照后端支持范围
  return `${op}(${left.join('，')})`
}

/** 兜底 JSON 字符串化；循环引用等异常返回空串 */
function safeJson(node: unknown): string {
  try {
    return JSON.stringify(node)
  } catch {
    return ''
  }
}

/** 超长摘要截断，避免撑破节点卡片 */
function truncate(text: string): string {
  if (!text) {
    return ''
  }
  return text.length > MAX_LEN ? `${text.slice(0, MAX_LEN)}…` : text
}
