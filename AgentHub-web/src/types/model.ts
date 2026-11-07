/**
 * 模型管理接口类型。所属域：model。接口只返回掩码，任何响应都不含完整 Key。
 * 用途权威是英文码；分组仅控制台下拉展示，写死码→组，勿做分组配置表。
 */

export interface CredentialRow {
  id: number
  providerId: number
  name: string
  apiKeyMask: string
  status: number
  createTime?: string
}

export interface ProviderCard {
  id: number
  name: string
  protocol: string
  baseUrl: string
  status: number
  remark?: string
  credentialCount: number
  apiKeyMask?: string
  /** 名下存在平台级实例：删除入口改为锁标识，后端同样拒绝删除。 */
  platformLocked?: boolean | null
  createTime?: string
  updateTime?: string
  credentials: CredentialRow[]
}

export interface InstanceRow {
  id: number
  providerId: number
  providerName?: string
  credentialId: number
  credentialName?: string
  apiKeyMask?: string
  displayName: string
  modelId: string
  capability: string
  status: number
  /** 1=平台级全局实例（EMBEDDING / RERANK 各最多一条）；0/空=普通。 */
  platformLocked?: number | null
  /** 最近一次连通测试成功快照的向量维度；仅 EMBEDDING 有值。 */
  embeddingDim?: number | null
  extraJson?: string
  /** 最近连通时间；空表示还没测过。 */
  lastTestTime?: string
  /** 1 成功，0 失败。 */
  lastTestOk?: number | null
  lastTestMsg?: string
  createTime?: string
  updateTime?: string
}

export interface ConnectivityResult {
  success: boolean
  latencyMs: number
  message: string
  protocol: string
  capability: string
}

/**
 * 供应商协议全量码，与后端 `ModelProtocol` 枚举一致。
 * 注意：这是「库里可能存在」的全集，**不等于**可新建范围——新建/编辑请用 {@link PROTOCOL_OPTIONS}。
 * 本常量只用于「兼容类型」筛选：历史 CUSTOM 行仍要能被筛出来查看，故保留 CUSTOM。
 */
export const PROTOCOLS = ['OPENAI_COMPAT', 'DASHSCOPE', 'ANTHROPIC', 'CUSTOM'] as const

/**
 * 新建 / 编辑供应商时可选的协议。
 * 为什么不含 CUSTOM：ACL 工厂对 CUSTOM 直接抛错（本期无 Adapter，接不了模型），
 * 让它在新建下拉里出现只会把错误推迟到试跑时才暴露。
 */
export const PROTOCOL_OPTIONS = ['OPENAI_COMPAT', 'DASHSCOPE', 'ANTHROPIC'] as const

/**
 * 判断协议是否属于当前可选范围。
 * 用途：编辑历史数据时识别已下线协议，转入「只读回显 + 提示不可用」模式，禁止重新选择也禁止静默改库。
 *
 * @param code 协议码，可空
 * @return true 表示可以在新建/编辑里选择
 */
export function isSelectableProtocol(code?: string | null): boolean {
  if (!code) {
    return false
  }
  const key = code.trim()
  return (PROTOCOL_OPTIONS as readonly string[]).includes(key)
}

/** 用途分组（非库字段）：生成 / 检索 / 多模态 / 音频 / 安全 */
export interface CapabilityGroup {
  key: string
  label: string
  codes: readonly string[]
}

/**
 * 定稿五组全码；与后端 ModelCapability.byGroup 顺序对齐。
 * PDF 已退出；旧码由后端映射，前端只出新码。
 */
export const CAPABILITY_GROUPS: readonly CapabilityGroup[] = [
  { key: 'generation', label: '生成', codes: ['CHAT', 'ROUTER', 'REWRITE', 'SUMMARY', 'MEMORY'] },
  { key: 'retrieval', label: '检索', codes: ['EMBEDDING', 'RERANK'] },
  { key: 'multimodal', label: '多模态', codes: ['VISION'] },
  { key: 'audio', label: '音频', codes: ['ASR', 'TTS'] },
  { key: 'safety', label: '安全', codes: ['GUARDRAIL'] }
] as const

/**
 * Agent「模型配置」Tab 的用途槽分组。所属域：agent（前端展示常量）。
 * 只列本 Tab 可绑定的用途：
 * - SUMMARY / MEMORY：配置入口已迁到「记忆配置」Tab（上下文压缩 / 长期记忆区块旁），本 Tab 不展示；
 * - EMBEDDING：嵌入跟知识库 / 向量实例走，Agent 不直绑；
 * - RERANK：属检索链路，入口在 Agent 详情「知识库」Tab。
 * 模型实例自身仍可被创建为这些用途，故 {@link CAPABILITY_GROUPS} 保持全码。
 */
export const PURPOSE_SLOT_GROUPS: readonly CapabilityGroup[] = [
  { key: 'generation', label: '生成', codes: ['CHAT', 'ROUTER', 'REWRITE'] },
  { key: 'multimodal', label: '多模态', codes: ['VISION'] },
  { key: 'audio', label: '音频', codes: ['ASR', 'TTS'] },
  { key: 'safety', label: '安全', codes: ['GUARDRAIL'] }
] as const

/**
 * 记忆配置页承载的用途槽：SUMMARY 绑「上下文压缩」旁，MEMORY 绑「长期记忆」旁。
 * 与 PURPOSE_SLOT_GROUPS 互斥，避免同一用途出现两个配置入口。
 */
export const MEMORY_SLOT_PURPOSES = ['SUMMARY', 'MEMORY'] as const

/** 模型配置页单槽提示文案：与需求口径一致，避免文案各处漂移 */
export const PURPOSE_SLOT_HINT: Record<string, string> = {
  REWRITE: '检索 / 问答改写（若开）；与知识库检索开关配合，模型在此绑定'
}

/** 记忆配置页单槽提示文案：入口已在本页，不再外链到模型配置 */
export const MEMORY_SLOT_HINT: Record<string, string> = {
  SUMMARY: '滚动摘要 / 窗口压缩用哪台模型；绑定即时保存，空则走平台默认 → 环境默认 → 可降对话模型',
  MEMORY:
    '长期记忆抽取（空闲批抽 / 显式记住）用哪台模型；绑定即时保存，不借摘要模型。' +
    '分类（L1）与抽取（L2）默认共用这一台：若该模型带推理/思考，系统会自动调整分类请求（放大输出上限并关闭 think），无需再绑第二个模型'
}

/** 每槽绑定上限 */
export const PURPOSE_SLOT_MAX = 3


/** 扁平全码，便于校验与默认值 */
export const CAPABILITIES = CAPABILITY_GROUPS.flatMap((g) => g.codes)

/** 协议在卡片上的展示名；库内仍存枚举码。含已下线协议（CUSTOM），保证历史数据仍可读。 */
export const PROTOCOL_LABEL: Record<string, string> = {
  OPENAI_COMPAT: 'OpenAI 兼容',
  DASHSCOPE: 'DashScope',
  ANTHROPIC: 'Anthropic',
  // 已停止支持：无 ACL Adapter，接了也调不通；仅用于历史数据展示与筛选
  CUSTOM: '自定义（已停用）'
}

/** 用途中文标签；存库仍英文码。含旧码展示兜底（洗库前列表可读）。 */
export const CAPABILITY_LABEL: Record<string, string> = {
  CHAT: '对话',
  ROUTER: '路由',
  REWRITE: '改写',
  SUMMARY: '摘要',
  MEMORY: '记忆抽取',
  EMBEDDING: '嵌入',
  RERANK: '重排',
  VISION: '视觉',
  ASR: '语音识别',
  TTS: '语音合成',
  GUARDRAIL: '护栏',
  // 旧码兜底（补丁执行前）
  LLM: '对话(旧)',
  INTENT: '路由(旧)',
  EMBED: '嵌入(旧)',
  OCR: '视觉(旧)',
  PDF: '视觉(旧PDF)'
}

/**
 * 下拉选项文案：中文 + 英文码。
 *
 * @param code 用途码
 */
export function capabilityOptionLabel(code: string) {
  const zh = CAPABILITY_LABEL[code]
  return zh ? `${zh}（${code}）` : code
}

/**
 * 单用途的温度策略。与后端 `ModelTemperaturePolicy.Profile` 同值，改一处必须同步另一处。
 * sampling=false 表示该用途根本不采样：温度锁 0、控件禁用、出站不传 temperature。
 */
export interface TemperatureProfile {
  /** 是否走采样；false 时温度固定 0 且滑块禁用 */
  sampling: boolean
  /** 新建 / 切换用途时带入的默认值 */
  defaultTemperature: number
  /** 建议区间下界；仅作红字提示，不做硬校验 */
  suggestedMin: number
  /** 建议区间上界；仅作红字提示，不做硬校验 */
  suggestedMax: number
}

/** 对话 / 多模态对话类：允许发散 */
const CONVERSATION_TEMPERATURE: TemperatureProfile = {
  sampling: true,
  defaultTemperature: 0.7,
  suggestedMin: 0.3,
  suggestedMax: 1.0
}

/** 改写 / 摘要 / 记忆抽取 / 路由：要求稳定可复现 */
const DETERMINISTIC_TEMPERATURE: TemperatureProfile = {
  sampling: true,
  defaultTemperature: 0.2,
  suggestedMin: 0.1,
  suggestedMax: 0.3
}

/** 不采样用途：温度无意义，统一锁 0 */
const FIXED_ZERO_TEMPERATURE: TemperatureProfile = {
  sampling: false,
  defaultTemperature: 0,
  suggestedMin: 0,
  suggestedMax: 0
}

/**
 * 用途 → 温度策略。与后端 `ModelTemperaturePolicy.profileOf` 逐项对齐。
 * VISION 归入「多模态对话类」，与 CHAT 同档；ASR / TTS / GUARDRAIL 明确不采样。
 */
export const TEMPERATURE_PROFILES: Record<string, TemperatureProfile> = {
  CHAT: CONVERSATION_TEMPERATURE,
  VISION: CONVERSATION_TEMPERATURE,
  ROUTER: DETERMINISTIC_TEMPERATURE,
  REWRITE: DETERMINISTIC_TEMPERATURE,
  SUMMARY: DETERMINISTIC_TEMPERATURE,
  MEMORY: DETERMINISTIC_TEMPERATURE,
  EMBEDDING: FIXED_ZERO_TEMPERATURE,
  RERANK: FIXED_ZERO_TEMPERATURE,
  ASR: FIXED_ZERO_TEMPERATURE,
  TTS: FIXED_ZERO_TEMPERATURE,
  GUARDRAIL: FIXED_ZERO_TEMPERATURE
}

/**
 * 取用途的温度策略；未知码按对话处理，避免历史脏数据让表单卡住。
 *
 * @param capability 用途码（新旧码都行）
 * @return 策略快照
 */
export function temperatureProfileOf(capability?: string | null): TemperatureProfile {
  const code = normalizeCapability(capability)
  return TEMPERATURE_PROFILES[code] || CONVERSATION_TEMPERATURE
}

/** 与后端一致的旧码映射；编辑回填时把库内遗留值收到新码选项上 */
const LEGACY_CAPABILITY: Record<string, string> = {
  LLM: 'CHAT',
  INTENT: 'ROUTER',
  EMBED: 'EMBEDDING',
  OCR: 'VISION',
  PDF: 'VISION'
}

/**
 * @param raw 库内或接口返回的用途
 * @return 规范码；未知则原样
 */
export function normalizeCapability(raw?: string | null) {
  if (!raw) {
    return ''
  }
  const key = raw.trim()
  return LEGACY_CAPABILITY[key] || key
}
