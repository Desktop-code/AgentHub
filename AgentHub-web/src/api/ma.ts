/**
 * 多智能体列表接口。所属域：前端 agent。
 *
 * <p>只读薄封装：后端把「编排根 + 模板 + 候选数」聚合成一页，
 * 前端不再按 Agent 分页逐条补 route 计数（避免 N+1 与口径漂移）。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { MaListRow } from '@/types/ma'

/**
 * 分页列出编排根（run_mode=ROUTER）。
 *
 * @param params 页码/条数/关键字（名称或编码）/启停
 * @return 多智能体列表行
 */
export function pageMultiAgents(params: {
  current: number
  size: number
  keyword?: string
  status?: number
}) {
  return request.get<Result<PageResult<MaListRow>>>('/api/agent/multi-agents', { params })
}
