/**
 * 工作流执行卡的纯展示推导。所属域：runtime（前端展示）。
 *
 * <p>职责：把投影好的 {@code TrialWorkflowView} 转成卡片上的文案 / 状态 / 时限 / 节点行。
 * 不碰 DOM、不持有响应式状态、不发请求；直播、收尾、回放读同一份快照，口径只此一处。
 *
 * <p>为什么单独成模块：对话壳层不再直接堆工作流细节；
 * 卡片组件、单测与回放都从这里取同一套判定，避免「卡片头说运行中、节点却说已完成」。
 */
import type {
  TrialChoiceView,
  TrialFileView,
  TrialWorkflowNodeView,
  TrialWorkflowStepView,
  TrialWorkflowView
} from '@/types/runtime'
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { workflowOutputState } from '@/components/runtime/workbench/workflowOutputView'
import {
  aggregateWorkflowToolPills,
  workflowNodeToolPill,
  type WorkflowNodeToolPill
} from '@/components/runtime/workbench/workbenchWorkflowNode'

/**
 * 节点过程折叠标题：与方案参与者卡 `thoughtToggleLabel()` 的「无耗时」那句完全一致。
 *
 * <p>工作流节点没有逐节点思考秒表，不补「(耗时 Xs)」；也不再带「(N 条)」——
 * 工具已经挪到折叠外的胶囊里，条数不再表示这一块的内容。
 */
export const WF_NODE_THINK_LABEL = '思考与工具执行过程'

/**
 * 工作流卡片：状态徽标是否已完成（成功后描边与头部才转绿）。
 *
 * @param workflow 卡片视图
 * @param closed   这一位的终态
 * @return true 表示运行成功
 */
export function wfCompleted(workflow: TrialWorkflowView, closed?: string): boolean {
  if (closed === 'done') {
    return true
  }
  return (workflow.status || '').toUpperCase() === 'SUCCESS'
}

/**
 * 工作流卡片：状态徽标文案。口径与后端 `WorkflowRunJson.statusText` 一致，
 * 未知状态按「运行中」处理，不伪造已完成。
 *
 * <p>运行中只回纯文案「运行中」：模板里已有旋转 ⏳，文案再带沙漏会叠成两个图标。
 *
 * @param workflow 卡片视图
 * @param closed   这一位的终态
 * @return 形如「运行中」「✅ 已完成」
 */
export function wfStatusText(workflow: TrialWorkflowView, closed?: string): string {
  // 这一位的终态先于快照状态：终态到了就停转，不等整轮、也不等节点快照改口
  if (closed === 'done') {
    return '✅ 已完成'
  }
  if (closed === 'failed') {
    return '❌ 运行失败'
  }
  if (closed === 'aborted') {
    return '⏹ 已取消'
  }
  const status = (workflow.status || '').toUpperCase()
  if (status === 'SUCCESS') {
    return '✅ 已完成'
  }
  if (status === 'FAILED') {
    return '❌ 运行失败'
  }
  if (status === 'CANCELLED') {
    return '⏹ 已取消'
  }
  if (status === 'PAUSED_HUMAN') {
    return '⏸ 等待人工处理'
  }
  if (status === 'WAITING_REPLY') {
    // 卡片头只表达「整段运行停住等输入」，具体问句在询问节点里
    return '⏸ 等待输入'
  }
  if (status === 'UNKNOWN') {
    // 回放兜底：历史事件被截断，只剩摘要能证明它已结束，如实说详情不可用
    return '已结束（详情不可用）'
  }
  return '运行中'
}

/**
 * 工作流卡片：状态徽标的样式类（运行中蓝、成功绿、失败红、等待橙）。
 *
 * @param workflow 卡片视图
 * @param closed   这一位的终态
 * @return 样式类名
 */
export function wfStatusClass(workflow: TrialWorkflowView, closed?: string): string {
  if (closed === 'done') {
    return 'is-success'
  }
  if (closed === 'failed') {
    return 'is-failed'
  }
  if (closed === 'aborted') {
    return 'is-cancelled'
  }
  const status = (workflow.status || '').toUpperCase()
  if (status === 'SUCCESS') {
    return 'is-success'
  }
  if (status === 'FAILED') {
    return 'is-failed'
  }
  if (status === 'CANCELLED') {
    return 'is-cancelled'
  }
  if (status === 'PAUSED_HUMAN' || status === 'WAITING_REPLY') {
    return 'is-waiting'
  }
  if (status === 'UNKNOWN') {
    // 详情不可用按「已收口」着色：不能再画成运行中，否则会一直转圈
    return 'is-cancelled'
  }
  return 'is-running'
}

/**
 * 毫秒时长 → 人话（分钟 / 分秒），绝不显示毫秒数。
 *
 * @param ms 毫秒；<=0 按 0 处理
 * @return 中文时长
 */
export function wfFormatMs(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  if (minutes <= 0) {
    return `${seconds} 秒`
  }
  return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${seconds} 秒`
}

/**
 * 运行时限的「现在」：等待中取进入等待的那一刻，否则取卡片时钟。
 *
 * <p>为什么不能直接用 Date.now()：等待用户回复 / 人工处理的时间不计入时限。
 * 后端给了 {@code waitingAt}，以它为基准算剩余，等待多久剩余都不变。
 *
 * @param workflow 卡片视图
 * @param now      卡片时钟（epoch ms）
 * @return epoch 毫秒
 */
function wfLimitNow(workflow: TrialWorkflowView, now: number): number {
  const waitingAt = workflow.limits?.waitingAt
  return typeof waitingAt === 'number' && waitingAt > 0 ? waitingAt : now
}

/**
 * 卡片头下方的时限行：整段上限 + 剩余，当前节点配了上限时再加一行节点时限。
 *
 * <p>只读后端给的绝对截止时刻现算剩余：刷新 / 离开再进 / 回放拿到的截止不变，
 * 剩余就不会重头计时。已收口或本轮已结束时只留「上限」，不再显示会停在半截的剩余。
 *
 * @param workflow 卡片视图
 * @param closed   这一位的终态；有值表示本轮已结束
 * @param now      卡片时钟（epoch ms）
 * @return 1~2 行文案；没有时限数据时为空表
 */
export function wfLimitLines(
  workflow: TrialWorkflowView,
  closed: string | undefined,
  now: number
): Array<{ kind: string; text: string }> {
  const limits = workflow.limits
  if (!limits || !limits.runDeadlineAt) {
    return []
  }
  const lines: Array<{ kind: string; text: string }> = []
  const status = (workflow.status || '').toUpperCase()
  const live = !closed && status === 'RUNNING'
  const suspended = !closed && (status === 'WAITING_REPLY' || status === 'PAUSED_HUMAN')
  if (limits.runLimitMs && limits.runLimitMs > 0) {
    let text = `整段上限 ${wfFormatMs(limits.runLimitMs)}`
    if (live || suspended) {
      text += ` · 剩余 ${wfFormatMs(limits.runDeadlineAt - wfLimitNow(workflow, now))}`
    }
    lines.push({ kind: 'run', text })
  }
  if (live && limits.nodeLimitMs && limits.nodeDeadlineAt && limits.nodeCode) {
    const name = limits.nodeName || limits.nodeCode
    lines.push({
      kind: 'node',
      text: `「${name}」上限 ${wfFormatMs(limits.nodeLimitMs)} · 剩余 ${wfFormatMs(
        limits.nodeDeadlineAt - wfLimitNow(workflow, now)
      )}`
    })
  }
  return lines
}

/**
 * 节点状态 → 时间轴样式：进行中（蓝点转圈）/ 已完成（绿勾）/ 失败（红叉）/ 未开始（灰点）。
 *
 * @param node   节点视图
 * @param closed 这一位的终态；整轮已收口时由投影补 aborted，用来停掉残留的进行态
 * @return done / active / failed / waiting / pending
 */
export function wfStepState(node: TrialWorkflowNodeView, closed?: string): string {
  const status = (node.status || '').toUpperCase()
  if (status === 'SUCCESS') {
    return 'done'
  }
  if (status === 'RUNNING' && closed !== 'aborted') {
    return 'active'
  }
  // 等待人工 / 等待回复：停在原地等外部输入，不按「执行中」转圈
  if (status === 'WAITING_HUMAN' || status === 'WAITING_REPLY') {
    return 'waiting'
  }
  if (status === 'FAILED') {
    return 'failed'
  }
  return 'pending'
}

/**
 * 节点下方的一句说明：优先用后端已脱敏的 note，缺失时按真实状态给中性文案。
 *
 * @param node   节点视图
 * @param closed 这一位的终态；中断时把「执行中」改成「已中断」
 * @return 非空说明
 */
export function wfStepNote(node: TrialWorkflowNodeView, closed?: string): string {
  const status = (node.status || '').toUpperCase()
  // 快照没来得及改口（仍是 RUNNING）但本轮已中断：说明句不能继续写「执行中」
  if (status === 'RUNNING' && closed === 'aborted') {
    return '已中断'
  }
  // 后端已把在途节点标成 CANCELLED：不再沿用「等待前置节点」，直接说明被中断
  if (status === 'CANCELLED') {
    return '已中断'
  }
  const note = (node.note || '').trim()
  if (note) {
    return note
  }
  const state = wfStepState(node, closed)
  if (state === 'done') {
    return '已完成'
  }
  if (state === 'active') {
    return '执行中'
  }
  if (state === 'waiting') {
    return status === 'WAITING_REPLY' ? '等待你的回复' : '等待人工处理'
  }
  if (state === 'failed') {
    return '执行失败'
  }
  return '等待前置节点'
}

/**
 * 产物区里的文件：工作流节点产出的真实文件（没有就是空表）。
 *
 * @param card 参与者卡片
 * @return 文件列表
 */
export function wfFilesOf(card: ParticipantCard): TrialFileView[] {
  return Array.isArray(card.files) ? card.files : []
}

/**
 * 产物区正文：终答文本与文件并列展示，不因文件清空文字。
 *
 * @param card 参与者卡片
 * @return 终答文本；没有时为空串
 */
export function wfAnswerOf(card: ParticipantCard): string {
  return (card.workflow?.answer || '').trim()
}

/**
 * 节点上的工具胶囊列表：0 或 1 条（仅 TOOL 节点）。
 *
 * <p>整轮已中断但节点快照仍写 RUNNING 时，胶囊必须停转，不能继续「正在调用」。
 *
 * @param node   工作流卡节点
 * @param closed 这一位的终态
 * @return 可直接 v-for 的数组
 */
export function wfNodeToolPills(node: TrialWorkflowNodeView, closed?: string) {
  const pill = workflowNodeToolPill(node)
  if (!pill) {
    return []
  }
  if (closed === 'aborted' && pill.state === 'running') {
    return [{ ...pill, state: 'done' as const }]
  }
  return [pill]
}

/**
 * 节点内工具调用（AGENT 节点 steps 里的 TOOL 步）→ 折叠外的胶囊。
 *
 * <p>同名工具合成一条并带真实调用次数（口径与方案参与者卡一致），详情保留每一次的结果；
 * 聚合规则在 {@link aggregateWorkflowToolPills}，这里只负责把节点 `steps` 喂进去。
 * 整轮已中断但步仍写 running 时同样停转，与节点级胶囊同一口径。
 *
 * @param node   工作流卡节点
 * @param closed 这一位的终态
 * @return 胶囊列表（按工具首次出现顺序）；没有工具步时为空表
 */
export function wfNodeStepToolPills(
  node: TrialWorkflowNodeView,
  closed?: string
): WorkflowNodeToolPill[] {
  return aggregateWorkflowToolPills(wfNodeSteps(node), closed)
}

/**
 * 工具胶囊标签用的节点状态：中断后把残留的 RUNNING 当作已取消，句子落到终态。
 *
 * @param node   工作流卡节点
 * @param closed 这一位的终态
 * @return 节点原始状态或替换后的 CANCELLED
 */
export function wfNodeLabelStatus(node: TrialWorkflowNodeView, closed?: string): string | undefined {
  if (closed === 'aborted' && (node.status || '').toUpperCase() === 'RUNNING') {
    return 'CANCELLED'
  }
  return node.status
}

/**
 * 卡片标题：优先工作流名称，其次编码；都没有时给中性兜底。
 *
 * @param card 参与者卡片
 * @return 标题
 */
export function wfCardTitle(card: ParticipantCard): string {
  return (card.workflow?.name || card.workflow?.code || '工作流执行').trim()
}

/** 节点自己的过程步；旧快照没有该字段时为空表。 */
export function wfNodeSteps(node: TrialWorkflowNodeView): TrialWorkflowStepView[] {
  return Array.isArray(node.steps) ? node.steps : []
}

/**
 * 节点自己的思考正文：只取 REASONING 步，按发生顺序拼接。
 *
 * <p>工具调用不进这里：它们改为折叠外的胶囊，不再和思考正文混在同一层，
 * 也不再把工具步算进折叠标题。
 *
 * @param node 工作流卡节点
 * @return 思考正文；没有推理时为空串
 */
export function wfNodeThinkingText(node: TrialWorkflowNodeView): string {
  const parts: string[] = []
  for (const step of wfNodeSteps(node)) {
    if (step.type !== 'REASONING') {
      continue
    }
    const text = (step.content || '').trim()
    if (text) {
      parts.push(text)
    }
  }
  return parts.join('\n').trim()
}

/** 节点产出的文件；旧快照没有该字段时为空表。 */
export function wfNodeFiles(node: TrialWorkflowNodeView): TrialFileView[] {
  return Array.isArray(node.files) ? node.files : []
}

/**
 * 节点里的用户回复：续跑后询问节点记下这次选择。
 *
 * <p>仍停在等待态时即使旧快照带了 reply 也不显示，避免同一步既出选项又出已答行。
 *
 * @param node 节点视图
 * @return 已答文案；没有或仍在等待时为空串
 */
export function wfNodeReply(node: TrialWorkflowNodeView): string {
  if ((node.status || '').toUpperCase() === 'WAITING_REPLY') {
    return ''
  }
  return (node.reply || '').trim()
}

/**
 * 节点内的可点选项：只认仍在等待回复的节点。
 *
 * <p>choice 是卡级字段（协议沿用），但展示位置必须回到询问节点内部；
 * 回复后 choice 由后端清空，选项自然消失，不会留在卡底。
 *
 * @param node 节点视图
 * @param card 参与者卡片
 * @return 选项；不是等待节点或结构不合格时为 undefined
 */
export function wfNodeChoice(
  node: TrialWorkflowNodeView,
  card: ParticipantCard
): TrialChoiceView | undefined {
  const status = (node.status || '').toUpperCase()
  const choice = card.workflow?.choice
  if (status !== 'WAITING_REPLY' || !choice || choice.options.length < 2) {
    return undefined
  }
  return choice
}

/**
 * 等待节点里的问句：优先用结构化选项的问句，其次用节点产出里的问句。
 *
 * @param node 节点视图
 * @param card 参与者卡片
 * @return 问句；都没有时给「请选择」
 */
export function wfNodeQuestion(node: TrialWorkflowNodeView, card: ParticipantCard): string {
  const fromChoice = (wfNodeChoice(node, card)?.question || '').trim()
  if (fromChoice) {
    return fromChoice
  }
  return (node.output || '').trim() || '请选择'
}

/**
 * 这个节点是不是「问用户」的节点：正在等回复，或已经收到过回复。
 *
 * @param node 节点视图
 * @return true 表示询问类节点
 */
export function wfNodeAsksUser(node: TrialWorkflowNodeView): boolean {
  if ((node.status || '').toUpperCase() === 'WAITING_REPLY') {
    return true
  }
  return !!wfNodeReply(node)
}

/**
 * 节点正文：询问类节点只留「您的选择」那一行。
 *
 * @param node 节点视图
 * @param card 参与者卡片
 * @return 可渲染正文；没有为空串
 */
export function wfNodeOutputText(node: TrialWorkflowNodeView, card: ParticipantCard): string {
  if (wfNodeChoice(node, card) || wfNodeAsksUser(node)) {
    return ''
  }
  return (node.output || '').trim()
}

/**
 * 节点是否有需要在过程区展示的内容：思考、折叠外的工具胶囊、或四行正文。
 *
 * <p>工具也纳入判定：只有工具、没有思考和正文的节点仍要出胶囊，
 * 不能因为「没有思考」把整块过程区一起藏掉。
 *
 * @param node 节点视图
 * @param card 参与者卡片（取节点正文与终态）
 * @return true 表示画过程区
 */
export function wfNodeHasBody(node: TrialWorkflowNodeView, card: ParticipantCard): boolean {
  return (
    wfNodeHasThinking(node) ||
    wfNodeToolPills(node, card.closed).length > 0 ||
    wfNodeStepToolPills(node, card.closed).length > 0 ||
    !!wfNodeOutputText(node, card)
  )
}

/**
 * 节点是否有可折叠的思考。
 *
 * <p>只认推理正文：工具单独做折叠外的胶囊，只有工具的节点不再出一个空折叠。
 *
 * @param node 节点视图
 * @return true 表示画折叠标题行
 */
export function wfNodeHasThinking(node: TrialWorkflowNodeView): boolean {
  return wfNodeThinkingText(node).length > 0
}

/** 节点是否正在运行：运行中的四行窗口跟最新内容走。整卡已收口时不再当运行中。 */
export function wfNodeRunning(node: TrialWorkflowNodeView, card: ParticipantCard): boolean {
  if (card.closed === 'done' || card.closed === 'failed' || card.closed === 'aborted') {
    return false
  }
  return (node.status || '').toUpperCase() === 'RUNNING'
}

/**
 * 卡片上是否已经有任一节点携带过程 / 正文 / 文件 / 询问表单；有则不画卡级旧结果区。
 *
 * @param card 参与者卡片
 * @return true 表示已有节点内细节
 */
export function wfHasNodeDetail(card: ParticipantCard): boolean {
  return (card.workflow?.nodes || []).some(
    (node) =>
      wfNodeSteps(node).length > 0 ||
      !!wfNodeOutputText(node, card) ||
      wfNodeFiles(node).length > 0 ||
      !!wfNodeReply(node) ||
      !!wfNodeChoice(node, card)
  )
}

/**
 * 结果区是否出现：有正文或文件就出现；所属方案还没结束时也出现，只是正文限高。
 *
 * @param card 参与者卡片
 * @param hold 所属方案是否还没全部结束
 * @return true 表示画出结果区
 */
export function wfOutputVisible(card: ParticipantCard, hold: boolean): boolean {
  return (
    workflowOutputState(
      card.workflow,
      wfAnswerOf(card),
      wfFilesOf(card).length,
      false,
      hold
    ) !== 'hidden'
  )
}

/**
 * 旧卡片兜底：只有整张卡没有任何节点内细节时才把卡级正文 / 文件画在下面。
 *
 * @param card 参与者卡片
 * @param hold 所属方案是否还没全部结束
 * @return true 表示画卡级结果区
 */
export function wfLegacyResultVisible(card: ParticipantCard, hold: boolean): boolean {
  if (wfHasNodeDetail(card)) {
    return false
  }
  return wfOutputVisible(card, hold)
}

/** 旧卡片兜底：没有等待节点承载 choice 时，才把选项留在卡底。 */
export function wfLegacyChoice(card: ParticipantCard): boolean {
  const choice = card.workflow?.choice
  if (!choice || choice.options.length < 2) {
    return false
  }
  return !(card.workflow?.nodes || []).some(
    (node) => (node.status || '').toUpperCase() === 'WAITING_REPLY'
  )
}

/**
 * 结果区副文案：有真实文件时补一句交付物说明；纯文本正文只靠「结果」小标题。
 *
 * <p>运行中不补「执行完毕」：那时文件是中间产物，说「完毕」是假话。
 *
 * @param card 参与者卡片
 * @return 一行提示；无文件或仍在运行时为空串
 */
export function wfOutputTip(card: ParticipantCard): string {
  if (!wfFilesOf(card).length) {
    return ''
  }
  const status = (card.workflow?.status || '').toUpperCase()
  // 收尾态才说「执行完毕」：运行中出现的文件属于中间产物
  return status === 'RUNNING' || status === 'CREATED' ? '流程产出文件：' : '流程执行完毕，为您生成交付物：'
}

/**
 * 工作流结果区标题。完成后用演示稿那句，进行中不写成「已完毕」。
 *
 * @param card 参与者卡片
 * @return 标题
 */
export function wfResultTitle(card: ParticipantCard): string {
  const done = card.workflow ? wfCompleted(card.workflow, card.closed) : false
  return done ? '流程执行完毕，结果如下：' : '执行结果'
}

export { wfNodeKey, wfNodeRoundLabel } from './workflowNodeRound'
