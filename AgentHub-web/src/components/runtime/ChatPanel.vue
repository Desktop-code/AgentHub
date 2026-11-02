<template>
  <div class="chat">
    <div ref="scroller" class="messages">
      <!-- 进行态粘在滚动容器顶，内容变长时仍可见；结束后整条不渲染 -->
      <div v-if="liveTurn" class="phase phase-sticky" :class="'phase-' + liveTurn.phase">
        <span class="phase-bar" />
        <span>{{ detached ? '连接已断开，等待本轮结束' : phaseLabel(liveTurn.phase) }}</span>
        <!-- 断线时不再显示「已等待」，避免让人以为还在收增量 -->
        <span v-if="!detached" class="wait">已等待 {{ waitSeconds(liveTurn.startTime) }}s</span>
        <span v-else class="wait">结束后自动补齐</span>
      </div>

      <div v-if="!rows.length" class="empty">
        <h3>开始对话</h3>
        <p>新建会话后在下方输入。Enter 发送，Shift+Enter 换行。</p>
      </div>

      <div v-for="row in rows" :key="row.turn.key" class="row" :class="row.turn.role">
        <div v-if="row.turn.role === 'user'" class="bubble">{{ row.turn.text }}</div>

        <!-- 计划执行模式：顺序钉死（计划思考 → 计划卡 → 按步执行 → 终答） -->
        <article v-else-if="row.flow" class="card">
          <!-- 1. 计划阶段的思考（可折叠）。出计划本身也有推理，不能只给执行阶段留思考位 -->
          <div v-if="row.flow.planPhase.length" class="think-block">
            <button
              type="button"
              class="think-toggle"
              :aria-expanded="isOpen(row.turn, PHASE_KEY, true)"
              :aria-label="isOpen(row.turn, PHASE_KEY, true) ? '收起计划思考' : '展开计划思考'"
              @click.stop.prevent="toggle(row.turn, PHASE_KEY, true)"
            >
              <span class="section-label">计划思考</span>
              <span class="chevron" :class="{ open: isOpen(row.turn, PHASE_KEY, true) }" aria-hidden="true" />
            </button>
            <div v-show="isOpen(row.turn, PHASE_KEY, true)" class="think-panel">
              <ProcessTimeline :steps="row.flow.planPhase" :streaming="row.turn.status === 'streaming'" />
            </div>
          </div>

          <!-- 2. 执行计划卡：待批准 / 执行中 / 已完成 -->
          <div v-if="row.flow.plan" class="plan-block">
            <div class="plan-head">
              <div class="section-label answer-label">执行计划</div>
              <el-tag size="small" effect="plain" :type="planTagType(row.flow.plan.state)">
                {{ planStateText(row.flow.plan.state) }}
              </el-tag>
            </div>
            <ol class="plan-steps">
              <li v-for="step in row.flow.plan.steps" :key="step.seq" class="plan-step">
                <span class="plan-seq">{{ step.seq }}</span>
                <span class="plan-text">
                  <span class="plan-title">{{ step.title }}</span>
                  <span v-if="step.detail" class="plan-detail">{{ step.detail }}</span>
                </span>
              </li>
            </ol>
            <div v-if="canApprove && row.turn.planPending" class="plan-actions">
              <el-button type="primary" size="small" :loading="approving" @click="onApprove(row.turn)">批准执行</el-button>
              <el-button size="small" :disabled="approving" @click="onReject(row.turn)">取消</el-button>
            </div>
            <!-- 批准后接口要跑完整轮才返回：这段时间必须有进行态，不能只剩一个转圈的按钮 -->
            <p v-else-if="approving && row.flow.plan.state === 'EXECUTING'" class="plan-running">
              已批准，正在按计划逐步执行…
            </p>
          </div>

          <!-- 3. 按步执行：每步含自己的思考（可折叠）与结果，顺序即执行顺序 -->
          <section
            v-for="step in row.flow.execution"
            :key="'s-' + step.seq"
            class="exec-step"
            :class="'step-' + step.state"
          >
            <button
              type="button"
              class="exec-head"
              :disabled="!step.steps.length"
              @click.stop.prevent="onStepHead(row.turn, step.seq, step.steps.length)"
            >
              <span class="exec-badge">{{ stepStateText(step.state) }}</span>
              <span class="exec-title">第 {{ step.seq }} 步 · {{ step.title }}</span>
              <template v-if="step.steps.length">
                <span class="exec-hint">
                  {{ isOpen(row.turn, stepKey(step.seq), true) ? '收起思考' : '展开思考' }}
                </span>
                <span class="chevron" :class="{ open: isOpen(row.turn, stepKey(step.seq), true) }" aria-hidden="true" />
              </template>
            </button>
            <div
              v-show="step.steps.length && isOpen(row.turn, stepKey(step.seq), true)"
              class="exec-think"
            >
              <ProcessTimeline
                :steps="step.steps"
                :streaming="step.state === 'RUNNING' || step.state === 'RETRYING'"
              />
            </div>
            <p v-if="step.state === 'DONE' && step.text" class="exec-result">
              完成第 {{ step.seq }} 步：{{ step.text }}
            </p>
            <p v-else-if="step.state === 'RETRYING' && step.text" class="exec-result retry">
              重试中：{{ step.text }}
            </p>
            <p v-else-if="step.state === 'FAILED' && step.text" class="exec-result error">
              第 {{ step.seq }} 步失败：{{ step.text }}
            </p>
          </section>

          <!-- 4. 终答。永远排在计划卡与步骤之后，不允许插队 -->
          <div v-if="row.flow.answer" class="answer-block">
            <div class="section-label answer-label">最终回答</div>
            <div class="md answer" v-html="markdown(row.flow.answer, row.turn.status === 'streaming')" />
            <span v-if="row.turn.status === 'streaming'" class="caret">|</span>
          </div>

          <p v-for="(note, index) in row.flow.notes" :key="'n-' + index" class="note">{{ note }}</p>
          <p v-for="(err, index) in row.flow.errors" :key="'e-' + index" class="error">{{ err }}</p>

          <!-- 5. 产物：Agent 写进工作区的文件。排在终答之后，是「结果」而不是「过程」 -->
          <FileCard v-for="file in row.flow.files" :key="'f-' + file.fileName" :file="file" />

          <p v-if="showPlanThinking(row.turn, row.flow)" class="thinking">
            {{ row.turn.planPending ? '计划待批准' : '思考中…' }}
          </p>
        </article>

        <!-- 非计划流程（REACT / ROUTER）：保持原有「思考过程 + 块顺序」渲染 -->
        <article v-else class="card">
          <!-- 思考过程：多路由按子 Agent 分块，收束后仍保持分块不拍扁 -->
          <div v-if="hasProcess(row.turn)" class="think-block">
            <button
              type="button"
              class="think-toggle"
              :aria-expanded="isOpen(row.turn, THINK_KEY, true)"
              :aria-label="isOpen(row.turn, THINK_KEY, true) ? '收起思考过程' : '展开思考过程'"
              @click.stop.prevent="toggle(row.turn, THINK_KEY, true)"
            >
              <span class="section-label">思考过程</span>
              <span class="chevron" :class="{ open: isOpen(row.turn, THINK_KEY, true) }" aria-hidden="true" />
            </button>
            <div
              v-show="isOpen(row.turn, THINK_KEY, true)"
              class="think-panel"
              :class="{ 'lanes-multi': lanesOf(row.turn).length > 1 }"
            >
              <section
                v-for="(lane, laneIndex) in lanesOf(row.turn)"
                :key="laneIndex"
                class="think-lane"
                :class="'lane-' + lane.kind"
              >
                <div class="lane-head">
                  <template v-if="lane.kind === 'agent' && routeAgentName(lane.title)">
                    路由到 <span class="route-agent">{{ routeAgentName(lane.title) }}</span>
                  </template>
                  <template v-else>{{ lane.title }}</template>
                  <span v-if="lane.routeKey" class="route-key">route_key · {{ lane.routeKey }}</span>
                </div>
                <pre v-if="lane.body" class="lane-body">{{ lane.body }}</pre>
                <ol v-if="lane.steps.length" class="timeline">
                  <li
                    v-for="(step, stepIndex) in lane.steps"
                    :key="stepIndex"
                    class="timeline-item"
                    :class="'step-' + step.type"
                  >
                    <div class="timeline-rail" aria-hidden="true">
                      <span class="timeline-dot" />
                      <span class="timeline-line" />
                    </div>
                    <div class="timeline-main">
                      <div class="step-title">{{ step.title }}</div>
                      <div
                        v-if="step.type === 'reasoning'"
                        class="md think-body"
                        v-html="markdown(step.text, row.turn.status === 'streaming')"
                      />
                      <pre v-else-if="step.type === 'tool_call'" class="step-call">{{ step.text }}</pre>
                      <div v-else class="step-result">
                        <pre
                          class="step-result-body"
                          :class="{ clamped: !isResultExpanded(row.turn, laneIndex, stepIndex) }"
                        >{{ step.text }}</pre>
                        <button
                          v-if="needsResultToggle(step.text)"
                          type="button"
                          class="result-toggle"
                          @click.stop.prevent="toggleResult(row.turn, laneIndex, stepIndex)"
                        >
                          {{ isResultExpanded(row.turn, laneIndex, stepIndex) ? '收起' : '展开' }}
                        </button>
                      </div>
                    </div>
                  </li>
                </ol>
              </section>
            </div>
          </div>

          <template v-for="(block, index) in row.turn.blocks" :key="'out-' + index">
            <!-- 图片文字提取结果：本轮素材，排在思考与终答之前，可折叠 -->
            <div v-if="block.kind === 'vision'" class="vision-block">
              <button
                type="button"
                class="think-toggle"
                :aria-expanded="isOpen(row.turn, VISION_KEY, false)"
                :aria-label="isOpen(row.turn, VISION_KEY, false) ? '收起图片文字' : '展开图片文字'"
                @click.stop.prevent="toggle(row.turn, VISION_KEY, false)"
              >
                <span class="section-label">图片文字</span>
                <span class="chevron" :class="{ open: isOpen(row.turn, VISION_KEY, false) }" aria-hidden="true" />
              </button>
              <pre v-show="isOpen(row.turn, VISION_KEY, false)" class="vision-body">{{ block.text }}</pre>
            </div>
            <div v-else-if="block.kind === 'assistant'" class="answer-block">
              <div class="section-label answer-label">最终回答</div>
              <div class="md answer" v-html="markdown(block.text, row.turn.status === 'streaming')" />
              <span
                v-if="row.turn.status === 'streaming' && index === lastAssistantIndex(row.turn)"
                class="caret"
              >|</span>
            </div>
            <p v-else-if="block.kind === 'system'" class="note">{{ block.text }}</p>
            <p v-else-if="block.kind === 'error'" class="error">{{ block.text }}</p>
            <!-- 产物卡片：块顺序里出现在工具步之后，读起来就是「这一步生成了这个文件」 -->
            <FileCard v-else-if="block.kind === 'file' && block.file" :file="block.file" />
          </template>
          <p v-if="showThinking(row.turn)" class="thinking">
            {{ row.turn.planPending ? '计划待批准' : '思考中…' }}
          </p>
        </article>
      </div>
    </div>
    <div class="composer">
      <!-- 已选图片缩略图：带删除角标，发送前可逐个移除 -->
      <div v-if="attachments.length" class="thumbs">
        <div v-for="(item, index) in attachments" :key="item.id" class="thumb">
          <img :src="item.dataUrl" :alt="item.name" />
          <button
            type="button"
            class="thumb-remove"
            :aria-label="`移除 ${item.name}`"
            :disabled="sending"
            @click="removeAttachment(index)"
          >
            ×
          </button>
        </div>
      </div>
      <el-input
        v-model="text"
        type="textarea"
        :rows="3"
        maxlength="8000"
        placeholder="输入问题，Enter 发送，Shift+Enter 换行"
        :disabled="!canSend || sending"
        @keydown.enter.exact.prevent="submit"
      />
      <div class="composer-bar">
        <div class="composer-left">
          <!-- 未绑定 VISION 模型时置灰并提示原因，避免用户传完图才在发送时被拒 -->
          <el-tooltip :content="uploadHint" :disabled="canUpload" placement="top">
            <span class="upload-wrap">
              <el-button
                class="upload-btn"
                size="small"
                :disabled="!canUpload || sending"
                @click="pickImages"
              >
                <el-icon><Picture /></el-icon>
                <span>图片</span>
              </el-button>
            </span>
          </el-tooltip>
          <!-- 原生 file input：支持多选，读取后转 data URI 提交给后端做 OCR -->
          <input
            ref="filePicker"
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            multiple
            class="file-input"
            @change="onFilesPicked"
          />
          <span class="hint">{{ sessionReady ? '' : blockedHint || '请先新建会话或从左侧选择' }}</span>
        </div>
        <el-button v-if="canSend" type="primary" :loading="sending" :disabled="!sessionReady" @click="submit">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 试跑对话区。所属域：runtime。只展示传入的时间线，不自己请求接口。
 *
 * 两种渲染分支：
 * - 计划执行模式（含 plan / step 块）：顺序钉死为
 *   计划思考（可折叠）→ 计划卡 → 按步执行（每步含可折叠思考与结果）→ 终答。
 *   终答与草稿一律排在计划卡之后，绝不抢到前面。
 * - REACT / ROUTER：沿用「思考过程（按子 Agent 分块）+ 块顺序」渲染。
 *
 * 折叠默认展开，仅当用户点过标题才收起；终答出现也不自动收起。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import type { ChatTurn } from '@/types/runtime'
import { ElMessage } from 'element-plus'
import {
  isPlanFlow,
  planStateText,
  processLanes,
  stepStateText,
  turnSections
} from '@/components/runtime/trialMessages'
import type { TurnSections } from '@/components/runtime/trialMessages'
import ProcessTimeline from '@/components/runtime/ProcessTimeline.vue'
import FileCard from '@/components/runtime/FileCard.vue'
import { renderSimpleMarkdown, renderStreamingMarkdown } from '@/utils/simpleMarkdown'

const props = defineProps<{
  turns: ChatTurn[]
  sending: boolean
  canSend: boolean
  sessionReady: boolean
  /** 不能发送时的说明。结案和未选会话用不同文案 */
  blockedHint?: string
  /** 进行中时每秒刷新「已等待」 */
  now: number
  /**
   * 是否已与后端断开、只能等落库。
   * 整页刷新后前端不再持有 SSE，增量拿不到；此时状态条如实标注，不假装还在思考。
   */
  detached?: boolean
  /** 是否有批准计划权限。无权限时计划卡只读展示 */
  canApprove?: boolean
  /** 批准请求进行中，按钮转圈且禁止重复点 */
  approving?: boolean
  /**
   * 当前 Agent 是否已绑定可用的 VISION 模型。
   * false 时上传图片按钮置灰并给出 Hover 提示——后端也会拒，但让用户先看到原因体验更好。
   */
  visionEnabled?: boolean
}>()

const emit = defineEmits<{
  send: [content: string, images?: string[]]
  /** 批准待执行计划；携带该轮 planId 便于定位 */
  approve: [planId?: string]
  /** 取消待执行计划 */
  reject: [planId?: string]
}>()

/** 待发送的图片。id 只用于列表 key 与删除定位 */
interface Attachment {
  id: number
  name: string
  /** data:{mime};base64,…，随发送一起提交给后端 */
  dataUrl: string
}

/** 单张图片的字节上限。与后端 VisionOcrTexts.MAX_IMAGE_CHARS 的量级对齐（约 6MB） */
const MAX_IMAGE_BYTES = 6 * 1024 * 1024

/** 单轮图片张数上限。与后端 VisionOcrTexts.MAX_IMAGES 一致，前后端不要各写一个数 */
const MAX_IMAGES = 4

/** 非计划流程里「思考过程」的折叠键后缀。 */
const THINK_KEY = 'think'
/** 计划阶段思考的折叠键后缀。 */
const PHASE_KEY = 'plan-phase'
/** 图片文字提取结果的折叠键后缀。默认收起，不抢终答的位置。 */
const VISION_KEY = 'vision'

/**
 * 点批准：把该轮的 planId 交给外层。外层负责调接口并在成功后刷新时间线。
 * @param item 计划所在的那一轮
 */
function onApprove(item: ChatTurn) {
  if (props.sending || props.approving) {
    return
  }
  emit('approve', item.planId)
}

/**
 * 点取消：同上，但走拒绝接口，不执行任何步骤。
 * @param item 计划所在的那一轮
 */
function onReject(item: ChatTurn) {
  if (props.sending || props.approving) {
    return
  }
  emit('reject', item.planId)
}

/**
 * 点单步标题行：只有该步真的带来了思考/工具时才切换折叠，
 * 否则点击是空操作（避免点出空白面板）。
 * @param item        该步所属的助手轮
 * @param seq         步骤序号
 * @param stepCount   该步已收集的思考步数
 */
function onStepHead(item: ChatTurn, seq: number, stepCount: number) {
  if (!stepCount) {
    return
  }
  toggle(item, stepKey(seq), true)
}

/** 计划状态对应的标签色。失败用 danger，待批准用 warning 提示需要人工介入 */
function planTagType(state?: string): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  if (state === 'PENDING') {
    return 'warning'
  }
  if (state === 'DONE') {
    return 'success'
  }
  if (state === 'EXECUTING') {
    return 'primary'
  }
  if (state === 'REJECTED') {
    return 'info'
  }
  return 'danger'
}

const text = ref('')
const scroller = ref<HTMLElement>()
/** 原生文件选择器句柄。用 input 而非 el-upload：只需读文件转 base64，不需要上传进度与分片 */
const filePicker = ref<HTMLInputElement>()
/** 已选待发图片。发送成功后才清空，失败保留让用户重试 */
const attachments = ref<Attachment[]>([])
/** 自增 id，仅用于列表 key 与删除定位 */
let attachmentSeq = 0

/** 上传按钮是否可用：需要已绑定 VISION 模型，且未超张数上限 */
const canUpload = computed(() => props.visionEnabled === true && attachments.value.length < MAX_IMAGES)

/** 按钮禁用时的 Hover 说明。已绑定时不弹（由 el-tooltip 的 disabled 控制） */
const uploadHint = computed(() =>
  props.visionEnabled === true
    ? `最多上传 ${MAX_IMAGES} 张图片`
    : '未配置视觉模型，无法识别图片'
)
/** 用户点标题后的折叠覆盖；未写入时用调用方给的默认态。键为「轮 key + 区段后缀」。 */
const foldOverride = reactive<Record<string, boolean>>({})
/** 长工具结果是否展开。 */
const resultOpen = reactive<Record<string, boolean>>({})

/**
 * 渲染行：每条对话配上它的计划分段视图（非计划流程为 undefined）。
 *
 * 用 computed 而不是模板里反复调函数：一次渲染周期内每轮只算一遍，
 * 也避免在模板里对可能为空的返回值做非空断言。
 */
const rows = computed<{ turn: ChatTurn; flow?: TurnSections }[]>(() =>
  props.turns.map((turn) => ({
    turn,
    flow: turn.role === 'assistant' && isPlanFlow(turn) ? turnSections(turn.blocks) : undefined
  }))
)

/** 当前流式中的助手轮；用于顶部粘性状态条。结束后为 null。 */
const liveTurn = computed(() => {
  for (let i = props.turns.length - 1; i >= 0; i -= 1) {
    const item = props.turns[i]
    if (item.role === 'assistant' && item.status === 'streaming' && phaseLabel(item.phase)) {
      return item
    }
  }
  return null
})

watch(
  () => props.turns,
  async () => {
    await nextTick()
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight
    }
  },
  { deep: true }
)

/** 状态码换成商城同款短句。未知阶段不显示空标签。 */
function phaseLabel(phase?: string) {
  if (phase === 'planning') {
    return '任务规划中'
  }
  if (phase === 'vision') {
    return '识别图片文字'
  }
  if (phase === 'reasoning') {
    return '思考中'
  }
  if (phase === 'tool') {
    return '工具执行中'
  }
  if (phase === 'answering') {
    return '回答生成中'
  }
  if (phase === 'error') {
    return '失败'
  }
  return ''
}

/** 多路由分块；单意图通常一块。 */
function lanesOf(item: ChatTurn) {
  return processLanes(item.blocks)
}

/** 从「路由到 {名}」抽出子 Agent 显示名，供红色强调。 */
function routeAgentName(title: string) {
  const prefix = '路由到 '
  if (!title.startsWith(prefix)) {
    return ''
  }
  return title.slice(prefix.length).trim()
}

function hasProcess(item: ChatTurn) {
  return lanesOf(item).length > 0
}

function hasAnswer(item: ChatTurn) {
  return item.blocks.some((block) => block.kind === 'assistant' && block.text.trim())
}

/**
 * 本轮是否已有可见产出。产物也算：只生成了文件、模型终答为空时，
 * 若仍判「什么都没有」就会一直挂着「思考中…」，与实际情况矛盾。
 *
 * @param item 助手轮
 */
function hasOutput(item: ChatTurn) {
  return hasAnswer(item) || item.blocks.some((block) => block.kind === 'file')
}

/** 非计划流程：还没有任何过程/终答/产物时才显示占位。 */
function showThinking(item: ChatTurn) {
  return item.status === 'streaming' && !hasProcess(item) && !hasOutput(item)
}

/**
 * 计划流程：只有这一轮确实还空着才显示占位。
 * 已经有计划思考、计划卡或步骤时再补一行「思考中…」只是噪音。
 * @param item 助手轮
 * @param flow 该轮的分段视图
 */
function showPlanThinking(item: ChatTurn, flow: TurnSections) {
  if (item.status !== 'streaming' || flow.answer) {
    return false
  }
  // 产物同样算产出：只写了文件没有终答时不该再显示「思考中」
  return !flow.planPhase.length && !flow.plan && !flow.execution.length && !flow.files.length
}

/** 单步思考的折叠键后缀。 */
function stepKey(seq: number) {
  return `step-${seq}`
}

/**
 * 折叠键。按轮与区段隔离，避免收起「计划思考」时连带把某一步也收起来。
 * @param item 助手轮
 * @param part 区段后缀（THINK_KEY / PHASE_KEY / stepKey）
 */
function foldKey(item: ChatTurn, part: string) {
  return `${item.key}:${part}`
}

/**
 * 是否展开。用户点过就以覆盖为准，否则用默认值。
 * @param item        助手轮
 * @param part        区段后缀
 * @param defaultOpen 未操作时的默认态
 */
function isOpen(item: ChatTurn, part: string, defaultOpen: boolean) {
  const key = foldKey(item, part)
  if (Object.prototype.hasOwnProperty.call(foldOverride, key)) {
    return foldOverride[key] !== false
  }
  return defaultOpen
}

/**
 * 切换折叠。
 * @param item        助手轮
 * @param part        区段后缀
 * @param defaultOpen 当前默认态，用于首次点击取反
 */
function toggle(item: ChatTurn, part: string, defaultOpen: boolean) {
  const key = foldKey(item, part)
  foldOverride[key] = !isOpen(item, part, defaultOpen)
}

function resultKey(item: ChatTurn, laneIndex: number, stepIndex: number) {
  return `${item.key}:r:${laneIndex}:${stepIndex}`
}

/** 多行或偏长时才出展开按钮；默认一律一行高度。 */
function needsResultToggle(value: string) {
  return value.includes('\n') || value.length > 48
}

function isResultExpanded(item: ChatTurn, laneIndex: number, stepIndex: number) {
  return resultOpen[resultKey(item, laneIndex, stepIndex)] === true
}

function toggleResult(item: ChatTurn, laneIndex: number, stepIndex: number) {
  const key = resultKey(item, laneIndex, stepIndex)
  resultOpen[key] = !isResultExpanded(item, laneIndex, stepIndex)
}

function lastAssistantIndex(item: ChatTurn) {
  for (let i = item.blocks.length - 1; i >= 0; i -= 1) {
    if (item.blocks[i].kind === 'assistant') {
      return i
    }
  }
  return -1
}

function waitSeconds(startTime: number) {
  if (!startTime) {
    return 0
  }
  return Math.max(0, Math.floor((props.now - startTime) / 1000))
}

/**
 * 思考与终答共用轻量 Markdown。流式走安全前缀+表渐进，结束后完整终渲。
 * 原文标签不会执行。
 */
function markdown(value: string, streaming = false) {
  return streaming ? renderStreamingMarkdown(value) : renderSimpleMarkdown(value)
}

function submit() {
  const content = text.value.trim()
  if (!content || !props.canSend || !props.sessionReady || props.sending) {
    return
  }
  // 未绑定视觉模型时即便有残留图片也不提交：后端会直接拒，不如前端先兜住
  const images = props.visionEnabled === true ? attachments.value.map((item) => item.dataUrl) : []
  emit('send', content, images.length ? images : undefined)
  text.value = ''
  attachments.value = []
}

/** 点「图片」：走原生文件选择器，避免 el-upload 额外引入一条上传链路 */
function pickImages() {
  if (!canUpload.value) {
    return
  }
  filePicker.value?.click()
}

/**
 * 选中文件后转 data URI。逐个读、边读边校验，
 * 单张超限或超张数时立即停止并提示，不把整批都读进内存。
 * @param event 原生 input 的 change 事件
 */
async function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  // 立刻清空 value：否则同一个文件再次选择不会触发 change
  input.value = ''
  if (!files.length) {
    return
  }
  const room = MAX_IMAGES - attachments.value.length
  if (room <= 0) {
    ElMessage.warning(`一次最多上传 ${MAX_IMAGES} 张图片`)
    return
  }
  const picked = files.slice(0, room)
  for (const file of picked) {
    if (file.size > MAX_IMAGE_BYTES) {
      ElMessage.warning(`${file.name} 超过 6MB，请压缩后再上传`)
      continue
    }
    try {
      const dataUrl = await readAsDataUrl(file)
      attachmentSeq += 1
      attachments.value = [...attachments.value, { id: attachmentSeq, name: file.name, dataUrl }]
    } catch {
      ElMessage.warning(`${file.name} 读取失败`)
    }
  }
  // 多选了仍放不下的部分明确告知，而不是静默丢弃
  if (files.length > picked.length) {
    ElMessage.info(`已达 ${MAX_IMAGES} 张上限，其余图片未加入`)
  }
}

/**
 * @param file 浏览器 File
 * @return data:{mime};base64,… 形式的字符串
 */
function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('read failed'))
    reader.readAsDataURL(file)
  })
}

/**
 * 移除一张待发图片。
 * @param index 在 attachments 中的位置
 */
function removeAttachment(index: number) {
  if (props.sending) {
    return
  }
  attachments.value = attachments.value.filter((_, i) => i !== index)
}
</script>

<style scoped>
.chat {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.empty {
  padding: 48px 12px;
  color: var(--ah-text-secondary);
  text-align: center;
}

.empty h3 {
  margin: 0 0 8px;
  color: var(--ah-text);
  font-size: 18px;
}

.empty p {
  margin: 0;
  font-size: 13px;
}

.row {
  margin-bottom: 14px;
}

.row.user {
  display: flex;
  justify-content: flex-end;
}

.bubble {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: 12px;
  background: #efeefe;
  line-height: 1.55;
  white-space: pre-wrap;
}

.card {
  width: 100%;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid transparent;
  border-radius: 12px;
}

.phase {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ah-text-secondary);
  font-size: 13px;
}

.phase-sticky {
  position: sticky;
  top: 0;
  z-index: 6;
  margin: 0 0 10px;
  padding: 8px 12px;
  border-bottom: 1px solid #e8eaef;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(4px);
}

.phase-bar {
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--ah-primary);
}

.phase-error .phase-bar {
  background: var(--el-color-danger);
}

.wait {
  color: var(--ah-text-muted);
}

.thinking {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary, #8c8c8c);
  font-size: 0.875em;
}

.caret {
  color: var(--ah-primary);
}

.section-label {
  margin: 0;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.3;
}

.think-block {
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: transparent;
}

.think-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin: 0;
  padding: 0 0 8px;
  border: 0;
  border-bottom: 2px solid var(--ah-primary, #635bff);
  background: transparent;
  cursor: pointer;
  text-align: left;
  position: relative;
  z-index: 1;
}

.think-toggle:hover .section-label {
  color: #374151;
}

.chevron {
  flex: none;
  margin-left: auto;
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid #98a2b3;
  transition: transform 0.15s ease;
}

.chevron.open {
  transform: rotate(180deg);
}

.think-panel {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.think-panel.lanes-multi {
  gap: 14px;
}

.think-lane {
  padding: 10px 12px;
  border: 1px solid #e8eaef;
  border-radius: 10px;
  background: #fafbff;
}

.think-lane.lane-decision {
  background: #f7f8fc;
}

.lane-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  color: #a3a3a3;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.3;
}

.route-key {
  color: #98a2b3;
  font-size: 11px;
  font-weight: 500;
}

.lane-body {
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f3f4f8;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
}

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

.route-agent {
  color: var(--el-color-danger, #f56c6c);
  font-weight: 700;
}

.think-body {
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.55;
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

.answer-label {
  padding-bottom: 8px;
  border-bottom: 2px solid var(--ah-primary, #635bff);
}

/* 计划卡：白底细框，与「最终回答」区分；步骤用序号圆点，读起来像清单 */
.plan-block {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #e6e8ef;
  border-radius: 10px;
  background: #fafbff;
}

.plan-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.plan-steps {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.plan-step {
  display: flex;
  gap: 8px;
  padding: 5px 0;
  font-size: 13px;
  line-height: 1.5;
}

.plan-seq {
  flex: none;
  width: 20px;
  height: 20px;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 650;
  line-height: 20px;
  text-align: center;
  background: rgba(99, 91, 255, 0.1);
  border-radius: 50%;
}

.plan-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.plan-title {
  color: var(--ah-text, #1f2430);
}

.plan-detail {
  margin-top: 2px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.plan-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #ebedf3;
}

.plan-running {
  margin: 8px 0 0;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

/* 按步执行：左侧色条区分状态，标题行整行可点（该步有思考时） */
.exec-step {
  margin-top: 8px;
  padding: 6px 10px;
  border-left: 3px solid #e4e7ef;
  border-radius: 6px;
  background: #fcfcfd;
}

.exec-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.exec-head:disabled {
  cursor: default;
}

.exec-badge {
  flex: none;
  padding: 0 6px;
  color: var(--ah-text-secondary, #6b7280);
  background: #f2f3f7;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}

.exec-title {
  flex: 1;
  min-width: 0;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 600;
}

.exec-hint {
  flex: none;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

.exec-head .chevron {
  margin-left: 0;
}

.exec-think {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fafbff;
}

.exec-result {
  margin: 6px 0 0;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.exec-result.retry {
  color: var(--el-color-warning, #e6a23c);
}

.exec-result.error {
  color: var(--el-color-danger);
}

.exec-step.step-RUNNING {
  border-left-color: var(--ah-primary, #635bff);
}

.exec-step.step-DONE {
  border-left-color: var(--el-color-success-light-3, #95d475);
}

.exec-step.step-FAILED {
  border-left-color: var(--el-color-danger-light-3, #fab6b6);
}

.exec-step.step-RETRYING {
  border-left-color: var(--el-color-warning-light-3, #f3d19e);
}

.answer-block {
  margin-top: 14px;
}

.answer {
  margin-top: 8px;
  color: var(--ah-text);
  font-size: 14px;
  line-height: 1.6;
}

.note {
  margin: 8px 0 0;
  color: var(--ah-text-secondary);
  font-size: 12px;
}

/* 图片文字：与「思考过程」同构，默认收起，正文用等宽便于比对识别结果 */
.vision-block {
  margin-top: 8px;
}

.vision-body {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #f7f8fb;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}

.error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  margin: 0.4em 0 0.25em;
  font-weight: 650;
  line-height: 1.35;
}

.answer :deep(h2) {
  font-size: 16px;
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

.md :deep(.md-table .align-center) {
  text-align: center;
}

.md :deep(.md-table .align-right) {
  text-align: right;
}

.md :deep(.md-table-pending) {
  margin: 0.45em 0;
  color: var(--ah-text-secondary, #8b8fa3);
  font-size: 13px;
}

.md.think-body :deep(h1),
.md.think-body :deep(h2),
.md.think-body :deep(h3) {
  margin: 0.35em 0 0.2em;
  color: #a3a3a3;
  font-size: 1.05em;
}

.md.think-body :deep(p),
.md.think-body :deep(li) {
  color: inherit;
}

.md.think-body :deep(strong) {
  color: #9a9a9a;
  font-weight: 700;
}

.md.think-body :deep(.md-table) {
  font-size: 1em;
}

.md.think-body :deep(.md-table th),
.md.think-body :deep(.md-table td) {
  padding: 4px 8px;
}

.composer {
  flex: none;
  margin-top: 12px;
}

/* 缩略图行：紧贴在输入框上方，横向排列，超出可横向滚动 */
.thumbs {
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 2px;
  overflow-x: auto;
}

.thumb {
  position: relative;
  flex: none;
  width: 64px;
  height: 64px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #f7f8fb;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 删除角标：右上角小圆点，hover 才明显，避免压住缩略图主体 */
.thumb-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
  line-height: 18px;
  text-align: center;
  cursor: pointer;
}

.thumb-remove:hover {
  background: var(--el-color-danger, #f56c6c);
}

.thumb-remove:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.composer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 置灰时仍要能触发 tooltip：disabled 的按钮不派发鼠标事件，故用外层 span 承接 */
.upload-wrap {
  display: inline-flex;
}

.upload-btn {
  gap: 4px;
}

/* 原生 file input 只做逻辑承载，不占版面 */
.file-input {
  display: none;
}

.hint {
  color: var(--ah-text-muted);
  font-size: 12px;
}
</style>
