/**
 * 工作台内容框的「中间过程 / 最终结果」判定。所属域：runtime（前端展示）。
 *
 * <p>为什么单独一个纯模块：这条判定同时决定「框要不要限高四行」「要不要在框内跟滚」
 * 「流式结束后要不要放开高度」，散在组件里既容易各处不一致，也没法在 node 里直接测。
 * 这里只做纯计算，不碰 DOM、不依赖 Vue 与 `@/` 别名。
 */

/** 中间过程内容框最多显示几行：超过就在框内滚动，不再往页面上铺 */
export const BOX_MAX_LINES = 4

/**
 * 含表格、且默认展开的结果框最高约占一屏。
 * 中间过程即使有表仍先收四行；只有本轮最后一份和综答才直接用这个高度。
 */
export const RESULT_TABLE_MAX_HEIGHT = '70vh'

/** 中间过程判定需要的字段（与 ParticipantCard 的展示相关字段同形） */
export interface ContentBoxShape {
  /** gateway＝入口块（名称读配置）；actor＝专家 / 方案；conclusion＝方案带结论 */
  kind: 'gateway' | 'actor' | 'conclusion'
  /** 「方案」角标 */
  roleLabel?: string
  /** 内容框里的成文正文（专家结论 / 方案汇总） */
  answer: string
  /**
   * 是否是整轮结束后的最后一条真正结论。
   *
   * <p>直播过程中投影不会打这个标记，每一张内容框都限高。
   * 整轮结束后只有这一张放开高度。由投影标记，组件不自己猜「谁在最后」。
   */
  isFinal?: boolean
}

/**
 * 这个内容框是否按「中间过程」处理：最高四行、框内滚动。
 *
 * <p>直播过程中每一张内容框都限高。整轮结束后，只有真正的收尾卡（`isFinal`）放开高度。
 * 流式中不得把「当前最后一张已经有正文的卡」当成终局：那会让先写完的一块先铺满整屏。
 *
 * <p>思考框不走这条判定（见 {@link isThinkOpenByDefault}），因此「思考仍约四行」不受影响。
 * 综答是否限高不走这里，见 {@link isUnlimitedAnswerBox}。
 *
 * @param card      内容框所属的参与者
 * @param streaming 本轮是否仍在流式输出；为 true 时一律限高
 * @return true 表示按中间过程限高
 */
export function isMidProcessBox(card: ContentBoxShape, streaming: boolean): boolean {
  if (streaming) {
    return true
  }
  return card.isFinal !== true
}

/**
 * 综答（方案汇总那一条独立记录）不收进四行。
 *
 * <p>过程里的入口、分流、专家结果仍按四行滚动。汇总正文是流式往下长的终稿，
 * 高度跟文字走，不给展开箭头。
 *
 * @param card 内容框所属的参与者
 * @return true 表示这段正文不限高
 */
export function isUnlimitedAnswerBox(card: ContentBoxShape): boolean {
  return card.kind === 'conclusion'
}

/** 结果正文的限高口径：普通文本四行；含表则整表进入可滚动结果框 */
export type AnswerClamp = 'lines' | 'table'

/**
 * 正文是否含一张已经成形的表（GFM 管道表或裸 HTML 表）。
 *
 * <p>只认表头 + 分隔行，或围栏外的 `&lt;table`。代码块里的示例表不算，
 * 避免一段普通说明因为围栏示例被拉成整屏滚动。半张还没写出分隔行的表仍按普通文本，
 * 分隔行一到就整段改走表格口径，直播与回放读的是同一份正文。
 *
 * @param source 结果正文原文（Markdown）
 * @return true 表示这里有一张不该被四行裁切的表
 */
export function answerContainsMarkdownTable(source: string): boolean {
  if (!source || !source.trim()) {
    return false
  }
  const lines = source.replace(/\uFF5C/g, '|').split(/\r?\n/)
  let inFence = false
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    if (/^\s*```/.test(line)) {
      inFence = !inFence
      continue
    }
    if (inFence) {
      continue
    }
    if (/<table\b/i.test(line)) {
      return true
    }
    if (!line.includes('|')) {
      continue
    }
    let delimAt = i + 1
    let blanks = 0
    while (delimAt < lines.length && !lines[delimAt].trim() && blanks < 2) {
      delimAt += 1
      blanks += 1
    }
    if (delimAt < lines.length && isTableDelimiterLine(lines[delimAt])) {
      return true
    }
  }
  return false
}

/**
 * 这段结果正文里有没有表。有表不等于默认铺开。
 *
 * <p>中间过程仍按四行收，见 {@link tableFoldedByDefault}。
 * 只有本轮最后一份（投影 {@code isFinal}）和综答，才默认整表放进结果框，
 * 最高 {@link RESULT_TABLE_MAX_HEIGHT}。
 *
 * @param source 结果正文原文
 * @return `table` 表示正文里有成形的表
 */
export function answerClampOf(source: string): AnswerClamp {
  return answerContainsMarkdownTable(source) ? 'table' : 'lines'
}

/**
 * 含表的这一张，默认要不要仍收成四行。
 *
 * <p>口径跟投影的终态标记走，不看专家名字：
 * 直播中每一张都还不是终局；整轮结束后只有 {@code isFinal} 那一张放开。
 * 综答（方案汇总）本来就不收四行。单 Agent 跑完后自己就是最后一张，
 * 所以独立对话里的表不会被收回四行。方案框里的子专家不是最后一张，有表也先收四行。
 *
 * @param card      内容框所属的参与者（含投影打上的 isFinal）
 * @param streaming 本轮是否仍在流式输出
 * @return true 表示先按四行收，点开后再看全表
 */
export function tableFoldedByDefault(card: ContentBoxShape, streaming: boolean): boolean {
  if (isUnlimitedAnswerBox(card)) {
    return false
  }
  return isMidProcessBox(card, streaming)
}

/**
 * GFM 分隔行：至少一格 `---`，允许对齐冒号。
 *
 * @param line 候选行
 * @return true 表示这是表分隔行
 */
function isTableDelimiterLine(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed.includes('-') || !trimmed.includes('|')) {
    return false
  }
  const cells = trimmed.replace(/^\|/, '').replace(/\|$/, '').split('|')
  return cells.length > 0 && cells.every((cell) => /^\s*:?-{3,}:?\s*$/.test(cell))
}

/**
 * 思考区是否应该默认展开（＝这一块是否仍处在「三拍」的第一拍）。
 *
 * <p>口径：只要**这一块还在工作中**（本轮流式中且这一位还没交出结果），就展开思考区，
 * 让用户看着它一行行长并在框内跟滚；一旦产生了真实结果，立刻收回折叠
 * （文案仍是「查看思考过程与工具调用」）。
 *
 * <p>为什么不能只看 `answer` 是否为空：没有成文正文时，投影会用「最后一条工具返回」
 * 之类的真实事件补一句最短结论（那是结果位该显示的东西）。这类补位句会让 `answer` 非空，
 * 但这一位其实还在跑——所以判定必须用 `pending`（仍在生成）这个更直接的事实。
 *
 * <p><b>三拍不得并行提前</b>：这一步一旦出现工具调用或真实结果，第一拍（思考）就结束了，
 * 整框立刻收起——之后即便还有零散推理增量，也只进折叠区，不会和工具胶囊 / 结果框同屏抢注意力。
 * 因此「思考框是否还在流式」与「该不该出工具胶囊 / 结果框」共用这一个判定，不会各判各的。
 *
 * @param card        内容框所属的参与者
 * @param streaming   本轮是否仍在流式输出
 * @param pending     这位参与者是否仍未交出结果
 * @param hasTool     这一块是否已经出现工具调用 / 工具结果
 * @param resultShown 这一块的真实结果是否已经出现（网关/方案的判定句、专家的正文/报错/产物）
 * @return true 表示默认展开思考区（仍在第一拍）
 */
export function isThinkOpenByDefault(
  card: ContentBoxShape,
  streaming: boolean,
  pending: boolean,
  hasTool = false,
  resultShown = false
): boolean {
  if (!streaming) {
    return false
  }
  // 工具或结果一到，思考这一拍结束：收起，让位给胶囊与结果框
  if (hasTool || resultShown) {
    return false
  }
  // 仍在跑：展开思考，让过程可见
  if (pending) {
    return true
  }
  // 没有结果句也没有正文：同样展开（还什么都没出来）
  return !(card.answer || '').trim()
}

/**
 * 折叠区当前是否展开。所属域：runtime（前端展示）。
 *
 * <p>口径：用户点过就以覆盖值为准，否则用默认态。
 *
 * <p><b>实现约束（别改回 hasOwnProperty）</b>：覆盖表是 Vue 的 `reactive` 对象，
 * 首次点击写入的是**新键**。`Object.prototype.hasOwnProperty.call(overrides, key)`
 * 读缺失键时不会建立依赖，写进去也不会触发重渲染——轮次结束后没有别的响应式更新，
 * 表现就是「点了没反应」。这里直接读值（缺失时读到 undefined 同样会被追踪），
 * 首次写入即可让视图重新求值。
 *
 * @param overrides   用户折叠覆盖表（键＝轮 + 区段）
 * @param key         折叠键
 * @param defaultOpen 用户没点过时的默认态
 * @return true 表示展开
 */
export function foldOpenOf(
  overrides: Record<string, boolean>,
  key: string,
  defaultOpen: boolean
): boolean {
  const value = overrides[key]
  return typeof value === 'boolean' ? value : defaultOpen
}

/**
 * 点击一次后的覆盖值：在「当前实际展开态」上取反。
 *
 * @param overrides   用户折叠覆盖表
 * @param key         折叠键
 * @param defaultOpen 用户没点过时的默认态
 * @return 要写入覆盖表的值
 */
export function nextFoldValue(
  overrides: Record<string, boolean>,
  key: string,
  defaultOpen: boolean
): boolean {
  return !foldOpenOf(overrides, key, defaultOpen)
}

/** 正文「展开全文 / 收起」按钮的三种态：不出现、展开、收起 */
export type BodyExpandAction = 'none' | 'expand' | 'collapse'

/**
 * 正文展开按钮当前该画哪一种。
 *
 * <p>只作用于正文文字，不作用于文件行，也不作用于思考框。
 * 思考框是四行内滚动，没有这颗按钮。
 * 没超过四行时不给按钮：否则点下去高度不变，看起来像没反应。
 *
 * @param limited     这一块正文是否按四行截断（中间过程，或工作流所属方案还没结束）
 * @param expanded    用户是否已经点过展开
 * @param overflowing 正文实际高度是否超过四行
 * @return none / expand / collapse
 */
export function bodyExpandAction(
  limited: boolean,
  expanded: boolean,
  overflowing: boolean
): BodyExpandAction {
  if (!limited) {
    return 'none'
  }
  if (expanded) {
    return 'collapse'
  }
  return overflowing ? 'expand' : 'none'
}

/**
 * 内容框当前应当占的高度：中间过程四行，最终结论不设限（流式中也一样）。
 *
 * <p>返回 CSS `max-height` 的值；`null` 表示不设上限（完整展开）。
 * 四行按行高折算，padding 由调用方在 CSS 里另计——这里只给「四行」这个语义上限。
 *
 * @param card         内容框所属的参与者
 * @param streaming    本轮是否仍在流式输出；只为兼容旧调用保留，不参与判定
 * @param lineHeightPx 单行高度（含行高，默认 22px ≈ 14px × 1.6）
 * @return CSS 长度；null 表示不限高
 */
export function boxMaxHeight(
  card: ContentBoxShape,
  streaming: boolean,
  lineHeightPx = 22
): string | null {
  return isMidProcessBox(card, streaming) ? `${BOX_MAX_LINES * lineHeightPx}px` : null
}

/**
 * 思考折叠行上的耗时。只格式化已经量到的毫秒，不编造。
 *
 * <p>不足 1 秒写毫秒；10 秒以内保留一位小数（与演示稿 `1.2s` 同形）；再长取整秒。
 *
 * @param ms 已观察到的毫秒；非数字或负数视为没有
 * @return 如 `1.2s`；没有可展示的时长时为空串
 */
export function formatThoughtElapsed(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) {
    return ''
  }
  if (ms < 1000) {
    return `${Math.round(ms)}ms`
  }
  const seconds = ms / 1000
  if (seconds < 10) {
    return `${seconds.toFixed(1)}s`
  }
  return `${Math.round(seconds)}s`
}
