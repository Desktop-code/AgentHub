/**
 * 调用追踪接口。所属域：obs。
 * 左栏会话、中栏提问、右栏步骤。只读。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { ObsSession, ObsSpan, ObsTrace } from '@/types/obs'

/** 左栏。q 匹配预览或会话编号，agentId 为空表示全部。 */
export function pageObsSessions(params: { current: number; size: number; q?: string; agentId?: number }) {
  return request.get<Result<PageResult<ObsSession>>>('/api/obs/sessions', { params })
}

/** 中栏。q 模糊匹配提问全文。 */
export function pageObsTraces(sessionId: number, params: { current: number; size: number; q?: string }) {
  return request.get<Result<PageResult<ObsTrace>>>(`/api/obs/sessions/${sessionId}/traces`, { params })
}

/**
 * 按工作流定义查调用记录（工作流列表「运行记录」入口）。
 * workflowId 是工作流定义 id；后端会换算成该定义最近的 run 再过滤 trace。
 */
export function pageObsTracesByWorkflow(params: {
  current: number
  size: number
  workflowId: number
  q?: string
}) {
  return request.get<Result<PageResult<ObsTrace>>>('/api/obs/traces', { params })
}

/** 右栏。按 seq 升序的完整步骤，含父子深度。 */
export function listObsSpans(traceId: string) {
  return request.get<Result<ObsSpan[]>>(`/api/obs/traces/${traceId}/spans`)
}
