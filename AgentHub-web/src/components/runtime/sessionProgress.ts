/**
 * 会话是否还可能有一轮在跑。所属域：runtime（前端）。
 *
 * <p>左栏转圈的正向信号仍是本页 {@code trialStream} 的 running 集合：
 * 多轮期间服务端 status 一直是 RUNNING，不能单凭它让历史行全转。
 * 这里只做反向闸门：启动收口或用户取消把会话收成终态之后，
 * 即使库里还留着未配对的 user 事件，也不再续订、不再显示「正在作答」。
 */

/**
 * 缺状态按仍在进行，避免旧响应没带回 status 时把真流式停掉。
 * 只有明确不是 RUNNING（取消 / 失败 / 成功）才视为已收口。
 *
 * @param status 会话状态，可空
 * @return true 表示这一行仍可能有一轮在跑
 */
export function sessionStillRunning(status?: string | null): boolean {
  const value = (status ?? '').trim()
  if (!value) {
    return true
  }
  return value === 'RUNNING'
}

/**
 * 左栏该行是否显示执行中转圈。
 * 终态优先于本页 running 集合：重启收口后的取消会话不再转。
 *
 * @param status      列表行上的会话状态
 * @param turnRunning 本页是否把这一轮登记成执行中
 * @param temp        是否前端临时草稿（负 id）
 * @return true 表示该行转圈
 */
export function rowShowsSpinner(status: string | null | undefined, turnRunning: boolean, temp: boolean): boolean {
  return !temp && turnRunning && sessionStillRunning(status)
}

/**
 * 一轮运行当前处于什么状态，供「运行是否还可停止」这一套共用判定取用。
 *
 * <p>三个信号必须都算进来：只看 {@code sending} 会漏掉「等待回复」与「刷新后续订」
 * 这两类没有在途流的运行，正是「运行还在跑、输入区却露出发送按钮」的成因。
 */
export interface RunActivity {
  /** 本页是否有一条在途 SSE（刚发出、还没收尾） */
  sending?: boolean
  /** 这一轮是否真的还在等用户回复（工作流 / 方案停等；不含「已答复、正在续跑」） */
  waitingReply?: boolean
  /** 是否正在续订直播（刷新后重新接上这一轮，含点选续跑这一拍） */
  resuming?: boolean
}

/**
 * 这一轮是否还有可中断的执行中对象。所属域：runtime（前端判定）。
 *
 * <p>MA 运行、工作台对话、管理端运行都用这一条：运行还在进行（含刷新后续跑）即为真，
 * 跑完才为假。调用方只负责各自把 {@link RunActivity} 的三个信号算准，不再各写一套。
 *
 * @param activity 本轮三个运行信号
 * @return true 表示输入区应显示停止，而不是可点的发送
 */
export function runStoppable(activity: RunActivity): boolean {
  return activity.sending === true || activity.waitingReply === true || activity.resuming === true
}

/**
 * 停止按钮此刻是否可点（显示与否由 {@link runStoppable} 决定）。所属域：runtime（前端判定）。
 *
 * <p>另加三道闸门：用户已点过停止（避免对同一轮重复发取消）、停止请求在途、
 * 以及还没有真实会话 id（草稿没有可中断的后端对象）。
 *
 * @param activity 本轮三个运行信号
 * @param halted   本轮是否已被用户停止
 * @param stopping 停止请求是否在途
 * @param hasSession 当前是否有真实落库会话（草稿为 false）
 * @return true 表示停止按钮可点
 */
export function canStopRun(
  activity: RunActivity,
  halted: boolean,
  stopping: boolean,
  hasSession: boolean
): boolean {
  return runStoppable(activity) && !halted && !stopping && hasSession
}

/**
 * 这一轮是否「运行还在进行，输入框不能当新问题发送」。所属域：runtime（前端判定）。
 *
 * <p>与 {@link runStoppable} 的唯一差别：**不算 {@code waitingReply}**。
 * 工作流停在「等待用户回复」时，这一轮已不在流式中，输入框那句就是答复，必须能发出去；
 * 而在途流与刷新后的续订都属于「还在跑」，此时露出可点的发送会让用户把下一句当成新问题
 * 发进去——正是要修的现象。
 *
 * @param activity 本轮三个运行信号
 * @return true 表示应显示停止、不显示发送
 */
export function runBlocksSending(activity: RunActivity): boolean {
  return runStoppable({ ...activity, waitingReply: false })
}

/**
 * 输入区该显示哪一组按钮。所属域：runtime（前端判定）。
 *
 * <p>MA 运行、工作台对话、管理端运行共用这一条，不再各写一套「什么时候显示停止」：
 * <ul>
 *   <li>运行还在进行（在途流 / 刷新后续跑）→ 只有停止，没有发送；</li>
 *   <li>工作流停在等待回复 → 停止与发送同时出现（发送即那句答复）；</li>
 *   <li>已经跑完 → 只有发送。</li>
 * </ul>
 *
 * @param activity 本轮三个运行信号
 * @return 两个按钮各自的显隐
 */
export function composerButtons(activity: RunActivity): { stop: boolean; send: boolean } {
  return {
    stop: runStoppable(activity),
    send: !runBlocksSending(activity)
  }
}

/**
 * 重进时要不要续订这一轮。
 * open / stale / hasEvents 由调用方用既有事件口径算好，本函数只加终态闸门。
 */
export interface ResumeDecision {
  /** 会话状态；空表示还没拿到，按仍可能在跑处理 */
  status?: string | null
  /** 末尾还有未收尾的一轮（最后一条 user 之后没有 assistant / error） */
  open: boolean
  /** 未收尾的一轮是否已经超过静默上限 */
  stale: boolean
  /** 是否已经有任何事件 */
  hasEvents: boolean
  /** 刚建会话、事件还没落库时的等待窗口（毫秒） */
  pendingMs: number
  /** 会话创建或更新时间的毫秒戳；无法解析时为 0 */
  sessionStamp: number
  /** 当前打开的是不是这一条会话 */
  sameSession: boolean
}

/**
 * 是否进入续订。终态直接否掉；其余口径与原来的 shouldResume 一致。
 *
 * @param input 状态与调用方算好的事件口径
 * @return true 表示还应轮询，并把末尾标成进行中
 */
export function shouldResumeTurn(input: ResumeDecision): boolean {
  if (!sessionStillRunning(input.status)) {
    return false
  }
  if (input.open) {
    return !input.stale
  }
  if (input.hasEvents) {
    return false
  }
  if (!input.sameSession) {
    return false
  }
  return input.sessionStamp > 0 && Date.now() - input.sessionStamp <= input.pendingMs
}
