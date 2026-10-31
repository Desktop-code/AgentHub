<template>
  <el-dialog
    :model-value="modelValue"
    :title="draft ? '编辑单价' : '新建单价'"
    width="560px"
    @close="emit('update:modelValue', false)"
  >
    <el-form label-width="120px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="模型名称">
        <el-select v-model="form.modelInstanceId" clearable filterable placeholder="默认规则" class="full">
          <el-option
            v-for="item in instances"
            :key="item.id"
            :label="item.providerName ? `${item.providerName} / ${item.displayName}` : item.displayName"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="输入单价 (Token)" required>
        <el-input-number v-model="form.pricePrompt" :min="0" :precision="6" :step="0.000001" class="full" />
      </el-form-item>
      <el-form-item label="输出单价 (Token)" required>
        <el-input-number v-model="form.priceCompletion" :min="0" :precision="6" :step="0.000001" class="full" />
      </el-form-item>
      <el-form-item label="币种">
        <el-input v-model="form.currency" maxlength="8" placeholder="CNY" />
      </el-form-item>
      <el-form-item label="生效开始" required>
        <el-date-picker
          v-model="form.effectiveFrom"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="开始时间"
          class="full"
        />
      </el-form-item>
      <el-form-item label="生效结束">
        <el-date-picker
          v-model="form.effectiveTo"
          type="datetime"
          value-format="YYYY-MM-DD HH:mm:ss"
          placeholder="不填表示一直有效"
          class="full"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" maxlength="500" :rows="2" />
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
 * 新建或编辑单价。所属域：billing。
 * 模型留空表示默认规则。启停不在这里改。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useObjectDirty } from '@/composables/useFormDirty'
import type { PriceRow, PriceSave } from '@/types/billing'
import type { InstanceRow } from '@/types/model'

const props = defineProps<{
  modelValue: boolean
  draft: PriceRow | null
  instances: InstanceRow[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: PriceSave, done: () => void]
}>()

const saving = ref(false)
const form = reactive({
  name: '',
  modelInstanceId: undefined as number | undefined,
  pricePrompt: 0,
  priceCompletion: 0,
  currency: 'CNY',
  effectiveFrom: '',
  effectiveTo: '',
  remark: ''
})

/**
 * 弹窗内可编辑字段的脏检查。单价、币种、生效区间都是「填写 / 选择」类，
 * 启停不在弹窗里改。
 */
const { isDirty, markClean } = useObjectDirty(() => form)

/** 取消 / 保存是否可点：新建态恒可点，编辑态要求有改动 */
const canSubmit = computed(() => !props.draft || isDirty.value || saving.value)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    const row = props.draft
    form.name = row?.name || ''
    form.modelInstanceId = row?.modelInstanceId ?? undefined
    form.pricePrompt = row?.pricePrompt ?? 0
    form.priceCompletion = row?.priceCompletion ?? 0
    form.currency = row?.currency || 'CNY'
    form.effectiveFrom = row?.effectiveFrom || ''
    form.effectiveTo = row?.effectiveTo || ''
    form.remark = row?.remark || ''
    saving.value = false
    // 回填完成即定为干净基线：打开弹窗不该显示可保存
    markClean()
  }
)

/** 校验必填后交给页面提交。 */
function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!form.effectiveFrom) {
    ElMessage.warning('请填写生效开始')
    return
  }
  saving.value = true
  const payload: PriceSave = {
    modelInstanceId: form.modelInstanceId ?? null,
    name: form.name.trim(),
    pricePrompt: form.pricePrompt,
    priceCompletion: form.priceCompletion,
    currency: form.currency.trim() || 'CNY',
    effectiveFrom: form.effectiveFrom,
    effectiveTo: form.effectiveTo || null,
    remark: form.remark.trim()
  }
  emit('submit', payload, () => {
    saving.value = false
  })
}
</script>

<style scoped>
.full {
  width: 100%;
}
</style>
