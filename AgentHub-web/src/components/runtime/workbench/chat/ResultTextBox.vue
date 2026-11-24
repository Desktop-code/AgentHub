<template>
  <!--
    结果框外壳：复制按钮 + 正文插槽 + 底部「展开全文 / 收起」。
    正文本身由调用方放进默认插槽（Markdown 与限高类名仍归调用方），
    这里只负责框的形态与按钮，不读正文、也不自己量高度。
  -->
  <div class="wb-result-box">
    <button
      v-if="copyText"
      type="button"
      class="wb-copy"
      aria-label="复制"
      @click.stop.prevent="emit('copy')"
    >
      <el-icon :size="14"><CopyDocument /></el-icon>
    </button>
    <slot />
    <button
      v-if="action !== 'none'"
      type="button"
      class="wb-result-jump"
      :class="{ 'is-up': action === 'collapse' }"
      :aria-label="action === 'collapse' ? '收回四行' : '展开全文'"
      @click.stop.prevent="emit('toggle')"
    >
      <span class="wb-jump-label">{{ action === 'collapse' ? '收起' : '展开全文' }}</span>
      <span class="wb-jump-chevron" aria-hidden="true" />
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 结果框外壳（复制 / 展开全文）。所属域：runtime（前端展示）。
 *
 * <p>职责边界：只画框与按钮，不判断内容有没有超出四行。
 * 「能不能展开」由调用方测量后通过 `action` 传入，保证单专家卡与工作流卡共用同一套按钮形态。
 */
import { CopyDocument } from '@element-plus/icons-vue'
import type { BodyExpandAction } from '@/components/runtime/workbench/workbenchContentBox'

withDefaults(
  defineProps<{
    /** 展开按钮状态：none 不出现；expand / collapse 由调用方实测决定 */
    action?: BodyExpandAction
    /** 复制按钮的回传文本；空则不出现复制按钮 */
    copyText?: string
  }>(),
  { action: 'none', copyText: '' }
)

const emit = defineEmits<{
  /** 点了复制（复制内容与提示音由调用方处理） */
  copy: []
  /** 点了展开 / 收起 */
  toggle: []
}>()
</script>

<style scoped>
/* 结果框：复制图标仍只在移入时出现；展开入口由 .wb-result-jump 常驻在框底 */
.wb-result-box {
  position: relative;
}

.wb-copy {
  position: absolute;
  top: 4px;
  right: 4px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #6b7280;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
}

.wb-result-box:hover .wb-copy,
.wb-copy:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.wb-copy:hover {
  border-color: #d1d5db;
  background: #f9fafb;
  color: #374151;
}

/*
 * 结果超出四行后的展开入口：不再依赖 hover，作为结果框底部的常驻文字按钮。
 * 键盘 Tab 能聚焦（:focus-visible 有明确描边）；展开/收起只改显示范围，不动正文。
 */
.wb-result-jump {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  height: 24px;
  margin: 4px 0 0;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
}

.wb-result-jump:hover {
  background: #f4f4ff;
}

.wb-result-jump:focus-visible {
  outline: 2px solid var(--ah-primary, #635bff);
  outline-offset: 1px;
}

.wb-jump-label {
  white-space: nowrap;
}

.wb-jump-chevron {
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 6px solid currentColor;
}

.wb-result-jump.is-up .wb-jump-chevron {
  border-top: 0;
  border-bottom: 6px solid currentColor;
}
</style>
