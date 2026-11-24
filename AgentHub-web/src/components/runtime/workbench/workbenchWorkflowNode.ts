/**
 * 工作流卡时间轴节点的展示口径。所属域：runtime（前端展示）。
 *
 * <p>只消费 {@link TrialWorkflowNodeView} 里已有字段：节点种类（AGENT / TOOL / …）与状态。
 * 不编造工具调用——节点级胶囊只给图上的 TOOL 节点；AGENT 节点内部 ReAct 的工具
 * 已经落在节点 `steps` 里，由 `wfNodeStepToolPills` 单独聚合成折叠外的胶囊，不在这里猜。
 */

import type { TrialWorkflowNodeView } from '@/types/runtime'

/**
 * 节点上可展示的工具胶囊（形态对齐专家卡）。
 *
 * <p>同名工具由 {@link aggregateWorkflowToolPills} 合成一条并按次计数；节点级胶囊
 * （图上 TOOL 节点）只有一次调用，不带次数字段，按 1 次展示。
 */
export interface WorkflowNodeToolPill {
  /** 稳定键：节点编码 / 名（同名合并后就是工具名） */
  key: string
  /** 工具展示名（节点名；编码仅作缺省） */
  name: string
  /** running / done / error；与专家卡胶囊同三态（合并后：有跑 → running，否则有失败 → error） */
  state: 'running' | 'done' | 'error'
  /**
   * 胶囊详情（合并后是**每一次**调用的结果摘录，可选）。
   *
   * <p>工作流快照只给脱敏后的结果文本，没有入参；有详情时胶囊可点开，
   * 行为与方案参与者卡共用 `ToolPillList`。
   */
  detail?: string
  /** 同名合并后的调用总次数；缺省按 1 次处理（节点级胶囊没有次数） */
  total?: number
  /** 合并后成功次数；缺省表示未统计 */
  ok?: number
  /** 合并后失败次数；缺省表示未统计 */
  failed?: number
}

/** 聚合所需的一步：与节点 `steps` 里 TOOL 步同形，避免把整个节点视图拉进来 */
export interface WorkflowToolStepLike {
  /** 步骤类型：只认 TOOL；缺省按 TOOL 处理（REASONING 等其它类型一律忽略） */
  type?: string
  /** 工具名；空时并到「工具」 */
  name?: string
  /** 工具结果摘录（已脱敏 / 已截断） */
  content?: string
  /** running / done / error；旧数据没有时按已完成 */
  state?: string
}

/**
 * 该步是否为 Agent 节点（要在时间轴上标明）。
 *
 * @param node 工作流卡节点；可空
 * @return true 表示 type=AGENT
 */
export function isWorkflowAgentNode(node: TrialWorkflowNodeView | null | undefined): boolean {
  return (node?.type || '').trim().toUpperCase() === 'AGENT'
}

/**
 * 从节点推出节点级工具胶囊：仅 type=TOOL 时有；其它种类（含 AGENT）返回 null。
 *
 * <p>为什么 AGENT 不出：AGENT 内部工具已经随节点 `steps` 落到前端，
 * 由 `wfNodeStepToolPills` 聚合；这里再用节点名冒充「已调用 xxx」会重复且误导。
 *
 * @param node 工作流卡节点；可空
 * @return 胶囊；不是 TOOL 或不完整时为 null
 */
export function workflowNodeToolPill(
  node: TrialWorkflowNodeView | null | undefined
): WorkflowNodeToolPill | null {
  if (!node) {
    return null
  }
  if ((node.type || '').trim().toUpperCase() !== 'TOOL') {
    return null
  }
  const name = (node.name || '').trim() || (node.code || '').trim() || '工具'
  const key = (node.code || '').trim() || name
  return { key, name, state: toolPillStateOf(node.status) }
}

/**
 * 节点状态 → 胶囊三态（对齐专家卡：调用中蓝、失败红、其余静态度）。
 *
 * @param status 节点大写状态码
 * @return running / done / error
 */
function toolPillStateOf(status: string | undefined): 'running' | 'done' | 'error' {
  const code = (status || '').trim().toUpperCase()
  if (code === 'RUNNING') {
    return 'running'
  }
  if (code === 'FAILED') {
    return 'error'
  }
  return 'done'
}

/**
 * 节点内工具调用（AGENT 节点 steps 里的 TOOL 步）→ 折叠外的胶囊。
 *
 * <p>合并口径与方案参与者卡 `aggregateToolPills` 一致：同一工具名只出一条、次数取真实调用数、
 * 详情保留每一次的结果。**不编造入参**：工作流快照只有结果摘录，没有入参。
 * 工作流自己的文案仍由 {@link workflowNodeToolLabel} 组装，不复用方案侧的 `toolPhrase`。
 *
 * <p>状态：还有一路在跑就整条 running（不再并列多条蓝条）；全部结束后有失败即 error，
 * 全成功为 done。整轮已中断（closed=aborted）时残留的 running 落到已完成，胶囊不再转圈。
 *
 * @param steps  节点过程步（只认 TOOL；其它类型忽略）
 * @param closed 这一位的终态
 * @return 胶囊列表（按工具首次出现顺序）；没有工具步时为空表
 */
export function aggregateWorkflowToolPills(
  steps: WorkflowToolStepLike[],
  closed?: string
): WorkflowNodeToolPill[] {
  const order: string[] = []
  const groups = new Map<string, {
    total: number
    running: number
    failed: number
    ok: number
    /** 是否真的有过结果摘录：全都没有时不出现「详情」入口 */
    hasContent: boolean
    /** 逐次结果摘录 */
    detail: string[]
  }>()

  for (const step of Array.isArray(steps) ? steps : []) {
    // 只聚合工具步：思考步（REASONING）不是一次调用，不能算进次数
    if ((step?.type || 'TOOL').trim().toUpperCase() !== 'TOOL') {
      continue
    }
    const name = (step?.name || '').trim() || '工具'
    let group = groups.get(name)
    if (!group) {
      group = { total: 0, running: 0, failed: 0, ok: 0, hasContent: false, detail: [] }
      groups.set(name, group)
      order.push(name)
    }
    group.total += 1
    const state = (step.state || '').trim().toLowerCase()
    // 整轮已中断：残留的 running 落到终态，不再当作还在跑
    if (state === 'running' && closed !== 'aborted') {
      group.running += 1
      continue
    }
    // 失败与成功分开计数：合并后文案按方案卡结构写清两边次数
    if (state === 'error') {
      group.failed += 1
    } else {
      group.ok += 1
    }
    const content = (step.content || '').trim()
    if (content) {
      group.hasContent = true
    }
    // 详情按真实调用顺序编号；这一次没有结果摘录时也留一行，不丢序号
    group.detail.push(`第 ${group.total} 次\n出参：${content || '（无）'}`)
  }

  return order.map((name) => {
    const group = groups.get(name)!
    const state: WorkflowNodeToolPill['state'] =
      group.running > 0 ? 'running' : group.failed > 0 ? 'error' : 'done'
    return {
      key: name,
      name,
      state,
      total: group.total,
      ok: group.ok,
      failed: group.failed,
      detail: group.hasContent ? group.detail.join('\n\n') : ''
    }
  })
}

/**
 * 节点名放进「已调用【…】」时用的展示名。
 *
 * <p>调用方已解析出工具项标题时优先用标题。节点名本身是中文（图上的展示名）也直接用。
 * 英文函数名没有标题时不写进句子，退回「工具」。
 *
 * @param face 已解析的中文名，可空
 * @param name 节点上的原始名字
 * @return 带中括号的展示名
 */
function bracketFace(face: string | undefined, name: string): string {
  const given = (face || '').trim()
  if (given) {
    return `【${given}】`
  }
  if (/[\u4e00-\u9fff]/.test(name || '')) {
    return `【${name.trim()}】`
  }
  return '【工具】'
}

/**
 * 工具胶囊上的一句文案（有调用才显示，不编造次数）。
 *
 * <p>计数口径与方案参与者卡 `toolPhrase` 一致：只有同名多次调用才补次数，单次不写「（1 次）」。
 * 句子结构仍归工作流自己，不和方案侧的 `toolPhrase` 合成一套函数。
 *
 * @param pill   胶囊（合并后的带 total / ok / failed；节点级胶囊没有）
 * @param status 节点原始状态（区分 PENDING 与 SUCCESS）
 * @param face   工具项中文名；不传时按节点名是否已是中文决定
 * @return 展示句
 */
export function workflowNodeToolLabel(
  pill: WorkflowNodeToolPill,
  status: string | undefined,
  face?: string
): string {
  const shown = bracketFace(face, pill.name)
  const code = (status || '').trim().toUpperCase()
  // 只有真的多于一次才报数：与方案卡同一套规则，单次不多写「（1 次）」
  const times = typeof pill.total === 'number' && pill.total > 1 ? pill.total : 0
  if (pill.state === 'running' || code === 'RUNNING') {
    return `正在调用${shown}${times ? `（${times}）` : ''}…`
  }
  if (pill.state === 'error' || code === 'FAILED') {
    // 合并后成功 / 失败都有真实计数时写清两边；单次失败仍是「调用失败」
    if (times && typeof pill.ok === 'number' && typeof pill.failed === 'number') {
      return `${shown}：成功 ${pill.ok} / 失败 ${pill.failed}`
    }
    return `${shown}：调用失败`
  }
  if (code === 'PENDING' || code === 'WAITING_HUMAN' || code === 'WAITING_REPLY') {
    return `将调用${shown}`
  }
  if (code === 'SKIPPED' || code === 'CANCELLED') {
    return `已跳过${shown}`
  }
  return `已调用${shown}${times ? `（${times} 次）` : ''}`
}
