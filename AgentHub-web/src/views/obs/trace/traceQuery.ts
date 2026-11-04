/**
 * 调用追踪入口类型与工作流 query 解析。所属域：obs（前端视图）。
 * <p>纯函数，不依赖 Vue / Router，便于 node:test 直接跑；
 * 页面只负责把解析结果写回 ref，并以路由 query 为唯一事实来源。
 */

/** 入口类型：Agent 走会话三栏；工作流按定义看 Run / 节点 TASK */
export const TRACE_TYPE_AGENT = 'agent'
export const TRACE_TYPE_WORKFLOW = 'workflow'

/** 调用追踪顶部可切换的两种入口 */
export type TraceType = typeof TRACE_TYPE_AGENT | typeof TRACE_TYPE_WORKFLOW

/**
 * 路由 query 值可能是 string / string[] / number / null；统一取第一个有效字符串。
 *
 * @param raw vue-router 原始 query 值
 * @return 去壳后的字符串；无法识别时返回空串
 */
export function firstQueryValue(raw: unknown): string {
  if (Array.isArray(raw)) {
    return firstQueryValue(raw[0])
  }
  if (typeof raw === 'string') {
    return raw
  }
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return String(raw)
  }
  return ''
}

/**
 * 解析工作流定义 id。空串 / 非数字 / ≤0 一律视为「无过滤」，避免把脏 query 当条件。
 *
 * @param raw 路由上的 workflowId
 * @return 正整数定义 id；不合法时返回 null
 */
export function parseWorkflowId(raw: unknown): number | null {
  const text = firstQueryValue(raw).trim()
  if (!text) {
    return null
  }
  const parsed = Number(text)
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return null
  }
  return Math.trunc(parsed)
}

/**
 * 决定入口类型：显式 type 优先；缺省时有 workflowId → 工作流，否则 Agent。
 * <p>显式 type=agent 时即使带了 workflowId 也回到 Agent 三栏，避免旧链接落错入口。
 *
 * @param rawType 路由上的 type
 * @param workflowId 已解析的工作流定义 id
 * @return agent / workflow
 */
export function resolveTraceType(rawType: unknown, workflowId: number | null): TraceType {
  const text = firstQueryValue(rawType).trim().toLowerCase()
  if (text === TRACE_TYPE_WORKFLOW) {
    return TRACE_TYPE_WORKFLOW
  }
  if (text === TRACE_TYPE_AGENT) {
    return TRACE_TYPE_AGENT
  }
  return workflowId != null ? TRACE_TYPE_WORKFLOW : TRACE_TYPE_AGENT
}

/**
 * 生成 /ops/traces 的 query。
 * <p>Agent 丢弃 workflowId / workflowName（不兼容筛选）；工作流保留 defId 与名称。
 *
 * @param input 目标类型与可选工作流条件
 * @return 可直接交给 router.replace 的 query 对象
 */
export function buildTraceQuery(input: {
  type: TraceType
  workflowId?: number | null
  workflowName?: string | null
}): Record<string, string> {
  const query: Record<string, string> = { type: input.type }
  if (input.type !== TRACE_TYPE_WORKFLOW) {
    return query
  }
  if (input.workflowId != null && input.workflowId > 0) {
    query.workflowId = String(input.workflowId)
  }
  const name = (input.workflowName || '').trim()
  if (name) {
    query.workflowName = name
  }
  return query
}
