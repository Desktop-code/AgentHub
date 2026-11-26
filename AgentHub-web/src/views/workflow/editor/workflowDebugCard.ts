/**
 * 调试框把后端卡片载荷交给首页同一张卡。所属域：workflow 的前端。
 *
 * <p>这里只做类型收口和入参合并，不根据 nodeRuns 推算节点、问句或选项。
 */
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'
import type { ChatTurn, TrialWorkflowView } from '@/types/runtime'
import type { WorkflowRunDetail, WorkflowRunLiveCard } from '@/types/workflow'

/** 过程区还没开始时的空态，不要写成「等待执行」 */
export const DEBUG_EMPTY_HINT = '输入要调试的内容，开始后过程会出现在这里'

/** 调试页和记录页 */
export type DebugPane = 'debug' | 'records'

/** 这些状态还在变，需要继续拉详情 */
const LIVE_STATUS = new Set(['CREATED', 'RUNNING'])

/** 已经结束，下一句应新开一条试跑 */
const TERMINAL_STATUS = new Set(['SUCCESS', 'FAILED', 'CANCELLED'])

/** 可以取消的状态，与后端 cancel 允许的集合一致 */
const CANCEL_STATUS = new Set(['CREATED', 'RUNNING', 'PAUSED_HUMAN', 'WAITING_REPLY'])

export type DebugSendMode = 'start' | 'reply' | 'blocked'

/**
 * 详情上的卡片载荷是否就是首页那份视图。
 *
 * <p>缺状态或缺节点数组时返回空，调用方保持空态，不去读 nodeRuns 补节点。
 *
 * @param value 详情里的 card；形状不对时为任意 JSON
 * @return 同一引用的视图；认不出时为 null
 */
export function asWorkflowView(value: unknown): TrialWorkflowView | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  if (typeof record.status !== 'string' || !Array.isArray(record.nodes)) {
    return null
  }
  return value as TrialWorkflowView
}

/**
 * 把视图原样挂到参与者卡片上，供 {@code WorkflowRunCard} 渲染。
 *
 * @param view 后端现算的卡片
 * @param runId 当前试跑主键，只用于渲染 key
 * @return 助手轮占位 + 工作流块；节点列表就是 view.nodes
 */
export function debugParticipant(view: TrialWorkflowView, runId: number): {
  turn: ChatTurn
  card: ParticipantCard
} {
  const status = (view.status || '').toUpperCase()
  const closed = status === 'SUCCESS' ? 'done' : status === 'FAILED' ? 'failed' : status === 'CANCELLED' ? 'aborted' : undefined
  const card: ParticipantCard = {
    key: `wf-debug-${runId}`,
    kind: 'actor',
    title: view.name || view.code || '工作流',
    participantKind: 'workflow',
    lines: [],
    steps: [],
    answer: '',
    errors: [],
    files: [],
    pending: LIVE_STATUS.has(status),
    closed,
    workflow: view
  }
  const turn: ChatTurn = {
    key: `wf-debug-turn-${runId}`,
    role: 'assistant',
    text: '',
    blocks: [],
    phase: '',
    status: card.pending ? 'streaming' : closed === 'failed' ? 'error' : 'done',
    startTime: 0
  }
  return { turn, card }
}

/**
 * 这一条还在跑，或者续跑请求还没回来，就要继续拉详情。
 *
 * @param status run 状态；可空
 * @param busy 回复 / 人工处理的请求还在飞
 * @return true 表示要轮询
 */
export function shouldPoll(status: string | null | undefined, busy: boolean): boolean {
  const normalized = (status || '').toUpperCase()
  if (LIVE_STATUS.has(normalized)) {
    return true
  }
  return busy && !TERMINAL_STATUS.has(normalized)
}

/**
 * 这条 run 是否还在运行中（CREATED / RUNNING）。
 *
 * <p>调试框用它决定是否优先按 run 订阅直播；已结束的历史记录不再订直播。
 *
 * @param status run 状态；可空
 * @return true 表示直播通道仍可用
 */
export function isLiveRunStatus(status: string | null | undefined): boolean {
  return LIVE_STATUS.has((status || '').toUpperCase())
}

/**
 * 把一帧按 run 的直播快照并到当前详情上。
 *
 * <p>卡片与节点行都以直播为准：只有这一帧没带 card / nodeRuns 时才保留上一层，
 * 绝不用较旧的卡把已经流出来的思考、工具和正文清掉。入参、人工待办、产出等
 * 详情字段不在直播快照里，继续沿用当前值。
 *
 * @param base 当前详情（来自开始 / 续跑 / 上次直播帧）
 * @param live 直播快照；调用方需先确认 live=true
 * @return 合并后的详情
 */
export function mergeLiveRun(base: WorkflowRunDetail, live: WorkflowRunLiveCard): WorkflowRunDetail {
  const rows = live.nodeRuns && live.nodeRuns.length ? live.nodeRuns : base.nodeRuns
  return {
    ...base,
    status: live.status || base.status,
    card: live.card ?? base.card,
    nodeRuns: rows,
    errorSummary: live.errorSummary ?? base.errorSummary
  }
}

/**
 * 收口时把直播卡的内部状态对齐到 run 的终态。
 *
 * <p>取消由请求线程直接落库，取消响应到达时卡片还是上一帧的 RUNNING；
 * 这里只换状态，保留已经流出的思考、工具与节点结果，避免整段替换或闪回。
 * 卡片本身已经是终态、run 还没结束、或载荷认不出时原样返回。
 *
 * @param card 当前卡片载荷（可能是上一帧的直播卡）
 * @param status run 的当前状态
 * @return 对齐后的卡片载荷；不需要对齐时是入参本身
 */
export function alignClosedCard(card: unknown, status: string | null | undefined): unknown {
  const normalized = (status || '').toUpperCase()
  if (!TERMINAL_STATUS.has(normalized)) {
    return card
  }
  const view = asWorkflowView(card)
  if (!view || TERMINAL_STATUS.has((view.status || '').toUpperCase())) {
    return card
  }
  return { ...view, status: normalized }
}

/**
 * 底部输入该新开一条、续上这一条，还是先别发。
 *
 * @param status 当前 run 状态；还没有 run 时为空
 * @param busy 已有请求在飞（开始、回复、取消、人工）
 * @return start 新开试跑；reply 续同一条；blocked 输入框不可发
 */
export function debugSendMode(status: string | null | undefined, busy: boolean): DebugSendMode {
  if (busy) {
    return 'blocked'
  }
  const normalized = (status || '').toUpperCase()
  if (!normalized || TERMINAL_STATUS.has(normalized)) {
    return 'start'
  }
  if (normalized === 'WAITING_REPLY') {
    return 'reply'
  }
  return 'blocked'
}

/**
 * 是否允许取消。终态不再提供取消。
 *
 * @param status run 状态
 * @return true 表示可以点取消
 */
export function canCancelStatus(status: string | null | undefined): boolean {
  return CANCEL_STATUS.has((status || '').toUpperCase())
}

/** 调试输入行右侧唯一主按钮的文案 */
export type DebugActionLabel = '运行' | '发送' | '停止'

/**
 * 调试输入行唯一主按钮上写什么。
 *
 * <p>还没开始是「运行」；停在等待回复、要接着说时是「发送」；
 * 运行中同一个按钮变「停止」，输入行不再另放「取消」这第二个按钮。
 *
 * @param mode 当前发送模式（start / reply / blocked）
 * @param canStop 当前这条是否还能停
 * @return 按钮文案
 */
export function debugActionLabel(mode: DebugSendMode, canStop: boolean): DebugActionLabel {
  if (canStop) {
    return '停止'
  }
  return mode === 'reply' ? '发送' : '运行'
}

/**
 * 卡片是否还要按「进行中」收着正文。终态才放开。
 *
 * @param status run 状态
 * @return true 表示未结束
 */
export function debugHold(status: string | null | undefined): boolean {
  return !TERMINAL_STATUS.has((status || '').toUpperCase())
}

/**
 * 不是错误的状态摘要：等待回复、用户取消都在 error_summary 里留了一句，
 * 但它们不是失败，不能再画成调试框顶部的错误条。
 */
const NON_ERROR_SUMMARY = new Set(['等待用户回复', '用户取消运行'])

/**
 * 调试框顶部悬浮错误条要写的这一句。
 *
 * <p>只在 run 已失败时出条：节点失败 / 超限的完整原因由 errorSummary 给出，
 * 例如「节点「play_check」失败：这一步运行超过上限，已停止」。
 * 等待回复、人工等待、用户取消都不出条——节点上的等待态与标题右侧的终态已经说清，
 * 不再重复一条红色底栏。
 *
 * @param status run 状态
 * @param errorSummary run 的错误摘要
 * @return 要展示的句子；不该出条时为空串
 */
export function debugRunErrorNotice(
  status: string | null | undefined,
  errorSummary: string | null | undefined
): string {
  // 只有 FAILED 是错误终态；RUNNING / WAITING_REPLY / PAUSED_HUMAN / CANCELLED 都不算
  if ((status || '').toUpperCase() !== 'FAILED') {
    return ''
  }
  const text = (errorSummary || '').trim()
  if (!text || NON_ERROR_SUMMARY.has(text)) {
    return ''
  }
  return text
}

/**
 * 从 run 入参里取出当时的问句，用作过程区上方的用户句。
 *
 * <p>续跑回复不另起一句。入参不是对象、或没有 query 时为空。
 *
 * @param input 详情里的 input
 * @return 问句；没有时为空串
 */
export function queryOf(input: unknown): string {
  let value = input
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value) as unknown
    } catch {
      return ''
    }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return ''
  }
  const query = (value as Record<string, unknown>).query
  return typeof query === 'string' ? query : ''
}

export type DebugInputMerge =
  | { ok: true; input: Record<string, unknown> }
  | { ok: false; error: string }

/**
 * 把输入框这句话当成调试入参的 query。空句子不发。
 *
 * @param query 输入框原文
 * @return 可提交的对象，或一句给输入区的错误
 */
export function debugQueryInput(query: string): DebugInputMerge {
  const trimmed = query.trim()
  if (!trimmed) {
    return { ok: false, error: '请输入要调试的内容' }
  }
  return { ok: true, input: { query: trimmed } }
}

/**
 * 调试记录一行上的版本标记。
 * <p>版本号文本与「未发布」分开：未发布调试也要先把「当时默认版本」写出来，再挂未发布标记。
 */
export interface DebugVersionTag {
  /** 版本号文本，如 v6；从未发布过且没有快照时为空串（不编版本号） */
  versionText: string
  /** 是否再标「未发布」；按当前草稿调试为 true */
  unpublished: boolean
}

/**
 * 调试记录上的版本标签。
 *
 * <p>已发布记录只认 releaseId / releaseVersion，不会带「未发布」。
 * 未发布记录用开始调试时记下的默认发布版本快照，不按此刻的 default_release_id 反推，
 * 以免后来又发了新版、旧记录跟着变成新默认版本；当时从未发布过就只标「未发布」。
 *
 * @param releaseId 发布版本 id；草稿调试为空
 * @param releaseVersion 发布版本号
 * @param draftBaseReleaseVersion 草稿调试开始时的默认发布版本快照；可空
 * @return 版本文本与是否标未发布
 */
export function debugVersionTag(
  releaseId: number | null | undefined,
  releaseVersion: number | null | undefined,
  draftBaseReleaseVersion?: number | null
): DebugVersionTag {
  if (releaseId != null && releaseVersion != null) {
    return { versionText: `v${releaseVersion}`, unpublished: false }
  }
  if (draftBaseReleaseVersion != null && draftBaseReleaseVersion > 0) {
    return { versionText: `v${draftBaseReleaseVersion}`, unpublished: true }
  }
  return { versionText: '', unpublished: true }
}

/**
 * 调试记录能不能拿来切换线上版本。
 * 未发布的记录没有 release，不能切；切换入口只认已发布版本列表。
 *
 * @param releaseId 这条记录绑的发布版本；空表示未发布
 * @return 有发布版本时为 true
 */
export function canSwitchDebugRecord(releaseId: number | null | undefined): boolean {
  return releaseId != null
}
