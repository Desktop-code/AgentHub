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
