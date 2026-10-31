<template>
  <el-dialog :model-value="modelValue" :title="editing ? '编辑 MCP 源' : '新建 MCP'" width="520px" @close="emit('update:modelValue', false)">
    <el-form label-width="96px">
      <el-form-item label="名称" required><el-input v-model="form.name" maxlength="64" /></el-form-item>
      <el-form-item label="传输" required>
        <el-select v-model="form.transport" style="width: 100%">
          <el-option label="SSE（HTTP+SSE）" value="SSE" />
          <el-option label="HTTP（Streamable HTTP）" value="HTTP" />
        </el-select>
      </el-form-item>
      <el-form-item :label="urlLabel" required>
        <el-input v-model="form.baseUrl" maxlength="512" :placeholder="urlPlaceholder" />
      </el-form-item>
      <el-form-item :label="editing ? '重置鉴权头' : '鉴权头'">
        <el-input
          v-model="form.authHeader"
          type="password"
          show-password
          maxlength="180"
          :placeholder="editing ? '留空则不修改' : '可选，如 Authorization: Bearer token'"
        />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      <!-- 启停在卡片开关上改，弹窗不再放状态栏 -->
      <el-alert
        v-if="probeHint"
        class="probe-hint"
        :title="probeHint"
        :type="probeOk ? 'success' : 'error'"
        show-icon
        :closable="false"
      />
    </el-form>
    <template #footer>
      <!-- 编辑态未改任何字段时取消 / 保存都不可点；新建态恒可点 -->
      <el-button :loading="probing" @click="probe">测试链接</el-button>
      <el-button :disabled="!canSubmit" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * MCP 源新建 / 编辑弹窗。所属域：tool。
 * 传输可选 SSE（旧 HTTP+SSE）或 HTTP（Streamable HTTP）；默认 SSE。
 * 编辑不回显鉴权头；填了内容才在保存后走重置接口。启停只在卡片开关操作。
 * 「测试链接」走 /probe：不写库；编辑留空鉴权头时带 sourceId 用库存密文。
 * 保存成功后页面只提示「已保存」，不回显鉴权头明文。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { probeSource } from '@/api/tool'
import { useObjectDirty } from '@/composables/useFormDirty'
import type { ToolSource } from '@/types/tool'

const props = defineProps<{
  modelValue: boolean
  editing: ToolSource | null
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: Record<string, unknown>, done: () => void]
}>()

const saving = ref(false)
const probing = ref(false)
const probeHint = ref('')
const probeOk = ref(false)
const form = reactive({
  name: '',
  transport: 'SSE',
  baseUrl: '',
  authHeader: '',
  remark: ''
})

/**
 * 弹窗内可编辑字段的脏检查。
 * 「重置鉴权头」编辑态下留空表示不修改，空串与打开时的空串相等，因此不会误判脏。
 */
const { isDirty, markClean } = useObjectDirty(() => form)

/** 取消 / 保存是否可点：新建态恒可点，编辑态要求有改动 */
const canSubmit = computed(() => !props.editing || isDirty.value || saving.value)

/** 按传输切换地址标签，避免用户把 Streamable HTTP 端点误当成 SSE 路径。 */
const urlLabel = computed(() => (form.transport === 'HTTP' ? 'HTTP 地址' : 'SSE 地址'))

const urlPlaceholder = computed(() =>
  form.transport === 'HTTP' ? 'http://127.0.0.1:3000/mcp' : 'http://127.0.0.1:3000/sse'
)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    const current = props.editing
    form.name = current?.name || ''
    form.transport = current?.transport === 'HTTP' ? 'HTTP' : 'SSE'
    form.baseUrl = current?.baseUrl || ''
    form.authHeader = ''
    form.remark = current?.remark || ''
    probeHint.value = ''
    probeOk.value = false
    probing.value = false
    // 回填完成即定为干净基线：打开弹窗不该显示可保存
    markClean()
  }
)

/**
 * 探测 MCP 可达：新建用表单地址与鉴权头；编辑头留空则复用库存密文。
 * 失败也尽量露出后端脱敏文案，勿只写「探测请求失败」。
 */
async function probe() {
  if (!form.baseUrl.trim()) {
    ElMessage.warning(`请先填写${urlLabel.value}`)
    return
  }
  if (!/^https?:\/\//i.test(form.baseUrl.trim())) {
    ElMessage.warning(`${urlLabel.value}需要以 http 或 https 开头`)
    return
  }
  probing.value = true
  probeHint.value = ''
  try {
    const payload: {
      sourceId?: number
      baseUrl: string
      authHeader?: string
      transport: string
    } = {
      baseUrl: form.baseUrl.trim(),
      transport: form.transport
    }
    if (form.authHeader.trim()) {
      payload.authHeader = form.authHeader.trim()
    } else if (props.editing) {
      payload.sourceId = props.editing.id
    }
    const { data } = await probeSource(payload)
    const result = data.data
    probeOk.value = !!result?.success
    const latency = result?.latencyMs != null ? `（${result.latencyMs} ms）` : ''
    probeHint.value = (result?.message || (probeOk.value ? '可达' : '探测失败')) + latency
  } catch (err: unknown) {
    probeOk.value = false
    // 拦截器已弹过业务 msg；此处把同一文案落到弹窗内，便于对照失败原因
    const body = err as { msg?: string; message?: string } | undefined
    const hint =
      form.transport === 'HTTP'
        ? '探测请求失败（请确认 HTTP 地址可达且符合 MCP Streamable HTTP）'
        : '探测请求失败（请确认 SSE 地址可达且符合 MCP SSE 协议）'
    probeHint.value = body?.msg || body?.message || hint
  } finally {
    probing.value = false
  }
}

/** 交给页面发请求。鉴权头单独带出，页面决定是否调用重置。 */
function submit() {
  if (!form.name.trim() || !form.baseUrl.trim()) {
    ElMessage.warning(`请填写名称和${urlLabel.value}`)
    return
  }
  if (!/^https?:\/\//i.test(form.baseUrl.trim())) {
    ElMessage.warning(`${urlLabel.value}需要以 http 或 https 开头`)
    return
  }
  saving.value = true
  const payload: Record<string, unknown> = {
    name: form.name.trim(),
    type: 'MCP',
    baseUrl: form.baseUrl.trim(),
    remark: form.remark.trim(),
    transport: form.transport
  }
  if (form.authHeader.trim()) {
    payload.authHeader = form.authHeader.trim()
  }
  emit('submit', payload, () => {
    saving.value = false
  })
}
</script>

<style scoped>
.probe-hint {
  margin-top: 4px;
}
</style>
