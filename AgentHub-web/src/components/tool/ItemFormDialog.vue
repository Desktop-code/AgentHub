<template>
  <el-dialog :model-value="modelValue" :title="editing ? '编辑工具项' : '新建工具项'" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="96px">
      <el-form-item label="工具源" required>
        <el-select v-model="form.sourceId" :disabled="!!editing" filterable style="width: 100%">
          <el-option v-for="item in sources" :key="item.id" :label="`${item.name} · ${item.type}`" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="128" :disabled="!!editing" placeholder="调用名" />
      </el-form-item>
      <el-form-item label="展示名"><el-input v-model="form.title" maxlength="128" /></el-form-item>
      <el-form-item label="说明"><el-input v-model="form.description" type="textarea" maxlength="1000" /></el-form-item>
      <el-form-item label="入参 Schema">
        <el-input v-model="form.inputSchemaJson" type="textarea" :rows="5" placeholder="空着或填写 JSON" />
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
 * 工具项新建 / 编辑弹窗。所属域：tool。
 * 编辑不能改调用名和所属源。新建后的启停不在这里提交。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useObjectDirty } from '@/composables/useFormDirty'
import type { ToolItemRow, ToolSource } from '@/types/tool'

const props = defineProps<{
  modelValue: boolean
  editing: ToolItemRow | null
  sources: ToolSource[]
  presetSourceId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: Record<string, unknown>, done: () => void]
}>()

const saving = ref(false)
const form = reactive({
  sourceId: undefined as number | undefined,
  name: '',
  title: '',
  description: '',
  inputSchemaJson: ''
})

/**
 * 弹窗内可编辑字段的脏检查。
 * sourceId 编辑态被禁用（不能换源），但仍纳入快照：新建态它是有意义的填写项。
 */
const { isDirty, markClean } = useObjectDirty(() => form)

/** 取消 / 保存是否可点：新建态恒可点，编辑态要求有改动 */
const canSubmit = computed(() => !props.editing || isDirty.value || saving.value)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    const current = props.editing
    form.sourceId = current?.sourceId || props.presetSourceId
    form.name = current?.name || ''
    form.title = current?.title || ''
    form.description = current?.description || ''
    form.inputSchemaJson = current?.inputSchemaJson || ''
    // 回填完成即定为干净基线：打开弹窗不该显示可保存
    markClean()
  }
)

/** 交给页面发请求。schema 留空表示清空。 */
function submit() {
  if (!form.sourceId || !form.name.trim()) {
    ElMessage.warning('请选择工具源并填写名称')
    return
  }
  const schema = form.inputSchemaJson.trim()
  if (schema) {
    try {
      JSON.parse(schema)
    } catch {
      ElMessage.warning('入参 Schema 不是合法 JSON')
      return
    }
  }
  saving.value = true
  emit(
    'submit',
    {
      sourceId: form.sourceId,
      name: form.name.trim(),
      title: form.title.trim(),
      description: form.description.trim(),
      inputSchemaJson: schema
    },
    () => {
      saving.value = false
    }
  )
}
</script>
