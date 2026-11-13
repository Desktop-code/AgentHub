/**
 * 工作台对话接口。所属域：runtime（前端）。
 *
 * <p>正式入口只走这一组：会话由服务端按默认接待方案创建，前端<b>不传</b> agentId / maId；
 * 调试入口 `/api/runtime/trial/**` 仍归 Agent 抽屉与 MA 运行页，两套列表不混用。
 *
 * <p>SSE 解析与试跑共用 {@link streamSessionMessage}，只是路径前缀换成 workbench。
 */
import request from '@/utils/request'
import {
  streamSessionMessage,
  type TrialChildPlanPendingChunk,
  type TrialStreamHandlers
} from '@/api/runtime'
import type { PageResult, Result } from '@/types'
import type { TrialEvent, TrialPlanView, TrialSession } from '@/types/runtime'
import type { WorkbenchConfig, WorkbenchRouteOptions } from '@/types/workbench'

/** 工作台接口前缀；SSE 与普通请求共用，避免两处写死后分叉。 */
const PREFIX = '/api/runtime/workbench'

/**
 * 就绪状态与默认接待候选。
 *
 * @return ready=false 时页面禁用输入并展示 reason
 */
export function getWorkbenchConfig() {
  return request.get<Result<WorkbenchConfig>>(`${PREFIX}/config`)
}

/**
 * 新建工作台会话。请求体为空：归属由服务端默认接待方案决定。
 *
 * @return 新会话（entryChannel=WORKBENCH）
 */
export function createWorkbenchSession() {
  return request.post<Result<TrialSession>>(`${PREFIX}/sessions`)
}

/**
 * 当前用户的工作台会话页。
 *
 * <p>服务端强制 `entry_channel='WORKBENCH'`，因此这里不需要（也不能）传 agentId / maId。
 *
 * @param params current 页码（从 1 开始）、size 页大小
 * @return 会话页
 */
export function pageWorkbenchSessions(params: { current: number; size: number }) {
  return request.get<Result<PageResult<TrialSession>>>(`${PREFIX}/sessions`, { params })
}

/** 会话元数据。 */
export function getWorkbenchSession(id: number) {
  return request.get<Result<TrialSession>>(`${PREFIX}/sessions/${id}`)
}

/** 已落库事件时间线。 */
export function listWorkbenchEvents(id: number) {
  return request.get<Result<TrialEvent[]>>(`${PREFIX}/sessions/${id}/events`)
}

/**
 * 进行中一轮的实时快照。
 *
 * <p>刷新后前端已失去 SSE、事件又要等整轮落库；这条接口从服务端内存读增量，
 * 用于恢复「边跑边看」。没有在跑的一轮时返回空数组，调用方回退到 events。
 */
export function listWorkbenchLiveEvents(id: number) {
  return request.get<Result<TrialEvent[]>>(`${PREFIX}/sessions/${id}/live-events`)
}

/**
 * 边跑边收 SSE。
 *
 * @param id             会话主键
 * @param content        用户输入
 * @param handlers       增量回调
 * @param signal         关闭页面时的中止信号
 * @param images         图片 data URI 数组，可空
 * @param idempotencyKey 业务幂等键，可空
 */
export async function streamWorkbenchMessage(
  id: number,
  content: string,
  handlers: TrialStreamHandlers,
  signal?: AbortSignal,
  images?: string[],
  idempotencyKey?: string
) {
  return streamSessionMessage(PREFIX, id, content, handlers, signal, images, idempotencyKey)
}

/** 改左栏标题（写入后不再被自动生成覆盖）。 */
export function renameWorkbenchSession(id: number, title: string) {
  return request.post<Result<TrialSession>>(`${PREFIX}/sessions/${id}/title`, { title })
}

/** 清空已落库事件，保留会话与标题。 */
export function clearWorkbenchSession(id: number) {
  return request.post<Result<TrialSession>>(`${PREFIX}/sessions/${id}/clear`)
}

/** 取消尚未完成的会话（不打断正在执行的一轮）。 */
export function cancelWorkbenchSession(id: number) {
  return request.post<Result<TrialSession>>(`${PREFIX}/sessions/${id}/cancel`)
}

/** 逻辑删除；列表不再返回。 */
export function deleteWorkbenchSession(id: number) {
  return request.delete<Result<void>>(`${PREFIX}/sessions/${id}`)
}

/** 待批准计划；没有时 data 为 null。 */
export function getPendingWorkbenchPlan(id: number) {
  return request.get<Result<TrialPlanView | null>>(`${PREFIX}/sessions/${id}/plan/pending`)
}

/** 编排根下当前待批的子专家计划；没有时 data 为 null。 */
export function getPendingWorkbenchChildPlan(id: number) {
  return request.get<Result<TrialChildPlanPendingChunk | null>>(`${PREFIX}/sessions/${id}/plan/child-pending`)
}

/**
 * 批准待执行计划。传 routeKey 表示批准的是子专家计划。
 *
 * <p>接口要跑完整轮才返回，前端必须给足超时；期间界面先本地切到「执行中」。
 */
export function approveWorkbenchPlan(id: number, routeKey?: string) {
  return request.post<Result<unknown>>(
    `${PREFIX}/sessions/${id}/plan/approve${routeKey ? `?routeKey=${encodeURIComponent(routeKey)}` : ''}`,
    {},
    { timeout: 310000 }
  )
}

/** 取消待执行计划（不执行任何步骤）。传 routeKey 表示拒绝子专家计划。 */
export function rejectWorkbenchPlan(id: number, routeKey?: string) {
  return request.post<Result<unknown>>(
    `${PREFIX}/sessions/${id}/plan/reject${routeKey ? `?routeKey=${encodeURIComponent(routeKey)}` : ''}`
  )
}

/**
 * 当前会话的可选线路（刀3）。候选只来自本会话快照方案的启用候选。
 *
 * @param id 会话主键
 * @return 当前沿用线路 + 可换线的候选；停用 / 删除的候选不会出现
 */
export function getWorkbenchRoutes(id: number) {
  return request.get<Result<WorkbenchRouteOptions>>(`${PREFIX}/sessions/${id}/routes`)
}

/**
 * 手动换线（刀3，仅工作台）。
 *
 * <p>服务端校验目标属于本会话快照方案且仍可用，成功后写沿用线路并置一次性强制标记：
 * 只影响下一轮，跑完即清，不会把会话永久锁死。
 *
 * @param id       会话主键
 * @param routeKey 目标候选的 route_key
 * @return 更新后的会话
 */
export function switchWorkbenchRoute(id: number, routeKey: string) {
  return request.put<Result<TrialSession>>(`${PREFIX}/sessions/${id}/route`, { routeKey })
}
