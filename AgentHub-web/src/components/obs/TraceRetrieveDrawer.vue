<template>
  <!--
    知识库检索明细抽屉。所属域：obs（前端展示）。
    入口：主泳道里挂了 RETRIEVE 子步的 TOOL 卡；主泳道本身不再铺开这七步。
    内容：按执行序分段（run 级各成一段、多库按库分段），每步一块、块间分割线；
          状态 / skipReason / 耗时 / 片段表 / 可折叠入参出参。
  -->
  <el-drawer
    :model-value="modelValue"
    :title="title"
    direction="rtl"
    :size="DRAWER_SIZE"
    append-to-body
    class="retrieve-detail-drawer"
    @update:model-value="onVisible"
  >
    <div class="retrieve">
      <!-- 汇总行：一眼知道这次检索跑了几步、几路库、有没有跳过 -->
      <p class="retrieve__lead">
        共 {{ steps.length }} 个子步<template v-if="kbCount > 0">，涉及 {{ kbCount }} 个知识库</template>
        <template v-if="skipCount > 0">，跳过 {{ skipCount }} 步</template>
      </p>

      <p v-if="!segments.length" class="retrieve__empty">本次工具调用没有检索子步</p>

      <section v-for="segment in segments" :key="segment.key" class="seg">
        <!-- 多库分段：库号做小节标题；run 级步骤（无库）不出标题，避免空标题 -->
        <header v-if="segment.kbId != null" class="seg__head">
          <span class="seg__badge">知识库</span>
          <strong class="seg__kb">#{{ segment.kbId }}</strong>
          <span class="seg__count">{{ segment.steps.length }} 个子步</span>
        </header>

        <article
          v-for="step in segment.steps"
          :key="step.id"
          class="step"
          :class="`is-${stateOf(step)}`"
        >
          <header class="step__head">
            <span class="step__dot" :class="`is-${stateOf(step)}`" aria-hidden="true" />
            <span class="step__idx">#{{ step.stepNo || step.seq }}</span>
            <strong class="step__title">{{ titleOf(step) }}</strong>
            <code class="step__name">{{ step.name }}</code>
            <el-tag size="small" effect="plain" :type="tagTypeOf(step)">{{ stateLabel(step) }}</el-tag>
            <span class="step__latency">{{ latencyOf(step) }}</span>
          </header>

          <p v-if="hintOf(step)" class="step__hint">{{ hintOf(step) }}</p>
          <p v-if="noteOf(step)" class="step__note">{{ noteOf(step) }}</p>

          <!-- 片段表：chunkId 与四类分数，缺列显示 -；解析不到时不渲染，原文仍在下方面板 -->
          <div v-if="rowsOf(step).length" class="chunk">
            <div class="chunk__scroll">
              <table class="chunk__table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>chunkId</th>
                    <th>score</th>
                    <th>vector</th>
                    <th>bm25</th>
                    <th>rrf</th>
                    <th>rerank</th>
                    <th>hitBy</th>
                    <th>片段</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in rowsOf(step)"
                    :key="`${step.id}-${row.seq}-${row.chunkId}`"
                    :title="rowTip(row)"
                  >
                    <td class="is-num">{{ row.seq }}</td>
                    <td class="is-num">{{ row.chunkId }}</td>
                    <td class="is-num">{{ row.score }}</td>
                    <td class="is-num">{{ row.vector }}</td>
                    <td class="is-num">{{ row.bm25 }}</td>
                    <td class="is-num">{{ row.rrf }}</td>
                    <td class="is-num">{{ row.rerank }}</td>
                    <td>{{ row.hitBy }}</td>
                    <td class="chunk__snippet">
                      <span v-if="hasText(row.title)" class="chunk__title">{{ row.title }}</span>
                      <span v-if="hasText(row.snippet)">{{ row.snippet }}</span>
                      <span v-if="!hasText(row.title) && !hasText(row.snippet)">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-if="step.inputText || step.outputText" class="step__io">
            <FoldableCodeBlock
              v-if="step.inputText"
              label="入参"
              :text="step.inputText"
              :preview-height="88"
              :expanded-max-height="260"
            />
            <FoldableCodeBlock
              v-if="step.outputText"
              label="出参"
              :text="step.outputText"
              :preview-height="88"
              :expanded-max-height="260"
            />
          </div>
        </article>
      </section>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 知识库检索明细抽屉。所属域：obs（前端展示）。
 *
 * <p>只消费 Trace 现有的 RETRIEVE 子 span：步骤顺序、跳过关口、片段明细全部来自后端埋点，
 * 前端只做分组与排版，不重新计算分数、不改埋点口径。
 *
 * <p>宽度钉死 760px：抽屉要横向放下「chunkId + 四类分数 + 摘要」九列，
 * 用百分比会在窄屏挤成一列；超过屏宽时由 el-drawer 自身收敛。
 */
import { computed } from 'vue'
import type { ObsSpan } from '@/types/obs'
import FoldableCodeBlock from '@/components/obs/FoldableCodeBlock.vue'
import {
  parseChunkRows,
  retrieveAttrs,
  retrieveStepHint,
  retrieveStepState,
  retrieveStepTitle,
  segmentRetrieveSteps,
  skipReasonText,
  type ChunkRow
} from '@/utils/retrieveTrace'

/** 抽屉固定宽度：九列表格 + 代码块需要横向空间 */
const DRAWER_SIZE = '760px'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 工具名，进标题：检索明细 · searchKnowledgeBase */
    toolName?: string | null
    /** 该 TOOL 下的检索子步（顺序不限，组件内按执行序分段） */
    steps?: ObsSpan[]
  }>(),
  { toolName: '', steps: () => [] }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

/** 标题：工具名缺失时退回通用名，不出现「检索明细 · null」 */
const title = computed(() => `检索明细 · ${props.toolName?.trim() || 'searchKnowledgeBase'}`)

/** 分段：run 级步骤各自成段，多库按 kbId 连续成段 */
const segments = computed(() => segmentRetrieveSteps(props.steps))

/** 涉及的知识库数量：只统计真正带 kbId 的段 */
const kbCount = computed(
  () => new Set(segments.value.map((segment) => segment.kbId).filter((id) => id != null)).size
)

/** 被跳过的步骤数：标题行用一句话点明「有几步没跑」 */
const skipCount = computed(
  () => props.steps.filter((step) => retrieveStepState(step) === 'skip').length
)

/**
 * 每步的片段表缓存：解析一次，模板里反复取值不再重复跑正则。
 * <p>key 用 span.id：换 trace / 换工具时整张表重建，不会串上一轮结果。
 */
const rowsByStep = computed(() => {
  const map = new Map<number, ChunkRow[]>()
  for (const step of props.steps) {
    map.set(step.id, parseChunkRows(step.outputText))
  }
  return map
})

/** 取某步的片段行；无则空数组（模板里判空） */
function rowsOf(step: ObsSpan): ChunkRow[] {
  return rowsByStep.value.get(step.id) ?? []
}

/** 步骤状态：跳过 / 失败 / 进行中 / 正常，用于左侧色条与标签 */
function stateOf(step: ObsSpan): 'skip' | 'error' | 'ok' | 'running' {
  return retrieveStepState(step)
}

/** 状态标签配色：跳过用灰，失败红，进行中橙，正常绿 */
function tagTypeOf(step: ObsSpan): 'success' | 'danger' | 'info' | 'warning' {
  const state = stateOf(step)
  if (state === 'skip') return 'info'
  if (state === 'error') return 'danger'
  if (state === 'running') return 'warning'
  return 'success'
}

/** 状态文案：跳过必须带原因机器码的人话，否则「这步为什么没结果」无从判断 */
function stateLabel(step: ObsSpan): string {
  const state = stateOf(step)
  if (state === 'skip') {
    const reason = skipReasonText(retrieveAttrs(step.attrsJson).skipReason)
    return reason ? `跳过 · ${reason}` : '跳过'
  }
  if (state === 'error') return '失败'
  if (state === 'running') return '进行中'
  return '正常'
}

/** 步骤中文名 */
function titleOf(step: ObsSpan): string {
  return retrieveStepTitle(step.name)
}

/** 步骤说明 */
function hintOf(step: ObsSpan): string {
  return retrieveStepHint(step.name)
}

/** 耗时：与主泳道同口径，无值写 — */
function latencyOf(step: ObsSpan): string {
  return step.latencyMs == null ? '—' : `${step.latencyMs} ms`
}

/**
 * 一步的摘要行：优先后端 brief；再补命中数 / 零命中原因 / 工具结果词。
 * <p>都为空时返回空串，模板不渲染空行。
 */
function noteOf(step: ObsSpan): string {
  const attrs = retrieveAttrs(step.attrsJson)
  const parts: string[] = []
  if (attrs.brief) {
    parts.push(attrs.brief)
  }
  if (attrs.hitCount != null) {
    parts.push(
      attrs.rawCount != null && attrs.rawCount > attrs.hitCount
        ? `命中 ${attrs.hitCount} 条（过滤前 ${attrs.rawCount} 条）`
        : `命中 ${attrs.hitCount} 条`
    )
  }
  if (attrs.emptyReason) {
    parts.push(`零命中原因：${skipReasonText(attrs.emptyReason)}`)
  }
  if (attrs.outcome) {
    parts.push(`结果：${attrs.outcome}`)
  }
  // 失败摘要兜底：ERROR 步骤没有 brief 时，至少把原因带出来
  if (!parts.length && step.status === 'ERROR' && step.errorSummary) {
    parts.push(step.errorSummary)
  }
  return parts.join(' · ')
}

/** 行 tooltip：docId / kbId 在表格里省列，需要时悬停可见 */
function rowTip(row: ChunkRow): string {
  return `chunkId=${row.chunkId} · docId=${row.docId} · kbId=${row.kbId}`
}

/** 片段摘要是否有内容：后端空值统一写 "-"，这里按无内容处理 */
function hasText(value: string): boolean {
  return !!value && value !== '-'
}

/** 抽屉显隐：只回传事件，状态由调用方持有 */
function onVisible(value: boolean) {
  emit('update:modelValue', value)
}
</script>

<style scoped>
.retrieve {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 0 4px 24px;
}

.retrieve__lead {
  margin: 0;
  color: var(--ah-text-secondary);
  font-size: 12px;
}

.retrieve__empty {
  margin: 24px 0;
  color: var(--ah-text-secondary);
  font-size: 13px;
  text-align: center;
}

/* 段卡片：一段 = run 级单步，或一个知识库的全部检索步；段间距由容器 gap 控制 */
.seg {
  padding: 12px 14px;
  background: #fff;
  border: 1px solid var(--ah-border);
  border-radius: 12px;
}

.seg__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.seg__badge {
  padding: 1px 8px;
  color: #5b21b6;
  background: #ede9fe;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.seg__kb {
  color: var(--ah-text);
  font-size: 13px;
}

.seg__count {
  color: var(--ah-text-secondary);
  font-size: 12px;
}

/* 一整块：段内步骤靠分割线分隔，不再每步一张卡 */
.step {
  padding: 14px 2px;
}

.step + .step {
  border-top: 1px solid #e4e7ec;
}

.step__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

/* 状态点：跳过灰、失败红、进行中橙、正常绿；不靠整块底色表达 */
.step__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #12b76a;
}

.step__dot.is-skip {
  background: #98a2b3;
}

.step__dot.is-error {
  background: #f04438;
}

.step__dot.is-running {
  background: #f79009;
}

.step__idx {
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.step__title {
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 650;
}

/* 跳过的步骤弱化标题：仍可读，但不与真实跑过的步骤抢注意力 */
.step.is-skip .step__title {
  color: var(--ah-text-secondary);
}

/* 失败步骤的说明走警示色：抽屉里一眼能定位炸在哪一步 */
.step.is-error .step__note {
  color: #b42318;
}

/* 稳定步骤名直接露出：和评测检索链 / 后端日志同一串，便于对照 */
.step__name {
  padding: 1px 6px;
  color: #475467;
  background: #f2f4f7;
  border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
}

.step__latency {
  margin-left: auto;
  color: #475467;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.step__hint {
  margin: 6px 0 0;
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.step__note {
  margin: 6px 0 0;
  color: #475467;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.chunk {
  margin-top: 10px;
}

/* 窄屏横向自滚：宁可滚表，也不把九列挤成豆腐块 */
.chunk__scroll {
  overflow-x: auto;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
}

.chunk__table {
  width: 100%;
  min-width: 620px;
  border-collapse: collapse;
  font-size: 12px;
}

.chunk__table th,
.chunk__table td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid #f2f4f7;
  white-space: nowrap;
}

.chunk__table th {
  color: var(--ah-text-secondary);
  background: #f9fafb;
  font-weight: 600;
}

.chunk__table tbody tr:last-child td {
  border-bottom: 0;
}

.chunk__table .is-num {
  font-variant-numeric: tabular-nums;
}

/* 摘要列可长：限宽 + 省略号，悬停看行级 tooltip */
.chunk__snippet {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chunk__title {
  margin-right: 6px;
  color: var(--ah-primary);
}

.step__io {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}
</style>

<style>
/* 抽屉挂 body：宽度与滚动区要非 scoped 才能生效 */
.retrieve-detail-drawer .el-drawer__body {
  padding-top: 4px;
  overflow: auto;
}

.retrieve-detail-drawer.el-drawer {
  max-width: 100vw;
}
</style>
