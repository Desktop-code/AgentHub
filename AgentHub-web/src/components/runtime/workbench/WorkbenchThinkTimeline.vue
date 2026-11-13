<template>
  <!--
    工作台思考面板：智能网关 → 参与者，一块一步，各自独立的步骤块。
    「参与者」按真实发生顺序平铺：入口专家、方案（打「方案」标）、方案内部各子专家。
    每块自带头像 + 名称 + 自己的内容；块间用浅灰竖线接力，不做成「下一块是上一块的子行」。
    只负责渲染与高度过渡；展开态由调用方（ChatPanel）按流式状态与用户点击决定。
  -->
  <div class="wtt" :class="{ 'is-open': open }">
    <div class="wtt-clip">
      <div class="wtt-panel">
        <div
          v-for="(step, index) in steps"
          :key="step.kind + '-' + index"
          class="wtt-step"
          :class="'is-' + step.kind"
        >
          <!-- 角色头像：网关是独立静态图标，专家是该 Agent 自己的机器人方座 / 首字兜底 -->
          <span
            class="wtt-avatar"
            :class="['is-' + step.kind, { 'is-pending': step.pending }]"
            :style="step.kind === 'expert' ? expertAvatarStyle(step) : undefined"
            :title="step.title"
          >
            <GatewayGlyph v-if="step.kind === 'gateway'" />
            <template v-else>
              <img v-if="step.look?.iconSrc" :src="step.look.iconSrc" :class="{ 'is-custom': step.look.custom }" alt="" />
              <AgentLookArt
                v-else-if="step.look?.art"
                :art="step.look.art"
                :color="step.look.initialColor"
              />
              <span v-else>{{ step.look?.initial || initialOf(step.title) }}</span>
            </template>
          </span>
          <!-- 接力竖线：从本块头像下方跨过块间距，落到下一块头像上 -->
          <span v-if="index < steps.length - 1" class="wtt-relay" aria-hidden="true" />
          <div class="wtt-body">
            <div class="wtt-head">
              <span class="wtt-name">{{ step.title }}</span>
              <span v-if="step.roleLabel" class="wtt-role">{{ step.roleLabel }}</span>
            </div>
            <p v-for="(line, lineIndex) in step.lines" :key="'l-' + lineIndex" class="wtt-line-text">
              {{ line }}
            </p>
            <!-- 专家真实思考 / 工具步：现网 reasoning / tool 事件投影，不做二次加工 -->
            <ol v-if="step.steps.length" class="wtt-thoughts">
              <li
                v-for="(item, itemIndex) in step.steps"
                :key="itemIndex"
                class="wtt-thought"
                :class="'is-' + item.type"
              >
                <span v-if="item.title && item.type !== 'reasoning'" class="wtt-thought-title">{{ item.title }}</span>
                <pre v-if="item.type === 'tool_call'" class="wtt-pre">{{ item.text }}</pre>
                <pre v-else-if="item.type === 'tool_result'" class="wtt-pre is-result">{{ item.text }}</pre>
                <p v-else class="wtt-reasoning">{{ item.text }}</p>
              </li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作台思考时间线渲染组件。所属域：runtime（前端）。
 *
 * <p>纯展示：步骤数据由 `buildThinkTimeline` 算好传入，本组件不解析事件、不请求接口。
 * 每一步各自成块（头像列 + 内容列），块数随真实参与者变化（两级路由时会有「方案 + 各子专家」多块）；
 * 块间用绝对定位的竖线接力；展开/收起用 `grid-template-rows` 过渡，流式正文增长不触发动画抖动。
 */
import { initialOf } from '@/constants/agentLooks'
import AgentLookArt from '@/components/agent/AgentLookArt.vue'
import GatewayGlyph from '@/components/runtime/workbench/GatewayGlyph.vue'
import type { ThinkTimelineStep } from '@/components/runtime/workbench/workbenchThinkTimeline'

defineProps<{
  /** 时间线步骤（网关 + 若干专家） */
  steps: ThinkTimelineStep[]
  /** 是否展开；收起时高度归零但保留 DOM，重新展开无需重建 */
  open: boolean
}>()

/**
 * 专家头像样式：白底方座不变色，首字兜底时用该套线稿色。
 *
 * @param step 专家步骤
 * @return 内联样式
 */
function expertAvatarStyle(step: ThinkTimelineStep) {
  return {
    background: step.look?.color || '#fff',
    color: step.look?.initialColor || '#635bff'
  }
}
</script>

<style scoped>
.wtt {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.28s ease;
}

/* 展开：行高由 0fr → 1fr，内容高度变化本身不参与过渡，流式增量不会跟着抽动 */
.wtt.is-open {
  grid-template-rows: 1fr;
}

.wtt-clip {
  min-height: 0;
  overflow: hidden;
}

.wtt-panel {
  display: flex;
  flex-direction: column;
  margin: 2px 0 6px;
  padding: 12px 12px 12px 10px;
  border-radius: 10px;
  background: #f9fafb;
  /* 思考很长时面板内滚动，避免把下方正文顶到很远 */
  max-height: 360px;
  overflow-y: auto;
}

/* 每块自成一行：头像列 + 内容列；块与块之间留出间距，视觉上不互相嵌套 */
.wtt-step {
  position: relative;
  padding-left: 34px;
}

.wtt-step + .wtt-step {
  margin-top: 16px;
}

.wtt-avatar {
  position: absolute;
  top: 0;
  left: 0;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  overflow: hidden;
  font-size: 11px;
  font-weight: 650;
}

/* 专家头像沿用机器人方座：白底 + 内描边 */
.wtt-avatar.is-expert {
  border-radius: 6px;
  box-shadow: inset 0 0 0 1px #e5e7eb;
}

.wtt-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 网关图标：中性静态描边，与专家形象明显区分 */
.wtt-avatar.is-gateway {
  border-radius: 6px;
  background: #fff;
  box-shadow: inset 0 0 0 1px #e5e7eb;
  color: #6b7280;
}

/* 进行中：头像外圈轻轻呼吸，不做进度条与跑马灯 */
.wtt-avatar.is-pending::after {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 8px;
  border: 1px solid rgba(99, 91, 255, 0.35);
  animation: wtt-breathe 1.6s ease-in-out infinite;
}

@keyframes wtt-breathe {
  0%,
  100% {
    opacity: 0.15;
  }

  50% {
    opacity: 0.7;
  }
}

/* 接力竖线：从本块头像下沿穿过块间距，接到下一块头像上沿 */
.wtt-relay {
  position: absolute;
  top: 26px;
  bottom: -16px;
  left: 11.5px;
  width: 1px;
  background: #e3e6ec;
}

.wtt-body {
  min-width: 0;
}

.wtt-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 24px;
}

.wtt-name {
  min-width: 0;
  overflow: hidden;
  color: #374151;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 网关角色标记：说明这一块只是「判定」，不产生正文 */
.wtt-role {
  flex: none;
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef0f4;
  color: #9ca3af;
  font-size: 10px;
  font-weight: 500;
}

.wtt-line-text {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.wtt-thoughts {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}

.wtt-thought + .wtt-thought {
  margin-top: 6px;
}

.wtt-thought-title {
  display: block;
  margin-bottom: 2px;
  color: #a3a3a3;
  font-size: 11px;
  font-weight: 600;
}

.wtt-reasoning {
  margin: 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.wtt-pre {
  max-height: 200px;
  margin: 0;
  padding: 6px 8px;
  overflow: auto;
  border-radius: 6px;
  background: #f3f4f8;
  color: #7b8291;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
}

.wtt-pre.is-result {
  background: #f7f8fb;
}
</style>
