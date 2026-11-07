<template>
  <el-dialog
    :model-value="modelValue"
    class="platform-confirm"
    width="680px"
    align-center
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <template #header>
      <div class="pc-head">
        <div class="pc-title">{{ title }}</div>
        <div v-if="subtitle" class="pc-subtitle">{{ subtitle }}</div>
      </div>
    </template>

    <!-- 结构化正文：事实用键值行，影响用提示块；不再把长句塞进一段纯文本 -->
    <div v-if="description" class="pc-desc">{{ description }}</div>

    <div v-if="rows.length" class="pc-rows">
      <div v-for="item in rows" :key="item.label + item.value" class="pc-row">
        <span class="pc-k">{{ item.label }}</span>
        <span class="pc-v" :class="`is-${item.tone || 'default'}`">{{ item.value }}</span>
      </div>
    </div>

    <div v-if="notices.length" class="pc-notices">
      <div v-for="(notice, index) in notices" :key="index" class="pc-notice" :class="`is-${notice.type}`">
        <el-icon class="pc-notice-icon">
          <InfoFilled v-if="notice.type === 'info'" />
          <WarningFilled v-else />
        </el-icon>
        <span class="pc-notice-text">{{ notice.text }}</span>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('cancel')">{{ cancelText }}</el-button>
      <el-button :type="danger ? 'danger' : 'primary'" @click="emit('confirm')">{{ confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 结构化二次确认弹窗。所属域：通用 UI。
 * <p>
 * 平台级换绑、取消平台级、受控重建都复用本弹窗：这些操作的正文包含实例名、集合名、双方维度等多条事实，
 * 纯文本对话框换行不可控、宽度也偏窄。这里用「键值行 + 提示块」固定排版：
 * <ul>
 *   <li>键值行左标签右取值，取值超长自动换行（集合名 / 实例名不截断）；</li>
 *   <li>提示块按 info / warning / danger 分色，危险操作与普通确认一眼可辨；</li>
 *   <li>弹窗固定 680px（窄屏收敛到视口内），正文超高时内部滚动，页脚按钮始终可见。</li>
 * </ul>
 * 只负责展示与触发 confirm，不发起任何接口调用；loading 由调用方用 ElLoading 表达。
 */
import { InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import type { ConfirmNotice, ConfirmRow } from '@/types'

withDefaults(
  defineProps<{
    /** 受控显隐；关闭（含 ESC / 右上角）会 emit false，由调用方按「取消」处理 */
    modelValue: boolean
    /** 主标题，一句话说明要做什么 */
    title: string
    /** 副标题，补充上下文（如「危险操作，不可撤销」） */
    subtitle?: string
    /** 可选引导语；多数场景直接靠 rows + notices 表达，不需要长段落 */
    description?: string
    /** 事实键值行（实例、用途、维度、集合名等） */
    rows?: ConfirmRow[]
    /** 提示块：补充说明 / 代价 / 危险后果 */
    notices?: ConfirmNotice[]
    /** true 时确认按钮走 danger 红色，用于不可撤销操作 */
    danger?: boolean
    confirmText?: string
    cancelText?: string
  }>(),
  {
    subtitle: '',
    description: '',
    // 数组型可选 props 必须给默认值，否则模板里的 v-for 需要额外的空值判定
    rows: () => [],
    notices: () => [],
    danger: false,
    confirmText: '确认',
    cancelText: '取消'
  }
)

const emit = defineEmits<{
  /**
   * 用户点击取消。显隐由调用方收口（本组件不自行改 modelValue），
   * 这样「上一轮关闭动画的残留事件」不会误取消刚打开的新确认。
   */
  cancel: []
  /** 用户点击确认按钮（关闭由调用方在同一次交互里完成） */
  confirm: []
}>()
</script>

<style scoped>
/* 标题区自带下分隔线：与正文拉开层次，避免长正文糊成一块 */
.platform-confirm :deep(.el-dialog__header) {
  padding: 18px 20px 12px;
  margin-right: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.platform-confirm :deep(.el-dialog__body) {
  padding: 16px 20px;
  max-height: 62vh;
  overflow-y: auto;
}

.platform-confirm :deep(.el-dialog__footer) {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

/* 弹窗宽度固定 680px，但窄屏（笔记本分屏 / 小窗）不能溢出视口 */
.platform-confirm {
  max-width: calc(100vw - 32px);
}

.pc-title {
  font-size: 16px;
  font-weight: 600;
  line-height: 22px;
  color: var(--el-text-color-primary);
}

.pc-subtitle {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
}

.pc-desc {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 20px;
  color: var(--el-text-color-regular);
}

/* 键值区：标签定宽右对齐，取值占满剩余宽度并允许长串换行 */
.pc-rows {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 10px 14px;
  padding: 12px 14px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}

.pc-row {
  display: contents;
}

.pc-k {
  font-size: 13px;
  line-height: 22px;
  color: var(--el-text-color-secondary);
  text-align: right;
  white-space: nowrap;
}

.pc-v {
  font-size: 13px;
  line-height: 22px;
  color: var(--el-text-color-primary);
  word-break: break-word;
}

.pc-v.is-warning {
  color: var(--el-color-warning);
  font-weight: 600;
}

.pc-v.is-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.pc-notices {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

/* 提示块：左侧色条 + 浅底，比纯色文字更容易区分「说明」和「后果」 */
.pc-notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 20px;
}

.pc-notice-icon {
  margin-top: 2px;
  flex: 0 0 auto;
  font-size: 15px;
}

.pc-notice-text {
  min-width: 0;
  word-break: break-word;
}

.pc-notice.is-info {
  background: var(--el-color-info-light-9);
  color: var(--el-text-color-regular);
}

.pc-notice.is-info .pc-notice-icon {
  color: var(--el-color-info);
}

.pc-notice.is-warning {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
}

.pc-notice.is-warning .pc-notice-icon {
  color: var(--el-color-warning);
}

.pc-notice.is-danger {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger-dark-2);
}

.pc-notice.is-danger .pc-notice-icon {
  color: var(--el-color-danger);
}
</style>
