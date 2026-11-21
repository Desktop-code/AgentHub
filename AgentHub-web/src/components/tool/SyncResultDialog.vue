<template>
  <el-dialog
    :model-value="modelValue"
    title="同步结果"
    width="480px"
    @close="emit('update:modelValue', false)"
  >
    <el-descriptions v-if="result" :column="1" border>
      <el-descriptions-item label="结果">{{ result.success ? '成功' : '失败' }}</el-descriptions-item>
      <el-descriptions-item label="耗时">{{ result.latencyMs }} ms</el-descriptions-item>
      <el-descriptions-item label="新增">{{ result.created }}</el-descriptions-item>
      <el-descriptions-item label="更新">{{ result.updated }}</el-descriptions-item>
      <el-descriptions-item label="下架">{{ result.retired }}</el-descriptions-item>
      <el-descriptions-item label="说明">{{ result.message }}</el-descriptions-item>
    </el-descriptions>

    <!--
      同步成功后不能停在「只有关闭」的死路：新增项默认停用，用户在这里最需要知道
      「下一步去哪把工具放进白名单」。现网只有「按源批量启停」接口（会把该源全部项
      置为启用，不是只启用本次新增），照此命名会撒谎，所以这里给「查看工具项」，
      由页面落到工具项列表并筛出停用态。
    -->
    <p v-if="result?.success" class="sync-next">
      <template v-if="result.created > 0">
        本次新增 {{ result.created }} 项默认<strong>停用</strong>，需在工具项列表里逐行启用后才会被 Agent 调用。
      </template>
      <template v-else>
        本次没有新增工具项；已下架或更新的项状态保持不变。
      </template>
    </p>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <!-- 成功且能看工具项时才给下一步；否则点了只会撞 403，比不给更差 -->
      <el-button v-if="result?.success && canViewItems" type="primary" @click="onViewItems">
        查看工具项
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * MCP 同步结果。所属域：tool。
 * 新增项默认停用；「下架」是本次按远端清单对齐时逻辑删除的条数（含重复行），说明里不应出现鉴权头。
 * 弹窗不是终点：成功后提供「查看工具项」，把用户交回工具项列表继续启用。
 */
import type { SyncResult } from '@/types/tool'

defineProps<{
  modelValue: boolean
  result: SyncResult | null
  /**
   * 当前账号是否有工具项查看权限。
   * 无权限时「查看工具项」点了只会撞 403，比不给入口更差，故由页面传入后隐藏。
   */
  canViewItems?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** 点「查看工具项」：由页面关闭弹窗、切到工具项列表并落筛选 */
  viewItems: []
}>()

/**
 * 下一步交给页面处理：弹窗只关自己，不直接操作列表与查询条件。
 */
function onViewItems() {
  emit('update:modelValue', false)
  emit('viewItems')
}
</script>

<style scoped>
/* 下一步说明：紧贴结果表下方，12px 可读灰；「停用」二字加重以示要点 */
.sync-next {
  margin: 12px 0 0;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}

.sync-next strong {
  color: var(--ah-text, #1f2937);
  font-weight: 600;
}
</style>
