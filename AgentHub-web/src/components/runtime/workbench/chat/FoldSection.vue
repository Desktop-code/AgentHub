<template>
  <!--
    可折叠过程区：标题行 + 正文槽。工作台单专家卡与工作流节点共用。
    折叠动画走 grid 行高 1fr ↔ 0fr，display 不瞬切；内容由调用方放进默认插槽。
  -->
  <div class="wb-actor-thought" :class="{ 'is-folded': !open, 'is-streaming': streaming }">
    <button
      type="button"
      class="wb-thought-head"
      :class="headClass"
      :aria-expanded="open"
      :aria-label="label"
      @click.stop.prevent="emit('toggle')"
    >
      <span class="thought-chevron" :class="{ open }" aria-hidden="true" />
      <span class="section-label" :class="{ 'is-streaming': streaming }">{{ label }}</span>
    </button>
    <div class="wb-actor-fold" :class="{ 'is-collapsed': !open }">
      <div class="wb-actor-fold-body">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 可折叠过程区（思考 / 工具）。所属域：runtime（前端展示）。
 *
 * <p>职责：只画折叠标题与容器，展开与否由调用方状态决定（用户点过就以点击为准）。
 * 单专家卡与工作流节点共用同一份折叠形态，避免两边动画、内边距慢慢长歪。
 */
withDefaults(
  defineProps<{
    /** 当前是否展开 */
    open: boolean
    /** 标题行文案（可含耗时 / 条数） */
    label: string
    /** 是否仍在生成：标题高光 */
    streaming?: boolean
    /** 调用方附加的标题样式类（如工作流节点的 wf-thought-head） */
    headClass?: string
  }>(),
  { streaming: false, headClass: '' }
)

const emit = defineEmits<{
  /** 点了一次标题行 */
  toggle: []
}>()
</script>

<style scoped>
/* 整块是一个浅灰圆角框。思考文字自己限高四行并在框内滚动，标题不跟着滚 */
.wb-actor-thought {
  width: 100%;
  box-sizing: border-box;
  margin: 0 0 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  overflow: hidden;
  transition: background 0.18s ease, border-color 0.18s ease;
}

/* 已收起：只剩标题那一行，点标题展开；底与描边淡出，读起来像一行可点文字 */
.wb-actor-thought.is-folded {
  background: transparent;
  border-color: transparent;
  /* 收起时保留与流式相同的高度上限：从「流式中」到「已收起」只改一帧，
     若这里放开 max-height，整框会先弹到全文高度再收起，看起来就是「闪一下」。
     41~88px 之间只有标题行，不会被裁到。 */
  max-height: 88px;
}

.wb-actor-thought.is-folded:hover {
  background: #f3f4f6;
  border-color: #e5e7eb;
}

/* 标题行：整行可点，与演示的 thought-header 一致 */
.wb-thought-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin: 0;
  padding: 8px 12px;
  border: 0;
  background: transparent;
  color: #9ca3af;
  font: inherit;
  font-size: 13px;
  font-weight: 400;
  text-align: left;
  cursor: pointer;
  user-select: none;
}

.wb-thought-head:hover {
  color: #4f46e5;
}

.thought-chevron {
  flex: none;
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid currentColor;
  transition: transform 0.2s ease;
}

.thought-chevron.open {
  transform: rotate(90deg);
}

/*
 * 折叠动画走「grid 行高 1fr ↔ 0fr」而不是 v-show 的 display 瞬切：
 * display 切换没有过渡，三拍切换（思考 → 胶囊 / 结果框）时整段内容会瞬间消失，
 * 看起来就是「跳一下」。行高可以连续过渡，形态与结构完全不变。
 */
.wb-actor-fold {
  display: grid;
  grid-template-rows: 1fr;
  transition: grid-template-rows 0.24s ease;
}

.wb-actor-fold.is-collapsed {
  grid-template-rows: 0fr;
}

.wb-actor-fold-body {
  max-height: none;
  /* 0fr 要真正收起，子元素必须允许被压到 0：min-height + overflow 是这套写法的前提 */
  min-height: 0;
  overflow: hidden;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 13px;
  line-height: 1.55;
}

/* 工作台：折叠思考用静灰底 + 发丝线，读起来是「可展开的过程」，不是答案 */
.chat.is-workbench .wb-actor-thought:not(.is-folded) {
  background: var(--ah-input-fill, #f5f7fa);
  border-color: var(--ah-border-soft, #f2f6fc);
}

.chat.is-workbench .wb-actor-thought.is-folded:hover {
  background: var(--ah-border-soft, #f2f6fc);
  border-color: var(--ah-border, #ebeef5);
}
</style>
