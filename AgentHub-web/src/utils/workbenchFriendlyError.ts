/**
 * 工作台展示收口：错误分流 + 友好文案 + 人设小节标题剥离。所属域：runtime（前端展示）。
 *
 * 只做展示层映射/裁剪，不改后端错误码、不改落库原文。
 * 分流口径：
 * <ul>
 *   <li><b>resting</b>：5xx / 网关 / 超时 / 撞限，按「稍后再试」处理；</li>
 *   <li><b>model</b>：对话模型未绑定 / 不可用，页面给出「去绑定模型」；</li>
 *   <li><b>route</b>：路由 / 方案 / 候选未配置，页面给出「去配置对话路由」；</li>
 *   <li><b>other</b>：其它业务错误，原样展示，不抹平。</li>
 * </ul>
 * 文案映射仍沿用改前口径（硬核不可用 → 「小憩」），分类只决定挂不挂深链，
 * 不再为了「分类更准」继续改写这句文案。
 */

/** 命中暂时性故障后的统一友好文案（灰色系统提示，不用红字） */
export const WORKBENCH_RESTING_HINT = '接待助手正在小憩，请稍后再试或联系管理员'

/**
 * 工作台错误的展示分类。
 *
 * <p>`model` / `route` 只决定「给不给深链」，不负责改写文案：
 * 文案仍由 {@link friendlyWorkbenchError} 按改前口径输出。
 */
export type WorkbenchErrorKind = 'resting' | 'model' | 'route' | 'other'

/**
 * 是否属于暂时性故障（5xx / 网关 / 超时 / 撞限）。
 *
 * <p>只用来优先压过可行动分类：例如模型调用超时不挂「去绑定模型」，
 * 避免把一个偶发故障说成配置缺失。
 *
 * @param raw 原始错误文案
 * @return true 表示可按「稍后再试」处理
 */
export function isWorkbenchTransient(raw: string): boolean {
  const text = (raw || '').trim()
  if (!text) {
    return false
  }
  if (/\b(?:500|502|503|504)\b/.test(text)) {
    return true
  }
  /*
   * 只认「整轮 / 出站调用」层面的超时与不可用。
   * 工具自身的「操作超时」属于某一步执行失败，不能改写成接待助手在休息。
   */
  return /Internal Server Error|Bad Gateway|Service Unavailable|Gateway Timeout|timed out|本轮.{0,8}(?:超时|timeout)|(?:运行|请求|响应|上游|模型服务).{0,8}(?:超时|timeout)|撞限|服务(?:暂时)?不可用|系统繁忙|网络异常|稍后再试/i.test(
    text
  )
}

/**
 * 是否属于「模型未绑定 / 不可用」一类硬核不可用。
 *
 * <p>保留旧导出的名字，调用方不必跟着改；判定范围补上视觉 / ASR / TTS 等模型槽。
 *
 * @param raw 原始错误文案
 * @return true 表示需要先绑定模型
 */
export function isWorkbenchHardUnavailable(raw: string): boolean {
  const text = (raw || '').trim()
  if (!text) {
    return false
  }
  return (
    /没有可用的?对话模型/.test(text) ||
    /无可用的?对话模型/.test(text) ||
    /对话模型.*(不可用|未配置|未绑定)/.test(text) ||
    /CHAT.*(不可用|未配置|未绑定)/i.test(text) ||
    /(?:未绑定|没有|缺少).{0,12}(?:CHAT|对话|视觉|语音识别|ASR|TTS).{0,4}模型/i.test(text) ||
    /(?:CHAT|VISION|ASR|TTS).{0,12}(?:不可用|未配置|未绑定)/i.test(text)
  )
}

/**
 * 是否属于「改路由 / 方案配置就能继续」的一类。
 *
 * <p>文案里已经明确指向对话路由、模型配置或方案时优先给出路由深链：
 * 例如「入口未绑定视觉模型…请到对话路由的『模型配置』绑定」，修的地方在对话路由而不是模型中心。
 *
 * @param raw 原始错误文案
 * @return true 表示应给「去配置对话路由」
 */
function isWorkbenchRouteFixable(raw: string): boolean {
  const text = (raw || '').trim()
  if (!text) {
    return false
  }
  return (
    /对话路由|模型配置/.test(text) ||
    /判路由|路由.{0,24}(?:未绑定|未配置).{0,12}模型/.test(text) ||
    /未配置(?:对话)?路由|路由.*(?:未配置|未就绪|不可用)/.test(text) ||
    /没有可用(?:的)?(?:编排)?方案|既没有 Agent/.test(text) ||
    /未命中|无命中|没有命中|没有候选|候选为空/.test(text)
  )
}

/**
 * 把原始错误分到四类，供文案与深链共用同一口径。
 *
 * @param raw 原始错误文案（接口 msg / Error.message / SSE error 帧）
 * @return 展示分类；空文案归 other
 */
export function classifyWorkbenchError(raw: string): WorkbenchErrorKind {
  const text = (raw || '').trim()
  if (!text) {
    return 'other'
  }
  if (isWorkbenchTransient(text)) {
    return 'resting'
  }
  // 先认「去对话路由里配」的一类：它的原话里常同时出现「模型」，不能被模型分支抢走
  if (isWorkbenchRouteFixable(text)) {
    return 'route'
  }
  if (isWorkbenchHardUnavailable(text)) {
    return 'model'
  }
  return 'other'
}

/**
 * 将错误文案映射为工作台展示文案。
 *
 * <p>保持改前口径：硬核不可用（无对话模型等）统一换成「小憩」；校验 / 权限 / 中断等
 * 原样返回。可行动的深链由页面按 {@link classifyWorkbenchError} 的结果补，
 * 不再为了分类继续改写这句文案。
 *
 * @param raw 原始错误文案（接口 msg / Error.message）
 * @return 展示文案
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
