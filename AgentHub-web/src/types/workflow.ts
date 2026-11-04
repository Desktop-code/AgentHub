/**
 * 工作流域类型。所属域：workflow。
 * <p>与后端 com.agenthub.workflow.api.dto 对齐；graph_json 的 nodes / edges 结构按定稿 §6。
 * <p>本刀只有「定义 / 草稿 / 发布 / 校验」，不含 run / node_run / human_task。
 */

/** 节点类型；P0 只允许前 8 种建图，其余在面板灰显「P1」 */
export type WorkflowNodeType =
  | 'START'
  | 'END'
  | 'AGENT'
  | 'TOOL'
  | 'CONDITION'
  | 'PARALLEL_FORK'
  | 'PARALLEL_JOIN'
  | 'HUMAN'
  | 'KB_RETRIEVE'
  | 'SKILL_INJECT'
  | 'SUBGRAPH'
  | 'MAP'
  | 'REDUCE'
  | 'TIMER'
  | 'COMPENSATE'

/** 边类型；CANCEL 本刀不产出 */
export type WorkflowEdgeType = 'NORMAL' | 'COND' | 'DEFAULT' | 'ERROR'

/** 人工卡点超时策略，与后端 HumanTimeoutPolicy 一致 */
export type HumanTimeoutPolicy = 'AUTO_REJECT' | 'AUTO_APPROVE' | 'ESCALATE'

/** 节点 config；未知字段原样保留，避免把其它刀写入的配置洗掉 */
export interface WorkflowNodeConfig {
  /** AGENT 节点绑定的 agent_def.id */
  agentId?: number | null
  /** TOOL 节点绑定的 tool_item.id */
  toolId?: number | null
  /** FORK / JOIN 成对追踪键 */
  forkGroupId?: string
  /** HUMAN 超时策略，必填 */
  timeoutPolicy?: HumanTimeoutPolicy | string
  /** HUMAN 超时毫秒数，可空 */
  timeoutMs?: number | null
  /**
   * HUMAN 处理人规则（W4）：
   * `ROLE:<roleCode>` 角色码命中；`USER:<id>[,<id>]` 用户 id 命中；空=有 human:act 即可。
   */
  assigneeRule?: string | null
  /** CONDITION 表达式，JSONLogic 子集；本刀只存盘与结构校验 */
  condition?: unknown
  /** 入参映射：目标字段 → $.input / $.outputs / $.vars 路径 */
  inputMapping?: Record<string, string>
  /** 出参映射 */
  outputMapping?: Record<string, string>
  [key: string]: unknown
}

/** 落库 / 回传的节点结构 */
export interface WorkflowGraphNode {
  id: string
  code: string
  type: WorkflowNodeType | string
  name: string
  config: WorkflowNodeConfig
  ui: { x: number; y: number }
}

/** 落库 / 回传的边结构 */
export interface WorkflowGraphEdge {
  id: string
  source: string
  target: string
  type: WorkflowEdgeType | string
  label?: string | null
  config?: Record<string, unknown>
}

/** graph_json 整体结构 */
export interface WorkflowGraph {
  nodes: WorkflowGraphNode[]
  edges: WorkflowGraphEdge[]
  viewport?: { x: number; y: number; zoom: number }
}

/** 画布节点 data（VueFlow 节点上的业务载荷） */
export interface WorkflowFlowNodeData {
  code: string
  name: string
  nodeType: WorkflowNodeType | string
  config: WorkflowNodeConfig
  /** 试跑高亮：由 run 详情回填，不参与保存 */
  runStatus?: WorkflowNodeRunStatus | string
}

/** 画布边 data */
export interface WorkflowFlowEdgeData {
  edgeType: WorkflowEdgeType | string
  label?: string | null
  condition?: unknown
  config?: Record<string, unknown>
}

/** 定义行 */
export interface WorkflowDefRow {
  id: number
  code: string
  name: string
  description?: string
  /** DRAFT_ONLY / HAS_RELEASE */
  status: string
  defaultReleaseId?: number | null
  defaultVersion?: number | null
  createTime?: string
  updateTime?: string
}

/** 草稿回显；expectedUpdateTime 取 updateTime */
export interface WorkflowDraftResp {
  workflowId: number
  graph: WorkflowGraph
  updateTime?: string
}

/** 单条校验问题 */
export interface WorkflowIssue {
  code: string
  message: string
  nodeId?: string | null
  nodeCode?: string | null
}

/** 校验结果 */
export interface WorkflowValidateResp {
  errors: WorkflowIssue[]
  warns: WorkflowIssue[]
  passed: boolean
}

/** 发布版本 */
export interface WorkflowReleaseRow {
  id: number
  workflowId: number
  version: number
  fingerprint: string
  publishNote?: string | null
  publishedBy: number
  publishTime?: string
}

/** 节点面板条目 */
export interface WorkflowPaletteItem {
  type: WorkflowNodeType
  label: string
  hint: string
  /** false 表示 P1，本刀灰显不可拖 */
  p0: boolean
}

/** run 状态，与后端 WorkflowRunStatus 一致 */
export type WorkflowRunStatus =
  | 'CREATED'
  | 'RUNNING'
  | 'PAUSED_HUMAN'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'

/** 节点运行态，与后端 WorkflowNodeRunStatus 一致 */
export type WorkflowNodeRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'SKIPPED'
  | 'WAITING_HUMAN'
  | 'CANCELLED'

/** 节点运行态行（GET run 返回） */
export interface WorkflowNodeRunRow {
  id: number
  nodeId: string
  nodeCode: string
  nodeType: string
  attempt: number
  status: WorkflowNodeRunStatus | string
  errorSummary?: string | null
  /** 选中的出边 id，逗号分隔 */
  selectedEdges?: string | null
  startTime?: string | null
  endTime?: string | null
}

/** 人工待办行 */
export interface WorkflowHumanTaskRow {
  id: number
  runId: number
  nodeRunId: number
  nodeCode: string
  status: string
  assigneeRule?: string | null
  timeoutPolicy: string
  expireTime?: string | null
  resultJson?: string | null
}

/**
 * 待办中心行（GET /api/workflow/human-tasks）。
 * <p>比 run 详情里的待办多带 run / 工作流上下文与 actionable；
 * 不含 form_json / result_json 正文。
 */
export interface WorkflowHumanTaskItem {
  id: number
  runId: number
  workflowId?: number | null
  workflowCode: string
  workflowName: string
  releaseVersion?: number | null
  runStatus?: string | null
  nodeCode: string
  status: string
  assigneeRule?: string | null
  /** 服务端给的规则中文描述，前端直接用，避免文案漂移 */
  assigneeText: string
  timeoutPolicy: string
  expireTime?: string | null
  createTime?: string | null
  /** 当前用户此刻能否处理 */
  actionable: boolean
}

/** run 详情（试跑抽屉与画布高亮的数据源） */
export interface WorkflowRunDetail {
  id: number
  workflowId: number
  releaseId: number
  releaseVersion: number
  status: WorkflowRunStatus | string
  triggerType: string
  /** 应用触发时的 sys_app.id；试跑为空 */
  appId?: number | null
  input?: unknown
  output?: unknown
  errorSummary?: string | null
  startTime?: string | null
  endTime?: string | null
  createTime?: string | null
  nodeRuns: WorkflowNodeRunRow[]
  /** 只含 OPEN 待办 */
  humanTasks: WorkflowHumanTaskRow[]
}

/** run 摘要（历史列表） */
export interface WorkflowRunSummary {
  id: number
  status: WorkflowRunStatus | string
  triggerType: string
  /** 应用触发时的 sys_app.id；试跑为空 */
  appId?: number | null
  releaseId: number
  releaseVersion: number
  errorSummary?: string | null
  startTime?: string | null
  endTime?: string | null
  createTime?: string | null
}

/**
 * 应用-工作流授权行（GET /api/workflow/app-grants）。
 * <p>releaseId 为空表示跟随定义的默认发布指针；非空表示钉死该历史版本。
 */
export interface WorkflowAppGrantItem {
  workflowId: number
  /** 定义被删除 / 跨租户时为空 */
  code?: string | null
  name?: string | null
  defaultReleaseId?: number | null
  /** 默认发布版本号；未发布为空 */
  defaultVersion?: number | null
  releaseId?: number | null
  status?: number | null
  createTime?: string | null
}

/** OpenAPI 触发响应（POST /api/open/workflow/runs） */
export interface WorkflowAppInvokeResult {
  runId: number
  status: string
  workflowId: number
  releaseId: number
  triggerType: string
}
