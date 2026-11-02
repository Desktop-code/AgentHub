/**
 * 绑定选择器的数据映射。所属域：前端通用（agent 绑定 / components/common 共用）。
 *
 * 为什么单独抽出来：把「接口行 → 选择器分组」的规则从弹窗里剥离后，
 * 中文名兜底、停用置灰、已绑定剔除这几条口径可以单测，
 * 不必为了验证一个 title 字段回退就去点页面。
 */
import type { ResourceGroup, ResourceOption } from '@/types/resource'
import type { SkillCard } from '@/types/skill'
import type { ToolItemRow } from '@/types/tool'

/** 工具分组的标题常量：与工具目录页的卡片分类保持一致 */
export const TOOL_GROUP_BUILTIN = '内置工具'
export const TOOL_GROUP_MCP = 'MCP 工具源'

/**
 * 工具项 → 选择器选项。
 *
 * 字段口径（勿混）：`title` 是中文展示名，`name` 才是英文调用码。
 * 只显示 name 会让配置人员看不懂这一行到底调什么能力，因此主标题优先 title。
 *
 * @param row 工具项（来源类型取自接口的 sourceType）
 * @return 选择器选项
 */
export function toToolOption(row: ToolItemRow): ResourceOption {
  return {
    id: row.id,
    // 没有中文展示名时回落到英文码，避免出现空白标题
    name: row.title?.trim() || row.name,
    code: row.name,
    description: row.description?.trim() || '',
    extra: row.sourceName || '',
    // 停用项可见但不可新勾选：让人知道「有这个东西但用不了」
    disabled: row.status !== 1
  }
}

/**
 * 工具项全量 → 分组数据源。
 * <p>
 * 分组固定为内置 / MCP 两段，空段整体丢弃（不渲染 0 条的标题）；
 * 已绑定项直接剔除，避免重复绑定撞后端唯一约束。
 *
 * @param rows     全量工具项（含已停用）
 * @param boundIds 当前 Agent 已绑定的工具项 id
 * @return 供 ResourceSelector 使用的分组
 */
export function buildToolGroups(rows: ToolItemRow[], boundIds: number[]): ResourceGroup[] {
  const bound = new Set(boundIds)
  const candidates = rows.filter((row) => !bound.has(row.id))
  return [
    {
      groupName: TOOL_GROUP_BUILTIN,
      items: candidates.filter((row) => row.sourceType === 'BUILTIN').map(toToolOption)
    },
    {
      groupName: TOOL_GROUP_MCP,
      // 非 BUILTIN 的一律归 MCP：旧数据或类型为空时也不该掉出目录
      items: candidates.filter((row) => row.sourceType !== 'BUILTIN').map(toToolOption)
    }
  ].filter((group) => group.items.length > 0)
}

/**
 * Skill 卡片 → 选择器选项。
 *
 * @param card Skill 卡片（含 triggerKeywords，不含正文）
 * @return 选择器选项
 */
export function toSkillOption(card: SkillCard): ResourceOption {
  return {
    id: card.id,
    // 标题优先，缺标题时用编码兜底
    name: card.title?.trim() || card.name,
    code: card.name,
    description: card.description?.trim() || '',
    tags: card.triggerKeywords || [],
    disabled: card.status !== 1
  }
}

/**
 * Skill 全量 → 分组数据源。
 * <p>
 * 当前 Skill 没有二级分类，硬分组只会造出空标题，因此统一归入「Skill 库」一组。
 *
 * @param cards    全量 Skill 卡片（含已停用）
 * @param boundIds 当前 Agent 已绑定的 Skill id
 * @return 供 ResourceSelector 使用的分组；无候选时返回空数组
 */
export function buildSkillGroups(cards: SkillCard[], boundIds: number[]): ResourceGroup[] {
  const bound = new Set(boundIds)
  const items = cards.filter((card) => !bound.has(card.id)).map(toSkillOption)
  return items.length ? [{ groupName: 'Skill 库', items }] : []
}
