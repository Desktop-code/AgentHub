/**
 * Agent 飞卡适配。所属域：agent。
 * 动画执行器已抽到 viewFlip；本文件只保留 Agent 行 → FlipCardSnap 映射，供列表页继续按旧名导入。
 */
import type { AgentCard } from '@/types/agent'
import type { FlipCardSnap } from '@/composables/viewFlip'
import { useViewFlip } from '@/composables/viewFlip'

export type { FlipCardSnap, FlipRect, GhostFrame, GhostLanding } from '@/composables/viewFlip'
export { rectOf, useViewFlip } from '@/composables/viewFlip'

/** 与历史命名兼容：Agent 页仍调 useAgentViewFlip。 */
export const useAgentViewFlip = useViewFlip

/** Agent 卡片压成飞卡快照。 */
export function snapFromCard(row: AgentCard): FlipCardSnap {
  return {
    id: row.id,
    name: row.name,
    code: row.code,
    tags: [
      {
        label: runModeLabel(row.runMode),
        tone: row.runMode === 'ROUTER' ? 'router' : 'react'
      },
      {
        label: row.status === 1 ? '启用' : '停用',
        tone: row.status === 1 ? 'on' : 'off'
      }
    ]
  }
}

/**
 * 运行模式展示名。三模式统一在这里取，避免列表 / 飞卡 / 抽屉各写一份三元表达式。
 *
 * @param runMode REACT | ROUTER | PLAN_AND_EXECUTE
 * @return 展示名；未知值按 ReAct
 */
export function runModeLabel(runMode?: string): string {
  if (runMode === 'ROUTER') {
    return 'Router'
  }
  if (runMode === 'PLAN_AND_EXECUTE') {
    return 'Plan-Execute'
  }
  return 'ReAct'
}
