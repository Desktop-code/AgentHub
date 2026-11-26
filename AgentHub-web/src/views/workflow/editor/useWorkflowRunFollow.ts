/**
 * 调试运行时把当前执行节点对到画布左侧可见区域中心的视口跟随。
 * 所属域：workflow 的前端画布层（`views/workflow/editor`）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>目标选择、去重、可见矩形与平移量全部复用 `workflowRunFollow.ts` 的纯函数；</li>
 *   <li>本文件只做 Vue 侧状态与 VueFlow 视口副作用：`setViewport` 只改 x / y，缩放沿用当前值；</li>
 *   <li>不碰节点坐标、不触发 fitView、不写草稿脏标记（视口不参与签名）。</li>
 * </ul>
 *
 * <p>与手动的边界：用户拖动 / 缩放 / 点缩放按钮后由宿主调用 {@link pause}，
 * 本次调试内不再自动移动；只有用户点「继续跟随」才 {@link resume} 回来。
 */
import { computed, ref } from 'vue'
import type { WorkflowRunDetail } from '@/types/workflow'
import {
  FOLLOW_MIN_MOVE,
  FOLLOW_MIN_VISIBLE_WIDTH,
  FOLLOW_MOVE_DURATION,
  FOLLOW_RESUME_DURATION,
  followKeyOf,
  followViewportOf,
  isTerminalDetail,
  isTerminalRunStatus,
  pickFollowTarget,
  pickLastEndedTarget,
  visibleFollowBox,
  type FollowRect,
  type FollowTarget
} from './workflowRunFollow'

/** 画布节点上跟随要用的最小结构；不引 VueFlow 类型，避免页面与 store 类型渗透 */
export interface FollowCanvasNode {
  position?: { x: number; y: number }
  /** 绝对位置（含父节点偏移）；没有父节点时与 position 相同 */
  computedPosition?: { x: number; y: number }
  dimensions?: { width: number; height: number }
}

/** 视口变换：只改 x / y 与沿用 zoom */
export interface FollowViewportTransform {
  x: number
  y: number
  zoom: number
}

/** 跟随需要的宿主能力；全部惰性读取，不在构造时碰 DOM 或 store */
export interface WorkflowRunFollowOptions {
  /** 调试框是否打开：关掉后不再接收任何移动 */
  isOpen: () => boolean
  /** 画布容器；坐标原点与尺寸来源 */
  getCanvasHost: () => HTMLElement | null
  /** 遮挡物矩形（相对窗口）；通常只有右侧调试框 */
  getObstructionRects: () => FollowRect[]
  /** 按节点 code 找画布节点 */
  findNodeByCode: (code: string) => FollowCanvasNode | null | undefined
  /** 当前视口（含读者当前缩放） */
  getViewport: () => FollowViewportTransform
  /** 平移视口；VueFlow 的 setViewport */
  setViewport: (
    transform: FollowViewportTransform,
    options?: { duration?: number }
  ) => unknown
}

/** 节点尺寸补量的重试间隔：只补一次，量不到就放弃这一拍 */
const MEASURE_RETRY_MS = 120

/**
 * 跟随控制器。
 *
 * @param options 宿主提供的画布 / 视口能力
 * @return 供页面使用的状态与动作
 */
export function useWorkflowRunFollow(options: WorkflowRunFollowOptions) {
  /** 是否已暂停（用户手动改过视野） */
  const paused = ref(false)
  /** 当前是否有可跟随 / 可回看的目标；底部「跟随」按钮只在这时为真才出现 */
  const hasTarget = ref(false)
  /** 跟随中：有目标且未被用户暂停；专注模式据此决定让不让位 */
  const following = computed(() => hasTarget.value && !paused.value)

  /** 已绑定过的 run；没绑定前用户拖画布不算暂停，起跑后仍能开始跟随 */
  let bound = false
  /** 最近一次采纳的 run 主键，用来判断「换了一条」 */
  let lastRunId: number | null = null
  /** 当前目标与去重键：键不变就不移动 */
  let trackedTarget: FollowTarget | null = null
  let lastKey = ''
  /** 已做过一次性定位的历史 run：同一条不再移动 */
  let oneShotRunId: number | null = null
  /** 尺寸补量的定时器，避免每拍排队 */
  let retryTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 采纳一份 run 详情，决定是否移动。
   *
   * <p>与画布高亮吃同一份 `nodeRuns`，不另算一套进度。
   *
   * @param detail 最新详情；null 表示这条已清掉
   */
  function onDetail(detail: WorkflowRunDetail | null): void {
    if (!detail || !options.isOpen()) {
      stop()
      return
    }
    const runId = Number(detail.id)
    const isNewRun = runId !== lastRunId
    // 换了一条：重新计 key、解除暂停；新一条的第一拍允许立刻跟随
    if (isNewRun) {
      lastRunId = runId
      bound = true
      paused.value = false
      trackedTarget = null
      lastKey = ''
      oneShotRunId = null
    }

    // 打开一条已结束的历史记录：没有「当前步骤」，只做一次性定位，之后不再跟
    const terminalDetail = isTerminalDetail(detail)
    if (terminalDetail && oneShotRunId === runId) {
      // 同一条历史已经定位过：重复收到也不再移，且不把跟随入口收掉
      return
    }
    if (isNewRun && terminalDetail) {
      clearRetry()
      oneShotRunId = runId
      const target = pickLastEndedTarget(detail.nodeRuns)
      if (target) {
        trackedTarget = target
        hasTarget.value = true
        lastKey = followKeyOf(runId, target)
        moveToTarget(target, FOLLOW_RESUME_DURATION)
      }
      return
    }

    const target = pickFollowTarget(detail.nodeRuns, detail.status)
    if (target) {
      trackedTarget = target
      hasTarget.value = true
      // 暂停期间只换目标不移动；用户点「继续跟随」时再对准
      if (!paused.value) {
        const key = followKeyOf(runId, target)
        if (key !== lastKey) {
          lastKey = key
          moveToTarget(target, FOLLOW_MOVE_DURATION)
        }
      }
      return
    }

    // 没有在跑 / 在等的节点：步骤间隙不动；run 已终态则收掉入口
    if (isTerminalRunStatus(detail.status)) {
      trackedTarget = null
      hasTarget.value = false
      lastKey = ''
      clearRetry()
    }
  }

  /**
   * 用户主动改了视野：本次调试内暂停自动跟随。
   *
   * <p>还没绑定 run 时不置暂停，否则刚打开调试框就把起跑后的跟随挡掉。
   */
  function pause(): void {
    if (!bound) {
      return
    }
    paused.value = true
  }

  /**
   * 点「继续跟随」：立刻对准当前目标并恢复自动跟随。
   *
   * <p>先解除暂停再移动，避免移动被自身状态挡掉。
   */
  function resume(): void {
    if (!trackedTarget) {
      return
    }
    paused.value = false
    if (lastRunId !== null) {
      lastKey = followKeyOf(lastRunId, trackedTarget)
    }
    moveToTarget(trackedTarget, FOLLOW_RESUME_DURATION)
  }

  /**
   * 视野尺寸变了（如进出专注模式）但仍要继续跟随：按当前目标重新对准，不改缩放。
   *
   * <p>跟随未开启时由宿主自己走原来的适配逻辑，本方法不做任何事。
   */
  function reposition(): void {
    if (!following.value || !trackedTarget) {
      return
    }
    moveToTarget(trackedTarget, FOLLOW_MOVE_DURATION)
  }

  /** 关掉调试框 / 清空这条时停跟随：清键与暂停态，但不回滚视口 */
  function stop(): void {
    clearRetry()
    bound = false
    paused.value = false
    hasTarget.value = false
    lastRunId = null
    lastKey = ''
    trackedTarget = null
    oneShotRunId = null
  }

  function clearRetry(): void {
    if (retryTimer !== null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  /**
   * 把目标节点中心对到左侧可见区域中心。
   *
   * <p>任何一项前置条件不满足（节点没量到尺寸、可见区太窄、节点放不下、位移太小）都保持原视口，
   * 宁可不动，也不把节点塞到调试框底下。
   *
   * @param target 目标节点
   * @param duration 平移时长（毫秒）
   */
  function moveToTarget(target: FollowTarget, duration: number): void {
    const node = options.findNodeByCode(target.code)
    if (!node) {
      return
    }
    const width = Number(node.dimensions?.width ?? 0)
    const height = Number(node.dimensions?.height ?? 0)
    if (!width || !height) {
      // 节点刚渲染还没量到盒子：下一拍再试一次，不无限重试
      scheduleMeasureRetry(target, duration)
      return
    }
    const host = options.getCanvasHost()
    if (!host) {
      return
    }
    const hostRect = host.getBoundingClientRect()
    if (!hostRect.width || !hostRect.height) {
      return
    }
    // 遮挡物换算到画布容器坐标系，由纯函数决定从哪边扣
    const obstructions = options.getObstructionRects().map((rect) => ({
      left: rect.left - hostRect.left,
      top: rect.top - hostRect.top,
      right: rect.right - hostRect.left,
      bottom: rect.bottom - hostRect.top
    }))
    const box = visibleFollowBox(hostRect.width, hostRect.height, obstructions)
    if (box.width < FOLLOW_MIN_VISIBLE_WIDTH || box.height <= 0) {
      return
    }
    const current = options.getViewport()
    const zoom = current.zoom
    // 当前缩放下节点都放不进可见区：不放大也不缩小，保持原视口
    if (width * zoom > box.width || height * zoom > box.height) {
      return
    }
    const position = node.computedPosition ?? node.position
    if (!position) {
      return
    }
    const nodeX = position.x + width / 2
    const nodeY = position.y + height / 2
    const next = followViewportOf(box, nodeX, nodeY, zoom)
    // 本来就在中心附近：不为了几像素再动一次，减少并行 / 连发时的抖动
    if (
      Math.abs(next.x - current.x) < FOLLOW_MIN_MOVE &&
      Math.abs(next.y - current.y) < FOLLOW_MIN_MOVE
    ) {
      return
    }
    void options.setViewport({ x: next.x, y: next.y, zoom }, { duration })
  }

  /**
   * 节点尺寸还没量到时的单次补量。
   *
   * @param target 目标节点
   * @param duration 平移时长
   */
  function scheduleMeasureRetry(target: FollowTarget, duration: number): void {
    if (retryTimer !== null) {
      return
    }
    const key = lastKey
    retryTimer = setTimeout(() => {
      retryTimer = null
      // 期间换了节点或用户暂停，这次补量作废
      if (paused.value || lastKey !== key) {
        return
      }
      const node = options.findNodeByCode(target.code)
      if (!node?.dimensions?.width || !node.dimensions?.height) {
        return
      }
      moveToTarget(target, duration)
    }, MEASURE_RETRY_MS)
  }

  return {
    paused,
    hasTarget,
    following,
    onDetail,
    pause,
    resume,
    reposition,
    stop
  }
}
