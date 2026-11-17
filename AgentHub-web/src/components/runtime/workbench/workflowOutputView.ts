/**
 * 工作流卡片「结果区」展开规则。所属域：runtime（前端）。
 *
 * <p>展开不看整轮是不是还在 streaming，也不看这张卡自己的节点有没有跑完。
 * 只看所属方案的子块是否都已经结束：还有人没结束时正文按四行展示，用户点「展开全文」才看完。
 * 没有更深的方案父块时，只看这一张卡自己的终态。
 *
 * <p>纯函数：不改入参、不发请求。文件与文字并列的口径不变。
 */

/** 结果区形态：不出现 / 四行正文 / 按终态展开 */
export type WorkflowOutputState = 'hidden' | 'folded' | 'open'

/**
 * 判断「所属方案的子块是否都已结束」时用到的卡片字段。
 * 与参与者卡同形，投影和页面都按这个形状传入。
 */
export interface SchemeMember {
  /** 渲染 key，用来认出「当前这张卡」 */
  key: string
  /** gateway / actor / conclusion */
  kind?: string
  /** begin 帧的 kind */
  participantKind?: string
  /** begin 帧的 depth */
  participantDepth?: number
  /** 这一位的终态；缺省表示还没结束 */
  closed?: string
  /** 「方案」角标。kind 缺失时用来认出方案卡 */
  roleLabel?: string
  /** 框内直接子卡。没有嵌套时不传，仍按平铺数组往前找父方案 */
  children?: readonly SchemeMember[]
  /** 方案汇总。展开判断不把它算进「子块还没结束」 */
  summary?: SchemeMember
}

/**
 * 这棵卡里是否已经按方案框收过子卡。
 *
 * @param cards 根卡
 * @return true 表示要顺着 children 找父方案，不能再把根数组当平铺名单
 */
function hasNestedMembers(cards: readonly SchemeMember[]): boolean {
  return cards.some(
    (item) =>
      (item.children != null && item.children.length > 0) ||
      item.summary != null ||
      (item.children != null && hasNestedMembers(item.children))
  )
}

/**
 * 顺着框往下找这张卡的方案父块。
 *
 * <p>父只认 kind=scheme 或角标「方案」。专家框不再往上冒充父块。
 *
 * @param cards    当前这一层
 * @param key      要找的卡
 * @param ancestor 更外层已经认定的方案
 * @return 最近的方案父块；自己就在根上时为空
 */
function findSchemeAncestor(
  cards: readonly SchemeMember[],
  key: string,
  ancestor?: SchemeMember
): SchemeMember | undefined {
  for (const item of cards) {
    const next = item.participantKind === 'scheme' || item.roleLabel === '方案' ? item : ancestor
    if (item.children?.some((child) => child.key === key)) {
      return next
    }
    if (item.children?.length) {
      const found = findSchemeAncestor(item.children, key, next)
      if (found) {
        return found
      }
    }
  }
  return undefined
}

/**
 * 方案框里的子卡是否都已经终态。嵌套方案再往下看一层。
 *
 * @param members 直接子卡
 * @return true 表示可以按终态展开工作流结果
 */
function membersClosed(members: readonly SchemeMember[]): boolean {
  for (const child of members) {
    if (!child.closed) {
      return false
    }
    if (child.children?.length && !membersClosed(child.children)) {
      return false
    }
  }
  return true
}

/**
 * 所属方案下的子块是否都已结束。
 *
 * <p>已经收进方案框时，顺着 children 找最近的方案父块，看框里的子卡是否都有终态。
 * 还是平铺数组时，往前找到 depth 更浅的 scheme，再看它后面、直到回到同层为止的每一张子卡。
 * 任何一张没有终态，就还不能整屏展开。找不到方案父块时，只看当前这张卡自己。
 *
 * @param cards 已经按 begin 排好的卡片；嵌套后根上是方案，专家在 children 里
 * @param card  当前工作流卡
 * @return true 表示可以按终态展开结果区
 */
export function schemeChildrenSettled(cards: readonly SchemeMember[], card: SchemeMember): boolean {
  // 已经收进方案框：平铺下标对不上框里的工作流卡，改顺着 children 找父方案
  if (hasNestedMembers(cards)) {
    const parent = findSchemeAncestor(cards, card.key)
    if (!parent) {
      const self = cards.find((item) => item.key === card.key) || card
      return !!self.closed
    }
    return membersClosed(parent.children || [])
  }
  const index = cards.findIndex((item) => item.key === card.key)
  const self = index >= 0 ? cards[index] : card
  const depth = self.participantDepth
  let schemeIndex = -1
  if (typeof depth === 'number' && index >= 0) {
    for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
      const earlier = cards[cursor]
      const scheme = earlier.participantKind === 'scheme' || earlier.roleLabel === '方案'
      if (scheme && typeof earlier.participantDepth === 'number' && earlier.participantDepth < depth) {
        schemeIndex = cursor
        break
      }
    }
  }
  if (schemeIndex < 0) {
    return !!self.closed
  }
  const schemeDepth = cards[schemeIndex].participantDepth as number
  for (let cursor = schemeIndex + 1; cursor < cards.length; cursor += 1) {
    const child = cards[cursor]
    if (child.kind === 'conclusion') {
      break
    }
    if (typeof child.participantDepth !== 'number' || child.participantDepth <= schemeDepth) {
      break
    }
    if (!child.closed) {
      return false
    }
  }
  return true
}

/**
 * 结果区当前形态。
 *
 * <p>{@code hold} 为 true 表示所属方案还有子块没结束（或这张卡自己还没有终态）。
 * 这时结果是 {@code folded}：页面仍画出正文，但只按四行限高，用户点「展开全文」才看完。
 * 不按空行数段落，也不再写「已产出 N 段」。hold 为 false 时为 {@code open}（最终卡不限高）。
 *
 * @param workflow  工作流卡片快照；没有快照时不出现结果区
 * @param answer    卡片终答 / 已流出正文
 * @param fileCount 属于这张卡的文件数
 * @param expanded  用户是否点了「展开查看」（只影响 folded 这一支）
 * @param hold      所属方案的子块还没全部结束
 * @return hidden / folded / open
 */
export function workflowOutputState(
  workflow: { status?: string; nodes?: readonly { status?: string }[] } | undefined,
  answer: string,
  fileCount: number,
  expanded = false,
  hold = false
): WorkflowOutputState {
  if (!workflow) {
    return 'hidden'
  }
  const hasAnswer = !!(answer || '').trim()
  const hasFile = fileCount > 0
  if (!hasAnswer && !hasFile) {
    return 'hidden'
  }
  if (hold) {
    return expanded ? 'open' : 'folded'
  }
  return 'open'
}

/**
 * 结果区是否可以整屏展开。
 *
 * @param workflow  工作流卡片快照；没有快照时不展开
 * @param answer    卡片终答 / 已流出正文
 * @param fileCount 属于这张卡的文件数
 * @param expanded  用户是否点了「展开查看」
 * @param hold      所属方案的子块还没全部结束
 * @return true 表示整屏展开
 */
export function shouldShowWorkflowOutput(
  workflow: { status?: string; nodes?: readonly { status?: string }[] } | undefined,
  answer: string,
  fileCount: number,
  expanded = false,
  hold = false
): boolean {
  return workflowOutputState(workflow, answer, fileCount, expanded, hold) === 'open'
}

/**
 * 结果是否处于「先按四行展示、不整屏」：所属方案还有子块没结束，且已有正文或文件。
 *
 * @param workflow  工作流卡片快照
 * @param answer    已流出正文
 * @param fileCount 属于这张卡的文件数
 * @param hold      所属方案的子块还没全部结束
 * @return true 表示正文先按四行展示
 */
export function workflowOutputFolded(
  workflow: { status?: string; nodes?: readonly { status?: string }[] } | undefined,
  answer: string,
  fileCount: number,
  hold = false
): boolean {
  return workflowOutputState(workflow, answer, fileCount, false, hold) === 'folded'
}
