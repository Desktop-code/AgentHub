/**
 * Skill 配置接口。所属域：skill。
 * 列表不带 contentMd；详情、新建、更新和启停的响应才有正文。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { SkillCard, SkillDetail } from '@/types/skill'

/** 卡片分页。size 不超过 100。 */
export function pageSkills(params: { current: number; size: number; keyword?: string; status?: number }) {
  return request.get<Result<PageResult<SkillCard>>>('/api/skill/skills', { params })
}

/** 详情，含 Markdown 正文。 */
export function getSkill(id: number) {
  return request.get<Result<SkillDetail>>(`/api/skill/skills/${id}`)
}

/** 新建。version 可空，后端默认 1。 */
export function createSkill(data: Record<string, unknown>) {
  return request.post<Result<SkillDetail>>('/api/skill/skills', data)
}

/** 保存元数据和正文，不改启停。 */
export function updateSkill(id: number, data: Record<string, unknown>) {
  return request.put<Result<SkillDetail>>(`/api/skill/skills/${id}`, data)
}

/** 逻辑删除。 */
export function deleteSkill(id: number) {
  return request.delete<Result<null>>(`/api/skill/skills/${id}`)
}

/** 启停。1 正常，0 停用。 */
export function changeSkillStatus(id: number, status: number) {
  return request.put<Result<SkillDetail>>(`/api/skill/skills/${id}/status`, { status })
}
