/**
 * 试跑抽屉的跨页状态。所属域：runtime。
 * 详情运行模式行的「对话」写这里，壳层只挂一个抽屉。侧栏不再提供试跑入口，也不跳 /agent/trial。
 */
import { ref } from 'vue'

/** 旧菜单路径。侧栏、工作台和登录后的第一个页面都要丢掉它。 */
export const TRIAL_MENU_PATH = '/agent/trial'

export interface TrialTarget {
  agentId?: number
  name: string
  runMode: string
}

const open = ref(false)
const target = ref<TrialTarget>({ name: '', runMode: '' })

/**
 * 打开指定 Agent 的试跑抽屉。
 * @param next 至少要有 agentId；名称和运行模式用于标题和徽章
 */
export function openAgentTrial(next: { agentId: number; name?: string; runMode?: string }) {
  target.value = {
    agentId: next.agentId,
    name: next.name || '',
    runMode: next.runMode || ''
  }
  open.value = true
}

/** 关闭抽屉。地址栏上的 trial 参数由壳层清掉。 */
export function closeAgentTrial() {
  open.value = false
}

/** 给壳层和页面共用同一份开关。在组件 setup 里调用。 */
export function useAgentTrial() {
  return { open, target, openAgentTrial, closeAgentTrial }
}
