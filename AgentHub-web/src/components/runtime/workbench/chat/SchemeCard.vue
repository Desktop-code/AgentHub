<template>
  <!--
    方案卡外壳：自己不是参与者，只把方案这一块包成一只分组框，
    内容（方案本人的思考 / 工具 / 正文）由调用方放进默认插槽，
    方案下的子专家与汇总由 WorkbenchActorNest 画在框内。
  -->
  <div :class="{ 'wb-scheme-frame': members.length > 0 }">
    <slot />
    <WorkbenchActorNest v-if="members.length" :cards="members" />
  </div>
</template>

<script setup lang="ts">
/**
 * 方案（MA）卡外壳。所属域：runtime（前端展示）。
 *
 * <p>职责：只负责「方案外框 + 框内子卡」的形态；参与者内容仍由调用方那套卡片渲染。
 * 为什么单独成模块：方案是「一条运行用到谁」的分组信息，对话壳层不应把
 * 方案框、子卡顺序、框内宽度这些细节和普通助手气泡写在一起。
 *
 * <p>子卡顺序已由投影按 begin 收好，这里不再排序、不重算归属。
 */
import WorkbenchActorNest from '@/components/runtime/workbench/WorkbenchActorNest.vue'
import type { ParticipantCard } from '@/components/runtime/workbench/workbenchParticipantFeed'

defineProps<{
  /** 方案下的子卡（含汇总）；空表表示这张不是方案外壳，只渲染内容 */
  members: ParticipantCard[]
}>()
</script>

<style scoped>
/*
 * 方案外框：专家卡画在这只框里面，头像和思考仍是原来的专家卡。
 * 方案自己的思考在框的上半，汇总卡在专家后面，不写进专家正文。
 */
.wb-scheme-frame {
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin-top: 4px;
  padding: 12px;
  border: 1px solid #e4e4f7;
  border-radius: 12px;
  background: #fff;
}

/* 子卡的 key 包装不占高度，专家卡仍是方案框的直接内容 */
.wb-scheme-frame :deep(.wb-scheme-member) {
  display: contents;
}

/* 框已经缩在方案内容列里，内部专家卡不要再按 640px 硬撑出框 */
.wb-scheme-frame :deep(.wb-actor-main) {
  width: auto;
  max-width: 100%;
}

/* 方案外框只当分组框：描边回到发丝线，不再用紫调强调 */
.chat.is-workbench .wb-scheme-frame {
  border-color: var(--ah-border-soft, #f2f6fc);
}
</style>
