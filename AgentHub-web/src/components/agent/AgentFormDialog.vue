<template>
  <el-dialog :model-value="modelValue" title="新建 Agent" width="800px" @close="emit('update:modelValue', false)">
    <el-form label-width="96px">
      <div class="basic-grid">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="form.code" maxlength="64" placeholder="未删除范围内唯一" />
        </el-form-item>
        <el-form-item required>
          <template #label>
            <span class="label-with-tip">
              运行模式
              <el-tooltip placement="top" :show-after="200">
                <template #content>
                  <div class="run-mode-tip">
                    <div><strong>ReAct</strong>：单 Agent 边推理边调用工具/技能，适合探索式、交互式任务。</div>
                    <div><strong>Plan-Execute</strong>：先拆成有序计划再逐步执行，复杂多步骤更可控（可人工确认计划）。</div>
                    <div><strong>Router</strong>：按意图分发给一层子 Agent，父节点只做路由，不直接跑工具链。</div>
                  </div>
                </template>
                <el-icon class="tip-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-radio-group v-model="form.runMode" class="run-mode-group">
            <el-radio-button value="REACT" class="is-react">ReAct</el-radio-button>
            <el-radio-button value="PLAN_AND_EXECUTE" class="is-plan">Plan-Execute</el-radio-button>
            <el-radio-button value="ROUTER" class="is-router">Router</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <!-- 新建固定启用，启停在详情里改，这里只占同一行 -->
          <el-switch :model-value="true" disabled />
        </el-form-item>
        <el-form-item class="span-2" label="简介">
          <el-input v-model="form.description" type="textarea" maxlength="1000" :rows="2" />
        </el-form-item>
        <el-form-item class="span-2" label="系统提示词">
          <AgentPromptEditor
            ref="promptEditor"
            v-model="form.systemPrompt"
            :prompt="form.prompt"
            :agent-name="form.name"
            :run-mode="form.runMode"
            @update:prompt="onPromptChange"
          />
        </el-form-item>
        <el-form-item class="span-2" label="备注">
          <el-input v-model="form.remark" type="textarea" maxlength="500" :rows="2" />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 新建 Agent 弹窗。所属域：agent。
 * 系统提示词不再预填任何默认模板：新建即空，由配置者在「配置提示词」里按需选择模板或填写业务正文，
 * 避免一打开弹窗就带着一段需要「删除或迁移」的默认正文。
 * 默认 ReAct，启停固定为正常。编辑基本信息在详情区完成。
 */
import { reactive, ref, watch } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AgentPromptEditor from '@/components/agent/AgentPromptEditor.vue'
import type { AgentSave, PromptBinding } from '@/types/agent'
import { checkUserPrompt, settleLegacyPrompt, PROMPT_MAX_CHARS } from '@/utils/promptSafety'

/** 新建默认运行配置：Router 那组（多意图开 / 拼接 / N=3 / ROUTER_PARALLEL）与计划那组（自动执行 / 8 步 / 0 重试），与后端 defaults 对齐 */
function defaultRuntimeConfig() {
  return {
    multiIntentEnabled: true,
    answerMode: 'CONCAT' as const,
    maxRouteChildren: 3,
    maTemplate: 'ROUTER_PARALLEL',
    maContextPolicy: 'ISOLATED',
    maAllowParentHistoryWhenSingle: true,
    requirePlanConfirm: false,
    maxPlanSteps: 8,
    maxStepRetries: 0
  }
}

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  submit: [payload: AgentSave, done: () => void]
}>()

const saving = ref(false)
const promptEditor = ref<{ reveal: () => { errors: string[]; warnings: string[] } } | null>(null)
const form = reactive({
  name: '',
  code: '',
  runMode: 'REACT',
  description: '',
  systemPrompt: '',
  /**
   * L3 四列。新建时默认不挂模板（templateId 为空）：
   * Router 模式下 supported=false，本区只提示去 ROUTER 用途槽配置；其它模式可在弹窗里选模板。
   */
  prompt: {
    supported: true,
    templateId: null,
    templateVersion: null,
    override: {} as Record<string, string>,
    promptExtra: ''
  } as PromptBinding,
  remark: ''
})

/**
 * L3 四列变化：直接写回 form，随提交一起落库。
 *
 * @param value 子组件回传的完整四列
 */
function onPromptChange(value: PromptBinding) {
  form.prompt = value
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    form.name = ''
    form.code = ''
    form.runMode = 'REACT'
    form.description = ''
    // 新建不预填默认提示词：留空交给配置者选择模板或自行填写，避免每次都先删默认正文
    form.systemPrompt = ''
    // 新建默认不挂 L2 模板：保留自由业务段的过渡路径，需要治理时再选模板
    form.prompt = {
      supported: true,
      templateId: null,
      templateVersion: null,
      override: {},
      promptExtra: ''
    }
    form.remark = ''
    saving.value = false
  }
)

/** 切 ReAct / Router：不再自动填默认模板，正文始终保持用户自己写的内容。 */
watch(
  () => form.runMode,
  (next, prev) => {
    if (!props.modelValue || !prev || next === prev) {
      return
    }
    // 新建时切到 Router：agent_def 只承载 CHAT，这里已选的模板会永远不生效，直接清掉
    if (next === 'ROUTER') {
      form.prompt = { supported: false, templateId: null, templateVersion: null, override: {}, promptExtra: '' }
    } else if (prev === 'ROUTER') {
      // 切回非 Router 时恢复可配置能力，但模板仍需用户重新选择
      form.prompt = { supported: true, templateId: null, templateVersion: null, override: {}, promptExtra: '' }
    }
  }
)

function submit() {
  if (!form.name.trim() || !form.code.trim()) {
    ElMessage.warning('请填写名称和编码')
    return
  }
  const report = promptEditor.value?.reveal() ?? checkUserPrompt(form.systemPrompt)
  if (report.errors.length) {
    ElMessage.error(report.errors.join('；'))
    return
  }
  // 若先写了业务正文再挂模板：正文并入追加要求、system_prompt 置空，避免后端「双轨」闸门拒绝
  const settled = settleLegacyPrompt(
    form.systemPrompt,
    form.prompt.promptExtra || '',
    form.prompt.templateId ?? null
  )
  // 合并后超长当场拦下，避免提交后被后端按追加区上限 400
  if (settled.promptExtra.length > PROMPT_MAX_CHARS) {
    ElMessage.error(`并入业务正文后「追加要求」超过长度，最长 ${PROMPT_MAX_CHARS}，请先精简`)
    return
  }
  saving.value = true
  emit(
    'submit',
    {
      name: form.name.trim(),
      code: form.code.trim(),
      runMode: form.runMode,
      description: form.description.trim(),
      systemPrompt: settled.systemPrompt,
      // 未挂模板时提交空四列（templateId 为空即「不挂模板」），后端按此清空
      prompt: {
        templateId: form.prompt.templateId ?? null,
        templateVersion: form.prompt.templateVersion ?? null,
        override: form.prompt.override || {},
        promptExtra: settled.promptExtra
      },
      remark: form.remark.trim(),
      runtimeConfig: defaultRuntimeConfig()
    },
    () => {
      saving.value = false
    }
  )
}
</script>

<style scoped>
.basic-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 16px;
}

.span-2 {
  grid-column: 1 / -1;
}

.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--el-text-color-placeholder);
  cursor: help;
}

.run-mode-tip {
  max-width: 320px;
  line-height: 1.55;
  font-size: 12px;
}

.run-mode-tip > div + div {
  margin-top: 6px;
}

@media (max-width: 768px) {
  .basic-grid {
    grid-template-columns: 1fr;
  }

  .span-2 {
    grid-column: auto;
  }
}
</style>
