<template>
  <!--
    执行链路单步卡片。所属域：obs。
    按 span_type 动态展示 LLM/TOOL/ROUTE/RETRIEVE 专属字段；
    isWideMode 时入参/出参左右并排；树状缩进 + 耗时着色。
  -->
  <article
    class="span-card"
    :class="[
      `span-card--${typeKey}`,
      {
        'is-error': span.status === 'ERROR',
        'is-wide': isWideMode,
        'is-flat': hideIndent
      }
    ]"
    :style="indentStyle"
  >
    <div v-if="!hideIndent && depth > 0" class="span-card__rail" aria-hidden="true" />

    <header class="span-card__head">
      <span class="seq">#{{ span.stepNo || span.seq }}</span>
      <el-tag size="small" :type="typeTagType" effect="plain">{{ typeLabel }}</el-tag>
      <strong class="span-card__name">{{ displayName }}</strong>
      <el-tag size="small" :type="statusType">{{ statusLabel }}</el-tag>

      <!-- LLM：模型名 / 厂商；TOOL：工具类型 -->
      <template v-if="span.spanType === 'LLM'">
        <el-tooltip v-if="llmTip" :content="llmTip" placement="top">
          <span class="chip chip--muted">{{ modelChip }}</span>
        </el-tooltip>
        <span v-else class="chip chip--muted">{{ modelChip }}</span>
        <el-tag v-if="span.finishReason" size="small" :type="finishTagType" effect="light">
          {{ span.finishReason }}
        </el-tag>
        <span v-if="tokenText" class="chip chip--tok">{{ tokenText }}</span>
      </template>

      <template v-else-if="span.spanType === 'TOOL'">
        <el-tooltip v-if="span.toolCallId" :content="`call id: ${span.toolCallId}`" placement="top">
          <span class="chip chip--muted">{{ span.toolType || 'TOOL' }}</span>
        </el-tooltip>
        <span v-else class="chip chip--muted">{{ span.toolType || 'TOOL' }}</span>
        <el-tag v-if="(span.retryCount ?? 0) > 0" size="small" type="danger" effect="dark">
          重试 {{ span.retryCount }} 次
        </el-tag>
        <el-tag
          v-if="span.toolSuccess != null"
          size="small"
          :type="span.toolSuccess === 1 ? 'success' : 'danger'"
          effect="plain"
        >
          {{ span.toolSuccess === 1 ? '成功' : '失败' }}
        </el-tag>
      </template>

      <span class="latency" :class="latencyClass">{{ latencyText }}</span>
    </header>

    <!-- 整条纯色：相对全部步骤的统一时间轴偏移和宽度 -->
    <div v-if="gantt" class="gantt">
      <el-tooltip :content="gantt.tip" placement="top">
        <div class="gantt__track">
          <span
            class="gantt__bar"
            :class="`is-${gantt.tone}`"
            :style="{ left: `${gantt.leftPct}%`, width: `${gantt.widthPct}%` }"
          />
        </div>
      </el-tooltip>
    </div>

    <!-- ERROR 必须顶显错误摘要 -->
    <p v-if="span.status === 'ERROR' && span.errorSummary" class="error-box">{{ span.errorSummary }}</p>

    <!-- ROUTE / RETRIEVE 专属行 -->
    <div v-if="span.spanType === 'ROUTE'" class="spec-block">
      <FoldableCodeBlock
        v-if="span.routeDecision"
        label="路由决策"
        :text="routeDecisionText"
        :max-height="codeMaxHeight"
      />
      <p v-if="span.childAgentId != null">
        分配至：{{ agentLabel(null, span.childAgentId) }}
      </p>
    </div>

    <div v-else-if="span.spanType === 'RETRIEVE'" class="spec-block">
      <p v-if="span.retrieveQuery">Query: {{ span.retrieveQuery }}</p>
      <p>
        命中 {{ span.retrieveHitCount ?? '—' }} 条 / Top {{ span.retrieveTopK ?? '—' }}
        <template v-if="span.retrieveKbId != null">（知识库 ID: {{ span.retrieveKbId }}）</template>
      </p>
    </div>

    <p v-if="span.brief" class="brief">{{ span.brief }}</p>

    <!-- 窄栏上下；宽屏且入参出参皆有时左右并排 -->
    <div
      v-if="inputText || outputText"
      class="io"
      :class="{ 'io--wide': sideBySide }"
    >
      <div v-if="inputText" class="io__cell">
        <FoldableCodeBlock label="入参" :text="inputText" :max-height="codeMaxHeight" />
      </div>
      <div v-if="outputText" class="io__cell">
        <FoldableCodeBlock label="出参" :text="outputText" :max-height="codeMaxHeight" />
      </div>
    </div>
    <div v-else-if="span.content" class="io">
      <FoldableCodeBlock label="内容" :text="span.content" :max-height="codeMaxHeight" />
    </div>

    <!-- 扩展信息默认收起：优先 attrs_json，并附带其它非空元数据 -->
    <div v-if="hasExtra" class="extra">
      <button type="button" class="extra__toggle" @click="extraOpen = !extraOpen">
        {{ extraOpen ? '收起扩展信息' : '查看扩展信息' }}
      </button>
      <div v-if="extraOpen" class="extra__body">
        <ul class="extra__meta">
          <li v-if="span.agentId != null">Agent：{{ agentLabel(span.agentName, span.agentId) }}</li>
          <li v-if="span.modelInstanceId != null">模型实例：#{{ span.modelInstanceId }}</li>
          <li v-if="span.toolId != null">工具 ID：#{{ span.toolId }}</li>
          <li v-if="span.toolsInjected != null || span.skillsInjected != null">
            注入 tools={{ span.toolsInjected ?? 0 }} skills={{ span.skillsInjected ?? 0 }}
            chars={{ span.promptChars ?? 0 }}
            <template v-if="span.truncated"> · truncated</template>
          </li>
          <li v-if="span.startTime">开始：{{ span.startTime }}</li>
          <li v-if="span.endTime">结束：{{ span.endTime }}</li>
          <li v-if="span.id != null">id：{{ span.id }}</li>
          <li v-if="span.parentId != null">parentId：{{ span.parentId }}</li>
          <li v-if="span.stepNo">stepNo：{{ span.stepNo }}</li>
        </ul>
        <FoldableCodeBlock
          v-if="span.attrsJson"
          label="attrs_json"
          :text="span.attrsJson"
          :max-height="codeMaxHeight"
        />
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ObsSpan } from '@/types/obs'
import type { SpanBar } from '@/utils/traceParallel'
import { tryPrettyJson } from '@/utils/codeDisplay'
import FoldableCodeBlock from '@/components/obs/FoldableCodeBlock.vue'

const props = withDefaults(
  defineProps<{
    span: ObsSpan
    /** 放大抽屉宽屏：入参/出参左右对照，代码块限高放宽 */
    isWideMode?: boolean
    /** 并行容器内取消树缩进，避免双列错位 */
    hideIndent?: boolean
    /** 相对整条链路时间轴的单色进度条；无时间时为空 */
    gantt?: SpanBar | null
  }>(),
  { isWideMode: false, hideIndent: false, gantt: null }
)

const extraOpen = ref(false)

/** 窄栏 150，放大模式 400 */
const codeMaxHeight = computed(() => (props.isWideMode ? 400 : 150))

const depth = computed(() => Math.max(0, props.span.depth ?? 0))

/** 左侧缩进 + 给连接线留位；并行格内不缩进 */
const indentStyle = computed(() =>
  props.hideIndent ? undefined : { marginLeft: `${depth.value * 20}px` }
)

const typeKey = computed(() => (props.span.spanType || 'step').toLowerCase())

const typeLabel = computed(() => {
  const t = props.span.spanType
  if (t === 'LLM') return '模型调用'
  if (t === 'TOOL') return '工具调用'
  if (t === 'ROUTE') return '路由'
  if (t === 'PLAN') return '规划'
  if (t === 'RETRIEVE') return '检索'
  if (t === 'AGENT') return 'Agent'
  if (t === 'SYSTEM') return '系统'
  return t || '步骤'
})

const typeTagType = computed((): 'primary' | 'success' | 'info' | 'warning' | 'danger' => {
  const t = props.span.spanType
  if (t === 'LLM') return 'primary'
  if (t === 'TOOL') return 'success'
  if (t === 'RETRIEVE') return 'warning'
  if (t === 'PLAN') return 'warning'
  if (t === 'ROUTE') return 'info'
  return 'info'
})

const displayName = computed(() => {
  const row = props.span
  if (row.spanType === 'LLM') return row.modelName || row.name || '模型'
  if (row.spanType === 'TOOL') return row.toolName || row.name || '工具'
  return row.name || typeLabel.value
})

const statusLabel = computed(() => {
  const s = props.span.status
  if (s === 'RUNNING') return '进行中'
  if (s === 'OK') return '成功'
  if (s === 'ERROR') return '失败'
  if (s === 'CANCELLED') return '已取消'
  return s || '—'
})

const statusType = computed((): 'success' | 'danger' | 'info' | 'warning' => {
  const s = props.span.status
  if (s === 'OK') return 'success'
  if (s === 'ERROR') return 'danger'
  if (s === 'CANCELLED') return 'info'
  return 'warning'
})

const latencyText = computed(() =>
  props.span.latencyMs == null ? '—' : `${props.span.latencyMs} ms`
)

/** >3000 红，>1000 橙，其余绿 */
const latencyClass = computed(() => {
  const ms = props.span.latencyMs
  if (ms == null) return 'latency--na'
  if (ms > 3000) return 'latency--danger'
  if (ms > 1000) return 'latency--warn'
  return 'latency--ok'
})

const modelChip = computed(() => {
  const name = props.span.modelName || props.span.name || '模型'
  const provider = props.span.provider
  return provider ? `${name} · ${provider}` : name
})

const llmTip = computed(() => {
  const parts: string[] = []
  if (props.span.temperature != null && props.span.temperature !== '') {
    parts.push(`temperature: ${props.span.temperature}`)
  }
  if (props.span.maxTokens != null) {
    parts.push(`max_tokens: ${props.span.maxTokens}`)
  }
  return parts.length ? parts.join(' · ') : ''
})

const finishTagType = computed((): 'success' | 'warning' | 'danger' | 'info' => {
  const r = (props.span.finishReason || '').toLowerCase()
  if (r === 'stop') return 'success'
  if (r === 'length') return 'warning'
  if (!r) return 'info'
  return 'danger'
})

const tokenText = computed(() => {
  const row = props.span
  if (row.tokenPrompt == null && row.tokenCompletion == null && row.tokenTotal == null) {
    return ''
  }
  const total = row.tokenTotal ?? (row.tokenPrompt ?? 0) + (row.tokenCompletion ?? 0)
  return `${total} tok (入: ${row.tokenPrompt ?? 0}, 出: ${row.tokenCompletion ?? 0})`
})

/** 有独立列时用独立列；皆空再回退 content（与后端约定一致） */
const inputText = computed(() => props.span.inputText || '')
const outputText = computed(() => props.span.outputText || '')

/**
 * 路由决策：能解析为 JSON 则缩进美化，供代码块高亮；否则原样。
 */
const routeDecisionText = computed(() => {
  const raw = props.span.routeDecision ?? ''
  return tryPrettyJson(raw) ?? raw
})

/** 宽屏且两侧都有内容时才并排，避免单侧空列 */
const sideBySide = computed(() => props.isWideMode && !!inputText.value && !!outputText.value)

const hasExtra = computed(() => {
  const s = props.span
  return !!(
    s.attrsJson ||
    s.agentId != null ||
    s.modelInstanceId != null ||
    s.toolId != null ||
    s.toolsInjected != null ||
    s.skillsInjected != null ||
    s.startTime ||
    s.id != null
  )
})

/** 有名称用名称，否则 #id */
function agentLabel(name: string | null | undefined, id: number | null | undefined) {
  if (name && name.trim()) return name
  if (id == null) return '—'
  return `#${id}`
}
</script>

<style scoped>
.span-card {
  position: relative;
  margin-bottom: 12px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid var(--ah-border);
  border-radius: 10px;
  border-left: 3px solid var(--ah-border);
}

.span-card--llm {
  border-left-color: #635bff;
}

.span-card--tool {
  border-left-color: #12b76a;
}

.span-card--route,
.span-card--agent,
.span-card--system {
  border-left-color: #98a2b3;
  background: #fafbfc;
}

.span-card--retrieve {
  border-left-color: #f79009;
}

.span-card.is-error {
  border-color: #fecdca;
  background: #fffbfa;
}

.span-card.is-flat {
  margin-bottom: 0;
  height: 100%;
}

.gantt {
  margin-top: 8px;
}

.gantt__track {
  position: relative;
  height: 8px;
  background: #e4e7ec;
  border-radius: 999px;
  overflow: hidden;
}

.gantt__bar {
  position: absolute;
  top: 0;
  bottom: 0;
  border-radius: 999px;
}

.gantt__bar.is-ok {
  background: #12b76a;
}

.gantt__bar.is-warn {
  background: #f79009;
}

.gantt__bar.is-danger {
  background: #f04438;
}

/* 父子树：左侧极细竖线 */
.span-card__rail {
  position: absolute;
  top: -8px;
  bottom: -8px;
  left: -12px;
  width: 0;
  border-left: 1px solid #d0d5dd;
}

.span-card__rail::after {
  content: '';
  position: absolute;
  top: 22px;
  left: 0;
  width: 10px;
  border-top: 1px solid #d0d5dd;
}

.span-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.seq {
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.span-card__name {
  font-size: 13px;
  font-weight: 650;
  color: var(--ah-text);
}

.chip {
  font-size: 12px;
  line-height: 1.3;
}

.chip--muted {
  color: var(--ah-text-secondary);
}

.chip--tok {
  color: #363f72;
  font-variant-numeric: tabular-nums;
}

.latency {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.latency--ok {
  color: #039855;
}

.latency--warn {
  color: #dc6803;
}

.latency--danger {
  color: #d92d20;
}

.latency--na {
  color: var(--ah-text-secondary);
  font-weight: 400;
}

.error-box {
  margin: 10px 0 0;
  padding: 8px 10px;
  color: #b42318;
  background: #fef3f2;
  border: 1px solid #fecdca;
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.5;
}

.spec-block {
  margin-top: 8px;
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px dashed #d0d5dd;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ah-text);
  display: grid;
  gap: 8px;
}

.spec-block p {
  margin: 0;
}

.brief {
  margin: 8px 0 0;
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.4;
}

.io {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
  margin-top: 10px;
}

/* 放大抽屉：入参 | 出参 左右对照 */
.io--wide {
  grid-template-columns: 1fr 1fr;
  gap: 0;
  align-items: start;
}

.io--wide .io__cell {
  min-width: 0;
}

.io--wide .io__cell:first-child {
  padding-right: 14px;
  border-right: 1px solid #d0d5dd;
}

.io--wide .io__cell:last-child {
  padding-left: 14px;
}

.extra {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ah-border);
}

.extra__toggle {
  padding: 0;
  color: var(--ah-primary);
  background: none;
  border: 0;
  cursor: pointer;
  font-size: 12px;
}

.extra__body {
  margin-top: 8px;
  display: grid;
  gap: 8px;
}

.extra__meta {
  margin: 0;
  padding-left: 1.1em;
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.55;
}
</style>
