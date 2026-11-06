/**
 * 多智能体方案类型。所属域：前端 agent。
 *
 * <p>MA-P2 刀1 起，列表一行对应一个 ma_def 方案，不再是「ROUTER Agent 聚合」：
 * 方案可以绑一个 ROUTER（试跑走旧执行器），也可以是虚拟根（按方案直接试跑）。
 */
export interface MaDefRow {
  /** ma_def 主键；编辑/启停/删除用 */
  id: number
  /** 租户内唯一编码 */
  code: string
  name: string
  /** 六值模板：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP | ROUTER_THEN_SEQUENCE | COMPETE */
  template: string
  /** 1 启用 / 0 停用 */
  enabled: number
  /** 绑定的 ROUTER 主键；null＝虚拟根 */
  rootAgentId: number | null
  /** 绑定的 ROUTER 名称；虚拟根为 null */
  rootAgentName: string | null
  /** 启用中的候选数 */
  candidateEnabledCount: number
  /** 方案版本，每次保存 +1 */
  version: number
  updateTime?: string
}

/** 与后端 AgentRuntimeConfigResp 同构的编排配置 */
export interface MaRuntimeConfig {
  maTemplate: string
  /** ISOLATED | PRIOR_SUMMARY_ONLY | SHARED_SCRATCH */
  maContextPolicy: string
  maAllowParentHistoryWhenSingle: boolean
  multiIntentEnabled: boolean
  /** CONCAT | SUMMARIZE | VOTE */
  answerMode: string
  maxRouteChildren: number
  requirePlanConfirm: boolean
  maxPlanSteps: number
  maxStepRetries: number
  /** REACT / PLAN_AND_EXECUTE；恒含 REACT */
  maChildRunModesAllowed: string[]
  maCriticMaxRounds: number
  maCriticAgentId: number | null
  /** 竞争选优（模板=COMPETE）策略：JUDGE_PICK（默认）/ FIRST_SUCCESS */
  competeStrategy?: string
}

/** 方案候选回显 */
export interface MaCandidate {
  id?: number
  agentId: number
  agentName?: string
  agentCode?: string
  agentRunMode?: string
  agentStatus?: number
  agentAllowOrchestrated?: number
  routeKey: string
  intentDesc: string
  sortNo?: number
  /** 1 启用 / 0 停用 */
  enabled: number
}

/** 方案详情（编辑抽屉回填用）；字段对齐 MaDefDetailResp，不含列表聚合列 */
export interface MaDefDetail {
  id: number
  code: string
  name: string
  template: string
  config: MaRuntimeConfig
  rootAgentId: number | null
  rootAgentName: string | null
  enabled: number
  version: number
  candidates: MaCandidate[]
  createTime?: string
  updateTime?: string
}

/** 候选保存请求 */
export interface MaCandidateSave {
  agentId: number
  /**
   * 可空。前端不再提交：路由键由服务端按子 Agent 编码生成（同方案内唯一，冲突回落 child_{id}），
   * 保留该字段仅为兼容旧调用。
   */
  routeKey?: string
  intentDesc: string
  sortNo?: number
  enabled?: number
}

/** 保存请求；候选嵌在方案里整份提交 */
export interface MaDefSave {
  code: string
  name: string
  template: string
  config?: Partial<MaRuntimeConfig>
  rootAgentId?: number | null
  enabled?: number
  candidates: MaCandidateSave[]
}
