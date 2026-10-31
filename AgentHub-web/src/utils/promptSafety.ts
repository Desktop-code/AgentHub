/**
 * 用户业务段的前端检查和推荐模板。所属域：agent。
 * 规则与后端 PromptSafetyChecker 对齐，只做保存前提示；以后端结果为准。
 * 这里只有形态特征，不写真实密钥、地址或租户。
 */

/** 与 agenthub.agent.prompt-max-chars 默认值、AgentChecks.PROMPT_MAX 一致，按 JS 字符计 */
export const PROMPT_MAX_CHARS = 12000

const KEY_HINT = /sk-|bearer\s+|api[_-]?key|-----BEGIN/i
const LONG_TOKEN = /(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{40,}={0,2}(?![A-Za-z0-9+/])/
const JWT_LIKE = /eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}/
const OVERRIDE =
  /忽略(?:以上|之前|系统|平台)(?:规则|约束|提示)|ignore\s+(?:all\s+)?(?:previous|above|system)\s+instructions|<\/system>|\[SYSTEM\]/i
const EXFIL =
  /curl\s+http|wget\s+|(?:把|将).{0,20}(?:对话|聊天记录|内容).{0,40}(?:post|发送|上传).{0,40}https?:\/\//i

export interface PromptCheck {
  errors: string[]
  warnings: string[]
}

/**
 * ReAct 用户业务段默认模板（助手 + 优先工具）。
 *
 * @param name 当前表单名称；空则用「助手」
 */
export function recommendedReactPrompt(name: string): string {
  const label = name.trim() || '助手'
  return [
    `你是「${label}」智能助手。请用简洁、准确的中文回答用户问题。`,
    '- 需要外部信息或操作时，优先调用已绑定的工具，并根据工具结果作答。',
    '- 不确定时如实说明，不要编造数据。',
    '- 输出条理清晰；涉及步骤时分点说明。'
  ].join('\n')
}

/**
 * Router 用户业务段默认模板（只做意图分发，不代跑工具链）。
 *
 * @param name 当前表单名称；空则用「路由助手」
 */
export function recommendedRouterPrompt(name: string): string {
  const label = name.trim() || '路由助手'
  return [
    `你是「${label}」路由助手。根据用户意图选择合适的子 Agent；一句可含多个意图。`,
    '- 输出有序 JSON 数组：[{"route_key":"...","sub_query":"切给该子的子问题"}, ...]；单意图长度为 1。',
    '- 不要代替子 Agent 执行专业工具链。',
    '- 仅在无法匹配子节点时，才可简短直答或说明无法路由。'
  ].join('\n')
}

/**
 * 计划执行用户业务段默认模板（先拆计划再逐步执行）。
 *
 * @param name 当前表单名称；空则用「执行助手」
 */
export function recommendedPlanPrompt(name: string): string {
  const label = name.trim() || '执行助手'
  return [
    `你是「${label}」计划执行助手。收到任务后先拆成有序步骤，再逐步执行并给出结论。`,
    '- 步骤要具体可执行；能一步完成就不要拆多步。',
    '- 每一小步只使用已绑定的工具取真实结果，不要编造数据。',
    '- 全部步骤完成后，用一段连贯的中文给出最终答复，不要罗列内部步骤编号。'
  ].join('\n')
}

/**
 * 「填入模板」/ 新建默认用。按 runMode 区分 ReAct / Router / 计划执行。
 *
 * @param name 当前表单名称
 * @param runMode REACT | ROUTER | PLAN_AND_EXECUTE；其它值按 REACT
 * @return 用户业务段模板，不含平台前缀
 */
export function recommendedPrompt(name: string, runMode: string = 'REACT'): string {
  if (runMode === 'ROUTER') {
    return recommendedRouterPrompt(name)
  }
  if (runMode === 'PLAN_AND_EXECUTE') {
    return recommendedPlanPrompt(name)
  }
  return recommendedReactPrompt(name)
}

/**
 * 切换运行模式时决定是否替换用户段。
 * 仅当正文为空，或仍等于「切换前模式」的默认模板时，才返回新模式默认模板；否则返回 null（不覆盖自定义）。
 *
 * @param current 当前用户段
 * @param name 当前 Agent 名称（用于拼默认模板）
 * @param fromMode 切换前模式
 * @param toMode 切换后模式
 */
export function promptAfterModeSwitch(
  current: string,
  name: string,
  fromMode: string,
  toMode: string
): string | null {
  if (!toMode || fromMode === toMode) {
    return null
  }
  const trimmed = (current || '').trim()
  const oldDefault = recommendedPrompt(name, fromMode).trim()
  if (!trimmed || trimmed === oldDefault) {
    return recommendedPrompt(name, toMode)
  }
  return null
}

/**
 * 失焦和保存前调用。错误挡住保存，警告可以继续。
 *
 * @param userPrompt 用户业务段
 * @return 错误与警告，文案与后端一致
 */
export function checkUserPrompt(userPrompt: string): PromptCheck {
  const text = userPrompt ?? ''
  const errors: string[] = []
  const warnings: string[] = []
  if (text.length > PROMPT_MAX_CHARS) {
    errors.push(`系统提示词超过长度，最长 ${PROMPT_MAX_CHARS}`)
  }
  if (text && (KEY_HINT.test(text) || LONG_TOKEN.test(text) || JWT_LIKE.test(text))) {
    errors.push('请勿在提示词中填写密钥或 Token')
  }
  if (OVERRIDE.test(text)) {
    warnings.push('提示词含有覆盖系统规则的表述，运行时平台约束仍会加在前面')
  }
  if (EXFIL.test(text)) {
    warnings.push('提示词含有外连或导出对话的表述')
  }
  return { errors, warnings }
}
