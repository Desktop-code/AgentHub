<template>
  <el-dialog :model-value="modelValue" title="添加子 Agent" width="560px" @close="emit('update:modelValue', false)">
    <el-form label-width="88px">
      <el-form-item label="子 Agent" required>
        <el-select v-model="form.childAgentId" filterable placeholder="只能选择已启用的 ReAct" style="width: 100%">
          <el-option v-for="item in options" :key="item.id" :label="`${item.name} · ${item.code}`" :value="item.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="意图" required>
        <el-input
          v-model="form.intent"
          maxlength="255"
          type="textarea"
          :rows="2"
          placeholder="白话描述该子 Agent 负责什么，供判路由识别"
        />
      </el-form-item>
      <el-collapse v-model="advancedOpen" class="advanced">
        <el-collapse-item title="高级（一般不用改）" name="adv">
          <el-form-item label="路由键">
            <el-input v-model="form.routeKey" maxlength="64" placeholder="留空则系统按子 Agent code 生成" />
            <div class="hint">同父下唯一；改后须与判路由输出一致</div>
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="form.sortOrder" :min="0" :max="10000" controls-position="right" />
            <div class="hint">留空则同父 max+10；无子从 10 起</div>
          </el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <el-empty v-if="loaded && !options.length" description="没有可添加的 ReAct Agent" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">添加</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 选择一层 ReAct 子 Agent。所属域：agent。
 * 主路径只要求子 Agent + 意图；route_key / sort 默认由后端生成，高级里可选手填。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pageAgents } from '@/api/agent'
import type { AgentCard } from '@/types/agent'

const props = defineProps<{
  modelValue: boolean
  parentId: number
  boundIds: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [
    payload: { childAgentId: number; routeKey?: string; intent: string; sortOrder?: number },
    done: () => void
  ]
}>()

const saving = ref(false)
const loaded = ref(false)
const advancedOpen = ref<string[]>([])
const options = ref<AgentCard[]>([])
const form = reactive({
  childAgentId: undefined as number | undefined,
  routeKey: '',
  intent: '',
  /** undefined = 交给后端 max+10；高级里点过数字后才有值 */
  sortOrder: undefined as number | undefined
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.childAgentId = undefined
    form.routeKey = ''
    form.intent = ''
    form.sortOrder = undefined
    advancedOpen.value = []
    saving.value = false
    load()
  }
)

async function load() {
  loaded.value = false
  const { data } = await pageAgents({ current: 1, size: 100, runMode: 'REACT', status: 1 })
  const bound = new Set(props.boundIds)
  options.value = (data.data?.records || []).filter(
    (item) => item.id !== props.parentId && item.runMode === 'REACT' && item.status === 1 && !bound.has(item.id)
  )
  loaded.value = true
}

function submit() {
  if (!form.childAgentId) {
    ElMessage.warning('请选择子 Agent')
    return
  }
  const intent = form.intent.trim()
  if (!intent) {
    ElMessage.warning('请填写意图')
    return
  }
  saving.value = true
  const payload: { childAgentId: number; routeKey?: string; intent: string; sortOrder?: number } = {
    childAgentId: form.childAgentId,
    intent
  }
  // 高级未填则不传，由后端补全。
  const key = form.routeKey.trim()
  if (key) {
    payload.routeKey = key
  }
  if (form.sortOrder != null) {
    payload.sortOrder = form.sortOrder
  }
  emit('submit', payload, () => {
    saving.value = false
  })
}
</script>

<style scoped>
.advanced {
  margin-top: 4px;
  border: none;
}
.advanced :deep(.el-collapse-item__header) {
  height: 36px;
  line-height: 36px;
  font-size: 13px;
  color: #6b7280;
  border: none;
}
.advanced :deep(.el-collapse-item__wrap) {
  border: none;
}
.hint {
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.4;
}
</style>
