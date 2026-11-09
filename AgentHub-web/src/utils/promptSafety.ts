/**
 * 用户业务段的前端检查。所属域：agent。
 * 规则与后端 PromptSafetyChecker 对齐，只做保存前提示；以后端结果为准。
 * 这里只有形态特征，不写真实密钥、地址或租户。
 *
 * <p>只保留校验：历史上那套「按运行模式生成推荐提示词 / 切模式自动替换正文」的模板文本
 * 已经整体下线——它会凭空往「系统提示词」里灌一段正文，与已绑定模板形成双轨，
 * 让「只切了个模式就保存不了」成为常见现象。正文现在只表达配置者自己写的内容。
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
 * 提交前收敛「旧系统提示词」。
 *
 * <p>挂上 L3 模板后，{@code system_prompt} 不再作为独立的整段业务提示词存在，
 * 后端会以「模板 + 旧正文双轨」直接拒绝保存。历史上这类正文可能已经留在库里，
 * 因此统一收敛：还残留正文就并入「追加要求」（等价于原弹窗的「迁移到追加要求」），
 * 并把 systemPrompt 置空后一起提交。这样切模式 / 直接保存都不会再被这条闸门挡住，
 * 也不会静默丢字。
 *
 * <p>未挂模板时原样返回：业务正文仍是该 Agent 唯一的提示词来源。
 *
 * @param systemPrompt 当前业务提示词正文
 * @param promptExtra  当前追加要求
 * @param templateId   已选模板 id；为空表示未挂模板
 * @return 可直接提交的正文与追加要求
 */
export function settleLegacyPrompt(
  systemPrompt: string,
  promptExtra: string,
  templateId: number | null | undefined
): { systemPrompt: string; promptExtra: string } {
  const legacy = (systemPrompt || '').trim()
  if (!templateId || !legacy) {
    return { systemPrompt: systemPrompt || '', promptExtra: promptExtra || '' }
  }
  const extra = (promptExtra || '').trim()
  return {
    systemPrompt: '',
    promptExtra: extra ? `${extra}\n\n${legacy}` : legacy
  }
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
