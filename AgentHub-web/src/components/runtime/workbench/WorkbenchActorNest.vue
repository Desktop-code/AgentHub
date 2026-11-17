<script lang="ts">
/**
 * 方案框内部的子卡。所属域：runtime（前端）。
 *
 * <p>不再画一套新的专家卡：把页面已经写好的那块插槽按 begin 收进来的子卡再画一遍。
 * 汇总卡如果挂在 summary 上，由调用方排在子卡后面一并传进来。
 */
import { defineComponent, h, inject, type PropType } from 'vue'
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { WB_ACTOR_SLOT } from '@/components/runtime/workbench/workbenchActorSlot'

export default defineComponent({
  name: 'WorkbenchActorNest',
  props: {
    /** 这一层要画进方案框的卡，顺序已经是 begin 先后 */
    cards: { type: Array as PropType<ParticipantCard[]>, required: true }
  },
  setup(props) {
    const renderSlot = inject(WB_ACTOR_SLOT, null)
    return () => {
      if (!renderSlot) {
        return null
      }
      // 包一层只为给每张子卡稳定 key。display:contents 不占盒子，专家卡仍直接排在方案框里
      return props.cards.map((card) => {
        const rendered = renderSlot(card)
        if (rendered == null || typeof rendered === 'boolean') {
          return h('div', { key: card.key, class: 'wb-scheme-member' })
        }
        return h('div', { key: card.key, class: 'wb-scheme-member' }, rendered)
      })
    }
  }
})
</script>
