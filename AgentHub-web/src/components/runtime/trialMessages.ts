/**
 * 把试跑事件收成对话时间线。所属域：runtime。
 * 同一轮的思考、工具和回答进同一张助手卡片。没有正文的思考不生成块。
 */
import type {
  AssistantBlock,
  ChatTurn,
  TrialChoiceView,
  TrialEvent,
  TrialFileView,
  TrialPlanView,
  TrialStepView,
  TrialWorkflowNodeView,
  TrialWorkflowView
} from '@/types/runtime'
import { renderSimpleMarkdown } from '@/utils/simpleMarkdown'
import { nextBlockId } from '@/components/runtime/trialTimeline'
import { parseRouteParticipant } from './workbench/workbenchRouteText.ts'

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
  if (type === 'vision') {
    // 图片文字已提取、主模型还没开始：状态条应显示「识别图片文字」而不是「规划中」
    return 'vision'
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
export function appendText(
  turn: ChatTurn,
  kind: 'reasoning' | 'assistant',
  text: string,
  participantId?: string,
  participantName?: string
) {
  if (!text) {
    return
  }
  const last = turn.blocks[turn.blocks.length - 1]
  /*
   * 同类型且同一位参与者才合并：并行 / 交错时两位参与者的 token 会相邻到达，
   * 只按类型合并会把 B 的话接进 A 的块里（串块）。身份不同就另起一块。
   */
  if (last && last.kind === kind && (last.participantId || '') === (participantId || '')) {
    last.text += text
    return
  }
  turn.blocks.push({ id: nextBlockId(turn), kind, text, participantId, participantName })
}

/**
 * 参与者「开始」帧：该步一开始就补一条占位路由块，块随 begin 立刻出现，不等思考 / 工具。
 *
 * <p>为什么入口与汇总不在这里建块：入口块由判定推理 / 判定句建立，汇总由「方案结论：X」
 * 建立；给它们补占位路由行反而会多出一位不存在的专家。
 *
 * <p>真实路由行随后到达时由 {@link appendRoute} 原位补全这条块（补上「依据」），
 * 因此页面上不会出现同一参与者的两块。
 *
 * @param turn  目标助手轮
 * @param chunk 身份帧（只处理 begin=true）
 */
export function appendParticipantBegin(
  turn: ChatTurn,
  chunk: { id?: string; name?: string; kind?: string; routeKey?: string; begin?: boolean }
) {
  if (!chunk || chunk.begin !== true) {
    return
  }
  const id = (chunk.id || '').trim()
  const name = (chunk.name || '').trim()
  const kind = (chunk.kind || '').trim()
  if (!id || !name || kind === 'gateway' || kind === 'conclusion') {
    return
  }
  // 同一位参与者重复 begin（重连 / 回放）：保留第一块，不重复出块
  const existing = turn.blocks.find(
    (block) => block.kind === 'route' && (block.participantId === id || routeNameOf(block.text) === name)
  )
  if (existing) {
    // 该参与者的路由行已经先到（单意图会在开跑前先推一条）：只补身份，不再造占位块
    existing.participantId = existing.participantId || id
    existing.participantName = existing.participantName || name
    return
  }
  const key = (chunk.routeKey || '').trim()
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'route',
    text: key ? `路由到 ${name}（${key}）` : `路由到 ${name}`,
    participantId: id,
    participantName: name,
    begin: true
  })
}

/**
 * 图片文字提取结果。每轮只有一条，整段插入；重复到达时以最新为准（避免刷新后续订重复堆叠）。
 * @param turn 目标助手轮
 * @param text 识别文本
 */
export function appendVision(turn: ChatTurn, text: string) {
  const body = (text || '').trim()
  if (!body) {
    return
  }
  const existing = turn.blocks.find((block) => block.kind === 'vision')
  if (existing) {
    existing.text = body
    return
  }
  // 识别结果排在所有块之前：它是本轮的事实素材，读起来应当在思考与终答之前
  turn.blocks.unshift({ id: nextBlockId(turn), kind: 'vision', text: body })
}

/** 工具按一次调用一块。参数行和结果分开。 */
export function appendTool(
  turn: ChatTurn,
  line: string,
  result?: string,
  participantId?: string,
  participantName?: string
) {
  const text = (line || '').trim()
  const detail = (result || '').trim()
  if (!text && !detail) {
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'tool',
    text: text || detail,
    detail: detail && detail !== text ? detail : undefined,
    participantId,
    participantName
  })
}

/**
 * 工具**开始**执行：追加一条占位块，等结果到达时原位补全。
 *
 * @param turn 目标助手轮
 * @param name 工具名
 * @param participantId   该工具属于哪一位参与者（可空：历史流）
 * @param participantName 参与者展示名（可空）
 */
export function appendToolStart(
  turn: ChatTurn,
  name: string,
  participantId?: string,
  participantName?: string
) {
  const tool = (name || '').trim()
  if (!tool) {
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'tool',
    text: '',
    toolName: tool,
    toolState: 'running',
    participantId,
    participantName
  })
}

/**
 * 工具执行结束：把最近一条同名「正在调用」块补成成功 / 失败。
 *
 * <p>为什么按名字找最近一条 running：同一轮里同名工具可能被连续调用多次，
 * 用「最后一条还在跑的」才对应得上引擎的完成顺序；找不到就退回追加一条普通结果块。
 *
 * @param turn     目标助手轮
 * @param name     工具名
 * @param line     展示行（含参数）
 * @param result   结果正文；可空
 * @param success  是否成功
 * @return true 表示已就地补全
 */
export function completeTool(
  turn: ChatTurn,
  name: string,
  line: string,
  result: string | undefined,
  success: boolean
): boolean {
  const tool = (name || '').trim()
  const text = (line || '').trim()
  const detail = (result || '').trim()
  for (let i = turn.blocks.length - 1; i >= 0; i -= 1) {
    const block = turn.blocks[i]
    if (block.kind !== 'tool' || block.toolState !== 'running') {
      continue
    }
    if (tool && (block.toolName || '').trim() !== tool) {
      continue
    }
    block.toolState = success ? 'done' : 'error'
    block.text = text || block.text || tool
    block.detail = detail && detail !== text ? detail : undefined
    block.toolArgsText = block.toolArgsText || text
    return true
  }
  return false
}

/**
 * 产物块：按 fileName 去重，同名只保留最新一条。
 *
 * <p>为什么要去重：Agent 常分多次写同一个文件（先写骨架再追加正文），
 * 每次都会推一条 file_generated。若逐条追加，聊天区会刷出一排指向同一文件的卡片，
 * 用户既看不出区别、也点不到「正确」的那个。
 * 覆盖更新还能同步刷新体积——最终卡片展示的必然是最后一次写入后的大小。
 *
 * @param turn 目标助手轮
 * @param file 产物视图（含后端拼好的 downloadUrl）
 */
export function appendFile(turn: ChatTurn, file: TrialFileView) {
  const name = (file.fileName || '').trim()
  if (!name) {
    return
  }
  const next: TrialFileView = { fileName: name, size: file.size, downloadUrl: file.downloadUrl }
  const existing = turn.blocks.find((block) => block.kind === 'file' && block.file?.fileName === name)
  if (existing) {
    // 原位覆盖：位置不变，用户已看到的卡片不会跳到别处
    existing.file = next
    existing.text = fileNameOf(next.fileName)
    return
  }
  turn.blocks.push({ id: nextBlockId(turn), kind: 'file', text: fileNameOf(name), file: next })
}

/**
 * 结构化选项块：本轮需要用户拍板时补一条，chips 由它渲染。
 *
 * <p>为什么单独成块而不是塞回正文：正文里不能出现裸 JSON；而且它必须在刷新 / 回放
 * 时有确定位置（紧跟助手正文之后），投影层据此把 chips 挂到同一位参与者的气泡下方。
 *
 * <p>同一轮只保留一条：后端最多推一次，重连 / 重复回放时原位覆盖即可，不叠加空栏。
 *
 * @param turn        目标助手轮
 * @param choice      结构化选项（question + 2~6 项）
 * @param participantId   该选项属于哪一位参与者（可空：续作轮没有身份帧）
 * @param participantName 参与者展示名（可空）
 */
export function appendChoice(
  turn: ChatTurn,
  choice: TrialChoiceView,
  participantId?: string,
  participantName?: string
) {
  const options = Array.isArray(choice?.options) ? choice.options.filter((one) => !!one?.id && !!one?.label) : []
  // 少于两项不成选项：宁可不出 chips，也不给一组看不懂的按钮
  if (options.length < 2) {
    return
  }
  const text = (choice.question || '').trim()
  const existing = turn.blocks.find((block) => block.kind === 'choice')
  if (existing) {
    existing.text = text
    existing.choice = { question: text, options }
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'choice',
    text,
    choice: { question: text, options },
    participantId,
    participantName
  })
}

/**
 * 工作流执行卡片：把一份状态快照收成 `workflow` 块。
 *
 * <p>为什么原位覆盖而不是追加：卡片是**状态快照**（同一份时间轴从「运行中」变成
 * 「已完成」），追加会让页面上出现两遍同样的流程，刷新回放更是一串互相矛盾的旧状态。
 *
 * <p>识别同一个工作流按「编码优先、名字兜底」：同一轮里同名同编码只会有一条。
 *
 * @param turn 目标助手轮
 * @param raw  SSE 帧 / 落库正文解析出的快照；结构不完整时整块丢弃（不画空卡片）
 */
export function appendWorkflow(turn: ChatTurn, raw: WorkflowSnapshotInput | undefined) {
  const workflow = normalizeWorkflow(raw)
  if (!workflow) {
    return
  }
  const existing = turn.blocks.find((block) => block.kind === 'workflow' && sameWorkflow(block.workflow, workflow))
  if (existing) {
    existing.workflow = workflow
    existing.participantName = existing.participantName || workflow.name
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'workflow',
    text: workflow.name || workflow.code,
    workflow,
    participantName: workflow.name || workflow.code
  })
}

/**
 * 两个卡片快照是否指向同一次工作流运行。
 *
 * @param left  已有块上的视图
 * @param right 新到的视图
 * @return true 表示同一块，应原位覆盖
 */
function sameWorkflow(left: TrialWorkflowView | undefined, right: TrialWorkflowView) {
  const code = (right.code || '').trim()
  if (code) {
    return (left?.code || '').trim() === code
  }
  return (left?.name || '').trim() === (right.name || '').trim()
}

/**
 * 卡片快照的**输入**形状：SSE 帧 / 落库 JSON / 内存快照回放都可能只带部分字段，
 * 因此这里按 unknown 收、逐字段校验，不信任任何上游。
 */
export interface WorkflowSnapshotInput {
  name?: unknown
  code?: unknown
  status?: unknown
  answer?: unknown
  nodes?: unknown
  /** 节点上的可选字段（归一化时逐个校验） */
  type?: unknown
  note?: unknown
}

/**
 * 归一化卡片快照：只接受真实字段，状态一律大写，节点按图顺序。
 *
 * <p>边界：名字与编码都为空时整块丢弃——一张没有名字的卡片无法归属到任何工作流。
 *
 * @param raw 原始快照（SSE 帧 / 落库 JSON / 快照回放）
 * @return 归一化视图；不可用时为 undefined
 */
export function normalizeWorkflow(raw: WorkflowSnapshotInput | undefined): TrialWorkflowView | undefined {
  if (!raw || typeof raw !== 'object') {
    return undefined
  }
  const name = typeof raw.name === 'string' ? raw.name.trim() : ''
  const code = typeof raw.code === 'string' ? raw.code.trim() : ''
  if (!name && !code) {
    return undefined
  }
  const rawNodes = Array.isArray(raw.nodes) ? raw.nodes : []
  const nodes: TrialWorkflowNodeView[] = rawNodes
    .filter((node) => !!node && typeof node === 'object')
    .map((node) => {
      const one = node as WorkflowSnapshotInput
      return {
        code: typeof one.code === 'string' ? one.code.trim() : '',
        name: typeof one.name === 'string' ? one.name.trim() : '',
        type: typeof one.type === 'string' ? one.type.trim().toUpperCase() : '',
        status: typeof one.status === 'string' && one.status.trim() ? one.status.trim().toUpperCase() : 'PENDING',
        note: typeof one.note === 'string' ? one.note.trim() : ''
      }
    })
  return {
    name,
    code,
    status: typeof raw.status === 'string' && raw.status.trim() ? raw.status.trim().toUpperCase() : 'RUNNING',
    answer: typeof raw.answer === 'string' ? raw.answer : '',
    nodes
  }
}

/**
 * 卡片标题用的文件名：只取末段，避免子目录把卡片撑宽。
 *
 * @param fileName 相对工作区根的路径
 * @return 末段文件名
 */
export function fileNameOf(fileName: string) {
  const normalized = (fileName || '').replace(/\\/g, '/')
  const slash = normalized.lastIndexOf('/')
  return slash >= 0 ? normalized.slice(slash + 1) : normalized
}

/**
 * 体积展示。与服务端 GeneratedFile#humanSize 口径一致，避免前后端两套数字。
 *
 * @param size 字节数；< 0 表示未知
 * @return 如 {@code 2.0 KB} / {@code 大小未知}
 */
export function formatFileSize(size: number) {
  if (!Number.isFinite(size) || size < 0) {
    return '大小未知'
  }
  if (size < 1024) {
    return `${size} B`
  }
  const kb = size / 1024
  if (kb < 1024) {
    return `${kb.toFixed(1)} KB`
  }
  return `${(kb / 1024).toFixed(1)} MB`
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
  /**
   * 工具块状态（仅 tool_call）：`running` 表示引擎已开始、结果还没回来。
   *
   * <p>缺省＝已完成。前端据此把「正在调用」画成蓝色胶囊，而不是等结果才出现。
   */
  state?: 'running' | 'done' | 'error'
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
  /** 该泳道对应的参与者身份（begin 帧给的 id）；历史数据为空 */
  participantId?: string
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
    // route_key 是内部定位键，不对终端用户展示（工作台 / 运行气泡）
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
  /*
   * 身份 → 泳道。有 begin 帧的轮次里，思考 / 工具块自带参与者 id：
   * 直接归到该参与者的泳道，不再靠「最后一条 route 文案」猜当前说话人。
   * 历史数据没有 id，仍走原来的 current 口径，行为不变。
   */
  const byParticipant = new Map<string, ProcessLane>()
  /*
   * 是否处于「汇总」阶段：`方案结论：X` 之后的过程事件属于汇总那条独立记录，
   * 不能再按上一位子专家的身份回填（否则汇总的思考会串进最后一位专家块）。
   */
  let conclusionPhase = false

  for (const block of processBlocks(blocks)) {
    if (block.kind === 'route') {
      for (const piece of splitRoutePieces(block.text)) {
        // 汇总标记不是参与者：从这里起，后续过程事件不再归属上一位
        if (piece.startsWith('方案结论：')) {
          conclusionPhase = true
          current = undefined
          continue
        }
        const lane = routeLane(piece)
        if (!lane) {
          continue
        }
        if (lane.kind === 'agent') {
          conclusionPhase = false
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
            // 占位块与真实路由行落在同一泳道时，把身份也记到泳道上
            if (block.participantId) {
              existing.participantId = block.participantId
              byParticipant.set(block.participantId, existing)
            }
            current = existing
            continue
          }
        }
        if (block.participantId && lane.kind === 'agent') {
          lane.participantId = block.participantId
        }
        lanes.push(lane)
        if (block.participantId && lane.kind === 'agent') {
          byParticipant.set(block.participantId, lane)
        }
        current = lane.kind === 'agent' ? lane : undefined
      }
      continue
    }
    /*
     * 有身份的块：落到它自己的泳道。
     * - id 不在映射里（入口网关的判定推理 / 未能建块的参与者）：落匿名执行块，
     *   不能退回「当前泳道」，否则会把入口的思考挂到第一位专家名下；
     * - 汇总阶段：一律落匿名执行块，汇总内容由投影层单独收进结论记录。
     */
    const pinned = !conclusionPhase && block.participantId ? byParticipant.get(block.participantId) : undefined
    if (pinned) {
      current = pinned
    } else if (block.participantId || conclusionPhase) {
      current = undefined
    }
    if (!current || current.kind !== 'agent') {
      // 尚无子块时落进匿名执行块，避免丢推理/工具；带上身份，投影层才能把入口推理认回来
      current = { kind: 'agent', title: '执行', participantId: block.participantId, steps: [] }
      lanes.push(current)
      /*
       * 同一身份的后续增量继续落这一块。
       * 不登记的话，每个带身份的块都会另起一条「执行」泳道：入口判定推理被拆成多条，
       * 投影层只能靠位置拼，一旦中间夹进决策行就整段丢掉。
       */
      if (block.participantId) {
        byParticipant.set(block.participantId, current)
      }
    }
    if (block.kind === 'reasoning') {
      // 同 lane 内连续推理合并为一步（防 laneBound 重推路由把流式拆成一词一步；回放错步亦重建）
      appendReasoningStep(current.steps, block.text)
      continue
    }
    const line = block.text.trim()
    const result = (block.detail || '').trim()
    /*
     * 工具开始（还没有结果）：只记一条 running 的 tool_call，用于画「正在调用」胶囊。
     * block.text 此时是空串，工具名在 block.toolName 里。
     */
    if (block.toolState === 'running' && !line) {
      const name = (block.toolName || '').trim()
      if (name) {
        current.steps.push({ type: 'tool_call', title: `调用工具 ${name}`, text: '', state: 'running' })
      }
      continue
    }
    if (line) {
      current.steps.push({
        type: 'tool_call',
        title: toolCallTitle(line),
        text: toolCallBody(line),
        state: block.toolState === 'error' ? 'error' : 'done'
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
 *
 * <p>固定模板 / 串行模板的子块标题是「固定并行 1/2 · 某专家（key）」这一类，
 * 也要按分号切开，否则多个子会被当成一段说明。
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
    .split(/；(?=路由到|固定并行|固定串行|路由后串行|竞争选优)/)
    .map((part) => part.trim())
    .filter(Boolean)
}

/**
 * 固定模板 / 串行模板的子块标题解析。
 *
 * <p>这些子块不是判路由出来的，但同样是「这一位参与者开始作答」的真实事件：
 * 后端写成「固定并行 1/2 · 制度知识专家（policy）」。只把它当说明句，
 * 消息流就会看不见这些专家。这里归一成与「路由到 X」同构的参与者块。
 */
function parseChildTitle(raw: string): { name: string; key: string } | undefined {
  return parseRouteParticipant(raw)
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
  // 固定模板 / 串行模板的子块：同样是某位专家开始作答，归一成参与者块
  const child = parseChildTitle(text)
  if (child?.name) {
    return {
      kind: 'agent',
      title: `路由到 ${child.name}`,
      routeKey: child.key || undefined,
      steps: []
    }
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
 *
 * <p>若该参与者在 begin 时已补过占位路由行（只有名字与 route_key），这里**原位补全**
 * 这条块（补上「依据」），不另起一条——同一个参与者只会出一块。
 *
 * <p>并行子重推本子路由：只要「最近一条路由」已是同文案（中间可夹推理/工具），就跳过，
 * 避免打断 appendText 合并。
 *
 * @param turn    目标助手轮
 * @param summary 路由摘要原文
 * @param participantId   当前归属身份（可空：历史流 / 决策块）
 * @param participantName 当前归属展示名（可空）
 */
export function appendRoute(
  turn: ChatTurn,
  summary: string,
  participantId?: string,
  participantName?: string
) {
  const text = (summary || '').trim()
  if (!text) {
    return
  }
  // 先找同一位参与者的 begin 占位块：真实路由行到达时原位补全
  const placeholder = text.startsWith('路由到')
    ? [...turn.blocks].reverse().find((block) => block.kind === 'route' && block.begin === true
      && sameRouteTarget(block.text, text))
    : undefined
  if (placeholder) {
    placeholder.text = text
    placeholder.begin = false
    if (!placeholder.participantId && participantId) {
      placeholder.participantId = participantId
    }
    if (!placeholder.participantName && participantName) {
      placeholder.participantName = participantName
    }
    return
  }
  const active = latestRouteText(turn.blocks)
  if (active === text) {
    return
  }
  // 身份只在「这条路由确实是这位参与者」时写入，避免把上一位的 id 贴到新路由块上
  const sameOwner = routeNameOf(text) !== '' && routeNameOf(text) === (participantName || '').trim()
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'route',
    text,
    participantId: sameOwner ? participantId : undefined,
    participantName: sameOwner ? participantName : undefined
  })
}

/**
 * 两条路由行是否指向同一位参与者（名字相同；双方都有 key 时 key 也必须相同）。
 *
 * @param left  已有路由行
 * @param right 新到的路由行
 * @return true 表示可以原位补全
 */
function sameRouteTarget(left: string, right: string): boolean {
  const a = parseRouteParticipant(left)
  const b = parseRouteParticipant(right)
  if (!a?.name || !b?.name || a.name !== b.name) {
    return false
  }
  const leftKey = (a.key || '').trim()
  const rightKey = (b.key || '').trim()
  return !leftKey || !rightKey || leftKey === rightKey
}

/**
 * 路由行里的展示名；解析不出时为空串（决策块 / 说明行不算参与者）。
 *
 * @param raw 路由行
 * @return 展示名
 */
function routeNameOf(raw: string): string {
  return (parseRouteParticipant(raw)?.name || '').trim()
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
  /** 本轮生成的可下载文件。渲染在终答之后，是「产物」而不是「过程」 */
  files: TrialFileView[]
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
  const files: TrialFileView[] = []
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
    if (block.kind === 'vision') {
      // 识别结果是本轮素材，不属于任何执行步；由模板在卡片顶部单独渲染，这里直接跳过桶分配
      continue
    }
    if (block.kind === 'file') {
      // 产物不属于某一步的思考过程：由模板在卡片下方统一渲染下载卡片，这里不进任何桶
      if (block.file) {
        // 同一文件多次生成时按文件名去重，与 appendFile 的口径一致
        const index = files.findIndex((item) => item.fileName === block.file?.fileName)
        if (index >= 0) {
          files[index] = block.file
        } else {
          files.push(block.file)
        }
      }
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
    files,
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
    // 工具开始（无结果）：只画「正在调用」胶囊
    if (block.toolState === 'running' && !line) {
      const name = (block.toolName || '').trim()
      if (name) {
        steps.push({ type: 'tool_call', title: `调用工具 ${name}`, text: '', state: 'running' })
      }
      continue
    }
    if (line) {
      steps.push({
        type: 'tool_call',
        title: toolCallTitle(line),
        text: toolCallBody(line),
        state: block.toolState === 'error' ? 'error' : 'done'
      })
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

/**
 * 给助手轮打上「编排调度卡」前端标记（ROUTER_TRACE）。
 *
 * <p>只影响前端时间线：不写入 agent_run_event、不参与 {@link turnsFromEvents} 回放，
 * 刷新后由试跑状态重新判定再挂上。插到块列表最前，保证「调度卡 → 思考 → 终答」的阅读顺序。
 *
 * @param turn 目标助手轮；用户轮直接忽略
 */
export function markRouterTrace(turn: ChatTurn) {
  if (turn.role !== 'assistant') {
    return
  }
  if (turn.blocks.some((block) => block.kind === 'ROUTER_TRACE')) {
    return
  }
  turn.blocks.unshift({ id: nextBlockId(turn), kind: 'ROUTER_TRACE', text: '正在调度…' })
}

/**
 * 这一轮是否带编排调度卡标记。
 * 仅用于展示层判位，不参与任何落库/回放逻辑。
 * @param turn 助手轮
 */
export function hasRouterTrace(turn: ChatTurn): boolean {
  return turn.role === 'assistant' && turn.blocks.some((block) => block.kind === 'ROUTER_TRACE')
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
  // 产物也走去重：同一文件多次生成只保留最新一条卡片
  if (block.kind === 'file' && block.file) {
    appendFile(turn, block.file)
    return
  }
  // 选项也走去重：同一轮只保留一条 chips，重复回放原位覆盖
  if (block.kind === 'choice' && block.choice) {
    appendChoice(turn, block.choice, block.participantId, block.participantName)
    return
  }
  // 工作流卡片同理：同一轮同一工作流只保留最新一份快照
  if (block.kind === 'workflow' && block.workflow) {
    appendWorkflow(turn, block.workflow)
    return
  }
  // 其余块（vision / system / error / ROUTER_TRACE）直接入列：补齐稳定 id 再 push，
  // 否则模板只能退回下标 key，插入视觉块时会整表重挂
  turn.blocks.push({ ...block, id: block.id || nextBlockId(turn) })
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
  if (item.eventType === 'vision') {
    const text = displayText(item)
    return text ? { kind: 'vision', text } : undefined
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
  if (item.eventType === 'file') {
    // 正文是 {"fileName","size"}；downloadUrl 落库时不存（会话主键在路径里），故按当前会话现拼
    const file = parseFile(item.contentText, item.sessionId)
    if (!file) {
      const fallback = (item.summary || '').trim()
      return fallback ? { kind: 'system', text: fallback } : undefined
    }
    return { kind: 'file', text: fileNameOf(file.fileName), file }
  }
  if (item.eventType === 'choice') {
    /*
     * 结构化选项：正文是 {"question","options"}（与 SSE 帧同形），摘要是问题句。
     * 解析不出合法结构时整块丢弃——回放也不能凭空造一组按钮。
     */
    const choice = parseChoiceEvent(item.contentText)
    return choice ? { kind: 'choice', text: choice.question, choice } : undefined
  }
  if (item.eventType === 'workflow') {
    /*
     * 工作流执行卡片：正文是 {name,code,status,answer,nodes} 的 JSON（与 SSE 帧同形）。
     * 解析失败时整块丢弃：宁可刷新后只剩正文，也不能画一张字段错位的假卡片。
     */
    const workflow = parseWorkflowEvent(item.contentText)
    return workflow ? { kind: 'workflow', text: workflow.name || workflow.code, workflow } : undefined
  }
  if (item.eventType === 'error') {
    const text = displayText(item)
    return text ? { kind: 'error', text } : undefined
  }
  /*
   * 参与者身份帧（后端只记 begin）：刷新时读的是进行中一轮的内存快照，
   * 此刻该步可能还没产出任何思考 / 工具。补一条占位路由块，页面才能与实时一致地先出块。
   * begin=false 的归属切换不建块（那是给实时增量定向用的，回放不需要）。
   */
  if (item.eventType === 'participant') {
    return participantBeginBlock(item)
  }
  return undefined
}

/**
 * 身份帧 → 占位路由块。所属域：runtime（前端）。
 *
 * <p>只认 begin=true 且 id / 名字齐全的帧；入口与汇总是「块由内容建立」的两位，
 * 它们不吃占位（补了会多出一位不存在的参与者）。与 {@link appendParticipantBegin} 同口径。
 *
 * @param item 落库 / 快照里的 participant 事件
 * @return 占位路由块；不入列时返回 undefined
 */
function participantBeginBlock(item: TrialEvent): AssistantBlock | undefined {
  const raw = (item.contentText || '').trim()
  if (!raw || raw[0] !== '{') {
    return undefined
  }
  let chunk: { id?: string; name?: string; kind?: string; routeKey?: string; begin?: boolean }
  try {
    chunk = JSON.parse(raw)
  } catch {
    // 半截 JSON（快照写入时被截断）：宁可不出块，也不抛进渲染树
    return undefined
  }
  if (!chunk || chunk.begin !== true) {
    return undefined
  }
  const id = (chunk.id || '').trim()
  const name = (chunk.name || '').trim()
  const kind = (chunk.kind || '').trim()
  if (!id || !name || kind === 'gateway' || kind === 'conclusion') {
    return undefined
  }
  const key = (chunk.routeKey || '').trim()
  return {
    kind: 'route',
    text: key ? `路由到 ${name}（${key}）` : `路由到 ${name}`,
    participantId: id,
    participantName: name,
    begin: true
  }
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
 * 解析落库的工作流卡片事件正文（{@code {"name","code","status","answer","nodes"}}）。
 *
 * <p>与 SSE 帧同形，因此直接复用 {@link normalizeWorkflow}：两条来源一套口径，
 * 不会出现「实时看得到、刷新后字段对不上」。
 *
 * @param raw 事件正文 JSON，可空
 * @return 归一化视图；空正文 / 半截 JSON / 缺名字与编码时为 undefined
 */
export function parseWorkflowEvent(raw: string | undefined): TrialWorkflowView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    return normalizeWorkflow(JSON.parse(text) as WorkflowSnapshotInput)
  } catch {
    // 半截 JSON（列宽截断）：不出卡片，本轮仍能看正文
    return undefined
  }
}

/**
 * 解析 CHOICE 事件正文（与 SSE `choice` 帧同一形状）。
 *
 * <p>边界与后端一致：`options` 必须是 2~6 项、每项 id / label 非空且 id 不重复，
 * 任何一条不合格就整份作废——宁可这一轮没有 chips，也不渲染一组点了会对错人的按钮。
 *
 * @param raw 事件正文 JSON，可空
 * @return 结构化选项；解析失败或越界时为 undefined
 */
export function parseChoiceEvent(raw: string | undefined): TrialChoiceView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  let data: { question?: unknown; options?: unknown }
  try {
    data = JSON.parse(text) as { question?: unknown; options?: unknown }
  } catch {
    // 半截 JSON（快照写入时被截断）：不出块，正文照常展示
    return undefined
  }
  if (!data || !Array.isArray(data.options)) {
    return undefined
  }
  const options: { id: string; label: string }[] = []
  for (const item of data.options) {
    if (!item || typeof item !== 'object') {
      return undefined
    }
    const one = item as { id?: unknown; label?: unknown }
    const id = typeof one.id === 'string' ? one.id.trim() : ''
    const label = typeof one.label === 'string' ? one.label.trim() : ''
    if (!id || !label) {
      return undefined
    }
    if (options.some((row) => row.id.toLowerCase() === id.toLowerCase())) {
      return undefined
    }
    options.push({ id, label })
  }
  if (options.length < 2 || options.length > 6) {
    return undefined
  }
  return { question: typeof data.question === 'string' ? data.question.trim() : '', options }
}

/**
 * 解析 FILE 事件正文，并现场拼出下载地址。
 *
 * <p>为什么落库只存 fileName + size：会话主键在下载路径里，
 * 存进正文会随会话复制/迁移而失效；地址是「派生值」，回放时按当前会话现拼更稳。
 *
 * @param raw       事件正文 JSON
 * @param sessionId 事件所属会话；缺失时 downloadUrl 为空串，卡片仍可展示但置灰
 * @return 产物视图；解析失败时为 undefined
 */
export function parseFile(raw: string | undefined, sessionId?: number): TrialFileView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    const data = JSON.parse(text) as { fileName?: unknown; size?: unknown }
    const fileName = String(data?.fileName || '').trim()
    if (!fileName) {
      return undefined
    }
    const size = Number(data?.size)
    return {
      fileName,
      size: Number.isFinite(size) ? size : -1,
      // 会话主键缺失（异常快照）时不编造地址，交给卡片按「不可下载」渲染
      downloadUrl: sessionId ? buildDownloadUrl(sessionId, fileName) : ''
    }
  } catch {
    return undefined
  }
}

/**
 * 拼下载地址。编码规则与后端 TrialChunkWriter 的 downloadUrl 保持一致，
 * 保证「实时推送的卡片」与「刷新后回放的卡片」点下去打开的是同一个地址。
 *
 * @param sessionId 会话主键
 * @param fileName  相对工作区根的文件名
 * @return 以 /api 开头的下载路径
 */
export function buildDownloadUrl(sessionId: number, fileName: string) {
  return `/api/runtime/workspace/download?sessionId=${sessionId}&fileName=${encodeURIComponent(fileName)}`
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
    turn.blocks.push({ id: nextBlockId(turn), kind: 'plan', text: plan.summary || planText(plan), plan })
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
  turn.blocks.push({ id: nextBlockId(turn), kind: 'step', text: next.line || stepText(next), step: next })
}
