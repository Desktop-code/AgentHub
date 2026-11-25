<template>
  <!--
    输入条：正文、待发附件、@ 指定线路、语音听写、拖拽 / 粘贴与停止 / 发送。所属域：runtime（前端展示）。
    整块自带本地状态（正文、附件、草稿槽、@ 面板、录音），只通过 props / emits 与壳层交互；
    不自己发消息、不碰 SSE：发送 / 停止 / 选线路都回到壳层原来那条回调。
  -->
  <div
    class="composer"
    :class="{ 'has-at-panel': atPanelOpen, 'is-drop': fileDragOver }"
    @paste="onComposerPaste"
    @dragenter="onComposerDragEnter"
    @dragover="onComposerDragOver"
    @dragleave="onComposerDragLeave"
    @drop="onComposerDrop"
  >
    <!-- 待发附件：图片缩略图和文件行在同一份列表里，发送前可逐个移除 -->
    <UserAttachmentStrip
      v-if="attachments.length"
      removable
      :disabled="sending"
      :items="pendingViews"
      @remove="removeAttachment"
    />
    <!-- @ 提及片：与正文分离，不进 {query} -->
    <div v-if="workbench && selectedRoute?.agentName" class="mention-chip-row">
      <span class="mention-chip">
        @{{ selectedRoute.agentName }}
        <button
          type="button"
          class="mention-chip-x"
          :disabled="sending"
          aria-label="取消指定线路"
          @click="emit('clear-route')"
        >
          ×
        </button>
      </span>
    </div>
    <!-- @ 候选面板：展示名检索，不暴露 route_key -->
    <ul v-if="atPanelOpen" class="at-panel" role="listbox" aria-label="指定线路">
      <li v-if="!filteredAtCandidates.length" class="at-empty">无匹配线路</li>
      <li
        v-for="(item, index) in filteredAtCandidates"
        :key="item.routeKey"
        class="at-item"
        :class="{ active: index === atActiveIndex }"
        role="option"
        :aria-selected="index === atActiveIndex"
        @mousedown.prevent="confirmAtPick(item)"
      >
        <span class="at-name">{{ item.agentName || '未命名线路' }}</span>
        <span v-if="item.intentDesc" class="at-desc">{{ item.intentDesc }}</span>
        <span v-if="item.targetType && item.targetType !== 'AGENT'" class="at-type">
          {{ item.targetType === 'MA' ? '方案' : item.targetType === 'WORKFLOW' ? '工作流' : '' }}
        </span>
      </li>
    </ul>
    <el-input
      v-model="text"
      type="textarea"
      :rows="2"
      :autosize="{ minRows: 2, maxRows: 10 }"
      resize="none"
      maxlength="8000"
      :placeholder="composerPlaceholder"
      :disabled="!canSend || sending"
      @keydown="onComposerKeydown"
      @input="onComposerInput"
    />
    <div class="composer-bar">
      <div class="composer-left">
        <!--
          自动朗读开关（喇叭）。开启后只朗读「之后完成」的最终回答（绑了 TTS 走模型音频，否则浏览器），
          绝不补读历史记录；关闭不再自动读，且立刻停掉正在朗读的那一条。
          开/关不用文字标注：开启时实心高亮，关闭时描边，靠形态与颜色一起区分。
          播放器单例仍在壳层，这里只画开关并抛出点击。
        -->
        <el-tooltip v-if="!workbench" :content="speakerHint || '自动朗读'" placement="top">
          <button
            type="button"
            class="voice-icon-btn speaker-btn"
            :class="{ on: autoSpeak }"
            :aria-pressed="autoSpeak"
            :aria-label="autoSpeak ? '关闭自动朗读' : '开启自动朗读'"
            @click="emit('toggle-auto-speak')"
          >
            <el-icon><Headset /></el-icon>
          </button>
        </el-tooltip>
        <!--
          语音听写：未落地能力在工作台不摆可点按钮（避免伪装可用）；
          调试形态仍保留置灰入口并说明原因。
        -->
        <el-tooltip v-if="showMicBtn" :content="recordHint" placement="top">
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
        <el-tooltip v-if="voiceRecording" content="取消本次录音" placement="top">
          <button type="button" class="voice-icon-btn cancel-btn" aria-label="取消本次录音" @click="cancelVoiceRecording">
            <el-icon><Close /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip v-if="showImageBtn && (!workbench || entryComposer)" :content="uploadHint" placement="top">
          <button
            type="button"
            class="voice-icon-btn attach-btn"
            :class="{ muted: !canUploadNow }"
            :aria-disabled="!canUploadNow"
            aria-label="添加附件"
            @click="pickImages"
          >
            <el-icon><Paperclip /></el-icon>
          </button>
        </el-tooltip>
        <!-- 首页只在已开视觉时保留原来的图片入口，不换成管理端的附件按钮 -->
        <el-tooltip v-else-if="showImageBtn" :content="uploadHint" placement="top">
          <button
            type="button"
            class="voice-icon-btn image-btn"
            :class="{ muted: !canUploadNow }"
            :aria-disabled="!canUploadNow"
            aria-label="上传图片或文件"
            @click="pickImages"
          >
            <el-icon><Picture /></el-icon>
          </button>
        </el-tooltip>
        <input
          ref="filePicker"
          type="file"
          accept="image/png,image/jpeg,image/webp,image/gif,.pdf,.txt,.csv,.xlsx,.xls,.docx,.doc,.pptx,.ppt,.zip,.webm,.wav,.mp3,.m4a,.ogg"
          multiple
          class="file-input"
          @change="onFilesPicked"
        />
        <!--
          工作台路由展示已换成每轮助手块顶部的常驻路由行（见 .wb-route-chain），
          输入条不再保留仅注解的「自动匹配专家」徽标。
          正文还在加载时同样不提示「请先新建会话」：那不是用户此刻的状态。
        -->
        <span class="hint">{{ threadLoading || sessionReady ? '' : blockedHint || '请先新建会话或从左侧选择' }}</span>
      </div>
      <!--
        停止按钮：管理端运行（非 workbench）有在途流时显示，行为与拆前一致；
        工作台（含 MA 运行输入条）显式传入 can-stop：运行还在进行就显示停止。
        can-stop 由壳层按「运行态可停」给（见 sessionProgress.canStopRun），
        刷新后仍在续跑（含工作流点选续跑这一拍）同样为真，所以停止按钮不会丢。
        工作流停在「等待用户回复」时这一轮已不在流式中，但用户仍要能停掉挂起的运行，
        因此 waiting-reply 为真时停止与发送同时出现（发送＝这就是那句回复）。
      -->
      <el-button
        v-if="stopShown"
        type="danger"
        plain
        :disabled="!stopEnabled"
        :title="stopEnabled ? undefined : '暂时不能停止，请稍候'"
        @click="requestStop"
      >
        停止
      </el-button>
      <el-button
        v-if="sendShown"
        type="primary"
        :loading="sending"
        :disabled="!sessionReady || sending"
        @click="submit"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 对话输入条。所属域：runtime（前端展示）。
 *
 * <p>职责：正文 / 待发附件 / 按会话草稿 / 语音听写 / @ 指定线路 / 拖拽与粘贴 / 停止与发送。
 * 这些状态原本就全在 ChatPanel 内部自洽，搬出来只换宿主，不新增协议。
 *
 * <p>约束：
 * - 发送、停止、选定 / 清除线路仍 emit 回壳层，由壳层原来那条回调处理；
 *   本组件不写第二套发消息或 SSE。
 * - 草稿槽的淘汰上限随组件走，壳层不再维护一份。
 * - `workbench` 由壳层按 skin 折算后传入，组件内不读 skin。
 */
import { computed, ref, watch } from 'vue'
import { composerButtons, runBlocksSending, runStoppable } from '@/components/runtime/sessionProgress'
import { Close, Headset, Loading, Microphone, Paperclip, Picture } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { transcribeAudio } from '@/api/voice'
import { useVoiceRecorder } from '@/composables/voiceRecorder'
import UserAttachmentStrip from '@/components/runtime/UserAttachmentStrip.vue'
import {
  classifyUserFile,
  clipboardAttachments,
  MAX_FILE_BYTES,
  MAX_USER_ATTACHMENTS,
  type OutgoingFile,
  type UserAttachmentView
} from '@/components/runtime/userMessageImages'
import type { WorkbenchRouteOption } from '@/types/workbench'
import {
  detectWorkbenchAtTrigger,
  filterWorkbenchAtCandidates,
  stripWorkbenchAtFragment
} from '@/utils/workbenchAtRoute'

const props = defineProps<{
  /** 是否工作台沉浸皮肤（由壳层折算，组件内不读 skin） */
  workbench: boolean
  /** 本轮是否正在发送 / 运行中 */
  sending: boolean
  /** 当前是否允许发送 */
  canSend: boolean
  /** 当前会话是否就绪（未就绪时发送按钮置灰） */
  sessionReady: boolean
  /** 不能发送时的说明；正文加载中不显示 */
  blockedHint?: string
  /** 正文是否仍在加载（首屏拉历史 / 取事件期间），此时不提示「请先新建会话」 */
  threadLoading?: boolean
  /** 工作台输入草稿键：按会话隔离未发送正文与附件；缺省不启用 */
  draftKey?: string
  /** 入口 Router 输入条：始终展示附件与麦克风，录音作为本轮附件发出 */
  entryComposer?: boolean
  /** 当前 Agent 是否已绑定可用的 VISION 模型 */
  visionEnabled?: boolean
  /** 当前 Agent 是否已绑定可用的 ASR 模型 */
  asrEnabled?: boolean
  /** 当前 Agent id：语音识别要落到具体 Agent 的模型槽 */
  agentId?: number
  /** 是否允许打开 @ 面板 */
  canPickRoute?: boolean
  /** @ 面板候选（只展示名，不暴露 route_key） */
  routeCandidates?: WorkbenchRouteOption[]
  /** 已选定的下一轮强制线路（提及片由壳层持有） */
  selectedRoute?: WorkbenchRouteOption | null
  /** 运行中是否允许停止；缺省（管理端运行）视为可用 */
  canStop?: boolean
  /**
   * 是否正在续订直播（刷新后重新接上这一轮，含点选续跑这一拍）。
   *
   * <p>这一拍没有在途流（sending=false），但运行确实还在进行：必须让它也算「还
   * 在跑」，输入区才能显示停止、不给发送。由壳层按自己的续订状态传入。
   */
  resuming?: boolean
  /** 工作流停在「等待用户回复」：停止与发送同时出现 */
  waitingReply?: boolean
  /** 自动朗读开关状态（播放器单例在壳层，这里只画开关） */
  autoSpeak?: boolean
  /** 喇叭按钮的 Hover 说明，由壳层按「是否有回答正在生成」给出 */
  speakerHint?: string
}>()

const emit = defineEmits<{
  /** 发消息：正文 / 图片 / 文件 / 图文顺序，与拆前一致 */
  send: [content: string, images?: string[], files?: OutgoingFile[], order?: Array<'image' | 'file'>]
  /** 请求停止本轮运行 */
  stop: []
  /** 选定 @ 线路 */
  'pick-route': [routeKey: string]
  /** 清除 @ 提及片 */
  'clear-route': []
  /** 点自动朗读喇叭（开 / 关由壳层决定并立即停读） */
  'toggle-auto-speak': []
}>()

/**
 * 本轮三个运行信号，与壳层共用一个形状（见 {@code sessionProgress.RunActivity}）。
 *
 * <p>三个都是真实信号，不从 can-stop 反推：can-stop 只回答「现在能不能点停止」，
 * 而「运行还在不在跑」还必须认续订（刷新后续跑这一拍没有在途流）。
 */
const runActivity = computed(() => ({
  sending: props.sending,
  waitingReply: props.waitingReply === true,
  resuming: props.resuming === true
}))

/**
 * 这一轮是否还有可中断的运行：在途流、真正的等待回复、或刷新后的续订中。
 *
 * <p>与壳层同一条纯函数（{@code sessionProgress.runStoppable}），
 * 工作台 / MA 运行 / 管理端共用一个口径，不在模板里各写一遍条件。
 */
const runActive = computed(() => runStoppable(runActivity.value))

/**
 * 停止 / 发送两个按钮的显隐，与工作台、MA 运行共用同一条判定
 * （{@code sessionProgress.composerButtons}）：运行还在进行就只有停止。
 */
const buttons = computed(() => composerButtons(runActivity.value))

/**
 * 是否显示停止按钮：canSend 是总闸，其余交给共用判定。
 */
const stopShown = computed(() => props.canSend && buttons.value.stop)

/**
 * 停止按钮是否可点。
 *
 * <p>can-stop 明确为 false 时置灰（会话未就绪 / 本轮已停过），避免对同一轮重复发取消；
 * 但「等待回复」这一拍即使 can-stop 为 false 也要放行——那是另一条挂起运行，
 * 停它不是重复操作（与拆前口径一致）。
 */
const stopEnabled = computed(() => props.canStop !== false || props.waitingReply === true)

/**
 * 是否显示发送按钮。运行还在进行（在途流 / 刷新后续跑）时一律不露发送：
 * 否则用户会把下一句当成新问题发出去，而这一轮其实还在跑。
 */
const sendShown = computed(() => props.canSend && buttons.value.send)

function requestStop() {
  /*
   * 与露出条件同一口径：runActive 为真说明这一轮确实还在跑（含刷新后续跑），可发停止。
   * 管理端运行没传 can-stop 时，runActive 只认在途流，行为与拆前一致。
   */
  if (!runActive.value) {
    return
  }
  emit('stop')
}

const text = ref('')
/** 原生文件选择器句柄。用 input 而非 el-upload：只需读文件转 base64，不需要上传进度与分片 */
const filePicker = ref<HTMLInputElement>()
/** 已选待发图片。发送成功后才清空，失败保留让用户重试 */
const attachments = ref<Attachment[]>([])
/** 自增 id，仅用于列表 key 与删除定位 */
let attachmentSeq = 0

/** 待发送的图片或文件。id 只用于列表 key 与删除定位，图和文件共用这一份 */
interface Attachment {
  id: number
  name: string
  size: number
  kind: 'image' | 'file'
  /** data:{mime};base64,…，随发送一起提交给后端 */
  dataUrl: string
}

/**
 * 一条会话的未发送输入。
 * 图片 / 文件带 base64，故只留在内存，且限制条目数（见 MAX_COMPOSER_DRAFTS）。
 */
interface ComposerDraft {
  text: string
  attachments: Attachment[]
}

/**
 * 工作台按会话保存的未发送正文与附件（键见壳层传入的 draftKey）。
 *
 * <p>为什么必须有这一份：输入条在切会话时不卸载，单个 `text` / `attachments`
 * 会把 A 的字带到 B。这里不新做协议，只是把同一份本地输入按会话分槽。
 */
const composerDrafts = new Map<string, ComposerDraft>()
/** 草稿槽条数上限：长时间来回切会话时，不要为每条会话都留一份 */
const MAX_COMPOSER_DRAFTS = 20
/** 草稿槽总字符上限（附件是 base64）：条数不爆，但大附件仍可能把内存撑高，按量再兜一层 */
const MAX_COMPOSER_DRAFT_CHARS = 32 * 1024 * 1024

/** 一条草稿的近似占用：正文 + 各附件 dataUrl 长度（base64 已含在字符串长度里） */
function composerDraftWeight(draft: ComposerDraft): number {
  return draft.text.length + draft.attachments.reduce((sum, item) => sum + item.dataUrl.length, 0)
}

/**
 * 把当前输入存进指定会话槽。
 *
 * <p>空正文 + 空附件不占槽：既避免无意义条目，也保证「手动清空后切走再回来」是空输入。
 * Map 重新插入以刷新顺序，超条数或超总量时从最早的一条开始淘汰（不淘汰刚写入的这条）。
 *
 * @param key 会话槽键（真实会话 / 临时草稿各自唯一）
 */
function stashComposerDraft(key: string) {
  const hasContent = text.value.trim().length > 0 || attachments.value.length > 0
  composerDrafts.delete(key)
  if (!hasContent) {
    return
  }
  composerDrafts.set(key, { text: text.value, attachments: [...attachments.value] })
  let totalChars = 0
  for (const draft of composerDrafts.values()) {
    totalChars += composerDraftWeight(draft)
  }
  while (composerDrafts.size > MAX_COMPOSER_DRAFTS || totalChars > MAX_COMPOSER_DRAFT_CHARS) {
    const oldest = composerDrafts.keys().next().value
    if (oldest === undefined || oldest === key) {
      break
    }
    const oldestDraft = composerDrafts.get(oldest)
    composerDrafts.delete(oldest)
    if (oldestDraft) {
      totalChars -= composerDraftWeight(oldestDraft)
    }
  }
}

/**
 * 从会话槽恢复输入；没存过就回到空输入，绝不沿用上一条会话的内容。
 *
 * @param key 目标会话槽键
 */
function restoreComposerDraft(key: string) {
  const saved = composerDrafts.get(key)
  text.value = saved ? saved.text : ''
  attachments.value = saved ? [...saved.attachments] : []
  // 旧会话的 @ 检索态不属于新正文，切过来先收起候选面板
  closeAtPanel()
}

/** 语音转写进行中：按钮转圈，避免用户连点重复上传 */
const transcribing = ref(false)

/** 听写是否可用。入口允许未绑 ASR 也录音，失败写在预处理步骤上。 */
const canRecord = computed(() => {
  if (props.sending || transcribing.value) {
    return false
  }
  if (props.entryComposer === true) {
    return true
  }
  return props.asrEnabled === true && !!props.agentId
})

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

/**
 * 草稿随会话切换：先存旧键、再读新键。
 *
 * <p>只在传入 draftKey 时生效；管理端运行 / MA 运行不传，单个输入的行为与拆前一致。
 * 之所以放在组件内而不是提到外层：正文、附件、粘贴 / 拖入都在这份本地输入里，
 * 由外层持有再回传只会多一层容易走样的同步。
 *
 * <p>注册点必须在录音组合式之后：切会话要取消正在录的音，回调里用到该句柄。
 */
watch(
  () => props.draftKey,
  (nextKey, prevKey) => {
    if (nextKey === prevKey) {
      return
    }
    if (prevKey) {
      stashComposerDraft(prevKey)
    }
    // 正在录的音不属于任何一条会话：切走先取消，避免录完后落进新会话的附件
    cancelVoiceRecording()
    if (nextKey) {
      restoreComposerDraft(nextKey)
    }
  },
  { immediate: true }
)

// 切 Agent 要停掉正在进行的录音：这段音频属于上一条会话的模型槽。
// 朗读（speechPlayer 单例）仍在壳层停，这里只收录音。
watch(
  () => props.agentId,
  () => {
    cancelVoiceRecording()
  }
)

/** 入口始终摆麦克风；管理端工作台未绑 ASR 时仍隐藏，避免伪装可用 */
const showMicBtn = computed(
  () =>
    props.entryComposer === true ||
    !props.workbench ||
    props.asrEnabled === true ||
    voiceRecording.value
)
/** 入口始终摆附件；管理端工作台未绑视觉时仍隐藏图片入口 */
const showImageBtn = computed(
  () => props.entryComposer === true || !props.workbench || props.visionEnabled === true
)

/** 「说话」按钮的 Hover 说明。图标按钮没有可见文字，状态与原因全部靠它交代 */
const recordHint = computed(() => {
  if (props.entryComposer === true) {
    if (voiceRecording.value) {
      return `录音中 ${voiceSeconds.value}s，点击结束并作为附件发送`
    }
    if (props.asrEnabled !== true) {
      return '入口未绑定语音识别模型时仍可录音发送，过程里会说明失败原因'
    }
    return '点击开始录音，再点一次结束；录音作为本轮附件发出，过程里可见转写'
  }
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
  if (props.entryComposer === true) {
    const file = new File([audio], recordingFileName(audio), { type: audio.type || 'audio/webm' })
    await addImageFiles([file])
    return
  }
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

/** @ 面板是否打开 */
const atPanelOpen = ref(false)
/** @ 后的检索片段（不含 @） */
const atQuery = ref('')
/** 键盘高亮下标 */
const atActiveIndex = ref(0)
/**
 * 触发 @ 时，输入框里「@检索片段」的起始下标；关闭面板时用于剥离。
 * -1 表示当前没有挂起的 @ 片段。
 */
const atTriggerStart = ref(-1)

/** 按展示名过滤候选；永不展示 route_key */
const filteredAtCandidates = computed(() =>
  filterWorkbenchAtCandidates(props.routeCandidates, atQuery.value)
)

/** 工作台占位：恢复带「@ 指定线路」的原文，@ 的能力与列表逻辑都不改 */
const composerPlaceholder = computed(() =>
  props.workbench
    ? '输入问题，@ 指定线路；Enter 发送，Shift+Enter 换行'
    : '输入问题，Enter 发送，Shift+Enter 换行'
)

/**
 * 根据 textarea 内容同步 @ 面板开闭与检索词。
 * 只认最后一个未闭合的 @（其后无空格）；有提及片后仍可再 @ 换人。
 */
function syncAtPanelFromText() {
  if (!props.workbench || props.canPickRoute === false) {
    closeAtPanel()
    return
  }
  const hit = detectWorkbenchAtTrigger(text.value)
  if (!hit) {
    closeAtPanel()
    return
  }
  atTriggerStart.value = hit.start
  atQuery.value = hit.query
  if (!atPanelOpen.value) {
    atActiveIndex.value = 0
  }
  atPanelOpen.value = true
  // 检索变化时把高亮钳在范围内
  if (atActiveIndex.value >= filteredAtCandidates.value.length) {
    atActiveIndex.value = Math.max(0, filteredAtCandidates.value.length - 1)
  }
}

/** 关闭 @ 面板（不改正文） */
function closeAtPanel() {
  atPanelOpen.value = false
  atQuery.value = ''
  atTriggerStart.value = -1
  atActiveIndex.value = 0
}

/**
 * 选定候选：剥离输入框里的 @片段，发出 pick-route；提及片由壳层 selectedRoute 驱动。
 *
 * @param item 候选
 */
function confirmAtPick(item: WorkbenchRouteOption) {
  if (!item?.routeKey) {
    return
  }
  text.value = stripWorkbenchAtFragment(text.value, atTriggerStart.value)
  closeAtPanel()
  emit('pick-route', item.routeKey)
}

/**
 * 输入区键盘：面板打开时上下/Enter/Esc 优先；否则 Enter 发送。
 *
 * @param event 键盘事件（el-input 透传）
 */
function onComposerKeydown(event: Event | KeyboardEvent) {
  const e = event as KeyboardEvent
  if (!e || typeof e.key !== 'string') {
    return
  }
  if (atPanelOpen.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      const max = filteredAtCandidates.value.length
      if (max > 0) {
        atActiveIndex.value = (atActiveIndex.value + 1) % max
      }
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      const max = filteredAtCandidates.value.length
      if (max > 0) {
        atActiveIndex.value = (atActiveIndex.value - 1 + max) % max
      }
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      // Esc：关掉面板并剥离未完成的 @片段，正文保留其余
      text.value = stripWorkbenchAtFragment(text.value, atTriggerStart.value)
      closeAtPanel()
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      const pick = filteredAtCandidates.value[atActiveIndex.value]
      if (pick) {
        confirmAtPick(pick)
      }
      return
    }
  }
  // 面板关闭：Enter 发送，Shift+Enter 换行（不 prevent）
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

/** el-input 内容变化时同步 @ 面板 */
function onComposerInput() {
  syncAtPanelFromText()
}

watch(
  () => props.canPickRoute,
  (ok) => {
    if (ok === false) {
      closeAtPanel()
    }
  }
)

/** 待发附件，图和文件加在一起，和已发送用同一条附件条 */
const pendingViews = computed<UserAttachmentView[]>(() =>
  attachments.value.map((item) => ({
    kind: item.kind,
    url: item.dataUrl,
    name: item.name,
    size: item.size
  }))
)

/** 图标按钮：发送中不可再选；到 9 个上限时也不可再选。没有视觉模型仍可加文件。 */
const canUploadNow = computed(() => !props.sending && attachments.value.length < MAX_USER_ATTACHMENTS)

/** 按钮旁的说明。没有视觉模型时说清楚图片不会加入，文件仍然可以。 */
const uploadHint = computed(() => {
  if (attachments.value.length >= MAX_USER_ATTACHMENTS) {
    return `一次最多 ${MAX_USER_ATTACHMENTS} 个附件`
  }
  if (props.entryComposer === true) {
    return '可添加图片、文档或录音，单个文件不超过 12MB'
  }
  if (props.visionEnabled !== true) {
    return '未配置视觉模型，图片不会加入；文档仍可添加'
  }
  return '可添加图片或文档，单个文件不超过 12MB'
})

/** 单张图片的字节上限。与后端 VisionOcrTexts.MAX_IMAGE_CHARS 的量级对齐（约 6MB） */
const MAX_IMAGE_BYTES = 6 * 1024 * 1024

/**
 * 空态快捷卡等外部入口：填入正文并立刻发送（与点发送等价，走同一条 submit）。
 *
 * <p>之所以由壳层通过 expose 调用而不是壳层自己存一份正文：正文、附件与发送前校验
 * 都在输入条里，壳层再存一份就会出现两个真源。
 *
 * @param prompt 预置文案
 */
function sendPrompt(prompt: string) {
  const content = prompt.trim()
  if (!content || !props.canSend || !props.sessionReady || runBlocksSending(runActivity.value)) {
    return
  }
  text.value = content
  submit()
}

/** 发送：只发图、只发文件，或和文字一起发都可以；发出后清空本地输入与草稿槽 */
function submit() {
  const content = text.value.trim()
  // 未绑定视觉模型时图片不提交：后端会直接拒。文件不走视觉，没有视觉模型也可以发。
  const sendingItems = attachments.value.filter(
    (item) =>
      item.kind === 'file' ||
      (item.kind === 'image' && (props.visionEnabled === true || props.entryComposer === true))
  )
  const images = sendingItems.filter((item) => item.kind === 'image').map((item) => item.dataUrl)
  const files: OutgoingFile[] = sendingItems
    .filter((item) => item.kind === 'file')
    .map((item) => ({ name: item.name, size: item.size, dataUrl: item.dataUrl }))
  const order = sendingItems.map((item) => item.kind)
  /*
   * 闸门与按钮显隐用同一条判定：运行还在进行（在途流 / 刷新后续跑）时，
   * 回车、快捷卡、外部 sendPrompt 都不能把下一句当新问题发出去。
   */
  if (
    (!content && images.length === 0 && files.length === 0) ||
    !props.canSend ||
    !props.sessionReady ||
    runBlocksSending(runActivity.value)
  ) {
    return
  }
  emit(
    'send',
    content,
    images.length ? images : undefined,
    files.length ? files : undefined,
    files.length ? order : undefined
  )
  text.value = ''
  attachments.value = []
  // 发出即丢掉这条会话的未发送槽：失败轮自己已有气泡可重试，输入框里不再留副本；
  // 草稿会话随后会换成真实主键，新键同样是空输入
  if (props.draftKey) {
    composerDrafts.delete(props.draftKey)
  }
}

/**
 * 把选中或粘贴的文件并进同一份待发列表。图片仍走贴图；文档显示文件名和大小。
 *
 * @param files 按用户给出的顺序
 */
async function addImageFiles(files: File[]) {
  if (!files.length) {
    return
  }
  let warnedImage = false
  let droppedByCap = 0
  for (const file of files) {
    const kind = classifyUserFile(file)
    const name = file.name || '未命名文件'
    if (kind === 'blocked') {
      ElMessage.warning(`${name} 是可执行文件，不能发送`)
      continue
    }
    if (kind === 'file' && name.length > 80) {
      ElMessage.warning(`${name} 文件名过长，请缩短后再发送`)
      continue
    }
    if (kind === 'unsupported') {
      ElMessage.warning(`${name} 不支持。仅支持 pdf、txt、csv、xlsx、xls、docx、doc、pptx、ppt、zip 以及录音`)
      continue
    }
    if (kind === 'image') {
      if (props.visionEnabled !== true && props.entryComposer !== true) {
        if (!warnedImage) {
          ElMessage.warning('未配置视觉模型，图片未加入')
          warnedImage = true
        }
        continue
      }
      if (attachments.value.length >= MAX_USER_ATTACHMENTS) {
        droppedByCap += 1
        continue
      }
      if (!file.type.startsWith('image/') && !['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(extensionOfName(name))) {
        continue
      }
      if (file.size > MAX_IMAGE_BYTES) {
        ElMessage.warning(`${name} 超过 6MB，请压缩后再上传`)
        continue
      }
      try {
        const dataUrl = await readAsDataUrl(file)
        attachmentSeq += 1
        attachments.value = [
          ...attachments.value,
          { id: attachmentSeq, name: file.name || `图片 ${attachmentSeq}`, size: file.size, kind: 'image', dataUrl }
        ]
      } catch {
        ElMessage.warning(`${name} 读取失败`)
      }
      continue
    }
    if (attachments.value.length >= MAX_USER_ATTACHMENTS) {
      droppedByCap += 1
      continue
    }
    if (file.size > MAX_FILE_BYTES) {
      ElMessage.warning(`${name} 超过 12MB，请压缩后再上传`)
      continue
    }
    try {
      const dataUrl = await readAsDataUrl(file)
      attachmentSeq += 1
      attachments.value = [
        ...attachments.value,
        { id: attachmentSeq, name, size: file.size, kind: 'file', dataUrl }
      ]
    } catch {
      ElMessage.warning(`${name} 读取失败`)
    }
  }
  if (droppedByCap > 0) {
    ElMessage.info(`一次最多 ${MAX_USER_ATTACHMENTS} 个附件，其余未加入`)
  }
}

/**
 * 文件名的小写扩展名。空类型的 png 也要能认成图片。
 *
 * @param name 文件名
 * @return 不含点的扩展名；没有点时为空串
 */
function extensionOfName(name: string): string {
  const dot = name.lastIndexOf('.')
  if (dot < 0 || dot === name.length - 1) {
    return ''
  }
  return name.slice(dot + 1).toLowerCase()
}

/**
 * 输入框粘贴。剪贴板是文件时收进待发列表；是图片时仍走贴图；是文字则不拦截。
 *
 * @param event 输入框上的粘贴事件
 */
function onComposerPaste(event: ClipboardEvent) {
  const picks = clipboardAttachments(event.clipboardData)
  if (!picks.length || event.defaultPrevented) {
    return
  }
  event.preventDefault()
  if (props.sending) {
    return
  }
  void addImageFiles(picks.map((item) => item.file))
}

/** 拖入高亮。只在拖的是文件时亮，离开输入区就灭 */
const fileDragOver = ref(false)

/**
 * 剪贴板和拖入都认 Files。文字拖动不拦截，避免挡住输入框里的选区拖动。
 *
 * @param event 拖放事件
 * @return 载荷里有文件才为 true
 */
function dragHasFiles(event: DragEvent): boolean {
  const types = event.dataTransfer?.types
  if (!types) {
    return false
  }
  return Array.from(types).includes('Files')
}

/**
 * 拖进输入区。子元素之间移动不算离开，避免高亮闪一下。
 *
 * @param event 拖入事件
 */
function onComposerDragEnter(event: DragEvent) {
  if (!dragHasFiles(event)) {
    return
  }
  event.preventDefault()
  fileDragOver.value = true
}

/**
 * 必须 preventDefault，浏览器才允许在这块区域松开文件。
 *
 * @param event 拖过事件
 */
function onComposerDragOver(event: DragEvent) {
  if (!dragHasFiles(event)) {
    return
  }
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
  fileDragOver.value = true
}

/**
 * 指针还在输入区内部（进了文本框或缩略图）时不取消高亮。
 *
 * @param event 拖离事件
 */
function onComposerDragLeave(event: DragEvent) {
  const host = event.currentTarget as HTMLElement | null
  const next = event.relatedTarget as Node | null
  if (host && next && host.contains(next)) {
    return
  }
  fileDragOver.value = false
}

/**
 * 松开后进入与选附件、Ctrl+V 同一份待发列表。文件夹不递归。
 *
 * @param event 松开事件
 */
function onComposerDrop(event: DragEvent) {
  fileDragOver.value = false
  if (!dragHasFiles(event)) {
    return
  }
  event.preventDefault()
  if (props.sending) {
    return
  }
  const dropped = filesFromDrop(event.dataTransfer)
  if (dropped.directories > 0) {
    ElMessage.warning('不支持拖入文件夹，请改为拖入文件')
  }
  if (dropped.files.length) {
    void addImageFiles(dropped.files)
  }
}

/** 资源管理器拖入的一项。目录用 webkitGetAsEntry 识别，不能靠文件大小猜 */
interface DropEntry {
  isDirectory: boolean
  isFile: boolean
}

/**
 * 按拖入顺序收集文件。目录只计数，不展开。
 *
 * @param data 拖放载荷，可空
 * @return 文件按松开时的顺序；directories 大于 0 表示其中夹了文件夹
 */
function filesFromDrop(data: DataTransfer | null): { files: File[]; directories: number } {
  if (!data) {
    return { files: [], directories: 0 }
  }
  const files: File[] = []
  let directories = 0
  const items = data.items
  if (items && items.length) {
    for (const item of Array.from(items)) {
      if (item.kind !== 'file') {
        continue
      }
      const entry = (item as DataTransferItem & { webkitGetAsEntry?: () => DropEntry | null }).webkitGetAsEntry?.()
      if (entry?.isDirectory) {
        directories += 1
        continue
      }
      const file = item.getAsFile()
      if (file) {
        files.push(file)
      }
    }
    return { files, directories }
  }
  return { files: Array.from(data.files || []), directories: 0 }
}

/** 点附件：走原生文件选择器，图片和允许的文档同一次选出，不另开上传接口 */
function pickImages() {
  if (!canUploadNow.value) {
    if (!props.sending) {
      ElMessage.warning(uploadHint.value)
    }
    return
  }
  filePicker.value?.click()
}

/**
 * 文件选择器与粘贴共用同一份待发列表。
 *
 * @param event 原生 input 的 change 事件
 */
async function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  // 立刻清空 value：否则同一个文件再次选择不会触发 change
  input.value = ''
  await addImageFiles(files)
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

/**
 * @param error    捕获到的异常
 * @param fallback 兜底文案
 * @return 可展示的中文原因
 */
function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

// 空态快捷卡 / 其它外部入口只认这一条：与点「发送」走同一份校验与清空逻辑
defineExpose({ sendPrompt })
</script>

<style scoped>
/*
 * 输入条与首页同一套：浅底圆角容器，正文在上，工具和发送在容器内底行。
 * 工作台只额外限宽居中，不另画一层面板；限宽规则留在壳层，子组件根节点会继承父作用域属性。
 */
.composer {
  position: relative;
  flex: none;
  margin-top: 12px;
  padding: 12px 14px 10px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: #f5f7fa;
  box-shadow: none;
  box-sizing: border-box;
}

.composer:focus-within,
.composer.is-drop {
  background: #fff;
  border-color: var(--ah-primary, #635bff);
  box-shadow: 0 0 0 3px rgba(99, 91, 255, 0.22);
}

.composer :deep(.el-textarea__inner) {
  background: transparent !important;
  box-shadow: none !important;
  border-color: transparent !important;
  /* 与对话页同一高度：两行起，没有拉高手柄；字多了再一起长高 */
  resize: none;
  padding: 6px 0;
  min-height: 56px;
  line-height: 1.7;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}

.composer-bar :deep(.el-button) {
  flex: none;
}

.composer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 附件按钮与语音按钮共用 .voice-icon-btn；回形针只表示「添加附件」，不另做一套图标 */

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

.mention-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 8px;
}

.mention-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px 2px 10px;
  border-radius: 999px;
  background: #efeefe;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

.mention-chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.mention-chip-x:hover:not(:disabled) {
  background: rgba(99, 91, 255, 0.15);
}

.mention-chip-x:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.at-panel {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: calc(100% + 6px);
  z-index: 12;
  max-height: 220px;
  margin: 0;
  padding: 6px 0;
  overflow: auto;
  list-style: none;
  border: 1px solid var(--ah-border-soft, #e8eaef);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
}

.at-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
  padding: 8px 14px;
  cursor: pointer;
}

.at-item:hover,
.at-item.active {
  background: #f5f3ff;
}

.at-name {
  color: var(--ah-text, #1f2937);
  font-size: 13px;
  font-weight: 600;
}

.at-desc {
  flex: 1;
  min-width: 0;
  color: #9ca3af;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.at-type {
  flex: none;
  padding: 0 6px;
  border-radius: 4px;
  background: #f3f4f6;
  color: #6b7280;
  font-size: 11px;
}

.at-empty {
  padding: 10px 14px;
  color: #9ca3af;
  font-size: 12px;
}
</style>
