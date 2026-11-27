/**
 * 入参声明的读写与出槽。所属域：workflow（前端），同时被 Agent 详情复用。
 * <p>声明形态固定为顶层 JSON Schema：{@code properties} 的键就是 {@code $.input} 下的字段名，
 * {@code required} 是其中必填的键。开始节点、Agent 定义、子图节点读取的都是这一份结构，
 * 因此出槽、必填提示、校验口径只需维护一处。
 * <p>为什么不用嵌套结构：画布槽位只看顶层，嵌套对象仍算一个槽；平台当前也没有
 * 「按子字段取值」的路径语法，存深了只会在运行期丢字段。
 * <p>纯函数、无 Vue 依赖：可在 node 里直接跑用例。
 */
import type { InputSlotDefinition } from './workflowInputSlots.ts'

/** 声明里能编辑的行（与详情页 / 属性面板表格一一对应） */
export interface InputDeclarationField {
  /** 键，即 $.input 下的字段名；同一份声明内唯一 */
  key: string
  /** 展示名；空串表示不显示，不自动翻译成中文 */
  title: string
  /** 是否必填：只影响画布标记与发布拦截，不改变对话入口仍只收一个问题 */
  required: boolean
  /** 说明；空串表示不写 */
  description: string
}

/** 单个字段的 schema（只保留平台认的字段，避免把任意 JSON 透传进库里） */
export interface InputDeclarationProperty {
  type: 'string'
  title?: string
  description?: string
}

/** 落库 / 传输用的声明结构 */
export interface InputDeclaration {
  type: 'object'
  properties: Record<string, InputDeclarationProperty>
  required?: string[]
}

/** 与后端 AgentInputSchema 的上限保持一致；前端先拦一次，给出可读提示 */
export const DECLARATION_MAX_FIELDS = 50
export const DECLARATION_KEY_MAX = 64
export const DECLARATION_TITLE_MAX = 64
export const DECLARATION_DESCRIPTION_MAX = 200

/** 判断任意值是否为普通对象（排除 null / 数组） */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 读取字符串字段并去空白；非字符串返回空串 */
function readText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * 声明原文 → 可编辑行。
 * <p>接受对象（开始节点 config、Agent 详情回显）与 JSON 字符串（工具形态）两种来源；
 * 解析失败、没有 properties、元素不是对象时按「没有声明」处理，不抛异常。
 *
 * @param raw 声明对象或 JSON 文本，可空
 * @return 声明行；保持 properties 的书写顺序
 */
export function parseDeclarationFields(raw: unknown): InputDeclarationField[] {
  let declaration: unknown = raw
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) {
      return []
    }
    try {
      declaration = JSON.parse(text)
    } catch {
      return []
    }
  }
  if (!isPlainObject(declaration)) {
    return []
  }
  const properties = declaration.properties
  if (!isPlainObject(properties)) {
    return []
  }
  const requiredList = Array.isArray(declaration.required)
    ? declaration.required
        .filter((item): item is string => typeof item === 'string')
        .map((item) => item.trim())
    : []
  const fields: InputDeclarationField[] = []
  Object.entries(properties).forEach(([key, definition]) => {
    const name = key.trim()
    if (!name) {
      return
    }
    const property = isPlainObject(definition) ? definition : {}
    fields.push({
      key: name,
      title: readText(property.title),
      required: requiredList.includes(name),
      description: readText(property.description)
    })
  })
  return fields
}

/**
 * 可编辑行 → 落库声明。
 * <p>空键直接丢掉；同名键只保留第一条（表格里会标重复，不允许保存歧义结构）；
 * 全部为空时返回 null，表示「没有声明」，由调用方决定是清空还是不改动。
 *
 * @param fields 编辑行
 * @return 声明对象；没有有效行时 null
 */
export function buildInputDeclaration(fields: InputDeclarationField[]): InputDeclaration | null {
  const properties: Record<string, InputDeclarationProperty> = {}
  const required: string[] = []
  ;(fields || []).forEach((field) => {
    const key = String(field?.key ?? '').trim()
    if (!key || properties[key]) {
      return
    }
    const property: InputDeclarationProperty = { type: 'string' }
    const title = String(field?.title ?? '').trim()
    if (title) {
      property.title = title
    }
    const description = String(field?.description ?? '').trim()
    if (description) {
      property.description = description
    }
    properties[key] = property
    if (field?.required) {
      required.push(key)
    }
  })
  if (!Object.keys(properties).length) {
    return null
  }
  const declaration: InputDeclaration = { type: 'object', properties }
  if (required.length) {
    declaration.required = required
  }
  return declaration
}

/**
 * 表格里重复的键（去空白后比较，空键不算重复）。
 * <p>JSON 对象本身不允许重复键，重复只可能出现在编辑表格的中间状态；
 * 这里列出来供页面标红并阻止保存，避免「后一行静默覆盖前一行」。
 *
 * @param fields 编辑行
 * @return 重复出现的键（按首次出现顺序）
 */
export function duplicateDeclarationKeys(fields: InputDeclarationField[]): string[] {
  const seen = new Set<string>()
  const duplicated: string[] = []
  ;(fields || []).forEach((field) => {
    const key = String(field?.key ?? '').trim()
    if (!key) {
      return
    }
    if (seen.has(key)) {
      if (!duplicated.includes(key)) {
        duplicated.push(key)
      }
      return
    }
    seen.add(key)
  })
  return duplicated
}

/**
 * 声明里的必填键：properties 与 required 取交集。
 *
 * @param raw 声明对象或 JSON 文本，可空
 * @return 必填键；无声明返回空数组
 */
export function declarationRequiredKeys(raw: unknown): string[] {
  return parseDeclarationFields(raw)
    .filter((field) => field.required)
    .map((field) => field.key)
}

/**
 * 声明 → 画布定义槽（不可改名、不可删，只用来选来源）。
 * <p>与工具槽同一展示口径：有展示名显示展示名，没有就只显示键名；不编造中文名。
 *
 * @param raw 声明对象或 JSON 文本，可空
 * @return 定义槽；没有声明返回空数组（编辑器保持自由添加）
 */
export function declaredInputSlots(raw: unknown): InputSlotDefinition[] {
  return parseDeclarationFields(raw).map((field) => ({
    key: field.key,
    title: field.title,
    description: field.description,
    required: field.required,
    origin: 'declaration' as const
  }))
}
