<template>
  <!--
    可折叠代码块。所属域：obs（三栏列表 / 放大抽屉 / 评测右拉共用）。
    默认只露约 6 行做短预览，点「展开全文」后整块限高自滚，
    点「收起」回到短预览。入参与出参各用一份实例，互不影响。
  -->
  <div class="fold-code">
    <div v-if="label" class="fold-code__label">
      <span>{{ label }}</span>
      <!-- 行数只作参考：让人知道展开后大概多长，避免点开才发现巨长 -->
      <span v-if="overflow" class="fold-code__meta">共 {{ totalLines }} 行</span>
    </div>
    <pre
      class="fold-code__pre"
      :class="{ 'is-collapsed': !expanded, 'is-expanded': expanded }"
      :style="{ maxHeight: `${expanded ? expandedMaxHeight : previewHeight}px` }"
    ><code class="fold-code__code" v-html="html" /></pre>
    <!-- 只有真的超出预览高度才给按钮，短内容不出现多余操作 -->
    <div v-if="overflow" class="fold-code__foot">
      <button type="button" class="fold-code__toggle" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开全文' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 可折叠代码块（入参 / 出参 / 路由决策 / attrs 共用）。所属域：obs。
 *
 * <p>高度策略（用户 2026-11-04 拍板）：默认只露 4～6 行短预览，避免展开一张
 * LLM/TOOL 卡就占掉半屏把列表顶没；点「展开全文」后限高 240～320px 并在块内自滚，
 * 不再把整段文本铺满页面。宽度不变、并排布局不变。
 */
import { computed, ref, watch } from 'vue'
import { highlightCodeHtml } from '@/utils/codeDisplay'
import { isCodeOverflow } from '@/utils/codePreview'

const props = withDefaults(
  defineProps<{
    /** 区块标题，如「入参」「出参」 */
    label?: string
    /** 原文；空时展示占位 */
    text?: string | null
    /** 折叠态可见高度（px）：112 ≈ 6 行（12px 字号 / 1.55 行高） */
    previewHeight?: number
    /** 展开后区内自滚上限（px）；真正的全文靠滚动看，不再无限拉高 */
    expandedMaxHeight?: number
  }>(),
  { previewHeight: 112, expandedMaxHeight: 280 }
)

/** 展开态：默认短预览，点开才铺满（限高自滚） */
const expanded = ref(false)
/** 内容是否超出短预览：决定要不要出「展开全文」按钮 */
const overflow = ref(false)

const html = computed(() => highlightCodeHtml(props.text))

/** 总行数：用于标题右侧的「共 N 行」提示 */
const totalLines = computed(() => (props.text ?? '').split('\n').length)

/**
 * 是否超出短预览：判定逻辑收在 {@code utils/codePreview}（纯函数、可窄测）。
 * <p>用预览高度而不是展开高度做预算：否则内容比预览高、比展开矮时不显示按钮，
 * 预览被截断却没有入口看全文。
 */
function detectOverflow() {
  overflow.value = isCodeOverflow(props.text, props.previewHeight)
}

// 换内容就收回短预览，避免上一个 span 的展开态带到下一个
watch(
  () => [props.text, props.previewHeight] as const,
  () => {
    expanded.value = false
    detectOverflow()
  },
  { immediate: true }
)
</script>

<style scoped>
.fold-code {
  min-width: 0;
}

.fold-code__label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.fold-code__meta {
  color: var(--ah-text-secondary);
  font-size: 11px;
  font-weight: 400;
  font-variant-numeric: tabular-nums;
}

.fold-code__pre {
  margin: 0;
  padding: 10px 12px;
  background: #0f172a0a;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 短预览：裁掉超出部分并做淡出遮罩，暗示还有下文 */
.fold-code__pre.is-collapsed {
  overflow: hidden;
  mask-image: linear-gradient(180deg, #000 62%, transparent);
}

/* 展开：限高 + 块内自滚，高度封顶不再撑高整页 */
.fold-code__pre.is-expanded {
  overflow: auto;
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

.fold-code__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}

.fold-code__toggle {
  padding: 0;
  color: var(--ah-primary);
  background: none;
  border: 0;
  cursor: pointer;
  font-size: 12px;
}

.fold-code__toggle:hover {
  text-decoration: underline;
}
</style>
