<template>
  <el-drawer
    :model-value="modelValue"
    class="trial-drawer"
    direction="rtl"
    size="calc(100vw - var(--ah-sidebar) - 324px)"
    append-to-body
    :modal="true"
    :close-on-click-modal="true"
    :close-on-press-escape="true"
    :lock-scroll="true"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="trial-head">
        <span class="trial-name">{{ shownName }}</span>
        <el-tag
          v-if="modeLabel"
          size="small"
          effect="plain"
          :type="shownMode === 'ROUTER' ? 'warning' : shownMode === 'PLAN_AND_EXECUTE' ? 'success' : 'primary'"
        >
          {{ modeLabel }}
        </el-tag>
      </div>
    </template>
    <TrialPanel
      v-if="modelValue"
      :key="agentId || 'none'"
      variant="drawer"
      :agent-id="agentId"
      :run-mode="shownMode"
    />
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * Agent 试跑抽屉。所属域：runtime。
 * 列表和详情共用。点遮罩或右上角关闭，点抽屉内部不关闭。对话仍走 TrialPanel，不新开路由。
 * 标题只显示 Agent 名称，运行模式标签跟在名称后面。
 * 宽度对齐主从详情区左缘（侧栏 240 + 主区内边距 28 + 左轨 280 + 间距 16）。
 */
import { computed, ref, watch } from 'vue'
import { getAgent } from '@/api/agent'
import TrialPanel from '@/components/runtime/TrialPanel.vue'
import { runModeLabel } from '@/composables/agentViewFlip'

const props = defineProps<{
  modelValue: boolean
  /** 未选中时抽屉仍可开，对话区提示先选 Agent */
  agentId?: number
  agentName?: string
  runMode?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
}>()

const fetchedName = ref('')
const fetchedMode = ref('')

const shownName = computed(() => props.agentName || fetchedName.value)
const shownMode = computed(() => props.runMode || fetchedMode.value)
const modeLabel = computed(() => (shownMode.value ? runModeLabel(shownMode.value) : ''))

watch(
  () => [props.modelValue, props.agentId, props.agentName, props.runMode] as const,
  async ([visible, id, name, mode]) => {
    if (!visible) {
      return
    }
    // 调用方已经带了名称和模式，不必再打详情
    if (!id || (name && mode)) {
      fetchedName.value = ''
      fetchedMode.value = ''
      return
    }
    try {
      const { data } = await getAgent(id)
      if (props.agentId !== id) {
        return
      }
      fetchedName.value = data.data?.name || ''
      fetchedMode.value = data.data?.runMode || ''
    } catch {
      // 名称没取到就留空，发送仍按 agentId 走现有试跑接口
      fetchedName.value = ''
      fetchedMode.value = ''
    }
  },
  { immediate: true }
)
</script>

<style>
/* 类名落在遮罩或抽屉根上都能选中正文。正文自己滚动，遮罩点击仍由组件关闭。 */
.trial-drawer .el-drawer__header,
.trial-drawer.el-drawer .el-drawer__header {
  align-items: center;
  margin-bottom: 0;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--ah-border, #e5e7eb);
}

/* 窄屏别把左轨挤没；至少留侧栏宽度 */
@media (max-width: 1100px) {
  .trial-drawer.el-drawer {
    width: min(96vw, calc(100vw - 64px)) !important;
  }
}

.trial-drawer .trial-head,
.trial-drawer.el-drawer .trial-head {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-right: 8px;
}

.trial-drawer .trial-name,
.trial-drawer.el-drawer .trial-name {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 16px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trial-drawer .el-drawer__body,
.trial-drawer.el-drawer .el-drawer__body {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
