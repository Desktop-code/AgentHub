<template>
  <ol class="timeline">
    <li v-for="(step, index) in steps" :key="index" class="timeline-item" :class="'step-' + step.type">
      <div class="timeline-rail" aria-hidden="true">
        <span class="timeline-dot" />
        <span class="timeline-line" />
      </div>
      <div class="timeline-main">
        <div class="step-title">{{ step.title }}</div>
        <div
          v-if="step.type === 'reasoning'"
          class="md think-body"
          v-html="markdown(step.text)"
        />
        <pre v-else-if="step.type === 'tool_call'" class="step-call">{{ step.text }}</pre>
        <div v-else-if="step.type === 'route'" class="route-body">{{ step.text }}</div>
        <div v-else class="step-result">
          <pre class="step-result-body" :class="{ clamped: !expanded[index] }">{{ step.text }}</pre>
          <button
            v-if="needsToggle(step.text)"
            type="button"
            class="result-toggle"
            @click.stop.prevent="toggle(index)"
          >
            {{ expanded[index] ? '收起' : '展开' }}
          </button>
        </div>
      </div>
    </li>
  </ol>
</template>

<script setup lang="ts">
/**
 * 单段时间线。所属域：runtime。
 * 用于计划执行模式的「计划阶段思考」与「第 N 步内部思考」：
 * 这两处都是扁平顺序（推理 → 工具调用 → 工具结果），不应生成「路由到 / 执行」这类分块头，
 * 因此不复用 Router 的 lane 渲染，避免多出无信息量的小标题。
 */
import { computed, reactive } from 'vue'
import type { ProcessStep } from '@/components/runtime/trialMessages'
import { renderSimpleMarkdown, renderStreamingMarkdown } from '@/utils/simpleMarkdown'

const props = defineProps<{
  /** 按事件顺序的时间线步骤 */
  steps: ProcessStep[]
  /** 是否仍在流式输出。为 true 时思考正文走流式解析，避免半截表格闪源码 */
  streaming?: boolean
}>()

/** 流式态传给 Markdown 渲染；模板里也直接读 streaming，这里显式说明来源便于阅读。 */
const isStreaming = computed(() => props.streaming === true)

/** 长工具结果的手动展开状态，按步骤下标记录。 */
const expanded = reactive<Record<number, boolean>>({})

/**
 * 思考与工具正文共用轻量 Markdown；流式走安全前缀渲染。
 * @param value 原文
 * @return 可直接 v-html 的 HTML
 */
function markdown(value: string) {
  return isStreaming.value ? renderStreamingMarkdown(value) : renderSimpleMarkdown(value)
}

/**
 * 多行或偏长时才给展开按钮；默认压成一行高度，避免长结果把计划卡挤下去。
 * @param text 结果正文
 */
function needsToggle(text: string) {
  return text.includes('\n') || text.length > 48
}

/**
 * 切换某条结果的展开态。
 * @param index 步骤下标
 */
function toggle(index: number) {
  expanded[index] = !expanded[index]
}
</script>

<style scoped>
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline-item {
  display: flex;
  gap: 10px;
  align-items: stretch;
}

.timeline-rail {
  position: relative;
  flex: none;
  width: 12px;
}

.timeline-dot {
  position: absolute;
  top: 5px;
  left: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ah-primary, #635bff);
  box-shadow: 0 0 0 2px #eef0ff;
}

.timeline-line {
  position: absolute;
  top: 16px;
  bottom: -4px;
  left: 5px;
  width: 2px;
  background: #e4e7ef;
}

.timeline-item:last-child .timeline-line {
  display: none;
}

.timeline-main {
  flex: 1;
  min-width: 0;
  padding-bottom: 12px;
}

.step-title {
  margin-bottom: 4px;
  color: #a3a3a3;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.3;
}

.think-body {
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.55;
}

.route-body {
  padding: 8px 10px;
  border-radius: 8px;
  background: #f3f4f8;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
}

.step-call {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #d6e4ff;
  background: #eef5ff;
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
}

.step-result {
  margin: 0;
}

.step-result-body {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #f7f8fb;
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
}

.step-result-body.clamped {
  /* 用单行省略，避免 max-height 被 padding 吃成半行 */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.result-toggle {
  margin-top: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ah-primary, #635bff);
  cursor: pointer;
  font-size: 12px;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4),
.md :deep(h5),
.md :deep(h6) {
  margin: 0.35em 0 0.2em;
  color: #a3a3a3;
  font-size: 1.05em;
  font-weight: 650;
  line-height: 1.35;
}

/* 思考流里的引用/分隔线同样弱化，保持「过程信息」的灰调 */
.md :deep(blockquote) {
  margin: 0.4em 0;
  padding: 2px 0 2px 10px;
  border-left: 3px solid #e0e0e0;
  color: #a3a3a3;
}

.md :deep(hr) {
  margin: 0.7em 0;
  border: none;
  border-top: 1px solid #e8e8e8;
}

.md :deep(a) {
  color: #635bff;
  text-decoration: none;
}

.md :deep(a:hover) {
  text-decoration: underline;
}

.md :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}

.md :deep(p) {
  margin: 0.35em 0;
}

.md :deep(ul),
.md :deep(ol) {
  margin: 0.35em 0;
  padding-left: 1.2em;
}

.md :deep(p),
.md :deep(li) {
  color: inherit;
}

.md :deep(strong) {
  color: #9a9a9a;
  font-weight: 700;
}

.md :deep(pre) {
  margin: 0.4em 0;
  padding: 8px 10px;
  overflow: auto;
  border-radius: 8px;
  background: #eef0f6;
}

.md :deep(code) {
  font-family: ui-monospace, Consolas, monospace;
}

.md :deep(.md-table-wrap) {
  display: block;
  max-width: 100%;
  margin: 0.45em 0;
  overflow-x: auto;
}

.md :deep(.md-table) {
  display: table;
  width: auto;
  max-width: 100%;
  border-collapse: collapse;
  border: 1px solid #d0d5dd;
  background: #fff;
  font-size: 1em;
  line-height: 1.45;
}

.md :deep(.md-table th),
.md :deep(.md-table td) {
  padding: 4px 8px;
  border: 1px solid #d0d5dd;
  text-align: left;
  vertical-align: top;
  white-space: normal;
}

.md :deep(.md-table th) {
  background: #f3f4f8;
  font-weight: 600;
}
</style>
