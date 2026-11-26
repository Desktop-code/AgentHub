/**
 * 工作流画布自定义边的选中回调注入键。所属域：workflow 的前端画布层。
 *
 * <p>为什么需要它：自定义边在拖拽走线时会拦住 pointerdown，
 * VueFlow 边包装层自带的 click 选中不一定还能触发；
 * 用 provide/inject 让编辑页把「选中这条边」同步给右侧属性面板，
 * 避免把回调函数塞进可序列化的 edge.data。
 */

import type { InjectionKey } from 'vue'

/** 自定义边与编辑页之间的交互契约 */
export interface WorkflowEdgeInteraction {
  /**
   * 选中指定连线。
   *
   * @param edgeId 边 id
   */
  selectEdge(edgeId: string): void
}

/** 注入键：编辑页 provide，WorkflowEdge.vue inject */
export const WORKFLOW_EDGE_INTERACTION: InjectionKey<WorkflowEdgeInteraction> =
  Symbol('workflowEdgeInteraction')
