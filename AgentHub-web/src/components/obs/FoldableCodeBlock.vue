<template>
  <!--
    可折叠代码块。所属域：obs。
    默认限高 150px；JSON 自动缩进并着色；超出时提供展开/收起。
  -->
  <div class="fold-code">
    <div v-if="label" class="fold-code__label">{{ label }}</div>
    <pre
      class="fold-code__pre"
      :class="{ 'is-collapsed': collapsed && overflow }"
      :style="collapsed && overflow ? { maxHeight: `${maxHeight}px` } : undefined"
    ><code class="fold-code__code" v-html="html" /></pre>
    <button v-if="overflow" type="button" class="fold-code__toggle" @click="collapsed = !collapsed">
      {{ collapsed ? '展开' : '收起' }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { highlightCodeHtml } from '@/utils/codeDisplay'

const props = withDefaults(
  defineProps<{
    /** 区块标题，如「入参」「出参」 */
    label?: string
    /** 原文；空时展示占位 */
    text?: string | null
    /** 折叠时最大高度（px） */
    maxHeight?: number
  }>(),
  { maxHeight: 150 }
)

const collapsed = ref(true)
const overflow = ref(false)

const html = computed(() => highlightCodeHtml(props.text))

/**
 * 按折叠高度粗估是否超出：窄栏 150px≈6 行，宽屏 400px≈18 行。
 * 用字符/行数判断，避免每帧量 DOM。
 */
function detectOverflow() {
  const raw = props.text ?? ''
  const lines = raw.split('\n').length
  const lineBudget = Math.max(4, Math.floor(props.maxHeight / 24))
  const charBudget = props.maxHeight * 4
  overflow.value = raw.length > charBudget || lines > lineBudget
}

onMounted(detectOverflow)
watch(() => [props.text, props.maxHeight], detectOverflow)
</script>

<style scoped>
.fold-code {
  min-width: 0;
}

.fold-code__label {
  margin-bottom: 6px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.fold-code__pre {
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  background: #0f172a0a;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.fold-code__pre.is-collapsed {
  overflow: hidden;
  mask-image: linear-gradient(180deg, #000 60%, transparent);
}

.fold-code__code {
  color: var(--ah-text);
  font-family: inherit;
}

.fold-code__code :deep(.cd-key) {
  color: #635bff;
}

.fold-code__code :deep(.cd-str) {
  color: #067647;
}

.fold-code__code :deep(.cd-num) {
  color: #b54708;
}

.fold-code__code :deep(.cd-lit) {
  color: #026aa2;
}

.fold-code__code :deep(.cd-empty) {
  color: var(--ah-text-secondary);
}

.fold-code__toggle {
  margin-top: 6px;
  padding: 0;
  color: var(--ah-primary);
  background: none;
  border: 0;
  cursor: pointer;
  font-size: 12px;
}
</style>
