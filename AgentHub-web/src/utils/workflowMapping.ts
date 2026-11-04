/**
 * 工作流入参 / 出参映射的路径解析与拼装。所属域：workflow 的前端展示层。
 * <p>后端契约（WorkflowNodeInputs）：映射是「字段 → 路径字符串」的扁平对象，
 * 路径只认 {@code $.input.x} / {@code $.outputs.<nodeCode>.x} / {@code $.vars.k}；
 * 不以 {@code $} 开头按字面量常量处理。
 * <p>职责：把这份字符串契约与「来源节点 + 字段」的 UI 行互转，让用户不必手敲 JSONPath。
 * <p>纯函数、无 Vue 依赖：可在 node 里直接跑用例验证往返一致性。
 */
import type { WorkflowMappingSource } from '@/types/workflow'

/** UI 来源类型：运行入参 / 前置节点 / 流程变量 / 自定义值（常量或高级路径） */
export type MappingSourceKind = 'input' | 'node' | 'vars' | 'custom'

/** 一行映射的 UI 状态（不含 uid，uid 由组件维护） */
export interface MappingUiRow {
  /** 目标字段，即落库对象的键 */
  target: string
  sourceKind: MappingSourceKind
  /** 仅 sourceKind=node 时有值 */
  sourceNodeId?: string
  /** 取值字段；空串表示取整段（$.input / $.outputs.code / $.vars） */
  sourceField: string
  /** 仅 sourceKind=custom：常量文本或手写路径，原样落库 */
  text: string
}

/** 解析上下文：前置节点列表 + 图内 START 编码 */
export interface MappingContext {
  sources: WorkflowMappingSource[]
  startCodes: string[]
}

/** $.input / $.input.x */
const PATH_INPUT = /^\$\.input(?:\.(.+))?$/
/** $.outputs.<code> / $.outputs.<code>.x；code 不跨点号，与后端按点号取值的口径一致 */
const PATH_OUTPUTS = /^\$\.outputs\.([^.[\]]+)(?:\.(.+))?$/
/** $.vars / $.vars.k */
const PATH_VARS = /^\$\.vars(?:\.(.+))?$/

/**
 * 把一条已存的路径 / 常量拆成 UI 行。
 * <p>START 节点的输出等价于运行入参，统一归一成 input 类型：
 * 既避免同一份取值出现两种路径写法，也保证改别的映射时不会把整张表悄悄换成另一种字符串。
 *
 * @param target 目标字段
 * @param raw    落库值：路径或常量
 * @param ctx    解析上下文
 * @return UI 行；无法识别的落到 custom，原样保留
 */
export function parseMappingRow(target: string, raw: string, ctx: MappingContext): MappingUiRow {
  const text = String(raw ?? '')
  const trimmed = text.trim()
  if (!trimmed.startsWith('$')) {
    return { target, sourceKind: 'custom', sourceField: '', text }
  }
  const inputMatch = PATH_INPUT.exec(trimmed)
  if (inputMatch) {
    return { target, sourceKind: 'input', sourceField: inputMatch[1] ?? '', text: '' }
  }
  const varsMatch = PATH_VARS.exec(trimmed)
  if (varsMatch) {
    return { target, sourceKind: 'vars', sourceField: varsMatch[1] ?? '', text: '' }
  }
  const outputsMatch = PATH_OUTPUTS.exec(trimmed)
  if (outputsMatch) {
    const code = outputsMatch[1]
    const field = outputsMatch[2] ?? ''
    if (ctx.startCodes.includes(code)) {
      return { target, sourceKind: 'input', sourceField: field, text: '' }
    }
    const node = ctx.sources.find((item) => item.code === code)
    if (node) {
      return { target, sourceKind: 'node', sourceNodeId: node.id, sourceField: field, text: '' }
    }
  }
  // 未知路径（老写法、无法拆解的形态）：不猜，交给用户在原文本框里修正
  return { target, sourceKind: 'custom', sourceField: '', text }
}

/**
 * 计算单行的落库路径。
 *
 * @param row UI 行
 * @param ctx 解析上下文（需能按 id 找到来源节点）
 * @return 路径 / 常量；来源缺失返回空串（调用方据此跳过该行）
 */
export function mappingRowPath(row: MappingUiRow, ctx: MappingContext): string {
  if (row.sourceKind === 'custom') {
    return row.text
  }
  const field = row.sourceField.trim()
  if (row.sourceKind === 'vars') {
    return field ? `$.vars.${field}` : '$.vars'
  }
  if (row.sourceKind === 'input') {
    return field ? `$.input.${field}` : '$.input'
  }
  const node = ctx.sources.find((item) => item.id === row.sourceNodeId)
  if (!node) {
    return ''
  }
  return field ? `$.outputs.${node.code}.${field}` : `$.outputs.${node.code}`
}

/**
 * 落库对象 → UI 行。
 *
 * @param mapping 目标字段 → 路径
 * @param ctx     解析上下文
 * @return UI 行（未过滤空键，保持与草稿一致的顺序）
 */
export function parseMappingObject(
  mapping: Record<string, string> | undefined | null,
  ctx: MappingContext
): MappingUiRow[] {
  return Object.entries(mapping || {}).map(([target, value]) =>
    parseMappingRow(target, String(value ?? ''), ctx)
  )
}

/**
 * UI 行 → 落库对象。
 * <p>空目标字段、空取值（未选来源节点的行）整行跳过，避免脏键进 graph_json；
 * 重复目标字段按「后一行覆盖前一行」处理，与对象语义一致。
 *
 * @param rows UI 行
 * @param ctx  解析上下文
 * @return 目标字段 → 路径
 */
export function buildMappingObject(
  rows: MappingUiRow[],
  ctx: MappingContext
): Record<string, string> {
  const result: Record<string, string> = {}
  rows.forEach((row) => {
    const target = row.target.trim()
    if (!target) {
      return
    }
    const path = mappingRowPath(row, ctx)
    if (!path) {
      return
    }
    result[target] = path
  })
  return result
}

/**
 * 键值无关顺序的比较，用于判断外部回填是否与当前行等价（等价则不重建，避免光标跳动）。
 *
 * @param left  外部传入的映射
 * @param right 当前行拼出的映射
 * @return 完全一致返回 true
 */
export function sameMapping(
  left: Record<string, string>,
  right: Record<string, string>
): boolean {
  const leftKeys = Object.keys(left)
  const rightKeys = Object.keys(right)
  if (leftKeys.length !== rightKeys.length) {
    return false
  }
  return leftKeys.every((key) => left[key] === right[key])
}
