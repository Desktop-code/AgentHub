<template>
  <el-dialog :model-value="modelValue" title="新建 Skill" width="640px" @close="emit('update:modelValue', false)">
    <el-form label-width="88px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="64" placeholder="调用名，未删除范围内唯一" />
      </el-form-item>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="128" />
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
        <!-- 一次粘贴多词会自动按中英文逗号切割成多个 Tag；保存时 join(',') 写库 -->
        <TagInput v-model="form.triggerKeywords" />
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
 *
 * 本刀交互口径：
 * - 不展示版本与状态控件；创建恒为启用（status=1）；
 * - 服务端创建后自动落 v1 草稿（简介与正文进版本），需在详情里「发布」才生效；
 * - 触发关键词用 Tag 数组提交，支持一次粘贴多词自动切割，后端负责 join 成库内逗号串。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import TagInput from '@/components/skill/TagInput.vue'

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
  description: '',
  /** 触发关键词 Tag 列表；提交前已由 TagInput 去重与长度校验 */
  triggerKeywords: [] as string[],
  remark: '',
  contentMd: ''
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.name = ''
    form.title = ''
    form.description = ''
    form.triggerKeywords = []
    form.remark = ''
    form.contentMd = ''
    saving.value = false
  }
)

/**
 * 交给页面发请求。空白正文不传，让后端存 null。
 * 不传 version / status：新建不接受手填版本号，status 缺省启用。
 */
function submit() {
  if (!form.name.trim() || !form.title.trim()) {
    ElMessage.warning('请填写名称和标题')
    return
  }
  saving.value = true
  const payload: Record<string, unknown> = {
    name: form.name.trim(),
    title: form.title.trim(),
    description: form.description.trim(),
    triggerKeywords: form.triggerKeywords.map((item) => item.trim()).filter(Boolean),
    remark: form.remark.trim()
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
</style>
