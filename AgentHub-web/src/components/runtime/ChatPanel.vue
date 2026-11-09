<template>
  <div class="chat">
    <div ref="scroller" class="messages" @scroll.passive="onScroll">
      <!-- 进行态粘在滚动容器顶，内容变长时仍可见；结束后整条不渲染 -->
      <div v-if="liveTurn" class="phase phase-sticky" :class="'phase-' + liveTurn.phase">
        <span class="phase-bar" />
        <span>{{ detached ? '连接已断开，等待本轮结束' : phaseLabel(liveTurn.phase) }}</span>
        <!-- 断线时不再显示「已等待」，避免让人以为还在收增量 -->
        <!-- 秒表下沉为叶子组件：只有这行文字每秒更新，对话树不跟着整棵重渲 -->
        <ElapsedSeconds v-if="!detached" class="wait" :start-time="liveTurn.startTime" />
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
          <!-- Router 试跑的编排调度卡：在用户气泡之后、思考与终答之前，数据只读不落库 -->
          <RouterTimeline
            v-if="showRouterCard(row.turn)"
            :trace-data="routerTrace?.model ?? null"
            :status="routerCardStatus(row.turn)"
            :error="routerTrace?.error ?? ''"
            :loading="routerTrace?.loading === true"
            :trace-id="routerTrace?.traceId ?? ''"
            @refresh="emit('refresh-router')"
            @open-gantt="emit('open-gantt')"
          />
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
            <div class="answer-head">
              <div class="section-label answer-label">最终回答</div>
              <!--
                只读最终回答：思考过程、工具原文不提供朗读入口。
                这里只放图标控件（播放 / 暂停 / 停止），不写「朗读」等旁注文字；
                自动朗读由输入区的喇叭开关负责，这条只是手动播放的最后一条回答。
              -->
              <div v-if="row.turn.status !== 'streaming'" class="speak-actions">
                <el-tooltip :content="speakHintOf(planAnswerKey(row.turn))" placement="top">
                  <button
                    type="button"
                    class="speak-icon"
                    :class="{ active: isSpeaking(planAnswerKey(row.turn)) }"
                    :aria-label="speakHintOf(planAnswerKey(row.turn))"
                    @click="toggleSpeak(planAnswerKey(row.turn), row.flow.answer)"
                  >
                    <el-icon :class="{ 'is-loading': isPreparing(planAnswerKey(row.turn)) }">
                      <Loading v-if="isPreparing(planAnswerKey(row.turn))" />
                      <VideoPause v-else-if="isPlaying(planAnswerKey(row.turn))" />
                      <VideoPlay v-else />
                    </el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip v-if="isSpeaking(planAnswerKey(row.turn))" content="停止朗读" placement="top">
                  <button type="button" class="speak-icon stop" aria-label="停止朗读" @click="stopSpeech">
                    <el-icon><Close /></el-icon>
                  </button>
                </el-tooltip>
              </div>
            </div>
            <div
              class="md answer"
              v-html="markdown(planAnswerSlot(row.turn), row.flow.answer, row.turn.status === 'streaming')"
            />
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
          <!-- Router 试跑的编排调度卡：在用户气泡之后、思考与终答之前，数据只读不落库 -->
          <RouterTimeline
            v-if="showRouterCard(row.turn)"
            :trace-data="routerTrace?.model ?? null"
            :status="routerCardStatus(row.turn)"
            :error="routerTrace?.error ?? ''"
            :loading="routerTrace?.loading === true"
            :trace-id="routerTrace?.traceId ?? ''"
            @refresh="emit('refresh-router')"
            @open-gantt="emit('open-gantt')"
          />
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
                        v-html="markdown(stepSlot(row.turn, laneIndex, stepIndex), step.text, row.turn.status === 'streaming')"
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

          <!-- 块列表：key 必须用数据层分配的稳定 id，禁止用数组下标（视觉块是 unshift 到队首的） -->
          <template v-for="(block, index) in row.turn.blocks" :key="blockKey(block, index)">
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
              <div class="answer-head">
                <div class="section-label answer-label">最终回答</div>
                <!-- 同上：完成态才给图标控件，流式中隐藏，避免念到半句 -->
                <div v-if="row.turn.status !== 'streaming'" class="speak-actions">
                  <el-tooltip :content="speakHintOf(blockAnswerKey(row.turn, block))" placement="top">
                    <button
                      type="button"
                      class="speak-icon"
                      :class="{ active: isSpeaking(blockAnswerKey(row.turn, block)) }"
                      :aria-label="speakHintOf(blockAnswerKey(row.turn, block))"
                      @click="toggleSpeak(blockAnswerKey(row.turn, block), block.text)"
                    >
                      <el-icon :class="{ 'is-loading': isPreparing(blockAnswerKey(row.turn, block)) }">
                        <Loading v-if="isPreparing(blockAnswerKey(row.turn, block))" />
                        <VideoPause v-else-if="isPlaying(blockAnswerKey(row.turn, block))" />
                        <VideoPlay v-else />
                      </el-icon>
                    </button>
                  </el-tooltip>
                  <el-tooltip
                    v-if="isSpeaking(blockAnswerKey(row.turn, block))"
                    content="停止朗读"
                    placement="top"
                  >
                    <button type="button" class="speak-icon stop" aria-label="停止朗读" @click="stopSpeech">
                      <el-icon><Close /></el-icon>
                    </button>
                  </el-tooltip>
                </div>
              </div>
              <div
                class="md answer"
                v-html="markdown(blockSlot(block, index), block.text, row.turn.status === 'streaming')"
              />
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
          <!--
            自动朗读开关（喇叭）。开启后只朗读「之后完成」的最终回答（绑了 TTS 走模型音频，否则浏览器），
            绝不补读历史记录；关闭不再自动读，且立刻停掉正在朗读的那一条。
            开/关不用文字标注：开启时实心高亮，关闭时描边，靠形态与颜色一起区分。
          -->
          <el-tooltip :content="speakerHint" placement="top">
            <button
              type="button"
              class="voice-icon-btn speaker-btn"
              :class="{ on: autoSpeak }"
              :aria-pressed="autoSpeak"
              :aria-label="autoSpeak ? '关闭自动朗读' : '开启自动朗读'"
              @click="toggleAutoSpeak"
            >
              <el-icon><Headset /></el-icon>
            </button>
          </el-tooltip>
          <!--
            语音听写：只留图标（时长与状态走 tooltip，不再排一行字）。
            未绑定 ASR 时按钮保留图标颜色、降透明度并拦住点击，hover 仍说明原因；
            禁止回退浏览器 Web Speech 识别。
          -->
          <el-tooltip :content="recordHint" placement="top">
            <button
              type="button"
              class="voice-icon-btn mic-btn"
              :class="{ recording: voiceRecording, muted: !canRecord && !voiceRecording && !transcribing }"
              :aria-disabled="!canRecord && !voiceRecording"
              :aria-label="voiceRecording ? '结束录音并识别' : '开始语音输入'"
              @click="toggleRecording"
            >
              <el-icon :class="{ 'is-loading': transcribing }">
                <Loading v-if="transcribing" />
                <Microphone v-else />
              </el-icon>
            </button>
          </el-tooltip>
          <!-- 录音中才出现：同样是图标按钮，避免和「说话」并排成一行文字 -->
          <el-tooltip v-if="voiceRecording" content="取消本次录音" placement="top">
            <button type="button" class="voice-icon-btn cancel-btn" aria-label="取消本次录音" @click="cancelVoiceRecording">
              <el-icon><Close /></el-icon>
            </button>
          </el-tooltip>
          <!--
            上传图片：同样只放图标（不入文字），与语音按钮同一套视觉；
            未绑 VISION 时保留图标颜色、降透明度并拦住点击，hover 仍说明原因。
          -->
          <el-tooltip :content="uploadHint" placement="top">
            <button
              type="button"
              class="voice-icon-btn image-btn"
              :class="{ muted: !canUploadNow }"
              :aria-disabled="!canUploadNow"
              aria-label="上传图片"
              @click="pickImages"
            >
              <el-icon><Picture /></el-icon>
            </button>
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
import { computed, onBeforeUnmount, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import { Microphone, Picture, Headset, VideoPlay, VideoPause, Loading, Close } from '@element-plus/icons-vue'
import type { AssistantBlock, ChatTurn, RouterTraceView } from '@/types/runtime'
import { ElMessage } from 'element-plus'
import { transcribeAudio } from '@/api/voice'
import { useVoiceRecorder } from '@/composables/voiceRecorder'
import { useSpeechPlayer } from '@/composables/speechPlayer'
import {
  hasRouterTrace,
  isPlanFlow,
  planStateText,
  processLanes,
  stepStateText,
  turnSections
} from '@/components/runtime/trialMessages'
import type { TurnSections } from '@/components/runtime/trialMessages'
import ProcessTimeline from '@/components/runtime/ProcessTimeline.vue'
import RouterTimeline from '@/components/runtime/RouterTimeline.vue'
import FileCard from '@/components/runtime/FileCard.vue'
import ElapsedSeconds from '@/components/runtime/ElapsedSeconds.vue'
import { resolveFollowAfterScroll } from '@/components/runtime/chatFollow'
import { renderMarkdownMemo } from '@/utils/simpleMarkdown'

const props = defineProps<{
  turns: ChatTurn[]
  sending: boolean
  canSend: boolean
  sessionReady: boolean
  /** 不能发送时的说明。结案和未选会话用不同文案 */
  blockedHint?: string
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
  /** 当前 Agent id。语音听写 / 合成都要落到具体 Agent 的模型槽；为空时语音入口不可用 */
  agentId?: number
  /**
   * 当前 Agent 是否已绑定可用的 ASR 模型。
   * false 时「说话」按钮置灰并说明需绑定语音识别模型——听写不做任何浏览器识别回退。
   */
  asrEnabled?: boolean
  /**
   * 当前 Agent 是否已绑定可用的 TTS 模型。
   * false 时朗读<b>仍可用</b>：降级浏览器 speechSynthesis，只影响请求路径与提示文案。
   */
  ttsEnabled?: boolean
  /**
   * Router 试跑的编排卡数据（obs span 树归纳，只读且仅前端态）。
   * null 表示不展示：非 Router、无追踪权限或还没拿到数据时都由外层传 null。
   */
  routerTrace?: RouterTraceView | null
}>()

const emit = defineEmits<{
  send: [content: string, images?: string[]]
  /** 批准待执行计划；携带该轮 planId 便于定位 */
  approve: [planId?: string]
  /** 取消待执行计划 */
  reject: [planId?: string]
  /** 编排卡点了「刷新」；数据源在外层，本组件不自己请求 */
  'refresh-router': []
  /** 紧凑时间线点了「查看详细甘特图」；抽屉由外层页面持有 */
  'open-gantt': []
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

/** 语音转写进行中：按钮转圈，避免用户连点重复上传 */
const transcribing = ref(false)

/** 听写是否可用：必须已绑定 ASR 模型且落到具体 Agent。未绑定时不做任何浏览器识别回退 */
const canRecord = computed(() => props.asrEnabled === true && !!props.agentId && !props.sending && !transcribing.value)

const {
  recording: voiceRecording,
  seconds: voiceSeconds,
  start: startVoiceRecording,
  stop: stopVoiceRecording,
  cancel: cancelVoiceRecording
} = useVoiceRecorder({
  // 60 秒与后端 max-audio-bytes（10MB）是同一档量级：正常 webm/opus 远小于 10MB
  maxSeconds: 60,
  maxBytes: 10 * 1024 * 1024,
  onComplete: handleRecorded,
  onCancel: () => ElMessage.info('已取消录音'),
  onError: (message) => ElMessage.warning(message)
})

/** 「说话」按钮的 Hover 说明。图标按钮没有可见文字，状态与原因全部靠它交代 */
const recordHint = computed(() => {
  if (!props.agentId) {
    return '当前会话没有 Agent，无法使用语音输入'
  }
  if (props.asrEnabled !== true) {
    return '未绑定语音识别模型，请前往 Agent 的「模型配置」Tab 绑定 ASR 模型'
  }
  if (transcribing.value) {
    return '正在识别，请稍候'
  }
  if (voiceRecording.value) {
    return `录音中 ${voiceSeconds.value}s，点击结束并识别（不会自动发送）`
  }
  return '点击开始录音，再点一次结束并识别（识别后不会自动发送）'
})

/** 点「说话」：未在录音则开始，录音中则结束并转写。 */
async function toggleRecording() {
  if (voiceRecording.value) {
    stopVoiceRecording()
    return
  }
  if (!canRecord.value) {
    return
  }
  try {
    await startVoiceRecording()
  } catch (error) {
    ElMessage.warning(errorMessage(error, '无法开始录音，请检查麦克风权限'))
  }
}

/**
 * 录音结束 → 调后端识别 → 把文字填进输入框。
 * 明确不自动发送：用户确认文字后再点发送，避免识别错误直接进入会话。
 *
 * @param audio 整段录音
 */
async function handleRecorded(audio: Blob) {
  const agentId = props.agentId
  if (!agentId) {
    return
  }
  transcribing.value = true
  try {
    const recognized = (await transcribeAudio(agentId, audio, recordingFileName(audio))).trim()
    if (!recognized) {
      ElMessage.warning('没有识别到内容，请靠近麦克风再说一次')
      return
    }
    // 已有草稿时追加，避免覆盖用户手打的内容
    text.value = text.value.trim() ? `${text.value.trim()} ${recognized}` : recognized
  } catch (error) {
    ElMessage.warning(errorMessage(error, '语音识别失败，请重试'))
  } finally {
    transcribing.value = false
  }
}

/**
 * 上传文件名：扩展名决定上游按什么容器解码，必须与录音 MIME 一致。
 *
 * @param audio 录音 Blob
 * @return 带扩展名的文件名
 */
function recordingFileName(audio: Blob): string {
  const type = audio.type.toLowerCase()
  if (type.includes('wav')) {
    return 'voice.wav'
  }
  if (type.includes('mpeg') || type.includes('mp3')) {
    return 'voice.mp3'
  }
  if (type.includes('mp4') || type.includes('m4a')) {
    return 'voice.m4a'
  }
  if (type.includes('ogg')) {
    return 'voice.ogg'
  }
  return 'voice.webm'
}

/**
 * 朗读播放器：同一条回答支持 播放 / 暂停 / 继续 / 停止。
 * 绑定 TTS 走模型音频；未绑定或合成失败自动降级浏览器朗读。
 */
const {
  activeKey: speakingAnswerKey,
  state: speechState,
  toggle: toggleSpeak,
  stop: stopSpeech
} = useSpeechPlayer({
  getAgentId: () => props.agentId,
  ttsEnabled: () => props.ttsEnabled === true,
  // 非打断轻提示：合成失败降级、浏览器不支持等，不弹红色报错
  onNotice: (message) => ElMessage.info(message)
})

/** 朗读按钮 Hover 说明：未绑定也能读，只是换浏览器引擎 */
const speakHint = computed(() =>
  props.ttsEnabled === true ? '使用 Agent 绑定的语音合成模型朗读' : '未绑定语音合成模型，将使用浏览器朗读'
)

/**
 * 自动朗读开关（喇叭）。默认关闭：进入页面不会突然出声。
 * 打开只影响「之后」完成的回答；会话历史与切换会话回放的旧回答一律不读。
 */
const autoSpeak = ref(false)

/**
 * 已经处理过的回答键。只用来防重复朗读，不参与「能不能读」的判断。
 */
const autoSpoken = new Set<string>()

/**
 * 本轮真正在本页跑过（出现过流式/待批准）的助手轮 key。
 *
 * <p>自动朗读只认这批轮次：历史回放、切换会话、刷新后续订拿到的旧回答从没在这里流过，
 * 因此绝不会被自动念出来。这也解决了「打开开关立刻念起历史记录」的问题——
 * 开关只影响此刻之后完成的回答。
 */
const liveTurns = new Set<string>()

/** 喇叭开关的 Hover 说明：把边界讲清楚，避免用户以为打开会补读历史 */
const speakerHint = computed(() =>
  autoSpeak.value
    ? '自动朗读已开启：只朗读之后完成的最终回答，点击关闭并停止当前朗读'
    : '自动朗读已关闭：点击开启，之后完成的最终回答自动朗读（不会补读历史）'
)

/**
 * 打开 / 关闭自动朗读。
 *
 * <p>打开不补读任何已有回答：历史记录不是用户此刻想听的内容，
 * 一开开关就念起来会被当成 bug（也确实打断了用户）。
 * 关闭时立刻停掉正在读的那一条，不等它读完。
 */
function toggleAutoSpeak(): void {
  autoSpeak.value = !autoSpeak.value
  if (!autoSpeak.value) {
    stopSpeech()
    return
  }
  // 打开时给一句反馈：否则用户看到按钮变色却听不到声音，会以为没生效。
  // 只有确实有回答正在生成时才不说——那条一会自己会读出来。
  if (!hasStreamingTurn()) {
    ElMessage.info('自动朗读已开启，之后完成的回答会自动朗读')
  }
}

/**
 * 当前是否有回答正在生成 / 等待批准。用于开关反馈的文案取舍。
 *
 * @return true 表示还有一轮在跑
 */
function hasStreamingTurn(): boolean {
  return props.turns.some(
    (turn) => turn.role === 'assistant' && (turn.status === 'streaming' || turn.planPending === true)
  )
}

/**
 * @param key 回答键
 * @return 该条是否正在播放/准备中/暂停
 */
function isSpeaking(key: string): boolean {
  return speakingAnswerKey.value === key
}

/**
 * @param key 回答键
 * @return 该条是否还在等模型合成
 */
function isPreparing(key: string): boolean {
  return speakingAnswerKey.value === key && speechState.value === 'preparing'
}

/**
 * @param key 回答键
 * @return 该条是否正在出声（暂停不算）
 */
function isPlaying(key: string): boolean {
  return speakingAnswerKey.value === key && speechState.value === 'playing'
}

/**
 * 单条朗读按钮的说明。图标按钮没有可见文字，含义全靠 tooltip 与 aria-label 交代。
 *
 * @param key 回答键
 * @return 中文说明
 */
function speakHintOf(key: string): string {
  if (speakingAnswerKey.value === key) {
    if (speechState.value === 'preparing') {
      return '正在合成语音，点击取消'
    }
    return speechState.value === 'paused' ? '继续朗读这条回答' : '暂停朗读'
  }
  return speakHint.value
}

/**
 * 一条「已完成的最终回答」。自动朗读只认这个结构：
 * 计划流用终答，非计划流用最后一段 assistant 块（与页面渲染口径一致）。
 */
interface SpeakTarget {
  key: string
  text: string
}

/**
 * 取一条助手轮「最终回答」的朗读键与原文。计划流用终答，非计划流用最后一段 assistant 块
 * （与页面渲染口径一致）；没有正文时返回 null。
 *
 * @param turn 助手轮
 * @return 回答键与原文；无可读内容时为 null
 */
function answerOf(turn: ChatTurn): SpeakTarget | null {
  if (isPlanFlow(turn)) {
    const answer = turnSections(turn.blocks).answer
    return answer ? { key: planAnswerKey(turn), text: answer } : null
  }
  // 非计划流：一段回答可能被拆成多块，只读最后一段正文，避免一段话被念两遍
  let last: SpeakTarget | null = null
  for (const block of turn.blocks) {
    if (block.kind === 'assistant' && block.text) {
      last = { key: blockAnswerKey(turn, block), text: block.text }
    }
  }
  return last
}

/**
 * 自动朗读的唯一入口：先给「本页真的在跑」的轮次记账，再只读刚完成的那些。
 *
 * <p>为什么不用「所有已完成回答」：会话历史、切换会话、刷新后续订回放都会把旧回答塞进
 * props.turns，按旧口径会在打开开关时把历史念一遍。这里以「曾在本页流式出现过」为闸，
 * 历史轮从未流过，自然不会被读。
 *
 * <p>入册与朗读解耦：开关关闭期间完成的回答也记进 autoSpoken，之后再打开不会补念。
 */
function flushAutoSpeak(): void {
  for (const turn of props.turns) {
    if (turn.role !== 'assistant') {
      continue
    }
    // 正在生成 / 等待批准：先记账，等它真正结束那一拍再读
    if (turn.status === 'streaming' || turn.planPending) {
      liveTurns.add(turn.key)
      continue
    }
    // 历史回放：本页从没见过它流过，不读、也不记账（与开关无关）
    if (!liveTurns.has(turn.key)) {
      continue
    }
    const target = answerOf(turn)
    if (!target || autoSpoken.has(target.key)) {
      continue
    }
    autoSpoken.add(target.key)
    if (!autoSpeak.value) {
      continue
    }
    // 与手动朗读共用同一个播放器：新一条会顶掉上一条，不会叠着念
    void toggleSpeak(target.key, target.text)
  }
}

/**
 * 自动朗读的最小依赖签名。
 *
 * <p>只收集「哪些助手轮存在、是否仍在进行 / 待批准」这几个浅字段：
 * 流式增量只改 blocks 里的正文，不会让签名变化，因此不再像 deep 监听那样
 * 每来一个 delta 就深遍历整棵 turns（历史轮越多越贵）。
 */
const speakSignature = computed(() =>
  props.turns
    .filter((turn) => turn.role === 'assistant')
    .map((turn) => `${turn.key}|${turn.status}|${turn.planPending === true ? 1 : 0}`)
    .join(';')
)

/**
 * 自动朗读的触发点：助手轮进入流式 / 结束 / 待批准解除时扫一遍。
 * 只在「回答已结束」时才会命中，因此流式增量不会误读半句。
 */
watch(speakSignature, flushAutoSpeak)

/**
 * 兜底触发：本轮发送结束（sending 由 true 变 false）后再扫一次。
 * 有些链路的终态是先改 sending 再回填正文，单靠 turns 深监听会漏掉这一拍。
 */
watch(
  () => props.sending,
  (now) => {
    if (!now) {
      flushAutoSpeak()
    }
  }
)

/** 计划执行的终答键：与块渲染区分，避免两条回答共用同一个播放状态 */
function planAnswerKey(item: ChatTurn): string {
  return `plan:${item.key}`
}

/**
 * 非计划流程里 assistant 块的朗读键。
 *
 * @param item  助手轮
 * @param block 块本身
 * @return 唯一键
 */
function blockAnswerKey(item: ChatTurn, block: AssistantBlock): string {
  // 用块稳定 id 而不是下标：插入视觉块时下标会整体位移，播放状态会串到别的块上
  return `block:${item.key}:${block.id ?? block.kind}`
}

/**
 * @param error    捕获到的异常
 * @param fallback 兜底文案
 * @return 可展示的中文原因
 */
function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

// 切 Agent 必须停掉正在进行的朗读与录音：上一条回答的声音不能串到新 Agent 的会话里
watch(
  () => props.agentId,
  () => {
    stopSpeech()
    cancelVoiceRecording()
  }
)

/** 上传按钮是否可用：需要已绑定 VISION 模型，且未超张数上限 */
const canUpload = computed(() => props.visionEnabled === true && attachments.value.length < MAX_IMAGES)

/** 图标按钮的实际可用态：除模型与张数外，发送中也不允许再选图（与旧按钮 disabled 口径一致） */
const canUploadNow = computed(() => canUpload.value && !props.sending)

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

/**
 * 最后一张助手轮的 key。
 * 编排数据只取「最近一轮 trace」，因此只挂在最后一轮上，避免把新一轮的泳道画进旧轮。
 */
const lastAssistantKey = computed(() => {
  for (let i = props.turns.length - 1; i >= 0; i -= 1) {
    if (props.turns[i].role === 'assistant') {
      return props.turns[i].key
    }
  }
  return ''
})

/**
 * 该轮是否渲染编排调度卡。
 * 条件：外层给了数据（无权限 / 非 Router 为 null）、是最后一轮、且带前端态标记。
 * @param item 助手轮
 */
function showRouterCard(item: ChatTurn) {
  return !!props.routerTrace && item.key === lastAssistantKey.value && hasRouterTrace(item)
}

/**
 * 编排卡状态：流式中＝正在调度，失败＝调度失败，其余＝完成。
 * @param item 助手轮
 */
function routerCardStatus(item: ChatTurn): 'running' | 'done' | 'error' {
  if (item.status === 'streaming') {
    return 'running'
  }
  return item.status === 'error' ? 'error' : 'done'
}

/**
 * 用户是否仍停在对话底部附近。
 * 只有它为 true 时才跟滚：上翻阅读历史时，不能被流式增量硬拽回底部。
 */
const nearBottom = ref(true)

/** 距底部多少像素内算「仍在底部」；略大于一行高度，避免轻微抖动就被判成上翻 */
const NEAR_BOTTOM_PX = 80

/** 已排队但还没执行的跟滚帧；同一帧内的多次更新只滚一次 */
let followFrame = 0

/**
 * 上一次由程序写入 `scrollTop` 后读回的实际值（-1 表示本轮还没写过）。
 *
 * <p>用途：区分「用户滚动」与「我们自己滚到底」。后者同样会派发 scroll 事件，
 * 且派发时正文可能又长了，若当成用户上翻就会把跟滚关掉（见 {@link resolveFollowAfterScroll}）。
 */
let selfScrollTop = -1

/**
 * 记录滚动位置：只在滚动事件里读一次布局，判断是否仍在底部附近。
 * 把布局读取从「每个增量」挪到「用户真的滚动」，避免流式期每帧强制同步布局。
 */
function onScroll() {
  const element = scroller.value
  if (!element) {
    return
  }
  nearBottom.value = resolveFollowAfterScroll({
    scrollTop: element.scrollTop,
    scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight,
    selfScrollTop,
    previous: nearBottom.value,
    threshold: NEAR_BOTTOM_PX
  })
}

/**
 * 强制恢复「跟到底部」。
 *
 * <p>用于语义上必然是「要看最新内容」的时刻：用户刚发问、刚切到另一个会话。
 * 这两处若沿用上一次的滚动状态，从旧会话带着「已上翻」进来就会一直不滚。
 */
function followToBottom() {
  nearBottom.value = true
  scheduleFollowBottom()
}

/**
 * 把跟滚合批到下一帧：一帧最多读一次 scrollHeight、写一次 scrollTop。
 *
 * <p>由 {@link onUpdated} 在内容（含 Markdown 增量）真正写进 DOM 之后触发；
 * 用户已上翻则直接跳过，不抢回滚动位置。
 */
function scheduleFollowBottom() {
  if (followFrame) {
    return
  }
  followFrame = requestAnimationFrame(() => {
    followFrame = 0
    if (!nearBottom.value) {
      return
    }
    const element = scroller.value
    if (!element) {
      return
    }
    // 读布局与写滚动在同一帧完成，只触发一次布局失效，替代原先每个 delta 一次
    element.scrollTop = element.scrollHeight
    // 读回钳制后的真实值：内容不足一屏时会被夹到 0，下次判定要认这个值
    selfScrollTop = element.scrollTop
  })
}

// 用户发问（sending 由 false 变 true）即视为「要看最新内容」：无论之前是否上翻，都重新跟到底
watch(
  () => props.sending,
  (now) => {
    if (now) {
      followToBottom()
    }
  }
)

/**
 * 会话身份（首轮 key）变化：新建会话或切到另一个会话，都要从底部开始看。
 * 不能只依赖上一次的滚动状态，否则从「上翻着的旧会话」切过来会停在顶部。
 */
watch(
  () => props.turns[0]?.key ?? '',
  () => {
    followToBottom()
  }
)

// 每次组件更新（流式增量 / 新轮次 / 排版变化）后安排一次跟滚；不再深监听整棵 turns
onUpdated(scheduleFollowBottom)

/**
 * 挂载时先定位到最新一条。
 * <p>抽屉关掉再打开、页签切回来都会重建组件，而挂载那一刻没有数据变化、不会触发 onUpdated，
 * 不补这一步就会停在最顶部，看起来像「没有自动定位」。
 */
onMounted(followToBottom)

// 卸载时取消未执行的帧，避免回调在已销毁组件上读 DOM
onBeforeUnmount(() => {
  if (followFrame) {
    cancelAnimationFrame(followFrame)
    followFrame = 0
  }
})

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

/**
 * 思考与终答共用轻量 Markdown。流式走安全前缀+表渐进，结束后完整终渲。
 * 原文标签不会执行。
 *
 * <p>按「渲染位置」缓存结果：流式期每帧只有正在增长的那一块签名会变，
 * 历史块的 HTML 直接命中缓存，不再每帧把全部历史重新跑一遍正则。
 *
 * @param slot      渲染位置键（轮次 + 块 + 区段），同一位置必须稳定不变
 * @param value     Markdown 原文
 * @param streaming 是否流式渲染（与终态分开缓存，避免脏 HTML）
 * @return 可直接 v-html 的 HTML
 */
function markdown(slot: string, value: string, streaming = false) {
  return renderMarkdownMemo(slot, value, streaming)
}

/** 计划流终答的缓存槽键。与朗读键区分：这里只标识渲染位置 */
function planAnswerSlot(item: ChatTurn): string {
  return `plan:${item.key}:answer`
}

/**
 * 块级 v-for 的 key。
 * <p>取数据层分配的稳定 id；块一旦建立就永远不变，视觉块/调度卡插到队首时其它块不会重挂。
 * 只有异常数据（外部构造、缺 id）才退化成「种类 + 轮内下标」——此时只保证同轮内不撞键。
 *
 * @param block 块
 * @param index 块在本轮内的下标；仅在缺 id 的兜底分支使用
 * @return 稳定 key
 */
function blockKey(block: AssistantBlock, index: number): string {
  return block.id || `out-${block.kind}-${index}`
}

/**
 * 非计划流程里 assistant 块的 Markdown 缓存槽键。
 * <p>与 v-for key 同源（稳定 id）：块顺序变化不会让历史块命中不到上一帧的 HTML。
 *
 * @param block 块
 * @param index 块在本轮内的下标；仅在缺 id 的兜底分支使用
 * @return 缓存槽键
 */
function blockSlot(block: AssistantBlock, index: number): string {
  return `out:${block.id || `${block.kind}-${index}`}`
}

/** 思考过程里单个步骤的缓存槽键；泳道与步骤序号共同定位 */
function stepSlot(item: ChatTurn, laneIndex: number, stepIndex: number): string {
  return `think:${item.key}:${laneIndex}:${stepIndex}`
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
  if (!canUploadNow.value) {
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

/* 回答标题行：左侧标题保持原下划线，右侧朗读控制与标题同一基线 */
.answer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.answer-head .answer-label {
  flex: 1;
}

/* 朗读控制：准备中 / 播放中 / 暂停共用同一组图标按钮，避免每态换一套 DOM 也不写旁注文字 */
.speak-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding-bottom: 8px;
  white-space: nowrap;
}

/* 单条回答的播放 / 暂停 / 停止：图标按钮，默认低饱和，播放中才点亮主色 */
.speak-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  color: var(--ah-text-secondary, #6b7280);
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.speak-icon .el-icon {
  font-size: 16px;
}

.speak-icon:hover {
  color: var(--el-color-primary, #635bff);
  background: #f2f1ff;
}

/* 这一条正在出声 / 合成：保持主色，用户能一眼看出声音来自哪条回答 */
.speak-icon.active {
  color: var(--el-color-primary, #635bff);
}

.speak-icon.stop:hover {
  color: var(--el-color-danger, #f56c6c);
  background: #fff1f3;
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
.md :deep(h3),
.md :deep(h4),
.md :deep(h5),
.md :deep(h6) {
  margin: 0.4em 0 0.25em;
  font-weight: 650;
  line-height: 1.35;
}

.answer :deep(h1) {
  font-size: 17px;
}

.answer :deep(h2) {
  font-size: 16px;
}

.answer :deep(h3) {
  font-size: 15px;
}

.answer :deep(h4) {
  font-size: 14px;
}

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

/* 图片按钮与语音按钮共用 .voice-icon-btn 的结构；这里只声明它自己的语义类 */

/*
 * 语音图标按钮（喇叭 / 说话 / 取消）：只放图标，不排文字。
 * 图标比常见小按钮略大（16→18px）并统一用主色，disabled 不用灰线框，
 * 而是「保留颜色 + 降透明度 + 拦截点击」，这样一眼能看出它属于哪个能力，而不是坏掉。
 */
.voice-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--el-color-primary, #635bff);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: color 0.15s, background 0.15s, opacity 0.15s;
}

.voice-icon-btn .el-icon {
  font-size: 18px;
}

.voice-icon-btn:hover {
  background: #f2f1ff;
}

/* 喇叭开启：实心主色块。开/关不只差一点颜色，形态本身就不一样 */
.speaker-btn.on {
  color: #fff;
  background: var(--el-color-primary, #635bff);
}

.speaker-btn.on:hover {
  background: #5148e0;
}

/* 未绑 ASR：按钮仍可 hover 出说明，但点击不生效，仅降透明度 */
.voice-icon-btn.muted {
  color: var(--el-color-primary, #635bff);
  opacity: 0.35;
  cursor: not-allowed;
}

/* 禁用态不给 hover 底色：避免看起来像可点，实际点了没反应 */
.voice-icon-btn.muted:hover {
  background: transparent;
}

/* 录音中：红色 + 图标脉动，明确「正在采集」而不是普通点击态 */
.voice-icon-btn.recording {
  color: #d03050;
  background: #fff1f3;
}

.voice-icon-btn.recording .el-icon {
  animation: voice-pulse 1s ease-in-out infinite;
}

/* 取消录音：中性色，不与「正在录音」抢视觉 */
.cancel-btn {
  color: var(--ah-text-muted, #9ca3af);
}

@keyframes voice-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.35;
  }
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
