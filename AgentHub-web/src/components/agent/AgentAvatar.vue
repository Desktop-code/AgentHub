<template>
  <!-- 白底圆角方座 + 座上线稿；颜色只作用于线稿，背景恒为白色 -->
  <span
    class="agent-avatar"
    :class="[`is-${sizeClass}`, { 'is-custom': view.isCustom, 'is-robot': view.isRobot }]"
    :style="boxStyle"
    :title="name || undefined"
  >
    <img v-if="view.imgSrc" :src="view.imgSrc" :alt="name || 'Agent 形象'" />
    <AgentLookArt v-else-if="view.art" :art="view.art" :color="view.strokeColor" />
    <span v-else class="agent-avatar__initial" :style="{ color: view.initialColor }">{{ view.initial }}</span>
  </span>
</template>

<script setup lang="ts">
/**
 * Agent 形象预览。所属域：agent（前端展示组件）。
 * <p>
 * 只读渲染：custom 显示上传图；preset 内联机器人线稿（白底方座，颜色只改线稿）；
 * 两者都没有时用名称首字兜底，底座同为白底方座。
 * 形状为圆角方形（半径 = 边长 × 25%），与设计稿的 72px 底座（18px 圆角）等比，不做正圆裁切。
 * 不关心表单与保存链路。
 */
import { computed } from 'vue'
import AgentLookArt from '@/components/agent/AgentLookArt.vue'
import { AGENT_AVATAR_BORDER, resolveAgentAvatar } from '@/constants/agentLooks'
import type { AvatarConfig } from '@/types/agent'

const props = withDefaults(
  defineProps<{
    /** Agent 名称：首字兜底用 */
    name?: string | null
    /** 形象配置；null 表示未配置 */
    config?: AvatarConfig | null
    /** 边长（px）：列表 40 / 详情 60 / 弹窗 64 */
    size?: number
  }>(),
  { name: '', config: null, size: 48 }
)

/** 渲染视图：上传图 / 线稿图元 / 线稿色 / 底座色 / 首字 */
const view = computed(() => resolveAgentAvatar(props.config, props.name))

/** 尺寸档位：只用于首字字号的微调 */
const sizeClass = computed(() => (props.size >= 64 ? 'lg' : props.size <= 32 ? 'sm' : 'md'))

/** 外框样式：方形死锁尺寸，圆角随尺寸等比（25%），白底 + 极细描边保证在白色卡片上有轮廓 */
const boxStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  borderRadius: `${Math.round(props.size * 0.25)}px`,
  background: view.value.background,
  boxShadow: `inset 0 0 0 1px ${AGENT_AVATAR_BORDER}`
}))
</script>

<style scoped>
.agent-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  overflow: hidden;
  color: #fff;
  font-weight: 600;
  line-height: 1;
  user-select: none;
}

.agent-avatar.is-sm {
  font-size: 13px;
}

.agent-avatar.is-md {
  font-size: 18px;
}

.agent-avatar.is-lg {
  font-size: 24px;
}

/* 上传图铺满方座；线稿由 AgentLookArt 内部撑满，自带白底与描边 */
.agent-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.agent-avatar__initial {
  letter-spacing: 0.5px;
}
</style>
