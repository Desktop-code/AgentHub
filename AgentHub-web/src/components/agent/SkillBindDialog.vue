<template>
  <el-dialog :model-value="modelValue" title="添加 Skill" width="560px" @close="emit('update:modelValue', false)">
    <el-select
      v-model="skillIds"
      multiple
      filterable
      collapse-tags
      collapse-tags-tooltip
      placeholder="可多选已启用的 Skill"
      style="width: 100%"
    >
      <el-option v-for="item in options" :key="item.id" :label="`${item.name} · ${item.title}`" :value="item.id" />
    </el-select>
    <el-empty v-if="loaded && !options.length" description="没有可绑定的已启用 Skill" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">添加</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 多选已启用 Skill。所属域：agent。候选来自 Skill 卡片接口，不拉取正文。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageSkills } from '@/api/skill'
import type { SkillCard } from '@/types/skill'

const props = defineProps<{
  modelValue: boolean
  boundIds: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [skillIds: number[], done: () => void]
}>()

const saving = ref(false)
const loaded = ref(false)
const skillIds = ref<number[]>([])
const options = ref<SkillCard[]>([])

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    skillIds.value = []
    saving.value = false
    load()
  }
)

/** 拉启用 Skill 并剔除已绑定。 */
async function load() {
  loaded.value = false
  const { data } = await pageSkills({ current: 1, size: 100, status: 1 })
  const bound = new Set(props.boundIds)
  options.value = (data.data?.records || []).filter((item) => item.status === 1 && !bound.has(item.id))
  loaded.value = true
}

/** 至少勾选一条后交给详情页批量创建绑定。 */
function submit() {
  if (!skillIds.value.length) {
    ElMessage.warning('请选择至少一个 Skill')
    return
  }
  saving.value = true
  emit('submit', [...skillIds.value], () => {
    saving.value = false
  })
}
</script>
