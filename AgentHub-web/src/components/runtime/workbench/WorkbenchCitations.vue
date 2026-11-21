<template>
  <!--
    贴在结果附近的引用区。只在已有出处时由外层挂上。
    默认一行摘要；点开后按文档分组，再点「查看」才展开该段原文。不跳文档页。
  -->
  <section v-if="board.documents.length" class="wb-cites" :class="{ 'is-attached': attached }" aria-label="引用">
    <button
      type="button"
      class="wb-cite-summary"
      :aria-expanded="boardOpen"
      :title="board.summary"
      @click.stop.prevent="toggleBoard"
    >
      <span class="wb-cite-summary-label">{{ board.summary }}</span>
      <span class="wb-cite-summary-action">{{ boardOpen ? '收起' : '展开' }}</span>
    </button>
    <div v-if="boardOpen" class="wb-cite-groups">
      <section v-for="doc in board.documents" :key="doc.key" class="wb-cite-doc">
        <div class="wb-cite-doc-head">
          <span class="wb-cite-doc-name" :title="doc.name">{{ doc.name }}</span>
        </div>
        <ul class="wb-cites-list">
          <li v-for="item in doc.snippets" :key="item.key" class="wb-cite">
            <button
              type="button"
              class="wb-cite-row"
              :aria-expanded="open[item.key] === true"
              :title="item.label"
              @click.stop.prevent="toggleSnippet(item.key)"
            >
              <span class="wb-cite-row-label">{{ item.label }}</span>
              <span class="wb-cite-row-action">{{ open[item.key] === true ? '收起' : '查看' }}</span>
            </button>
            <div v-if="open[item.key] === true" class="wb-cite-body">
              <p v-if="item.content" class="wb-cite-snippet">{{ item.content }}</p>
              <p v-else class="wb-cite-snippet is-empty">这段没有留下原文</p>
              <p v-if="showSource(item, doc.name)" class="wb-cite-source">来源：{{ item.source }}</p>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 工作台引用区。所属域：runtime（前端展示）。
 *
 * <p>外层已经按工具结果抽出出处，并在同一轮多次检索之间做过同段去重。
 * 这里只排版：默认收成一行，展开后按文档分组列出该篇全部片段。
 * 展开状态只留在当前屏，刷新后回到收起。不读事件、不发请求、不改正文。
 */
import { computed, reactive, ref } from 'vue'
import {
  arrangeCitations,
  type CitationSnippetView,
  type WorkbenchCitationView
} from '@/components/runtime/workbench/workbenchCitations'

const props = defineProps<{
  /** 这一张卡上已经抽出的出处，调用方保证非空才挂载 */
  items: WorkbenchCitationView[]
  /**
   * 是否贴在结果框内部。
   * true 时顶部分隔线把引用和正文分开；单独挂在框外时不再画这条线。
   */
  attached?: boolean
}>()

/** 摘要是否展开。新建即收起，不写入会话 */
const boardOpen = ref(false)

/** 哪一段原文展开。收起摘要时一并关掉，避免下次点开就把原文铺出来 */
const open = reactive<Record<string, boolean>>({})

/** 同一份出处的分组。items 引用不变时不重排 */
const board = computed(() => arrangeCitations(props.items))

/**
 * 展开或收起整块引用。收起时关掉已打开的原文，回到只有一行摘要。
 */
function toggleBoard() {
  boardOpen.value = !boardOpen.value
  if (!boardOpen.value) {
    for (const key of Object.keys(open)) {
      open[key] = false
    }
  }
}

/**
 * 展开或收起某一段已保存的原文。不影响其它段，也不打开文档页。
 *
 * @param key 这段的键
 */
function toggleSnippet(key: string) {
  open[key] = open[key] !== true
}

/**
 * 来源行是否还要单独写。和文档名相同时不再重复一行。
 *
 * @param item    一段
 * @param docName 这组的文档名
 * @return true 表示展开后补一行来源
 */
function showSource(item: CitationSnippetView, docName: string): boolean {
  const source = (item.source || '').trim()
  if (!source) {
    return false
  }
  return source !== docName && source !== (item.title || '').trim() && source !== item.label
}
</script>

<style scoped>
/*
 * 引用区比结果框降一档：默认只有一行摘要，不铺紫底、不用大圆角。
 * 只用 DESIGN 的令牌：8px 按钮圆角、6px 小控件、发丝线 #EBEEF5 / #F2F6FC，
 * 说明灰 #6B7280，紫色只在 hover 与聚焦时出现，不做静态装饰。
 */
.wb-cites {
  margin-top: 8px;
}

.wb-cites.is-attached {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ah-border-soft, #f2f6fc);
}

/* 摘要行：透明底 + 发丝线，读起来是「可展开的说明」，不是第三块卡片 */
.wb-cite-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 4px 6px;
  border: 0;
  border-radius: var(--ah-radius-md, 6px);
  background: transparent;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0.02em;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.16s ease;
}

.wb-cite-summary:hover {
  background: var(--ah-surface-muted, #fafafa);
}

.wb-cite-summary:focus-visible {
  outline: 2px solid var(--ah-primary, #635bff);
  outline-offset: 1px;
}

.wb-cite-summary-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-cite-summary-action {
  flex: none;
  font-size: 12px;
  font-weight: 400;
  color: var(--ah-text-secondary, #6b7280);
}

.wb-cite-summary:hover .wb-cite-summary-action {
  color: var(--ah-primary, #635bff);
}

.wb-cite-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 6px;
}

/* 文档组之间只用 1px 发丝线分隔，不再给每组套一块底色 */
.wb-cite-doc + .wb-cite-doc {
  padding-top: 10px;
  border-top: 1px solid var(--ah-border-soft, #f2f6fc);
}

.wb-cite-doc-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 6px;
  margin-bottom: 2px;
}

.wb-cite-doc-name {
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0.02em;
  color: var(--ah-text, #1f2937);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-cites-list {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 每条片段是一整行文字按钮，靠发丝线分隔；hover 才出现浅灰底 */
.wb-cite-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 6px;
  border: 0;
  border-radius: var(--ah-radius-md, 6px);
  background: transparent;
  color: var(--ah-text-regular, #374151);
  font-size: 13px;
  line-height: 1.4;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.16s ease;
}

.wb-cite-row:hover {
  background: var(--ah-surface-muted, #fafafa);
}

.wb-cite-row:focus-visible {
  outline: 2px solid var(--ah-primary, #635bff);
  outline-offset: 1px;
}

.wb-cite-row-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-cite-row-action {
  flex: none;
  font-size: 12px;
  font-weight: 400;
  color: var(--ah-text-secondary, #6b7280);
}

.wb-cite-row:hover .wb-cite-row-action {
  color: var(--ah-primary, #635bff);
}

/* 原文区：静灰底 + 8px 圆角，和结果正文同一套字号，不抢正文 */
.wb-cite-body {
  margin: 2px 0 4px;
  padding: 8px 10px;
  border-radius: var(--ah-radius-lg, 8px);
  background: var(--ah-surface-muted, #fafafa);
}

.wb-cite-snippet {
  margin: 0;
  max-height: 9.6em;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ah-text-regular, #374151);
  white-space: pre-wrap;
  word-break: break-word;
}

.wb-cite-snippet.is-empty,
.wb-cite-source {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ah-text-secondary, #6b7280);
}

.wb-cite-snippet.is-empty {
  margin-top: 0;
}
</style>
