/**
 * 工作台对话契约。所属域：runtime（前端）。
 *
 * <p>与 `/api/runtime/workbench/**` 一一对应；只放工作台页展示就绪状态与候选所需的字段，
 * 不复制调试接口的 Agent / 方案配置结构（那是 agent 域的类型）。
 */
import type { AvatarConfig } from '@/types/agent'

/** 默认接待的候选（只用于展示与说明，不参与前端路由）。 */
export interface WorkbenchCandidate {
  /** AGENT / MA（刀5：也可是另一套编排方案）/ WORKFLOW（刀5B：已发布工作流） */
  targetType?: 'AGENT' | 'MA' | 'WORKFLOW'
  agentId?: number
  /** MA 候选的目标方案主键 */
  maDefId?: number
  /** WORKFLOW 候选的目标工作流主键（刀5B） */
  workflowDefId?: number
  /** 展示名：Agent 名称 / 方案名称 / 工作流名称 */
  agentName?: string
  /** Agent 形象配置；MA/工作流候选为空 */
  avatarConfig?: AvatarConfig | null
  /** 该候选负责的意图说明；空表示仅按名称兜底 */
  intentDesc?: string
  /** 方案内子路由键；前端只用于提交换线，不直接展示给用户 */
  routeKey?: string
  sortNo?: number
}

/**
 * 换线下拉里的一个候选（本会话快照方案的启用候选）。
 *
 * <p>`routeKey` 是提交换线请求的定位键，界面上只显示 `agentName`。
 * 刀5：`targetType=MA` 时 `agentId` 为空、`maDefId` 有值，界面按「方案」打标。
 * 刀5B：`targetType=WORKFLOW` 时 `agentId` 为空、`workflowDefId` 有值，界面按「工作流」打标。
 */
export interface WorkbenchRouteOption {
  /** 方案候选的 route_key；换线请求按它定位 */
  routeKey: string
  /** AGENT / MA / WORKFLOW */
  targetType?: 'AGENT' | 'MA' | 'WORKFLOW'
  /** 子 Agent 主键；MA / WORKFLOW 候选为空 */
  agentId?: number
  /** MA 候选的目标方案主键 */
  maDefId?: number
  /** WORKFLOW 候选的目标工作流主键（刀5B） */
  workflowDefId?: number
  /** 候选展示名：Agent 名称 / 方案名称 / 工作流名称 */
  agentName?: string
  /** Agent 形象配置；MA/工作流候选为空 */
  avatarConfig?: AvatarConfig | null
  /** 该候选负责的意图说明；可空 */
  intentDesc?: string
  /** 方案内排序 */
  sortNo?: number
  /**
   * 方案内部的子候选（仅 `targetType=MA` 可能有值）：展示名 + 形象。
   *
   * <p>只用于思考时间线给「方案 → 各子专家」的块取头像；
   * 不参与 @ 面板与换线下拉，也不暴露 route_key。
   */
  children?: WorkbenchChildOption[]
}

/**
 * 方案内部的一个子候选（时间线头像取用）。所属域：runtime（前端）。
 */
export interface WorkbenchChildOption {
  /** 子候选展示名，与时间线「路由到 …」一致 */
  agentName?: string
  /** AGENT / WORKFLOW */
  targetType?: 'AGENT' | 'WORKFLOW'
  /** Agent 形象配置；非 Agent 子候选为空 */
  avatarConfig?: AvatarConfig | null
}

/**
 * 当前会话的可选线路。
 *
 * <p>候选只来自本会话快照方案的启用候选（服务端已过滤停用 / 删除），
 * 前端据此判断「线路已失效」：`currentRouteKey` 有值但命中不到任何候选。
 */
export interface WorkbenchRouteOptions {
  /** 会话当前沿用的 route_key；空表示尚未定线，下一轮交给判路由 */
  currentRouteKey?: string | null
  /** 当前线路的候选展示名；线路已停用 / 删除时为空 */
  currentRouteName?: string | null
  candidates: WorkbenchRouteOption[]
}

/**
 * 工作台就绪状态。
 *
 * <p>`ready=false` 时页面禁用输入、只展示原因并给配置入口；
 * 前端不得自行挑一条别的方案顶上。
 */
export interface WorkbenchConfig {
  ready: boolean
  /** 未就绪原因；ready=true 时为 null */
  reason?: string | null
  /** 默认接待方案；未配置时为 null */
  schemeId?: number | null
  schemeCode?: string | null
  schemeName?: string | null
  template?: string | null
  candidates?: WorkbenchCandidate[]
}
