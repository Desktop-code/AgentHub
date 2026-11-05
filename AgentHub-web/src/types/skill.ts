/**
 * Skill 卡片与详情。所属域：skill。
 * 列表类型没有正文，详情才有 contentMd。
 * 字段职责（与后端一致，勿混）：
 * - description：简介，供大模型判断何时加载此技能（会进 Prompt 的 Skill 目录）；取自生效已发布版
 * - remark：备注，仅内部人员可见，永不注入模型
 * - triggerKeywords：触发关键词数组；用户输入命中任一词则后端强制注入生效版正文
 * - activeVersionNo：当前生效的已发布版本号；没发布过为 null
 * - hasDraft：是否存在草稿
 */
export interface SkillCard {
  id: number
  name: string
  title: string
  description?: string
  /** 库内是逗号串，后端已拆成数组；空数组表示不会硬触发 */
  triggerKeywords?: string[]
  /** 生效版本号字符串镜像；展示请优先用 activeVersionNo */
  version: string
  /** 当前生效的已发布版本号；未发布为 null/undefined */
  activeVersionNo?: number | null
  /** 是否存在草稿 */
  hasDraft?: boolean
  status: number
  remark?: string
  createTime?: string
  updateTime?: string
}

/**
 * Skill 详情。contentMd 是「生效已发布版本」正文，只读展示用；
 * 编辑正文请走版本接口。
 */
export interface SkillDetail extends SkillCard {
  contentMd?: string
}

/** Skill 版本列表行：不带正文。publishStatus 0 草稿 / 1 已发布；isActive 1 表示生效。 */
export interface SkillVersionRow {
  id: number
  skillId: number
  versionNo: number
  description?: string
  publishStatus: number
  isActive: number
  publishedAt?: string
  publishedBy?: number
  createTime?: string
  updateTime?: string
}

/** Skill 版本详情：在列表行上补 Markdown 正文。 */
export interface SkillVersionDetail extends SkillVersionRow {
  contentMd?: string
}
