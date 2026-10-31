<template>
  <el-dialog :model-value="modelValue" title="连通测试" width="460px" @close="emit('update:modelValue', false)">
    <el-descriptions v-if="result" :column="1" border>
      <el-descriptions-item label="结果">{{ result.success ? '成功' : '失败' }}</el-descriptions-item>
      <el-descriptions-item label="耗时">{{ result.latencyMs }} ms</el-descriptions-item>
      <el-descriptions-item label="协议">{{ result.protocol }}</el-descriptions-item>
      <el-descriptions-item label="用途">{{ result.capability }}</el-descriptions-item>
      <el-descriptions-item label="说明">{{ result.message }}</el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 探活结果。所属域：model。只展示 success、耗时和说明，不出现 Key。
 */
import type { ConnectivityResult } from '@/types/model'

defineProps<{
  modelValue: boolean
  result: ConnectivityResult | null
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
}>()
</script>
