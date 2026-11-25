/**
 * 工作流时间线上的循环框。所属域：runtime（前端展示）。
 *
 * <p>循环不再单独占一张步骤卡，而是把循环体的节点圈进一层分组框。本模块只把投影好的
 * 节点列表整理成「框 + 框内节点」的树，不改数据、不推算进度：
 *
 * <ul>
 *   <li>框的范围来自节点上的 {@code group}（后端按图上循环边算好的所属循环编码）；</li>
 *   <li>轮次来自节点自己的 {@code attempt}，循环框的「第 N/M 轮」来自循环节点的
 *       {@code loopRound} / {@code loopTotal}；</li>
 *   <li>同一循环的多轮追加在同一个框里，不重复套框；</li>
 *   <li>嵌套循环时取最内层归属，内层框画在外层框里面，不拍平。</li>
 * </ul>
 *
 * <p>直播与刷新回放读同一份投影后的节点列表，因此这里对两条路径是同一套逻辑；
 * 没有循环图（节点不带 group）时原样平铺，与改动前一致。
 *
 * <p>框的出现时机是「该循环或它的体节点已经出现在时间线上」：体节点还是「等待前置节点」时
 * 就先预露一只「等待中」的框，真正进体后同一只框改标题为「第 N/M 轮 · 进行中」，
 * 不先平铺再套框。判断后根本没进这个循环时，框改标「未进入」，不会一直挂着等待中。
 */
import type { TrialWorkflowNodeView } from '@/types/runtime'

/**
 * 循环框的展示态。
 *
 * <ul>
 *   <li>{@code waiting}：已预露，还没进过循环体（体节点仍是「等待前置节点」）；</li>
 *   <li>{@code running}：已在体里跑；</li>
 *   <li>{@code done}：从「通过」走出去或整轮收口；</li>
 *   <li>{@code exhausted}：次数用尽后离开；</li>
 *   <li>{@code failed}：循环节点按配置失败；</li>
 *   <li>{@code cancelled}：整轮被中断；</li>
 *   <li>{@code skipped}：判断后没进这个循环（体节点已跳过），不是还在等。</li>
 * </ul>
 */
export type WfLoopState = 'waiting' | 'running' | 'done' | 'exhausted' | 'failed' | 'cancelled' | 'skipped'

/** 一个循环框的展示信息 */
export interface WfLoopFrame {
  /** 列表键（循环节点编码） */
  key: string
  /** 循环节点编码 */
  code: string
  /** 循环节点展示名 */
  name: string
  /** 当前 / 结束时的轮次；0 表示还没进入循环体 */
  round: number
  /** 配置的总次数；0 表示后端没给（旧快照 / 还没产出） */
  total: number
  /** 展示态 */
  state: WfLoopState
  /** 标题行文案，如「循环 · 第 1/3 轮 · 进行中」 */
  title: string
}

/** 循环框条目；框内条目可继续嵌套 */
export interface WfLoopEntry {
  kind: 'loop'
  loop: WfLoopFrame
  entries: WfTimelineEntry[]
}

/** 时间线上的一条：普通步骤，或一个循环框 */
export type WfTimelineEntry =
  | { kind: 'step'; node: TrialWorkflowNodeView; index: number }
  | WfLoopEntry

/** 判定一个节点视图是不是循环节点 */
export function isLoopNode(node: TrialWorkflowNodeView | undefined | null): boolean {
  return !!node && (node.type || '').trim().toUpperCase() === 'LOOP'
}

/** 节点自己的轮次：缺省按第 1 轮 */
export function attemptOfNode(node: TrialWorkflowNodeView): number {
  return node.attempt && node.attempt > 0 ? node.attempt : 1
}

/**
 * 循环归属链：从最外层到最内层。
 *
 * @param code    循环节点编码
 * @param outerOf 循环编码 → 外层循环编码
 * @return 外层在前、自身在后的链；没有外层时只有自身
 */
export function loopChainOf(code: string, outerOf: Map<string, string>): string[] {
  const chain: string[] = []
  let current: string | undefined = code
  const guard = new Set<string>()
  while (current && !guard.has(current)) {
    chain.unshift(current)
    guard.add(current)
    current = outerOf.get(current)
  }
  return chain
}

/**
 * 循环编码 → 外层循环编码（取第一次出现的声明）。
 *
 * @param nodes 节点列表
 * @return 外层映射
 */
function outerMapOf(nodes: readonly TrialWorkflowNodeView[]): Map<string, string> {
  const outerOf = new Map<string, string>()
  for (const node of nodes) {
    if (!isLoopNode(node)) {
      continue
    }
    const code = (node.code || '').trim()
    const outer = (node.group || '').trim()
    if (code && outer && !outerOf.has(code)) {
      outerOf.set(code, outer)
    }
  }
  return outerOf
}

/** 节点状态码（已大写），缺省为空串 */
function statusOf(node: TrialWorkflowNodeView): string {
  return (node.status || '').trim().toUpperCase()
}

/** 节点是否仍在运行 / 等待：框据此保持进行中 */
function liveStatus(node: TrialWorkflowNodeView): boolean {
  const status = statusOf(node)
  return status === 'RUNNING' || status === 'WAITING_HUMAN' || status === 'WAITING_REPLY'
}

/** 节点是否已经真的跑过（不是预建的空行）：判定「已经离开循环」用 */
function ranStatus(node: TrialWorkflowNodeView): boolean {
  const status = statusOf(node)
  return status === 'RUNNING' || status === 'SUCCESS' || status === 'FAILED'
    || status === 'WAITING_HUMAN' || status === 'WAITING_REPLY' || status === 'CANCELLED'
}

/** 节点是否已被判定「不会跑」：条件分支没选中、或整轮中断时引擎会把它落成跳过 */
function skippedStatus(node: TrialWorkflowNodeView): boolean {
  return statusOf(node) === 'SKIPPED'
}

/**
 * 循环框标题。
 *
 * @param frame 框（state / round / total 已就绪，缺 title）
 * @return 标题行文案
 */
function titleOf(frame: Omit<WfLoopFrame, 'title'>): string {
  const total = frame.total > 0 ? frame.total : 0
  const round = frame.round > 0 ? frame.round : 0
  // 预露态：框已经画出来，但还没进体，标题不写轮次也不写「共 N 轮」
  if (frame.state === 'waiting') {
    return '循环 · 等待中'
  }
  // 判断后不进这个循环：框收口成「未进入」，不留一个永远等待中的壳
  if (frame.state === 'skipped') {
    return '循环 · 未进入'
  }
  if (frame.state === 'exhausted') {
    return `循环 · 共 ${total > 0 ? total : round} 轮 · 次数用尽`
  }
  if (frame.state === 'done') {
    return round > 0 ? `循环 · 共 ${round} 轮 · 已完成` : '循环 · 已完成'
  }
  // 一次都没进过体（还没轮到 / 直接失败）：标题不写「第 0 轮」
  const progress = round <= 0
    ? (total > 0 ? `共 ${total} 轮` : '')
    : total > 0
      ? `第 ${round}/${total} 轮`
      : `第 ${round} 轮`
  const suffix = frame.state === 'failed' ? '已失败' : frame.state === 'cancelled' ? '已中断' : '进行中'
  return progress ? `循环 · ${progress} · ${suffix}` : `循环 · ${suffix}`
}

/**
 * 一个循环框的展示态与轮次。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>进过体且还在跑（循环节点自己或任一成员节点 RUNNING / 等待）→ 进行中；</li>
 *   <li>整轮已被中断 → 已中断；循环节点写了 EXIT → 次数用尽；写了 FAIL（或循环节点失败）→ 已失败；</li>
 *   <li>还没进过体：框是预露出来的，先「等待中」；若循环节点 / 体节点已被跳过，或整轮已收口，
 *       改标「未进入」，不留一个永远等待中的壳；</li>
 *   <li>进过体、体也跑完了、循环再没进来：可能刚打回（下一拍才重进），也可能从「通过」离开，
 *       用「框外有节点真的开始跑」或「整轮已收口」作为离开的证据，避免轮次之间闪一下完成态。</li>
 * </ol>
 *
 * @param code     循环节点编码
 * @param nodes    本张卡上的全部节点
 * @param chain    该循环的归属链（外层 → 自身）
 * @param outerOf  循环编码 → 外层循环编码
 * @param closed   这一位的终态
 * @param finished 整张卡是否已收口（工作流状态为终态）
 * @return 框信息
 */
export function loopFrameOf(
  code: string,
  nodes: readonly TrialWorkflowNodeView[],
  chain: string[],
  outerOf: Map<string, string>,
  closed?: string,
  finished = false
): WfLoopFrame {
  const views = nodes
    .filter((node) => isLoopNode(node) && (node.code || '').trim() === code)
    .sort((left, right) => attemptOfNode(left) - attemptOfNode(right))
  const latest = views[views.length - 1]
  // 预建的下一轮 PENDING 行没有 iteration：往前找最近一份带轮次的产出
  const rounded = [...views].reverse().find((node) => (node.loopRound || 0) > 0)
  const recordedRound = (rounded?.loopRound || 0) || (latest?.loopRound || 0)
  const total = (rounded?.loopTotal || 0) || (latest?.loopTotal || 0)
  const decision = ((latest?.loopDecision || rounded?.loopDecision || '') as string).toUpperCase()
  const name = (latest?.name || rounded?.name || code).trim() || code

  // 成员：归属链包含本循环的节点（嵌套时外层框也看得到内层在跑）
  const members = nodes.filter((node) => {
    const group = (node.group || '').trim()
    return !!group && loopChainOf(group, outerOf).includes(code)
  })
  /*
   * 轮次取「循环节点已写下的 iteration」与「体节点真的跑到第几轮」中较大的那个：
   * 打回后体节点已经开始新一轮、循环节点那一行还没来得及带出新的 iteration 时，
   * 标题仍要写「第 2 轮」，不能退回第 1 轮。
   * 跳过 / 预建的 PENDING 行都不算「跑到过」，否则预露的框会被当成已进体。
   */
  const bodyRound = members.reduce(
    (best, member) => (statusOf(member) === 'PENDING' || skippedStatus(member)
      ? best
      : Math.max(best, attemptOfNode(member))),
    0
  )
  const round = Math.max(recordedRound, bodyRound)
  // 真的进过循环体：循环节点写下了轮次，或体节点已经跑过
  const entered = round > 0
  // 仍有人在跑：循环节点本身或任一成员节点是运行 / 等待态
  const stillRunning = views.some(liveStatus) || members.some(liveStatus)
  // 循环节点自己已经落成跳过 / 失败 / 中断：即便体节点这一拍还没跟着改口，框也该收口
  const loopSkipped = views.some(skippedStatus)
  const loopFailed = views.some((node) => statusOf(node) === 'FAILED')
  const loopCancelled = views.some((node) => statusOf(node) === 'CANCELLED')

  let state: WfLoopState
  /*
   * 中断优先：整轮已中断（或循环节点自己已中断）时，即便某个体节点的快照还写着 RUNNING，
   * 框也不能继续转圈；与步骤卡「closed=aborted 停成完成态」同一口径。
   */
  if (closed === 'aborted' || loopCancelled) {
    state = 'cancelled'
  } else if (entered && stillRunning) {
    state = 'running'
  } else if (decision === 'EXIT') {
    state = 'exhausted'
  } else if (decision === 'FAIL' || loopFailed || closed === 'failed') {
    state = 'failed'
  } else if (!entered) {
    /*
     * 还没进过循环体：框已经预露出来，标题先写「等待中」（循环节点自己正在判定时也算等待，
     * 不在这两帧之间闪一次「进行中」）。
     * 但如果判断后根本不进这个循环（循环节点或体节点已被跳过），或整轮已收口，
     * 就不能继续挂着「等待中」——改标「未进入」，让体节点不再像还在等进循环。
     */
    const allSkipped = members.length > 0 && members.every(skippedStatus)
    const settled = finished || closed === 'done'
    state = loopSkipped || allSkipped || settled ? 'skipped' : 'waiting'
  } else {
    /*
     * 体已经跑完、循环也没再进来：可能刚打回（下一拍才会重进），也可能已经从「通过」离开。
     * 用「框外有节点真的开始跑」或「整轮已收口」作为离开的证据，避免轮次之间闪一下完成态。
     */
    const loopIndex = nodes.findIndex((node) => isLoopNode(node) && (node.code || '').trim() === code)
    const leftByEvidence = nodes.some((node, index) => {
      if (index <= loopIndex) {
        return false
      }
      const group = (node.group || '').trim()
      if (group && loopChainOf(group, outerOf).includes(code)) {
        return false
      }
      if (isLoopNode(node) && (node.code || '').trim() === code) {
        return false
      }
      return ranStatus(node)
    })
    state = finished || leftByEvidence || closed === 'done' ? 'done' : 'running'
  }

  // 还没进过体时标题不写「第 0 轮」：有总次数就只写进行中
  const frame: Omit<WfLoopFrame, 'title'> = {
    key: code,
    code,
    name,
    round: round > 0 ? round : 0,
    total,
    state
  }
  return { ...frame, title: titleOf(frame) }
}

/**
 * 循环是否已经出现在时间线上（预露条件）。
 *
 * <p>只要该循环的循环节点行，或它体内任一节点（含「等待前置节点」）已经在时间线上，
 * 就先把框画出来——体节点一出现就包住，不再等真正进循环才突然套框。
 * 预建的下一轮 PENDING 行不算（它们本来就不铺），避免提前占第 2、3 轮的空卡。
 *
 * @param code    循环节点编码
 * @param nodes   节点列表（已过滤掉下一轮预建空行）
 * @param outerOf 循环编码 → 外层循环编码
 * @return true 表示该循环可以画框
 */
export function loopAppearedOf(
  code: string,
  nodes: readonly TrialWorkflowNodeView[],
  outerOf: Map<string, string>
): boolean {
  return nodes.some((node) => {
    if (isLoopNode(node) && (node.code || '').trim() === code) {
      return true
    }
    const group = (node.group || '').trim()
    return !!group && loopChainOf(group, outerOf).includes(code)
  })
}

/**
 * 只保留「已经出现在时间线上」的那段祖先链：没出现的循环不建框，体节点在最近的已出现祖先里平铺。
 *
 * @param chain   完整归属链（外层 → 自身）
 * @param nodes   节点列表
 * @param outerOf 循环编码 → 外层循环编码
 * @return 已出现的前缀
 */
function appearedChainOf(
  chain: string[],
  nodes: readonly TrialWorkflowNodeView[],
  outerOf: Map<string, string>
): string[] {
  const kept: string[] = []
  for (const code of chain) {
    if (!loopAppearedOf(code, nodes, outerOf)) {
      break
    }
    kept.push(code)
  }
  return kept
}

/**
 * 把节点列表整理成时间线条目（循环框 + 框内条目）。
 *
 * <p>同一循环的后续轮次只追加进同一个框，不重复套框；循环节点自己的轮次行
 * 只用来算框的「第 N/M 轮」，不再作为独立步骤画出来。
 *
 * @param nodes    投影后的节点（顺序即图顺序，同一节点的多轮各占一条）
 * @param closed   这一位的终态（done / failed / aborted）
 * @param status   工作流卡片状态（终态时框可以收口）
 * @return 根层条目；没有循环时退化成逐节点平铺
 */
export function buildWorkflowTimeline(
  nodes: readonly TrialWorkflowNodeView[] | null | undefined,
  closed?: string,
  status?: string
): WfTimelineEntry[] {
  const list = Array.isArray(nodes) ? nodes.filter((node) => !!node) : []
  if (!list.length) {
    return []
  }
  /*
   * 下一轮还没真正开始：循环会为体节点预建 attempt+1 的 PENDING 行，
   * 若照常铺出来，框里会提前出现「第 2 轮 · 等待前置节点」的空卡。
   * 第 1 轮的 PENDING 是真「还没轮到的步骤」，要保留；再往里只认已经开跑的轮次。
   */
  const visible = list.filter(
    (node) => !(attemptOfNode(node) > 1 && (node.status || '').trim().toUpperCase() === 'PENDING')
  )
  if (!visible.length) {
    return []
  }
  const outerOf = outerMapOf(visible)
  const code = (status || '').trim().toUpperCase()
  const finished = code === 'SUCCESS' || code === 'FAILED' || code === 'CANCELLED'
  // 循环编码 → 已经开出来的框条目（同轮复用，保证多轮只有一个框）
  const frames = new Map<string, WfLoopEntry>()
  const root: WfTimelineEntry[] = []
  /** 当前打开的框链，外层在前 */
  const stack: WfLoopEntry[] = []

  /** 把打开的框链对齐到目标链（外层在前）：多退少补，复用的框不重复建 */
  const alignTo = (chain: string[]) => {
    let common = 0
    while (common < stack.length && common < chain.length && stack[common].loop.code === chain[common]) {
      common += 1
    }
    stack.length = common
    for (let index = common; index < chain.length; index += 1) {
      const loopCode = chain[index]
      let entry = frames.get(loopCode)
      if (!entry) {
        entry = {
          kind: 'loop',
          loop: loopFrameOf(loopCode, visible, chain.slice(0, index + 1), outerOf, closed, finished),
          entries: []
        }
        frames.set(loopCode, entry)
        const host = stack.length ? stack[stack.length - 1].entries : root
        host.push(entry)
      }
      stack.push(entry)
    }
  }

  visible.forEach((node, index) => {
    if (isLoopNode(node)) {
      // 循环节点本身不画成步骤卡：它的轮次只用来算框的标题
      return
    }
    const group = (node.group || '').trim()
    alignTo(group ? appearedChainOf(loopChainOf(group, outerOf), visible, outerOf) : [])
    const host = stack.length ? stack[stack.length - 1].entries : root
    host.push({ kind: 'step', node, index })
  })

  // 全部铺完后按最新节点重算一次：结束那一刻状态可能刚落定
  const refresh = (entries: WfTimelineEntry[], chain: string[]) => {
    for (const entry of entries) {
      if (entry.kind !== 'loop') {
        continue
      }
      const nextChain = [...chain, entry.loop.code]
      entry.loop = loopFrameOf(entry.loop.code, visible, nextChain, outerOf, closed, finished)
      refresh(entry.entries, nextChain)
    }
  }
  refresh(root, [])
  // 空框不画：体内一条真实步骤都没有的框整块去掉，避免时间线上出现只剩标题的壳
  return pruneEmptyLoops(root)
}

/**
 * 去掉体内没有任何真实步骤的框（递归处理嵌套）。
 *
 * @param entries 条目
 * @return 去掉空框后的条目
 */
function pruneEmptyLoops(entries: WfTimelineEntry[]): WfTimelineEntry[] {
  const kept: WfTimelineEntry[] = []
  for (const entry of entries) {
    if (entry.kind !== 'loop') {
      kept.push(entry)
      continue
    }
    entry.entries = pruneEmptyLoops(entry.entries)
    if (entry.entries.length) {
      kept.push(entry)
    }
  }
  return kept
}

/**
 * 时间线里是否有可画的条目：空框不画，避免出现只剩标题的壳。
 *
 * @param entries 条目
 * @return true 表示至少有一条真实步骤
 */
export function hasTimelineEntries(entries: readonly WfTimelineEntry[]): boolean {
  return entries.some((entry) => (entry.kind === 'step' ? true : hasTimelineEntries(entry.entries)))
}
