/**
 * 工作流卡时间轴节点的展示口径。所属域：runtime（前端展示）。
 *
 * <p>只消费 {@link TrialWorkflowNodeView} 里已有字段：节点种类（AGENT / TOOL / …）与状态。
 * 不编造工具调用——只有图上的 TOOL 节点才出工具胶囊；AGENT 节点内部 ReAct 调了什么工具，
 * 现网工作流 SSE / 落库快照里没有这份列表，这里也不补假数据。
 */

import type { TrialWorkflowNodeView } from '@/types/runtime'

/**
 * 节点上可展示的工具胶囊（形态对齐专家卡，但没有入参出参详情——快照里没有）。
 */
export interface WorkflowNodeToolPill {
  /** 稳定键：节点编码 / 名 */
  key: string
  /** 工具展示名（节点名；编码仅作缺省） */
  name: string
  /** running / done / error；与专家卡胶囊同三态 */
  state: 'running' | 'done' | 'error'
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
 * 从节点推出工具胶囊：仅 type=TOOL 时有；其它种类（含 AGENT）返回 null。
 *
 * <p>为什么 AGENT 不出：子 Agent 内部工具调用没有落到工作流卡片快照，
 * 用节点名冒充「已调用 xxx」会误导用户。有真实记法后再扩，不在这里猜。
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
 * @param pill   胶囊
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
  if (pill.state === 'running' || code === 'RUNNING') {
    return `正在调用${shown}…`
  }
  if (pill.state === 'error' || code === 'FAILED') {
    return `${shown}：调用失败`
  }
  if (code === 'PENDING' || code === 'WAITING_HUMAN' || code === 'WAITING_REPLY') {
    return `将调用${shown}`
  }
  if (code === 'SKIPPED' || code === 'CANCELLED') {
    return `已跳过${shown}`
  }
  return `已调用${shown}`
}
