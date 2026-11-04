/**
 * 工作流定义 / 草稿 / 校验 / 发布 / 试跑接口。所属域：workflow。
 * <p>定义路径前缀 /api/workflow/defs；试跑 /api/workflow/runs；人工待办 /api/workflow/human-tasks。
 * <p>试跑只绑已发布版本；权限码 workflow:run:trial、workflow:human:act 由后端注解拦截。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  WorkflowAppGrantItem,
  WorkflowDefRow,
  WorkflowDraftResp,
  WorkflowGraph,
  WorkflowHumanTaskItem,
  WorkflowReleaseRow,
  WorkflowRunDetail,
  WorkflowRunSummary,
  WorkflowValidateResp
} from '@/types/workflow'

/** 分页查询定义；keyword 匹配 code / 名称 */
export function pageWorkflowDefs(params: {
  current: number
  size: number
  keyword?: string
  status?: string
}) {
  return request.get<Result<PageResult<WorkflowDefRow>>>('/api/workflow/defs', { params })
}

/** 新建定义；后端会自动生成「1 START + 1 END」最小草稿 */
export function createWorkflowDef(data: { code: string; name: string; description?: string }) {
  return request.post<Result<WorkflowDefRow>>('/api/workflow/defs', data)
}

/** 定义详情 */
export function getWorkflowDef(id: number) {
  return request.get<Result<WorkflowDefRow>>(`/api/workflow/defs/${id}`)
}

/** 改名称与说明；code 不可改 */
export function updateWorkflowDef(id: number, data: { name: string; description?: string }) {
  return request.put<Result<WorkflowDefRow>>(`/api/workflow/defs/${id}`, data)
}

/** 读取草稿图 */
export function getWorkflowDraft(id: number) {
  return request.get<Result<WorkflowDraftResp>>(`/api/workflow/defs/${id}/draft`)
}

/**
 * 保存草稿。
 *
 * @param id 定义 id
 * @param graph 完整 graph_json（nodes / edges / viewport）
 * @param expectedUpdateTime 上次读到的 updateTime；不一致后端返回 409
 */
export function saveWorkflowDraft(id: number, graph: WorkflowGraph, expectedUpdateTime?: string) {
  return request.put<Result<WorkflowDraftResp>>(`/api/workflow/defs/${id}/draft`, graph, {
    params: expectedUpdateTime ? { expectedUpdateTime } : undefined
  })
}

/** 校验当前草稿（以库里的草稿为准，前端需先保存） */
export function validateWorkflow(id: number) {
  return request.post<Result<WorkflowValidateResp>>(`/api/workflow/defs/${id}/validate`)
}

/** 发布当前草稿，生成不可变版本 */
export function publishWorkflow(id: number, data: { note?: string }) {
  return request.post<Result<WorkflowReleaseRow>>(`/api/workflow/defs/${id}/publish`, data)
}

/** 版本列表，按版本号倒序 */
export function listWorkflowReleases(id: number) {
  return request.get<Result<WorkflowReleaseRow[]>>(`/api/workflow/defs/${id}/releases`)
}

/**
 * 发起试跑。只跑已发布版本；不传 releaseId 时后端用默认发布指针。
 *
 * @param data releaseId / workflowId / input 样例
 */
export function startWorkflowTrial(data: {
  releaseId?: number
  workflowId: number
  useDefaultRelease?: boolean
  input: unknown
}) {
  return request.post<Result<WorkflowRunDetail>>('/api/workflow/runs/trial', data)
}

/** 查询 run 详情（含节点状态与 OPEN 人工待办） */
export function getWorkflowRun(id: number) {
  return request.get<Result<WorkflowRunDetail>>(`/api/workflow/runs/${id}`)
}

/** 取消 run；已终态幂等返回 */
export function cancelWorkflowRun(id: number) {
  return request.post<Result<WorkflowRunDetail>>(`/api/workflow/runs/${id}/cancel`)
}

/** 某工作流最近 10 次 run */
export function listWorkflowRuns(workflowId: number) {
  return request.get<Result<WorkflowRunSummary[]>>('/api/workflow/runs', { params: { workflowId } })
}

/** 通过人工待办并续跑 */
export function approveHumanTask(id: number, note?: string) {
  return request.post<Result<WorkflowRunDetail>>(`/api/workflow/human-tasks/${id}/approve`, { note })
}

/** 驳回人工待办；无 ERROR 边时整 run 失败 */
export function rejectHumanTask(id: number, note?: string) {
  return request.post<Result<WorkflowRunDetail>>(`/api/workflow/human-tasks/${id}/reject`, { note })
}

/**
 * 待办中心列表（权限 workflow:human:act）。
 *
 * @param params mine=1 只出当前用户可处理的 OPEN；status 过滤 OPEN/DONE/EXPIRED
 */
export function listHumanTasks(params: { mine?: number; status?: string }) {
  return request.get<Result<WorkflowHumanTaskItem[]>>('/api/workflow/human-tasks', { params })
}

/**
 * 某个应用的工作流触发授权（权限 system:app:list）。
 *
 * @param appId 应用 id
 */
export function listAppWorkflowGrants(appId: number) {
  return request.get<Result<WorkflowAppGrantItem[]>>('/api/workflow/app-grants', { params: { appId } })
}

/**
 * 可授权的工作流清单（权限 system:app:list）。
 * <p>只返回已发布（HAS_RELEASE）的定义，避免应用管理员额外持有 workflow:def:view。
 */
export function listPublishedWorkflows() {
  return request.get<Result<WorkflowAppGrantItem[]>>('/api/workflow/app-grants/published-defs')
}

/**
 * 整体对齐应用的工作流授权（权限 system:app:edit）。
 * 列表里没有的定义会被撤销；空数组 = 清空。
 *
 * @param appId 应用 id
 * @param workflowIds 目标工作流定义 id
 */
export function saveAppWorkflowGrants(appId: number, workflowIds: number[]) {
  return request.put<Result<null>>(`/api/workflow/app-grants/${appId}`, { workflowIds })
}
