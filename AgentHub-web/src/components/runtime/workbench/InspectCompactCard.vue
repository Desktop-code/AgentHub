<template>
  <!--
    运行中的执行明细紧凑状态卡。所属域：runtime（前端展示）。

    它替代「运行中就弹出整张泳道」：只回答「现在跑到哪」——标题（并行执行中 / N 个任务）
    + 已耗时 + 每个参与者的当前动作。跑完后由外层换成完整泳道，本卡不再展示。
    本卡只读，不发请求、不落库；点击 X 由外层收起。
  -->
  <section class="inspect-compact">
    <header class="inspect-compact__head">
      <el-icon class="inspect-compact__spin is-loading"><Loading /></el-icon>
      <span class="inspect-compact__title" :title="title">{{ title }}</span>
      <span v-if="meta" class="inspect-compact__meta">{{ meta }}</span>
      <span class="inspect-compact__spacer" />
      <button
        type="button"
        class="inspect-compact__close"
        aria-label="收起执行明细"
        title="收起"
        @click="emit('close')"
      >
        <el-icon><Close /></el-icon>
      </button>
    </header>

    <!-- 任务行：一行一位参与者，只写「谁 · 在干什么」；状态随直播进度原地更新。
         完成的行先变绿底留观 2s，再滑出列表（由外层按 doneAtMs 过滤）。 -->
    <transition-group v-if="tasks.length" name="inspect-task" tag="ul" class="inspect-compact__list">
      <li
        v-for="task in tasks"
        :key="task.key"
        class="inspect-compact__task"
        :class="{ 'is-done': task.done, 'is-failed': task.state === INSPECT_STATE_FAILED }"
      >
        <span class="inspect-compact__name" :title="task.title">{{ task.title }}</span>
        <span
          class="inspect-compact__state"
          :class="{ 'is-done': task.done, 'is-failed': task.state === INSPECT_STATE_FAILED }"
        >
          {{ task.state }}
        </span>
      </li>
    </transition-group>
    <p v-else class="inspect-compact__empty">正在准备编排…</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 运行中执行明细紧凑状态卡。所属域：runtime（前端展示）。
 *
 * <p>职责：把 {@link inspectLiveTasks} 推导出的任务行渲染成一张小卡片。
 * 数据全部来自 props，本组件不推导业务（推导在纯函数里，便于单测）。
 *
 * <p>关键约束：
 * - 只出现在「本轮仍在跑」时；收尾后由外层换成完整泳道，不在结束态继续占运行中文案；
 * - 没有遮罩、没有居中、没有标题栏；宽度由外层按形态给（紧凑卡更窄）；
 * - 不提供刷新：运行中的数据由轮询自动更新。
 */
import { Close, Loading } from '@element-plus/icons-vue'
import {
  INSPECT_STATE_FAILED,
  type InspectRunningTask
} from '@/components/runtime/workbench/inspectOverlay'

defineProps<{
  /** 标题：并行执行中 / 执行中（N 个任务） */
  title: string
  /** 副文案：已耗时 */
  meta?: string
  /** 任务行：谁 · 当前动作 */
  tasks: InspectRunningTask[]
}>()

const emit = defineEmits<{
  /** 点 X 收起紧凑卡；开关状态由外层持有 */
  close: []
}>()
</script>

<style scoped>
.inspect-compact {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.inspect-compact__head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 2px 2px 6px;
}

.inspect-compact__spin {
  flex: none;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

.inspect-compact__title {
  min-width: 0;
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspect-compact__meta {
  flex: none;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.inspect-compact__spacer {
  flex: 1;
}

/* 与浮层顶栏的 X 同尺寸：同一套收口手感 */
.inspect-compact__close {
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary, #8b90a0);
  font: inherit;
  cursor: pointer;
  transition: color 0.15s ease, background-color 0.15s ease;
}

.inspect-compact__close:hover {
  color: var(--ah-text, #1f2430);
  background: rgba(16, 24, 40, 0.06);
}

.inspect-compact__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 一行一位参与者：名字左、状态右，长名字省略而不换行，保证卡不高 */
.inspect-compact__task {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  /* 底色由「运行中 → 完成」平滑过渡，绿底不会硬闪一下 */
  transition: background-color 0.2s ease, color 0.2s ease;
}

/*
 * 完成：整行浅绿底。
 * 为什么要底色而不是只改文字色：用户要的是「这一位做完了」一眼可见，
 * 小卡片里一行灰字太弱；浅绿 + 深绿字既有肯定感又不抢还在跑的那几行。
 */
.inspect-compact__task.is-done {
  background: rgba(18, 183, 106, 0.1);
}

.inspect-compact__task.is-failed {
  background: rgba(217, 45, 32, 0.08);
}

.inspect-compact__name {
  min-width: 0;
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspect-compact__state {
  flex: none;
  margin-left: auto;
  color: var(--ah-primary, #635bff);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

/* 已完成 / 失败：文字转绿 / 转红，与底色一起表达终态 */
.inspect-compact__task.is-done .inspect-compact__name {
  color: #0e7a52;
}

.inspect-compact__state.is-done {
  color: #0e9f6e;
}

.inspect-compact__task.is-failed .inspect-compact__name,
.inspect-compact__state.is-failed {
  color: #b42318;
}

/*
 * 滑出：留观 2s 后再收高度 + 向左淡出。
 * 用 transition-group 的 leave 钩子，Vue 会把它从列表里移除时播放这段动画，
 * 因此「绿底停留」与「滑出」是两拍，不会一闪就没。
 */
.inspect-task-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease, max-height 0.18s ease, margin 0.18s ease;
  /* 高度收拢需要显式起点，否则 flex 子项不会动画 */
  max-height: 40px;
  overflow: hidden;
}

.inspect-task-leave-to {
  max-height: 0;
  margin-top: -4px;
  opacity: 0;
  transform: translateX(12px);
}

/* 进场：从右侧轻微滑入，配合列表更新不突兀 */
.inspect-task-enter-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.inspect-task-enter-from {
  opacity: 0;
  transform: translateX(8px);
}

/* 其余行上移让位，避免跳一格 */
.inspect-task-move {
  transition: transform 0.18s ease;
}

@media (prefers-reduced-motion: reduce) {
  .inspect-compact__task,
  .inspect-task-leave-active,
  .inspect-task-enter-active,
  .inspect-task-move {
    transition: none;
  }
}

.inspect-compact__empty {
  margin: 2px 2px 4px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}
</style>
