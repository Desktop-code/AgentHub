<template>
  <!--
    非 CHAT 用途的提示词入口弹窗。所属域：agent。
    壳与 CHAT **完全相同**（PromptL3Dialog），只是落库目标换成 agent_model_bind 的对应用途行，
    标题文案随 purpose 变化（路由 / 查询改写 / 摘要 / 记忆抽取）。
  -->
  <PromptL3Dialog
    :model-value="modelValue"
    :purpose="purpose"
    :binding="bind?.prompt"
    :locale="locale"
    :agent-name="agentName"
    :disabled="disabled"
    @update:model-value="emit('update:modelValue', $event)"
    @save="onSave"
  />
</template>

<script setup lang="ts">
/**
 * 非 CHAT 用途的 L3 提示词配置弹窗。所属域：agent。
 * <p>
 * 为什么复用 {@link PromptL3Dialog} 而不是各自实现一个 el-dialog：
 * 需求要求 CHAT 与非 CHAT「样式统一」，此前两侧宽度、标题、追加区都不同，
 * 用户在不同 Tab 看到的是两套交互。现在只保留一个弹窗组件，本类仅做「绑定行 → 四列」的搬运。
 * <p>
 * 落库去向：架构 §3.3 规定 ROUTER / REWRITE / SUMMARY / MEMORY 的模板挂在
 * 「对应用途的 agent_model_bind 行」，因此保存走模型绑定更新接口，由外层执行。
 */
import { ElMessage } from 'element-plus'
import PromptL3Dialog from '@/components/agent/PromptL3Dialog.vue'
import type { ModelBind, PromptBinding } from '@/types/agent'

const props = defineProps<{
  modelValue: boolean
  /** 绑定行；含该用途当前的 L3 回显 */
  bind?: ModelBind | null
  /** 用途码（来自槽位，不由绑定行的 purpose 决定 —— 它可能为空表示跟实例） */
  purpose: string
  locale?: string
  agentName?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** 提交四列；由外层调用 updateModelBind 落库 */
  submit: [payload: PromptBinding & { purpose: string }, done: (ok: boolean) => void]
}>()

/**
 * 转发保存：弹窗已完成自身的硬闸校验（钉版本 / 失效段落），
 * 这里只补用途码并交给外层落库。
 *
 * @param payload 弹窗内的四列
 * @param done    结果回调；外层落库失败时传 false 保留弹窗
 */
function onSave(payload: PromptBinding, done: (ok: boolean) => void) {
  if (!props.bind?.id) {
    ElMessage.error('该用途尚无模型绑定，请先绑定模型再配置提示词')
    done(false)
    return
  }
  emit(
    'submit',
    {
      purpose: props.purpose,
      templateId: payload.templateId ?? null,
      templateVersion: payload.templateVersion ?? null,
      override: { ...(payload.override || {}) },
      promptExtra: payload.promptExtra || ''
    },
    done
  )
}
</script>
