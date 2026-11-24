<template>
  <!--
    可点选项块：单专家卡、工作流询问节点、工作流旧卡底共用这一套芯片。
    字母序号 + 文案 + 推荐标记；点击只回传原始 option，续作入参不改写。
  -->
  <div class="wb-choice" :class="extraClass">
    <!-- 已答复回执：选择写在发问专家的卡内，不再额外冒一条用户气泡 -->
    <template v-if="reply">
      <p class="wb-choice-question">{{ questionText }}</p>
      <p class="wb-choice-reply">您的选择：{{ reply }}</p>
    </template>
    <template v-else>
      <p class="wb-choice-question">{{ questionText }}</p>
      <div class="wb-choice-options">
        <button
          v-for="row in rows"
          :key="row.option.id"
          type="button"
          class="wb-choice-chip"
          :disabled="disabled"
          @click.stop.prevent="emit('choose', row.option)"
        >
          <span class="wb-choice-index" aria-hidden="true">{{ row.letter }}</span>
          <span class="wb-choice-label">{{ row.text }}</span>
          <span v-if="row.recommended" class="wb-choice-recommend">推荐</span>
        </button>
      </div>
      <p v-if="waiting" class="wb-choice-waiting">等待你的回复，选好后方案会从这位专家继续</p>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 可点选项块。所属域：runtime（前端展示）。
 *
 * <p>职责：只画题面 / 选项 / 已答复回执，不决定能不能点、也不发请求。
 * 「是不是最后一条助手消息」等业务判定由调用方通过 `disabled` 传入。
 */
import { computed } from 'vue'
import type { ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { choiceRowsOf } from '@/components/runtime/workbench/chat/chipRows'

const props = withDefaults(
  defineProps<{
    /** 题面；空时用「请选择」 */
    question?: string
    /** 后端选项；已答复时可为空 */
    options?: ParticipantChoiceOption[]
    /** 已答复的选项文案；有值即画回执而不是芯片 */
    reply?: string
    /** 是否禁止点击（历史轮 / 正在发送） */
    disabled?: boolean
    /** 是否显示「等待你的回复」提示 */
    waiting?: boolean
    /** 调用方附加的形态类（如 wf-node-choice / wf-choice / wb-choice-replied） */
    extraClass?: string
  }>(),
  {
    question: '',
    options: () => [],
    reply: '',
    disabled: false,
    waiting: false,
    extraClass: ''
  }
)

const emit = defineEmits<{
  /** 用户点了某一项；回传原始 option（含 id 与后端 label） */
  choose: [option: ParticipantChoiceOption]
}>()

/** 题面永远有一行：模型给了问题就用它，没给就用「请选择」，不让选项孤零零地出现 */
const questionText = computed(() => (props.question || '').trim() || '请选择')

/** 渲染行：字母序号 + 展示文案 + 推荐标记 */
const rows = computed(() => choiceRowsOf(props.options))
</script>

<style scoped>
.chat.is-workbench .wb-choice {
  margin-top: 12px;
}

/* 一句话问题：比正文略小、略浅，让选项行成为视觉落点 */
.chat.is-workbench .wb-choice-question {
  margin: 0 0 8px;
  color: #4b5563;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
}

/* 已答复回执：选择写在发问专家的卡内，不再额外冒一条用户气泡 */
.chat.is-workbench .wb-choice-replied {
  border-left: 2px solid #ddd6fe;
  padding-left: 10px;
}

.chat.is-workbench .wb-choice-reply {
  margin: 0;
  color: #4338ca;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

/* 等待提示：说明方案停在这位专家处，选完会从这里继续 */
.chat.is-workbench .wb-choice-waiting {
  margin: 8px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}

/*
 * 选项一行一项：竖排铺满文字区，不挤成同一行里的多个胶囊。
 * 选项文案可能是一整句，横排换行会让几行混在一起分不清哪行是哪一项。
 */
.chat.is-workbench .wb-choice-options {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  max-width: 680px;
}

/*
 * 选项行：白底 + 轻发丝边 + 8px 小圆角（对话气泡本体仍是 12px）。
 * 紫色只在 hover / 键盘聚焦时出现，服从 The One Violet Rule。
 */
.chat.is-workbench .wb-choice-chip {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #1f2937;
  font: inherit;
  font-size: 14px;
  line-height: 1.5;
  /* 长文案左对齐、自然折行：一行一项，行首对齐才好扫读；不截省略号 */
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s, color 0.15s;
}

/* 字母序号：静止是中性灰小方块，不抢正文；长文案换行时仍与首行对齐 */
.chat.is-workbench .wb-choice-index {
  display: grid;
  flex: none;
  place-items: center;
  width: 20px;
  height: 20px;
  margin-top: 1px;
  border-radius: 5px;
  background: #f3f4f6;
  color: #6b7280;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  transition: background-color 0.15s, color 0.15s;
}

/* 选项文案：完整换行，不截断；与序号同一行起读 */
.chat.is-workbench .wb-choice-label {
  flex: 1;
  min-width: 0;
  white-space: normal;
  word-break: break-word;
}

/* 推荐标识：仅在文案自带「推荐」等显式标记时出现，克制的小标签，不做色块 */
.chat.is-workbench .wb-choice-recommend {
  flex: none;
  margin-top: 1px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f4f3ff;
  color: #5146f5;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.5;
}

/* hover / 键盘聚焦：浅紫底 + 信号紫描边，不用遗留蓝 */
.chat.is-workbench .wb-choice-chip:hover:not(:disabled),
.chat.is-workbench .wb-choice-chip:focus-visible:not(:disabled) {
  border-color: #635bff;
  background: #f4f3ff;
  color: #1f2937;
  outline: none;
}

/* 序号随选项一起点亮，但不做成实心紫块（避免一屏出现一排强紫） */
.chat.is-workbench .wb-choice-chip:hover:not(:disabled) .wb-choice-index,
.chat.is-workbench .wb-choice-chip:focus-visible:not(:disabled) .wb-choice-index {
  background: #e9e5ff;
  color: #5146f5;
}

/* 键盘聚焦加 DESIGN 规定的 2px 紫光；鼠标点击不触发（:focus-visible） */
.chat.is-workbench .wb-choice-chip:focus-visible {
  box-shadow: 0 0 0 2px rgba(99, 91, 255, 0.2);
}

/* 已过期（历史轮）或正在发送：置灰且不可点，避免续作到已翻页的上下文 */
.chat.is-workbench .wb-choice-chip:disabled {
  border-color: #eef0f4;
  background: #f9fafb;
  color: #9ca3af;
  cursor: not-allowed;
}

.chat.is-workbench .wb-choice-chip:disabled .wb-choice-index {
  background: #f3f4f6;
  color: #b6bac3;
}
</style>
