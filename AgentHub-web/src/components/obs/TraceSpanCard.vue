<template>
  <!--
    执行链路单步卡片。所属域：obs。
    按 span_type 动态展示 LLM/TOOL/ROUTE/RETRIEVE 专属字段；
    isWideMode 时入参/出参左右并排；树状缩进 + 耗时着色。
    入出参默认只露约 6 行短预览（FoldableCodeBlock），展开后限高自滚，避免整卡占满半屏。
  -->
  <article
    ref="cardRef"
    class="span-card"
    :id="`span-${span.id}`"
    :class="[
      `span-card--${typeKey}`,
      {
        'is-error': isError,
        'is-wide': isWideMode,
        'is-flat': hideIndent,
        'is-collapsed': collapsible && !open,
        'is-hit': isHit
      }
    ]"
    :style="[paletteStyle, indentStyle]"
  >
    <div v-if="!hideIndent && depth > 0" class="span-card__rail" aria-hidden="true" />

    <header
      class="span-card__head"
      :class="{ 'is-clickable': collapsible }"
      :role="collapsible ? 'button' : undefined"
      :tabindex="collapsible ? 0 : undefined"
      :aria-expanded="collapsible ? open : undefined"
      @click="onHeadClick"
      @keydown.enter.prevent="onHeadClick"
      @keydown.space.prevent="onHeadClick"
    >
      <!-- 折叠箭头：只有可折叠卡才出现，展开方向朝下 -->
      <el-icon v-if="collapsible" class="span-card__chevron" :class="{ 'is-open': open }">
        <ArrowRight />
      </el-icon>
      <span class="seq">#{{ span.stepNo || span.seq }}</span>
      <el-tag size="small" :type="typeTagType" effect="plain">{{ typeLabel }}</el-tag>
      <strong class="span-card__name">{{ displayName }}</strong>
      <!-- 失败只在文字层提示：类型色与卡片底色保持，不做整卡红，也不与状态标签重复 -->
      <span v-if="isError" class="err-flag">ERROR</span>
      <el-tag v-else size="small" :type="statusType">{{ statusLabel }}</el-tag>

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

    <!-- Body：折叠时只隐藏不销毁（v-show），保留代码块展开态与滚动位置 -->
    <div v-show="!collapsible || open" class="span-card__body">
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

    <!-- ERROR 必须顶显错误摘要；TOOL 失败改在卡尾右下角小红字，见 error-tail -->
    <p
      v-if="span.status === 'ERROR' && span.errorSummary && span.spanType !== 'TOOL'"
      class="error-box"
    >
      {{ span.errorSummary }}
    </p>

    <!-- ROUTE / RETRIEVE 专属行 -->
    <div v-if="span.spanType === 'ROUTE'" class="spec-block">
      <FoldableCodeBlock
        v-if="span.routeDecision"
        label="路由决策"
        :text="routeDecisionText"
        :preview-height="codePreviewHeight"
        :expanded-max-height="codeExpandedMaxHeight"
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
        <FoldableCodeBlock
          label="入参"
          :text="inputText"
          :preview-height="codePreviewHeight"
          :expanded-max-height="codeExpandedMaxHeight"
        />
      </div>
      <div v-if="outputText" class="io__cell">
        <FoldableCodeBlock
          label="出参"
          :text="outputText"
          :preview-height="codePreviewHeight"
          :expanded-max-height="codeExpandedMaxHeight"
        />
      </div>
    </div>
    <div v-else-if="span.content" class="io">
      <FoldableCodeBlock
        label="内容"
        :text="span.content"
        :preview-height="codePreviewHeight"
        :expanded-max-height="codeExpandedMaxHeight"
      />
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
            <template v-if="span.promptTokensEst != null"> · ≈{{ span.promptTokensEst }} tok</template>
            <template v-if="span.truncated"> · truncated</template>
          </li>
          <!-- 分段占比：保护区 / 可压区 / 记忆 / Skill / 目录 / schema / 用户，后端已压成一行 -->
          <li v-if="span.promptBreakdown">{{ span.promptBreakdown }}</li>
          <li v-if="(span.forcedSkillClipped ?? 0) > 0" class="extra__warn">
            强制 Skill 正文超上限已截断 {{ span.forcedSkillClipped }} 条
          </li>
          <!-- P1：长工具结果被落盘成文件，对话里只留摘要 + 路径；这里给出省下的原文字数 -->
          <li v-if="(span.toolResultsOffloaded ?? 0) > 0">
            工具结果已落盘 {{ span.toolResultsOffloaded }} 条
            （原文 {{ span.toolResultsOffloadedChars ?? 0 }} 字，对话内只留摘要 + 路径）
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
          :preview-height="codePreviewHeight"
          :expanded-max-height="codeExpandedMaxHeight"
        />
      </div>
    </div>

    <!-- 嵌套子卡插槽：AGENT 展开后渲染子 LLM/TOOL，不再画树状折线 -->
    <div v-if="$slots.default" class="span-card__children">
      <slot />
    </div>

    <!-- TOOL 失败：右下角小红字，保持卡片外框淡红 + 默认展开，一眼能认出哪一步炸了 -->
    <p v-if="span.spanType === 'TOOL' && isError" class="error-tail">
      {{ span.errorSummary || (span.toolSuccess === 0 ? '工具执行失败' : '执行失败') }}
    </p>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import type { ObsSpan } from '@/types/obs'
import type { SpanBar } from '@/utils/traceParallel'
import { traceTypeAccent } from '@/utils/tracePalette'
import { tryPrettyJson } from '@/utils/codeDisplay'
import FoldableCodeBlock from '@/components/obs/FoldableCodeBlock.vue'

const props = withDefaults(
  defineProps<{
    span: ObsSpan
    /** 放大抽屉宽屏：入参/出参左右对照；预览高度不变（高度靠短预览控，不随宽度放开） */
    isWideMode?: boolean
    /** 并行容器内取消树缩进，避免双列错位 */
    hideIndent?: boolean
    /** 相对整条链路时间轴的单色进度条；无时间时为空 */
    gantt?: SpanBar | null
    /** 是否可折叠：列表区 AGENT 大卡默认只留 Header */
    collapsible?: boolean
    /** 初始是否展开；省略时按「非 AGENT 默认展开」由调用方决定 */
    defaultOpen?: boolean
    /** 上方拓扑点中的节点 id：相等时该卡会强制展开、滚动到位并短暂高亮 */
    activeSpanId?: number | null
    /** 该卡是否位于「被点节点的祖先链」上：用于逐级自动展开，否则目标卡藏在折叠 Body 里 */
    forceOpen?: boolean
  }>(),
  {
    isWideMode: false,
    hideIndent: false,
    gantt: null,
    collapsible: false,
    defaultOpen: true,
    activeSpanId: null,
    forceOpen: false
  }
)

const extraOpen = ref(false)
/** 折叠态；不可折叠时恒为 true */
const open = ref(true)

/**
 * 折叠初始态：失败一律展开（ERROR 必须第一眼可见），否则用调用方给的值。
 * <p>依赖 span.id：复用同一个卡片组件渲染不同步骤时必须重算，否则会串展开态。
 */
watch(
  () => [props.span.id, props.collapsible, props.defaultOpen, isErrorGuard()] as const,
  () => {
    open.value = !props.collapsible ? true : props.defaultOpen || isErrorGuard()
  },
  { immediate: true }
)

/** 失败判定在初始化时就要用，单独抽一层避免 watch 回调里读不到 computed */
function isErrorGuard(): boolean {
  return (
    props.span.status === 'ERROR' ||
    (props.span.spanType === 'TOOL' && props.span.toolSuccess === 0)
  )
}

/** 点 Header 切换折叠；不可折叠时不响应 */
function onHeadClick() {
  if (!props.collapsible) {
    return
  }
  open.value = !open.value
}

/** 卡片根节点：定位时用来滚到视野中间 */
const cardRef = ref<HTMLElement | null>(null)
/** 命中高亮：滚到后几秒自动褪去，避免长期发光干扰阅读 */
const isHit = ref(false)
/** 高亮留存计时器：避免连点多个节点时上一张一直亮着 */
let highlightTimer: number | null = null

/**
 * 祖先卡自动展开：被点节点若藏在折叠的 AGENT Body 里，必须逐级打开才能看见。
 * <p>只负责展开，不负责收起——用户手动关掉后不要因为别的定位又被弹开。
 */
watch(
  () => props.forceOpen,
  (force) => {
    if (force && props.collapsible) {
      open.value = true
    }
  }
)

/**
 * 定位联动：拓扑点中本卡时展开自身并平滑滚到视野中间。
 * <p>滚动的时机必须是「祖先展开已经落到 DOM 之后」，否则祖先 Body 仍是 display:none，
 * scrollIntoView 等于没滚，所以这里等两轮 nextTick 再滚。
 */
watch(
  () => props.activeSpanId,
  (id) => {
    if (id == null || id !== props.span.id) {
      return
    }
    if (props.collapsible) {
      open.value = true
    }
    isHit.value = true
    if (highlightTimer != null) {
      window.clearTimeout(highlightTimer)
    }
    highlightTimer = window.setTimeout(() => {
      isHit.value = false
      highlightTimer = null
    }, 2400)
    void nextTick(() =>
      nextTick(() => {
        cardRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      })
    )
  }
)

// 卸载时清计时器，防止抽屉关掉后仍回调已销毁组件
onBeforeUnmount(() => {
  if (highlightTimer != null) {
    window.clearTimeout(highlightTimer)
  }
})

/**
 * 入出参短预览高度（px）：约 6 行（12px 字号 / 1.55 行高 ≈ 112）。
 * <p>宽窄栏一致——用户反馈的是「竖向太高」，宽屏给 400 只会让整卡更高，
 * 高度控制不该随宽度变化。
 */
const codePreviewHeight = computed(() => 112)

/**
 * 展开全文后的区内自滚上限（px）：宽屏 320、窄栏 260，都留出列表可见空间。
 * <p>展开不是「无限铺开」，而是「给更大的窗口 + 内部滚动」。
 */
const codeExpandedMaxHeight = computed(() => (props.isWideMode ? 320 : 260))

const depth = computed(() => Math.max(0, props.span.depth ?? 0))

/**
 * 失败口径与拓扑图保持一致：显式 ERROR，或工具自报失败（toolSuccess=0）。
 * <p>两处必须同口径，否则同一页里图上是红的、列表里像成功，排查会误判。
 */
const isError = computed(() => isErrorGuard())

/** 左侧缩进 + 给连接线留位；并行格内不缩进 */
const indentStyle = computed(() =>
  props.hideIndent ? undefined : { marginLeft: `${depth.value * 20}px` }
)

/**
 * 类型色：左侧色条取 {@code traceTypeAccent}（与拓扑同色相、半透明），
 * 卡片底色一律纯白，不再上类型底色。
 * <p>用 CSS 变量注入而不是给每个类型写一条 scoped 规则，新增类型不必再改样式表。
 */
const paletteStyle = computed(() => {
  return {
    '--span-accent': traceTypeAccent(props.span.spanType)
  } as Record<string, string>
})

const typeKey = computed(() => (props.span.spanType || 'step').toLowerCase())

const typeLabel = computed(() => {
  const t = props.span.spanType
  if (t === 'LLM') return '模型调用'
  if (t === 'TOOL') return '工具调用'
  if (t === 'VISION') return '图片识别'
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
  if (t === 'VISION') return 'success'
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
    s.promptBreakdown ||
    (s.toolResultsOffloaded ?? 0) > 0 ||
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
  /* 白卡，不上类型底色；左侧色条由 paletteStyle 注入的 CSS 变量决定（半透明同色相） */
  background: #fff;
  border: 1px solid var(--ah-border);
  border-radius: 10px;
  border-left: 3px solid var(--span-accent, var(--ah-border));
}

/* 折叠态只留 Header：上下留白略收，视觉上像一行可点的标题条 */
.span-card.is-collapsed {
  padding-top: 10px;
  padding-bottom: 10px;
}

/*
 * 失败态：三边框转淡红（外框淡红），左侧色条保持类型色。
 * 这样「同一类型同色」在图与列表都不破，失败另由红字 + 淡红外框表达。
 */
.span-card.is-error {
  border-color: #fda29b;
  border-left-color: var(--span-accent, var(--ah-border));
}

/*
 * 命中定位：淡紫描边 + 一圈柔和外发光，持续约 2.4s 后自动褪去。
 * 不用位移/缩放，避免滚动落点后再抖一下。
 */
.span-card.is-hit {
  border-color: #a5b4fc;
  box-shadow: 0 0 0 3px rgba(99, 91, 255, 0.16);
}

/* TOOL 卡尾右下角小红字；只报错误，不占 Header 注意力 */
.error-tail {
  margin: 8px 0 0;
  color: #d92d20;
  font-size: 11px;
  line-height: 1.4;
  text-align: right;
  word-break: break-word;
}

/* 与拓扑图同口径的 ERROR 小字：10～11px 红字，不做大红块 */
.err-flag {
  color: #dc2626;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

/* 列表内嵌：外层已用 gap 控距，这里只清掉自带外边距 */
.span-card.is-flat {
  margin-bottom: 0;
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

/* 可折叠：整行可点，键盘也能操作；hover 提示可点 */
.span-card__head.is-clickable {
  cursor: pointer;
  user-select: none;
}

.span-card__head.is-clickable:hover .span-card__name {
  color: var(--ah-primary, #635bff);
}

.span-card__chevron {
  color: var(--ah-text-secondary);
  font-size: 12px;
  transition: transform 0.15s ease;
}

.span-card__chevron.is-open {
  transform: rotate(90deg);
}

/* 嵌套子卡：只做缩进，不画树状折线/连接轨；层级靠卡片自带左侧色条区分 */
.span-card__children {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
  padding-left: 2px;
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

/* 强制 Skill 被硬上限截断：属于「规则不完整」，用警示色而非普通灰字 */
.extra__warn {
  color: #b54708;
}
</style>
