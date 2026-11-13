/**
 * 多智能体方案类型。所属域：前端 agent。
 *
 * <p>MA-P2 刀1 起，列表一行对应一个 ma_def 方案，不再是「ROUTER Agent 聚合」。
 * 本刀起方案以 ma_def 自己为根（模型槽 / ROUTER 提示词 / 候选池都在方案上），
 * 与 Agent 管理里的单兵彻底断开；形象也配在方案自己身上。
 */
import type { AvatarConfig } from '@/types/agent'

export interface MaDefRow {
  /** ma_def 主键；编辑/启停/删除用 */
  id: number
  /** 租户内唯一编码 */
  code: string
  name: string
  /** 方案说明：被当作路由目标时的意图说明；可空 */
  description?: string
  /** 六值模板：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP | ROUTER_THEN_SEQUENCE | COMPETE
   *  <p>库内还可能出现入口类型 ENTRY_ROUTER（平台唯一 Router），页面据此锁定身份字段 */
  template: string
  /** 1 启用 / 0 停用 */
  enabled: number
  /** 方案自己的形象；null/缺省＝未配置，前端按方案名走 8 套机器人兜底 */
  avatarConfig?: AvatarConfig | null
  /** 历史绑根字段：已停用，列表恒回 null；前端不再据此决定运行入口 */
  rootAgentId: number | null
  /** 历史绑根名称：已停用，恒为 null */
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
  /**
   * 判路由 / 汇总用的模型实例主键（model_instance.id）。
   *
   * <p>方案以自己为根，没有 Agent 模型槽可回落：留空时执行器回落平台 ROUTER / CHAT 能力槽。
   * 对话路由（对话入口）与普通场景 Router 共用这一个字段，没有第二套入口模型配置。
   */
  maJudgeModelInstanceId?: number | null
  /** 判路由模型兼容别名；执行以 id 为准，本字段只回显 */
  maJudgeModel?: string | null
  /**
   * ROUTER 提示词：钉住的已发布模板主键 / 版本。
   *
   * <p>与专家配提示词同一套三段式：模板锁定段不可改，只覆盖 soft 段 + 追加要求。
   * 必须成对提交；只给一半时服务端按「未钉死」处理。
   */
  maJudgePromptTemplateId?: number | null
  maJudgePromptTemplateVersion?: number | null
  /** ROUTER 提示词的 soft 段覆盖（seg_key → 文案）；未钉模板时恒为空 */
  maJudgePromptOverride?: Record<string, string>
  /** ROUTER 提示词追加区（append-only） */
  maJudgePromptExtra?: string
}

/** 方案编辑页「判路由模型」下拉项：只含启用中、用途属于对话形态的实例 */
export interface MaJudgeModelOption {
  id: number
  displayName?: string
  modelId?: string
  capability?: string
  status?: number
}

/** 方案候选回显：子 Agent、另一套编排方案（刀5）或已发布工作流（刀5B） */
export interface MaCandidate {
  id?: number
  /** AGENT / MA / WORKFLOW；历史行为 AGENT */
  targetType?: 'AGENT' | 'MA' | 'WORKFLOW'
  /** 子 Agent 主键；MA / WORKFLOW 候选为 null */
  agentId: number | null
  agentName?: string
  agentCode?: string
  agentRunMode?: string
  agentStatus?: number
  agentAllowOrchestrated?: number
  /** MA 目标方案主键；targetType=MA 时非空 */
  maDefId?: number | null
  maName?: string
  maCode?: string
  /** 目标方案模板码，只读展示 */
  maTemplate?: string
  /** 目标方案启用状态：1 启用 / 0 停用 */
  maStatus?: number | null
  /** WORKFLOW 目标工作流主键；targetType=WORKFLOW 时非空 */
  workflowDefId?: number | null
  workflowName?: string
  workflowCode?: string
  /** 目标工作流当前是否仍有可用发布；false 表示线路已失效 */
  workflowHasRelease?: boolean | null
  routeKey: string
  /** 只读：Agent 简介（agent_def.description）或方案说明（ma_def.description） */
  intentDesc?: string
  /** true 表示来源还没填说明，界面要标出来 */
  intentDescMissing?: boolean
  sortNo?: number
  /** 1 启用 / 0 停用 */
  enabled: number
}

/** 方案详情（编辑抽屉回填用）；字段对齐 MaDefDetailResp，不含列表聚合列 */
export interface MaDefDetail {
  id: number
  code: string
  name: string
  /** 方案说明：被当作路由目标时的意图说明；可空 */
  description?: string
  template: string
  config: MaRuntimeConfig
  /** 方案自己的形象；null＝未配置，按方案名走 8 套机器人兜底 */
  avatarConfig?: AvatarConfig | null
  /** 历史绑根字段：已停用，详情恒回 null */
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
  /** 子 Agent 主键；targetType=MA / WORKFLOW 时留空 */
  agentId?: number | null
  /** AGENT（默认）/ MA / WORKFLOW；后两者必须给对应目标主键 */
  targetType?: 'AGENT' | 'MA' | 'WORKFLOW'
  /** MA 目标方案主键；targetType=MA 时必填 */
  maDefId?: number | null
  /** 工作流目标主键；targetType=WORKFLOW 时必填 */
  workflowDefId?: number | null
  /**
   * 可空。前端不再提交：路由键由服务端按子 Agent 编码生成（同方案内唯一，冲突回落 child_{id}），
   * 保留该字段仅为兼容旧调用。
   */
  routeKey?: string
  /**
   * 已废止：意图说明只来自 Agent 简介 / 方案说明 / 工作流说明，服务端会覆盖；前端不再提交。
   */
  intentDesc?: string
  sortNo?: number
  enabled?: number
}

/**
 * 抽屉里的候选行：保存请求字段 + 回显名称。
 *
 * <p>候选表在入口两栏与普通方案 Tab 里共用同一份结构（{@code MaRoutePoolPanel}），
 * 因此类型提到这里共享，避免两处各写一份字段定义后漂移。
 * sortNo 为空时由后端按 max(sort_no)+10 生成。
 *
 * <p>intentDesc / intentDescMissing 只用于展示：意图说明真实来源是 Agent 简介、
 * 方案说明或工作流说明，前端不编辑、不提交（服务端会用来源覆盖）。
 */
export interface MaCandidateRow {
  /** AGENT（默认）/ MA / WORKFLOW */
  targetType?: 'AGENT' | 'MA' | 'WORKFLOW'
  agentId: number | null
  agentName?: string
  agentCode?: string
  agentRunMode?: string
  agentStatus?: number
  /** MA 目标方案主键 */
  maDefId?: number | null
  maName?: string
  maCode?: string
  maTemplate?: string
  maStatus?: number | null
  /** WORKFLOW 目标工作流主键 */
  workflowDefId?: number | null
  workflowName?: string
  workflowCode?: string
  /**
   * 不保留 routeKey：路由键是服务端派生数据（目标编码，冲突回落 child_{id}），
   * 前端不展示、不提交，避免切换目标后旧键残留导致指向错误。
   */
  intentDesc: string
  /** true 表示来源还没填说明，界面标红 */
  intentDescMissing?: boolean
  sortNo?: number
  enabled: number
}

/** 保存请求；候选嵌在方案里整份提交 */
export interface MaDefSave {
  code: string
  name: string
  /** 方案说明：被当作路由目标时的意图说明；空说明的方案加不进路由池 */
  description?: string
  template: string
  config?: Partial<MaRuntimeConfig>
  /** 方案自己的形象；null＝清空（按方案名兜底） */
  avatarConfig?: AvatarConfig | null
  /** 历史绑根字段：服务端已忽略并恒写 null，前端不再提交 */
  rootAgentId?: number | null
  enabled?: number
  candidates: MaCandidateSave[]
}
