<template>
  <el-dialog :model-value="modelValue" title="添加工具" width="560px" @close="emit('update:modelValue', false)">
    <el-select
      v-model="toolItemIds"
      multiple
      filterable
      collapse-tags
      collapse-tags-tooltip
      placeholder="可多选已启用的工具项"
      style="width: 100%"
    >
      <el-option
        v-for="item in options"
        :key="item.id"
        :label="`${item.name}${item.sourceName ? ' · ' + item.sourceName : ''}`"
        :value="item.id"
      />
    </el-select>
    <el-empty v-if="loaded && !options.length" description="没有可绑定的已启用工具" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">添加</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 多选已启用工具项。所属域：agent。候选来自工具列表，已绑定的不再出现。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageItems } from '@/api/tool'
import type { ToolItemRow } from '@/types/tool'

const props = defineProps<{
  modelValue: boolean
  boundIds: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [toolItemIds: number[], done: () => void]
}>()

const saving = ref(false)
const loaded = ref(false)
const toolItemIds = ref<number[]>([])
const options = ref<ToolItemRow[]>([])

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    toolItemIds.value = []
    saving.value = false
    load()
  }
)

/** 拉启用工具项并剔除已绑定。 */
async function load() {
  loaded.value = false
  const { data } = await pageItems({ current: 1, size: 100, status: 1 })
  const bound = new Set(props.boundIds)
  options.value = (data.data?.records || []).filter((item) => item.status === 1 && !bound.has(item.id))
  loaded.value = true
}

/** 至少勾选一条后交给详情页批量创建绑定。 */
function submit() {
  if (!toolItemIds.value.length) {
    ElMessage.warning('请选择至少一个工具项')
    return
  }
  saving.value = true
  emit('submit', [...toolItemIds.value], () => {
    saving.value = false
  })
}
</script>
