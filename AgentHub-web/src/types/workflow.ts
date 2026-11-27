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
  | 'LOOP'
  | 'KB_RETRIEVE'
  | 'SKILL_INJECT'
  | 'SUBGRAPH'
  | 'MAP'
  | 'REDUCE'
  | 'TIMER'
  | 'COMPENSATE'

/** 边类型；CANCEL 本刀不产出。循环三条边必须显式声明，不能靠普通回边冒充 */
export type WorkflowEdgeType =
  | 'NORMAL'
  | 'COND'
  | 'DEFAULT'
  | 'ERROR'
  | 'LOOP_BODY'
  | 'LOOP_BACK'
  | 'LOOP_EXIT'

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
  /**
   * START 节点的入参声明：顶层 JSON Schema（properties / required / title / description）。
   * <p>键即编排里 $.input 下的字段名；随 graph_json 进发布快照，发布后不可变。
   */
  inputSchema?: unknown
  /**
   * SUBGRAPH 节点绑定的被调工作流 def id；必须已被发布过。
   * <p>跨租户不允许，后端按「不存在」处理。
   */
  subWorkflowId?: number | null
  /** SUBGRAPH 节点钉死的发布版本 id（workflow_release.id）；出槽只认这一版的声明 */
  subReleaseId?: number | null
  /** 入参映射：目标字段 → $.input / $.outputs / $.vars 路径 */
  inputMapping?: Record<string, string>
  /** 出参映射 */
  outputMapping?: Record<string, string>
  /** 循环最多进入循环体的次数；不得超过平台硬顶 */
  maxIterations?: number | null
  /** 次数用尽后的出路：DOWNSTREAM / HUMAN / FAIL */
  onExceeded?: 'DOWNSTREAM' | 'HUMAN' | 'FAIL' | string
  /** 打回反馈从上下文取的路径，如 $.outputs.check */
  feedbackPath?: string
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

/**
 * 工作流自身的运行时限。单位：分钟；不配 = 跟平台硬顶走。
 *
 * <p>随 graph_json 一起落库，不进 settings 表：它属于这份图的运行口径，
 * 换引擎 / 换部署都必须和工作流定义一起走。
 */
export interface WorkflowGraphLimits {
  /** 整段运行上限（分钟）；不得大于平台上限，也不得小于单节点上限 */
  runMinutes?: number | null
  /** 单节点上限（分钟）；不得大于整段上限 */
  nodeMinutes?: number | null
}

/** graph_json 整体结构 */
export interface WorkflowGraph {
  nodes: WorkflowGraphNode[]
  edges: WorkflowGraphEdge[]
  viewport?: { x: number; y: number; zoom: number }
  /** 运行时限（分钟）；缺省表示未配置，运行时按平台硬顶 */
  limits?: WorkflowGraphLimits
}

/** 画布节点 data（VueFlow 节点上的业务载荷） */
export interface WorkflowFlowNodeData {
  code: string
  name: string
  nodeType: WorkflowNodeType | string
  config: WorkflowNodeConfig
  /** 试跑高亮：由 run 详情回填，不参与保存 */
  runStatus?: WorkflowNodeRunStatus | string
  /** AGENT 节点绑定后的 Agent 名称：仅画布展示，不进 graph_json */
  agentName?: string
  /** AGENT 节点绑定后的 Agent 编码：仅画布展示，不进 graph_json */
  agentCode?: string
  /** TOOL 节点绑定后的工具显示名（title 优先）：仅画布展示，不进 graph_json */
  toolTitle?: string
  /** TOOL 节点绑定后的工具技术名（MCP 原始 name）：仅画布展示，不进 graph_json */
  toolName?: string
  /** SUBGRAPH 节点绑定后的被调工作流名称：仅画布展示，不进 graph_json */
  subWorkflowName?: string
  /** SUBGRAPH 节点钉死的版本号：仅画布展示，不进 graph_json */
  subReleaseVersion?: number | null
  /** 必填入参还没配来源的提示（TOOL / AGENT / SUBGRAPH）：仅画布展示，不进 graph_json */
  missingInputHint?: string
}

/** 画布边 data */
export interface WorkflowFlowEdgeData {
  edgeType: WorkflowEdgeType | string
  label?: string | null
  condition?: unknown
  config?: Record<string, unknown>
}

/**
 * 入参映射「来源节点」下拉项。所属域：workflow 的前端画布层。
 * <p>由编辑页按拓扑算出（当前节点的所有祖先），只在 UI 使用，不落库；
 * 落库仍然是「目标字段 → $.input / $.outputs.code / $.vars 路径」的扁平字符串对象。
 */
export interface WorkflowMappingSource {
  /** 画布节点 id */
  id: string
  /** 节点编码：拼 $.outputs.<code> 路径用 */
  code: string
  /** 节点显示名（人话标签，如「审稿 Agent」） */
  name: string
  /** 下拉展示文案：默认就是节点名；不要再拼「类型 · 名字（code）」 */
  label: string
  /** 该节点 outputMapping 的键；有值时才是真实可取的输出字段，空则只有 text */
  outputKeys: string[]
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
  /**
   * 平台硬顶（整分钟）：来自后端 yml 的 agenthub.workflow.wall-ms。
   * 页面只用它做「不得大于平台上限」的校验与说明，不显示键名或毫秒。
   */
  platformMaxMinutes?: number
  /**
   * 单个循环最多进入循环体的次数。画布上的次数不能超过它。
   */
  platformMaxLoopIterations?: number
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

/**
 * 切换默认发布后的结果。
 * 带上该版的图，画布据此恢复；列表接口仍然不下发图。
 */
export interface WorkflowReleaseActivateResult {
  id: number
  workflowId: number
  version: number
  fingerprint: string
  publishNote?: string | null
  publishedBy: number
  publishTime?: string
  graph: WorkflowGraph
  draftUpdateTime?: string
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
  /** 按当前草稿调试时为空 */
  releaseId?: number | null
  /** 按当前草稿调试时为空 */
  releaseVersion?: number | null
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
  /**
   * 与首页工作流卡片同一份载荷。
   * 调试过程只渲染它，不用 nodeRuns 再拼一张时间轴。
   */
  card?: unknown
}

/**
 * 调试试跑的直播卡片快照（GET /api/workflow/runs/{id}/live-card）。
 *
 * <p>卡片字段与详情接口同一份口径，前端直接交给同一张 `WorkflowRunCard`；
 * {@code live=false} 表示这次没有按 run 的直播（未接入 / 已过期 / 不属于当前租户），
 * 调用方应退回详情接口现算的已落库卡片。
 */
export interface WorkflowRunLiveCard {
  live: boolean
  /** run 当前状态；直播第一帧还没出来时可空 */
  status?: WorkflowRunStatus | string | null
  /** 与详情同一份卡片载荷；第一帧之前可空 */
  card?: unknown
  /** 节点运行态，供画布高亮与跟随 */
  nodeRuns?: WorkflowNodeRunRow[]
  errorSummary?: string | null
}

/** run 摘要（历史列表） */
export interface WorkflowRunSummary {
  id: number
  status: WorkflowRunStatus | string
  triggerType: string
  /** 应用触发时的 sys_app.id；试跑为空 */
  appId?: number | null
  /** 按当前草稿调试时为空 */
  releaseId?: number | null
  /** 按当前草稿调试时为空 */
  releaseVersion?: number | null
  /**
   * 草稿调试开始那一刻的默认发布版本号快照。
   * <p>为空表示当时从未发布过；之后又发布了新版也不改写这条记录。
   */
  draftBaseReleaseVersion?: number | null
  /** 这次调试开始时的用户问题（input.query）；旧记录或没有 query 时为空串 */
  query?: string | null
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

/**
 * 某个已发布版本的 START 入参声明（GET /api/workflow/defs/{id}/releases/{releaseId}/input-schema）。
 * <p>画布 SUBGRAPH 节点按这份声明出槽：只认钉死的那个版本，不读对方当前草稿。
 */
export interface WorkflowReleaseInputSchema {
  workflowId: number
  releaseId: number
  /** 版本号，用于展示 */
  version?: number | null
  /** 声明对象；该版本没有声明时为空（保持自由添加，不编造槽） */
  inputSchema?: unknown
  /** 声明里的必填键（properties ∩ required） */
  requiredKeys?: string[]
}

/** OpenAPI 触发响应（POST /api/open/workflow/runs） */
export interface WorkflowAppInvokeResult {
  runId: number
  status: string
  workflowId: number
  releaseId: number
  triggerType: string
}
