/**
 * 工具胶囊的展示条目。所属域：runtime（前端展示）。
 *
 * <p>只描述「怎么画」：聚合键、状态、已组装文案与可展开详情。
 * 聚合规则仍在 `workbenchToolPill`，中文名替换仍由调用方决定，组件不反向依赖业务逻辑。
 */
export interface ToolPillItem {
  /** 聚合键（同一参与者 + 工具名） */
  key: string
  /** 聚合后的状态 */
  state: 'running' | 'done' | 'error'
  /** 已组装好的一句文案（含工具中文名与次数） */
  label: string
  /** 可展开的入参出参；空表示不出现「详情」入口 */
  detail?: string
}
