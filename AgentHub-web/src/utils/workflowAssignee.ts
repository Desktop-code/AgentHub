/**
 * 工作流人工节点处理人规则（W4）的前端解析 / 命中工具。所属域：workflow 的展示层。
 *
 * <p>与后端 {@code WorkflowAssigneeRule} 同语法、同命中口径：
 * <ul>
 *   <li>{@code ROLE:<roleCode>}：当前用户启用角色里命中即通过（忽略大小写）；</li>
 *   <li>{@code USER:<id>[,<id>...]}：当前用户 id 命中任一即通过；</li>
 *   <li>空规则：有 {@code workflow:human:act} 即可（历史图兼容）。</li>
 * </ul>
 *
 * <p>为什么前端也要一份：试跑抽屉与待办列表需要在不发请求的情况下提示
 * 「是否待我处理」，避免用户点了按钮才被后端 403。真正放行仍以后端为准。
 */

/** 规则种类 */
export type AssigneeKind = 'EMPTY' | 'USER' | 'ROLE'

/** 解析结果 */
export interface ParsedAssigneeRule {
  kind: AssigneeKind
  /** ROLE 规则的角色码 */
  roleCode: string | null
  /** USER 规则的用户 id 列表 */
  userIds: number[]
  /** 非法原因（中文）；合法时为 null */
  problem: string | null
}

/** 单条规则最大长度，与后端 VARCHAR(128) 对齐 */
export const MAX_ASSIGNEE_RULE_LENGTH = 128

/** 单条 USER 规则最多列多少用户，与后端一致 */
export const MAX_ASSIGNEE_USER_IDS = 100

const ROLE_CODE_PATTERN = /^[A-Za-z0-9_.:-]{1,64}$/

/** 归一：去首尾空白，空串视为无规则 */
export function normalizeAssigneeRule(raw?: string | null): string | null {
  if (raw === null || raw === undefined) {
    return null
  }
  const text = String(raw).trim()
  return text ? text : null
}

/**
 * 解析规则。
 *
 * @param raw 规则原文
 * @returns 解析结果；非法时 problem 为中文原因
 */
export function parseAssigneeRule(raw?: string | null): ParsedAssigneeRule {
  const text = normalizeAssigneeRule(raw)
  if (!text) {
    return { kind: 'EMPTY', roleCode: null, userIds: [], problem: null }
  }
  if (text.length > MAX_ASSIGNEE_RULE_LENGTH) {
    return {
      kind: 'EMPTY',
      roleCode: null,
      userIds: [],
      problem: `处理人规则长度 ${text.length} 超过上限 ${MAX_ASSIGNEE_RULE_LENGTH} 字符`
    }
  }
  const upper = text.toUpperCase()
  if (upper.startsWith('USER:')) {
    return parseUsers(text.slice(5))
  }
  if (upper.startsWith('ROLE:')) {
    return parseRole(text.slice(5))
  }
  return {
    kind: 'EMPTY',
    roleCode: null,
    userIds: [],
    problem: `处理人规则只能写 ROLE:角色码 或 USER:用户id,用户id；当前为「${text}」`
  }
}

function parseUsers(body: string): ParsedAssigneeRule {
  const ids: number[] = []
  for (const part of body.split(',')) {
    const token = part.trim()
    if (!token) {
      continue
    }
    if (!/^\d+$/.test(token)) {
      return {
        kind: 'USER',
        roleCode: null,
        userIds: [],
        problem: `处理人规则里的用户 id 不是数字：「${token}」`
      }
    }
    const id = Number(token)
    if (!Number.isSafeInteger(id) || id <= 0) {
      return {
        kind: 'USER',
        roleCode: null,
        userIds: [],
        problem: `处理人规则里的用户 id 必须是正整数，当前为「${token}」`
      }
    }
    if (!ids.includes(id)) {
      ids.push(id)
    }
  }
  if (ids.length === 0) {
    return { kind: 'USER', roleCode: null, userIds: [], problem: '处理人规则 USER: 后至少要写 1 个用户 id' }
  }
  if (ids.length > MAX_ASSIGNEE_USER_IDS) {
    return {
      kind: 'USER',
      roleCode: null,
      userIds: [],
      problem: `处理人规则最多列出 ${MAX_ASSIGNEE_USER_IDS} 个用户，当前 ${ids.length} 个`
    }
  }
  return { kind: 'USER', roleCode: null, userIds: ids, problem: null }
}

function parseRole(body: string): ParsedAssigneeRule {
  const code = body.trim()
  if (!code) {
    return { kind: 'ROLE', roleCode: null, userIds: [], problem: '处理人规则 ROLE: 后必须写角色码' }
  }
  if (!ROLE_CODE_PATTERN.test(code)) {
    return {
      kind: 'ROLE',
      roleCode: null,
      userIds: [],
      problem: `角色码「${code}」不合法：只允许字母、数字、下划线、点、冒号、连字符，最长 64 字符`
    }
  }
  return { kind: 'ROLE', roleCode: code, userIds: [], problem: null }
}

/**
 * 判断当前用户能否处理。
 *
 * @param raw       规则原文
 * @param userId    当前用户 id；未知传 null（空规则以外一律不命中）
 * @param roleCodes 当前用户启用角色码
 */
export function matchesAssigneeRule(
  raw: string | null | undefined,
  userId: number | null | undefined,
  roleCodes: string[] | null | undefined
): boolean {
  const parsed = parseAssigneeRule(raw)
  if (parsed.problem) {
    return false
  }
  if (parsed.kind === 'EMPTY') {
    return true
  }
  if (parsed.kind === 'USER') {
    return typeof userId === 'number' && parsed.userIds.includes(userId)
  }
  const codes = roleCodes ?? []
  return codes.some(
    (code) => typeof code === 'string' && code.toLowerCase() === (parsed.roleCode ?? '').toLowerCase()
  )
}

/**
 * 规则中文描述（列表 / 抽屉展示）。
 *
 * @param raw 规则原文
 */
export function describeAssigneeRule(raw?: string | null): string {
  const parsed = parseAssigneeRule(raw)
  if (parsed.problem) {
    return `规则非法：${parsed.problem}`
  }
  if (parsed.kind === 'EMPTY') {
    return '任何人（需处理权限）'
  }
  if (parsed.kind === 'USER') {
    return `指定用户：${parsed.userIds.join(',')}`
  }
  return `角色：${parsed.roleCode}`
}
