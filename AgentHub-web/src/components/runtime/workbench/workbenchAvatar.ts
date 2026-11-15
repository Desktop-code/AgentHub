/**
 * 消息流头像口径：谁用固定图标、谁用形象色。所属域：runtime（前端展示）。
 *
 * <p>为什么单独一个纯模块：头像是否读 {@code lookOf} 由这里一句话决定，
 * 展示层（ChatPanel）与单测都读同一份判断，避免「页面上是齿轮、用例以为还是形象」。
 * 纯计算，不依赖 Vue / DOM。
 */
import { WORKFLOW_ROLE_LABEL } from './workbenchThinkTimeline.ts'

/**
 * 工作流头像的固定线稿色（近黑）。
 *
 * <p>为什么写死：工作流不是某位专家，头像不跟随任何保存的形象色；
 * 与消息流里的名称文字同色系，保证「浅底圆角方形 + 单色线框图标」这一套风格一致。
 */
export const WORKFLOW_AVATAR_COLOR = '#1f2430'

/**
 * 这一块是不是工作流（用固定齿轮头像）。
 *
 * <p>判据与角标同源：有执行卡片快照，或角色位就是「工作流 WF」（卡片快照还没到时的占位块）。
 * 只认这两条，不按展示名猜——工作流叫什么名字都不影响头像。
 *
 * @param card 参与者卡片（只需这两个字段）
 * @return true 表示头像固定为黑色齿轮，且不读 lookOf
 */
export function isWorkflowAvatarCard(card: {
  /** 工作流执行卡片快照；非工作流块为空 */
  workflow?: unknown
  /** 角色位角标；工作流占位块是「工作流 WF」 */
  roleLabel?: string
}): boolean {
  return !!card.workflow || card.roleLabel === WORKFLOW_ROLE_LABEL
}
