<template>
  <el-dialog :model-value="modelValue" :title="editing ? '编辑模型' : '新建模型'" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="96px">
      <!--
        平台级实例的「向量空间 / 出站身份」已锁定：供应商、凭证、模型 ID、用途一律只读。
        后端 assertPlatformImmutable 同样强校验，这里禁用以避免用户改完必然保存失败。
      -->
      <el-alert
        v-if="platformLocked"
        type="warning"
        show-icon
        :closable="false"
        class="platform-alert"
        title="该实例已是平台级"
        description="供应商 / 凭证 / 模型 ID / 用途已锁定；如需更换请先取消平台级或走换绑流程。仅名称可改。"
      />
      <el-form-item label="供应商" required>
        <el-select
          v-model="form.providerId"
          style="width: 100%"
          :disabled="platformLocked"
          @change="onProviderChange"
        >
          <el-option v-for="item in providers" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="凭证" required>
        <el-select v-model="form.credentialId" style="width: 100%" :disabled="platformLocked">
          <el-option v-for="item in credentialOptions" :key="item.id" :label="`${item.name}（${item.apiKeyMask}）`" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required><el-input v-model="form.displayName" maxlength="64" /></el-form-item>
      <el-form-item label="模型 ID" required>
        <el-input
          v-model="form.modelId"
          maxlength="128"
          placeholder="qwen-plus / gpt-4o-mini"
          :disabled="platformLocked"
        />
      </el-form-item>
      <el-form-item label="用途" required>
        <el-select
          v-model="form.capability"
          style="width: 100%"
          :disabled="platformLocked"
          @change="onCapabilityChange"
        >
          <el-option-group v-for="group in CAPABILITY_GROUPS" :key="group.key" :label="group.label">
            <el-option
              v-for="item in group.codes"
              :key="item"
              :label="capabilityOptionLabel(item)"
              :value="item"
            />
          </el-option-group>
        </el-select>
      </el-form-item>
      <!-- 启停在列表开关改；扩展 JSON 收敛为温度滑动条 -->
      <el-form-item label="温度">
        <div class="temp-field">
          <div class="temp-row">
            <el-slider
              v-model="form.temperature"
              :min="0"
              :max="2"
              :step="0.1"
              :show-tooltip="false"
              :disabled="temperatureLocked"
              @input="onTemperatureInput"
            />
            <span class="temp-value">{{ form.temperature.toFixed(1) }}</span>
            <!-- 非不采样用途才给恢复入口：显式动作才允许覆盖用户已存温度 -->
            <el-button
              v-if="!temperatureLocked"
              link
              type="primary"
              class="temp-restore"
              @click="restoreSuggestedTemperature"
            >
              恢复建议值
            </el-button>
          </div>
          <!-- 红字提示：不采样用途说明锁 0 的原因，采样用途给出建议区间（仅提示，不拦截） -->
          <p class="temp-hint">{{ temperatureHint }}</p>
        </div>
      </el-form-item>
      <!-- 附加参数：仅 TTS 露出；切走用途只隐藏，已填内容仍随 extraJson 保存，不丢 -->
      <el-form-item v-if="isTts" label="附加参数">
        <div class="speech-params-field">
          <el-input
            v-model="form.speechParamsText"
            type="textarea"
            :rows="4"
            spellcheck="false"
            class="speech-params-input"
            placeholder='{"voice":"longanhuan_v3.1"}'
          />
          <p class="speech-params-hint">
            按「当前协议」官方文档填写可选合成参数（音色、语速、instruction 等），键名原样发送，平台不做转换。
            留空走协议默认：DashScope 原生按模型族给默认音色，DashScope 兼容口默认 Cherry，OpenAI 兼容默认 alloy。
          </p>
          <!-- 实时提示：只在用户正在编辑且不是合法对象时出现，避免一打开弹窗就红字 -->
          <p v-if="speechParamsError" class="speech-params-error">{{ speechParamsError }}</p>
        </div>
      </el-form-item>
      <!-- 视觉输出上限：选填，写入 extraJson.max_tokens；留空即用后端默认 1024 -->
      <el-form-item v-if="isVision" label="输出上限">
        <div class="max-tokens-field">
          <el-input-number
            v-model="form.maxTokens"
            :min="1"
            :step="256"
            :controls="false"
            placeholder="留空默认 1024"
            style="width: 160px"
          />
          <span class="max-tokens-unit">tokens</span>
        </div>
        <p class="max-tokens-hint">
          留空时按 1024 出站。部分视觉模型的 max_tokens 上限就是 1024，填更大值会被上游拒绝并返回 400；
          确有需要可手填更大值，保存后原样发出（平台不钳制）。
        </p>
      </el-form-item>
    </el-form>
    <template #footer>
      <!-- 编辑态未改任何字段时取消 / 保存都不可点；新建态恒可点 -->
      <el-button :disabled="!canSubmit" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 实例新建 / 编辑。所属域：model。
 * 凭证下拉只提供当前供应商下启用的 Key；编辑时保留已绑定的那条，避免打开弹窗就丢选项。
 * 温度写入 extraJson.temperature，默认值与建议区间按用途取 {@link temperatureProfileOf}：
 * - 不采样用途（EMBEDDING / RERANK / ASR / TTS / GUARDRAIL）：温度锁 0、滑块禁用、红字说明；
 * - 新建或切换用途且用户未手改温度时，套该用途默认值；编辑回填的已存温度不被静默覆盖，
 *   只有点「恢复建议值」才显式套默认。
 * VISION 额外可填 max_tokens（写入 extraJson.max_tokens，留空即后端默认 1024）：
 * 部分视觉模型输出上限就是 1024，填更大值会被上游 400；平台按填的值原样发出，不做钳制。
 * TTS 额外可填「附加参数」JSON 对象（写入 extraJson.speechParams）：
 * 前端只校验 JSON 对象 / 体积 / 控制字符 / 凭证与系统保留键，不解释键名，
 * 出站由后端浅合并进该协议 TTS 报文的可选参数位置。
 * 启停不在弹窗里改。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useObjectDirty } from '@/composables/useFormDirty'
import type { CredentialRow, InstanceRow, ProviderCard } from '@/types/model'
import {
  CAPABILITY_GROUPS,
  capabilityOptionLabel,
  normalizeCapability,
  temperatureProfileOf
} from '@/types/model'

const props = defineProps<{
  modelValue: boolean
  providers: ProviderCard[]
  editing: InstanceRow | null
  presetProviderId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: Record<string, unknown>, done: () => void]
}>()

const saving = ref(false)
/** 编辑时保留原 extraJson 其它字段，只改 temperature */
const previousExtraJson = ref('')
/** 用户是否手改过温度；未脏改时切换用途才允许自动套新用途默认值 */
const temperatureDirty = ref(false)

const form = reactive({
  providerId: undefined as number | undefined,
  credentialId: undefined as number | undefined,
  displayName: '',
  modelId: '',
  capability: 'CHAT',
  temperature: temperatureProfileOf('CHAT').defaultTemperature,
  /** 视觉用途的输出上限；null 表示未配置，后端按 1024 默认出站 */
  maxTokens: null as number | null,
  /** TTS 附加参数 JSON 原文；只隐藏不丢弃，切走用途再保存同样原样带回 */
  speechParamsText: ''
})

/** 附加参数体积上限，与后端 SpeechParams.MAX_BYTES 同值 */
const SPEECH_PARAMS_MAX_BYTES = 8 * 1024

/** 凭证类键：比较前统一去 - _ 空格并转小写，命中即拒绝保存 */
const SPEECH_PARAMS_CREDENTIAL_KEYS = new Set([
  'apikey', 'xapikey', 'authorization', 'auth', 'token', 'accesstoken', 'refreshtoken',
  'secret', 'clientsecret', 'appsecret', 'accesskey', 'accesskeyid', 'accesskeysecret',
  'password', 'passwd', 'credential', 'credentials', 'bearer'
])

/** 系统保留键：模型名 / 待朗读文本 / 音频二进制一律以平台值为准 */
const SPEECH_PARAMS_RESERVED_KEYS = new Set(['model', 'input', 'file', 'text'])

/**
 * 弹窗内可编辑字段的脏检查。温度滑块是「选择类」输入，纳入快照；
 * 启停不在弹窗里改，因此不在 form 中。
 */
const { isDirty, markClean } = useObjectDirty(() => form)

/** 取消 / 保存是否可点：新建态恒可点，编辑态要求有改动 */
const canSubmit = computed(() => !props.editing || isDirty.value || saving.value)

/** 当前用途的温度策略（含是否采样、默认值、建议区间） */
const temperatureProfile = computed(() => temperatureProfileOf(form.capability))

/** 不采样用途禁用控件并恒为 0 */
const temperatureLocked = computed(() => !temperatureProfile.value.sampling)

/** 是否视觉用途：输出上限说明只在 VISION 展示，避免让 CHAT 用户误以为也受 1024 限制 */
const isVision = computed(() => normalizeCapability(form.capability) === 'VISION')

/** 是否语音合成用途：附加参数只给 TTS 露出，其它用途隐藏但内容保留在 form 里 */
const isTts = computed(() => normalizeCapability(form.capability) === 'TTS')

/**
 * 编辑的是否为平台级实例：供应商 / 凭证 / 模型 ID / 用途只读。
 * 这几个字段决定「向量空间与出站身份」，后端 assertPlatformImmutable 强校验，
 * 前端禁用以避免用户改完必然被拒；更换请走取消平台级或换绑流程。
 */
const platformLocked = computed(() => props.editing?.platformLocked === 1)

/** 附加参数的实时错误；仅用于提示，提交时还会再校验一次 */
const speechParamsError = ref('')

/** 红字提示：锁定用途说明原因，采样用途给建议区间 */
const temperatureHint = computed(() => {
  const profile = temperatureProfile.value
  if (!profile.sampling) {
    return '该用途不使用温度'
  }
  return `建议区间 ${profile.suggestedMin.toFixed(1)}～${profile.suggestedMax.toFixed(1)}，超出仍可保存`
})

const credentialOptions = computed(() => {
  const provider = props.providers.find((item) => item.id === form.providerId)
  const rows = provider?.credentials || []
  return rows.filter((item) => item.status === 1 || item.id === form.credentialId)
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    const current = props.editing
    form.providerId = current?.providerId || props.presetProviderId
    form.credentialId = current?.credentialId
    form.displayName = current?.displayName || ''
    form.modelId = current?.modelId || ''
    form.capability = normalizeCapability(current?.capability) || 'CHAT'
    previousExtraJson.value = current?.extraJson || ''
    // 编辑：回填已存温度，并预置「不自动套默认」——用户已存的选择不得被切用途静默覆盖，
    //       要套默认只能显式点「恢复建议值」。
    // 新建：套当前用途默认值，且未脏改时切用途自动跟随。
    form.temperature = current
      ? parseTemperature(current.extraJson, form.capability)
      : temperatureProfileOf(form.capability).defaultTemperature
    // 输出上限只对视觉用途有意义；切到其它用途时置空，避免把视觉专用值带进 CHAT 实例
    form.maxTokens = current ? parseMaxTokens(current.extraJson) : null
    // 附加参数回填：只取 speechParams 子对象并按缩进展开，便于继续手改
    form.speechParamsText = current ? formatSpeechParams(current.extraJson) : ''
    speechParamsError.value = ''
    temperatureDirty.value = Boolean(current)
    if (!current) {
      pickFirstCredential()
    }
    // 回填完成即定为干净基线：打开弹窗不该显示可保存
    markClean()
  }
)

/**
 * 从 extraJson 读温度；非法或缺省时用该用途的默认值（而非固定 0.7）。
 *
 * @param extraJson  库里的扩展 JSON
 * @param capability 用途码，决定缺省默认值
 * @return 0～2 的温度
 */
function parseTemperature(extraJson: string | undefined, capability: string) {
  const fallback = temperatureProfileOf(capability)
  if (!extraJson?.trim()) {
    return fallback.defaultTemperature
  }
  try {
    const obj = JSON.parse(extraJson) as { temperature?: unknown }
    const raw = obj.temperature
    if (typeof raw === 'number' && Number.isFinite(raw)) {
      return Math.min(2, Math.max(0, Math.round(raw * 10) / 10))
    }
  } catch {
    // 忽略坏 JSON，走默认
  }
  return fallback.defaultTemperature
}

/**
 * 用途变更：不采样用途立即锁 0；采样用途仅在用户未手改温度时套新默认值。
 * 编辑已有实例时若用户已手改，切换用途不覆盖其选择（需显式点「恢复建议值」）。
 */
function onCapabilityChange() {
  const profile = temperatureProfileOf(form.capability)
  // 输出上限是视觉专用字段：切到其它用途时清空，避免把 VISION 的值悄悄发给 CHAT
  if (normalizeCapability(form.capability) !== 'VISION') {
    form.maxTokens = null
  }
  if (!profile.sampling) {
    // 不采样用途没有「用户已选温度」可言，直接锁 0
    form.temperature = 0
    temperatureDirty.value = false
    return
  }
  if (!temperatureDirty.value) {
    form.temperature = profile.defaultTemperature
  }
}

/** 标记用户已手改温度，避免切换用途时被静默覆盖 */
function onTemperatureInput() {
  temperatureDirty.value = true
}

/** 显式「恢复建议值」：套当前用途默认值并清掉脏标记 */
function restoreSuggestedTemperature() {
  form.temperature = temperatureProfileOf(form.capability).defaultTemperature
  temperatureDirty.value = false
}

/**
 * 从 extraJson 读视觉输出上限。
 *
 * <p>两种键名都认（`max_tokens` 是 OpenAI 风格、`maxTokens` 兼容手填），
 * 只接受正整数：0、负数、非数字一律视为「未配置」，由后端走默认值。
 *
 * @param extraJson 库里的扩展 JSON
 * @return 正整数或 null
 */
function parseMaxTokens(extraJson: string | undefined): number | null {
  if (!extraJson?.trim()) {
    return null
  }
  try {
    const obj = JSON.parse(extraJson) as { max_tokens?: unknown; maxTokens?: unknown }
    for (const raw of [obj.max_tokens, obj.maxTokens]) {
      const value = typeof raw === 'number' ? raw : Number(raw)
      if (Number.isFinite(value) && value >= 1) {
        return Math.floor(value)
      }
    }
  } catch {
    // 忽略坏 JSON：后端同样按未配置处理
  }
  return null
}

/**
 * 附加参数实时校验：空框不报错（等价「未配置」）；有内容才提示，避免一打字就整片飘红。
 */
watch(
  () => form.speechParamsText,
  (text) => {
    speechParamsError.value = text.trim() ? validateSpeechParams(text) : ''
  }
)

/**
 * 从 extraJson 读 TTS 附加参数并展开成可编辑文本。
 *
 * <p>只读 speechParams 子对象；缺键 / 非对象 / 坏 JSON 一律返回空串（等价「未配置」），
 * 让用户看到空框而不是一段无法解析的垃圾文本。
 *
 * @param extraJson 库里的扩展 JSON
 * @return 缩进后的 JSON 文本；未配置为空串
 */
function formatSpeechParams(extraJson: string | undefined): string {
  if (!extraJson?.trim()) {
    return ''
  }
  try {
    const parsed = JSON.parse(extraJson) as { speechParams?: unknown }
    const value = parsed?.speechParams
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return JSON.stringify(value, null, 2)
    }
  } catch {
    // 忽略坏 JSON：后端保存前也会再校验
  }
  return ''
}

/**
 * 统一键名归一：去掉 - _ 空格并转小写，用于凭证 / 保留键比对。
 *
 * @param key 用户写的键名
 * @return 归一后的键名
 */
function normalizeSpeechKey(key: string): string {
  return key.toLowerCase().replace(/[-_\s]/g, '')
}

/**
 * 校验附加参数文本。空串合法（走协议默认）；非空必须是 JSON 对象。
 * 与后端规则对齐：≤8KB、无控制字符、不含凭证类与系统保留键。
 *
 * @param text 输入框原文
 * @return 中文错误文案；合法返回空串
 */
function validateSpeechParams(text: string): string {
  const raw = text.trim()
  if (!raw) {
    return ''
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return '附加参数不是合法 JSON，请检查括号、引号与逗号'
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return '附加参数必须是 JSON 对象，例如 {"voice":"longanhuan_v3.1"}'
  }
  // 体积按 UTF-8 字节算：中文参数容易「看着不长、实际超限」
  const bytes = new TextEncoder().encode(JSON.stringify(parsed)).length
  if (bytes > SPEECH_PARAMS_MAX_BYTES) {
    return `附加参数最长 ${SPEECH_PARAMS_MAX_BYTES / 1024}KB，当前 ${bytes} 字节`
  }
  // 控制字符：只放过换行 / 回车 / 制表符，其余（含 NUL、退格、ESC）一律拒绝
  if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(raw)) {
    return '附加参数含非法控制字符，请检查后重填'
  }
  const blocked = findBlockedSpeechKey(parsed as Record<string, unknown>)
  if (blocked) {
    return blocked
  }
  return ''
}

/**
 * 递归查凭证类 / 系统保留键，返回第一条中文提示。
 *
 * @param node 附加参数子树
 * @return 提示文案；无违规为空串
 */
function findBlockedSpeechKey(node: unknown): string {
  if (Array.isArray(node)) {
    for (const item of node) {
      const hit = findBlockedSpeechKey(item)
      if (hit) return hit
    }
    return ''
  }
  if (!node || typeof node !== 'object') {
    return ''
  }
  for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
    const normalized = normalizeSpeechKey(key)
    if (SPEECH_PARAMS_CREDENTIAL_KEYS.has(normalized)) {
      return `附加参数不能包含凭证类键「${key}」，凭证由平台按实例注入`
    }
    if (SPEECH_PARAMS_RESERVED_KEYS.has(normalized)) {
      return `附加参数不能包含系统保留键「${key}」，模型名与待朗读文本以实例配置为准`
    }
    const hit = findBlockedSpeechKey(value)
    if (hit) return hit
  }
  return ''
}

/**
 * 写入 temperature / max_tokens / speechParams，保留原 JSON 其它键。
 *
 * @param temperature   滑动条值
 * @param maxTokens     视觉输出上限；null 表示删除该键，后端按默认 1024 出站
 * @param speechParams  附加参数原文；空串表示删除该键（走协议默认）
 * @param previous      编辑前的 extraJson
 * @return 可提交的 JSON 字符串
 */
function buildExtraJson(
  temperature: number,
  maxTokens: number | null,
  speechParams: string,
  previous: string
) {
  let base: Record<string, unknown> = {}
  if (previous.trim()) {
    try {
      const parsed = JSON.parse(previous) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        base = { ...(parsed as Record<string, unknown>) }
      }
    } catch {
      base = {}
    }
  }
  base.temperature = temperature
  // 两种键名统一收口到 max_tokens，避免库里同一语义出现两个字段
  delete base.maxTokens
  if (maxTokens == null) {
    delete base.max_tokens
  } else {
    base.max_tokens = maxTokens
  }
  // 附加参数按对象存；清空即删键，后端按「未配置」处理而不是拿到空对象
  const paramsText = speechParams.trim()
  if (paramsText) {
    base.speechParams = JSON.parse(paramsText) as Record<string, unknown>
  } else {
    delete base.speechParams
  }
  return JSON.stringify(base)
}

/** 换供应商后清掉不属于它的凭证，改选第一条启用凭证。 */
function onProviderChange() {
  form.credentialId = undefined
  pickFirstCredential()
}

function pickFirstCredential() {
  const first = credentialOptions.value.find((item: CredentialRow) => item.status === 1)
  form.credentialId = first?.id
}

function submit() {
  if (!form.providerId || !form.credentialId || !form.displayName.trim() || !form.modelId.trim()) {
    ElMessage.warning('请完整填写供应商、凭证、名称和模型 ID')
    return
  }
  // 附加参数与用途无关地校验：切走 TTS 后内容仍会保存，不能借「已隐藏」绕过安全校验
  const paramsError = validateSpeechParams(form.speechParamsText)
  speechParamsError.value = paramsError
  if (paramsError) {
    ElMessage.error(paramsError)
    return
  }
  saving.value = true
  // 不采样用途提交 0：后端也会再夹一次，这里先保证前端不发「禁用控件残留值」
  const temperature = temperatureLocked.value ? 0 : form.temperature
  const payload: Record<string, unknown> = {
    providerId: form.providerId,
    credentialId: form.credentialId,
    displayName: form.displayName.trim(),
    modelId: form.modelId.trim(),
    capability: form.capability,
    extraJson: buildExtraJson(
      temperature,
      isVision.value ? form.maxTokens : null,
      form.speechParamsText,
      previousExtraJson.value
    )
  }
  // 编辑带上当前启停，避免后端把空 status 当成默认启用；新建不传，后端默认启用
  if (props.editing) {
    payload.status = props.editing.status
  }
  emit('submit', payload, () => {
    saving.value = false
  })
}
</script>

<style scoped>
/* 平台级只读提示：紧贴表单顶部，说明为何四项禁用，避免用户以为是权限问题 */
.platform-alert {
  margin-bottom: 12px;
}

.temp-field {
  width: 100%;
}

.temp-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.temp-row .el-slider {
  flex: 1;
  margin: 0 8px 0 0;
}

.temp-value {
  flex: none;
  min-width: 36px;
  color: var(--ah-text, #1f2430);
  font-variant-numeric: tabular-nums;
  text-align: right;
}

/* 恢复建议值：次要动作用链接样式，不抢保存按钮的视觉权重 */
.temp-restore {
  flex: none;
  padding: 0;
  font-size: 12px;
}

/* 红字提示：建议区间 / 该用途不使用温度；始终占一行，避免切换用途时表单高度跳动 */
.temp-hint {
  margin: 2px 0 0;
  color: #ef4444;
  font-size: 12px;
  line-height: 18px;
}

/* 视觉输出上限说明：灰字，与红字告警区分开（这是提示而非错误） */
.max-tokens-hint {
  margin: 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

/* 输出上限输入行：数字框 + 单位，与温度那一行的排版节奏一致 */
.max-tokens-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.max-tokens-unit {
  color: #6b7280;
  font-size: 12px;
}

/* 附加参数：整块占满表单宽度，textarea 用等宽字体便于看括号与引号 */
.speech-params-field {
  width: 100%;
}

.speech-params-input :deep(textarea) {
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
  line-height: 18px;
}

/* 灰字说明与红字错误分成两行：说明常驻，错误只在非法时出现 */
.speech-params-hint {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

.speech-params-error {
  margin: 2px 0 0;
  color: #ef4444;
  font-size: 12px;
  line-height: 18px;
}
</style>
