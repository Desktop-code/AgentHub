<template>
  <el-dialog :model-value="modelValue" :title="editing ? '编辑供应商' : '新建供应商'" width="520px" @close="emit('update:modelValue', false)">
    <el-form label-width="88px">
      <el-form-item label="名称" required><el-input v-model="form.name" maxlength="64" /></el-form-item>
      <el-form-item label="协议" required>
        <!--
          历史数据是已下线协议（如 CUSTOM）时：只读回显 + 明确提示不可用。
          不放进下拉选项，也不静默改成别的协议——那等于悄悄改库。
        -->
        <div v-if="legacyProtocol" class="protocol-legacy">
          <el-input :model-value="legacyProtocolLabel" disabled />
          <p class="protocol-legacy__hint">
            该协议的模型通道已停用（无对应 Adapter），无法新建模型实例或试跑。请新建一个受支持协议的供应商后迁移。
          </p>
        </div>
        <el-select v-else v-model="form.protocol" style="width: 100%">
          <el-option
            v-for="item in PROTOCOL_OPTIONS"
            :key="item"
            :label="PROTOCOL_LABEL[item] || item"
            :value="item"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="Base URL" required><el-input v-model="form.baseUrl" maxlength="512" placeholder="https://example.com/v1" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      <!-- 新建不再填凭证/API Key；凭证在主从「凭证」弹窗配置。启停在卡片/右栏开关。 -->
    </el-form>
    <template #footer>
      <!-- 编辑态未改任何字段时两个按钮都不可点：没有草稿可丢、也没有改动可存 -->
      <el-button :disabled="!canSubmit" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 供应商新建 / 编辑弹窗。所属域：model。
 * 新建不携带凭证与 API Key；编辑不回显 Key。启停只在开关操作。
 * 协议选项只开放已实现 ACL Adapter 的三类；历史数据若为已停用协议（CUSTOM），
 * 走只读回显 + 提示不可用，禁止重新选择，也禁止静默改写协议值。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useObjectDirty } from '@/composables/useFormDirty'
import type { ProviderCard } from '@/types/model'
import { PROTOCOL_LABEL, PROTOCOL_OPTIONS, isSelectableProtocol } from '@/types/model'

const props = defineProps<{
  modelValue: boolean
  editing: ProviderCard | null
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: Record<string, unknown>, done: () => void]
}>()

const saving = ref(false)
const form = reactive({
  name: '',
  protocol: 'OPENAI_COMPAT' as string,
  baseUrl: '',
  remark: ''
})

/**
 * 弹窗内可编辑字段的脏检查。只含填写 / 选择类字段；
 * 启停（status）不走本弹窗，因此不在 form 里、也不进快照。
 */
const { isDirty, markClean } = useObjectDirty(() => form)

/**
 * 编辑对象是否为「已下线协议」。为真时协议行改只读回显，
 * 并保持 form.protocol 为库中原值（提交时原样回传，不改写）。
 */
const legacyProtocol = computed(() => {
  if (!props.editing) {
    return false
  }
  // 只有编辑态才需要判断：新建永远从可选范围里挑，不可能出现历史协议
  return !isSelectableProtocol(props.editing.protocol)
})

/**
 * @return 已下线协议的展示名，未知码时原样给出，避免显示空白
 */
const legacyProtocolLabel = computed(() => {
  const code = props.editing?.protocol || ''
  return PROTOCOL_LABEL[code] || code
})

/**
 * 取消 / 保存是否可点。
 * 新建态没有「初始值」可比，恒为可点；编辑态未改任何字段时两者都禁用。
 */
const canSubmit = computed(() => !props.editing || isDirty.value || saving.value)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    const current = props.editing
    form.name = current?.name || ''
    // 编辑历史协议时保留原值，避免下拉默认值把 CUSTOM 悄悄刷成 OPENAI_COMPAT
    form.protocol = current?.protocol || 'OPENAI_COMPAT'
    form.baseUrl = current?.baseUrl || ''
    form.remark = current?.remark || ''
    // 打开即把回填值定为干净基线：异步/同步回填都不该被当成用户改动
    markClean()
  }
)

/**
 * 交给页面发请求。编辑时带上当前启停，避免后端把空 status 当成默认启用。
 */
function submit() {
  if (!form.name.trim() || !form.baseUrl.trim() || !form.protocol) {
    ElMessage.warning('请填写名称、协议和 Base URL')
    return
  }
  // 已下线协议只允许原样保存其它字段：新建场景不可能走到这里，编辑场景已在只读模式
  if (props.editing && legacyProtocol.value && form.protocol !== props.editing.protocol) {
    ElMessage.warning('该协议已停用，不能变更协议类型')
    return
  }
  saving.value = true
  const payload: Record<string, unknown> = {
    name: form.name.trim(),
    protocol: form.protocol,
    baseUrl: form.baseUrl.trim(),
    remark: form.remark.trim()
  }
  if (props.editing) {
    payload.status = props.editing.status
  }
  emit('submit', payload, () => {
    saving.value = false
  })
}
</script>

<style scoped>
/* 已下线协议的只读区：输入框禁用态 + 一条灰字说明，不抢主流程视觉 */
.protocol-legacy {
  width: 100%;
}

.protocol-legacy__hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #d97706;
}
</style>
