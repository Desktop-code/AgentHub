/**
 * Skill 配置接口。所属域：skill。
 * 定义层（名称/标题/触发词/备注/启停）走 /skills；
 * 正文与简介属于版本，走 /skills/{id}/versions。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { SkillCard, SkillDetail, SkillVersionDetail, SkillVersionRow } from '@/types/skill'

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

/** 详情；contentMd 是生效已发布版本正文（只读展示）。 */
export function getSkill(id: number) {
  return request.get<Result<SkillDetail>>(`/api/skill/skills/${id}`)
}

/** 新建。不再接受手填版本号：服务端自动建 v1 草稿，需发布才生效。 */
export function createSkill(data: Record<string, unknown>) {
  return request.post<Result<SkillDetail>>('/api/skill/skills', data)
}

/** 保存定义层元数据（名称/标题/触发词/备注），不改启停、简介、正文与版本号。 */
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

/** 版本列表（不含正文），版本号倒序。 */
export function listSkillVersions(skillId: number) {
  return request.get<Result<SkillVersionRow[]>>(`/api/skill/skills/${skillId}/versions`)
}

/** 单个版本详情（含正文）。 */
export function getSkillVersion(skillId: number, versionId: number) {
  return request.get<Result<SkillVersionDetail>>(`/api/skill/skills/${skillId}/versions/${versionId}`)
}

/**
 * 新建草稿：不传 fromVersionId 为空白草稿，传则复制该已发布版本正文。
 * 该 Skill 已有草稿时后端拒绝。
 */
export function createSkillVersionDraft(skillId: number, data: { fromVersionId?: number }) {
  return request.post<Result<SkillVersionDetail>>(`/api/skill/skills/${skillId}/versions`, data)
}

/** 保存草稿简介与正文；已发布版本只读。 */
export function saveSkillVersionDraft(
  skillId: number,
  versionId: number,
  data: { description?: string; contentMd?: string }
) {
  return request.put<Result<SkillVersionDetail>>(
    `/api/skill/skills/${skillId}/versions/${versionId}`,
    data
  )
}

/** 删除草稿。只剩草稿可删：已发布版本是历史快照，后端会拒绝。 */
export function deleteSkillVersionDraft(skillId: number, versionId: number) {
  return request.delete<Result<null>>(`/api/skill/skills/${skillId}/versions/${versionId}`)
}

/** 发布草稿：成为唯一生效版本，并回写 skill_def 的生效号与简介。 */
export function publishSkillVersion(skillId: number, versionId: number) {
  return request.post<Result<SkillVersionDetail>>(
    `/api/skill/skills/${skillId}/versions/${versionId}/publish`
  )
}

/** 把历史已发布版本设回生效。 */
export function activateSkillVersion(skillId: number, versionId: number) {
  return request.post<Result<SkillVersionDetail>>(
    `/api/skill/skills/${skillId}/versions/${versionId}/activate`
  )
}
