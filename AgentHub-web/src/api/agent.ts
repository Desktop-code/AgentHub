/**
 * Agent 配置接口。所属域：agent。
 * 定义、三类绑定和一层子路由都在这里。试跑不调用后端。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  AgentCard,
  AgentDetail,
  AgentRouteRow,
  AgentSave,
  ModelBind,
  PlatformPrefix,
  RuntimeLimitsView,
  SkillBind,
  ToolBind
} from '@/types/agent'

/** 分页。size 不超过 100。 */
export function pageAgents(params: {
  current: number
  size: number
  keyword?: string
  runMode?: string
  status?: number
  scope?: 'SINGLE'
}) {
  return request.get<Result<PageResult<AgentCard>>>('/api/agent/agents', { params })
}

/** 详情，含绑定计数和只读平台前缀，不含绑定明细。systemPrompt 只是用户段。 */
export function getAgent(id: number) {
  return request.get<Result<AgentDetail>>(`/api/agent/agents/${id}`)
}

/** 新建页和编辑页切换运行模式时用。不落库。 */
export function getPlatformPrefixes() {
  return request.get<Result<PlatformPrefix>>('/api/agent/agents/platform-prefix')
}

/** 运行限制平台默认与硬顶。配置页占位；保存超顶后端再拦。 */
export function getRuntimeLimits() {
  return request.get<Result<RuntimeLimitsView>>('/api/agent/agents/runtime-limits')
}

/** 新建。未传 runMode 时后端默认 REACT。 */
export function createAgent(data: AgentSave) {
  return request.post<Result<AgentDetail>>('/api/agent/agents', data)
}

/**
 * 复制 Agent。所属域：agent。
 * <p>
 * 只提交名称与编码：运行模式、运行限制、编排策略与 CHAT 的 L3 四列
 * 全部由后端从源 Agent 继承，避免前端漏传把复制品的模板配置打回空。
 *
 * @param id   源 Agent 主键
 * @param data 复制品的名称与编码
 */
export function copyAgent(id: number, data: { name: string; code: string }) {
  return request.post<Result<AgentDetail>>(`/api/agent/agents/${id}/copy`, data)
}

/** 保存基本信息，不改启停。 */
export function updateAgent(id: number, data: AgentSave) {
  return request.put<Result<AgentDetail>>(`/api/agent/agents/${id}`, data)
}

/**
 * 上传 Agent 自定义形象（jpg/png/webp，≤2MB）。所属域：agent。
 * <p>只落盘并回 URL，不写库；由配置页点「确定」后随 Agent 保存。
 *
 * @param file 图片文件
 */
export function uploadAgentAvatar(file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<Result<{ url: string }>>('/api/agent/agents/avatar-upload', form)
}

/** 逻辑删除定义及其绑定、路由。 */
export function deleteAgent(id: number) {
  return request.delete<Result<null>>(`/api/agent/agents/${id}`)
}

/** 启停。1 正常，0 停用。 */
export function changeAgentStatus(id: number, status: number) {
  return request.put<Result<AgentDetail>>(`/api/agent/agents/${id}/status`, { status })
}

/** 模型绑定列表。 */
export function listModelBinds(agentId: number) {
  return request.get<Result<ModelBind[]>>(`/api/agent/agents/${agentId}/model-binds`)
}

/** 添加模型绑定。 */
export function createModelBind(agentId: number, data: { modelInstanceId: number; purpose?: string; priority?: number }) {
  return request.post<Result<ModelBind>>(`/api/agent/agents/${agentId}/model-binds`, data)
}

/** 改用途、优先级或启停。prompt 传了才改 L3 四列（null=不动）。 */
export function updateModelBind(
  agentId: number,
  bindId: number,
  data: {
    purpose?: string
    priority: number
    status: number
    prompt?: { templateId?: number | null; templateVersion?: number | null; override?: Record<string, string>; promptExtra?: string }
  }
) {
  return request.put<Result<ModelBind>>(`/api/agent/agents/${agentId}/model-binds/${bindId}`, data)
}

/** 移除模型绑定。 */
export function deleteModelBind(agentId: number, bindId: number) {
  return request.delete<Result<null>>(`/api/agent/agents/${agentId}/model-binds/${bindId}`)
}

/** 工具绑定列表。 */
export function listToolBinds(agentId: number) {
  return request.get<Result<ToolBind[]>>(`/api/agent/agents/${agentId}/tool-binds`)
}

/** 添加工具绑定。 */
export function createToolBind(agentId: number, data: { toolItemId: number }) {
  return request.post<Result<ToolBind>>(`/api/agent/agents/${agentId}/tool-binds`, data)
}

/** 改工具绑定启停。 */
export function updateToolBind(agentId: number, bindId: number, status: number) {
  return request.put<Result<ToolBind>>(`/api/agent/agents/${agentId}/tool-binds/${bindId}`, { status })
}

/** 移除工具绑定。 */
export function deleteToolBind(agentId: number, bindId: number) {
  return request.delete<Result<null>>(`/api/agent/agents/${agentId}/tool-binds/${bindId}`)
}

/** Skill 绑定列表，没有正文。 */
export function listSkillBinds(agentId: number) {
  return request.get<Result<SkillBind[]>>(`/api/agent/agents/${agentId}/skill-binds`)
}

/** 添加 Skill 绑定。 */
export function createSkillBind(agentId: number, data: { skillId: number }) {
  return request.post<Result<SkillBind>>(`/api/agent/agents/${agentId}/skill-binds`, data)
}

/** 改 Skill 绑定启停。 */
export function updateSkillBind(agentId: number, bindId: number, status: number) {
  return request.put<Result<SkillBind>>(`/api/agent/agents/${agentId}/skill-binds/${bindId}`, { status })
}

/** 移除 Skill 绑定。 */
export function deleteSkillBind(agentId: number, bindId: number) {
  return request.delete<Result<null>>(`/api/agent/agents/${agentId}/skill-binds/${bindId}`)
}

/** 一层子路由。父节点是 REACT 时也可能还有保留行。 */
export function listRoutes(agentId: number) {
  return request.get<Result<AgentRouteRow[]>>(`/api/agent/agents/${agentId}/routes`)
}

/** 添加子 Agent。意图必填；route_key / sort 可空由后端生成。 */
export function createRoute(
  agentId: number,
  data: { childAgentId: number; routeKey?: string; intent: string; sortOrder?: number }
) {
  return request.post<Result<AgentRouteRow>>(`/api/agent/agents/${agentId}/routes`, data)
}

/** 改路由键、意图、排序或启停。空 route_key 由后端补全。 */
export function updateRoute(
  agentId: number,
  routeId: number,
  data: { routeKey?: string; intent?: string; sortOrder: number; status: number }
) {
  return request.put<Result<AgentRouteRow>>(`/api/agent/agents/${agentId}/routes/${routeId}`, data)
}

/** 移除子路由。父节点必须当前是 ROUTER。 */
export function deleteRoute(agentId: number, routeId: number) {
  return request.delete<Result<null>>(`/api/agent/agents/${agentId}/routes/${routeId}`)
}
