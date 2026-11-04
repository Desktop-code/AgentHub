<template>
  <div class="jsonlogic">
    <el-input
      v-model="text"
      type="textarea"
      :rows="6"
      spellcheck="false"
      class="jsonlogic__input"
      placeholder='JSONLogic 子集，例如 {"==":[{"var":"$.input.type"},"refund"]}'
      @input="onInput"
    />
    <div v-if="error" class="jsonlogic__error">{{ error }}</div>
    <div v-else class="jsonlogic__hint">仅存盘与结构校验；求值引擎在下一刀接入，禁止写 SpEL。</div>
  </div>
</template>

<script setup lang="ts">
/**
 * JSONLogic 表达式编辑器（P0 文本框版）。所属域：workflow 的前端画布层。
 * <p>把 config.condition 对象与文本互转：输入合法 JSON 才向上 emit，非法时只提示不写坏数据。
 * <p>为什么不用表单向导：P0 只要求「存盘 + 结构可见」，表达式结构留给引擎刀再收敛。
 */
import { ref, watch } from 'vue'

const props = defineProps<{
  /** 当前表达式对象；空表示未配置 */
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: unknown): void
}>()

const text = ref(toText(props.modelValue))
const error = ref('')

/** 外部切换节点/回填时同步文本；自身输入引起的变化不覆盖，避免光标跳动 */
watch(
  () => props.modelValue,
  (next) => {
    if (sameValue(parse(text.value), next)) {
      return
    }
    text.value = toText(next)
    error.value = ''
  }
)

/** 输入即解析：合法则上抛对象，非法只保留文本并提示 */
function onInput() {
  if (!text.value.trim()) {
    error.value = ''
    emit('update:modelValue', null)
    return
  }
  try {
    const parsed = JSON.parse(text.value) as unknown
    error.value = ''
    emit('update:modelValue', parsed)
  } catch {
    error.value = 'JSONLogic 不是合法 JSON'
  }
}

/**
 * 对象 → 文本。
 *
 * @param value 表达式对象
 * @return 格式化 JSON；空值返回空串
 */
function toText(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'string') {
    return value
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return ''
  }
}

/**
 * 宽松解析；失败返回 undefined，避免与合法 null 混淆。
 */
function parse(raw: string): unknown {
  if (!raw.trim()) {
    return null
  }
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return undefined
  }
}

/** 结构化比较：字符串化后对比，忽略键顺序差异带来的误判 */
function sameValue(left: unknown, right: unknown): boolean {
  try {
    return JSON.stringify(left ?? null) === JSON.stringify(right ?? null)
  } catch {
    return false
  }
}
</script>

<style scoped>
.jsonlogic__input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.jsonlogic__error {
  margin-top: 4px;
  color: #dc2626;
  font-size: 12px;
}

.jsonlogic__hint {
  margin-top: 4px;
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.5;
}
</style>
