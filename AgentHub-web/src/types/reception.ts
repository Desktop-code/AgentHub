/**
 * 路由池候选目标的选项类型。所属域：前端 agent。
 *
 * <p>默认接待页已整页下线：这里只保留「可加入对话路由（对话入口）候选池」的三类目标选项，
 * 供多智能体「可视化编排」页的下拉使用。候选名单本身仍存在 ma_candidate，
 * 编辑入口只有多智能体方案详情一处。
 */

/** 候选目标类型：子 Agent / 另一套编排方案 / 已发布工作流 */
export type ReceptionTargetType = 'AGENT' | 'MA' | 'WORKFLOW'

/** 「添加专家」下拉项：已排除被任一方案占用的 Agent */
export interface ReceptionAgentOption {
  id: number
  name: string
  code: string
  runMode: string
  /** Agent 简介：加进池子后就是这条线路的意图说明 */
  description?: string
  /** true 表示还没填简介，添加会被后端拒绝 */
  descriptionMissing?: boolean
}

/** 「添加方案」下拉项：已剔除自己 / 入口 / 停用 / 非叶子 / 不判路由的方案 */
export interface ReceptionSchemeOption {
  id: number
  name: string
  code: string
  /** 方案模板码 */
  template?: string
  /** 方案说明：加进池子后就是这条线路的意图说明 */
  description?: string
  /** true 表示还没填方案说明，添加会被后端拒绝 */
  descriptionMissing?: boolean
}

/**
 * 「添加工作流」下拉项：后端只返回已发布且有说明的工作流。
 *
 * <p>草稿-only、无默认发布指针、说明为空的定义不会出现在这里——
 * 它们既跑不起来也无法作为判路由依据。
 */
export interface ReceptionWorkflowOption {
  /** workflow_def.id，添加时作为 workflowDefId 提交 */
  id: number
  name: string
  code: string
  /** 定义状态，此处恒为 HAS_RELEASE */
  status?: string
  /** 默认发布指针：非空才允许运行（后端已过滤） */
  defaultReleaseId?: number | null
  /** 工作流说明：加进池子后就是这条线路的意图说明 */
  description?: string
}
