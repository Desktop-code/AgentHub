/**
 * 方案框里复用同一套参与者卡插槽。所属域：runtime（前端）。
 *
 * <p>插槽画在页面组件里，样式才能跟着页面走。嵌套时由框内组件按这把钥匙把同一套插槽再叫一次，
 * 不另写第二套专家卡。
 */
import type { InjectionKey, VNodeChild } from 'vue'
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'

/** 用当前这张卡画出一块参与者（头像、思考、工具、结果都在插槽里） */
export type ActorSlotRender = (card: ParticipantCard) => VNodeChild

/** 页面插槽的注入钥匙。只在工作台消息流这一棵里用 */
export const WB_ACTOR_SLOT: InjectionKey<ActorSlotRender> = Symbol('wbActorSlot')
