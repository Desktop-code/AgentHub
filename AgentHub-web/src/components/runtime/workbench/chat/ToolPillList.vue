<template>
  <!--
    工具调用胶囊列表：同名调用已由上层聚合成一条。
    调用中蓝、有失败红、全成功绿；可点的成功/失败胶囊能展开逐次入参出参。
    工作流节点用 readonly：快照没有入参出参，不伪造可点详情。
  -->
  <div class="wb-tool-pills" :class="{ 'is-readonly': readonly }">
    <template v-for="pill in items" :key="pill.key">
      <component
        :is="readonly || pill.state === 'running' || !pill.detail ? 'span' : 'button'"
        class="wb-tool-pill"
        :class="'is-' + pill.state"
        :type="readonly || pill.state === 'running' || !pill.detail ? undefined : 'button'"
        :aria-expanded="
          readonly || pill.state === 'running' || !pill.detail ? undefined : open[pill.key] === true
        "
        @click.stop.prevent="
          readonly || pill.state === 'running' || !pill.detail ? undefined : emit('toggle', pill.key)
        "
      >
        <span class="wb-tool-icon" aria-hidden="true">
          <template v-if="pill.state === 'running'">◠</template>
          <template v-else-if="pill.state === 'done'">✓</template>
          <template v-else>✕</template>
        </span>
        <span class="wb-tool-label">{{ pill.label }}</span>
        <span
          v-if="!readonly && pill.state !== 'running' && pill.detail"
          class="wb-tool-more"
        >
          {{ open[pill.key] === true ? '收起' : '详情' }}
        </span>
      </component>
      <pre
        v-if="!readonly && pill.state !== 'running' && pill.detail && open[pill.key] === true"
        class="wb-tool-detail"
      >{{ pill.detail }}</pre>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具调用胶囊列表。所属域：runtime（前端展示）。
 *
 * <p>职责：只画胶囊与可展开详情；聚合规则、中文名替换、是否可点都在调用方决定。
 * 单专家卡与工作流节点共用这一份，避免两处胶囊状态色 / 间距慢慢长歪。
 */
import type { ToolPillItem } from '@/components/runtime/workbench/chat/toolPill'

withDefaults(
  defineProps<{
    /** 要画的胶囊 */
    items: ToolPillItem[]
    /** 是否只读（工作流节点快照没有入参出参） */
    readonly?: boolean
    /** 展开态（键 = 胶囊 key）；只读时不使用 */
    open?: Record<string, boolean>
  }>(),
  { readonly: false, open: () => ({}) }
)

const emit = defineEmits<{
  /** 点了一次「详情 / 收起」 */
  toggle: [key: string]
}>()
</script>

<style scoped>
.wb-tool-pills {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 10px;
}

.wb-tool-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  max-width: 100%;
  padding: 6px 12px;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  background: #f0fdf4;
  color: #166534;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

/* 调用中：蓝底 + 图标旋转，明确「还在跑」 */
.wb-tool-pill.is-running {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  cursor: default;
}

.wb-tool-pill.is-running .wb-tool-icon {
  animation: wb-tool-spin 1s linear infinite;
}

/* 失败：红底；原因已写在胶囊上，点开可看细节 */
.wb-tool-pill.is-error {
  border-color: #fecaca;
  background: #fef2f2;
  color: #991b1b;
}

.wb-tool-icon {
  flex: none;
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  font-size: 12px;
  line-height: 1;
}

.wb-tool-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-tool-more {
  flex: none;
  margin-left: 4px;
  color: #9ca3af;
  font-size: 10px;
}

/* 工具详情：深色代码块，与演示的 .tool-detail 一致 */
.wb-tool-detail {
  margin: 0;
  padding: 10px 14px;
  border-radius: 6px;
  background: #1e293b;
  color: #e2e8f0;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 只读：工作流节点快照没有入参出参，不能看起来像可点 */
.wb-tool-pills.is-readonly .wb-tool-pill {
  cursor: default;
}

/* 工作台：完成态从整块绿底收到白底 + 绿字，只保留状态语义，不抢结果 */
.chat.is-workbench .wb-tool-pill:not(.is-running):not(.is-error) {
  background: var(--ah-surface, #fff);
  border-color: var(--ah-border-soft, #f2f6fc);
  color: #047857;
}

@keyframes wb-tool-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
