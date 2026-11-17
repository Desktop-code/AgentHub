/**
 * 流式收尾的轮次合并。所属域：runtime（前端展示）。
 *
 * <p>为什么单独一个纯模块：它只做「按角色序列配对 + 沿用稳定标识」的纯计算，
 * 不碰 DOM、不依赖 Vue 与 `@/` 别名，可以在 node 里直接跑用例；
 * 而它决定的正是「终答落地时整行会不会闪一下」，值得被单测钉住。
 */
import type { AssistantBlock, ChatTurn } from '@/types/runtime'

/**
 * 收尾合并：把「落库事件回放」的结果与本轮流式时间线对齐，**沿用本地 key 与块 id**。
 *
 * <p>为什么不能直接替换：流式轮次的 key 是前端临时键（{@code wb-u-*} / {@code wb-a-*}），
 * 回放轮次的 key 是事件派生的 {@code u-<id>} / {@code e-<id>}。整表替换会让每一行的 `v-for` key
 * 变化，Vue 只能卸载整行再重建 —— 页面上就是终答落地后「整块闪一下」，
 * 图片重载、选区丢失、折叠状态一起被清空。
 *
 * <p>合并口径：
 * <ul>
 *   <li>只有「用户 / 助手轮的角色序列完全一致」才按位置配对；结构对不上（SSE 丢过事件、
 *       本地多出一张失败占位）时退回整表替换，宁可不复用也不能把内容错配；</li>
 *   <li>配对成功后沿用本地的 `key` / `startTime` / 块 id（`v-for` key 同源），
 *       只把本地没有的语义字段（如入口身份、续作标记）补上；</li>
 *   <li><b>块序沿用本地</b>：流式过程中已经画出来的块，位置就此钉死；落库回放只往这些块里
 *       补内容与终态，不能按落库 order 把已经在前面的块搬到后面；</li>
 *   <li>文字内容一律以回放结果为准：落库事件是真源，本地流式只是预览。</li>
 * </ul>
 *
 * @param local 当前时间线（流式过程中累积的本地态）
 * @param fresh 落库事件回放结果
 * @return 合并后的时间线；结构不一致时就是 {@code fresh} 本身
 */
export function mergeTurnsAfterRun(local: ChatTurn[], fresh: ChatTurn[]): ChatTurn[] {
  const localShape = turnShape(local)
  // 结构对不上：不做配对，直接以落库结果为准（宁可整表重建，也不能把两轮内容错配）
  if (localShape !== turnShape(fresh)) {
    return fresh
  }
  const localUsers = local.filter((turn) => turn.role === 'user')
  const localAssistants = local.filter((turn) => turn.role === 'assistant')
  let userIndex = 0
  let assistantIndex = 0
  return fresh.map((turn) => {
    const previous = turn.role === 'user' ? localUsers[userIndex++] : localAssistants[assistantIndex++]
    return previous ? adoptTurnIdentity(turn, previous) : turn
  })
}

/**
 * 轮次角色序列（如 `u,a,u,a`）：合并前用它判「两套时间线是不是同一轮结构」。
 *
 * @param turns 时间线
 * @return 角色序列串
 */
function turnShape(turns: ChatTurn[]): string {
  return turns.map((turn) => (turn.role === 'user' ? 'u' : 'a')).join(',')
}

/**
 * 把上一帧的稳定标识与本地语义补到回放出的轮次上（内容仍用回放的）。
 *
 * @param fresh    落库回放出的轮次
 * @param previous 本地流式轮次
 * @return 沿用本地 key / 块 id 的新轮次对象
 */
function adoptTurnIdentity(fresh: ChatTurn, previous: ChatTurn): ChatTurn {
  const merged: ChatTurn = {
    ...fresh,
    // key 是行级复用的唯一依据：沿用本地值，终答落地时才不会卸载整行
    key: previous.key,
    startTime: previous.startTime || fresh.startTime,
    // 这些字段只有流式路径知道（历史回放拿不到），缺失时沿用本地
    entryParticipantId: fresh.entryParticipantId || previous.entryParticipantId,
    continuation: fresh.continuation ?? previous.continuation,
    planPending: fresh.planPending ?? previous.planPending,
    planId: fresh.planId || previous.planId
  }
  // 块序沿用本地、内容用回放的：位置钉死，收尾只补数据不重排
  merged.blocks = alignBlocks(fresh.blocks, previous.blocks)
  // 自增序号只增不减：后续若还要往这轮追加块，id 不能和已复用的旧 id 撞
  merged.blockSeq = Math.max(fresh.blockSeq ?? 0, previous.blockSeq ?? 0, merged.blocks.length)
  return merged
}

/**
 * 把回放出来的块按**本地已有块序**对齐。
 *
 * <p>为什么不能直接按回放顺序整表替换：流式与落库的块序本来就可能不同。
 * 典型是一条消息多个问题并跑——流式里工作流卡先出现（位置固定），落库分段却按「方案 → 子专家 →
 * 工作流」写；整表替换就会把已经画在前面的工作流卡搬到后面，用户看到的是「结束一闪、结构错位」。
 *
 * <p>口径：
 * <ol>
 *   <li>配对上的块：位置沿用本地，内容与终态用回放的，id 沿用本地（`v-for` key 不变）；</li>
 *   <li>本地有、回放没有的块：<b>原地保留</b>——它已经在页面上出现过，位置就此钉死；
 *       落库漏写一条（例如方案自己的「方案结论：X」标记）时，不能借合并把它摘掉；</li>
 *   <li>回放多出来的块：插到它在回放里的前一块之后，保持落库的相邻关系，不硬塞到末尾。</li>
 * </ol>
 *
 * @param fresh 落库回放出来的块
 * @param local 本轮流式时间线里的块
 * @return 对齐后的块（内容回放、顺序本地）
 */
function alignBlocks(fresh: AssistantBlock[], local: AssistantBlock[]): AssistantBlock[] {
  if (!local.length) {
    return fresh
  }
  // 回放一条都没有（落库整体失败）：本地已经画出来的块一个都不能丢
  if (!fresh.length) {
    return local
  }
  // 本地下标 → 回放下标：同类同身份就近配对，一位回放块只被认领一次
  const pairs = new Map<number, number>()
  const claimed = new Set<number>()
  local.forEach((block, localIndex) => {
    const freshIndex = fresh.findIndex((item, index) => !claimed.has(index) && sameBlock(item, block))
    if (freshIndex < 0) {
      return
    }
    claimed.add(freshIndex)
    pairs.set(localIndex, freshIndex)
  })
  const out: AssistantBlock[] = []
  // 回放下标 → 它在输出里的位置（给下面「多出来的块找锚点」用）
  const positionByFresh = new Map<number, number>()
  for (let index = 0; index < local.length; index += 1) {
    const freshIndex = pairs.get(index)
    if (freshIndex === undefined) {
      // 回放没有这一块：保留本地原块（内容不变），保持它原来的位置
      out.push(local[index])
      continue
    }
    positionByFresh.set(freshIndex, out.length)
    out.push(adoptBlockData(fresh[freshIndex], local[index]))
  }  fresh.forEach((block, freshIndex) => {
    if (claimed.has(freshIndex)) {
      return
    }
    // 锚点：它在回放里的前一块若已就位，就紧跟其后；否则放最前（保持落库相邻关系）
    let insertAt = 0
    for (let prev = freshIndex - 1; prev >= 0; prev -= 1) {
      const position = positionByFresh.get(prev)
      if (position !== undefined) {
        insertAt = position + 1
        break
      }
    }
    out.splice(insertAt, 0, block)
    // 插入后，锚点之后的块整体后移一位，后续插入才不会错位
    for (const [key, position] of positionByFresh) {
      if (position >= insertAt) {
        positionByFresh.set(key, position + 1)
      }
    }
    positionByFresh.set(freshIndex, insertAt)
  })
  return out
}

/**
 * 配对上的块：内容用回放的，渲染 id 沿用本地。
 *
 * <p>身份以回放块上已经贴好的为准。回放现在会带参与者，本地若还留着旧的空戳或错戳，
 * 不能再盖掉正确身份。回放没有身份时才沿用本地，避免刷新把直播里已经认好的人丢掉。
 *
 * @param fresh 回放块（内容与身份真源）
 * @param local 本地块（渲染 id 真源）
 * @return 合并后的块
 */
function adoptBlockData(fresh: AssistantBlock, local: AssistantBlock): AssistantBlock {
  const merged: AssistantBlock = { ...fresh }
  // id 同源：`v-for` key 不变，Markdown 不会整块重挂
  if (local.id || fresh.id) {
    merged.id = local.id || fresh.id
  }
  if (fresh.participantId || local.participantId) {
    merged.participantId = fresh.participantId || local.participantId
  }
  if (fresh.participantName || local.participantName) {
    merged.participantName = fresh.participantName || local.participantName
  }
  return merged
}

/**
 * 两块是不是「同一位参与者 / 同一个对象」的同一个块。
 *
 * <p>回放出来的正文块没有身份字段（`blockFromEvent` 只带 kind / text），所以正文与思考按
 * 「同类就近」配对；路由、工作流卡、产物、计划、步骤都有稳定标识，必须严格对齐——
 * 否则会把工作流块与方案块互换，等于又一次重排。
 *
 * <p>正文还有一条额外限制：**拼接稿不与单段正文配对**。后端并行轮把各子正文合成一条
 * 「### 作者 … ### 作者 …」落库；流式里它们是各自一块。若把某位参与者的单段正文与那份
 * 拼接稿配上，内容会被整份换成「所有人说的话」，一段正文被复制到别人块里。
 *
 * @param fresh 回放块
 * @param local 本地块
 * @return true 表示可以视为同一个块
 */
function sameBlock(fresh: AssistantBlock, local: AssistantBlock): boolean {
  if (fresh.kind !== local.kind) {
    return false
  }
  if (fresh.kind === 'workflow') {
    return nameOf(fresh.workflow?.name || fresh.workflow?.code) === nameOf(local.workflow?.name || local.workflow?.code)
  }
  if (fresh.kind === 'file') {
    return nameOf(fresh.file?.fileName) === nameOf(local.file?.fileName)
  }
  if (fresh.plan || local.plan) {
    return nameOf(fresh.plan?.planId) === nameOf(local.plan?.planId)
  }
  if (fresh.step || local.step) {
    return fresh.step?.seq === local.step?.seq
  }
  if (fresh.kind === 'route') {
    // 路由块按「分给谁」配对：判定句 / 说明行没有目标名时退回原文比较
    return routeTargetOf(fresh.text) === routeTargetOf(local.text)
  }
  // 正文 / 思考：拼接稿只与拼接稿配对，且首段作者要对得上，避免一段正文被整份换成别人的话
  const freshDoc = isSectionDoc(fresh.text)
  if (freshDoc !== isSectionDoc(local.text)) {
    return false
  }
  return !freshDoc || firstHeadingOf(fresh.text) === firstHeadingOf(local.text)
}

/**
 * 一段正文是不是「### 作者 … ### 作者 …」拼接稿。
 *
 * <p>只判结构（两段以上带标题的分段），不看内容：单段正文即使自己带一个小标题也不算。
 *
 * @param text 正文
 * @return true 表示是多作者拼接稿
 */
function isSectionDoc(text?: string): boolean {
  return headingsOf(text).length >= 2
}

/**
 * 第一个标题文本（去掉尾部冒号）。
 *
 * <p>用来区分「多人拼接稿的第一位作者」与「报告里的第一章」：拼接稿首段是作者名，
 * 报告首段是章节名，两者对不上就不能当成同一块。
 *
 * @param text 正文
 * @return 首个标题文本；没有标题为空串
 */
function firstHeadingOf(text?: string): string {
  const [heading] = headingsOf(text)
  return (heading || '').replace(/[：:]\s*$/, '').trim()
}

/**
 * 取正文里所有 Markdown 标题文本（只用于结构比较，不参与展示）。
 *
 * @param text 正文
 * @return 标题文本列表
 */
function headingsOf(text?: string): string[] {
  const out: string[] = []
  for (const line of (text || '').split('\n')) {
    const hit = /^\s{0,3}#{1,6}\s+(.+?)\s*$/.exec(line)
    if (hit) {
      out.push(hit[1])
    }
  }
  return out
}

/**
 * 路由块配对的键：分给谁。
 *
 * @param text 路由行原文（形如「路由到 X（key），依据：…」）
 * @return 目标展示名；不是路由行时返回去空白的原文
 */
function routeTargetOf(text: string): string {
  const value = (text || '').trim()
  const prefix = '路由到 '
  if (!value.startsWith(prefix)) {
    return value
  }
  const rest = value.slice(prefix.length)
  return rest.split(/[（(，,]/)[0].trim()
}

/** 空白归一：只用于标识比较，不做展示 */
function nameOf(value?: string): string {
  return (value || '').trim()
}
