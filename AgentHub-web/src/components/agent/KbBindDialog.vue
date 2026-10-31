<template>
  <el-dialog
    :model-value="modelValue"
    title="添加知识库"
    width="600px"
    @close="emit('update:modelValue', false)"
  >
    <el-form label-width="88px">
      <el-form-item label="知识库">
        <el-select
          v-model="form.kbId"
          filterable
          placeholder="选择可见且已就绪的知识库"
          style="width: 100%"
          @change="onKbChange"
        >
          <el-option
            v-for="item in options"
            :key="item.id"
            :label="`${item.name}（${KB_CAPABILITY_LABEL[item.storeCapability || ''] || '未初始化'}）`"
            :value="item.id"
          />
        </el-select>
        <div class="hint">只列出已就绪、且对当前账号可见的知识库；PRIVATE 库需由创建人或管理员绑定</div>
      </el-form-item>
      <el-form-item label="检索覆盖">
        <el-input
          v-model="form.overridesJson"
          type="textarea"
          :rows="3"
          class="mono"
          placeholder="留空表示沿用知识库默认"
        />
        <div class="hint">
          可选 JSON，例如 {"topK":3,"rrfK":60}；不填则完全用知识库的检索参数。
          重排请在下方的「检索链路」统一配置，不写在这个 JSON 里（Agent 的检索链路设置优先于这里）。
        </div>
      </el-form-item>
    </el-form>
    <el-empty v-if="loaded && !options.length" description="没有可绑定的知识库" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!form.kbId" @click="submit">添加</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 添加 Agent 知识库绑定。所属域：rag。
 * 候选只取「当前用户可见 + 已就绪」的库，避免绑一个查不到的库后再排查。
 * 检索覆盖项留空即全用 KB 默认，与后端 RetrievalConfigResolver 的优先级一致。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageKbs } from '@/api/rag'
import { KB_CAPABILITY_LABEL, type RagKbRow } from '@/types/rag'

const props = defineProps<{
  modelValue: boolean
  /** 已绑定的知识库 id，候选里排除 */
  boundIds: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [kbId: number, overridesJson: string | undefined, done: () => void]
}>()

const saving = ref(false)
const loaded = ref(false)
const options = ref<RagKbRow[]>([])
const form = ref({
  kbId: undefined as number | undefined,
  overridesJson: ''
})

/** 选中库时给出默认覆盖项建议，减少手写 JSON 出错 */
function onKbChange() {
  // 有意留空：默认不覆盖，交给 KB 的 retrieval_config
  form.value.overridesJson = ''
}

async function load() {
  try {
    const { data } = await pageKbs({ current: 1, size: 100, status: 'READY' })
    const bound = new Set(props.boundIds)
    options.value = (data.data.records || []).filter((item) => !bound.has(item.id))
    loaded.value = true
  } catch {
    ElMessage.warning('知识库列表加载失败')
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.value = { kbId: undefined, overridesJson: '' }
    saving.value = false
    loaded.value = false
    void load()
  }
)

function submit() {
  if (!form.value.kbId) {
    ElMessage.warning('请选择知识库')
    return
  }
  saving.value = true
  emit('submit', form.value.kbId, form.value.overridesJson.trim() || undefined, () => {
    saving.value = false
  })
}
</script>

<style scoped>
.hint {
  margin-top: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
</style>
