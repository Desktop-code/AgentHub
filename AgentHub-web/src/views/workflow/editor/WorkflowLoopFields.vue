<template>
  <el-form-item label="最大次数" required>
    <el-input-number
      :model-value="maxIterations"
      class="wf-field"
      :min="1"
      :max="cap"
      :step="1"
      :precision="0"
      controls-position="right"
      :disabled="disabled"
      @update:model-value="onMax"
    />
  </el-form-item>
  <div class="wf-tip">建议 3 次。平台硬顶 {{ cap }} 次，图上不能写得更大。</div>
  <el-form-item label="超限出路" required>
    <el-select
      :model-value="onExceeded"
      class="wf-field"
      placeholder="必须选择"
      :disabled="disabled"
      @update:model-value="onOutcome"
    >
      <el-option label="下游继续" value="DOWNSTREAM" />
      <el-option label="转人工" value="HUMAN" />
      <el-option label="失败" value="FAIL" />
    </el-select>
  </el-form-item>

  <el-form-item label="打回反馈">
    <!-- 没有打回边时不摆一个空下拉，先告诉用户缺哪条边 -->
    <div v-if="!feedbackSources.length" class="wf-feedback">
      <div class="wf-tip">还没有打回边。先从循环体拉一条「回到循环」到本节点，再选意见来源。</div>
    </div>
    <!-- 与映射同款的点选：选意见从哪个节点来，必要时再选字段 -->
    <div v-else class="wf-feedback">
      <MappingPathPicker
        :sources="pickerSources"
        :source-value="mode === 'manual' ? '' : sourceCode"
        :field-value="fieldValue"
        :mode="mode"
        :manual-value="manualText"
        :path-text="pathText"
        :disabled="disabled"
        allow-manual
        allow-path-edit
        :field-label="mode === 'manual' ? '路径' : '字段'"
        source-placeholder="选择意见来源节点"
        field-placeholder="选择反馈字段（留空取整段产出）"
        manual-placeholder="如 $.outputs.check；历史写法原样保留"
        empty-text="（未选择意见来源）"
        :attention="attention"
        @update:source-value="onSourceChange"
        @update:field-value="onFieldChange"
        @update:manual-value="onManualInput"
        @update:mode="onModeChange"
        @update:path-value="onPathChange"
      />
      <!-- 多条打回边：不猜来源，把候选高亮出来让用户拍板 -->
      <div v-if="attention" class="wf-tip wf-tip--error">
        有 {{ feedbackSources.length }} 条打回边，请选择意见从哪个节点来。
      </div>
    </div>
  </el-form-item>

  <div class="wf-tip">
    从本节点拉一条「进入循环体」到要重复的第一步，再从最后一步拉「回到循环」指回本节点。
    下游继续或转人工还要拉一条「次数用尽」；失败出路不要这条边。
    取消、节点失败和时限到期按失败或取消结束，不走超限出路。
  </div>
</template>

<script setup lang="ts">
/**
 * 循环节点的次数、超限出路和打回反馈。所属域：workflow 的画布属性。
 *
 * <p>只改当前节点的 config，不在这里连边。次数上限来自草稿接口下发的平台硬顶，
 * 页面上的数字不能超过它；保存时后端还会再校验一次。
 * <p>打回反馈不再用裸输入框：来源只能从指向本节点的 LOOP_BACK 起点里选，
 * 落库仍是 {@code config.feedbackPath}（{@code $.outputs.&lt;code&gt;(&lt;field&gt;)}），
 * 运行时写入 {@code vars.loop.feedback} 的行为不变。
 * <p>路径默认收在「高级」里，点选时只看来源与字段；要改路径时展开高级或切到手写。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MappingPathPicker from './MappingPathPicker.vue'
import {
  loopFeedbackPath,
  parseLoopFeedbackPath
} from '@/utils/workflowMapping'
import type { MappingPickerSource } from '@/utils/workflowMapping'
import type { WorkflowMappingSource } from '@/types/workflow'

const props = defineProps<{
  /** 图上填写的最大次数；空表示还没配 */
  maxIterations: number | null
  /** DOWNSTREAM / HUMAN / FAIL */
  onExceeded: string
  /** 打回时写入上下文的路径，可空 */
  feedbackPath: string
  /** 指向本循环节点的 LOOP_BACK 起点（已按画布节点整理） */
  feedbackSources: WorkflowMappingSource[]
  /** 平台硬顶，输入框不能超过它 */
  cap: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:maxIterations': [value: number | null]
  'update:onExceeded': [value: string]
  'update:feedbackPath': [value: string]
}>()

/** 打回反馈的 UI 模式：点选 / 手写历史路径 */
const mode = ref<'pick' | 'manual'>('pick')

/** 手写模式的本地文本；切回点选时用它反解来源 */
const manualText = ref('')

/** 当前 feedbackPath 的解析结果：来源编码 + 字段，手写时归到 manual */
const parsed = computed(() => parseLoopFeedbackPath(props.feedbackPath))

/** 点选模式下当前的来源节点编码 */
const sourceCode = computed(() => (parsed.value.manual ? '' : parsed.value.sourceCode))

/** 点选模式下当前的来源字段；空串表示整段产出 */
const fieldValue = computed(() => (parsed.value.manual ? '' : parsed.value.field))

/**
 * 打回来源下拉：全部用节点名做人话标签，字段只显示字段名。
 * <p>若配置里指向的节点已经不在打回边中（边被改过 / 删过），补一条「不在打回边中」，
 * 让用户先看见现状，而不是静默显示空白。
 */
const pickerSources = computed<MappingPickerSource[]>(() => {
  const options: MappingPickerSource[] = props.feedbackSources.map((source) => {
    const keys = source.outputKeys.length ? source.outputKeys : ['text']
    return {
      value: source.code,
      label: source.name || source.code,
      hint: `$.outputs.${source.code}`,
      wholeLabel: '整段产出',
      allowCustomField: true,
      fields: keys.map((key) => ({
        value: key,
        label: key,
        hint: `$.outputs.${source.code}.${key}`
      }))
    }
  })
  const current = sourceCode.value
  if (current && !options.some((item) => item.value === current)) {
    options.push({
      value: current,
      label: `${current}（不在打回边中）`,
      hint: `$.outputs.${current}`,
      wholeLabel: '整段产出',
      allowCustomField: true,
      fields: []
    })
  }
  return options
})

/** 多条打回边且还没选来源：高亮来源下拉，提示需要用户拍板 */
const attention = computed(
  () => props.feedbackSources.length > 1 && !sourceCode.value && mode.value !== 'manual'
)

/** 回路预览：手写模式看原文，点选模式看拼出来的路径 */
const pathText = computed(() => (mode.value === 'manual' ? manualText.value : props.feedbackPath))

/**
 * 外部回填：父层预填、切换节点、载入手稿时同步模式与手写文本。
 * <p>本组件挂载时由父层用 :key 绑定节点 id，不会把上一节点的模式带到下一节点。
 */
watch(
  () => props.feedbackPath,
  (raw) => {
    const next = parseLoopFeedbackPath(raw)
    if (next.manual) {
      mode.value = 'manual'
      manualText.value = next.text
      return
    }
    // 已经是手写模式时不要因为输入恰好拼成合法路径就强行切走，避免光标/焦点被抢
    if (mode.value !== 'manual') {
      manualText.value = raw
    }
  },
  { immediate: true }
)

/** 次数只接受正整数；清空留空，交给保存校验拒绝。 */
function onMax(value: number | undefined) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    emit('update:maxIterations', null)
    return
  }
  emit('update:maxIterations', Math.min(props.cap, Math.max(1, Math.trunc(value))))
}

/** 超限出路三选一，空字符串表示用户清掉了。 */
function onOutcome(value: string) {
  emit('update:onExceeded', value || '')
}

/**
 * 切换意见来源：换节点必须清空字段，回落到该节点的整段产出。
 * <p>路径由 {@link loopFeedbackPath} 拼，配置键与后端解析都不变。
 *
 * @param value 来源节点编码；空串表示清空
 */
function onSourceChange(value: string) {
  const code = String(value ?? '')
  mode.value = 'pick'
  emit('update:feedbackPath', code ? loopFeedbackPath(code, '') : '')
}

/** 选反馈字段：来源必须是已选的打回节点，否则不拼路径 */
function onFieldChange(value: string) {
  if (!sourceCode.value) {
    return
  }
  emit('update:feedbackPath', loopFeedbackPath(sourceCode.value, String(value ?? '')))
}

/** 手写路径：原文上抛，保留历史写法与高级用法 */
function onManualInput(value: string) {
  manualText.value = String(value ?? '')
  emit('update:feedbackPath', manualText.value)
}

/**
 * 点选 / 手写来回切。
 * <p>切回点选时只有能归一到 {@code $.outputs.*} 才允许，否则留在手写，避免猜错来源。
 *
 * @param next 目标模式
 */
function onModeChange(next: 'pick' | 'manual') {
  if (next === 'manual') {
    manualText.value = props.feedbackPath || ''
    mode.value = 'manual'
    return
  }
  const parsedValue = parseLoopFeedbackPath(manualText.value)
  if (parsedValue.manual) {
    ElMessage.warning('当前是手写路径，无法自动对应到打回来源；请先选择来源。')
    return
  }
  mode.value = 'pick'
  emit('update:feedbackPath', loopFeedbackPath(parsedValue.sourceCode, parsedValue.field))
}

/**
 * 高级面板里直接改反馈路径。
 * <p>能归一成 {@code $.outputs.<code>(.<field>)} 就回到点选；历史 / 高级写法留到手写原文，
 * 清空则回到「未选择意见来源」，不猜来源节点。
 *
 * @param value 高级面板里输入的路径
 */
function onPathChange(value: string) {
  const text = String(value ?? '')
  if (!text.trim()) {
    mode.value = 'pick'
    manualText.value = ''
    emit('update:feedbackPath', '')
    return
  }
  const parsedValue = parseLoopFeedbackPath(text)
  if (parsedValue.manual) {
    manualText.value = parsedValue.text
    mode.value = 'manual'
    emit('update:feedbackPath', parsedValue.text)
    return
  }
  mode.value = 'pick'
  emit('update:feedbackPath', loopFeedbackPath(parsedValue.sourceCode, parsedValue.field))
}
</script>

<style scoped>
.wf-feedback {
  width: 100%;
  min-width: 0;
}
</style>
