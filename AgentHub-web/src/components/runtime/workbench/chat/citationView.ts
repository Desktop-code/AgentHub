/**
 * 参与者卡片来源（引用）的展示视图。所属域：runtime（前端展示）。
 *
 * <p>职责：从参与者卡上已经投影好的工具步骤里抽出处，并按卡片对象缓存。
 * 不另拼一层时间线，也不改 turns：直播、收尾、回放读的是同一份 {@code card.steps}。
 *
 * <p>为什么单独成模块：单专家卡与工作流卡都要画引用，
 * 缓存按卡片对象（投影按内容签名重建卡片）天然失效，不需要额外签名比对。
 */
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { citationsFromToolSteps, type WorkbenchCitationView } from '@/components/runtime/workbench/workbenchCitations'

/** 一张卡上的引用缓存：同一帧模板会问多次，不必把工具结果再解析几遍。 */
const citationMemo = new WeakMap<ParticipantCard, WorkbenchCitationView[]>()

/**
 * 读这张卡上已经投影好的工具结果，抽出出处。
 *
 * <p>只在工作台计算；管理端运行不走这条模板，调用方负责再挡一次皮肤。
 *
 * @param card 参与者卡片
 * @return 可点来源；没有时为空数组
 */
export function citationsOf(card: ParticipantCard): WorkbenchCitationView[] {
  const cached = citationMemo.get(card)
  if (cached) {
    return cached
  }
  const items = citationsFromToolSteps(card.steps)
  citationMemo.set(card, items)
  return items
}

/**
 * 正文已经落地时，引用贴在结果框里。
 *
 * @param card 参与者卡片
 * @return true 表示画在结果框内部
 */
export function hasAnswerCitations(card: ParticipantCard): boolean {
  return !!card.answer && citationsOf(card).length > 0
}

/**
 * 工作流产物区已经有正文时，引用贴在那一块结果下面。
 *
 * @param card 参与者卡片
 * @return true 表示画在工作流结果区
 */
export function hasWorkflowCitations(card: ParticipantCard): boolean {
  return !!(card.workflow?.answer || '').trim() && citationsOf(card).length > 0
}

/**
 * 这一步已经结束，但没有成文正文。出处仍挂在原卡上，不插进还在生成的过程里。
 *
 * @param card 参与者卡片
 * @return true 表示在结果框外单独挂引用
 */
export function hasOrphanCitations(card: ParticipantCard): boolean {
  if (card.answer || (card.workflow?.answer || '').trim() || card.pending === true) {
    return false
  }
  return citationsOf(card).length > 0
}
