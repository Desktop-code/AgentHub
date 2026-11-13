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
                  </div>
                </template>
                <el-icon class="tip-icon"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <!--
            本页只建单兵：Router 已迁到「多智能体」，新建下拉不再出现该类型，
            以免在 Agent 管理里造出多智能体列表看不到的孤立路由根。
          -->
          <el-radio-group v-model="form.runMode" class="run-mode-group">
            <el-radio-button value="REACT" class="is-react">ReAct</el-radio-button>
            <el-radio-button value="PLAN_AND_EXECUTE" class="is-plan">Plan-Execute</el-radio-button>
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
          <!-- 平台没有可钉的对话模板时提前说明：保存会被拒，避免用户先填一堆再失败 -->
          <el-alert
            v-if="form.runMode !== 'ROUTER' && !form.prompt.templateId && !defaultPrompt"
            class="no-default-alert"
            type="warning"
            :closable="false"
            show-icon
            title="平台还没有已发布生效的对话用途模板（CHAT）：请先在「提示词」页发布，再新建 Agent。"
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
 * <p>
 * 新建即钉住平台当前生效的对话模板：打开弹窗时拉一次默认版本写入 L3 四列，
 * 配置者只在「可覆盖段 + 追加要求」里改，或换同用途其他已发布模板；
 * 不再有「未配置提示词模板 + 空白业务提示词」这个默认态。
 * 默认 ReAct，启停固定为正常。编辑基本信息在详情区完成。
 */
import { reactive, ref, watch } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AgentPromptEditor from '@/components/agent/AgentPromptEditor.vue'
import { getDefaultPromptTemplate } from '@/api/agentPrompt'
import type { AgentSave, PromptBinding, PromptTemplateOption } from '@/types/agent'
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
/** 平台当前生效的对话模板；打开弹窗时拉取，用于把新建表单直接置于 L3 绑定态 */
const defaultPrompt = ref<PromptTemplateOption | null>(null)
const form = reactive({
  name: '',
  code: '',
  runMode: 'REACT',
  description: '',
  systemPrompt: '',
  /**
   * L3 四列。新建默认钉住平台当前生效的 CHAT 模板（有版本即钉死）；
   * 平台确实没有已发布生效模板时保持为空，由保存接口给出明确失败文案。
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
    // 新建不再提供「空白业务提示词」主路径：正文保持为空，提示词一律走 L3 模板 + 追加要求
    form.systemPrompt = ''
    form.prompt = emptyBinding()
    form.remark = ''
    saving.value = false
    // 打开即钉平台当前生效的对话模板（有版本即钉死）；拉不到时保持空，由后端保存时给出明确失败
    void pinDefaultTemplate()
  }
)

/** 空四列。用于切换模式时重置，避免把上一个用途的模板带到新用途。 */
function emptyBinding(): PromptBinding {
  return { supported: true, templateId: null, templateVersion: null, override: {}, promptExtra: '' }
}

/**
 * 拉平台当前生效的 CHAT 模板并写入四列。
 * <p>
 * 只在用户还没有显式选择模板时补（避免把用户选好的版本覆盖掉）；
 * 失败按无默认处理，不阻断新建——保存接口会给出「平台未发布对话模板」的明确文案。
 */
async function pinDefaultTemplate() {
  try {
    const { data } = await getDefaultPromptTemplate({ purpose: 'CHAT', locale: 'zh-CN' })
    const option = data.data || null
    defaultPrompt.value = option
    if (!option || form.prompt.templateId) {
      return
    }
    form.prompt = {
      supported: true,
      templateId: option.id,
      templateVersion: option.version,
      override: {},
      promptExtra: ''
    }
  } catch {
    defaultPrompt.value = null
  }
}

/**
 * 运行模式切换：新建表单已移除 Router 选项，只留 ReAct / 计划执行两种单兵模式。
 *
 * <p>下方 Router 分支只为兼容历史数据（旧深链打开 Router 时 runMode 仍是 ROUTER）；
 * 正常编辑不会走到——详情里 ROUTER 只出只读标签，不给单选组。
 * 切模式时重新钉 CHAT 默认模板，避免沿用上一个模式的绑定。
 */
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
      // 切回非 Router：重新进入 L3 绑定态（钉平台当前生效版本）
      form.prompt = emptyBinding()
      void pinDefaultTemplate()
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
      // 极端情况下弹窗打开时没拉到默认模板（模板未发布）：四列提交为空，后端会再次解析默认并给出明确失败，
      // 不会落成「未挂模板」——CHAT 保存后必须处于已绑定状态
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

.no-default-alert {
  margin-top: 8px;
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
