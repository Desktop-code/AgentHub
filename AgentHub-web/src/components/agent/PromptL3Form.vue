<template>
  <!--
    L3 提示词表单（CHAT / 非 CHAT 共用）。所属域：agent。
    只吃「钉死版 segment 列表」与四列表单值，写入哪张表由调用方决定（Adapter 不同、UI 相同）。
  -->
  <div class="l3-form">
    <!-- 未挂模板 / 未选模板时的提示：本组件不提供任何「整段覆盖 L2」的自由大文本框 -->
    <p class="l3-lead">
      选定平台已发布的用途模板后，仅「可覆盖段落」可在此填写业务要求；锁定段由平台维护，运行时原样注入。
    </p>

    <el-form label-width="96px">
      <el-form-item label="提示词模板">
        <div class="template-row">
          <!--
            teleported + 独立 popper 类：模板下拉在弹窗内使用时，若跟随父级层叠上下文，
            会被 el-dialog 的 overflow 裁掉（线上「下拉被裁」现象），故显式挂到 body 并抬高层级。
          -->
          <el-select
            :model-value="selectedTemplateId"
            :disabled="disabled || loadingOptions"
            :loading="loadingOptions"
            placeholder="选择已发布的用途模板"
            style="width: 100%"
            teleported
            popper-class="l3-select-popper"
            @update:model-value="onTemplateChange"
          >
            <el-option
              v-for="item in options"
              :key="item.id"
              :label="optionLabel(item)"
              :value="item.id"
            />
          </el-select>
          <!-- 平台发新版只提示，不静默升级；必须点按钮显式确认 -->
          <el-tag v-if="upgradeAvailable" type="warning" effect="plain" class="upgrade-tag">
            有可升级版本 v{{ latestVersion }}
          </el-tag>
          <el-button
            v-if="upgradeAvailable"
            type="warning"
            link
            :disabled="disabled"
            @click="upgrade"
          >
            升级到 v{{ latestVersion }}
          </el-button>
        </div>
        <p v-if="!options.length && !loadingOptions" class="l3-hint">
          该用途还没有已发布的提示词模板，请先在「提示词」页发布 L2 模板。
        </p>
        <p v-else-if="pinnedVersion" class="l3-hint">
          已钉死 v{{ pinnedVersion }}：平台发新版不会自动升级，本 Agent 始终读该版本。
        </p>
      </el-form-item>

      <!-- 失效 key 高亮：旧 override 里有、当前钉死版没有的段，必须清理后才能保存 -->
      <el-form-item v-if="staleKeys.length" label="待清理">
        <el-alert type="error" :closable="false" show-icon>
          <template #title>
            以下覆盖段在该版本已不存在，请清理后再保存：
            <el-tag
              v-for="key in staleKeys"
              :key="key"
              type="danger"
              effect="plain"
              size="small"
              class="stale-tag"
            >
              {{ labelOf(key) }}
            </el-tag>
          </template>
        </el-alert>
      </el-form-item>

      <template v-if="currentView">
        <!-- core：灰底只读，Label=display_name；禁止编辑，禁止整段替换 -->
        <el-form-item
          v-for="segment in coreSegments"
          :key="segment.segKey"
          :label="segment.displayName"
        >
          <div class="core-box">
            <pre class="core-text">{{ segment.content }}</pre>
            <span class="core-badge">平台锁定 · 不可修改</span>
          </div>
        </el-form-item>

        <!-- soft：动态输入框，Label=display_name，提交 key=segKey -->
        <el-form-item
          v-for="segment in softSegments"
          :key="segment.segKey"
          :label="segment.displayName"
        >
          <el-input
            :model-value="displayValue(segment)"
            type="textarea"
            :rows="3"
            :maxlength="OVERRIDE_MAX_CHARS"
            :disabled="disabled"
            :placeholder="`留空即使用模板默认：${shorten(segment.content)}`"
            @update:model-value="(value: string) => onOverrideInput(segment, value)"
          />
          <!-- 说明当前这行到底是「已覆写」还是「跟随模板默认」，避免用户对着正文猜 -->
          <p class="l3-hint">
            {{ overrideHintOf(segment) }}
          </p>
        </el-form-item>

        <!-- 该版模板没有可覆盖段：明确空态，别让用户对着空白以为表单没加载 -->
        <el-form-item v-if="!softSegments.length" label="可覆盖段落">
          <p class="l3-empty">
            当前钉死版没有「可覆盖段落」，全部内容由平台维护；如需补充业务要求，请用下方追加区。
          </p>
        </el-form-item>
      </template>

      <!--
        追加区：append-only，文案必须写明不能覆盖锁定段。
        非 CHAT 用途默认折叠：判路由 / 改写 / 摘要 / 抽取对「额外业务约定」需求低，
        常显只会把软段输入框挤到视口外；需要时展开即可。
      -->
      <el-collapse v-if="showExtra && extraCollapsed" v-model="extraPanels" class="l3-extra-collapse">
        <el-collapse-item name="extra" title="追加要求（可选，追加在模板之后，不能覆盖锁定段）">
          <el-input
            :model-value="draft.promptExtra || ''"
            type="textarea"
            :rows="3"
            :maxlength="EXTRA_MAX_CHARS"
            :disabled="disabled"
            placeholder="追加在模板之后，不能覆盖锁定段；用于本用途的额外业务约定"
            @update:model-value="(value: string) => emit('update:promptExtra', value)"
          />
        </el-collapse-item>
      </el-collapse>
      <el-form-item v-else-if="showExtra" label="追加要求">
        <el-input
          :model-value="draft.promptExtra || ''"
          type="textarea"
          :rows="3"
          :maxlength="EXTRA_MAX_CHARS"
          :disabled="disabled"
          placeholder="追加在模板之后，不能覆盖锁定段；用于本 Agent 的额外业务约定"
          @update:model-value="(value: string) => emit('update:promptExtra', value)"
        />
        <p class="l3-hint">
          追加在模板之后，不能覆盖锁定段；锁定段由平台统一维护，运行时先注入 L1/L2 再追加本区。
        </p>
      </el-form-item>
    </el-form>

    <!--
      预览入口已上移到弹窗页脚（取消左侧）。这里保留一句说明，
      让配置者知道预览不调模型、走后端组装路径。
    -->
    <p class="l3-actions-hint">
      预览走后端组装路径，不调用模型；缺平台约束或必选段会直接报错。
    </p>

    <el-dialog v-model="previewOpen" title="提示词预览" width="760px" append-to-body>
      <p class="preview-meta">
        用途 {{ previewMeta.purpose }} · 模板 {{ previewMeta.templateKey }} v{{ previewMeta.templateVersion }} ·
        L1 v{{ previewMeta.constraintVersion }} · 覆盖 {{ previewMeta.overriddenSegmentCount }}/{{ previewMeta.segmentCount }} 段
        <el-tag v-if="previewMeta.truncated" type="warning" size="small" effect="plain" class="gap">已按预算截断</el-tag>
      </p>
      <el-alert
        v-for="notice in previewMeta.notices"
        :key="notice"
        :title="notice"
        type="warning"
        :closable="false"
        show-icon
        class="preview-notice"
      />
      <pre class="preview-text">{{ previewText }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * L3 提示词表单（表单驱动覆写）。所属域：agent。
 * <p>
 * 设计约束（架构定稿 §6.2）：
 * 1. 只能按 L2 的 soft 段动态生成输入框，Label 取 display_name，提交键取 seg_key；
 * 2. core 段灰底只读展示，**严禁**提供「全局覆盖 L2」的单个自由文本框；
 * 3. 版本钉死：平台发新版只提示，必须点「升级」显式确认后才改 version；
 * 4. 升级/换模板后旧 override 里的失效 key 高亮列出，清理前禁止保存与预览。
 * <p>
 * CHAT（写 agent_def）与非 CHAT（写 agent_model_bind）用同一套组件，只是 v-model 绑到不同表单对象。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getPromptTemplate, listPromptTemplates, previewPrompt } from '@/api/agentPrompt'
import type {
  PromptBinding,
  PromptPreviewResult,
  PromptTemplateOption,
  PromptTemplateSegment,
  PromptTemplateView
} from '@/types/agent'
import { nextOverride, overrideHint, softDisplayValue } from '@/utils/promptSoftValue'

/** 单段覆盖上限，与后端 AgentPromptBindingService.OVERRIDE_VALUE_MAX 对齐 */
const OVERRIDE_MAX_CHARS = 12000
/** 追加区上限，与后端 EXTRA_MAX 对齐 */
const EXTRA_MAX_CHARS = 12000

const props = defineProps<{
  /** 用途码：CHAT / ROUTER / REWRITE / SUMMARY / MEMORY */
  purpose: string
  /** 四列当前值；未挂模板时 templateId 为空 */
  draft: PromptBinding
  /** 语言；默认 zh-CN */
  locale?: string
  /** Agent 名称，供预览里的 {{agent_name}}；可空 */
  agentName?: string
  /** 是否展示追加区（CHAT 展示，非 CHAT 由调用方按需开启） */
  showExtra?: boolean
  /**
   * 追加区是否默认折叠。非 CHAT 用途折叠成一行，避免与 soft 输入框抢空间；
   * CHAT 保持常显（追加要求是主对话的常用配置）。
   */
  extraCollapsed?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:templateId': [value: number | null]
  'update:templateVersion': [value: number | null]
  'update:override': [value: Record<string, string>]
  'update:promptExtra': [value: string]
  /** 预览按钮可用态；弹窗页脚据此置灰，避免两处各判一套 */
  'update:previewable': [value: boolean]
}>()

const options = ref<PromptTemplateOption[]>([])
const loadingOptions = ref(false)
const currentView = ref<PromptTemplateView | null>(null)
/** 折叠态追加区的展开面板；已有内容时自动展开，避免「配了却看不见」 */
const extraPanels = ref<string[]>([])
const previewOpen = ref(false)
const previewing = ref(false)
const previewText = ref('')
const previewMeta = ref<Partial<PromptPreviewResult>>({})

/** 已钉死版本；回显与表单共用同一个值，避免「显示 v1 实际存 v2」 */
const pinnedVersion = computed(() => props.draft.templateVersion || null)
const selectedTemplateId = computed(() => props.draft.templateId || null)

/** core 段（mutable=0）：只读展示 */
const coreSegments = computed(() =>
  (currentView.value?.segments || []).filter((item) => item.mutable === 0)
)
/** soft 段（mutable=1）：动态输入框 */
const softSegments = computed(() =>
  (currentView.value?.segments || []).filter((item) => item.mutable === 1)
)

/**
 * soft 输入框的显示值。
 * <p>
 * 语义（与后端 {@code PromptOverrideReader}「空白=未覆盖」保持一致）：
 * <ul>
 *   <li>该段在 override 里有非空值 → 显示该覆写（用户保存过的内容）；</li>
 *   <li>否则 → 显示模板该段的默认正文，让编辑框一打开就是「当前实际生效的内容」，</li>
 *       而不是空框 + placeholder 冒充回显。</li>
 * </ul>
 * 只读计算：不把模板默认值写回 override，避免「没改过却存了一份副本」，导致模板升级后仍读旧正文。
 *
 * @param segment 当前钉死版的 soft 段
 * @return 输入框应显示的内容
 */
function displayValue(segment: PromptTemplateSegment): string {
  return softDisplayValue(segment, props.draft.override)
}

/**
 * 输入框下方的一行状态说明，把「覆写」与「跟随默认」的区别写清，
 * 避免配置者看到正文以为已经存了一份 override。
 *
 * @param segment 当前钉死版的 soft 段
 * @return 提示文案
 */
function overrideHintOf(segment: PromptTemplateSegment): string {
  return overrideHint(segment, props.draft.override)
}

/**
 * 旧 override 里不属于当前钉死版的段键。升级模板后必须清掉，否则后端保存闸会拒绝。
 */
const staleKeys = computed(() => {
  const known = new Set((currentView.value?.segments || []).map((item) => item.segKey))
  return Object.keys(props.draft.override || {}).filter((key) => {
    const value = props.draft.override?.[key]
    // 空值不算残留：空串本来就不入库
    return !!value && value.trim() !== '' && !known.has(key)
  })
})

/** 平台该用途当前生效版本；与钉死版不同才算「有可升级版本」 */
const latestVersion = computed(() => {
  const active = options.value.find((item) => item.isActive === 1)
  return active?.version ?? null
})
const upgradeAvailable = computed(() => {
  if (!pinnedVersion.value || latestVersion.value === null) {
    return false
  }
  return latestVersion.value > pinnedVersion.value
})

/**
 * 选项文案：名称 + 版本 + 生效标记。
 * 只展示已发布版本，草稿不会出现在下拉里。
 */
function optionLabel(item: PromptTemplateOption) {
  return `${item.name || item.templateKey} · v${item.version}${item.isActive === 1 ? '（当前生效）' : ''}`
}

/** 旧版段落名缓存：升级/换模板后仍能显示「角色与语气」而不是 role_tone */
const staleLabelMap = ref<Record<string, string>>({})

/**
 * 失效 key 的可读标签：优先用旧段落名，缺省显示原始 key，
 * 避免只给英文 key 让配置者无从判断该删哪一段。
 */
function labelOf(key: string) {
  return staleLabelMap.value[key] || key
}

/** 占位文案里截断模板默认值，避免一行撑满 */
function shorten(text: string) {
  const value = (text || '').replace(/\s+/g, ' ').trim()
  return value.length > 40 ? `${value.slice(0, 40)}…` : value
}

/**
 * 拉该用途的可发布版本。失败时清空下拉并提示，不让用户对着空列表猜原因。
 */
async function loadOptions() {
  if (!props.purpose) {
    options.value = []
    return
  }
  loadingOptions.value = true
  try {
    const { data } = await listPromptTemplates({ purpose: props.purpose, locale: props.locale || 'zh-CN' })
    options.value = data.data || []
  } catch {
    options.value = []
  } finally {
    loadingOptions.value = false
  }
}

/**
 * 拉钉死版段落。模板未选时清空视图（此时表单只显示「请选模板」）。
 * <p>
 * 同时把段落名记入兜底字典：升级/换模板后，旧 override 里的失效 key 仍能显示中文名
 * 而不是原始 seg_key，用户才知道该删哪一段。
 *
 * @param templateId 模板主键；null 表示清空视图
 * @return 拉到的模板视图；失败或未选时为 null
 */
async function loadView(templateId: number | null): Promise<PromptTemplateView | null> {
  if (!templateId) {
    currentView.value = null
    return null
  }
  try {
    const { data } = await getPromptTemplate(templateId)
    const view = data.data || null
    currentView.value = view
    if (view) {
      const map = { ...staleLabelMap.value }
      for (const segment of view.segments || []) {
        map[segment.segKey] = segment.displayName
      }
      staleLabelMap.value = map
    }
    return view
  } catch {
    // 拉不到段落时不显示半截表单：清空视图，由「未选模板」态兜住
    currentView.value = null
    return null
  }
}

/**
 * 换模板：版本随所选行一起钉死，同时清空旧 override（旧键对新模板无意义）。
 * 换模板属于「重新选择」，与「显式升级」同样需要清理旧覆盖，因此一并清空更安全。
 */
function onTemplateChange(value: number) {
  const picked = options.value.find((item) => item.id === value)
  if (!picked) {
    return
  }
  emit('update:templateId', picked.id)
  emit('update:templateVersion', picked.version)
  emit('update:override', {})
  // 换模板后立即按新版重渲表单，避免用户对着上一版段落继续填
  void loadView(picked.id)
}

/**
 * 显式升级到平台当前生效版本。
 * 先按新版重渲，再由用户在失效 key 提示下清空残留 — 这里不清 override，
 * 目的是让失效 key 高亮可见（静默清空会让用户以为原有配置还在）。
 */
async function upgrade() {
  const active = options.value.find((item) => item.isActive === 1)
  if (!active) {
    return
  }
  emit('update:templateId', active.id)
  emit('update:templateVersion', active.version)
  const view = await loadView(active.id)
  // 逐段核对：新版没有的键保留（值非空），交由 staleKeys 高亮提示清理
  const known = new Set((view?.segments || []).map((item) => item.segKey))
  const next: Record<string, string> = {}
  let dropped = 0
  for (const [key, value] of Object.entries(props.draft.override || {})) {
    if (known.has(key)) {
      next[key] = value
    } else if (value && value.trim() !== '') {
      next[key] = value
      dropped++
    }
  }
  emit('update:override', next)
  if (dropped > 0) {
    ElMessage.warning(`已切到 v${active.version}：有 ${dropped} 个覆盖段在新版不存在，清理后才能保存`)
  } else {
    ElMessage.success(`已切到 v${active.version}`)
  }
}

/**
 * soft 段输入：只回写该段，保持其它段原值。
 * 语义（清空 / 改回模板默认 = 未覆写）在 {@link nextOverride} 里统一实现，便于单测。
 *
 * @param segment 该行对应的 soft 段
 * @param value   输入框新值
 */
function onOverrideInput(segment: PromptTemplateSegment, value: string) {
  emit('update:override', nextOverride(segment, props.draft.override, value))
}

/** 预览是否可用：已选模板且无失效段；通过事件告知父级页脚按钮 */
const canPreview = computed(() => !!selectedTemplateId.value && staleKeys.value.length === 0)

// 任何影响可用性的变化都同步给父级（弹窗页脚按钮据此置灰），避免父级重复实现判断
watch(
  canPreview,
  (value) => emit('update:previewable', value),
  { immediate: true }
)

/**
 * 预览：走后端 PromptAssembler，与出站同路径，不调模型。
 * 存在失效 key 时禁止预览，避免演示一个「保存一定失败」的状态。
 */
async function onPreview() {
  if (!selectedTemplateId.value) {
    ElMessage.warning('请先选择提示词模板')
    return
  }
  if (staleKeys.value.length) {
    ElMessage.warning('请先清理失效的覆盖段')
    return
  }
  previewing.value = true
  try {
    const { data } = await previewPrompt({
      purpose: props.purpose,
      locale: props.locale || 'zh-CN',
      templateId: selectedTemplateId.value,
      templateVersion: pinnedVersion.value,
      override: props.draft.override || {},
      promptExtra: props.draft.promptExtra || undefined,
      agentName: props.agentName,
      // 用固定测试值，保证预览可复现；不传用户真实输入
      userQuery: '（预览示例问题）'
    })
    const result = data.data
    previewText.value = result?.text || ''
    previewMeta.value = result || {}
    previewOpen.value = true
  } catch (error) {
    // 后端返回的业务错误文案已足够说明问题（缺 L1 / 缺必选段等），直接透出
    const message = (error as { message?: string })?.message
    ElMessage.error(message || '预览失败：提示词未就绪')
  } finally {
    previewing.value = false
  }
}

// 用途或已钉死模板变化时，重新拉版本列表与段落（含外部回填与切 Tab 回来）
watch(
  () => [props.purpose, props.locale],
  () => {
    void loadOptions()
  },
  { immediate: true }
)

watch(
  () => props.draft.templateId,
  (id) => {
    void loadView(id ?? null)
  },
  { immediate: true }
)

// 折叠态追加区：回显里已有内容时自动展开，否则用户会以为追加要求丢了
watch(
  () => [props.extraCollapsed, props.draft.promptExtra] as [boolean | undefined, string | undefined],
  ([collapsed, extra]) => {
    if (collapsed && (extra || '').trim()) {
      extraPanels.value = ['extra']
    }
  },
  { immediate: true }
)

defineExpose({
  /**
   * 保存前调用：返回阻止保存的原因，null 表示可提交。
   * 校验只做前端能判的部分（选模板、钉版本、失效 key），键合法性以后端为准。
   */
  validate(): string | null {
    if (props.draft.templateId && !props.draft.templateVersion) {
      return '请选择提示词模板版本'
    }
    if (staleKeys.value.length) {
      return `请先清理失效的覆盖段：${staleKeys.value.map(labelOf).join('、')}`
    }
    return null
  },
  /** 触发预览（弹窗页脚按钮调用）；内部仍走 Assembler，不调模型 */
  preview(): Promise<void> {
    return onPreview()
  }
})
</script>

<style scoped>
.l3-form {
  width: 100%;
}

.l3-lead {
  margin: 0 0 12px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}

.template-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.upgrade-tag {
  flex: none;
}

.l3-hint {
  margin: 4px 0 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.l3-empty {
  margin: 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.6;
}

/* core 段：灰底只读，视觉上明确「不是输入框」 */
.core-box {
  position: relative;
  width: 100%;
  padding: 10px 12px;
  background: #f5f6fa;
  border: 1px solid var(--ah-border, #e6e8ef);
  border-radius: 6px;
  box-sizing: border-box;
}

.core-text {
  margin: 0;
  overflow: visible;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.55;
  color: #4b5563;
}

.core-badge {
  position: absolute;
  top: 8px;
  right: 10px;
  padding: 1px 6px;
  color: #9ca3af;
  background: #eef0f6;
  border-radius: 4px;
  font-size: 11px;
  line-height: 18px;
}

.stale-tag {
  margin-left: 6px;
}

/* 预览说明：原预览按钮已上移页脚，这里只留一行灰字 */
.l3-actions-hint {
  margin: 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

/* 折叠态追加区：与表单项留同样的上间距，展开后输入框不再额外缩进 */
.l3-extra-collapse {
  margin-left: 96px;
  border-top: none;
  border-bottom: none;
}

.l3-extra-collapse :deep(.el-collapse-item__header) {
  height: 32px;
  line-height: 32px;
  padding-left: 0;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  background: transparent;
  border-bottom: none;
}

.l3-extra-collapse :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-bottom: none;
}

.l3-extra-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

.preview-meta {
  margin: 0 0 10px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}

.preview-notice {
  margin-bottom: 8px;
}

/* 预览正文：等宽 + 保留换行，便于核对段落顺序 */
.preview-text {
  max-height: 52vh;
  margin: 0;
  overflow: auto;
  padding: 12px;
  background: #fafbfd;
  border: 1px solid var(--ah-border, #e6e8ef);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.gap {
  margin-left: 6px;
}
</style>
