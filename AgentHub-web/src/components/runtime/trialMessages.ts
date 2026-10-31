/**
 * 把试跑事件收成对话时间线。所属域：runtime。
 * 同一轮的思考、工具和回答进同一张助手卡片。没有正文的思考不生成块。
 */
import type {
  AssistantBlock,
  ChatTurn,
  TrialEvent,
  TrialPlanView,
  TrialStepView
} from '@/types/runtime'
import { renderSimpleMarkdown } from '@/utils/simpleMarkdown'

/**
 * 按 seq 回放。用户单独成气泡，其后的步骤并进一张助手卡片。
 * @param events 同一会话的事件，顺序不限
 * @return 对话区条目
 */
export function turnsFromEvents(events: TrialEvent[]): ChatTurn[] {
  const ordered = [...events].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  const turns: ChatTurn[] = []
  let assistant: ChatTurn | undefined
  for (const item of ordered) {
    if (item.eventType === 'user') {
      const text = displayText(item)
      assistant = undefined
      if (text) {
        turns.push(userTurn(item.id, text))
      }
      continue
    }
    const block = blockFromEvent(item)
    if (!block) {
      continue
    }
    if (!assistant) {
      // 历史回放已是终态，phase 必须 done，禁止一直显示「任务规划中」
      assistant = assistantTurn(`e-${item.id}`, 'done')
      assistant.phase = 'done'
      turns.push(assistant)
    }
    pushBlock(assistant, block)
    if (block.kind === 'error') {
      assistant.status = 'error'
      assistant.phase = 'error'
    }
  }
  return turns
}

/** 收尾事件类型：助手正文或失败说明。出现任一即表示这一轮已结束 */
const CLOSED_EVENT_TYPES = new Set(['assistant', 'error'])

/**
 * 计划事件里的「本轮已收束」状态。
 * 待批准（PENDING）、已取消（REJECTED）、已完成（DONE）都不会再有后续助手正文；
 * 只有 EXECUTING / FAILED 仍可能跟着步骤与终答，故不在此列。
 * 不加这条判断时，停在待批准的轮次会被当成「还在跑」而一直空转轮询。
 */
const CLOSED_PLAN_STATES = new Set(['PENDING', 'REJECTED', 'DONE'])

/**
 * 事件流是否还有未收尾的一轮。
 * 后端在本轮开始时只落 user；思考、工具、助手正文都要等本轮跑完才入库。
 * 因此判据是「最后一条 user 之后还没有 assistant / error」，而不是只看末尾事件类型——
 * 末尾若再跟一条 system（装配说明、子 Agent 失败提示），用末尾判断会把已结束的轮次误判成进行中。
 * @param events 同一会话已落库事件，顺序不限
 * @return true 表示还应继续续订
 */
export function hasOpenTurn(events: TrialEvent[]): boolean {
  const ordered = [...events].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  let open = false
  for (const item of ordered) {
    if (item.eventType === 'user') {
      open = true
    } else if (CLOSED_EVENT_TYPES.has(item.eventType)) {
      open = false
    } else if (item.eventType === 'plan' && CLOSED_PLAN_STATES.has(parsePlan(item.contentText)?.state || '')) {
      // 计划停在待批准/已取消/已完成：这一轮没有后续正文，不能再续订
      open = false
    }
  }
  return open
}

/**
 * 把重进取回的时间线标成「进行中」：末尾是用户气泡就补一张进行中的助手卡，
 * 否则把最后一张助手卡改成 streaming。阶段按末尾事件类型推断，不新造协议。
 * @param turns   {@link turnsFromEvents} 回放结果
 * @param events  同一批落库事件，用来推断阶段与等待起点
 * @return 新的数组（末尾卡片被改为 streaming）
 */
export function markLiveTail(turns: ChatTurn[], events: TrialEvent[]): ChatTurn[] {
  const phase = phaseOfTail(tailEvent(events)?.eventType)
  const startTime = lastUserTime(events)
  const list = [...turns]
  const last = list[list.length - 1]
  if (!last || last.role !== 'assistant') {
    // 只有 user 落库时还没有助手卡，补一张空卡让粘性状态条有挂载点
    const live = assistantTurn('live-tail', 'streaming')
    live.phase = phase
    live.startTime = startTime
    list.push(live)
    return list
  }
  last.status = 'streaming'
  last.phase = phase
  last.startTime = startTime
  return list
}

/** seq 最大的一条；空数组返回 undefined。 */
function tailEvent(events: TrialEvent[]): TrialEvent | undefined {
  let tail: TrialEvent | undefined
  for (const item of events) {
    if (!tail || (item.seq ?? 0) >= (tail.seq ?? 0)) {
      tail = item
    }
  }
  return tail
}

/** 末尾事件换成粘性状态条文案的阶段码。 */
function phaseOfTail(type?: string) {
  if (type === 'tool') {
    return 'tool'
  }
  if (type === 'reasoning' || type === 'route') {
    return 'reasoning'
  }
  if (type === 'assistant') {
    return 'answering'
  }
  if (type === 'plan') {
    return 'planning'
  }
  if (type === 'step') {
    return 'tool'
  }
  return 'planning'
}

/**
 * 未收尾的一轮是否已经太旧。库里遗留的僵尸 RUNNING 不该让重进一直转，
 * 因此只在末尾事件足够新时自动续订；时间缺失或解析出未来时间（时区差异）时按「新」处理。
 * @param events   已落库事件
 * @param maxAgeMs 允许的最大静默时长
 * @return true 表示过旧，不要自动续订
 */
export function openTurnStale(events: TrialEvent[], maxAgeMs: number): boolean {
  const tail = tailEvent(events)
  const at = parseEventTime(tail?.createTime)
  if (!at) {
    return false
  }
  return Date.now() - at > maxAgeMs
}

/**
 * 等待起点：本轮最后一条 user 事件的落库时间。
 * 每轮轮询都会重放时间线，用落库时间当锚点，避免「已等待」每次刷新归零。
 */
function lastUserTime(events: TrialEvent[]): number {
  let best = 0
  let at = 0
  for (const item of events) {
    if (item.eventType !== 'user') {
      continue
    }
    const seq = item.seq ?? 0
    if (seq >= best) {
      best = seq
      at = parseEventTime(item.createTime)
    }
  }
  return at || Date.now()
}

/** 服务端时间可能是「yyyy-MM-dd HH:mm:ss」，统一成毫秒。 */
function parseEventTime(value?: string): number {
  if (!value) {
    return 0
  }
  const time = new Date(value.replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? 0 : time
}

/** 发送时先放一张进行中的助手卡片，等内容到达后再填。 */
export function assistantTurn(key: string, status: ChatTurn['status']): ChatTurn {
  return {
    key,
    role: 'assistant',
    text: '',
    blocks: [],
    phase: 'planning',
    status,
    startTime: Date.now()
  }
}

/** 连续的思考或回答拼进同一块，避免每个 token 一块。 */
export function appendText(turn: ChatTurn, kind: 'reasoning' | 'assistant', text: string) {
  if (!text) {
    return
  }
  const last = turn.blocks[turn.blocks.length - 1]
  if (last && last.kind === kind) {
    last.text += text
    return
  }
  turn.blocks.push({ kind, text })
}

/** 工具按一次调用一块。参数行和结果分开。 */
export function appendTool(turn: ChatTurn, line: string, result?: string) {
  const text = (line || '').trim()
  const detail = (result || '').trim()
  if (!text && !detail) {
    return
  }
  turn.blocks.push({
    kind: 'tool',
    text: text || detail,
    detail: detail && detail !== text ? detail : undefined
  })
}

/**
 * 思考过程折叠区内的片段：路由 + 推理 + 工具，保持事件顺序。
 * 展示层合成一个「思考过程」头，不再按片段拆多个同名头。
 */
export function processBlocks(blocks: AssistantBlock[]): AssistantBlock[] {
  return blocks.filter((block) => block.kind === 'route' || block.kind === 'reasoning' || block.kind === 'tool')
}

/** 思考时间线一步。连续 reasoning 在入库/追加时已合并，这里不再按 token 拆。 */
export type ProcessStep = {
  type: 'route' | 'reasoning' | 'tool_call' | 'tool_result'
  title: string
  text: string
}

/**
 * 多 Agent 思考分块。进行中与结束后同一结构，禁止收束拍扁成单时间线。
 * decision：父级完整路由列表；agent：标题固定「路由到 {名}」+ route_key，块内仅该子步骤。
 */
export type ProcessLane = {
  kind: 'decision' | 'agent' | 'note'
  /** 块标题。agent 为「路由到 {显示名}」 */
  title: string
  routeKey?: string
  /** 决策 JSON / 依据 / 其它路由说明 */
  body?: string
  steps: ProcessStep[]
}

/**
 * 按事件顺序拆成时间线（单意图或兼容旧数据）。
 * 多意图请用 {@link processLanes}，避免第二路塞进第一路依据。
 */
export function processSteps(blocks: AssistantBlock[]): ProcessStep[] {
  const steps: ProcessStep[] = []
  for (const lane of processLanes(blocks)) {
    if (lane.kind === 'decision' || lane.kind === 'note') {
      if (lane.body?.trim()) {
        steps.push({ type: 'route', title: lane.title, text: lane.body.trim() })
      }
      continue
    }
    const meta: string[] = []
    if (lane.routeKey) {
      meta.push(`route_key：${lane.routeKey}`)
    }
    if (lane.body?.trim()) {
      meta.push(lane.body.trim())
    }
    steps.push({ type: 'route', title: lane.title, text: meta.join('\n') })
    steps.push(...lane.steps)
  }
  return steps
}

/**
 * 多路由按子 Agent 分块；单路由仍一块。
 * 兼容旧落库「路由到 A…；路由到 B…」拼串，按分号切开。
 */
export function processLanes(blocks: AssistantBlock[]): ProcessLane[] {
  const lanes: ProcessLane[] = []
  let current: ProcessLane | undefined

  for (const block of processBlocks(blocks)) {
    if (block.kind === 'route') {
      for (const piece of splitRoutePieces(block.text)) {
        const lane = routeLane(piece)
        if (!lane) {
          continue
        }
        if (lane.kind === 'agent') {
          // 同名同 key 复用块（流式先决策后子开跑会再推一次「路由到」）
          const existing = lanes.find(
            (item) =>
              item.kind === 'agent' &&
              item.title === lane.title &&
              (item.routeKey || '') === (lane.routeKey || '')
          )
          if (existing) {
            if (!existing.body && lane.body) {
              existing.body = lane.body
            }
            current = existing
            continue
          }
        }
        lanes.push(lane)
        current = lane.kind === 'agent' ? lane : undefined
      }
      continue
    }
    if (!current || current.kind !== 'agent') {
      // 尚无子块时落进匿名执行块，避免丢推理/工具
      current = { kind: 'agent', title: '执行', steps: [] }
      lanes.push(current)
    }
    if (block.kind === 'reasoning') {
      // 同 lane 内连续推理合并为一步（防 laneBound 重推路由把流式拆成一词一步；回放错步亦重建）
      appendReasoningStep(current.steps, block.text)
      continue
    }
    const line = block.text.trim()
    const result = (block.detail || '').trim()
    if (line) {
      current.steps.push({
        type: 'tool_call',
        title: toolCallTitle(line),
        text: toolCallBody(line)
      })
    }
    if (result) {
      current.steps.push({ type: 'tool_result', title: '工具结果', text: result })
    }
  }
  return lanes
}

/**
 * 旧数据可能把多条「路由到」用中文分号拼成一条；新数据一条事件一段。
 */
function splitRoutePieces(raw: string): string[] {
  const text = (raw || '').trim()
  if (!text) {
    return []
  }
  if (text.startsWith('路由决策')) {
    return [text]
  }
  return text
    .split(/；(?=路由到)/)
    .map((part) => part.trim())
    .filter(Boolean)
}

/**
 * 把落库/SSE 的路由摘要收成一块：决策 / 子 Agent / 其它说明。
 */
function routeLane(raw: string): ProcessLane | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  if (text.startsWith('路由决策')) {
    const body = text.replace(/^路由决策\s*/, '').trim()
    return { kind: 'decision', title: '路由决策', body: body || text, steps: [] }
  }
  const matched = /^路由到\s*(.+?)(?:（([^）]*)）)?(?:，依据[：:]\s*(.*))?$/s.exec(text)
  if (!matched) {
    return { kind: 'note', title: '路由', body: text, steps: [] }
  }
  const name = (matched[1] || '').trim().replace(/，$/, '')
  const key = (matched[2] || '').trim()
  const reason = (matched[3] || '').trim()
  const title = name ? `路由到 ${name}` : '路由到'
  const lines: string[] = []
  if (reason) {
    lines.push(`依据：${reason}`)
  }
  return {
    kind: 'agent',
    title,
    routeKey: key || undefined,
    body: lines.join('\n') || undefined,
    steps: []
  }
}

/**
 * 流式收到路由事件时追加一块；多意图可连续多条「路由到…」。
 * 并行子 laneBound 会在每个 reasoning/tool 增量前重推本子路由：
 * 只要「最近一条路由」已是同文案（中间可夹推理/工具），就跳过，避免打断 appendText 合并。
 * 切到另一子时最近路由文案不同，仍会插入，保证分块切换。
 */
export function appendRoute(turn: ChatTurn, summary: string) {
  const text = (summary || '').trim()
  if (!text) {
    return
  }
  const active = latestRouteText(turn.blocks)
  if (active === text) {
    return
  }
  turn.blocks.push({ kind: 'route', text })
}

/**
 * 从块列表末尾往前找最近一条路由正文；没有则空串。
 * 中间的推理/工具不改变「当前活动子路由」，供 laneBound 重推去重。
 */
function latestRouteText(blocks: AssistantBlock[]): string {
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    const block = blocks[i]
    if (block.kind === 'route') {
      return (block.text || '').trim()
    }
  }
  return ''
}

/**
 * 同 lane 连续推理追加到上一步；工具/路由边界后开新「推理」步。
 * 空正文忽略；首段 trim 两端，续段保留原增量空白以免吃掉词间空格。
 */
function appendReasoningStep(steps: ProcessStep[], raw: string) {
  if (!(raw || '').trim()) {
    return
  }
  const last = steps[steps.length - 1]
  if (last && last.type === 'reasoning') {
    last.text += raw
    return
  }
  steps.push({ type: 'reasoning', title: '推理', text: raw.trim() })
}

/** 从「调用工具 name，参数 …」里抽出标题，失败后缀仍挂在 name 后。 */
function toolCallTitle(line: string): string {
  const failed = /·\s*失败/.test(line)
  const matched = /^调用工具\s+([^\s，,·]+)/.exec(line)
  if (matched) {
    return failed ? `调用工具 ${matched[1]} · 失败` : `调用工具 ${matched[1]}`
  }
  return failed ? '调用工具 · 失败' : '调用工具'
}

/** 参数正文；没有参数时给一行说明，避免步骤空壳。 */
function toolCallBody(line: string): string {
  const cleaned = line.replace(/\s*·\s*失败\s*$/, '')
  const idx = cleaned.indexOf('，参数 ')
  if (idx >= 0) {
    return cleaned.slice(idx + '，参数 '.length).trim() || '（无参数）'
  }
  const stripped = cleaned.replace(/^调用工具\s+[^\s，,·]+\s*/, '').trim()
  return stripped || '（无参数）'
}

/**
 * 思考正文按二级标题切开；终态与终答同一套 renderSimpleMarkdown（加粗/列表/表格）。
 * 流式过程由 ChatPanel 走 renderStreamingMarkdown，避免半截表刷源码。
 * 「分析推理」段单独浅底，便于和普通思考区分。
 * @param source 引擎给出的思考
 */
export function reasoningSections(source: string): { tone: 'plain' | 'analysis'; html: string }[] {
  const text = (source || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim()
  if (!text) {
    return []
  }
  return text
    .split(/\n(?=## )/)
    .map((chunk) => chunk.trim())
    .filter(Boolean)
    .map((chunk) => {
      const title = /^##\s+(.+)$/m.exec(chunk)?.[1] || ''
      return {
        tone: title.includes('分析推理') ? 'analysis' as const : 'plain' as const,
        html: renderSimpleMarkdown(chunk)
      }
    })
}

/**
 * 计划执行模式里「一步」的展示结构。
 * steps 只放这一步内部的思考与工具，按事件顺序；不含其它步的内容。
 */
export interface PlanStepSection {
  seq: number
  title: string
  state: string
  text: string
  /** 时间线一行文案，服务端拼好；缺失时由展示层兜底 */
  line?: string
  steps: ProcessStep[]
}

/**
 * 计划执行模式一轮的分段视图。顺序即渲染顺序，不允许再按块位置自由浮排：
 * 计划阶段思考 → 计划卡 → 逐步执行（每步含自己的思考） → 终答。
 */
export interface TurnSections {
  /** 计划卡出现之前的思考（规划阶段的推理） */
  planPhase: ProcessStep[]
  /** 本轮的计划卡；没有计划时为 undefined */
  plan?: TrialPlanView
  /** 按 seq 升序的步骤进度 */
  execution: PlanStepSection[]
  /** 终答正文；可能为空（待批准或失败） */
  answer: string
  /** 装配说明等非错误提示，排在终答之后 */
  notes: string[]
  /** 失败说明，排在最后 */
  errors: string[]
}

/**
 * 这一轮是否是计划执行模式（含计划卡或步骤块）。
 * 是则走分段渲染，否则沿用 Router/ReAct 的「思考过程 + 块顺序」渲染。
 * @param item 助手轮
 */
export function isPlanFlow(item: ChatTurn): boolean {
  return item.blocks.some((block) => block.kind === 'plan' || block.kind === 'step')
}

/**
 * 把一轮的块切成四段：计划阶段思考 / 计划卡 / 逐步执行 / 终答。
 *
 * 归属规则（与后端落库顺序一一对应）：
 * - 计划卡之前的推理与工具归「计划阶段」；
 * - 某个 STEP 块（RUNNING）之后、下一个 STEP 之前的推理与工具归该步；
 * - 计划卡之后、第一个 STEP 之前的零散推理也归计划阶段，避免被丢掉；
 * - assistant 块一律进终答，绝不插到计划卡之前。
 *
 * @param blocks 该轮的块，按事件顺序
 * @return 分段视图
 */
export function turnSections(blocks: AssistantBlock[]): TurnSections {
  const planPhaseBlocks: AssistantBlock[] = []
  const buckets: { seq: number; title: string; state: string; text: string; line?: string; blocks: AssistantBlock[] }[] = []
  const notes: string[] = []
  const errors: string[] = []
  let plan: TrialPlanView | undefined
  let current: (typeof buckets)[number] | undefined
  let answer = ''
  for (const block of blocks) {
    if (block.kind === 'plan' && block.plan) {
      plan = block.plan
      // 计划卡之后的推理不再算规划阶段；下一条 STEP 会重新开桶
      current = undefined
      continue
    }
    if (block.kind === 'step' && block.step) {
      const view = block.step
      // 同一 seq 的 RUNNING/DONE 是状态推进，必须合并成一个桶，不能新增行
      let bucket = buckets.find((item) => item.seq === view.seq)
      if (!bucket) {
        bucket = { seq: view.seq, title: view.title, state: view.state, text: view.text, line: view.line, blocks: [] }
        buckets.push(bucket)
      } else {
        bucket.state = view.state
        bucket.text = view.text
        bucket.line = view.line || bucket.line
        bucket.title = view.title || bucket.title
      }
      current = bucket
      continue
    }
    if (block.kind === 'assistant') {
      answer += block.text
      continue
    }
    if (block.kind === 'system') {
      notes.push(block.text)
      continue
    }
    if (block.kind === 'error') {
      errors.push(block.text)
      continue
    }
    if (block.kind === 'route' || block.kind === 'reasoning' || block.kind === 'tool') {
      if (current) {
        current.blocks.push(block)
      } else {
        planPhaseBlocks.push(block)
      }
    }
  }
  return {
    planPhase: simpleProcessSteps(planPhaseBlocks),
    plan,
    execution: buckets
      .slice()
      .sort((a, b) => a.seq - b.seq)
      .map((bucket) => ({
        seq: bucket.seq,
        title: bucket.title,
        state: bucket.state,
        text: bucket.text,
        line: bucket.line,
        steps: simpleProcessSteps(bucket.blocks)
      })),
    answer: answer.trim(),
    notes,
    errors
  }
}

/**
 * 扁平时间线：不生成「路由到 / 执行」这类分块头。
 * 计划阶段的推理与单步内部思考都不含路由语义，套 lane 头只会多出无信息的空标题。
 *
 * @param blocks 思考 / 工具 / 路由块，按事件顺序
 * @return 时间线步骤
 */
export function simpleProcessSteps(blocks: AssistantBlock[]): ProcessStep[] {
  const steps: ProcessStep[] = []
  for (const block of blocks) {
    if (block.kind === 'reasoning') {
      appendReasoningStep(steps, block.text)
      continue
    }
    if (block.kind === 'route') {
      const text = (block.text || '').trim()
      if (text) {
        steps.push({ type: 'route', title: '路由', text })
      }
      continue
    }
    const line = block.text.trim()
    const result = (block.detail || '').trim()
    if (line) {
      steps.push({ type: 'tool_call', title: toolCallTitle(line), text: toolCallBody(line) })
    }
    if (result) {
      steps.push({ type: 'tool_result', title: '工具结果', text: result })
    }
  }
  return steps
}

/**
 * 就地推进计划卡状态，并同步「待批准」标记。
 *
 * 用于用户点「批准执行」后立刻把卡片从「待批准」改成「执行中」：
 * 批准接口要跑完整轮才返回，若等响应再刷新，界面会长时间停在「待批准」并只转个按钮。
 *
 * @param turn   该轮助手卡片
 * @param planId 目标计划；为空时取最后一个计划块
 * @param state  新状态（EXECUTING / REJECTED / DONE / FAILED）
 * @return 命中的计划块；没有时为 undefined
 */
export function advancePlanState(turn: ChatTurn, planId: string | undefined, state: string) {
  let hit: AssistantBlock | undefined
  for (let i = turn.blocks.length - 1; i >= 0; i -= 1) {
    const block = turn.blocks[i]
    if (block.kind !== 'plan' || !block.plan) {
      continue
    }
    if (!planId || block.plan.planId === planId) {
      hit = block
      break
    }
  }
  if (!hit || !hit.plan) {
    return undefined
  }
  hit.plan = { ...hit.plan, state }
  hit.text = hit.plan.summary || planText(hit.plan)
  // 待批准是唯一需要人工介入的状态；推进到其它状态后按钮必须立刻消失
  turn.planPending = state === 'PENDING'
  return hit
}

/** 左栏时间。空值返回空串。 */
export function formatRelativeTime(value?: string) {
  if (!value) {
    return ''
  }
  const date = new Date(value.replace(' ', 'T'))
  const time = date.getTime()
  if (Number.isNaN(time)) {
    return value
  }
  const delta = Date.now() - time
  if (delta < 60_000) {
    return '刚刚'
  }
  if (delta < 3_600_000) {
    return `${Math.floor(delta / 60_000)} 分钟前`
  }
  if (delta < 86_400_000) {
    return `${Math.floor(delta / 3_600_000)} 小时前`
  }
  if (delta < 7 * 86_400_000) {
    return `${Math.floor(delta / 86_400_000)} 天前`
  }
  const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`)
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function userTurn(id: number, text: string): ChatTurn {
  return {
    key: `u-${id}`,
    role: 'user',
    text,
    blocks: [],
    phase: 'done',
    status: 'done',
    startTime: 0
  }
}

function pushBlock(turn: ChatTurn, block: AssistantBlock) {
  if (block.kind === 'reasoning' || block.kind === 'assistant') {
    appendText(turn, block.kind, block.text)
    return
  }
  // 回放路由也走 appendRoute，去掉「同子重复 route」以免把连续推理拆开
  if (block.kind === 'route') {
    appendRoute(turn, block.text)
    return
  }
  // 计划与步骤也走 upsert：回放的是一串状态事件，必须原位合并而不是堆多张卡/多行
  if (block.kind === 'plan' && block.plan) {
    upsertPlan(turn, block.plan)
    return
  }
  if (block.kind === 'step' && block.step) {
    upsertStep(turn, block.step)
    return
  }
  turn.blocks.push(block)
}

function blockFromEvent(item: TrialEvent): AssistantBlock | undefined {
  if (item.eventType === 'reasoning') {
    const text = displayText(item)
    return text ? { kind: 'reasoning', text } : undefined
  }
  if (item.eventType === 'tool') {
    const raw = displayText(item) || (item.summary || '').trim()
    if (!raw) {
      return undefined
    }
    const split = raw.indexOf('\n\n')
    if (split < 0) {
      return { kind: 'tool', text: raw }
    }
    const detail = raw.slice(split + 2).trim()
    return {
      kind: 'tool',
      text: raw.slice(0, split).trim(),
      detail: detail || undefined
    }
  }
  if (item.eventType === 'assistant') {
    const text = displayText(item)
    return text ? { kind: 'assistant', text } : undefined
  }
  if (item.eventType === 'route') {
    const text = displayText(item)
    return text ? { kind: 'route', text } : undefined
  }
  if (item.eventType === 'plan') {
    // 正文是结构化 JSON；解析失败时退回摘要，至少让时间线知道这里有一份计划
    const plan = parsePlan(item.contentText)
    if (plan) {
      return { kind: 'plan', text: plan.summary || planText(plan), plan }
    }
    const fallback = (item.summary || '').trim()
    return fallback ? { kind: 'system', text: fallback } : undefined
  }
  if (item.eventType === 'step') {
    const step = parseStep(item.contentText)
    if (step) {
      return { kind: 'step', text: step.line || stepText(step), step }
    }
    const fallback = (item.summary || '').trim()
    return fallback ? { kind: 'step', text: fallback } : undefined
  }
  if (item.eventType === 'system') {
    const text = displayText(item)
    return text ? { kind: 'system', text } : undefined
  }
  if (item.eventType === 'error') {
    const text = displayText(item)
    return text ? { kind: 'error', text } : undefined
  }
  return undefined
}

/** 展示优先用正文，旧数据没有 contentText 时退回短摘要。 */
function displayText(item: TrialEvent): string {
  const full = (item.contentText || '').trim()
  if (full) {
    return full
  }
  return (item.summary || '').trim()
}

/**
 * 解析 PLAN 事件/SSE 帧的 JSON 正文。
 * 字段与后端 PlanJson 一致；形状不对时返回 undefined，由调用方退回摘要展示。
 *
 * @param raw content_text 或 SSE data
 * @return 计划视图；解析失败为 undefined
 */
export function parsePlan(raw?: string): TrialPlanView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    const data = JSON.parse(text) as Partial<TrialPlanView>
    if (!data || typeof data !== 'object') {
      return undefined
    }
    const steps = Array.isArray(data.steps) ? data.steps : []
    return {
      planId: String(data.planId || ''),
      state: String(data.state || 'FAILED'),
      summary: typeof data.summary === 'string' ? data.summary : undefined,
      steps: steps.map((item) => ({
        seq: Number(item?.seq) || 0,
        title: String(item?.title || ''),
        detail: String(item?.detail || '')
      }))
    }
  } catch {
    return undefined
  }
}

/**
 * 解析 STEP 事件/SSE 帧的 JSON 正文。
 *
 * @param raw content_text 或 SSE data
 * @return 单步视图；解析失败为 undefined
 */
export function parseStep(raw?: string): TrialStepView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    const data = JSON.parse(text) as Partial<TrialStepView>
    if (!data || typeof data !== 'object') {
      return undefined
    }
    return {
      seq: Number(data.seq) || 0,
      title: String(data.title || ''),
      state: String(data.state || 'FAILED'),
      text: String(data.text || ''),
      line: typeof data.line === 'string' ? data.line : undefined
    }
  } catch {
    return undefined
  }
}

/** 计划块兜底文案：结构化数据在但服务端没给 summary 时用。 */
export function planText(plan: TrialPlanView): string {
  return `计划共 ${plan.steps.length} 步 · ${planStateText(plan.state)}`
}

/** 计划状态中文。未知状态不伪装成完成。 */
export function planStateText(state?: string): string {
  if (state === 'PENDING') return '待批准'
  if (state === 'EXECUTING') return '执行中'
  if (state === 'REJECTED') return '已取消'
  if (state === 'DONE') return '已完成'
  return '执行失败'
}

/** 单步状态中文。 */
export function stepStateText(state?: string): string {
  if (state === 'RUNNING') return '执行中'
  if (state === 'RETRYING') return '重试中'
  if (state === 'DONE') return '完成'
  return '失败'
}

/** 单步块兜底文案。 */
function stepText(step: TrialStepView): string {
  return `第 ${step.seq} 步 · ${step.title} · ${stepStateText(step.state)}`
}

/**
 * 把计划状态推进写到同一张计划卡上：同一 planId 只保留一份，避免时间线堆出多张矛盾的计划。
 * 状态更新事件不带 steps，此时沿用已有步骤。</br>
 *
 * @param turn 本轮的助手卡片
 * @param next 新到的计划视图
 */
export function upsertPlan(turn: ChatTurn, next: TrialPlanView) {
  const existing = turn.blocks.find((block) => block.kind === 'plan' && block.plan?.planId === next.planId)
  if (existing && existing.plan) {
    existing.plan = {
      planId: next.planId,
      state: next.state,
      summary: next.summary,
      // 状态事件刻意不带 steps；只有首次计划才写步骤
      steps: next.steps.length ? next.steps : existing.plan.steps
    }
    existing.text = existing.plan.summary || planText(existing.plan)
  } else {
    const plan: TrialPlanView = {
      planId: next.planId,
      state: next.state,
      summary: next.summary,
      steps: next.steps
    }
    turn.blocks.push({ kind: 'plan', text: plan.summary || planText(plan), plan })
  }
  turn.planId = next.planId
  // 待批准是唯一需要人工介入的状态；其它状态按钮都应消失
  turn.planPending = next.state === 'PENDING'
}

/**
 * 单步进度：同一 seq 原位更新状态与结果，不追加新块。
 *
 * @param turn 本轮的助手卡片
 * @param next 新到的单步视图
 */
export function upsertStep(turn: ChatTurn, next: TrialStepView) {
  const existing = turn.blocks.find((block) => block.kind === 'step' && block.step?.seq === next.seq)
  if (existing) {
    existing.step = next
    existing.text = next.line || stepText(next)
    return
  }
  turn.blocks.push({ kind: 'step', text: next.line || stepText(next), step: next })
}
