/**
 * 评测域 API。所属域：eval。
 * <p>封装刀1~5 既有接口 + 坏案回流 glue；无 SSE。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  EvalBadCaseRow,
  EvalCaseRow,
  EvalImportResult,
  EvalJson,
  EvalResultRow,
  EvalRunRow,
  EvalSuiteDetail,
  EvalSuiteRow
} from '@/types/eval'

/** 套件分页 */
export function pageEvalSuites(params: {
  current: number
  size: number
  keyword?: string
  enabled?: number
}) {
  return request.get<Result<PageResult<EvalSuiteRow>>>('/api/eval/suites', { params })
}

/** 套件详情（含当前版本 cases） */
export function getEvalSuite(id: number) {
  return request.get<Result<EvalSuiteDetail>>(`/api/eval/suites/${id}`)
}

/** 新建套件 */
export function createEvalSuite(data: {
  suiteKey: string
  name: string
  description?: string
  cases?: EvalCaseRow[]
}) {
  return request.post<Result<EvalSuiteDetail>>('/api/eval/suites', data)
}

/**
 * 更新套件元数据（名称/描述/默认配置/启停）；**不改 cases、不升版**。
 * <p>启停开关复用该接口：后端要求 name 必填，因此调用方需回传当前行名称。
 */
export function updateEvalSuite(
  id: number,
  data: {
    name: string
    description?: string
    defaultConfig?: EvalJson
    enabled?: number
  }
) {
  return request.put<Result<EvalSuiteDetail>>(`/api/eval/suites/${id}`, {
    name: data.name,
    description: data.description,
    default_config: data.defaultConfig,
    enabled: data.enabled
  })
}

/** 导入完整套件 JSON（升版） */
export function importEvalSuite(id: number, data: unknown) {
  return request.post<Result<EvalImportResult>>(`/api/eval/suites/${id}/import`, data)
}

/** 列出版本用例 */
export function listEvalCases(id: number, suiteVersion?: number) {
  return request.get<Result<EvalCaseRow[]>>(`/api/eval/suites/${id}/cases`, {
    params: suiteVersion == null ? undefined : { suiteVersion }
  })
}

/** 升版写单条 case */
export function upsertEvalCase(id: number, data: EvalCaseRow) {
  return request.post<Result<EvalImportResult>>(`/api/eval/suites/${id}/cases`, data)
}

/** 导入平台样例 */
export function importEvalSample() {
  return request.post<Result<EvalImportResult>>('/api/eval/suites/import-sample')
}

/** 跑批分页 */
export function pageEvalRuns(params: {
  current: number
  size: number
  suiteId?: number
  agentId?: number
  status?: string
}) {
  return request.get<Result<PageResult<EvalRunRow>>>('/api/eval/runs', { params })
}

/**
 * 建跑。
 * <p>judgeModelInstanceId：本 run 的打分模型实例；不传或传 null 表示只跑规则门禁（关闭软评分）。
 * 后端字段名固定为 judge_model_instance_id（此处显式转换，避免与既有 camelCase 参数混淆）。
 */
export function createEvalRun(data: {
  suiteId: number
  suiteVersion?: number
  agentId: number
  judgeModelInstanceId?: number | null
  configOverride?: Record<string, unknown>
}) {
  return request.post<Result<EvalRunRow>>('/api/eval/runs', {
    suiteId: data.suiteId,
    suiteVersion: data.suiteVersion,
    agentId: data.agentId,
    judge_model_instance_id: data.judgeModelInstanceId ?? null,
    configOverride: data.configOverride
  })
}

/**
 * 开跑。
 *
 * <p>后端是同步执行（串行跑完所有 case 才返回），一条跑批可能远超全局 15s 超时。
 * 沿用全局超时会让前端提前断开：按钮转圈结束、却又看到 PENDING 的「执行」按钮，
 * 而后端其实还在跑/已跑完，只能靠手动刷新才对上。这里单独放宽到 10 分钟。
 * 前端超时只做 UX；是否允许重复执行仍由后端 CAS 兜底。
 */
export function executeEvalRun(id: number) {
  return request.post<Result<EvalRunRow>>(`/api/eval/runs/${id}/execute`, {}, { timeout: 600000 })
}

/** 查跑批 */
export function getEvalRun(id: number) {
  return request.get<Result<EvalRunRow>>(`/api/eval/runs/${id}`)
}

/** 跑批结果分页 */
export function pageEvalRunResults(id: number, params: { current: number; size: number }) {
  return request.get<Result<PageResult<EvalResultRow>>>(`/api/eval/runs/${id}/results`, { params })
}

/** 报告 JSON */
export function getEvalReport(id: number) {
  return request.get<Result<unknown>>(`/api/eval/runs/${id}/report`)
}

/** 取消 */
export function cancelEvalRun(id: number) {
  return request.post<Result<EvalRunRow>>(`/api/eval/runs/${id}/cancel`)
}

/** 坏案分页（默认 FAIL） */
export function pageEvalBadCases(params: {
  current: number
  size: number
  includeError?: boolean
}) {
  return request.get<Result<PageResult<EvalBadCaseRow>>>('/api/eval/results', { params })
}

/** 从 result 回流 */
export function promoteFromResult(
  suiteId: number,
  data: { resultId: number; caseKey?: string; title?: string; note?: string; force?: boolean }
) {
  return request.post<Result<EvalImportResult>>(`/api/eval/suites/${suiteId}/cases/from-result`, data)
}

/** 从观测摘要回流 */
export function promoteFromTrace(
  suiteId: number,
  data: {
    message: string
    finalAnswer?: string
    sessionId?: string
    traceId?: string
    caseKey?: string
    title?: string
    note?: string
  }
) {
  return request.post<Result<EvalImportResult>>(`/api/eval/suites/${suiteId}/cases/from-trace`, data)
}
