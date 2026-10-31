<template>
  <el-dialog :model-value="modelValue" title="添加模型" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="88px">
      <el-form-item label="实例" required>
        <el-select
          v-model="form.modelInstanceIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="可多选已启用的模型实例"
          style="width: 100%"
        >
          <el-option
            v-for="item in options"
            :key="item.id"
            :label="`${item.displayName} · ${item.capability}`"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="用途">
        <!-- 从用途槽打开时用途已固定：直接给文案，避免下拉里缺对应项时显示原始码 -->
        <span v-if="fixedPurpose" class="fixed-purpose">{{ capabilityOptionLabel(fixedPurpose) }}</span>
        <el-select
          v-else
          v-model="form.purpose"
          clearable
          placeholder="空则沿用实例用途"
          style="width: 100%"
        >
          <!-- 只列「模型配置」Tab 在册的槽：嵌入与重排已移出本页，不在此下拉出现 -->
          <el-option-group v-for="group in PURPOSE_SLOT_GROUPS" :key="group.key" :label="group.label">
            <el-option
              v-for="item in group.codes"
              :key="item"
              :label="capabilityOptionLabel(item)"
              :value="item"
            />
          </el-option-group>
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-input-number v-model="form.priority" :min="0" :max="10000" />
        <span class="hint">越小越优先；多选时共用</span>
      </el-form-item>
    </el-form>
    <el-empty
      v-if="loaded && !options.length"
      :description="
        fixedPurpose
          ? `没有可绑定的 ${capabilityOptionLabel(fixedPurpose)} 已启用实例`
          : '没有可绑定的已启用模型'
      "
    />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">添加</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 多选已启用模型实例。所属域：agent。
 * 从用途槽打开时按槽筛 capability；候选仅未删启用且未绑定实例。
 * 本弹窗服务「模型配置」与「记忆配置」两处用途槽（都由调用方固定 purpose 打开），
 * 因此不提供 EMBEDDING / RERANK：前者跟知识库 / 向量实例，后者在「知识库」Tab 的检索链路里配。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageInstances } from '@/api/model'
import type { InstanceRow } from '@/types/model'
import { PURPOSE_SLOT_GROUPS, capabilityOptionLabel, normalizeCapability } from '@/types/model'

const props = defineProps<{
  modelValue: boolean
  boundIds: number[]
  /** 从用途槽打开时固定用途 */
  fixedPurpose?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: { modelInstanceIds: number[]; purpose?: string; priority: number }, done: () => void]
}>()

const saving = ref(false)
const loaded = ref(false)
const options = ref<InstanceRow[]>([])
const form = reactive({
  modelInstanceIds: [] as number[],
  purpose: '',
  priority: 100
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.modelInstanceIds = []
    form.purpose = props.fixedPurpose || ''
    form.priority = 100
    saving.value = false
    load()
  }
)

/**
 * 拉启用实例：有固定槽则带 capability 筛用途；客户端再按规范码兜底，避免旧码漏网。
 */
async function load() {
  loaded.value = false
  const slot = normalizeCapability(props.fixedPurpose)
  const { data } = await pageInstances({
    current: 1,
    size: 100,
    // 列表 API 已支持用途筛选（含旧码 IN）
    ...(slot ? { capability: slot } : {})
  })
  const bound = new Set(props.boundIds)
  options.value = (data.data?.records || []).filter((item) => {
    if (item.status !== 1 || bound.has(item.id)) {
      return false
    }
    // 二次本地过滤：与槽用途不一致的不进下拉
    if (slot && normalizeCapability(item.capability) !== slot) {
      return false
    }
    return true
  })
  loaded.value = true
}

/** 至少勾选一条后交给详情页批量创建绑定。 */
function submit() {
  if (!form.modelInstanceIds.length) {
    ElMessage.warning('请选择至少一个模型实例')
    return
  }
  saving.value = true
  emit(
    'submit',
    {
      modelInstanceIds: [...form.modelInstanceIds],
      purpose: form.purpose || undefined,
      priority: form.priority
    },
    () => {
      saving.value = false
    }
  )
}
</script>

<style scoped>
.hint {
  margin-left: 8px;
  color: var(--ah-text-secondary);
  font-size: 12px;
}

/* 固定用途：下拉退化为只读文案，与其它只读字段风格一致 */
.fixed-purpose {
  color: var(--ah-text-primary, #1f2430);
  font-size: 13px;
}
</style>
