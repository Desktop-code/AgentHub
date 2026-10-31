/**
 * Agent 知识库绑定接口。所属域：rag。
 * 知识与工具的分工：tool_bind 管「能不能调检索工具」，kb_bind 管「能查哪些库、用什么参数」。
 */
import request from '@/utils/request'
import type { Result } from '@/types'
import type { AgentRerank, KbBindRow } from '@/types/rag'

/** 某 Agent 已绑定的知识库。 */
export function listAgentKbs(agentId: number) {
  return request.get<Result<KbBindRow[]>>(`/api/rag/agents/${agentId}/kbs`)
}

/** 新增绑定。知识库必须对当前用户可见且可用。 */
export function bindAgentKb(agentId: number, data: Record<string, unknown>) {
  return request.post<Result<KbBindRow>>(`/api/rag/agents/${agentId}/kbs`, data)
}

/** 改启停与检索覆盖项。停用后该库不进运行时检索。 */
export function updateAgentKb(agentId: number, bindId: number, data: Record<string, unknown>) {
  return request.put<Result<KbBindRow>>(`/api/rag/agents/${agentId}/kbs/${bindId}`, data)
}

/** 解绑。 */
export function unbindAgentKb(agentId: number, bindId: number) {
  return request.delete<Result<null>>(`/api/rag/agents/${agentId}/kbs/${bindId}`)
}

/**
 * 读 Agent 检索链路的重排配置。所属域：rag。
 * 重排的配置入口唯一在「知识库」Tab；模型配置的用途槽不再提供 RERANK 写入口。
 */
export function getAgentRerank(agentId: number) {
  return request.get<Result<AgentRerank>>(`/api/rag/agents/${agentId}/retrieval/rerank`)
}

/**
 * 设置或清空重排模型。传 0 表示关闭重排。
 *
 * @param agentId         Agent 主键
 * @param modelInstanceId 用途为 RERANK 的实例；0 = 关闭
 */
export function saveAgentRerank(agentId: number, modelInstanceId: number) {
  return request.put<Result<AgentRerank>>(`/api/rag/agents/${agentId}/retrieval/rerank`, { modelInstanceId })
}
