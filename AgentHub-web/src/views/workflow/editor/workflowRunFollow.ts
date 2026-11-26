/**
 * 调试跟随的纯逻辑：认「当前步骤」是哪个节点、算左侧可见矩形、反算视口平移量。
 * 所属域：workflow 的前端画布层（`views/workflow/editor`）。
 *
 * <p>这里刻意不依赖 Vue / DOM：目标选择与几何计算都能用 `node:test` 直接覆盖，
 * 真正读写 VueFlow 视口的副作用在 `useWorkflowRunFollow.ts`。
 *
 * <p>跟随只平移、不改缩放，也不改节点坐标；所有时间都只用于比先后，不做展示。
 */
import type { WorkflowNodeRunRow, WorkflowRunDetail } from '@/types/workflow'

/** 边界标记不是用户看到的「一步」，跟随要跳过 */
const SKIP_NODE_TYPES = new Set(['START', 'END'])

/** 停在等外部输入的两个节点态：等待人工 / 等待回复 */
const WAITING_STATUS = new Set(['WAITING_HUMAN', 'WAITING_REPLY'])

/** run 已结束：成功 / 失败 / 取消 */
const TERMINAL_RUN_STATUS = new Set(['SUCCESS', 'FAILED', 'CANCELLED'])

/** 顶中编辑按钮组占用的高度：让出后节点不会压在图标上 */
export const FOLLOW_RESERVED_TOP = 64

/** 底部缩放 / 调试胶囊占用的高度 */
export const FOLLOW_RESERVED_BOTTOM = 64

/** 可见区左右再让出一点，节点贴边时不至于压着调试框或画布边框 */
export const FOLLOW_SIDE_GAP = 12

/** 可用宽度低于这个值就不跟随，避免把节点硬塞到调试框底下 */
export const FOLLOW_MIN_VISIBLE_WIDTH = 300

/** 位移小于这个像素量说明本来就在中心附近，不必再动一次 */
export const FOLLOW_MIN_MOVE = 16

/** 步骤切换的平移时长；只改 x / y，缩放保持用户当时的值 */
export const FOLLOW_MOVE_DURATION = 280

/** 悬停 / 点「继续跟随」时立即对准的时长 */
export const FOLLOW_RESUME_DURATION = 200

/** 跟随目标：同一节点不同轮次算两次步骤切换 */
export interface FollowTarget {
  /** 节点编码，与画布 data.code 对应 */
  code: string
  /** 第几轮；缺省按 1 */
  attempt: number
}

/** 相对画布容器左上角的矩形 */
export interface FollowRect {
  left: number
  top: number
  right: number
  bottom: number
}

/** 左侧可见区域（画布容器坐标系） */
export interface VisibleFollowBox extends FollowRect {
  width: number
  height: number
}

/**
 * 把后端时间串换成可比较的毫秒数。
 *
 * <p>后端固定写 `yyyy-MM-dd HH:mm:ss.SSS`，部分浏览器不认空格分隔，统一换成 ISO 的 `T`。
 * 解析失败按 0 处理：只影响先后排序，不让跟随因为脏数据抛错。
 *
 * @param raw 时间串；可空
 * @return 毫秒数；空或非法为 0
 */
export function followTimeOf(raw?: string | null): number {
  if (!raw) {
    return 0
  }
  const text = raw.includes('T') ? raw : raw.replace(' ', 'T')
  const value = Date.parse(text)
  return Number.isNaN(value) ? 0 : value
}

/**
 * 归一化状态串，避免大小写差异把 RUNNING 认丢。
 *
 * @param raw 状态
 * @return 大写去空白；空为空串
 */
function statusOf(raw?: string | null): string {
  return String(raw ?? '').trim().toUpperCase()
}

/**
 * 取节点行的轮次；库里的旧行可能没有，按第 1 轮。
 *
 * @param row 节点运行态行
 * @return 轮次（>=1）
 */
export function followAttemptOf(row: WorkflowNodeRunRow): number {
  const attempt = Number(row.attempt)
  return Number.isFinite(attempt) && attempt >= 1 ? attempt : 1
}

/**
 * 运行态行是否属于跟随范围（剔除 START / END 边界节点；编码为空的脏行也跳过）。
 *
 * @param row 节点运行态行
 * @return true 表示是可跟随的业务节点
 */
function isFollowableRow(row: WorkflowNodeRunRow): boolean {
  if (SKIP_NODE_TYPES.has(statusOf(row.nodeType))) {
    return false
  }
  return String(row.nodeCode ?? '').trim() !== ''
}

/**
 * 去重键：同一节点同一轮只移动一次，思考 / 工具 / 流式文本刷新不会反复跳。
 *
 * @param runId run 主键
 * @param target 跟随目标
 * @return 去重键
 */
export function followKeyOf(runId: number, target: FollowTarget): string {
  return `${runId}:${target.code}:${target.attempt}`
}

/**
 * 判断 run 是否已结束。
 *
 * @param status run 状态
 * @return true 表示 SUCCESS / FAILED / CANCELLED
 */
export function isTerminalRunStatus(status?: string | null): boolean {
  return TERMINAL_RUN_STATUS.has(statusOf(status))
}

/**
 * 取开始时间最早的一行；同刻按行 id 升序，保证每拍结果稳定不来回跳。
 *
 * @param rows 候选行
 * @return 最早的一行
 */
function earliestByStart(rows: WorkflowNodeRunRow[]): WorkflowNodeRunRow {
  return [...rows].sort((a, b) => {
    const diff = followTimeOf(a.startTime) - followTimeOf(b.startTime)
    return diff !== 0 ? diff : Number(a.id) - Number(b.id)
  })[0]
}

/**
 * 取开始时间最晚的一行；同刻按行 id 降序（后建的行更新）。
 *
 * @param rows 候选行
 * @return 最晚的一行
 */
function latestByStart(rows: WorkflowNodeRunRow[]): WorkflowNodeRunRow {
  return [...rows].sort((a, b) => {
    const diff = followTimeOf(b.startTime) - followTimeOf(a.startTime)
    return diff !== 0 ? diff : Number(b.id) - Number(a.id)
  })[0]
}

/**
 * 取结束时间最晚的一行；没有结束时间时退回开始时间。
 *
 * @param rows 候选行
 * @return 最后结束的一行
 */
function latestByEnd(rows: WorkflowNodeRunRow[]): WorkflowNodeRunRow {
  return [...rows].sort((a, b) => {
    const left = followTimeOf(a.endTime) || followTimeOf(a.startTime)
    const right = followTimeOf(b.endTime) || followTimeOf(b.startTime)
    const diff = right - left
    return diff !== 0 ? diff : Number(b.id) - Number(a.id)
  })[0]
}

/**
 * 选「当前步骤」。
 *
 * <p>优先级：
 * <ol>
 *   <li>唯一 / 多个 RUNNING：取开始最早的那个；并行的其它节点不抢，等它走完再切；</li>
 *   <li>等待态（WAITING_HUMAN / WAITING_REPLY）：多行时取最晚开始的；</li>
 *   <li>整次 run 已 FAILED 且没有 RUNNING：跟失败节点（补一次定位）；</li>
 *   <li>步骤间隙 / 成功 / 取消：没有目标，调用方保持原视口不动。</li>
 * </ol>
 *
 * @param rows run 详情里的节点运行态
 * @param runStatus 整次 run 的状态
 * @return 跟随目标；没有时为 null
 */
export function pickFollowTarget(
  rows: WorkflowNodeRunRow[] | undefined | null,
  runStatus?: string | null
): FollowTarget | null {
  const list = (rows ?? []).filter(isFollowableRow)
  const running = list.filter((row) => statusOf(row.status) === 'RUNNING')
  if (running.length) {
    const row = earliestByStart(running)
    return { code: String(row.nodeCode ?? ''), attempt: followAttemptOf(row) }
  }
  const waiting = list.filter((row) => WAITING_STATUS.has(statusOf(row.status)))
  if (waiting.length) {
    const row = latestByStart(waiting)
    return { code: String(row.nodeCode ?? ''), attempt: followAttemptOf(row) }
  }
  if (statusOf(runStatus) === 'FAILED') {
    const failed = list.filter((row) => statusOf(row.status) === 'FAILED')
    if (failed.length) {
      const row = latestByEnd(failed)
      return { code: String(row.nodeCode ?? ''), attempt: followAttemptOf(row) }
    }
  }
  return null
}

/**
 * 回看已结束的历史记录时，没有「当前步骤」，只能定位到最后结束的那个业务节点。
 *
 * <p>一次性定位用：之后的详情不再移动视口。
 *
 * @param rows run 详情里的节点运行态
 * @return 最后一次结束的目标；没有时为 null
 */
export function pickLastEndedTarget(
  rows: WorkflowNodeRunRow[] | undefined | null
): FollowTarget | null {
  const ended = (rows ?? [])
    .filter(isFollowableRow)
    .filter((row) => statusOf(row.status) !== 'SKIPPED')
    .filter((row) => Boolean(row.endTime) || Boolean(row.startTime))
  if (!ended.length) {
    return null
  }
  const row = latestByEnd(ended)
  return { code: String(row.nodeCode ?? ''), attempt: followAttemptOf(row) }
}

/**
 * 取 run 详情里的「打开历史记录」判断依据：首帧就是终态才算回看。
 *
 * @param detail run 详情
 * @return true 表示这条记录已经结束
 */
export function isTerminalDetail(detail: WorkflowRunDetail | null | undefined): boolean {
  return Boolean(detail) && isTerminalRunStatus(detail?.status)
}

/**
 * 算画布左侧的可见矩形（相对画布容器）。
 *
 * <p>只扣贴着左右边的遮挡物（右侧调试框这类竖长面板）；上下不按遮挡裁，
 * 而是固定让出顶中图标组与底部缩放胶囊的高度。遮挡物若横向盖满整块画布，
 * 返回零矩形，调用方据此放弃跟随，不把节点挪到调试框底下。
 *
 * @param width 画布容器宽（px）
 * @param height 画布容器高（px）
 * @param obstructions 遮挡物矩形（同一画布容器坐标系）；可空
 * @return 可见矩形与宽高
 */
export function visibleFollowBox(
  width: number,
  height: number,
  obstructions?: FollowRect[] | null
): VisibleFollowBox {
  const covered = { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 }
  let left = 0
  let right = Math.max(0, width)
  for (const rect of obstructions ?? []) {
    // 与画布不相交的直接忽略
    if (rect.right <= 0 || rect.left >= width || rect.bottom <= 0 || rect.top >= height) {
      continue
    }
    // 横向盖满（如调试框比画布还宽）：左侧已无可见区，索性放弃跟随
    if (rect.left <= 0 && rect.right >= width) {
      return covered
    }
    // 贴右边缘的是右侧面板（调试框），从右边扣掉；贴左边缘的从左边扣掉
    if (rect.left > 0 && rect.right >= width) {
      right = Math.min(right, rect.left)
    } else if (rect.left <= 0 && rect.right < width) {
      left = Math.max(left, rect.right)
    }
  }
  const top = FOLLOW_RESERVED_TOP
  const bottom = Math.max(0, height - FOLLOW_RESERVED_BOTTOM)
  // 左右再收一点间隙；收完不能反向（极窄画布时退化为零宽，交给调用方跳过）
  const innerLeft = Math.min(left + FOLLOW_SIDE_GAP, right)
  const innerRight = Math.max(right - FOLLOW_SIDE_GAP, innerLeft)
  return {
    left: innerLeft,
    top,
    right: innerRight,
    bottom,
    width: Math.max(0, innerRight - innerLeft),
    height: Math.max(0, bottom - top)
  }
}

/**
 * 反算视口平移量：让目标节点中心落到可见矩形中心，缩放沿用当前值。
 *
 * @param box 可见矩形
 * @param nodeX 节点中心在流坐标系里的 x
 * @param nodeY 节点中心在流坐标系里的 y
 * @param zoom 当前缩放
 * @return 新的视口 x / y（zoom 不变）
 */
export function followViewportOf(
  box: VisibleFollowBox,
  nodeX: number,
  nodeY: number,
  zoom: number
): { x: number; y: number } {
  const centerX = (box.left + box.right) / 2
  const centerY = (box.top + box.bottom) / 2
  return { x: centerX - nodeX * zoom, y: centerY - nodeY * zoom }
}
