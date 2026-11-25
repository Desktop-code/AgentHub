/**
 * 工作台「其它 / 自填」答复的共用纯逻辑。所属域：runtime（前端展示）。
 *
 * <p>职责：
 * <ol>
 *   <li>平台固定追加的自填行文案，以及「模型是否已经自带其它项」的展示去重判定；
 *       工作流、MA、单 Agent 三端都走 {@code ChoiceChips}，因此只在这里维护一份；</li>
 *   <li>自填答复的浅层校验：trim 后非空、长度按 {@link WORKBENCH_ANSWER_MAX_CHARS} 对齐、
 *       拒绝不可见控制字符。</li>
 * </ol>
 *
 * <p>边界：这里只做输入形态的浅层拦截与提示，<b>不是安全边界</b>；
 * 用户文本只作为 user message 进入，工具与资源权限始终由平台服务端把关，
 * 平台锁定要求仍保持在最后一段，不因用户输入而改变顺序。
 */

/** 平台追加的自填行文案：不写进模型的 choice.options，只由前端固定补一行 */
export const WORKBENCH_OTHER_CHOICE_LABEL = '其它，我自己说'

/**
 * 自填答复的长度上限（按 Unicode 码点计）。
 *
 * <p>与后端续作侧的原问句保留上限（2000）对齐；超长只提示、不静默截断，
 * 让用户自己删减，避免把被截断的答复当成完整答复续跑。
 */
export const WORKBENCH_ANSWER_MAX_CHARS = 2000

/** 控制字符：除换行 / 回车 / 制表符外都可能破坏文本协议或界面的不可见字符 */
const CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/

/** 括号包裹的补充说明（如「其它（请说明）」）：只用于同义判定，不改写展示文案 */
const BRACKETED_NOTE = /[（(【\[][^）)】\]]*[）)】\]]/g

/** 同义判定时忽略的空白与标点：让「其它，我自己说」「其它 - 自填」落到同一个键 */
const KEY_NOISE = /[\s\-—–·|｜:：,，、.。;；!！?？~～/／]+/g

/**
 * 其它项的同义写法。命中即视为「模型自带的其它」，展示时去重，
 * 由平台固定行替代——否则用户会看到两行「其它」，其中一行还会被当答复提交。
 *
 * <p>只认「整条文案就是其它 / 自定义」这类写法：像「其他部门的审批流程」这种
 * 带实质内容的长句不在此列，避免误删真正的候选。
 */
const OTHER_LIKE_PATTERNS: readonly RegExp[] = [
  /^其[它他](?:(?:我)?自己(?:说|写|填|填写|输入)|选项|项|方式|方案|答案|回答|答复|说明|补充|内容|情况|原因)?$/,
  /^自定义(?:选项|方式|回答|答复|内容|输入|填写|说明)?$/,
  /^自填(?:内容|回答|答复|选项)?$/,
  /^自己(?:说|写|填|填写|输入|回答|答复|补充)$/,
  /^自行(?:填写|输入|回答|答复|说明|补充)$/,
  /^以上都不是$/,
  /^都不是$/,
  /^都不合适$/,
  /^没有合适的(?:选项|方案)?$/,
  /^(?:other|others|custom)$/
]

/** 自填校验的失败原因；合法时为空串 */
export type WorkbenchAnswerInvalid = '' | 'empty' | 'too-long' | 'control'

/** 一次自填校验的结果 */
export interface WorkbenchAnswerCheck {
  /** 是否通过 */
  ok: boolean
  /** trim 后的文本；失败时保留原文，便于用户看到自己写了什么 */
  text: string
  /** 失败原因；合法时为空串 */
  code: WorkbenchAnswerInvalid
  /** 可直接展示给用户的提示；合法时为空串 */
  message: string
}

/**
 * 把文案归一成同义判定键：去掉括号补充说明、空白与常见标点，再转小写。
 *
 * <p>只用于判断「是不是其它项」，不参与展示与提交，原始 label 始终原样保留。
 *
 * @param label 模型给出的选项文案，可空
 * @return 归一后的比对键；空文案返回空串
 */
function otherLikeKey(label: string): string {
  return (label || '')
    .replace(BRACKETED_NOTE, '')
    .replace(KEY_NOISE, '')
    .toLowerCase()
}

/**
 * 这条选项文案是否属于「其它 / 自填」的同义写法。
 *
 * <p>命中项由 {@code ChoiceChips} 从模型选项里过滤掉，再由平台固定追加一行，
 * 保证页面上始终只有一行「其它」，且点击语义固定为「只进入输入」。
 *
 * @param label 模型给出的选项文案，可空
 * @return true 表示应被平台自填行替代
 */
export function isOtherChoiceLabel(label: string): boolean {
  const key = otherLikeKey(label)
  if (!key) {
    return false
  }
  return OTHER_LIKE_PATTERNS.some((pattern) => pattern.test(key))
}

/**
 * 校验用户自己写下的答复。
 *
 * <p>顺序固定：先 trim 判空，再判长度，最后拦不可见控制字符。
 * 长度不是静默截断——超长返回完整原文与明确提示，由用户自行删减后重发。
 *
 * @param raw 输入框原文（未 trim）
 * @return 校验结果；ok=true 时 text 为可直接发送的 trim 文本
 */
export function validateWorkbenchAnswer(raw: string): WorkbenchAnswerCheck {
  const text = (raw || '').trim()
  if (!text) {
    return { ok: false, text, code: 'empty', message: '请先输入答复，再发送' }
  }
  // 按码点计数：中文与表情都按「一个字」对齐，避免 emoji 被算成两个字符
  const chars = Array.from(text).length
  if (chars > WORKBENCH_ANSWER_MAX_CHARS) {
    return {
      ok: false,
      text,
      code: 'too-long',
      message: `答复最多 ${WORKBENCH_ANSWER_MAX_CHARS} 字，当前 ${chars} 字，请删减后再发送`
    }
  }
  if (CONTROL_CHARS.test(text)) {
    return {
      ok: false,
      text,
      code: 'control',
      message: '答复里含有不可见的控制字符，请删掉后重试'
    }
  }
  return { ok: true, text, code: '', message: '' }
}

/**
 * 自填答复提交时的去处（见 {@link routeWorkbenchAnswer}）。
 */
export type WorkbenchAnswerRoute = 'reply-waiting' | 'agent-choice' | 'new-turn'

/**
 * 自填答复提交时的去处。
 *
 * <p>三种结局：
 * <ul>
 *   <li>{@code reply-waiting}：工作流 / 方案正停在「等待用户回复」——仍走普通发送，
 *       由后端把这句话续进同一条运行。这是本刀之前已经修好的行为，不在这里改道。</li>
 *   <li>{@code agent-choice}：单 Agent 的选项还开着——走 {@code /choice} 的 {@code text}
 *       通道回到同一位专家。走普通发送会被入口重新判路由，正是本刀要修的现象。</li>
 *   <li>{@code new-turn}：普通提问（没点「其它」）、带了附件，或已经没有任何可续选项——
 *       保持原有发送语义。</li>
 * </ul>
 *
 * <p>判定顺序固定：先让工作流 / 方案的等待优先。它们也画着「其它」行，
 * 但点击后的答复语义与单 Agent 不同（不落用户气泡、由后端投影写进询问节点），
 * 不能因为本刀把它们的自填改道到 {@code /choice}。
 *
 * <p>带附件时退回普通发送：续作端点不接收附件，静默丢掉附件比「重新判一次路由」更糟。
 *
 * <p>只是输入路由的浅层判定，不是安全边界；服务端仍会校验上一轮确有可点选项块。
 *
 * @param input.customAnswer   是否通过「其它 / 自填」进入输入
 * @param input.waitingReply   最后一条助手轮是否（工作流 / 方案）在等用户回复
 * @param input.agentChoiceOpen 最后一条助手轮是否有可点的单 Agent 选项块
 * @param input.hasAttachments 本次是否带图片 / 文件
 * @return 提交去处
 */
export function routeWorkbenchAnswer(input: {
  customAnswer: boolean
  waitingReply: boolean
  agentChoiceOpen: boolean
  hasAttachments: boolean
}): WorkbenchAnswerRoute {
  // 工作流 / 方案等待优先：它们的自填已经走普通发送续跑，本刀不改
  if (input.waitingReply) {
    return 'reply-waiting'
  }
  if (!input.customAnswer) {
    return 'new-turn'
  }
  // 续作端点不带附件：带附件时按普通新问题发送，附件不能被静默丢掉
  if (input.hasAttachments) {
    return 'new-turn'
  }
  return input.agentChoiceOpen ? 'agent-choice' : 'new-turn'
}
