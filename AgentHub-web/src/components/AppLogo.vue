<template>
  <div class="app-logo" role="img" :aria-label="ariaLabel">
    <!-- 方案 1：极简暗黑 · 大中枢 + 不对称三角 -->
    <svg
      v-if="variant === 'hub'"
      class="mark"
      width="32"
      height="32"
      viewBox="0 0 32 32"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <rect width="32" height="32" rx="8" fill="#111827" />
      <g
        fill="none"
        stroke="#fff"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <line x1="13" y1="8" x2="7" y2="24" />
        <line x1="13" y1="8" x2="26" y2="21" />
        <line x1="7" y1="24" x2="26" y2="21" />
      </g>
      <circle cx="13" cy="8" r="3.5" fill="#fff" />
      <circle cx="7" cy="24" r="2" fill="#fff" />
      <circle cx="26" cy="21" r="2" fill="#fff" />
    </svg>

    <!-- 方案 2：抽象字母 A · 霓虹青节点 -->
    <svg
      v-else-if="variant === 'agent'"
      class="mark"
      width="32"
      height="32"
      viewBox="0 0 32 32"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <defs>
        <linearGradient :id="gradA" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#1E1B4B" />
          <stop offset="100%" stop-color="#312E81" />
        </linearGradient>
        <filter :id="glowA" x="-40%" y="-40%" width="180%" height="180%">
          <feGaussianBlur stdDeviation="1.1" result="blur" />
          <feMerge>
            <feMergeNode in="blur" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>
      <rect width="32" height="32" rx="8" :fill="`url(#${gradA})`" />
      <g
        fill="none"
        stroke="#A5B4FC"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M8 24 L16 7 L24 24" />
        <line x1="11" y1="16" x2="21" y2="16" />
      </g>
      <g :filter="`url(#${glowA})`" fill="#22D3EE">
        <circle cx="16" cy="7" r="2.5" />
        <circle cx="8" cy="24" r="2.5" />
        <circle cx="24" cy="24" r="2.5" />
      </g>
    </svg>

    <!-- 方案 3：∞ 链路 · ReAct 流转 -->
    <svg
      v-else
      class="mark mark-loop"
      width="32"
      height="32"
      viewBox="0 0 32 32"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <rect x="0.5" y="0.5" width="31" height="31" rx="7.5" fill="#F8FAFC" stroke="#E2E8F0" />
      <path
        d="M6 16 C6 10.5 10 8 16 16 C22 24 26 21.5 26 16 C26 10.5 22 8 16 16 C10 24 6 21.5 6 16 Z"
        fill="none"
        stroke="#635BFF"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <circle cx="9.5" cy="12.5" r="2.2" fill="#635BFF" />
      <circle cx="16" cy="16" r="2.2" fill="#635BFF" />
      <circle cx="22.5" cy="12.5" r="2.2" fill="#635BFF" />
    </svg>

    <span v-if="showText" class="wordmark">
      <span class="word-agent">Agent</span>
      <span class="word-hub">Hub</span>
    </span>
  </div>
</template>

<script setup lang="ts">
/**
 * 品牌 Logo（可切换内联 SVG 角标 + 字标）。所属域：前端壳。
 * variant：hub 暗黑中枢 / agent 抽象 A / loop ∞ 链路；外框 32×32 圆角与字标排版固定。
 */
import { useId } from 'vue'

export type AppLogoVariant = 'hub' | 'agent' | 'loop'

withDefaults(
  defineProps<{
    /** 是否展示右侧字标 */
    showText?: boolean
    /**
     * 角标风格：
     * - hub：极简暗黑中枢
     * - agent：字母 A + 霓虹节点
     * - loop：∞ 链路 / ReAct
     */
    variant?: AppLogoVariant
  }>(),
  {
    showText: true,
    variant: 'hub'
  }
)

const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '')
const gradA = `ahLogoGradA-${uid}`
const glowA = `ahLogoGlowA-${uid}`
const ariaLabel = 'AgentHub'
</script>

<style scoped>
.app-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  user-select: none;
}

.mark {
  flex: none;
  display: block;
  border-radius: 8px;
}

.wordmark {
  display: inline-flex;
  align-items: baseline;
  gap: 0.2em;
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0.01em;
  color: #1e293b;
}

.word-hub {
  color: #635bff;
}
</style>
