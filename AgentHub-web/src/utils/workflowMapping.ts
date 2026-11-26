/**
 * 工作流入参 / 出参映射的路径解析与拼装。所属域：workflow 的前端展示层。
 * <p>后端契约（WorkflowNodeInputs）：映射是「字段 → 路径字符串」的扁平对象，
 * 路径只认 {@code $.input.x} / {@code $.outputs.<nodeCode>.x} / {@code $.vars.k}；
 * 不以 {@code $} 开头按字面量常量处理。
 * <p>职责：把这份字符串契约与「来源节点 + 字段」的 UI 行互转，让用户不必手敲 JSONPath。
 * <p>纯函数、无 Vue 依赖：可在 node 里直接跑用例验证往返一致性。
 */
import type { WorkflowMappingSource } from '@/types/workflow'

/**
 * UI 来源类型：未选择 / 运行入参 / 前置节点 / 流程变量 / 自定义值（常量或高级路径）。
 * <p>{@code unset} 只存在于编辑态：新加的行默认没选来源，不落库；
 * 已存配置解析出来永远是具体来源或 custom，不会凭空多出 unset。
 */
export type MappingSourceKind = 'unset' | 'input' | 'node' | 'vars' | 'custom'

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
  // 空值等价于「还没选来源」：存量草稿里的空串键回归未选态，下次保存时不再写回
  if (!trimmed) {
    return { target, sourceKind: 'unset', sourceField: '', text: '' }
  }
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
 * 高级面板里直接改入参路径。
 * <p>能归一成 {@code $.input} / {@code $.outputs} / {@code $.vars} 就回到点选；
 * 否则按「固定值」原文保留，与 {@link parseMappingRow} 的口径一致。
 * <p>空串回到未选择：既不落空路径，也不把空值猜成「整段入参」。
 *
 * @param row 原 UI 行
 * @param raw 高级面板里输入的路径 / 常量
 * @param ctx 解析上下文
 * @return 新的 UI 行（不修改入参）
 */
export function applyMappingPathEdit(
  row: MappingUiRow,
  raw: string,
  ctx: MappingContext
): MappingUiRow {
  const text = String(raw ?? '')
  if (!text.trim()) {
    return { target: row.target, sourceKind: 'unset', sourceNodeId: undefined, sourceField: '', text: '' }
  }
  const parsed = parseMappingRow(row.target, text, ctx)
  return {
    target: row.target,
    sourceKind: parsed.sourceKind,
    sourceNodeId: parsed.sourceNodeId,
    sourceField: parsed.sourceField,
    text: parsed.text
  }
}

/**
 * 计算单行的落库路径。
 *
 * @param row UI 行
 * @param ctx 解析上下文（需能按 id 找到来源节点）
 * @return 路径 / 常量；来源缺失返回空串（调用方据此跳过该行）
 */
export function mappingRowPath(row: MappingUiRow, ctx: MappingContext): string {
  // 还没选来源的编辑态行不产出路径；buildMappingObject 会整行跳过
  if (row.sourceKind === 'unset') {
    return ''
  }
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

/* ============ 点选收口的展示口径 ============ */

/**
 * 点选行的字段列标签。
 * <p>自定义值填的是写死的常量，不再叫「字段」；切回运行入参 / 前置节点时恢复「字段」。
 *
 * @param sourceKind 当前来源
 * @return 标签文案
 */
export function mappingFieldLabel(sourceKind: MappingSourceKind): string {
  return sourceKind === 'custom' ? '固定值' : '字段'
}

/**
 * 路径在高级面板里的可见性。
 * <p>点选模式下默认收起，展开「高级」才出现；手写模式由输入框本身承担，不再另铺灰字路径。
 *
 * @param mode         点选 / 手写
 * @param advancedOpen 高级面板是否展开
 * @return true 表示显示路径
 */
export function isMappingPathVisible(mode: 'pick' | 'manual', advancedOpen: boolean): boolean {
  return mode === 'pick' && advancedOpen
}

/* ============ 三处点选共用的下拉数据类型 ============ */

/** 点选下拉里的一项字段 */
export interface MappingPickerField {
  /** 拼进落库路径的字段名（如 text / data.items） */
  value: string
  /** 入门展示的人话字段名：只显示字段名，不带整条路径 */
  label: string
  /** 次要说明：完整路径，放在选项右侧，不抢占主标签 */
  hint?: string
}

/** 点选下拉里的一个来源 */
export interface MappingPickerSource {
  /** 父层用来判定来源类型 / 节点的值 */
  value: string
  /** 入门展示的人话来源名：节点名 / 运行入参 / 本节点产出 */
  label: string
  /** 次要说明：节点编码或路径前缀 */
  hint?: string
  /** 该来源可选字段；空数组表示只能手写或取整段 */
  fields: MappingPickerField[]
  /** 整段取值项文案；不传表示该来源不提供整段项 */
  wholeLabel?: string
  /** 字段是否允许直接输入嵌套路径（默认允许） */
  allowCustomField?: boolean
}

/* ============ 按同名带入 ============ */

/** 一条「按同名带入」的落点 */
export interface SameNameFillItem {
  /** 命中的目标字段，也是上游输出字段名 */
  target: string
  /** 来源节点 id */
  sourceNodeId: string
  /** 来源字段，与 target 同名 */
  sourceField: string
  /** 要写入的行下标 */
  rowIndex: number
}

/**
 * 判断一行映射是否「还没选来源」。
 * <p>只把 unset / 空自定义 / 缺节点的行当空行；input / vars 留空字段表示取整段，
 * 是用户已经做过的选择，不算空行，避免「按同名带入」把整段映射冲掉。
 *
 * @param row UI 行
 * @return true 表示这一行还等着填来源
 */
export function isMappingRowEmpty(row: MappingUiRow): boolean {
  if (!row.target.trim()) {
    return false
  }
  if (row.sourceKind === 'unset') {
    return true
  }
  if (row.sourceKind === 'custom') {
    return !row.text.trim()
  }
  if (row.sourceKind === 'node') {
    return !row.sourceNodeId
  }
  return false
}

/**
 * 规划「按同名带入」：只看目标字段已填、来源还空着的行，用前置节点 outputKeys 同名命中。
 * <p>已经配好来源的行一律不动，也不新增行——一期只补空洞，不做整体覆盖。
 * <p>多个前置节点有同名字段时按 sources 顺序取最近的（collectAncestors 已按距离排）。
 *
 * @param rows    UI 行
 * @param sources 前置节点候选
 * @return 需要填入的项；空数组表示没有可补的空行
 */
export function planSameNameFill(
  rows: MappingUiRow[],
  sources: WorkflowMappingSource[]
): SameNameFillItem[] {
  const result: SameNameFillItem[] = []
  rows.forEach((row, rowIndex) => {
    if (!isMappingRowEmpty(row)) {
      return
    }
    const target = row.target.trim()
    // 没配 outputMapping 的节点后端只保留 {text}，所以 text 也要参与同名匹配
    const source = sources.find((item) => {
      const keys = item.outputKeys.length ? item.outputKeys : ['text']
      return keys.includes(target)
    })
    if (!source) {
      return
    }
    result.push({
      target,
      sourceNodeId: source.id,
      sourceField: target,
      rowIndex
    })
  })
  return result
}

/* ============ 出参点选 ============ */

/** 出参取值域固定为本节点原始产出：$.output / $.output.<field> */
const PATH_OUTPUT = /^\$\.output(?:\.(.+))?$/

/**
 * 出参一行 UI 状态。
 * <p>来源固定是本节点产出，不再出现前置节点 / 运行入参——后端 shapeOutput 只把它们包进
 * {@code {output: raw}}，选别的域运行时会落空。
 */
export interface OutputMappingRow {
  /** 目标字段，即落库对象的键 */
  target: string
  /** $.output 下的字段；空串表示整段产出 */
  field: string
  /** 是否已经选过字段（含「整段产出」）；新行未选时不落库 */
  picked: boolean
  /** true 表示手写 / 历史写法，原样落库 */
  manual: boolean
  /** 仅 manual 使用：常量或手写路径，保留原文 */
  text: string
}

/**
 * 出参字段 → 落库路径。
 *
 * @param field $.output 下的字段名；空串取整段产出
 * @return $.output 或 $.output.&lt;field&gt;
 */
export function outputFieldPath(field: string): string {
  const key = String(field ?? '').trim()
  return key ? `$.output.${key}` : '$.output'
}

/**
 * 已存出参值 → UI 行。
 * <p>只把 $.output / $.output.&lt;field&gt; 归一成点选；其它历史写法（如 $.input.*）原样留到手写，
 * 不猜语义，也不丢用户数据。
 *
 * @param target 目标字段
 * @param raw    落库值
 * @return UI 行
 */
export function parseOutputRow(target: string, raw: string): OutputMappingRow {
  const text = String(raw ?? '')
  const match = PATH_OUTPUT.exec(text.trim())
  if (match) {
    return { target, field: match[1] ?? '', picked: true, manual: false, text: '' }
  }
  return { target, field: '', picked: true, manual: true, text }
}

/**
 * 高级面板里直接改出参路径。
 * <p>能归一成 {@code $.output.*} 就回到点选；其它写法留到手写原样保存。
 * <p>空串回到「还没选字段」，不猜整段产出，避免把清空当成 {@code $.output}。
 *
 * @param row 原 UI 行
 * @param raw 高级面板里输入的路径
 * @return 新的 UI 行（不修改入参）
 */
export function applyOutputPathEdit(row: OutputMappingRow, raw: string): OutputMappingRow {
  const text = String(raw ?? '')
  if (!text.trim()) {
    return { target: row.target, field: '', picked: false, manual: false, text: '' }
  }
  return { ...row, ...parseOutputRow(row.target, text) }
}

/**
 * 出参 UI 行 → 扁平对象；空目标、空路径整行跳过。
 *
 * @param rows UI 行
 * @return 目标字段 → $.output.* / 手写值
 */
export function buildOutputMapping(rows: OutputMappingRow[]): Record<string, string> {
  const result: Record<string, string> = {}
  rows.forEach((row) => {
    const target = row.target.trim()
    if (!target) {
      return
    }
    // 新行还没选字段：不猜「整段产出」，整行不落库
    if (!row.manual && !row.picked) {
      return
    }
    const path = row.manual ? row.text : outputFieldPath(row.field)
    if (!path) {
      return
    }
    result[target] = path
  })
  return result
}

/* ============ 循环打回反馈点选 ============ */

/** 循环打回反馈的 UI 值：来源必须是打回边起点，取值域 $.outputs.&lt;code&gt;(&lt;field&gt;) */
export interface LoopFeedbackValue {
  /** 来源节点编码；空表示还没点选 */
  sourceCode: string
  /** 来源字段；空表示整段产出 */
  field: string
  /** true 表示手写 / 历史写法，原样落库 */
  manual: boolean
  /** 仅 manual 使用：手写原文 */
  text: string
}

/**
 * 点选结果 → feedbackPath。
 *
 * @param sourceCode 打回来源节点编码
 * @param field      来源字段；空串取整段产出
 * @return $.outputs.&lt;code&gt; 或 $.outputs.&lt;code&gt;.&lt;field&gt;；无来源返回空串
 */
export function loopFeedbackPath(sourceCode: string, field: string): string {
  const code = String(sourceCode ?? '').trim()
  const key = String(field ?? '').trim()
  if (!code) {
    return ''
  }
  return key ? `$.outputs.${code}.${key}` : `$.outputs.${code}`
}

/**
 * 已存 feedbackPath → UI 值。
 * <p>只认 $.outputs.&lt;code&gt;(&lt;field&gt;)；其它写法留到手写，不猜来源节点。
 *
 * @param raw 落库值
 * @return UI 值
 */
export function parseLoopFeedbackPath(raw: string): LoopFeedbackValue {
  const text = String(raw ?? '')
  const match = PATH_OUTPUTS.exec(text.trim())
  if (match) {
    return { sourceCode: match[1], field: match[2] ?? '', manual: false, text: '' }
  }
  return { sourceCode: '', field: '', manual: !!text.trim(), text }
}

/**
 * 打回边只有一条时预填该来源节点；多条或没有时返回空串，交给用户点选。
 * <p>已有配置一律保留，不覆盖；只补空值。
 *
 * @param current     当前 feedbackPath
 * @param backSources 指向本循环节点的 LOOP_BACK 起点
 * @return 预填路径；不预填返回空串
 */
export function singleBackSourcePrefill(
  current: string,
  backSources: WorkflowMappingSource[]
): string {
  if (String(current ?? '').trim()) {
    return current
  }
  if (backSources.length !== 1) {
    return ''
  }
  return `$.outputs.${backSources[0].code}`
}
