/**
 * 紧凑型 Router 时间线的展示模型与状态枚举。所属域：runtime（展示层）。
 *
 * <p>职责：给 {@link RouterTimelineCard.vue} 与 {@link RouterTimeline.vue} 共用一套
 * 卡片状态/类型定义，避免模板里散落字符串。
 *
 * <p>约束：只是前端视图模型，不携带时间戳、不写库、不参与 obs span 树归纳。
 */

/**
 * 卡片状态。
 * - running：步骤已开始但未结束（子卡黄色边框，Router/汇总卡紫色边框）；
 * - success：正常结束（绿色边框）；
 * - error：执行失败（红色边框）；
 * - cancelled：会话取消或步骤被取消（灰色边框）。
 */
export type TimelineCardStatus = 'running' | 'success' | 'error' | 'cancelled'

/**
 * 卡片类型。
 * - router：Router / 总控的编排分析；
 * - child：被派发的子 Agent（派发与返回合并为同一张卡）；
 * - aggregate：最终汇总。
 */
export type TimelineCardKind = 'router' | 'child' | 'aggregate'

/** 单张时间线卡片；只保留「节点名称 + 状态 + 摘要」，不展示时间戳。 */
export interface TimelineCardModel {
  /** 稳定 key：直接复用泳道行的 key，状态原地变更时不会重建 DOM */
  key: string
  kind: TimelineCardKind
  /** 节点名称，如「总控 · 分析」「财务专家」「汇总（CONCAT）」 */
  title: string
  status: TimelineCardStatus
  /** 返回文本摘要；运行中可能为空，成功后原地补上 */
  summary: string
}
