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
 * 独立试跑页路径。
 *
 * @param agentId 编排根主键
 * @return 形如 /orchestration/multi-agent/12/trial
 */
export function maTrialPath(agentId: number): string {
  return `${MA_TRIAL_BASE_PATH}/${agentId}/trial`
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
    return '没有试跑权限，已返回多智能体列表'
  }
  // 模式未取到时先放行，等详情回来再判；避免首帧就闪一次误报
  if (input.runMode !== undefined && input.runMode !== MA_TRIAL_RUN_MODE) {
    return '只有多智能体（Router）编排根可以进入独立试跑页'
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
