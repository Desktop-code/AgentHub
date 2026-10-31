<template>
  <el-dialog :model-value="modelValue" title="新建 Skill" width="640px" @close="emit('update:modelValue', false)">
    <el-form label-width="88px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="64" placeholder="调用名，未删除范围内唯一" />
      </el-form-item>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="128" />
      </el-form-item>
      <el-form-item label="版本">
        <el-input v-model="form.version" maxlength="32" placeholder="默认 1" />
      </el-form-item>
      <el-form-item>
        <template #label>
          <span class="label-with-tip">
            简介
            <el-tooltip content="此描述会供大模型判断何时加载此技能，请精炼准确。" placement="top">
              <el-icon class="tip-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </span>
        </template>
        <el-input v-model="form.description" type="textarea" maxlength="1000" :rows="2" />
      </el-form-item>
      <el-form-item label="触发关键词">
        <el-select
          v-model="form.triggerKeywords"
          class="keyword-select"
          multiple
          filterable
          allow-create
          default-first-option
          :reserve-keyword="false"
          placeholder="回车成 Tag；命中任一词则强制注入正文"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          maxlength="500"
          :rows="2"
          placeholder="仅内部人员可见，不会注入模型"
        />
      </el-form-item>
      <el-form-item label="正文">
        <el-input v-model="form.contentMd" type="textarea" :rows="8" placeholder="Markdown，可稍后在详情里补" />
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 新建 Skill 弹窗。所属域：skill。
 * 编辑元数据和正文在详情区完成，这里只负责创建。
 * 触发关键词用 Tag 数组提交，后端负责 join 成库内逗号串。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: Record<string, unknown>, done: () => void]
}>()

const saving = ref(false)
const form = reactive({
  name: '',
  title: '',
  version: '1',
  description: '',
  triggerKeywords: [] as string[],
  remark: '',
  contentMd: '',
  status: 1
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.name = ''
    form.title = ''
    form.version = '1'
    form.description = ''
    form.triggerKeywords = []
    form.remark = ''
    form.contentMd = ''
    form.status = 1
    saving.value = false
  }
)

/** 交给页面发请求。空白正文不传，让后端存 null。 */
function submit() {
  if (!form.name.trim() || !form.title.trim()) {
    ElMessage.warning('请填写名称和标题')
    return
  }
  saving.value = true
  const payload: Record<string, unknown> = {
    name: form.name.trim(),
    title: form.title.trim(),
    version: form.version.trim() || '1',
    description: form.description.trim(),
    triggerKeywords: form.triggerKeywords.map((item) => item.trim()).filter(Boolean),
    remark: form.remark.trim(),
    status: form.status
  }
  if (form.contentMd.trim()) {
    payload.contentMd = form.contentMd
  }
  emit('submit', payload, () => {
    saving.value = false
  })
}
</script>

<style scoped>
.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--el-text-color-placeholder);
  cursor: help;
}

.keyword-select {
  width: 100%;
}
</style>
