<template>
  <!--
    识别结果框。所属域：runtime（展示）。
    首页网关、MA 路由卡、管理端已有的图片文字预览共用：
    一个框里按附件名往下排，正文默认折叠。不另起多张卡。
  -->
  <div v-if="title || items.length" class="prep-box">
    <p v-if="title" class="prep-title">{{ title }}</p>
    <ul v-if="items.length" class="prep-list">
      <li v-for="(item, index) in items" :key="`${index}-${item.name}`" class="prep-item">
        <button type="button" class="prep-head" @click="toggle(index)">
          <span class="prep-name">{{ item.name }}</span>
          <span v-if="item.body" class="prep-action">{{ isOpen(index) ? '收起' : '展开' }}</span>
        </button>
        <p v-if="item.body && isOpen(index)" class="prep-body">{{ item.body }}</p>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
/**
 * 识别结果框。所属域：runtime（展示）。
 *
 * <p>条目由投影层拆好。这里只负责一个框、按名排列、默认折叠。
 * 不改识别原文，也不决定这条结果该不该出现。
 */
import { ref } from 'vue'
import type { PreprocessItem } from '@/components/runtime/trialMessages'

defineProps<{
  /** 框标题，例如已抽字并回传。进行中可以只有这一行 */
  title?: string
  /** 按发送顺序排好的条目 */
  items: PreprocessItem[]
}>()

/** 每条是否展开。默认都不展开，避免一上来铺满 */
const opened = ref<Record<number, boolean>>({})

/**
 * @param index 条目序号
 * @return 这一条是否展开
 */
function isOpen(index: number): boolean {
  return opened.value[index] === true
}

/**
 * @param index 条目序号
 */
function toggle(index: number) {
  opened.value = { ...opened.value, [index]: !opened.value[index] }
}
</script>

<style scoped>
.prep-box {
  margin: 0 0 8px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8f7ff;
}

.prep-title {
  margin: 0;
  color: #1f2937;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
}

.prep-list {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}

.prep-item + .prep-item {
  margin-top: 4px;
}

.prep-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.prep-name {
  overflow: hidden;
  color: #1f2937;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prep-action {
  flex: none;
  color: #635bff;
  font-size: 12px;
}

.prep-body {
  margin: 4px 0 0;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  writing-mode: horizontal-tb;
}
</style>
