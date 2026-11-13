<template>
  <!--
    判路由模型 + ROUTER 提示词。所属域：前端 agent（方案编辑共享块）。
    入口（对话路由）与普通场景 Router 用的是同一块：同一模型槽、同一个三段式 L3 弹窗，
    所以抽成组件复用，避免入口与普通方案各写一套提示词表单。
  -->
  <section class="form-block form-block--first">
    <h3 class="form-block__title">
      判路由模型与 ROUTER 提示词
      <!-- 协议说明收进图标悬浮：三段式规则不必在 Tab 底下占两三行 -->
      <el-tooltip placement="top" content="走现网三段式：钉住一份已发布的 ROUTER 用途模板 + 只改可覆盖段落 + 追加要求；锁定段由平台维护。入口与普通场景 Router 共用同一套协议，改这里不会动任何专家的模型或人设。">
        <el-icon class="form-block__info"><QuestionFilled /></el-icon>
      </el-tooltip>
    </h3>
    <el-form-item>
      <template #label>
        <span class="label-with-tip">
          判路由模型
          <el-tooltip
            placement="top"
            content="只列启用中、用途为 ROUTER 的实例（与 Agent 管理 ROUTER 用途槽同口径）；不显式绑定则按平台 ROUTER / CHAT 能力槽回落。"
          >
            <el-icon class="tip-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
      </template>
      <el-select
        :model-value="judgeModelId"
        class="full-width"
        clearable
        filterable
        :loading="loading"
        placeholder="不显式绑定：按平台 ROUTER / CHAT 能力槽回落"
        @update:model-value="onJudgeModelChange"
      >
        <el-option v-for="item in selectableModels" :key="item.id" :label="judgeModelLabel(item)" :value="item.id" />
      </el-select>
      <!-- 没有任何 ROUTER 用途实例时给出可执行动作，避免用户以为是下拉坏了 -->
      <p v-if="!loading && !selectableModels.length" class="field-hint">
        没有启用中的 ROUTER 实例：请到「模型中心」把实例用途设为 ROUTER；也可以不绑定，按平台 ROUTER / CHAT 槽回落。
      </p>
    </el-form-item>
    <!--
      提示词入口与 Agent 管理「基本信息 · 系统提示词」同一套观感：
      摘要行（当前模板 · 版本 + 已钉标记）+「配置提示词」按钮 + 一行说明，
      编辑仍进统一 L3 弹窗，不在页内展开长文本。
    -->
    <el-form-item label="ROUTER 提示词">
      <div class="prompt-entry">
        <div class="entry-row">
          <div class="entry-main">
            <span class="entry-label" :class="{ 'is-pending': !bound }">{{ promptLabel }}</span>
            <el-tag v-if="bound" size="small" effect="plain" type="success" class="entry-tag">已钉版本</el-tag>
          </div>
          <el-button type="primary" plain size="small" :disabled="!canEdit" @click="emit('edit-prompt')">
            配置提示词
          </el-button>
        </div>
        <p class="entry-hint">
          模板锁定段由平台维护；可覆盖段落与追加要求在弹窗中编辑，保存前可预览最终注入内容。
        </p>
      </div>
    </el-form-item>
  </section>
</template>

<script setup lang="ts">
/**
 * 判路由模型与 ROUTER 提示词面板。所属域：前端 agent（方案编辑）。
 *
 * <p>职责：只负责展示与编辑两个字段——判路由模型实例、ROUTER 提示词入口。
 * 提示词本身仍由父级的 {@code PromptL3Dialog}（purpose=ROUTER）编辑，
 * 本组件不持有草稿、不发请求，避免出现第二套提示词协议。
 *
 * <p>边界：不含保存动作；值通过 {@code update:judgeModelId} 回写父级表单，
 * 由抽屉底部「保存」统一落库。
 */
import { computed, onMounted } from 'vue'
import type { MaJudgeModelOption } from '@/types/ma'
import type { PromptBinding } from '@/types/agent'
import { usePromptTemplateCatalog } from '@/composables/promptTemplateCatalog'
import { normalizeCapability } from '@/types/model'
import { QuestionFilled } from '@element-plus/icons-vue'

/** ROUTER 提示词用途码：与父级 L3 弹窗、后端用途槽保持同一个码 */
const ROUTER_PURPOSE = 'ROUTER'

const props = withDefaults(
  defineProps<{
    /** 当前判路由模型实例主键；null 表示不显式绑定、走平台能力槽回落 */
    judgeModelId?: number | null
    /** 可选模型实例（只含启用中、用途属于对话形态的） */
    judgeModels?: MaJudgeModelOption[]
    /** 模型下拉加载中 */
    loading?: boolean
    /** ROUTER 提示词四列绑定（模板 id / 版本 / 覆盖段 / 追加要求） */
    binding?: PromptBinding
    /** 是否允许编辑；只读用户不弹提示词弹窗 */
    canEdit?: boolean
  }>(),
  {
    judgeModelId: null,
    judgeModels: () => [],
    loading: false,
    binding: () => ({ templateId: null, templateVersion: null, override: {}, promptExtra: '' }),
    canEdit: false
  }
)

const emit = defineEmits<{
  /** 判路由模型被改选（清空为 null） */
  'update:judgeModelId': [value: number | null]
  /** 点「配置提示词」：由父级打开 L3 弹窗 */
  'edit-prompt': []
}>()

/** 模板名目录：只用来把 templateId 翻成「模板名 · vN」，与 Agent 管理摘要行同源 */
const { ensureLoaded, promptTemplateName, promptTemplateDefault } = usePromptTemplateCatalog()

/** 是否已钉住模板：只看 templateId，未钉死时展示平台当前生效版 */
const bound = computed(() => !!props.binding?.templateId)

/** 已钉版本的摘要：模板名 · v 版本；目录未加载完时退化为「模板 #id」 */
const boundLabel = computed(() => {
  const id = props.binding?.templateId
  const version = props.binding?.templateVersion
  const name = promptTemplateName(id) || `模板 #${id}`
  return version ? `${name} · v${version}` : name
})

/** 尚未钉模板时展示「保存时按哪条生效版组装」，与 Agent 管理未绑定态同口径 */
const defaultLabel = computed(() => {
  const option = promptTemplateDefault(ROUTER_PURPOSE)
  if (!option) return ''
  const name = option.name || option.templateKey || `模板 #${option.id}`
  return `${name} · v${option.version}`
})

/** 摘要行文案；未钉模板且目录未知时给一句明确的兜底说明 */
const promptLabel = computed(() => {
  if (bound.value) return boundLabel.value
  return defaultLabel.value || '平台还没有已发布的 ROUTER 模板：运行时按平台生效版组装'
})

// 首次进入拉一次 ROUTER 用途模板名；失败不阻断，摘要退化为 id / 兜底文案
onMounted(() => {
  void ensureLoaded(ROUTER_PURPOSE)
})

/**
 * 判路由模型候选：与 Agent 管理的 ROUTER 用途槽同口径，只留 capability=ROUTER 的实例。
 *
 * <p>父级接口给的是「对话形态」全集（含 CHAT / REWRITE 等，供未绑定时的回落语义），
 * 页面只把 ROUTER 实例放进下拉，避免在这里绑一个能力不匹配的模型。
 */
const routerModels = computed(() =>
  props.judgeModels.filter((item) => normalizeCapability(item.capability) === 'ROUTER')
)

/**
 * 实际可选项：ROUTER 实例 + 当前已绑定的历史实例。
 *
 * <p>历史方案可能早先绑了 CHAT 实例：过滤后必须把它补回下拉，否则 el-select 只显示裸 id，
 * 运营会以为配置丢了。补回项带原 capability，靠标签文案区分。
 */
const selectableModels = computed(() => {
  const rows = routerModels.value
  const boundId = props.judgeModelId
  if (!boundId || rows.some((item) => item.id === boundId)) return rows
  const current = props.judgeModels.find((item) => item.id === boundId)
  return current ? [...rows, current] : rows
})

/**
 * 模型下拉选中变化。
 *
 * <p>Element 清空时可能给空串 / undefined，这里统一收敛成 null，
 * 避免把空串写进 number 字段后提交出非法 JSON。
 *
 * @param value 选中值
 */
function onJudgeModelChange(value: number | string | null | undefined) {
  const normalized = value === null || value === undefined || value === '' ? null : Number(value)
  emit('update:judgeModelId', Number.isNaN(normalized) ? null : normalized)
}

/** 模型下拉文案：展示名 + 供应商模型标识 + 用途，避免同名实例分不清 */
function judgeModelLabel(item: MaJudgeModelOption): string {
  const base = `${item.displayName || `实例 #${item.id}`}（${item.modelId || '未填模型标识'}）`
  return item.capability ? `${base} · ${item.capability}` : base
}
</script>

<style scoped>
/* 与 ma/list 抽屉里的分区样式同口径：组件内独立声明，避免 scoped 样式跨组件失效 */
.form-block {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--ah-border, #e8eaf0);
}

.form-block--first {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.form-block__title {
  display: flex;
  align-items: center;
  margin: 0 0 16px;
  color: var(--ah-text-primary, #1f2430);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.form-block__title::before {
  content: '';
  width: 3px;
  height: 14px;
  margin-right: 8px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.form-block__note {
  margin: -4px 0 0;
  padding-left: 112px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.6;
}

/* 协议说明图标：跟在标题右侧，弱化成辅助色，hover 才出完整口径 */
.form-block__info {
  margin-left: 6px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 14px;
  cursor: help;
}

/* 下拉为空时的兜底提示：跟在控件下方，别和占位文案抢行 */
.field-hint {
  margin: 4px 0 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.full-width {
  width: 100%;
}

/* Label 旁问号：与 Agent 管理 / 方案抽屉同一套 tip-icon 观感 */
.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 14px;
  cursor: help;
}

/* 提示词摘要行：与 Agent 管理「基本信息 · 系统提示词」保持同一视觉 */
.prompt-entry {
  width: 100%;
}

.entry-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  background: #fafbfd;
  border: 1px solid var(--ah-border, #e6e8ef);
  border-radius: 6px;
}

.entry-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.entry-label {
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 未绑定态：弱化颜色，明确「这是待生效的默认模板」而不是已钉配置 */
.entry-label.is-pending {
  color: var(--ah-text-secondary, #6b7280);
  font-weight: 500;
}

.entry-tag {
  flex: none;
}

.entry-hint {
  margin: 6px 0 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}
</style>
