/**
 * 多智能体独立试跑页的入口口径。所属域：前端 runtime。
 *
 * <p>为什么要单独一层：列表、Agent 详情（「对话」与编排 Tab 的「试跑」）两处入口，
 * 加上页面自身的进入校验，都依赖同一套「谁能进、路径是什么」规则。
 * 抽成纯函数后，页面与入口只调一处，node:test 也能直接覆盖，
 * 不用把判定散在三处三元表达式里各写一遍。
 */

/** 多智能体列表路径，同时作为试跑页返回的兜底落点 */
export const MA_TRIAL_BASE_PATH = '/orchestration/multi-agent'

/** 唯一允许进独立试跑页的运行模式：一层编排根 */
export const MA_TRIAL_RUN_MODE = 'ROUTER'

/**
 * 独立试跑页路径（绑根方案）。
 *
 * @param agentId 编排根主键
 * @return 形如 /orchestration/multi-agent/12/trial
 */
export function maTrialPath(agentId: number): string {
  return `${MA_TRIAL_BASE_PATH}/${agentId}/trial`
}

/**
 * 独立试跑页路径（虚拟根方案，MA-P2 刀2）。
 *
 * <p>为什么用 {@code def/} 段：虚拟根没有 agentId，直接写 {@code /multi-agent/12/trial}
 * 会和「Agent 12 的试跑」撞路径；加一段静态前缀后两条入口一眼可分，也不会误命中。
 *
 * @param maId 方案主键
 * @return 形如 /orchestration/multi-agent/def/12/trial
 */
export function maDefTrialPath(maId: number): string {
  return `${MA_TRIAL_BASE_PATH}/def/${maId}/trial`
}

/** 进入校验入参 */
export interface MaTrialEntryInput {
  /** 路径上的 Agent 主键；0 / 非法值表示链接有问题 */
  agentId: number
  /** 是否具备试跑权限（runtime:trial:run 或 runtime:trial:list） */
  canTrial: boolean
  /** 已知的运行模式；undefined 表示详情还没取到，此时只校前两项 */
  runMode?: string | null
}

/**
 * 进入独立试跑页的校验。
 *
 * @param input 校验入参
 * @return 空串表示可以进入；否则是可直接展示给用户的中文原因
 */
export function maTrialEntryError(input: MaTrialEntryInput): string {
  if (!input.agentId) {
    return '缺少 Agent 标识，已返回多智能体列表'
  }
  if (!input.canTrial) {
    return '没有运行权限，已返回多智能体列表'
  }
  // 模式未取到时先放行，等详情回来再判；避免首帧就闪一次误报
  if (input.runMode !== undefined && input.runMode !== MA_TRIAL_RUN_MODE) {
    return '只有多智能体（Router）编排根可以进入独立运行页'
  }
  return ''
}

/**
 * 路径是否仍落在独立试跑页。
 *
 * <p>只回答「是不是试跑路由」，不校验 id 是否合法：非法 id 也要先被识别成试跑页，
 * 才能由页面统一提示并回多智能体列表。KeepAlive 下页面失活后 route.params 会变成
 * 目标路由的值（agentId 变 undefined / 0），不能拿参数判断是否还在本页。
 *
 * <p>两种形态都算：绑根 {@code /multi-agent/{agentId}/trial}、
 * 虚拟根 {@code /multi-agent/def/{maId}/trial}。
 *
 * @param path 当前路由 path
 * @return true 表示形如 /orchestration/multi-agent/{id|def/{id}}/trial
 */
export function isMaTrialPath(path: string): boolean {
  const prefix = `${MA_TRIAL_BASE_PATH}/`
  const suffix = '/trial'
  // 前缀、后缀都要命中，且中间必须是一段非空 id，避免把列表页 /orchestration/multi-agent 误判进来
  if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
    return false
  }
  const idPart = path.slice(prefix.length, path.length - suffix.length)
  // 虚拟根多一层静态 def/：只允许「def + 一段 id」，其它多段路径仍判否
  if (idPart.startsWith('def/')) {
    const maPart = idPart.slice('def/'.length)
    return maPart.length > 0 && !maPart.includes('/')
  }
  return idPart.length > 0 && !idPart.includes('/')
}

/** 虚拟根试跑页的进入校验入参 */
export interface MaDefTrialEntryInput {
  /** 路径上的方案主键；0 / 非法值表示链接有问题 */
  maId: number
  /** 是否具备试跑权限（runtime:trial:run 或 runtime:trial:list） */
  canTrial: boolean
}

/**
 * 进入虚拟根试跑页的校验。
 *
 * @param input 校验入参
 * @return 空串表示可以进入；否则是可直接展示给用户的中文原因
 */
export function maDefTrialEntryError(input: MaDefTrialEntryInput): string {
  if (!input.maId) {
    return '缺少方案标识，已返回多智能体列表'
  }
  if (!input.canTrial) {
    return '没有运行权限，已返回多智能体列表'
  }
  return ''
}

/**
 * 试跑落点：Router 走独立页，其它模式仍开原抽屉。
 *
 * @param agentId Agent 主键
 * @param runMode 已保存的运行模式
 * @return 独立页路径；非 Router 返回空串，调用方据此走原抽屉
 */
export function maTrialPathOrEmpty(agentId: number, runMode?: string | null): string {
  return runMode === MA_TRIAL_RUN_MODE && agentId > 0 ? maTrialPath(agentId) : ''
}
