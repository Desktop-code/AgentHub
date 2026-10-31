<template>
  <!--
    CHAT（agent_def）提示词摘要行 + 统一大弹窗入口。所属域：agent。
    页内不再内嵌旧「自由大文本 + 右侧平台约束」双栏，也不内嵌整套 L3 表单：
    这里只给一行摘要（模板名 · 版本 / 未配置）与「配置提示词」，编辑态一律进 PromptL3Dialog。
  -->
  <div class="prompt-entry">
    <!-- Router 模式：agent_def 只承载 CHAT，提示词挂在 ROUTER 用途槽，这里只说明去处 -->
    <el-alert v-if="isRouterMode" type="info" :closable="false" show-icon class="router-note">
      <template #title>
        Router 模式的提示词请在「模型配置」Tab 的 ROUTER 用途槽点「配置提示词」：
        Agent 定义这里只承载 CHAT 模板，Router 判路由走 ROUTER 用途模板。
      </template>
      <div v-if="props.prompt?.templateId" class="router-cleanup">
        检测到历史 CHAT 模板配置（id={{ props.prompt.templateId }} v{{ props.prompt.templateVersion }}），
        该模式下不会生效：
        <el-button type="danger" link :disabled="disabled" @click="clearPromptBinding">清空该配置</el-button>
      </div>
    </el-alert>

    <template v-else>
      <div class="entry-row">
        <div class="entry-main">
          <span class="entry-label">{{ bound ? boundLabel : '未配置提示词模板' }}</span>
          <el-tag v-if="bound" size="small" effect="plain" type="success" class="entry-tag">已钉版本</el-tag>
          <span v-else-if="legacyLength" class="entry-note">
            当前使用业务提示词正文（{{ legacyLength }} 字）
          </span>
        </div>
        <el-button type="primary" plain size="small" :disabled="disabled" @click="openDialog">
          配置提示词
        </el-button>
      </div>
      <p class="entry-hint">
        模板锁定段由平台维护；可覆盖段落与追加要求在大弹窗中编辑，保存前可预览最终注入内容。
      </p>
    </template>

    <!-- 统一大弹窗：与非 CHAT 用途（模型槽 / 记忆页）同一个组件、同一套交互 -->
    <PromptL3Dialog
      v-model="dialogOpen"
      purpose="CHAT"
      :binding="props.prompt"
      :legacy-prompt="modelValue"
      :locale="locale"
      :agent-name="agentName"
      :disabled="disabled"
      @update:legacy-prompt="(value) => emit('update:modelValue', value)"
      @save="onSave"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * CHAT 提示词入口（摘要 + 弹窗）。所属域：agent。
 * <p>
 * 为什么改成「摘要 + 弹窗」：旧实现把整套 L3 表单内嵌在基本信息里，选完模板后一旦
 * 上层回传的四列丢了 supported 字段，视图就会判定为「不支持 L3」而整块塌回
 * 「左自由大文本 + 右平台约束」的旧双栏。现在页面只有一行摘要，编辑态固定在弹窗内，
 * 弹窗按 purpose 自取段落，不存在「塌回」的状态分支。
 * <p>
 * 数据边界：本组件不直接调写接口，四列与业务提示词都通过 v-model / emit 交回父级表单，
 * 由父级随基本信息一起保存（取消时整表回滚）。
 */
import { computed, ref, watch } from 'vue'
import PromptL3Dialog from '@/components/agent/PromptL3Dialog.vue'
import { usePromptTemplateCatalog } from '@/composables/promptTemplateCatalog'
import type { PromptBinding } from '@/types/agent'
import { checkUserPrompt } from '@/utils/promptSafety'

const props = defineProps<{
  /** CHAT 的历史业务提示词（system_prompt）；未挂模板时生效 */
  modelValue?: string
  /** L3 四列 */
  prompt?: PromptBinding
  /** 语言；默认 zh-CN */
  locale?: string
  agentName: string
  /** 决定是否走 Router 提示：Router 模式的 CHAT 模板不生效 */
  runMode?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:prompt': [value: PromptBinding]
}>()

const dialogOpen = ref(false)
const { ensureLoaded, promptTemplateName } = usePromptTemplateCatalog()

/** Router 模式：提示词挂在 ROUTER 用途槽，本区不提供 CHAT 配置 */
const isRouterMode = computed(() => props.runMode === 'ROUTER')
/** 是否已挂模板；仅 templateId 非空才算绑定 */
const bound = computed(() => !!props.prompt?.templateId)
const legacyLength = computed(() => (props.modelValue || '').trim().length)

/** 摘要文案：有名称用名称，拉取失败时退化为 id，避免出现空白行 */
const boundLabel = computed(() => {
  const version = props.prompt?.templateVersion
  const name = promptTemplateName(props.prompt?.templateId) || `模板 #${props.prompt?.templateId}`
  return version ? `${name} · v${version}` : name
})

// 绑定变化（含外部回填、切 Agent）时确保名称已缓存，摘要不会显示上一个模板的名字
watch(
  () => props.prompt?.templateId,
  async (id) => {
    if (!id) {
      return
    }
    await ensureLoaded('CHAT', props.locale || 'zh-CN')
  },
  { immediate: true }
)

/** 打开大弹窗；弹窗内部自己读段落与预览 */
function openDialog() {
  if (props.disabled) {
    return
  }
  dialogOpen.value = true
}

/**
 * 弹窗保存：四列整份回传父级表单（含 supported，避免上层归一化时丢能力标记）。
 *
 * @param payload 弹窗内编辑后的四列
 * @param done    回传结果；本组件同步写回父级，故总是成功
 */
function onSave(payload: PromptBinding, done: (ok: boolean) => void) {
  emit('update:prompt', {
    supported: props.prompt?.supported !== false,
    templateId: payload.templateId ?? null,
    templateVersion: payload.templateVersion ?? null,
    override: payload.override || {},
    promptExtra: payload.promptExtra || ''
  })
  done(true)
}

/**
 * 清空历史 CHAT 模板配置（Router 模式下不会生效，留着只会误导）。
 * 传空四列即「不挂模板」，后端按 templateId 为空处理。
 */
function clearPromptBinding() {
  emit('update:prompt', {
    supported: props.prompt?.supported ?? false,
    templateId: null,
    templateVersion: null,
    override: {},
    promptExtra: ''
  })
}

/**
 * 保存前由父级调用：返回阻止保存的原因。
 * 旧实现会校验内嵌表单，现在编辑态在弹窗内且弹窗自带保存闸，
 * 页面保存只需校验「已绑模板却残留旧正文」这一跨区约束（后端也会拒）。
 *
 * @return 校验报告，格式与父级期望一致
 */
function reveal() {
  const errors: string[] = []
  const warnings: string[] = []
  if (isRouterMode.value) {
    return { errors, warnings }
  }
  if (bound.value) {
    // 绑模板后旧正文必须迁移：后端也会拒，这里提前说清该点哪里
    if (legacyLength.value > 0) {
      errors.push('已绑定模板：请先把「系统提示词」正文迁移到追加要求，或丢弃旧正文后再保存')
    }
    return { errors, warnings }
  }
  // 未绑模板：业务正文仍按既有安全规则校验（长度、疑似密钥、外连诱导）
  return checkUserPrompt(props.modelValue || '')
}

defineExpose({ reveal })
</script>

<style scoped>
.prompt-entry {
  width: 100%;
}

.entry-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  background: #fafbfd;
  border: 1px solid var(--ah-border, #e6e8ef);
  border-radius: 6px;
}

.entry-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.entry-label {
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-tag {
  flex: none;
}

.entry-note {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.entry-hint {
  margin: 6px 0 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

/* Router 模式说明：与摘要行同区，不抢视觉 */
.router-note {
  margin-bottom: 0;
}

.router-cleanup {
  margin-top: 6px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}
</style>
