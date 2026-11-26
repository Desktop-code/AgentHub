<template>
  <div class="mpp">
    <!-- 来源：出参只有本节点一个来源时隐藏下拉，避免多一个无意义的选择 -->
    <div v-if="showSource" class="mpp__line" :class="{ 'mpp__line--attention': attention }">
      <span class="mpp__label">来源</span>
      <el-select
        :model-value="sourceValue"
        size="small"
        class="mpp__control"
        :placeholder="sourcePlaceholder || '选择来源'"
        :disabled="disabled"
        @update:model-value="onSource"
      >
        <el-option v-for="item in sources" :key="item.value" :label="item.label" :value="item.value">
          <span class="mpp__opt">
            <span class="mpp__opt-main">{{ item.label }}</span>
            <!-- 完整路径属于高级信息：默认不露出，打开「高级」后才随选项显示 -->
            <span v-if="showOptionHints && item.hint" class="mpp__opt-hint">{{ item.hint }}</span>
          </span>
        </el-option>
      </el-select>
    </div>

    <!-- 字段 / 固定值：点选只显示人话字段名；自定义值填的是写死内容，标签由父层传「固定值」 -->
    <div class="mpp__line">
      <span class="mpp__label">{{ fieldLabel }}</span>
      <!-- 手写模式：常量或高级路径，原样交给父层落库 -->
      <el-input
        v-if="mode === 'manual'"
        :model-value="manualValue"
        :type="manualMultiline ? 'textarea' : 'text'"
        :autosize="manualMultiline ? { minRows: 2, maxRows: 6 } : undefined"
        size="small"
        class="mpp__control"
        :placeholder="manualPlaceholder || '固定内容'"
        :disabled="disabled"
        @update:model-value="onManual"
      />
      <!-- 点选模式：字段无法穷举时仍可手输（含嵌套路径），但路径仍由父层拼 -->
      <el-select
        v-else
        :model-value="fieldValue"
        size="small"
        class="mpp__control"
        :filterable="allowCreate"
        :allow-create="allowCreate"
        :default-first-option="allowCreate"
        :placeholder="fieldPlaceholder || '选择字段'"
        :disabled="fieldDisabled"
        @update:model-value="onField"
      >
        <el-option
          v-for="item in fieldOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        >
          <span class="mpp__opt">
            <span class="mpp__opt-main">{{ item.label }}</span>
            <span v-if="showOptionHints && item.hint" class="mpp__opt-hint">{{ item.hint }}</span>
          </span>
        </el-option>
      </el-select>
    </div>

    <!-- 高级：路径默认收起，不再铺在每行下面；展开后可核对，允许时直接改 -->
    <div v-if="mode !== 'manual'" class="mpp__adv">
      <el-button
        link
        type="primary"
        size="small"
        class="mpp__adv-toggle"
        :disabled="disabled"
        @click="toggleAdvanced"
      >
        {{ advancedOpen ? '收起高级' : '高级' }}
      </el-button>
      <div v-if="pathVisible" class="mpp__adv-body">
        <span class="mpp__adv-label">路径</span>
        <!-- 可改路径时用输入框：失焦 / 回车才提交，避免逐字符把点选改成固定值 -->
        <el-input
          v-if="allowPathEdit"
          v-model="pathDraft"
          size="small"
          class="mpp__control"
          placeholder="直接编辑路径；失焦或回车生效"
          :disabled="disabled"
          @change="commitPath"
          @keyup.enter="commitPath"
        />
        <!-- 只读查看：保留等宽字体，完整内容放 title -->
        <div v-else class="mpp__path" :title="pathText">{{ pathText || emptyText }}</div>
      </div>
    </div>

    <!-- 手写入口只给需要高级写法的场景，不抢占默认点选路径 -->
    <el-button
      v-if="allowManual"
      link
      type="primary"
      size="small"
      class="mpp__manual"
      :disabled="disabled"
      @click="toggleMode"
    >
      {{ mode === 'manual' ? '改为点选' : '手写路径' }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作流映射的三处共用点选。所属域：workflow 的前端画布层。
 *
 * <p>职责：渲染「来源 + 字段」两级下拉，入参映射、出参映射、循环打回反馈共用同一套交互。
 * 本组件不解析路径、不拼字符串：来源与字段都是人话标签，落库路径由父层用
 * {@code utils/workflowMapping} 的纯函数拼装，保证存储格式与后端契约不变。
 *
 * <p>路径收口：默认不铺 {@code $.outputs...} 灰字，只有展开「高级」（或切到手写）后才出现；
 * 下拉选项里的路径说明也只在高级展开时显示。来源是自定义值时，父层把字段标签传成「固定值」。
 *
 * <p>约束：
 * <ul>
 *   <li>来源以 {@code value} 标识，父层负责映射成 input / node / vars / output / 打回来源编码；</li>
 *   <li>字段下拉的 label 只写字段名，完整路径放在高级信息里；</li>
 *   <li>手写模式只在父层明确允许时出现，避免把高级写法当默认入口。</li>
 * </ul>
 */
import { computed, ref, watch } from 'vue'
import { isMappingPathVisible } from '@/utils/workflowMapping'
import type { MappingPickerField, MappingPickerSource } from '@/utils/workflowMapping'

const props = withDefaults(
  defineProps<{
    /** 可选来源；出参只传本节点产出这一项 */
    sources: MappingPickerSource[]
    /** 当前来源值；空串表示未选择（新加的空行） */
    sourceValue: string
    /** 当前字段值；空串表示取整段，undefined 表示还没选过 */
    fieldValue?: string
    /** pick=点选，manual=手写 */
    mode?: 'pick' | 'manual'
    /** 手写原文 */
    manualValue?: string
    /** 实时回显的落库路径；父层算好传进来 */
    pathText?: string
    /** 是否显示来源下拉；出参固定本节点时隐藏 */
    showSource?: boolean
    /** 是否允许切到手写；入参用「自定义值」来源，不需要额外开关 */
    allowManual?: boolean
    /** 手写输入是否多行（固定值允许换行、看全） */
    manualMultiline?: boolean
    /** 高级面板里是否允许直接改路径；false 只读查看 */
    allowPathEdit?: boolean
    /** 只读角色禁用全部交互 */
    disabled?: boolean
    /** 字段列标签，默认「字段」；自定义值由父层传「固定值」 */
    fieldLabel?: string
    sourcePlaceholder?: string
    fieldPlaceholder?: string
    manualPlaceholder?: string
    /** 没有路径时的占位文案 */
    emptyText?: string
    /** 多条打回边且未选来源时高亮，提示这不是猜出来的 */
    attention?: boolean
  }>(),
  {
    mode: 'pick',
    manualValue: '',
    pathText: '',
    showSource: true,
    allowManual: false,
    manualMultiline: false,
    allowPathEdit: false,
    disabled: false,
    fieldLabel: '字段',
    sourcePlaceholder: '',
    fieldPlaceholder: '',
    manualPlaceholder: '',
    emptyText: '（未选择来源）',
    attention: false
  }
)

const emit = defineEmits<{
  (e: 'update:sourceValue', value: string): void
  (e: 'update:fieldValue', value: string): void
  (e: 'update:manualValue', value: string): void
  (e: 'update:mode', value: 'pick' | 'manual'): void
  (e: 'update:pathValue', value: string): void
}>()

/** 高级面板是否展开：默认收起，路径不再每行默认回显 */
const advancedOpen = ref(false)

/** 高级面板里的路径草稿：失焦 / 回车才上抛，避免逐字改写来源 */
const pathDraft = ref('')

/** 路径是否可见：点选 + 展开高级才出现；手写模式由输入框本身承担 */
const pathVisible = computed(() => isMappingPathVisible(props.mode, advancedOpen.value))

/** 下拉选项右侧的路径说明只在高级展开时显示，默认只留人话标签 */
const showOptionHints = computed(() => advancedOpen.value)

/** 当前来源项：字段候选、是否允许手写、整段文案都从它取 */
const currentSource = computed(() =>
  props.sources.find((item) => item.value === String(props.sourceValue ?? ''))
)

/** 未选来源时字段下拉不可用，避免用户先填字段再回头选来源造成误解 */
const fieldDisabled = computed(() => props.disabled || !currentSource.value)

/** 字段是否允许直接输入嵌套路径；缺省允许 */
const allowCreate = computed(() => currentSource.value?.allowCustomField !== false)

/**
 * 字段下拉项：整段项 + 当前来源的字段。
 * <p>当前值不在候选里时补一条，保证回显的历史字段 / 手输嵌套路径不会显示成空白。
 */
const fieldOptions = computed<MappingPickerField[]>(() => {
  const source = currentSource.value
  const options: MappingPickerField[] = []
  if (source) {
    if (source.wholeLabel !== undefined && source.wholeLabel !== '') {
      options.push({ value: '', label: source.wholeLabel })
    }
    options.push(...source.fields)
  }
  const field = String(props.fieldValue ?? '')
  if (field && !options.some((item) => item.value === field)) {
    options.push({
      value: field,
      label: field,
      hint: source?.hint ? `${source.hint}.${field}` : ''
    })
  }
  return options
})

/** 路径 / 展开状态变化时同步草稿，保证外部回填后高级面板不显示旧值 */
watch(
  () => [props.pathText, advancedOpen.value] as const,
  () => {
    pathDraft.value = String(props.pathText ?? '')
  },
  { immediate: true }
)

/** 来源切换：只上抛值，清空字段与路径由父层决定（不同来源字段互不通用） */
function onSource(value: unknown) {
  emit('update:sourceValue', String(value ?? ''))
}

/** 字段选择 / 手输嵌套路径 */
function onField(value: unknown) {
  emit('update:fieldValue', String(value ?? ''))
}

/** 手写输入：原文上抛，父层按常量 / 高级路径落库 */
function onManual(value: string) {
  emit('update:manualValue', String(value ?? ''))
}

/** 手写 / 点选来回切；能否切回由父层解析结果决定 */
function toggleMode() {
  emit('update:mode', props.mode === 'manual' ? 'pick' : 'manual')
}

/** 展开 / 收起高级；展开时先把草稿对齐当前路径，避免读到上一行的旧值 */
function toggleAdvanced() {
  if (!advancedOpen.value) {
    pathDraft.value = String(props.pathText ?? '')
  }
  advancedOpen.value = !advancedOpen.value
}

/**
 * 提交高级面板里的路径。
 * <p>只有真的变了才上抛；父层据此把路径归一成点选来源或固定值原文。
 */
function commitPath() {
  const next = pathDraft.value
  if (next === String(props.pathText ?? '')) {
    return
  }
  emit('update:pathValue', next)
}
</script>

<style scoped>
.mpp__line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
}

/* 多条打回边未选来源时给一条主色描边，提示需要用户拍板 */
.mpp__line--attention :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--ah-primary) inset;
}

/* 标签列：固定值比字段多一个字，用最小宽度而不是写死 28px，避免文字被裁 */
.mpp__label {
  flex: none;
  min-width: 28px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}

.mpp__control {
  flex: 1;
  min-width: 0;
}

/* 选项内的次要说明：完整路径靠右、灰色，只在高级展开时出现 */
.mpp__opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mpp__opt-main {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mpp__opt-hint {
  flex: none;
  color: var(--ah-text-faint);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

/* 高级入口：小号链接，放在字段行下方，不抢默认点选 */
.mpp__adv {
  margin-top: 2px;
}

.mpp__adv-toggle {
  padding: 0;
  font-size: 12px;
}

.mpp__adv-body {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 4px;
}

.mpp__adv-label {
  flex: none;
  min-width: 28px;
  padding-top: 5px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}

.mpp__path {
  flex: 1;
  min-width: 0;
  padding: 2px 6px;
  overflow: hidden;
  border-radius: 4px;
  background: var(--ah-neutral-tint);
  color: var(--ah-text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mpp__manual {
  padding: 0;
  font-size: 12px;
}
</style>
