/**
 * 调试框的一条试跑：开始、按 run 订阅直播、按 run 回复、取消、人工续跑。
 * 所属域：workflow 的前端。
 *
 * <p>进行中的卡片以按 run 的直播快照为准（`GET /api/workflow/runs/{id}/live-card`），
 * 思考、工具和节点结果边跑边追加；直播不可用时才退回 `GET /api/workflow/runs/{id}`
 * 的已落库卡片（约 1.5 秒一拍）。不创建工作台会话，不读写会话事件流。
 *
 * <p>收口：直播最后一帧与详情现算走同一套投影，同一条 run 上只补库字段，
 * 不把已经流出来的卡片换回库版本，避免结束时闪一下或整段重画。
 */
import { onBeforeUnmount, ref } from 'vue'
import {
  approveHumanTask,
  cancelWorkflowRun,
  getWorkflowRun,
  getWorkflowRunLiveCard,
  rejectHumanTask,
  replyWorkflowTrial,
  startWorkflowTrial
} from '@/api/workflow'
import type { WorkflowRunDetail, WorkflowRunLiveCard } from '@/types/workflow'
import { alignClosedCard, isLiveRunStatus, mergeLiveRun, queryOf, shouldPoll } from './workflowDebugCard'

/** 直播快照的读取间隔：投影本身约 150ms 出一帧，这里稍慢一点，够连续又不过密 */
const LIVE_POLL_MS = 250

/** 详情兜底间隔：与接入直播前一致，仅在按 run 的直播不可用时走 */
const POLL_MS = 1500

/** 连续几次读不到直播才退回详情：覆盖「run 已返回、后台线程还没开播」这段空档 */
const LIVE_MISS_LIMIT = 6

/** 退回详情后再次探直播的间隔：后台排队或重启后能自动接回，不一直空转 */
const LIVE_PROBE_MS = 5000

/**
 * @param options.onDetail 画布运行态变化时通知宿主；传 null 表示这条调试已清掉
 * @param options.isActive 调试框开着且宿主页在前台时才继续读
 */
export function useWorkflowDebugSession(options: {
  onDetail: (detail: WorkflowRunDetail | null) => void
  isActive: () => boolean
}) {
  const run = ref<WorkflowRunDetail | null>(null)
  /** 过程区上方那一句，只取当前 run 的 input.query */
  const asked = ref('')
  const starting = ref(false)
  const replying = ref(false)
  const cancelling = ref(false)
  const acting = ref(false)
  /** 丢弃过期响应：开始、回复、换一条、换工作流都会递增 */
  let epoch = 0
  let timer: ReturnType<typeof setTimeout> | null = null
  /** 是否优先读按 run 的直播快照；false 时退回详情轮询 */
  let liveMode = false
  /** 连续读不到直播的次数：够了才退回详情，避免开播前空档把直播关掉 */
  let liveMisses = 0
  /** 下次允许再探直播的时刻（毫秒）；退回详情后按这个节奏重试 */
  let nextLiveProbeAt = 0
  /** 当前卡片来自哪条 run 的直播；只有它才允许在同一条 run 上压过库现算卡片 */
  let liveCardRunId: number | null = null
  /** 上一次通知画布的节点状态指纹：直播每帧都到，但节点没变不必重刷画布 */
  let lastCanvasStamp = ''

  /**
   * 采纳一份详情。更早发出的请求回来时直接丢掉。
   *
   * <p>同一条 run 上，直播刚流出的卡片比库现算更完整：这里只补库里字段，
   * 不再用详情里的 card 整份替换；其它 run 才以详情为准。
   *
   * @param detail 接口返回的 run
   * @param ticket 发起这次操作时的代次
   */
  function accept(detail: WorkflowRunDetail, ticket: number) {
    if (ticket !== epoch) {
      return
    }
    const previous = run.value
    const keepLiveCard =
      previous != null &&
      previous.id === detail.id &&
      liveCardRunId === detail.id &&
      previous.card != null
    /*
     * 终态（尤其是取消）由请求线程直接落库，直播卡可能还停在上一帧「运行中」。
     * 这里只把卡片状态对齐成终态，保留已经流出的思考、工具与节点结果。
     */
    const next = keepLiveCard && previous
      ? { ...detail, card: alignClosedCard(previous.card, detail.status) }
      : detail
    run.value = next
    asked.value = queryOf(next.input)
    lastCanvasStamp = canvasStamp(next)
    options.onDetail(next)
    syncPoll()
  }

  /**
   * 按当前状态和页面是否在前台，决定要不要预约下一次读取。
   * 运行中优先走直播；直播退出后按详情间隔继续。
   */
  function syncPoll() {
    stopTimer()
    const current = run.value
    const busy = replying.value || acting.value
    if (!options.isActive() || !current || !shouldPoll(current.status, busy)) {
      return
    }
    const ticket = epoch
    const live = liveMode
    timer = setTimeout(() => {
      if (live) {
        void pullLive(current.id, ticket)
      } else {
        void pull(current.id, ticket)
      }
    }, live ? LIVE_POLL_MS : POLL_MS)
  }

  /**
   * 拉一次详情。代次变了说明用户已经开始另一条，这次结果作废。
   *
   * @param id run 主键
   * @param ticket 预约这次轮询时的代次
   */
  async function pull(id: number, ticket: number) {
    if (ticket !== epoch) {
      return
    }
    try {
      const { data } = await getWorkflowRun(id)
      if (ticket !== epoch || !data.data) {
        return
      }
      // 直播判不可用后以库现算卡片为准，别让旧直播帧一直压着详情
      liveCardRunId = null
      // run 还在跑但直播尚未开播（后台排队 / 重启）时，过一会儿自动再探
      if (isLiveRunStatus(data.data.status) && Date.now() >= nextLiveProbeAt) {
        liveMode = true
        liveMisses = 0
      }
      accept(data.data, ticket)
    } catch {
      // 单次失败不打断调试：下一拍再试，终态由下一次成功响应收口
      if (ticket === epoch) {
        syncPoll()
      }
    }
  }

  /**
   * 读一次按 run 的直播快照，把同一张卡的下一个增量接上。
   *
   * <p>没有直播时先按直播节奏重试几次；仍没有才退回详情。进入等待或终态后，
   * 直播最后一帧已经是完整卡片，只再拉一次详情补人工待办与入参，不换卡片。
   *
   * @param id run 主键
   * @param ticket 预约这次读取时的代次
   */
  async function pullLive(id: number, ticket: number) {
    if (ticket !== epoch) {
      return
    }
    let live: WorkflowRunLiveCard | null = null
    try {
      const { data } = await getWorkflowRunLiveCard(id)
      live = data.data ?? null
    } catch {
      live = null
    }
    if (ticket !== epoch) {
      return
    }
    if (!live || !live.live) {
      liveMisses += 1
      if (liveMisses < LIVE_MISS_LIMIT) {
        syncPoll()
        return
      }
      liveMode = false
      nextLiveProbeAt = Date.now() + LIVE_PROBE_MS
      await pull(id, ticket)
      return
    }
    liveMisses = 0
    const base = run.value
    if (!base || base.id !== id) {
      return
    }
    // 同一帧只更新卡片与节点状态，正文由投影继续追加，这里不清空任何已流出的内容
    const merged = mergeLiveRun(base, live)
    liveCardRunId = merged.id
    run.value = merged
    asked.value = queryOf(merged.input)
    notifyCanvas(merged)
    // 续跑 / 取消请求还在飞时不要收口：等待态的旧状态可能先于第一帧到达
    const busy = replying.value || cancelling.value || acting.value || starting.value
    if (!busy && !isLiveRunStatus(merged.status)) {
      liveMode = false
      await settle(id, ticket)
      return
    }
    syncPoll()
  }

  /**
   * 直播进入等待 / 终态后补一次完整详情。
   *
   * <p>只取人工待办、入参、产出与错误摘要；卡片仍保留直播最后一帧
   * （{@link accept} 在同一条 run 上会保留 `liveCardRunId` 的卡片）。
   *
   * @param id run 主键
   * @param ticket 代次
   */
  async function settle(id: number, ticket: number) {
    if (ticket !== epoch) {
      return
    }
    try {
      const { data } = await getWorkflowRun(id)
      if (ticket !== epoch) {
        return
      }
      if (data.data) {
        accept(data.data, ticket)
      } else {
        syncPoll()
      }
    } catch {
      // 详情补不到也保留直播最后一帧，不把已流出的卡片清掉
      if (ticket === epoch) {
        syncPoll()
      }
    }
  }

  /**
   * 通知宿主画布最新的节点运行态。
   *
   * <p>直播按约 250ms 读一次，卡片要跟着长；但节点行没变时不重复发，
   * 避免每一帧都重刷节点 data 和高亮。跟随仍由宿主按这份数据决定。
   *
   * @param detail 当前详情
   */
  function notifyCanvas(detail: WorkflowRunDetail) {
    const stamp = canvasStamp(detail)
    if (stamp === lastCanvasStamp) {
      return
    }
    lastCanvasStamp = stamp
    options.onDetail(detail)
  }

  /**
   * 画布关心的最小指纹：整体状态 + 每个节点的 attempt / status。
   *
   * @param detail 当前详情
   * @return 可直接比较的字符串
   */
  function canvasStamp(detail: WorkflowRunDetail): string {
    const parts: string[] = [String(detail.status || '')]
    for (const row of detail.nodeRuns || []) {
      parts.push(`${row.nodeCode ?? ''}:${row.attempt ?? 0}:${row.status ?? ''}`)
    }
    return parts.join('|')
  }

  /** 停掉已预约的一次读取 */
  function stopTimer() {
    if (timer != null) {
      clearTimeout(timer)
      timer = null
    }
  }

  /**
   * 用这句话按当前草稿开始一条调试。调用方应先把画布落成草稿。
   *
   * @param workflowId 定义 id
   * @param input 含 query 的对象
   * @return 是否已提交；请求失败时为 false，界面保持未开始
   */
  async function start(workflowId: number, input: Record<string, unknown>) {
    epoch += 1
    const ticket = epoch
    stopTimer()
    // 新的一条：直播与卡片归属都重新开始
    liveMode = false
    liveMisses = 0
    liveCardRunId = null
    starting.value = true
    try {
      const { data } = await startWorkflowTrial({ workflowId, useDraft: true, input })
      if (!data.data) {
        return false
      }
      liveMode = isLiveRunStatus(data.data.status)
      accept(data.data, ticket)
      return ticket === epoch
    } catch {
      return false
    } finally {
      if (ticket === epoch) {
        starting.value = false
      }
    }
  }

  /**
   * 把下一句或选项文案送回当前这条试跑。
   *
   * <p>续跑接口只做回复落库与状态迁移，返回时引擎已经在后台继续：
   * 请求在飞期间不启动直播读取，避免读到上一段已收口的等待快照，
   * 把刚回到运行中的状态又显示成「等待回复」。
   *
   * @param text 回复正文
   * @return 是否续上了同一条
   */
  async function reply(text: string) {
    const current = run.value
    const body = text.trim()
    if (!current || !body) {
      return false
    }
    epoch += 1
    const ticket = epoch
    const runId = current.id
    replying.value = true
    try {
      const { data } = await replyWorkflowTrial(runId, body)
      if (!data.data) {
        return false
      }
      // 返回时状态已是 RUNNING：从这里开始按直播读，卡片边跑边出
      liveMode = isLiveRunStatus(data.data.status)
      liveMisses = 0
      accept(data.data, ticket)
      return data.data.id === runId
    } catch {
      return false
    } finally {
      // 请求被取消 / 换一条打断时也要复位：否则「停止」会被 busy 一直锁住
      replying.value = false
      if (ticket === epoch) {
        syncPoll()
      }
    }
  }

  /**
   * 打开一条已有调试记录，不新开 run。
   *
   * <p>已结束的记录只按 run 现算一次，不订直播；还在跑的记录先接回这条 run 的直播。
   *
   * @param id 历史 run 主键
   */
  async function openRun(id: number) {
    epoch += 1
    const ticket = epoch
    stopTimer()
    liveMode = false
    liveMisses = 0
    nextLiveProbeAt = 0
    liveCardRunId = null
    try {
      const { data } = await getWorkflowRun(id)
      if (ticket !== epoch || !data.data) {
        return
      }
      liveMode = isLiveRunStatus(data.data.status)
      accept(data.data, ticket)
    } catch {
      // 打不开就留在上一条；错误文案由请求拦截器给出
    }
  }

  /**
   * 取消当前这条。终态由详情回写：取消接口一返回就按终态收口，
   * 不再等引擎线程的下一帧（它可能还卡在一次出站调用里）。
   */
  async function cancel() {
    const current = run.value
    // 取消已经在飞时不再重复发：按钮此时由 cancelling 收成 loading
    if (!current || cancelling.value) {
      return
    }
    epoch += 1
    const ticket = epoch
    cancelling.value = true
    try {
      const { data } = await cancelWorkflowRun(current.id)
      if (data.data) {
        liveMode = isLiveRunStatus(data.data.status)
        accept(data.data, ticket)
      }
    } catch {
      // 拦截器已提示
    } finally {
      // 复位与代次无关：换条 / 重置打断时也不能把取消态留在 true
      cancelling.value = false
      if (ticket === epoch) {
        // 取消失败时 run 仍在跑：把读取重新排上，别让轮询停在这次请求上
        syncPoll()
      }
    }
  }

  /**
   * 通过或驳回人工待办，并用返回的详情（含卡片）刷新过程区。
   *
   * @param taskId 待办主键
   * @param approved true 通过并续跑
   * @param note 备注；可空
   */
  async function decide(taskId: number, approved: boolean, note: string) {
    epoch += 1
    const ticket = epoch
    acting.value = true
    // 人工通过同样可能同步续跑，处理期间继续按直播读
    liveMode = true
    liveMisses = 0
    syncPoll()
    try {
      const trimmed = note.trim()
      const { data } = approved
        ? await approveHumanTask(taskId, trimmed || undefined)
        : await rejectHumanTask(taskId, trimmed || undefined)
      if (data.data) {
        liveMode = isLiveRunStatus(data.data.status)
        accept(data.data, ticket)
      }
    } catch {
      // 拦截器已提示
    } finally {
      if (ticket === epoch) {
        acting.value = false
        syncPoll()
      }
    }
  }

  /** 宿主切走或调试框关上时停读取，不清当前这条，重开还能接着看。 */
  function pause() {
    stopTimer()
  }

  /** 重新回到前台：进行中 / 等待回复的 run 继续按原节奏读。 */
  function resume() {
    syncPoll()
  }

  /** 换工作流时丢掉上一条的过程和订阅。 */
  function reset() {
    epoch += 1
    stopTimer()
    liveMode = false
    liveMisses = 0
    nextLiveProbeAt = 0
    liveCardRunId = null
    lastCanvasStamp = ''
    run.value = null
    asked.value = ''
    starting.value = false
    replying.value = false
    cancelling.value = false
    acting.value = false
    options.onDetail(null)
  }

  onBeforeUnmount(() => {
    epoch += 1
    stopTimer()
  })

  return {
    run,
    asked,
    starting,
    replying,
    cancelling,
    acting,
    start,
    reply,
    openRun,
    cancel,
    decide,
    pause,
    resume,
    reset
  }
}
