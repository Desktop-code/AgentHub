<template>
  <div class="mapping">
    <div v-for="(row, index) in rows" :key="index" class="mapping__row">
      <el-input v-model="row.key" class="mapping__key" placeholder="目标字段" @input="emitRows" />
      <el-input v-model="row.value" class="mapping__value" placeholder="$.input.xxx" @input="emitRows" />
      <el-button link type="danger" class="mapping__remove" @click="removeRow(index)">删除</el-button>
    </div>
    <el-button link type="primary" class="mapping__add" @click="addRow">+ 添加映射</el-button>
    <div class="mapping__hint">值支持 $.input / $.outputs / $.vars 路径；常量可直接写文本。</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 简单 KV 映射编辑器。所属域：workflow 的前端画布层。
 * <p>只编辑「目标字段 → 取值路径」的扁平对象；空键不写入，避免脏键进 graph_json。
 * <p>每次修改整体上抛新对象（不做原地 mutate），父层可直接覆盖 config.inputMapping。
 */
import { ref, watch } from 'vue'

const props = defineProps<{
  /** 当前映射对象；空表示未配置 */
  modelValue?: Record<string, string>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, string>): void
}>()

interface Row {
  key: string
  value: string
}

const rows = ref<Row[]>([])

/** 外部回填（切换节点/接口回显）时重建行；自身刚上抛的变更不重建，避免输入框被重置 */
watch(
  () => props.modelValue,
  (next) => {
    if (sameMap(next || {}, build())) {
      return
    }
    rows.value = Object.entries(next || {}).map(([key, value]) => ({
      key,
      value: value == null ? '' : String(value)
    }))
  },
  { immediate: true }
)

/** 丢掉空键后压回对象 */
function build(): Record<string, string> {
  const result: Record<string, string> = {}
  for (const row of rows.value) {
    const key = row.key.trim()
    if (!key) {
      continue
    }
    result[key] = row.value
  }
  return result
}

function emitRows() {
  emit('update:modelValue', build())
}

function addRow() {
  rows.value.push({ key: '', value: '' })
}

function removeRow(index: number) {
  rows.value.splice(index, 1)
  emitRows()
}

/** 键顺序无关的比较，避免回填把正在编辑的值冲掉 */
function sameMap(left: Record<string, string>, right: Record<string, string>): boolean {
  const leftKeys = Object.keys(left)
  const rightKeys = Object.keys(right)
  if (leftKeys.length !== rightKeys.length) {
    return false
  }
  return leftKeys.every((key) => left[key] === right[key])
}
</script>

<style scoped>
.mapping__row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.mapping__key {
  flex: 0 0 38%;
}

.mapping__value {
  flex: 1;
}

.mapping__remove {
  flex: none;
  padding: 0;
  font-size: 12px;
}

.mapping__add {
  padding: 0;
  font-size: 12px;
}

.mapping__hint {
  margin-top: 4px;
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.5;
}
</style>
