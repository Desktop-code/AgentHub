/**
 * 评测域类型。所属域：eval。
 */

/** 宽松 JSON 节点 */
export type EvalJson = Record<string, unknown> | unknown[] | string | number | boolean | null

/** 套件卡片 */
export interface EvalSuiteRow {
  id: number
  tenantId?: number
  suiteKey: string
  name: string
  description?: string
  currentVersion: number
  defaultConfig?: EvalJson
  enabled: number
  readOnly?: boolean
  updateTime?: string
}

/** 用例 */
export interface EvalCaseRow {
  id?: number
  caseKey: string
  title: string
  tags?: string[]
  enabled?: number
  sortOrder?: number
  input?: EvalJson
  expect?: EvalJson
  assertions?: EvalJson
  scoring?: EvalJson
}

export interface EvalSuiteDetail extends EvalSuiteRow {
  cases?: EvalCaseRow[]
}

/** 跑批 */
export interface EvalRunRow {
  id: number
  suiteId: number
  suiteVersion: number
  agentId: number
  status: string
  gatePassed?: number | null
  totalCases?: number
  passed?: number
  failed?: number
  errored?: number
  skipped?: number
  openjudgeEnabled?: number
  reportPath?: string | null
  report?: EvalJson
  errorMessage?: string | null
  startedTime?: string
  finishedTime?: string
  createTime?: string
  agentSnapshotHash?: string | null
}

/** 单 case 结果 */
export interface EvalResultRow {
  id: number
  runId: number
  caseKey: string
  caseId?: number
  verdict?: string | null
  rulePassed?: number | null
  openjudgeStatus?: string | null
  scores?: EvalJson
  artifactSummary?: EvalJson
  assertionDetails?: EvalJson
  sessionId?: string | null
  traceId?: string | null
  latencyMs?: number | null
  errorMessage?: string | null
  finishedTime?: string
}

/** 坏案行 */
export interface EvalBadCaseRow {
  resultId: number
  runId: number
  suiteId?: number
  suiteVersion?: number
  agentId?: number
  caseKey: string
  verdict?: string
  rulePassed?: number | null
  openjudgeStatus?: string | null
  scores?: EvalJson
  assertionDetails?: EvalJson
  artifactSummary?: EvalJson
  sessionId?: string | null
  traceId?: string | null
  latencyMs?: number | null
  errorMessage?: string | null
  finishedTime?: string
  createTime?: string
}

export interface EvalImportResult {
  suiteId: number
  suiteKey: string
  previousVersion: number
  currentVersion: number
  caseCount: number
}
