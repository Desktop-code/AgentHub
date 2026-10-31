/**
 * 提示词 L1/L2 管理接口。所属域：prompt。
 * 本刀不含 Assembler / 试跑拼装。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  PromptCatalog,
  PromptConstraint,
  PromptConstraintDraft,
  PromptTemplate,
  PromptTemplateDraft,
  PromptTemplateQuery
} from '@/types/prompt'

/** 左栏目录：单 locale 下 L1 版本摘要 + 按 template_key 聚合的 L2 摘要。 */
export function getPromptCatalog(params?: { locale?: string }) {
  return request.get<Result<PromptCatalog>>('/api/prompt/catalog', { params })
}

/** L1 版本列表；locale 空则后端默认 zh-CN。 */
export function listConstraints(params?: { locale?: string }) {
  return request.get<Result<PromptConstraint[]>>('/api/prompt/constraints', { params })
}

/** L1 详情。 */
export function getConstraint(id: number) {
  return request.get<Result<PromptConstraint>>(`/api/prompt/constraints/${id}`)
}

/** L1 保存草稿（新建或更新草稿正文）。 */
export function saveConstraintDraft(data: PromptConstraintDraft) {
  return request.post<Result<PromptConstraint>>('/api/prompt/constraints/draft', data)
}

/** L1 发布并切换 is_active。 */
export function publishConstraint(id: number) {
  return request.post<Result<PromptConstraint>>(`/api/prompt/constraints/${id}/publish`)
}

/** L2 分页。templateKey 为精确键筛选（中栏取某模板全部版本）。 */
export function pageTemplates(params: PromptTemplateQuery) {
  return request.get<Result<PageResult<PromptTemplate>>>('/api/prompt/templates', { params })
}

/** L2 详情（含段落）。 */
export function getTemplate(id: number) {
  return request.get<Result<PromptTemplate>>(`/api/prompt/templates/${id}`)
}

/** L2 保存草稿（主表 + 段落同步）。 */
export function saveTemplateDraft(data: PromptTemplateDraft) {
  return request.post<Result<PromptTemplate>>('/api/prompt/templates/draft', data)
}

/** L2 发布。 */
export function publishTemplate(id: number) {
  return request.post<Result<PromptTemplate>>(`/api/prompt/templates/${id}/publish`)
}
