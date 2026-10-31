<template>
  <!--
    飞卡幽灵层。所属域：前端壳。
    几何由 ghostFrame 内联；外观按 landing 对齐轨选中卡或网格卡。
  -->
  <Teleport to="body">
    <div
      v-if="snap && frame"
      class="flip-ghost"
      :class="landing === 'rail-selected' ? 'is-rail-selected' : 'is-grid-card'"
      :style="frameStyle"
      aria-hidden="true"
    >
      <h3>{{ snap.name }}</h3>
      <p v-if="snap.code" class="code">{{ snap.code }}</p>
      <div v-if="snap.tags?.length" class="badges">
        <span
          v-for="(tag, idx) in snap.tags"
          :key="`${tag.label}-${idx}`"
          class="ghost-tag"
          :class="toneClass(tag.tone)"
        >
          {{ tag.label }}
        </span>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
/**
 * FLIP 幽灵卡渲染。所属域：前端壳。
 * 内容只读 FlipCardSnap；禁止业务页各自拼一套幽灵 DOM。
 */
import { computed } from 'vue'
import type { CSSProperties } from 'vue'
import type { FlipCardSnap, GhostFrame, GhostLanding, FlipGhostTag } from '@/composables/viewFlip'

const props = defineProps<{
  snap: FlipCardSnap | null
  frame: GhostFrame | null
  landing: GhostLanding
}>()

/** GhostFrame 字段均为字符串内联样式，收窄为 CSSProperties 过模板检查。 */
const frameStyle = computed((): CSSProperties | undefined => {
  if (!props.frame) {
    return undefined
  }
  return props.frame as CSSProperties
})

/** tone → 幽灵标签修饰 class。 */
function toneClass(tone: FlipGhostTag['tone']) {
  if (tone === 'on') return 'is-on'
  if (tone === 'off') return 'is-off'
  if (tone === 'react') return 'is-react'
  if (tone === 'router') return 'is-router'
  if (tone === 'warn') return 'is-warn'
  return 'is-plain'
}
</script>
