/**
 * Agent 配置接口类型。所属域：agent。绑定行不包含模型 Key 或 Skill 正文。
 */

export interface AgentCard {
  id: number
  name: string
  code: string
  description?: string
  runMode: string
  /** 是否允许被编排挂为子专家；ROUTER 恒为 false */
  allowOrchestrated?: boolean
  status: number
  updateTime?: string
}

/**
 * 详情变更时回传的卡片字段子集。
 * 与左栏展示的列一一对应；外层用它原地修补列表行，
 * 避免为刷新一个名称而重置分页、让左栏滚回顶部。
 */
export type AgentCardPatch = Pick<AgentCard, 'id' | 'name' | 'code' | 'runMode' | 'status' | 'description' | 'updateTime'>

export interface AgentDetail {
  id: number
  name: string
  code: string
  description?: string
  runMode: string
  /** 是否允许被编排挂为子专家；ROUTER 恒为 false（开关禁用且为关） */
  allowOrchestrated?: boolean
  /** 用户业务段，不含平台前缀 */
  systemPrompt?: string
  /**
   * L3 提示词回显。supported=false 时（Router 模式）整个区块隐藏，
   * 其提示词改到「模型配置」的 ROUTER 用途槽配置。
   */
  prompt?: PromptBinding
  /** 当前运行模式的只读平台前缀 */
  platformPrefix?: string
  /** 保存响应里的告警。查询详情一般为空 */
  warnings?: string[]
  /** 运行限制覆盖，空/undefined=跟平台 */
  timeoutMs?: number | null
  maxRounds?: number | null
  toolTimeoutMs?: number | null
  toolMaxRetries?: number | null
  maxToolCalls?: number | null
  llmTimeoutMs?: number | null
  maxLlmCalls?: number | null
  consecutiveFailAbort?: number | null
  toolResultMaxChars?: number | null
  /** Router 运行配置；库空时后端仍回默认 */
  runtimeConfig?: AgentRuntimeConfig
  status: number
  remark?: string
  modelBindCount: number
  toolBindCount: number
  skillBindCount: number
  routeCount: number
  createTime?: string
  updateTime?: string
}

/** Router 多意图 / 终答模式等；进 runtime_config_json */
export interface AgentRuntimeConfig {
  /** 允许多意图路由；新建 ROUTER 默认 true */
  multiIntentEnabled?: boolean
  /** CONCAT | SUMMARIZE；默认 CONCAT；编排 Tab 的「结果合成」 */
  answerMode?: 'CONCAT' | 'SUMMARIZE' | string
  /** 每轮最多子数；默认 3，硬顶 5；编排 Tab 的「并行上限」 */
  maxRouteChildren?: number
  /** 协作模式；ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP | ROUTER_THEN_SEQUENCE，非法/空回显 ROUTER_PARALLEL */
  maTemplate?: string
  /**
   * 上下文共享；ISOLATED（默认）| PRIOR_SUMMARY_ONLY（仅串行模板：固定串行 / 路由后串行）|
   * SHARED_SCRATCH（共享草稿区，五值模板都可选；与本字段单选的前置摘要互斥）。
   */
  maContextPolicy?: string
  /** 单任务时是否继承父会话历史；默认 true */
  maAllowParentHistoryWhenSingle?: boolean
  /** 计划执行：规划后是否需要人工批准才继续；默认 false（自动执行） */
  requirePlanConfirm?: boolean
  /** 计划执行：计划步数上限；默认 8，硬顶 20 */
  maxPlanSteps?: number
  /** 计划执行：单步失败重试次数；默认 0，硬顶 3 */
  maxStepRetries?: number
  /**
   * 允许挂为候选子的运行模式；至少含 REACT，可含 PLAN_AND_EXECUTE。
   * 缺省/空按仅 REACT（与服务端同口径）；ROUTER 恒不在其中。
   */
  maChildRunModesAllowed?: string[]
  /**
   * 评审环（CRITIC_LOOP）：最大评审轮次，默认 1，服务端夹到 1～3。
   * 一轮 = 一次评审判定 +（若 REVISE）一次执行者回修。
   */
  maCriticMaxRounds?: number
  /**
   * 评审环：评审专家 agentId；模板为 CRITIC_LOOP 时必填，且不能与执行者相同。
   * 非评审环模板也保留该值，切回时可直接复用。
   */
  maCriticAgentId?: number | null
}

/** 平台运行限制默认与硬顶 */
export interface LimitBoundView {
  defaultValue: number
  max: number
}

export interface RuntimeLimitsView {
  timeoutMs: LimitBoundView
  maxRounds: LimitBoundView
  toolTimeoutMs: LimitBoundView
  toolMaxRetries: LimitBoundView
  maxToolCalls: LimitBoundView
  llmTimeoutMs: LimitBoundView
  maxLlmCalls: LimitBoundView
  consecutiveFailAbort: LimitBoundView
  toolResultMaxChars: LimitBoundView
}

/** 各运行模式的平台前缀，只读 */
export interface PlatformPrefix {
  react: string
  router: string
  /** PLAN_AND_EXECUTE：与 react 相同（基础约束，不加路由句） */
  plan?: string
}

export interface AgentSave {
  name: string
  code: string
  description?: string
  runMode: string
  /** 允许被编排调用；未传按模式默认（ROUTER→false，其它→true），ROUTER 服务端强制 false */
  allowOrchestrated?: boolean
  systemPrompt?: string
  /** L3 提示词挂接；不传=不改现有配置，传空对象=清空模板与覆盖 */
  prompt?: PromptBinding
  remark?: string
  timeoutMs?: number | null
  maxRounds?: number | null
  toolTimeoutMs?: number | null
  toolMaxRetries?: number | null
  maxToolCalls?: number | null
  llmTimeoutMs?: number | null
  maxLlmCalls?: number | null
  consecutiveFailAbort?: number | null
  toolResultMaxChars?: number | null
  runtimeConfig?: AgentRuntimeConfig
}

export interface ModelBind {
  id: number
  agentId: number
  modelInstanceId: number
  displayName?: string
  modelId?: string
  capability?: string
  purpose?: string
  /** 有效用途（purpose 或实例 capability） */
  effectivePurpose?: string
  /** 本槽 #1/#2/#3 */
  slotRank?: number | null
  priority: number
  status: number
  targetStatus?: number
  /**
   * 非 CHAT 用途的 L3 提示词回显；用途无模板维度（VISION / ASR 等）时为 null。
   * 只有「主模型」（槽内第一条）承载提示词，其余降级行仅透传。
   */
  prompt?: PromptBinding | null
}

/** L3 模板版本下拉项（只含已发布版本） */
export interface PromptTemplateOption {
  id: number
  templateKey: string
  name: string
  purpose: string
  locale: string
  version: number
  /** 1 表示平台当前生效版本；旧钉死版据此提示「有可升级版本」 */
  isActive: number
  updateTime?: string
}

/** L3 表单段落；mutable=0 为 core（只读），=1 为 soft（可覆盖） */
export interface PromptTemplateSegment {
  segKey: string
  displayName: string
  sortNo: number
  mutable: number
  required: number
  content: string
}

/** 钉死版模板 + 段落 */
export interface PromptTemplateView {
  id: number
  templateKey: string
  name: string
  purpose: string
  locale: string
  version: number
  isActive: number
  segments: PromptTemplateSegment[]
}

/** L3 四列的值；提交与回显共用一份结构 */
export interface PromptBinding {
  /** 钉死模板主键；null/空=不挂模板 */
  templateId?: number | null
  /** 钉死版本；templateId 非空时必填 */
  templateVersion?: number | null
  /** soft 段覆盖：segKey → 文案 */
  override?: Record<string, string>
  /** 追加区（append-only，不能覆盖锁定段） */
  promptExtra?: string
  /** 该用途是否支持挂模板；false 时整个区块不渲染 */
  supported?: boolean
}

/** 预览结果；正文由后端 PromptAssembler 组装，不调用模型 */
export interface PromptPreviewResult {
  text: string
  purpose: string
  locale: string
  constraintVersion?: number
  templateKey?: string
  templateVersion?: number
  segmentCount?: number
  overriddenSegmentCount?: number
  truncated?: boolean
  notices?: string[]
}

export interface ToolBind {
  id: number
  agentId: number
  toolItemId: number
  name?: string
  title?: string
  sourceName?: string
  status: number
  targetStatus?: number
  /** ROUTER 绑过多时的保存警告 */
  warning?: string
}

export interface SkillBind {
  id: number
  agentId: number
  skillId: number
  name?: string
  title?: string
  status: number
  targetStatus?: number
  /** ROUTER 绑过多时的保存警告 */
  warning?: string
}

export interface AgentRouteRow {
  id: number
  parentAgentId: number
  childAgentId: number
  childName?: string
  childCode?: string
  childRunMode?: string
  childStatus?: number
  /** 子 Agent 是否允许被编排调用；0=禁止，前端校验据此提前报 error */
  childAllowOrchestrated?: number
  routeKey?: string
  intent?: string
  sortOrder: number
  status: number
}
