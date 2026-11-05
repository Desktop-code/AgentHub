<template>
  <!--
    MA 运行页中栏：多轮问答流。所属域：runtime（展示层）。
    每一轮固定三部分，顺序与 Agent 管理页对话一致：
      1) 用户问题——右对齐气泡，就是普通对话的样子；
      2) 模型思考——保留原来的协作卡排版（路由 → 子 Agent），只显示执行过程，
         运行中会转圈；汇总（父模型结果合成）算在路由卡上，不单出「结论」卡；
      3) 模型回答——正文用 Markdown 直接给用户，与对话页同一套渲染。
    点击任一区块都把右栏切到该轮；思考链与工具明细一律留在右栏。
  -->
  <div class="ma-flow">
    <div ref="scroller" class="ma-flow__scroll">
      <div v-if="!groups.length" class="ma-flow__empty">
        <h3>开始运行</h3>
        <p>在下方输入问题。左侧可切换历史会话；每一轮的思考过程与回答会按顺序排在这里。</p>
      </div>

      <section
        v-for="group in groups"
        :key="group.key"
        class="ma-turn"
        :class="{ 'is-selected': group.key === selectedKey }"
      >
        <!-- 1) 用户问题：与对话页一致，靠右气泡；点它即把右栏切到本轮 -->
        <div class="ma-turn__ask" @click="emit('select', group.key)">
          <div class="ma-turn__bubble" :title="group.question">{{ group.question }}</div>
        </div>

        <!-- 连接断开：只能等落库，如实提示，不假装还在收增量 -->
        <p v-if="group.status === 'running' && detached" class="ma-turn__detached">
          连接已断开，等待本轮结束
        </p>

        <!-- 2) 模型思考：保持原有卡片排版，只显示执行过程；汇总算在路由卡上 -->
        <div v-if="group.cards.length" class="ma-run" @click="emit('select', group.key)">
          <div class="ma-run__head">
            <span class="ma-run__label" aria-hidden="true">运</span>
            <span class="ma-run__title">运行</span>
            <span v-if="turnTime(group)" class="ma-run__time">{{ turnTime(group) }}</span>
          </div>
          <div class="ma-run__cards">
            <MaRunCard v-for="card in group.cards" :key="card.key" :card="card" />
          </div>
        </div>

        <!-- 3) 模型回答：正文直接用 Markdown 给到用户，与对话页同一套渲染 -->
        <div v-if="group.answer" class="ma-turn__answer" @click="emit('select', group.key)">
          <div class="md answer" v-html="markdown(group.answer, group.status === 'running')" />
          <span v-if="group.status === 'running'" class="ma-turn__caret">|</span>
        </div>
        <p
          v-for="(err, index) in errorsOf(group)"
          :key="`e-${index}`"
          class="ma-turn__error"
          @click="emit('select', group.key)"
        >
          {{ err }}
        </p>
        <!-- 还在跑且还没有正文：只给一句状态，不编造内容 -->
        <p
          v-if="group.status === 'running' && !group.answer && !errorsOf(group).length"
          class="ma-turn__pending"
        >
          思考中…
        </p>
      </section>
    </div>

    <!-- 输入区：与试跑一致，Enter 发送、Shift+Enter 换行；本页不做图片上传 -->
    <div class="ma-flow__composer">
      <el-input
        v-model="text"
        type="textarea"
        :rows="3"
        maxlength="8000"
        placeholder="输入问题，Enter 发送，Shift+Enter 换行"
        :disabled="!canSend || sending"
        @keydown.enter.exact.prevent="submit"
      />
      <div class="ma-flow__bar">
        <span class="ma-flow__hint">{{ sessionReady ? '' : blockedHint || '请先新建会话或从左侧选择' }}</span>
        <el-button v-if="canSend" type="primary" :loading="sending" :disabled="!sessionReady" @click="submit">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * MA 运行页中栏多轮问答流。所属域：runtime（展示层）。
 *
 * <p>职责：把 {@link MaRunGroup} 按「问题 → 思考 → 回答」三段铺开，并把「点哪一轮」
 * 交回父组件；自身只负责滚动、选中态与输入区，不发任何请求。
 *
 * <p>约束：
 * - 卡片内容由 maRunView 归纳，本组件不读 spans 明细，思考链与工具正文留在右栏；
 * - 思考段只显示执行过程（含运行中状态），汇总算在路由卡上，不单出结论卡；
 * - 回答段与 Agent 管理页对话同一套 Markdown 渲染，正文直接给用户；
 * - 新内容到达时只在「用户仍在看最后一轮」的情况下自动滚到底，避免打断回看；
 * - 输入为空、无权限或正在发送时不提交。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { MaRunGroup } from '@/components/runtime/ma/maRunView'
import { formatDuration } from '@/components/runtime/ma/maRunView'
import MaRunCard from '@/components/runtime/ma/MaRunCard.vue'
import { renderSimpleMarkdown, renderStreamingMarkdown } from '@/utils/simpleMarkdown'

const props = defineProps<{
  /** 全部运行轮次，按提问顺序 */
  groups: MaRunGroup[]
  /** 当前选中的轮次 key；右栏绑定它 */
  selectedKey: string
  /** 本轮是否正在执行（发送 / 续订） */
  sending: boolean
  /** 是否有运行权限 */
  canSend: boolean
  /** 当前会话是否可继续发送 */
  sessionReady: boolean
  /** 不能发送时的说明 */
  blockedHint?: string
  /** 是否已与后端断开、只能等落库 */
  detached?: boolean
  /** 每秒刷新的当前时间戳 */
  now: number
}>()

const emit = defineEmits<{
  /** 选中某一轮：右栏跟随切换 */
  select: [key: string]
  /** 发送用户输入 */
  send: [content: string]
}>()

const text = ref('')
const scroller = ref<HTMLElement>()

/** 最后一轮 key：决定新内容到达时是否自动跟随滚动 */
const lastKey = computed(() => props.groups[props.groups.length - 1]?.key ?? '')

/**
 * 运行区标题右侧的耗时。
 *
 * <p>运行中显示已运行时长；结束后有总耗时才显示，拿不到时间就不占位。
 */
function turnTime(group: MaRunGroup): string {
  const duration = formatDuration(group.durationMs)
  if (group.status === 'running') {
    return duration ? `进行中 ${duration}` : '进行中'
  }
  if (group.status === 'error') {
    return duration ? `失败 · ${duration}` : '失败'
  }
  return duration ? `耗时 ${duration}` : ''
}

/**
 * 本轮的失败块：与对话页一致，失败正文用红字单独给出，不混进回答。
 *
 * @param group 运行轮次
 * @return 失败说明列表；没有失败返回空数组
 */
function errorsOf(group: MaRunGroup): string[] {
  return (group.assistant?.blocks ?? [])
    .filter((block) => block.kind === 'error')
    .map((block) => (block.text || '').trim())
    .filter(Boolean)
}

/**
 * 回答正文渲染：流式时用流式渲染（未闭合的代码块也能看），结束后用普通渲染。
 *
 * @param value     正文
 * @param streaming 是否仍在流式输出
 * @return HTML 字符串
 */
function markdown(value: string, streaming = false): string {
  return streaming ? renderStreamingMarkdown(value) : renderSimpleMarkdown(value)
}

/** 滚到底部 */
function scrollToBottom() {
  const element = scroller.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

/**
 * 内容签名：只包含轮数、卡片数、状态与回答长度，不包含每秒变化的耗时。
 * 用它在「有新卡 / 状态变化 / 正文增长」时滚到底，而不是每秒都触发滚动。
 */
const scrollSignature = computed(() =>
  props.groups
    .map(
      (group) =>
        `${group.key}:${group.cards.length}:${group.cards.map((card) => card.status).join(',')}:${group.status}:${group.answer.length}`
    )
    .join('|')
)

// 新内容到达：用户仍停在最后一轮时自动跟随；正在回看旧轮则不打扰
watch(scrollSignature, async () => {
  if (props.selectedKey && props.selectedKey !== lastKey.value) {
    return
  }
  await nextTick()
  scrollToBottom()
})

onMounted(() => {
  void nextTick().then(scrollToBottom)
})

/** 提交输入：空内容、无权限、未就绪或发送中都直接忽略 */
function submit() {
  const content = text.value.trim()
  if (!content || !props.canSend || !props.sessionReady || props.sending) {
    return
  }
  emit('send', content)
  text.value = ''
}
</script>

<style scoped>
/* 只靠 flex:1 撑满中栏剩余高度；写死 100% 会在上方有告警/待批准卡片时把输入区顶出视口 */
.ma-flow {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.ma-flow__scroll {
  flex: 1;
  min-height: 0;
  padding-right: 4px;
  overflow: auto;
}

.ma-flow__empty {
  padding: 48px 12px;
  color: var(--ah-text-secondary);
  text-align: center;
}

.ma-flow__empty h3 {
  margin: 0 0 8px;
  color: var(--ah-text);
  font-size: 18px;
}

.ma-flow__empty p {
  margin: 0;
  font-size: 13px;
}

/* 一轮：三段之间用间距分隔，不套大框，避免把问答又框成一张卡 */
.ma-turn {
  margin-bottom: 20px;
}

/* 1) 用户问题：与对话页一致，靠右气泡，最大 78% 宽 */
.ma-turn__ask {
  display: flex;
  justify-content: flex-end;
  cursor: pointer;
}

.ma-turn__bubble {
  max-width: 78%;
  padding: 10px 12px;
  background: #efeefe;
  border-radius: 12px;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 选中态：只加一层极浅描边，不动气泡配色，保持「普通对话」的观感 */
.ma-turn.is-selected .ma-turn__bubble {
  box-shadow: 0 0 0 1px #cdc6ff;
}

.ma-turn__detached {
  margin: 6px 0 0;
  color: var(--el-color-warning, #b54708);
  font-size: 12px;
  line-height: 1.5;
  text-align: right;
}

/* 2) 模型思考：无外框，与提问拉开距离；块内只有路由 / 子 Agent 卡 */
.ma-run {
  margin-top: 12px;
  padding-left: 32px;
  cursor: pointer;
}

.ma-run__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

/* 「运」标记：与对话页的方形标记同尺寸，强调这是模型侧的执行过程 */
.ma-run__label {
  display: grid;
  flex: none;
  place-items: center;
  width: 22px;
  height: 22px;
  margin-left: -32px;
  border: 1px solid #e4e6ee;
  border-radius: 6px;
  background: #fff;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 11px;
  font-weight: 700;
}

.ma-run__title {
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  font-weight: 600;
}

.ma-run__time {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

/* 卡片纵向 flex-col：中栏主宽，卡片本身不横排 */
.ma-run__cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 3) 模型回答：左对齐正文，与对话页 answer 区一致，不加气泡底色 */
.ma-turn__answer {
  position: relative;
  margin-top: 12px;
  cursor: pointer;
}

.ma-turn__caret {
  color: var(--ah-primary, #635bff);
}

.ma-turn__error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.6;
}

.ma-turn__pending {
  margin: 8px 0 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 13px;
}

/* Markdown 正文：与 ChatPanel 的 .md 口径一致（标题 / 引用 / 列表 / 代码 / 表格 / 链接） */
.md {
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4),
.md :deep(h5),
.md :deep(h6) {
  margin: 0.4em 0 0.25em;
  font-weight: 650;
  line-height: 1.35;
}

.md :deep(h1) {
  font-size: 17px;
}

.md :deep(h2) {
  font-size: 16px;
}

.md :deep(h3) {
  font-size: 15px;
}

.md :deep(h4) {
  font-size: 14px;
}

.md :deep(h5),
.md :deep(h6) {
  font-size: 13px;
}

/* 引用块：左侧竖线弱化，别抢正文注意力 */
.md :deep(blockquote) {
  margin: 0.45em 0;
  padding: 2px 0 2px 10px;
  border-left: 3px solid #d8dce8;
  color: var(--ah-text-secondary, #6b7280);
}

.md :deep(hr) {
  margin: 0.8em 0;
  border: none;
  border-top: 1px solid #e5e7ef;
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
  font-size: 13px;
  line-height: 1.45;
}

.md :deep(.md-table th),
.md :deep(.md-table td) {
  padding: 6px 10px;
  border: 1px solid #d0d5dd;
  text-align: left;
  vertical-align: top;
  white-space: normal;
}

.md :deep(.md-table th) {
  background: #f3f4f8;
  font-weight: 600;
}

.ma-flow__composer {
  flex: none;
  margin-top: 10px;
}

.ma-flow__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.ma-flow__hint {
  color: var(--ah-text-muted);
  font-size: 12px;
}
</style>
