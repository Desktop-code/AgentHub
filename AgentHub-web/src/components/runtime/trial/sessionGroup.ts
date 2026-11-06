/**
 * 试跑左栏「历史会话」的分组模型。所属域：runtime（前端）。
 *
 * 从 `TrialPanel.vue` 抽出的共享类型：父组件负责分组计算，子组件只按组渲染，
 * 双方都以本接口为契约，避免同一结构在组件两侧各写一份 interface。
 */
import type { TrialSession } from '@/types/runtime'

/** 一个分组标题（今日 / 昨日 / 本周 / 更早）+ 该组下的会话行。 */
export interface SessionGroup {
  /** 分组标题，直接展示 */
  label: string
  /** 该组的会话，顺序由父组件决定 */
  items: TrialSession[]
}
