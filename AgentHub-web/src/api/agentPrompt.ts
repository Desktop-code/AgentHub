/**
 * L3 提示词配置接口。所属域：agent。
 * <p>
 * 三个接口：可选版本、钉死版段落、预览。
 * CHAT（agent_def）与非 CHAT（agent_model_bind）共用这套读接口，
 * 保存仍走各自的 agent / model-bind 写接口。
 */
import request from '@/utils/request'
import type { Result } from '@/types'
import type { PromptBinding, PromptPreviewResult, PromptTemplateOption, PromptTemplateView } from '@/types/agent'

/** 只提交后端 DTO 认的字段，避免把回显用的 supported 一起发上去 */
export interface PromptPreviewPayload extends PromptBinding {
  /** 用途码，决定组装走哪套 L2 */
  purpose: string
  locale?: string
  /** 供 {{agent_name}} */
  agentName?: string
  /** 供 {{user_query}}；用固定测试值，不传真实用户输入 */
  userQuery?: string
}

/** 该用途可选的已发布模板版本（草稿不返回）。 */
export function listPromptTemplates(params: { purpose: string; locale?: string }) {
  return request.get<Result<PromptTemplateOption[]>>('/api/agent/prompts/templates', { params })
}

/** 钉死版（或待升级版）模板与段落；mutable=0 只读，=1 可覆盖。 */
export function getPromptTemplate(id: number) {
  return request.get<Result<PromptTemplateView>>(`/api/agent/prompts/templates/${id}`)
}

/**
 * 预览组装结果。走后端 PromptAssembler，与出站同路径，**不调用模型**。
 * 缺 L1 / 缺模板 / 缺必选段时与运行时同语义失败。
 *
 * @param data 用途 + 四列表单值
 */
export function previewPrompt(data: PromptPreviewPayload) {
  return request.post<Result<PromptPreviewResult>>('/api/agent/prompts/preview', {
    purpose: data.purpose,
    locale: data.locale,
    templateId: data.templateId ?? null,
    templateVersion: data.templateVersion ?? null,
    override: data.override || {},
    promptExtra: data.promptExtra || undefined,
    agentName: data.agentName,
    userQuery: data.userQuery
  })
}
