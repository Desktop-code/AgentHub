/**
 * 提示词 L1/L2 前端类型。所属域：prompt。
 */

/** L1 平台约束 */
export interface PromptConstraint {
  id: number
  locale: string
  content: string
  version: number
  /** 0 草稿 1 已发布 */
  status: number
  isActive: number
  createBy?: number
  updateBy?: number
  createTime?: string
  updateTime?: string
}

/** L1 草稿入参 */
export interface PromptConstraintDraft {
  id?: number
  locale?: string
  content: string
}

/** L2 段落 */
export interface PromptSegment {
  id?: number
  templateId?: number
  segKey: string
  displayName: string
  sortNo: number
  mutable: number
  required: number
  /**
   * true 表示该段正文由平台锁定（记忆抽取的「抽取契约」「输出格式」），
   * 页面只读展示「平台锁定 · 不可修改」，保存时后端也会拒绝改写。
   */
  contentLocked?: boolean
  content: string
  createTime?: string
  updateTime?: string
}

/** L2 模板 */
export interface PromptTemplate {
  id: number
  templateKey: string
  name: string
  purpose: string
  locale: string
  protocol?: string | null
  version: number
  status: number
  isActive: number
  remark?: string | null
  createBy?: number
  updateBy?: number
  createTime?: string
  updateTime?: string
  segments?: PromptSegment[]
}

/** L2 草稿入参 */
export interface PromptTemplateDraft {
  id?: number
  templateKey?: string
  name: string
  purpose: string
  locale?: string
  protocol?: string
  remark?: string
  segments: PromptSegment[]
}

/** L2 分页查询 */
export interface PromptTemplateQuery {
  current: number
  size: number
  keyword?: string
  /** 业务键精确匹配：中栏取某模板全部版本时用，避免模糊关键词串味 */
  templateKey?: string
  purpose?: string
  locale?: string
  status?: number
  isActive?: number
}

/** 左栏 L1 版本摘要（无正文，仅卡片所需字段） */
export interface PromptCatalogConstraint {
  id: number
  locale: string
  version: number
  /** 0 草稿 1 已发布 */
  status: number
  isActive: number
  updateTime?: string
}

/** 左栏 L2 用途槽位摘要：一个 purpose 恒一条，template_key 由后端绑定，前端不再自由造键 */
export interface PromptCatalogTemplate {
  /** 用途码，与后端 PromptPurpose 同名，左栏槽位顺序即枚举顺序 */
  purpose: string
  /** 该槽位绑定的业务键；空槽时为约定键（用途名本身） */
  templateKey: string
  name: string
  locale: string
  /** 0 表示该用途尚无任何版本，即空槽 */
  versionCount: number
  /** 最新版本行 */
  latestId?: number
  latestVersion?: number
  /** 最新版本状态：0 草稿 1 已发布 */
  latestStatus?: number
  /** 当前生效版本；无则空 */
  activeId?: number
  activeVersion?: number
  updateTime?: string
}

/** 左栏目录：L1 版本摘要 + L2 模板摘要 */
export interface PromptCatalog {
  locale: string
  constraints: PromptCatalogConstraint[]
  templates: PromptCatalogTemplate[]
}
