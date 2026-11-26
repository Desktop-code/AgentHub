/**
 * 工作流入参「按定义出槽」的解析与归并。所属域：workflow 的前端展示层。
 * <p>职责：把工具 {@code inputSchemaJson} 的顶层参数、AGENT 的默认「用户输入」槽
 * 解析成可编辑的目标槽；并在保存前剔掉空值键，避免空字符串挡住运行时的兜底取值。
 * <p>契约：本模块只产出「目标字段 + 展示信息」，落库仍是 {@code config.inputMapping} 的
 * {@code Record<string, string>}（目标字段 → $.input / $.outputs / $.vars / 常量），
 * 路径仍由 {@code workflowMapping} 拼装，运行时的解析口径完全不变。
 * <p>纯函数、无 Vue 依赖：可在 node 里直接跑用例验证。
 */

/**
 * 槽的来源。
 * <p>{@code tool}：读工具 JSON Schema 的顶层参数；{@code agent}：AGENT 没有入参声明，
 * 只给一个「用户输入」默认槽，不代表平台认领了 Agent 的参数表。
 */
export type InputSlotOrigin = 'tool' | 'agent'

/** 一个入参目标槽（定义槽不可改名、不可删；手填行没有这个结构） */
export interface InputSlotDefinition {
  /** 目标字段，即落库对象的键 */
  key: string
  /** 展示名：工具 schema 的 title；AGENT 默认槽固定「用户输入」。空串表示不显示 */
  title: string
  /** 说明：工具 schema 的 description；没有就空串，不翻译、不编造 */
  description: string
  /** 是否必填：工具 schema 的 required 命中；AGENT 默认槽不算必填 */
  required: boolean
  /** 来源：工具定义 / AGENT 默认槽 */
  origin: InputSlotOrigin
}

/** AGENT 默认槽的展示名：没有 Agent 入参声明，不再假装有一份参数表 */
export const AGENT_INPUT_SLOT_TITLE = '用户输入'

/** AGENT 默认槽的候选键，按优先级排列；与后端 WorkflowNodeInputs.message 的兜底顺序对齐 */
const AGENT_INPUT_KEYS = ['query', 'message', 'text', 'content'] as const

/** 判断任意值是否为普通对象（排除 null / 数组） */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 读取字符串字段并去掉首尾空白；非字符串返回空串（不把数字等值当展示名） */
function readText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * 解析工具 {@code inputSchemaJson} 的顶层参数槽。
 * <p>只取 {@code properties} 的第一层：嵌套对象仍算一个槽，不拆成一串子槽，
 * 也不因为嵌套结构去猜用户的意图。{@code required} 与 {@code properties} 取交集，
 * 保证画布上一定有对应的槽可以配；没有 title / description 时保持空串，不编造中文名。
 *
 * @param schemaJson 工具项的 inputSchemaJson，可空 / 非法 JSON
 * @return 顶层参数槽；空 schema、非法 JSON、没有 properties 均返回空数组
 */
export function parseToolInputSlots(schemaJson?: string | null): InputSlotDefinition[] {
  const raw = String(schemaJson ?? '').trim()
  if (!raw) {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    // 坏 schema 不阻断编辑：退回自由填写，用户仍可手工加映射
    return []
  }
  if (!isPlainObject(parsed)) {
    return []
  }
  const properties = parsed.properties
  if (!isPlainObject(properties)) {
    return []
  }
  const requiredList = Array.isArray(parsed.required)
    ? parsed.required.filter((item): item is string => typeof item === 'string').map((item) => item.trim())
    : []
  const slots: InputSlotDefinition[] = []
  Object.entries(properties).forEach(([key, definition]) => {
    const name = key.trim()
    if (!name) {
      return
    }
    const property = isPlainObject(definition) ? definition : {}
    slots.push({
      key: name,
      title: readText(property.title),
      description: readText(property.description),
      required: requiredList.includes(name),
      origin: 'tool'
    })
  })
  return slots
}

/**
 * AGENT 默认「用户输入」槽的键。
 * <p>已有映射里按 query → message → text → content 取第一个，都没有时用 query：
 * 与后端无映射时的兜底顺序一致，用户配过哪个就沿用哪个，不另起新键。
 *
 * @param mapping 已保存的入参映射，可空
 * @return 目标键（非空）
 */
export function agentDefaultInputKey(mapping?: Record<string, string> | null): string {
  const keys = Object.keys(mapping || {})
  for (const candidate of AGENT_INPUT_KEYS) {
    if (keys.includes(candidate)) {
      return candidate
    }
  }
  return 'query'
}

/** AGENT 默认「用户输入」槽：不可改名、不可删，但不强制必填（不新增发布拦截） */
export function agentInputSlot(mapping?: Record<string, string> | null): InputSlotDefinition {
  return agentInputSlotForKey(agentDefaultInputKey(mapping))
}

/**
 * 按明确键构造 AGENT 默认槽。
 * <p>编辑器在打开时把键定一次再传进来：否则用户手输目标字段到一半命中 query，
 * 槽位会在输入过程中换键，把正在编辑的行抢成只读槽。
 *
 * @param key 目标键；空串回落到 query
 * @return AGENT 默认槽
 */
export function agentInputSlotForKey(key: string): InputSlotDefinition {
  const resolved = String(key ?? '').trim() || 'query'
  return {
    key: resolved,
    title: AGENT_INPUT_SLOT_TITLE,
    description: '',
    required: false,
    origin: 'agent'
  }
}

/**
 * 按节点类型解析当前应展示的定义槽。
 * <p>TOOL 按工具 schema 出槽；AGENT 只给默认「用户输入」；其它节点没有定义，返回空数组，
 * 编辑器继续走自由添加。
 *
 * @param nodeType      节点类型（AGENT / TOOL / …）
 * @param schemaJson    绑定工具的 inputSchemaJson，可空
 * @param mapping       已保存的入参映射，用于决定 AGENT 默认槽的键
 * @return 定义槽；空数组表示没有定义
 */
export function resolveInputSlots(
  nodeType: string | undefined,
  schemaJson: string | null | undefined,
  mapping?: Record<string, string> | null
): InputSlotDefinition[] {
  const type = String(nodeType ?? '').trim()
  if (type === 'AGENT') {
    return [agentInputSlot(mapping)]
  }
  if (type === 'TOOL') {
    return parseToolInputSlots(schemaJson)
  }
  return []
}

/**
 * 槽集合签名：用于判断「定义是否变了」（换绑工具、schema 同步）而重建行。
 * <p>只包含会改变槽位语义的字段，不含用户填的来源，避免每次输入都重排行。
 *
 * @param slots 定义槽
 * @return 稳定字符串
 */
export function slotSignature(slots: InputSlotDefinition[]): string {
  return slots
    .map((slot) => [slot.key, slot.origin, slot.title, slot.required ? '1' : '0', slot.description].join('\u0001'))
    .join('\u0002')
}

/**
 * 目标字段是否落在定义槽里（定义槽不可改名、不可删）。
 *
 * @param slots  定义槽
 * @param target 行上的目标字段（可带空白）
 * @return true 表示这是定义槽
 */
export function isDefinitionSlot(slots: InputSlotDefinition[], target: string): boolean {
  const key = String(target ?? '').trim()
  return !!key && slots.some((slot) => slot.key === key)
}

/**
 * 必填槽里还没配来源的键。
 * <p>「配了」的判定：映射里存在该键且值不是空白。空串 / 全空白视为没配，
 * 因为运行时拿到空文本后不会再走 input.message → query → text → content 兜底。
 *
 * @param slots   定义槽
 * @param mapping 入参映射，可空
 * @return 缺失的必填键（按定义顺序）
 */
export function missingRequiredSlotKeys(
  slots: InputSlotDefinition[],
  mapping?: Record<string, string> | null
): string[] {
  const source = mapping || {}
  return slots
    .filter((slot) => slot.required)
    .filter((slot) => {
      const value = source[slot.key]
      return value === undefined || String(value ?? '').trim() === ''
    })
    .map((slot) => slot.key)
}

/**
 * 保存前剔掉空键。
 * <p>只展示、还没配来源的槽不写进映射：空字符串会在运行时被解析成 null，
 * 让「无映射时按 message → query → text → content 兜底」失效。
 * <p>键去空白；值原样保留（固定值可能带首尾空格，不做二次加工）。
 *
 * @param mapping 目标字段 → 路径 / 常量
 * @return 只含非空键值的新对象
 */
export function dropEmptyInputMapping(
  mapping?: Record<string, string> | null
): Record<string, string> {
  const result: Record<string, string> = {}
  Object.entries(mapping || {}).forEach(([key, value]) => {
    const target = String(key ?? '').trim()
    const path = String(value ?? '')
    if (!target || !path.trim()) {
      return
    }
    result[target] = path
  })
  return result
}

/**
 * 把定义槽与已有行归并成展示顺序（定义槽在前，其余手填行按原顺序在后）。
 * <p>同名键视为该槽的已有行：保留它已经配好的来源，不覆盖，也不额外新建一条；
 * 换了工具后不再属于新定义的旧键会留在「其余行」里，由组件标「未在工具定义中」并允许删除。
 * <p>不修改入参数组；已有行的对象引用原样返回，组件可据此保持输入框状态。
 *
 * @param rows   已有行（至少含 target）
 * @param slots  定义槽
 * @param create 新建空行的工厂：仅在定义槽还没有对应行时调用
 * @return 行 + 槽归属；slot 为空表示手填行
 */
export function planSlotRows<R extends { target: string }>(
  rows: R[],
  slots: InputSlotDefinition[],
  create: (target: string) => R
): Array<{ row: R; slot?: InputSlotDefinition }> {
  const used = new Set<number>()
  const planned: Array<{ row: R; slot?: InputSlotDefinition }> = slots.map((slot) => {
    const index = rows.findIndex(
      (row, rowIndex) => !used.has(rowIndex) && String(row.target ?? '').trim() === slot.key
    )
    if (index >= 0) {
      used.add(index)
      return { row: rows[index], slot }
    }
    return { row: create(slot.key), slot }
  })
  rows.forEach((row, index) => {
    if (!used.has(index)) {
      planned.push({ row })
    }
  })
  return planned
}
