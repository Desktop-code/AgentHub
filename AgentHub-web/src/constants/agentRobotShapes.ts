/**
 * Agent 机器人形象的矢量图元数据。所属域：agent（前端）。
 *
 * <p>8 套图形用数据描述（rect / path / circle），由 AgentLookArt 内联渲染成 SVG。
 * 之所以不做成静态 .svg 文件再用 img 引：颜色要由 avatarConfig.color 动态驱动，
 * img 无法改描边色；内联 SVG 才能让「选颜色 = 改图形颜色」，且背景恒为白底不变。
 */

/** 单个图元：字段与 SVG 同名属性一一对应 */
export type AgentRobotShape =
  | { t: 'rect'; x: number; y: number; w: number; h: number; rx?: number }
  | { t: 'path'; d: string }
  | { t: 'circle'; cx: number; cy: number; r: number }
