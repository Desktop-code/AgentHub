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

/**
 * 拉取全部 Skill 卡片（含已停用），供 Agent「Skill 绑定」选择器分组展示。
 * <p>
 * size 上限 100，超过时按页累加；最多 20 页防御异常 total。
 * 卡片接口不返回正文，绑定时只需要标题/简介/关键词。
 *
 * @return 全量 Skill 卡片
 */
export async function listAllSkills(): Promise<SkillCard[]> {
  const size = 100
  const maxPages = 20
  const rows: SkillCard[] = []
  for (let page = 1; page <= maxPages; page += 1) {
    const { data } = await pageSkills({ current: page, size })
    const result = data.data
    const records = result?.records || []
    rows.push(...records)
    if (records.length < size || rows.length >= (result?.total ?? 0)) {
      break
    }
  }
  return rows
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
