<template>
  <!--
    L3 提示词统一大弹窗（CHAT / ROUTER / REWRITE / SUMMARY / MEMORY 共用）。所属域：agent。
    页内只保留「摘要 + 配置提示词」，整套表单都收在本弹窗里，避免页面被旧「大文本 + 平台约束」双栏撑开。
    落哪张表由调用方决定（CHAT → agent_def，非 CHAT → agent_model_bind），本组件只负责编辑与校验。
  -->
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="80vw"
    top="4vh"
    class="prompt-l3-dialog"
    append-to-body
    :close-on-click-modal="false"
    @close="emit('update:modelValue', false)"
  >
    <p class="dialog-lead">
      模板锁定段落由平台维护；这里只能调整「可覆盖段落」与追加要求。保存前可点「预览」核对最终注入内容。
    </p>

    <div class="dialog-body">
      <PromptL3Form
        ref="formRef"
        :purpose="purpose"
        :draft="draft"
        :locale="locale"
        :agent-name="agentName"
        :show-extra="true"
        :extra-collapsed="!isChat"
        :disabled="disabled"
        @update:template-id="onTemplateId"
        @update:template-version="(value) => (draft.templateVersion = value)"
        @update:override="(value) => (draft.override = value)"
        @update:prompt-extra="(value) => (draft.promptExtra = value)"
        @update:previewable="(value) => (previewable = value)"
      />

      <!--
        存量「业务提示词」只读展示：这是未挂 L2 时的旧自由文本通道，已不再是常规编辑路径。
        这里不给输入框，避免重新长出「模板 + 整段业务正文」双轨；保存时由服务端
        （或页面提交链路）把这段正文并入「追加要求」并清空 system_prompt。
      -->
      <div v-if="isChat && legacyText" class="legacy-block">
        <p class="block-title">原「系统提示词」正文（历史配置）</p>
        <pre class="legacy-text">{{ legacyText }}</pre>
        <p class="block-hint">
          该 Agent 目前按这段旧正文运行。保存后会钉住平台默认对话模板，并把这段正文并入「追加要求」，
          不需要手工迁移。
        </p>
      </div>
    </div>

    <!--
      页脚操作区：预览 | 取消 | 保存。
      预览与取消同级（普通按钮），保存仍是唯一主按钮；顺序按「先看效果 → 再决定存不存」。
    -->
    <template #footer>
      <div class="dialog-footer">
        <el-button
          :loading="previewing"
          :disabled="disabled || !previewable"
          title="按当前表单值走后端 PromptAssembler 组装，不调用模型"
          @click="onPreview"
        >
          预览
        </el-button>
        <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="disabled" @click="submit">保存</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * L3 统一大弹窗。所属域：agent。
 * <p>
 * 为什么收成弹窗：页内内嵌整套 L3 表单会把基本信息区撑成「左大文本 + 右平台约束」的旧形态，
 * 且选择模板后一旦 supported 丢失就会整块塌回旧双栏（本轮要修的线上现象）。
 * 现在页面只留摘要行，编辑态一律在本弹窗内完成，CHAT 与非 CHAT 走同一个组件、同一套交互。
 * <p>
 * 数据边界：弹窗内部维护草稿深拷贝，取消即丢弃；保存成功后由调用方落库并刷新回显。
 * 本组件不发请求（模板段落与预览由 {@link PromptL3Form} 自己读），也不写库。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PromptL3Form from '@/components/agent/PromptL3Form.vue'
import type { PromptBinding } from '@/types/agent'

/** 各用途在弹窗标题里的中文名；CHAT 不叫「对话 · 提示词」而叫「系统提示词」 */
const TITLE_BY_PURPOSE: Record<string, string> = {
  CHAT: '系统提示词',
  ROUTER: '路由 · 提示词',
  REWRITE: '查询改写 · 提示词',
  SUMMARY: '摘要 · 提示词',
  MEMORY: '记忆抽取 · 提示词'
}

const props = defineProps<{
  modelValue: boolean
  /** 用途码：CHAT / ROUTER / REWRITE / SUMMARY / MEMORY */
  purpose: string
  /** 当前已保存的四列（打开时深拷贝成草稿） */
  binding?: PromptBinding | null
  /** CHAT 的历史业务提示词（system_prompt）；非 CHAT 不传 */
  legacyPrompt?: string
  locale?: string
  agentName?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** CHAT 的业务提示词改写 / 清空 */
  'update:legacyPrompt': [value: string]
  /**
   * 提交四列。done(true) 关闭弹窗，done(false) 保留弹窗让用户继续修正。
   */
  save: [payload: PromptBinding, done: (ok: boolean) => void]
}>()

/** 编辑草稿：与列表/表单对象隔离，取消不污染已保存值 */
const draft = reactive<PromptBinding>({
  templateId: null,
  templateVersion: null,
  override: {},
  promptExtra: ''
})

const formRef = ref<{
  validate: () => string | null
  preview: () => Promise<void>
} | null>(null)
const submitting = ref(false)
/** 预览可用态由子表单回传（已选模板 + 无失效段），页脚按钮据此置灰 */
const previewable = ref(false)
/** 预览请求进行中：与保存分开，避免互相顶掉 loading */
const previewing = ref(false)

/** CHAT 走追加区常显；非 CHAT 默认折叠，避免判路由 / 摘要这类用途被无关输入框干扰 */
const isChat = computed(() => props.purpose === 'CHAT')
const dialogTitle = computed(() => TITLE_BY_PURPOSE[props.purpose] || `${props.purpose} · 提示词`)
/** 弹窗内的历史业务正文（只读）；保存时由提交链路并入追加要求，不再作为独立编辑区 */
const legacyText = computed(() => (props.legacyPrompt || '').trim())

/**
 * 打开时把已保存的四列深拷贝进草稿。
 * override 必须新建对象，否则弹窗内编辑会直接改到详情表单里的那份数据。
 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      submitting.value = false
      return
    }
    draft.templateId = props.binding?.templateId ?? null
    draft.templateVersion = props.binding?.templateVersion ?? null
    draft.override = { ...(props.binding?.override || {}) }
    draft.promptExtra = props.binding?.promptExtra || ''
    submitting.value = false
    // 预览可用态先按「已绑模板」预估，随后由子表单回传精确值；
    // 避免换用途后短暂沿用上一个用途的可用态
    previewable.value = !!draft.templateId
    previewing.value = false
  },
  { immediate: true }
)

/** 换模板时旧覆盖由 PromptL3Form 清空；这里只承接 id，保持四列结构完整 */
function onTemplateId(value: number | null) {
  draft.templateId = value
}

/**
 * 页脚「预览」：委托给子表单，保持与出站同一条 Assembler 路径，不调模型。
 * loading 在弹窗侧维护，避免页脚按钮与子组件内部状态不同步。
 */
async function onPreview() {
  previewing.value = true
  try {
    await formRef.value?.preview()
  } finally {
    previewing.value = false
  }
}

/**
 * 保存：先跑前端能判的硬闸（钉版本、失效段落），再交调用方落库。
 * 段落键合法性（core / 未知段）以后端为准，前端不重复实现一套规则。
 * <p>「已挂模板还残留业务正文」不再是闸门：调用方保存时会把旧正文并入「追加要求」并清空 system_prompt。
 */
function submit() {
  const error = formRef.value?.validate?.() ?? null
  if (error) {
    ElMessage.error(error)
    return
  }
  // 未选模板时前端不拦：后端会钉住平台默认模板；该用途确实没有模板时由后端给出明确失败文案
  submitting.value = true
  emit(
    'save',
    {
      templateId: draft.templateId ?? null,
      templateVersion: draft.templateVersion ?? null,
      override: { ...(draft.override || {}) },
      promptExtra: draft.promptExtra || ''
    },
    (ok: boolean) => {
      submitting.value = false
      if (ok) {
        emit('update:modelValue', false)
      }
    }
  )
}
</script>

<style scoped>
.dialog-lead {
  margin: 0 0 12px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}

.dialog-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 页脚操作区：预览 | 取消 | 保存，右对齐；预览与取消同级，保存为唯一主按钮 */
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.legacy-block {
  padding-top: 12px;
  border-top: 1px dashed var(--ah-border, #eceef4);
}

.block-title {
  margin: 0 0 8px;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 600;
}

/* 旧正文只读预览：等宽 + 限高滚动，长正文不把页脚顶出视口 */
.legacy-text {
  max-height: 240px;
  margin: 0;
  overflow: auto;
  padding: 10px 12px;
  background: #fafbfd;
  border: 1px solid var(--ah-border, #e6e8ef);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #4b5563;
}

.block-hint {
  margin: 6px 0 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}
</style>

<!--
  弹窗被 teleport 到 body，scoped 样式够不到内部结构，故此处用带前缀的全局类名：
  1. 大弹窗尺寸下限 960px（需求：宽 ≥960 或 80vw）；
  2. 内容区独立滚动，长段落不会把页脚顶出视口；
  3. 模板下拉挂到 body 且抬高 z-index，避免被弹窗层级裁切。
-->
<style>
.prompt-l3-dialog {
  min-width: 960px;
  max-width: 1400px;
}

/* 窄屏兜底：min-width 960 会在小屏溢出，此时退化为满宽并允许内部滚动 */
@media (max-width: 1024px) {
  .prompt-l3-dialog {
    min-width: auto;
    width: 96vw !important;
    margin: 0 auto;
  }
}

.prompt-l3-dialog .el-dialog__body {
  max-height: 70vh;
  overflow: auto;
  padding-top: 8px;
}

.l3-select-popper {
  z-index: 5000 !important;
}
</style>
