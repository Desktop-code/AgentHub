<template>
  <el-dialog :model-value="modelValue" title="同步结果" width="460px" @close="emit('update:modelValue', false)">
    <el-descriptions v-if="result" :column="1" border>
      <el-descriptions-item label="结果">{{ result.success ? '成功' : '失败' }}</el-descriptions-item>
      <el-descriptions-item label="耗时">{{ result.latencyMs }} ms</el-descriptions-item>
      <el-descriptions-item label="新增">{{ result.created }}</el-descriptions-item>
      <el-descriptions-item label="更新">{{ result.updated }}</el-descriptions-item>
      <el-descriptions-item label="下架">{{ result.retired }}</el-descriptions-item>
      <el-descriptions-item label="说明">{{ result.message }}</el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * MCP 同步结果。所属域：tool。
 * 新增项默认停用；「下架」是本次按远端清单对齐时逻辑删除的条数（含重复行），说明里不应出现鉴权头。
 */
import type { SyncResult } from '@/types/tool'

defineProps<{
  modelValue: boolean
  result: SyncResult | null
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
}>()
</script>
