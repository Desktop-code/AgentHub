<template>
  <el-dialog :model-value="modelValue" :title="editing ? '编辑模型' : '新建模型'" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="96px">
      <el-form-item label="供应商" required>
        <el-select v-model="form.providerId" style="width: 100%" @change="onProviderChange">
          <el-option v-for="item in providers" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="凭证" required>
        <el-select v-model="form.credentialId" style="width: 100%">
          <el-option v-for="item in credentialOptions" :key="item.id" :label="`${item.name}（${item.apiKeyMask}）`" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required><el-input v-model="form.displayName" maxlength="64" /></el-form-item>
      <el-form-item label="模型 ID" required><el-input v-model="form.modelId" maxlength="128" placeholder="qwen-plus / gpt-4o-mini" /></el-form-item>
      <el-form-item label="用途" required>
        <el-select v-model="form.capability" style="width: 100%" @change="onCapabilityChange">
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
  maxTokens: null as number | null
})

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
 * 写入 temperature（及视觉用途的 max_tokens），保留原 JSON 其它键。
 *
 * @param temperature 滑动条值
 * @param maxTokens   视觉输出上限；null 表示删除该键，后端按默认 1024 出站
 * @param previous    编辑前的 extraJson
 * @return 可提交的 JSON 字符串
 */
function buildExtraJson(temperature: number, maxTokens: number | null, previous: string) {
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
  saving.value = true
  // 不采样用途提交 0：后端也会再夹一次，这里先保证前端不发「禁用控件残留值」
  const temperature = temperatureLocked.value ? 0 : form.temperature
  const payload: Record<string, unknown> = {
    providerId: form.providerId,
    credentialId: form.credentialId,
    displayName: form.displayName.trim(),
    modelId: form.modelId.trim(),
    capability: form.capability,
    extraJson: buildExtraJson(temperature, isVision.value ? form.maxTokens : null, previousExtraJson.value)
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
</style>
