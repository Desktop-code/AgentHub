<template>
  <div class="wf-node" :class="[`is-${tone}`, runClass, { 'is-selected': selected }]">
    <!-- 除 START 外都有入边锚点；除 END 外都有出边锚点 -->
    <Handle v-if="nodeType !== 'START'" type="target" :position="Position.Left" />
    <div class="wf-node__head">
      <span class="wf-node__type">{{ typeLabel }}</span>
      <span class="wf-node__code">{{ data.code }}</span>
    </div>
    <div class="wf-node__name" :title="displayName">{{ displayName }}</div>
    <div v-if="summary" class="wf-node__summary" :title="summary">{{ summary }}</div>
    <div v-if="runLabel" class="wf-node__run">{{ runLabel }}</div>
    <Handle v-if="nodeType !== 'END'" type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
/**
 * 工作流画布节点视图。所属域：workflow 的前端画布层。
 * <p>VueFlow 的单一自定义节点类型（nodeTypes.workflow）按 data.nodeType 上色与标注；
 * 只在画布内使用，不含编辑逻辑，属性编辑在右侧抽屉。
 * <p>data.runStatus 由试跑抽屉回填，仅影响视觉，不参与草稿保存。
 * <p>data.agentName / agentCode 由编辑页按 agentId 注入，只用于把节点标题显示成真实 Agent 名称；
 * data.toolTitle / toolName 同理服务于 TOOL 节点标题；
 * 画布保存走 buildGraph 的字段白名单，这些展示字段不会写进 graph_json。
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import { summarizeJsonLogic } from '@/utils/jsonLogicSummary'
import type { WorkflowFlowNodeData } from '@/types/workflow'

const props = defineProps<NodeProps<WorkflowFlowNodeData>>()

/** 类型中文名 */
const TYPE_LABEL: Record<string, string> = {
  START: '开始',
  END: '结束',
  AGENT: 'Agent',
  TOOL: '工具',
  CONDITION: '条件',
  PARALLEL_FORK: '并行分叉',
  PARALLEL_JOIN: '并行汇合',
  HUMAN: '人工审批',
  KB_RETRIEVE: '知识检索',
  SKILL_INJECT: 'Skill 注入',
  SUBGRAPH: '子图',
  MAP: '列表映射',
  REDUCE: '列表归约',
  TIMER: '定时等待',
  COMPENSATE: '补偿'
}

/** 类型 → 配色档位（CSS 类后缀） */
const TYPE_TONE: Record<string, string> = {
  START: 'start',
  END: 'end',
  AGENT: 'agent',
  TOOL: 'tool',
  CONDITION: 'cond',
  PARALLEL_FORK: 'fork',
  PARALLEL_JOIN: 'join',
  HUMAN: 'human'
}

const nodeType = computed(() => String(props.data?.nodeType || 'UNKNOWN'))
const typeLabel = computed(() => TYPE_LABEL[nodeType.value] || nodeType.value)
const tone = computed(() => TYPE_TONE[nodeType.value] || 'other')

/**
 * 节点主标题。
 * <p>优先显示绑定资源的真实名称，避免画布上只剩「Agent #30 / 工具 #12」这类无信息文案：
 * AGENT → Agent 名称（如 IT_Ops_Expert）；TOOL → 工具显示名（title 优先，回退技术名）。
 * <p>未绑定或资源已被删除时回退到节点显示名，保证旧草稿仍可读。
 */
const displayName = computed(() => {
  const data = props.data
  if (nodeType.value === 'AGENT' && data.agentName) {
    return String(data.agentName)
  }
  if (nodeType.value === 'TOOL') {
    const toolName = data.toolTitle || data.toolName
    if (toolName) {
      return String(toolName)
    }
  }
  return String(data.name || data.code || '')
})

/** 卡片第三行：只展示最关键的绑定信息，避免节点被撑高 */
const summary = computed(() => {
  const config = props.data?.config || {}
  if (nodeType.value === 'AGENT') {
    // 主标题已经是 Agent 名称，这里补 Agent 编码，方便和主键 / 日志对照
    if (!config.agentId) {
      return '未绑定 Agent'
    }
    return props.data?.agentCode ? String(props.data.agentCode) : '已绑定 Agent'
  }
  if (nodeType.value === 'TOOL') {
    if (!config.toolId) {
      return '未绑定工具'
    }
    // 标题用的是 title，这里补 MCP 原始工具名，便于和工具目录 / 调用日志对照
    return props.data?.toolName ? String(props.data.toolName) : '已绑定工具'
  }
  if (nodeType.value === 'HUMAN') {
    return config.timeoutPolicy ? `超时：${config.timeoutPolicy}` : '未配置超时策略'
  }
  if (nodeType.value === 'PARALLEL_FORK' || nodeType.value === 'PARALLEL_JOIN') {
    return config.forkGroupId ? `组：${config.forkGroupId}` : '未配置 forkGroupId'
  }
  if (nodeType.value === 'CONDITION') {
    // 条件节点直接给出规则摘要（如 `type = refund`），而不是「已配置」这种空信息
    const rule = summarizeJsonLogic(config.condition)
    return rule || '未配置条件表达式'
  }
  return ''
})

/** 节点运行状态中文名；空状态不显示徽标 */
const RUN_LABEL: Record<string, string> = {
  PENDING: '待运行',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  SKIPPED: '已跳过',
  WAITING_HUMAN: '等待人工',
  CANCELLED: '已取消'
}

/** 运行状态 → 视觉档位（对应下方 CSS 的 run-* 类） */
const RUN_TONE: Record<string, string> = {
  PENDING: 'pending',
  RUNNING: 'running',
  SUCCESS: 'success',
  FAILED: 'failed',
  SKIPPED: 'skipped',
  WAITING_HUMAN: 'waiting',
  CANCELLED: 'cancelled'
}

const runStatus = computed(() => String(props.data?.runStatus || ''))
const runLabel = computed(() => RUN_LABEL[runStatus.value] || '')
const runClass = computed(() => {
  const tone = RUN_TONE[runStatus.value]
  return tone ? `run-${tone}` : ''
})
</script>

<style scoped>
.wf-node {
  min-width: 148px;
  max-width: 220px;
  padding: 8px 10px;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-left: 4px solid #94a3b8;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 6px rgb(15 23 42 / 8%);
  font-size: 12px;
  transition: box-shadow 0.15s ease, border-color 0.15s ease;
}

.wf-node.is-selected {
  border-color: #635bff;
  box-shadow: 0 0 0 2px rgb(99 91 255 / 22%);
}

.wf-node__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.wf-node__type {
  font-size: 11px;
  font-weight: 600;
  color: #635bff;
}

.wf-node__code {
  overflow: hidden;
  max-width: 96px;
  color: #9ca3af;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-node__name {
  margin-top: 2px;
  overflow: hidden;
  color: #111827;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-node__summary {
  margin-top: 2px;
  overflow: hidden;
  color: #6b7280;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 类型配色：只改左侧条与类型文字，保持整体克制 */
.wf-node.is-start {
  border-left-color: #10b981;
}

.wf-node.is-start .wf-node__type {
  color: #059669;
}

.wf-node.is-end {
  border-left-color: #0ea5e9;
}

.wf-node.is-end .wf-node__type {
  color: #0284c7;
}

.wf-node.is-agent {
  border-left-color: #635bff;
}

.wf-node.is-tool {
  border-left-color: #f59e0b;
}

.wf-node.is-tool .wf-node__type {
  color: #b45309;
}

.wf-node.is-cond {
  border-left-color: #a855f7;
}

.wf-node.is-cond .wf-node__type {
  color: #7e22ce;
}

.wf-node.is-fork,
.wf-node.is-join {
  border-left-color: #14b8a6;
}

.wf-node.is-fork .wf-node__type,
.wf-node.is-join .wf-node__type {
  color: #0f766e;
}

.wf-node.is-human {
  border-left-color: #ef4444;
}

.wf-node.is-human .wf-node__type {
  color: #dc2626;
}

/* ===== 试跑高亮：只加描边 / 徽标，不改变节点既有类型配色 ===== */
.wf-node.run-running {
  border-color: #635bff;
  box-shadow: 0 0 0 3px rgb(99 91 255 / 18%);
  animation: wf-node-pulse 1.2s ease-in-out infinite;
}

.wf-node.run-success {
  border-color: #10b981;
  box-shadow: 0 0 0 3px rgb(16 185 129 / 16%);
}

.wf-node.run-failed {
  border-color: #ef4444;
  box-shadow: 0 0 0 3px rgb(239 68 68 / 18%);
}

.wf-node.run-waiting {
  border-color: #f59e0b;
  box-shadow: 0 0 0 3px rgb(245 158 11 / 18%);
  animation: wf-node-pulse 1.4s ease-in-out infinite;
}

.wf-node.run-skipped {
  opacity: 0.55;
  filter: grayscale(0.6);
}

.wf-node.run-cancelled {
  border-style: dashed;
  opacity: 0.7;
}

.wf-node__run {
  margin-top: 4px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #eef2ff;
  color: #4338ca;
  font-size: 10px;
  font-weight: 600;
  text-align: center;
}

.wf-node.run-success .wf-node__run {
  background: #ecfdf5;
  color: #047857;
}

.wf-node.run-failed .wf-node__run {
  background: #fef2f2;
  color: #b91c1c;
}

.wf-node.run-waiting .wf-node__run {
  background: #fffbeb;
  color: #b45309;
}

.wf-node.run-skipped .wf-node__run,
.wf-node.run-cancelled .wf-node__run {
  background: #f3f4f6;
  color: #6b7280;
}

/* 运行中 / 等待人工：呼吸提示，让用户一眼看出卡在哪 */
@keyframes wf-node-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 2px rgb(99 91 255 / 14%);
  }
  50% {
    box-shadow: 0 0 0 5px rgb(99 91 255 / 6%);
  }
}
</style>
