/**
 * 工作台内容框的「中间过程 / 最终结果」判定。所属域：runtime（前端展示）。
 *
 * <p>为什么单独一个纯模块：这条判定同时决定「框要不要限高四行」「要不要在框内跟滚」
 * 「流式结束后要不要放开高度」，散在组件里既容易各处不一致，也没法在 node 里直接测。
 * 这里只做纯计算，不碰 DOM、不依赖 Vue 与 `@/` 别名。
 */

/** 中间过程内容框最多显示几行：超过就在框内滚动，不再往页面上铺 */
export const BOX_MAX_LINES = 4

/** 中间过程判定需要的字段（与 ParticipantCard 的展示相关字段同形） */
export interface ContentBoxShape {
  /** gateway＝入口块（名称读配置）；actor＝专家 / 方案；conclusion＝方案带结论 */
  kind: 'gateway' | 'actor' | 'conclusion'
  /** 「方案」角标 */
  roleLabel?: string
  /** 内容框里的成文正文（专家结论 / 方案汇总） */
  answer: string
  /**
   * 是否是整轮的**最后一条**记录（真正结论）。
   *
   * <p>它从流式第一个字起就不限高、边出边铺开；它前面所有块即使已经写完也仍是四行。
   * 由投影按本轮实际顺序标记，组件不自己猜「谁在最后」。
   */
  isFinal?: boolean
}

/**
 * 这个内容框是否按「中间过程」处理：最高四行、框内滚动。
 *
 * <p><b>唯一判据是「它是不是整轮最后一条真正结论」</b>，与「是否仍在流式」无关：
 * <ul>
 *   <li>整轮最后一条（`isFinal`）：**从第一个字开始就按流式铺开**，边出边长，结束后同样完整展开。
 *       先卡四行、等结束再撑开，会让用户读一段被切掉的长答，正是要改掉的行为；</li>
 *   <li>其余块（入口判定句、方案分流句、子专家结果）：始终四行 + 框内跟滚，不被放大成终局。</li>
 * </ul>
 *
 * <p>思考框不走这条判定（见 {@link isThinkOpenByDefault}），因此「思考仍约四行」不受影响。
 *
 * @param card      内容框所属的参与者
 * @param streaming 本轮是否仍在流式输出；只为兼容旧调用保留，不参与判定
 * @return true 表示按中间过程限高
 */
export function isMidProcessBox(card: ContentBoxShape, streaming: boolean): boolean {
  return card.isFinal !== true
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
