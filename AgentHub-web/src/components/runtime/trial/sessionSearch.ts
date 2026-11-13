/**
 * 试跑 / 工作台左栏「历史会话」的本地模糊搜索。所属域：runtime（前端）。
 *
 * <p>纯函数：不做请求，也不改分页协议——只对「已加载」的会话按展示文案做子串匹配。
 * 拆成纯函数是为了能在本地 tests/ 里直接覆盖多词、大小写与空关键字的边界。
 */
import type { SessionGroup } from './sessionGroup'
import type { TrialSession } from '@/types/runtime'

/**
 * 把用户输入拆成检索词：按空白切分，统一小写。
 *
 * <p>多词之间是「与」关系（都命中才算），符合用户「标题 + 时间/预览」混合输入的直觉；
 * 中文不需要额外分词，直接子串匹配即可满足左栏这种短文案检索。
 *
 * @param keyword 输入框原文
 * @return 小写检索词数组；空输入返回空数组（表示不过滤）
 */
export function splitSessionKeyword(keyword: string): string[] {
  return (keyword || '')
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
}

/**
 * 一条会话是否命中全部检索词。
 *
 * @param text   该行参与检索的展示文案（标题 + 副标题等，已拼好）
 * @param tokens {@link splitSessionKeyword} 的结果；空数组时恒真
 * @return true 表示应展示该行
 */
export function matchesSessionKeyword(text: string, tokens: string[]): boolean {
  if (!tokens.length) {
    return true
  }
  const target = (text || '').toLowerCase()
  return tokens.every((token) => target.includes(token))
}

/**
 * 过滤分组：保留组内命中的行，丢掉因此变空的组。
 *
 * <p>组的顺序与组内顺序都不变，避免搜索时列表「跳来跳去」；
 * 关键字为空时直接返回原数组引用，调用方可据此判断是否处于搜索态。
 *
 * @param groups  父组件已分好组的会话
 * @param textOf  取该行参与检索的展示文案
 * @param keyword 输入框原文
 * @return 过滤后的分组（可能为空数组）
 */
export function filterSessionGroups(
  groups: SessionGroup[],
  textOf: (item: TrialSession) => string,
  keyword: string
): SessionGroup[] {
  const tokens = splitSessionKeyword(keyword)
  if (!tokens.length) {
    return groups
  }
  const filtered: SessionGroup[] = []
  for (const group of groups) {
    const items = group.items.filter((item) => matchesSessionKeyword(textOf(item), tokens))
    if (items.length) {
      filtered.push({ label: group.label, items })
    }
  }
  return filtered
}
