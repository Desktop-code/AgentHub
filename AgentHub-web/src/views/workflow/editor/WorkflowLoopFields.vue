<template>
  <el-form-item label="最大次数" required>
    <el-input-number
      :model-value="maxIterations"
      class="wf-field"
      :min="1"
      :max="cap"
      :step="1"
      :precision="0"
      controls-position="right"
      :disabled="disabled"
      @update:model-value="onMax"
    />
  </el-form-item>
  <div class="wf-tip">建议 3 次。平台硬顶 {{ cap }} 次，图上不能写得更大。</div>
  <el-form-item label="超限出路" required>
    <el-select
      :model-value="onExceeded"
      class="wf-field"
      placeholder="必须选择"
      :disabled="disabled"
      @update:model-value="onOutcome"
    >
      <el-option label="下游继续" value="DOWNSTREAM" />
      <el-option label="转人工" value="HUMAN" />
      <el-option label="失败" value="FAIL" />
    </el-select>
  </el-form-item>
  <el-form-item label="打回反馈路径">
    <el-input
      :model-value="feedbackPath"
      maxlength="128"
      placeholder="如 $.outputs.check"
      :disabled="disabled"
      @update:model-value="onPath"
    />
  </el-form-item>
  <div class="wf-tip">
    从本节点拉一条「进入循环体」到要重复的第一步，再从最后一步拉「回到循环」指回本节点。
    下游继续或转人工还要拉一条「次数用尽」；失败出路不要这条边。
    取消、节点失败和时限到期按失败或取消结束，不走超限出路。
  </div>
</template>

<script setup lang="ts">
/**
 * 循环节点的次数、超限出路和打回反馈。所属域：workflow 的画布属性。
 *
 * <p>只改当前节点的 config，不在这里连边。次数上限来自草稿接口下发的平台硬顶，
 * 页面上的数字不能超过它；保存时后端还会再校验一次。
 */
const props = defineProps<{
  /** 图上填写的最大次数；空表示还没配 */
  maxIterations: number | null
  /** DOWNSTREAM / HUMAN / FAIL */
  onExceeded: string
  /** 打回时写入上下文的路径，可空 */
  feedbackPath: string
  /** 平台硬顶，输入框不能超过它 */
  cap: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:maxIterations': [value: number | null]
  'update:onExceeded': [value: string]
  'update:feedbackPath': [value: string]
}>()

/** 次数只接受正整数；清空留空，交给保存校验拒绝。 */
function onMax(value: number | undefined) {
  if (value === undefined || value === null || Number.isNaN(value)) {
    emit('update:maxIterations', null)
    return
  }
  emit('update:maxIterations', Math.min(props.cap, Math.max(1, Math.trunc(value))))
}

/** 超限出路三选一，空字符串表示用户清掉了。 */
function onOutcome(value: string) {
  emit('update:onExceeded', value || '')
}

/** 反馈路径原样写回，前后空格留给保存时的格式校验。 */
function onPath(value: string) {
  emit('update:feedbackPath', value ?? '')
}
</script>
