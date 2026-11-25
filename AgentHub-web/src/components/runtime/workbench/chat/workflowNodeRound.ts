/**
 * 工作流节点多轮的展示键。所属域：runtime（前端展示）。
 *
 * <p>循环会让同一个节点编码出现多行。列表键和「第 N 轮」都从这里算，
 * 直播和回放读同一份，避免后一轮盖掉前一轮的 DOM。
 */
import type { TrialWorkflowNodeView } from '@/types/runtime'

/**
 * 同一节点出现多轮时给出「第 N 轮」。只有一轮且没有 attempt 时不额外占字。
 *
 * @param node 当前节点
 * @param peers 这张卡上的全部节点
 * @return 轮次文案；不需要标注时为空串
 */
export function wfNodeRoundLabel(
  node: TrialWorkflowNodeView,
  peers: TrialWorkflowNodeView[] | null | undefined
): string {
  const attempt = node.attempt && node.attempt > 0 ? node.attempt : 1
  const code = (node.code || '').trim()
  const same = (peers || []).filter((item) => (item.code || '').trim() === code && code)
  if (same.length <= 1 && attempt <= 1) {
    return ''
  }
  return `第 ${attempt} 轮`
}

/**
 * 列表键：同一编码的不同轮次不能共用一个键，否则后一轮会盖掉前一轮的 DOM。
 *
 * @param node 节点
 * @param index 列表下标，编码为空时用来区分
 * @return 稳定键
 */
export function wfNodeKey(node: TrialWorkflowNodeView, index: number): string {
  const attempt = node.attempt && node.attempt > 0 ? node.attempt : 1
  return `${node.code || 'node'}#${attempt}#${index}`
}
