/**
 * Skill 卡片与详情。所属域：skill。
 * 列表类型没有正文，详情才有 contentMd。
 * 字段职责（与后端一致，勿混）：
 * - description：简介，供大模型判断何时加载此技能（会进 Prompt 的 Skill 目录）
 * - remark：备注，仅内部人员可见，永不注入模型
 * - triggerKeywords：触发关键词数组；用户输入命中任一词则后端强制注入正文
 */
export interface SkillCard {
  id: number
  name: string
  title: string
  description?: string
  /** 库内是逗号串，后端已拆成数组；空数组表示不会硬触发 */
  triggerKeywords?: string[]
  version: string
  status: number
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface SkillDetail extends SkillCard {
  contentMd?: string
}
