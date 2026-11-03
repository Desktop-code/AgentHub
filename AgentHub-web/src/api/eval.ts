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

/** 建跑 */
export function createEvalRun(data: {
  suiteId: number
  suiteVersion?: number
  agentId: number
  configOverride?: Record<string, unknown>
}) {
  return request.post<Result<EvalRunRow>>('/api/eval/runs', data)
}

/** 开跑 */
export function executeEvalRun(id: number) {
  return request.post<Result<EvalRunRow>>(`/api/eval/runs/${id}/execute`)
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
