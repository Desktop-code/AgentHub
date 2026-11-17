<script setup lang="ts">
/**
 * 工作台消息流的根列表。所属域：runtime（前端）。
 *
 * <p>只负责把投影好的根卡交给页面插槽，并把这个插槽交给框内嵌套。
 * 父子不在这里重算：children / summary 已经按 begin 收好。
 */
import { provide, useSlots } from 'vue'
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { WB_ACTOR_SLOT, type ActorSlotRender } from '@/components/runtime/workbench/workbenchActorSlot'

defineProps<{
  /** 根上的入口 / 方案 / 没有父的块 */
  cards: ParticipantCard[]
}>()

const slots = useSlots()
if (slots.default) {
  // 渲染时再取插槽，避免 setup 当下把第一版画法冻死
  const renderSlot: ActorSlotRender = (card) => slots.default?.({ card })
  provide(WB_ACTOR_SLOT, renderSlot)
}
</script>

<template>
  <template v-for="card in cards" :key="card.key">
    <slot :card="card" />
  </template>
</template>
