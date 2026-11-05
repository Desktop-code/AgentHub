<template>
  <!-- 点击容器任意位置都聚焦到输入框，符合 Tag 输入习惯 -->
  <div
    class="tag-input"
    :class="{ 'is-focus': focused, 'is-disabled': disabled, 'has-clear': showClear }"
    @click="focusInput"
  >
    <el-tag
      v-for="(tag, index) in modelValue"
      :key="`${tag}-${index}`"
      class="tag-input__tag"
      size="small"
      :closable="!disabled"
      :disable-transitions="true"
      @close="removeAt(index)"
    >
      {{ tag }}
    </el-tag>
    <input
      ref="inputRef"
      class="tag-input__input"
      type="text"
      :value="draft"
      :disabled="disabled"
      :placeholder="modelValue.length ? '' : placeholder"
      aria-label="触发关键词"
      @input="onInput"
      @keydown="onKeydown"
      @paste="onPaste"
      @focus="focused = true"
      @blur="onBlur"
      @compositionstart="composing = true"
      @compositionend="onCompositionEnd"
    />
    <!-- 最右侧一键清空；无 Tag 或只读时不占位，避免空白框右侧多出控件 -->
    <button
      v-if="showClear"
      type="button"
      class="tag-input__clear"
      aria-label="清空触发关键词"
      @click.stop="clearAll"
      @mousedown.prevent
    >
      <el-icon><CircleClose /></el-icon>
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 触发关键词 Tag 输入框。所属域：前端通用（Skill 新建弹窗 / 详情编辑共用）。
 *
 * 关键行为：
 * - 一次输入或粘贴 `退款,发票，对账`，按中英文逗号与换行自动切割成多个 Tag；
 * - 回车 / 失焦也会把当前输入落成 Tag，仍支持逐个添加；
 * - 去空白、去空段、忽略大小写去重；超长/超个数/合计超列宽中文提示，不静默截断；
 * - v-model 始终是字符串数组，保存时由调用方 join(',') 写库。
 *
 * 不负责发起请求，也不改后端硬触发匹配语义。
 */
import { computed, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleClose } from '@element-plus/icons-vue'
import { addKeywords } from '@/utils/skillKeywords'

defineOptions({ name: 'SkillTagInput' })

const props = withDefaults(
  defineProps<{
    /** 当前 Tag 列表 */
    modelValue: string[]
    /** 只读态：隐藏删除按钮并禁用输入 */
    disabled?: boolean
    /** 空列表时的占位提示 */
    placeholder?: string
  }>(),
  {
    disabled: false,
    placeholder: '输入或粘贴关键词，逗号分隔，回车确认'
  }
)

const emit = defineEmits<{
  'update:modelValue': [tags: string[]]
}>()

/** 尚未落成 Tag 的输入原文 */
const draft = ref('')
const inputRef = ref<HTMLInputElement | null>(null)
const focused = ref(false)
/** 输入法组词中：此阶段不切割，避免中文候选词被拆开 */
const composing = ref(false)

/**
 * 是否显示右侧清空按钮：有 Tag 且可编辑时才出现。
 * 空列表与只读态都不显示，避免右侧常驻一个无效控件。
 */
const showClear = computed(() => !props.disabled && props.modelValue.length > 0)

/** 一键清空：Tag 与未确认草稿一起清掉，避免草稿随后又落成 Tag。 */
function clearAll() {
  if (props.disabled) {
    return
  }
  draft.value = ''
  if (props.modelValue.length) {
    emit('update:modelValue', [])
  }
  void nextTick(() => inputRef.value?.focus())
}

/** 聚焦内部输入框；只读态不抢焦点。 */
function focusInput() {
  if (props.disabled) {
    return
  }
  inputRef.value?.focus()
}

/** 删除指定位置的 Tag。 */
function removeAt(index: number) {
  if (props.disabled) {
    return
  }
  const next = props.modelValue.slice()
  next.splice(index, 1)
  emit('update:modelValue', next)
}

/**
 * 把一段原文切割并并入 Tag 列表。
 *
 * @param raw     输入或粘贴的原文
 * @param refocus 落 Tag 后是否把焦点还给输入框（失焦场景传 false，避免抢焦点）
 */
function apply(raw: string, refocus = true) {
  const text = raw.trim()
  if (!text) {
    draft.value = ''
    return
  }
  const result = addKeywords(props.modelValue, text)
  if (result.error) {
    ElMessage.warning(result.error)
  }
  // 只有真正新增了 Tag 才回写，避免重复词触发无意义的父级更新
  if (result.tags.length !== props.modelValue.length) {
    emit('update:modelValue', result.tags)
  }
  draft.value = ''
  if (refocus && focused.value) {
    void nextTick(() => inputRef.value?.focus())
  }
}

/** 输入时同步草稿；出现分隔符立即切割（输入法组词中除外）。 */
function onInput(event: Event) {
  const value = (event.target as HTMLInputElement).value
  draft.value = value
  if (composing.value) {
    return
  }
  if (/[,，\n\r]/.test(value)) {
    apply(value)
  }
}

/** 回车落 Tag；草稿为空时退格删除最后一个 Tag。 */
function onKeydown(event: KeyboardEvent) {
  if (event.isComposing) {
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    apply(draft.value)
    return
  }
  if (event.key === 'Backspace' && !draft.value && props.modelValue.length) {
    event.preventDefault()
    removeAt(props.modelValue.length - 1)
  }
}

/**
 * 粘贴整串时接管为「切割成多个 Tag」。
 * 先把剪贴板文本与草稿拼接，避免用户先打了一半再粘贴时丢字。
 */
function onPaste(event: ClipboardEvent) {
  const text = event.clipboardData?.getData('text') ?? ''
  if (!text) {
    return
  }
  event.preventDefault()
  apply(draft.value + text)
}

/** 输入法结束：若候选词里带全角逗号，同样切割。 */
function onCompositionEnd(event: CompositionEvent) {
  composing.value = false
  const value = (event.target as HTMLInputElement).value
  draft.value = value
  if (/[,，\n\r]/.test(value)) {
    apply(value)
  }
}

/** 失焦：把未确认的输入落成 Tag；不重新抢焦点。 */
function onBlur() {
  focused.value = false
  apply(draft.value, false)
}
</script>

<style scoped>
/* 外观对齐 Element Plus 输入框：白底、1px 边框、聚焦主色 */
.tag-input {
  box-sizing: border-box;
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 32px;
  padding: 2px 8px;
  background: var(--el-fill-color-blank, #fff);
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 4px;
  cursor: text;
  transition: border-color 0.2s ease;
}

.tag-input:hover {
  border-color: var(--el-border-color-hover, #c0c4cc);
}

.tag-input.is-focus {
  border-color: var(--el-color-primary, #635bff);
}

.tag-input.is-disabled {
  background: var(--el-disabled-bg-color, #f5f7fa);
  border-color: var(--el-border-color-light, #e4e7ed);
  cursor: not-allowed;
}

.tag-input__tag {
  max-width: 100%;
}

/* 输入框自身去壳，宽度不足时自动换行到下一行 */
.tag-input__input {
  flex: 1 1 120px;
  min-width: 120px;
  height: 24px;
  padding: 0;
  color: var(--el-text-color-regular, #606266);
  font-size: 14px;
  line-height: 24px;
  background: transparent;
  border: 0;
  outline: none;
}

/*
 * 有清空按钮时右侧留出固定空位：
 * 按钮绝对定位贴右边，多行 Tag 也不会被压到按钮底下。
 */
.tag-input.has-clear {
  padding-right: 28px;
}

/* 清空按钮：竖向居中贴最右侧；hover 变灰底，与 Element 的 clear 图标观感一致 */
.tag-input__clear {
  position: absolute;
  top: 50%;
  right: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  color: var(--el-text-color-placeholder, #a8abb2);
  font-size: 14px;
  line-height: 1;
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  transform: translateY(-50%);
}

.tag-input__clear:hover {
  color: var(--el-text-color-secondary, #909399);
  background: var(--el-fill-color-light, #f5f7fa);
}

.tag-input__input::placeholder {
  color: var(--el-text-color-placeholder, #a8abb2);
}
</style>
