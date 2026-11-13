<template>
  <!--
    单个用途槽的绑定列表。所属域：agent（展示组件，无副作用）。
    「模型配置」Tab 与「记忆配置」Tab 共用：一处维护 ≤3 上限、上→下优先级、拖拽与箭头交互，
    避免两页各写一套排序规则导致 priority 语义漂移。
    只负责渲染与排序交互，落库由调用方通过 add / reorder / remove 事件完成。
  -->
  <div class="slot">
    <div class="slot-head">
      <div class="slot-title">
        <span class="slot-name">{{ capabilityOptionLabel(purpose) }}</span>
        <!-- CHAT 是唯一有下限的槽，保留最小合规提示 -->
        <el-tag v-if="purpose === 'CHAT'" size="small" type="warning" effect="plain">至少 1</el-tag>
        <span class="slot-count">{{ sortedRows.length }}/{{ PURPOSE_SLOT_MAX }}</span>
      </div>
      <el-button
        v-if="canBind"
        type="primary"
        link
        :disabled="blocked || sortedRows.length >= PURPOSE_SLOT_MAX"
        @click="emit('add', purpose)"
      >
        + 添加
      </el-button>
    </div>

    <!-- 槽位说明：把「行为开关在哪个 Tab」「不借 SUMMARY」这类边界写清，避免用户误解 -->
    <p v-if="hint" class="slot-hint">{{ hint }}</p>

    <!--
      提示词摘要 + 入口：挂在**槽级**而不是某一行内。
      放槽级的原因：L3 四列只挂在槽内主模型上，与"第几行"无关；
      若塞进行内（旧实现）一旦该行未渲染、顺序变化或窄栏挤压，入口就会整块消失，
      线上表现为「配了模型却看不到配置提示词」。槽级渲染与绑定行数无关，恒定可见。
      CHAT 不走这里（入口在基本信息），由 purposeSupportsPromptEntry 统一排除。
    -->
    <div v-if="showPromptEntry" class="prompt-bar">
      <div class="prompt-bar-main">
        <span class="prompt-bar-label">提示词</span>
        <span class="prompt-summary" :class="{ 'is-empty': !mainRow?.prompt?.templateId }">
          {{ promptSummary(mainRow) }}
        </span>
      </div>
      <el-button
        class="prompt-btn"
        link
        type="primary"
        :disabled="!mainRow"
        :title="promptButtonTitle"
        @click="onOpenPrompt"
      >
        配置提示词
      </el-button>
    </div>

    <!-- 有绑定：横向卡片列表，顺序即降级优先级 -->
    <div v-if="sortedRows.length" class="bind-list">
      <div v-for="(row, index) in sortedRows" :key="row.id" class="bind-row"
        :class="{
          'is-dragging': drag.from === index,
          'is-drop-target': drag.over === index && drag.from !== index,
          'is-locked': !canBind
        }"
        @dragstart="onDragStart(index, $event)"
        @dragover="onDragOver(index, $event)"
        @dragleave="onDragLeave(index, $event)"
        @drop="onDrop(index, $event)"
        @dragend="resetDrag"
      >
        <!-- 拖拽手柄：只有此处可起拖，点「移除」不会误触换序 -->
        <span v-if="canBind" class="drag-handle" title="拖拽调整优先级" draggable="true">≡</span>
        <!-- 左侧优先级标识：首条为主模型，其余依次为降级 N -->
        <span class="rank-tag" :class="index === 0 ? 'is-main' : 'is-backup'">{{ rankLabel(index) }}</span>
        <span class="bind-name" :title="row.displayName || '实例已删除'">
          {{ row.displayName || '实例已删除' }}
        </span>
        <span v-if="canBind" class="bind-actions">
          <button
            type="button"
            class="arrow-btn"
            title="提高优先级"
            :disabled="index === 0"
            @click="moveBy(index, -1)"
          >
            <el-icon><CaretTop /></el-icon>
          </button>
          <button
            type="button"
            class="arrow-btn"
            title="降低优先级"
            :disabled="index >= sortedRows.length - 1"
            @click="moveBy(index, 1)"
          >
            <el-icon><CaretBottom /></el-icon>
          </button>
          <el-button link type="danger" @click="emit('remove', row)">移除</el-button>
        </span>
      </div>
    </div>

    <!-- 空槽：虚线框按钮代替空表格；整组不可用时退化为灰字 -->
    <button v-else-if="canBind && !blocked" type="button" class="empty-add" @click="emit('add', purpose)">
      + 添加模型
    </button>
    <p v-else class="empty-text">{{ blocked ? disabledText : '暂未绑定模型' }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 用途槽绑定列表（单用途）。所属域：agent。
 * 输入按「有效用途 == purpose」筛好的绑定行，组件内部再按槽内序号 / priority / id 排序，
 * 使上→下即降级顺序；排序结果通过 reorder 事件交回调用方换算 priority 落库。
 */
import { computed, reactive, watch, type PropType } from 'vue'
import { CaretBottom, CaretTop } from '@element-plus/icons-vue'
import { usePromptTemplateCatalog } from '@/composables/promptTemplateCatalog'
import type { ModelBind } from '@/types/agent'
import { PURPOSE_SLOT_MAX, capabilityOptionLabel, normalizeCapability } from '@/types/model'
import { purposeSupportsPromptEntry } from '@/utils/promptEntry'

/**
 * 组件入参。
 * <p>
 * 这里刻意用**运行时对象声明**而不是纯类型声明：`promptSupported` 是「缺省即允许」的开关，
 * 而 Vue 3 对 `type: Boolean` 的 prop 有一条固定规则——**父级不传时强制转成 `false`**
 * （不是 `undefined`）。纯类型声明无法补 default，会导致 `promptSupported !== false` 恒为 false，
 * 入口被静默隐藏（线上「配了模型却看不到配置提示词」就是这条规则踩的坑）。
 * 显式写 `default: undefined` 才会跳过布尔强转，让「不传」真正表示「未指定」。
 */
const props = defineProps({
  /** 用途码，如 CHAT / REWRITE / SUMMARY / MEMORY */
  purpose: { type: String, required: true },
  /** 该用途下已有的绑定行（顺序不重要，组件内部重排） */
  rows: { type: Array as PropType<ModelBind[]>, required: true },
  /** 是否有绑定权限（agent:bind） */
  canBind: { type: Boolean, default: false },
  /** 整组不可用（模型中心没有该组任何已启用实例）时禁用添加入口 */
  disabled: { type: Boolean, default: false },
  /** 整组不可用时的灰字原因 */
  disabledText: { type: String, default: '' },
  /** 槽位说明文案；空则不渲染该行 */
  hint: { type: String, default: '' },
  /**
   * 是否允许展示「配置提示词」入口；**不传或传 true 都表示允许**，只有显式 false 才整体关闭。
   * 必须保留 `default: undefined`：否则 Vue 会把未传的布尔 prop 转成 false，入口会永久消失。
   */
  promptSupported: { type: Boolean, default: undefined }
})

const emit = defineEmits<{
  add: [purpose: string]
  /** 槽内新顺序（绑定 id 数组，从高优先级到低优先级），由调用方换算 priority 落库 */
  reorder: [purpose: string, orderedIds: number[]]
  remove: [row: ModelBind]
  /** 打开该槽主模型的 L3 提示词配置 */
  prompt: [purpose: string, row: ModelBind]
}>()

/**
 * 该用途槽是否展示「提示词摘要 + 配置提示词」入口。
 * 判定收敛到 {@link purposeSupportsPromptEntry}：白名单 + 父级开关；
 * 与「是否已绑模型」「是否有 bind 权限」都无关——入口恒定可见，避免时显时隐。
 */
const showPromptEntry = computed(() => purposeSupportsPromptEntry(props.purpose, props.promptSupported))

const { ensureLoaded, promptTemplateName, promptTemplateDefault } = usePromptTemplateCatalog()

// 槽内有绑定时确保模板名已缓存，摘要行才能显示「摘要模板 · v2」而不是 id
watch(
  () => [props.purpose, showPromptEntry.value, props.rows.map((row) => row.prompt?.templateId).join(',')],
  () => {
    if (showPromptEntry.value) {
      void ensureLoaded(props.purpose)
    }
  },
  { immediate: true }
)

/**
 * 摘要文案：与 CHAT 侧一致，已绑显示「模板名 · vN」；
 * 未绑时显示「保存时会自动钉哪条」而不是空态的「未配置提示词模板」——
 * 首次绑定该用途模型时后端会钉住平台生效版本，页面不该把它显示成「没有配置」。
 *
 * @param row 槽内主模型绑定行
 * @return 摘要文本
 */
function promptSummary(row?: ModelBind) {
  if (!row) {
    return '请先绑定该用途的模型'
  }
  const bound = row.prompt
  if (!bound?.templateId) {
    const def = promptTemplateDefault(props.purpose)
    if (!def) {
      return '未绑定（该用途暂无生效模板）'
    }
    const defName = def.name || def.templateKey || `模板 #${def.id}`
    return `未绑定 · 保存时自动钉「${defName} · v${def.version}」`
  }
  const version = bound.templateVersion
  const name = promptTemplateName(bound.templateId) || `模板 #${bound.templateId}`
  return version ? `${name} · v${version}` : name
}

/**
 * 按钮悬浮说明：把「已挂哪个模板、哪一版」直接写在 title 上，
 * 避免用户点开才发现自己配的是别的版本。
 *
 * @param row 槽内主模型绑定行
 * @return 提示文案
 */
function promptTitle(row: ModelBind) {
  const bound = row.prompt
  if (bound?.templateId) {
    return `已挂模板 v${bound.templateVersion ?? '-'}，点击修改可覆盖段落与追加要求`
  }
  return '为该用途选择提示词模板并配置可覆盖段落'
}

/** 拖拽中的起止下标；仅用于视觉反馈与落点计算 */
const drag = reactive({ from: -1, over: -1 })

/** 是否禁用添加入口（无可用实例） */
const blocked = computed(() => props.disabled === true)

/**
 * 本槽绑定行：按槽内序号、priority、id 依次升序，顺序即降级顺序。
 */
const sortedRows = computed(() =>
  props.rows
    .filter((row) => effectiveOf(row) === props.purpose)
    .slice()
    .sort((a, b) => {
      const ra = a.slotRank || 999
      const rb = b.slotRank || 999
      if (ra !== rb) return ra - rb
      if (a.priority !== b.priority) return a.priority - b.priority
      return a.id - b.id
    })
)

/**
 * 槽内主模型（排序后第一条）：只有它承载 L3 四列，槽级摘要与入口都指向它。
 * 未绑任何模型时为空，此时入口置灰并提示先绑模型。
 */
const mainRow = computed<ModelBind | undefined>(() => sortedRows.value[0])

/** 入口按钮的悬浮说明：无绑定行时提示先绑模型；无权限时说明只读 */
const promptButtonTitle = computed(() => {
  const row = mainRow.value
  if (!row) {
    return '请先在下方绑定该用途的模型，再配置提示词'
  }
  if (!props.canBind) {
    return '只读查看：保存需「Agent 绑定」权限（agent:bind）'
  }
  return promptTitle(row)
})

/**
 * 点击「配置提示词」：把槽内主模型交给父级打开统一大弹窗。
 * 槽级渲染后不再依赖行内事件绑定，避免入口与行一起被裁掉或漏渲染。
 */
function onOpenPrompt() {
  const row = mainRow.value
  if (!row) {
    return
  }
  emit('prompt', props.purpose, row)
}

/**
 * 绑定行的有效用途：purpose 优先，否则跟实例 capability（旧码已规范化）。
 *
 * @param row 绑定行
 * @return 规范用途码
 */
function effectiveOf(row: ModelBind) {
  return normalizeCapability(row.effectivePurpose || row.purpose || row.capability)
}

/**
 * 优先级标识文案：第 1 条是主模型，其余按位次降级。
 *
 * @param index 槽内从 0 开始的下标
 * @return 标签文本
 */
function rankLabel(index: number) {
  return index === 0 ? '主模型' : `降级 ${index}`
}

/**
 * 上下箭头换序：与相邻位次互换后整槽重排提交。
 *
 * @param index 当前位次
 * @param delta -1 提高优先级 / 1 降低优先级
 */
function moveBy(index: number, delta: number) {
  const ids = sortedRows.value.map((row) => row.id)
  const target = index + delta
  if (target < 0 || target >= ids.length) {
    return
  }
  const [moved] = ids.splice(index, 1)
  ids.splice(target, 0, moved)
  emit('reorder', props.purpose, ids)
}

/** 记录拖拽起点；只有可绑定角色才能拖。 */
function onDragStart(index: number, event: DragEvent) {
  drag.from = index
  drag.over = index
  if (event.dataTransfer) {
    // 必须写入数据，Firefox 才会真正开始拖拽
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(index))
  }
}

/** 经过其他卡片时放行落点，让 drop 事件可用。 */
function onDragOver(index: number, event: DragEvent) {
  if (drag.from < 0) {
    return
  }
  event.preventDefault()
  drag.over = index
}

/** 离开卡片时清掉高亮；指针仍在卡片内部子元素间移动时不误清。 */
function onDragLeave(index: number, event: DragEvent) {
  if (drag.over !== index) {
    return
  }
  const box = event.currentTarget as HTMLElement | null
  const next = event.relatedTarget as Node | null
  if (box && next && box.contains(next)) {
    return
  }
  drag.over = -1
}

/** 落在某张卡片上：把起点插到落点位置，整槽重排提交。 */
function onDrop(index: number, event: DragEvent) {
  if (drag.from < 0) {
    resetDrag()
    return
  }
  event.preventDefault()
  const ids = sortedRows.value.map((row) => row.id)
  const [moved] = ids.splice(drag.from, 1)
  ids.splice(Math.min(index, ids.length), 0, moved)
  const from = drag.from
  resetDrag()
  if (from !== index) {
    emit('reorder', props.purpose, ids)
  }
}

/** 结束或取消拖拽时复位状态。 */
function resetDrag() {
  drag.from = -1
  drag.over = -1
}
</script>

<style scoped>
.slot {
  display: flex;
  flex-direction: column;
}

.slot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.slot-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.slot-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ah-text, #1f2937);
}

.slot-count {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

/* 槽位说明：灰字一行，语义固定，避免用户在错误的位置找配置入口 */
.slot-hint {
  margin: -2px 0 8px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.bind-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 横向卡片：细边框 + 微圆角，一行放下手柄、优先级、模型名与操作 */
.bind-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  background: #fff;
  border: 1px solid var(--ah-border, #ebeef5);
  border-radius: 6px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, opacity 0.15s ease;
}

.bind-row:hover {
  border-color: #d9dcea;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.06);
}

.bind-row.is-locked {
  background: #fcfcfd;
}

.bind-row.is-dragging {
  opacity: 0.45;
}

/* 落点用主色内描边提示，不用位移动画，避免列表跳动 */
.bind-row.is-drop-target {
  border-color: var(--ah-primary, #635bff);
  box-shadow: inset 0 0 0 1px var(--ah-primary, #635bff);
}

.drag-handle {
  flex: none;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 14px;
  line-height: 1;
  cursor: grab;
  user-select: none;
}

.drag-handle:active {
  cursor: grabbing;
}

.rank-tag {
  flex: none;
  min-width: 54px;
  padding: 1px 8px;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
  border-radius: 4px;
}

/* 主模型用主色，降级用中性灰，靠颜色区分层级 */
.rank-tag.is-main {
  color: var(--ah-primary, #635bff);
  background: rgba(99, 91, 255, 0.1);
}

.rank-tag.is-backup {
  color: var(--ah-text-secondary, #6b7280);
  background: #f2f3f7;
}

.bind-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--ah-text, #1f2937);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 提示词摘要：与模型名同排，弱化处理；未配置时用更浅的灰提示「还没挂模板」 */
.prompt-summary {
  flex: none;
  max-width: 260px;
  overflow: hidden;
  padding: 1px 8px;
  color: var(--ah-text-secondary, #6b7280);
  background: #f4f5f9;
  border-radius: 4px;
  font-size: 12px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prompt-summary.is-empty {
  color: var(--ah-text-muted, #9ca3af);
  background: #fafafc;
}

/* 提示词摘要条：槽级独立一行，位于绑定卡片之上，与槽名同列对齐 */
.prompt-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding: 6px 10px;
  background: #fafbfd;
  border: 1px solid var(--ah-border, #e6e8ef);
  border-radius: 6px;
}

.prompt-bar-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.prompt-bar-label {
  flex: none;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

/* 「配置提示词」按钮：文案比旧「提示词」更长，收窄内边距避免撑破卡片 */
.prompt-btn {
  flex: none;
  padding: 0 2px;
  font-size: 13px;
}

.bind-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 2px;
}

/* 极简箭头：无边框无文字，只在悬浮时变主色 */
.arrow-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  color: var(--ah-text-muted, #9ca3af);
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.arrow-btn:hover:not(:disabled) {
  color: var(--ah-primary, #635bff);
  background: rgba(99, 91, 255, 0.08);
}

.arrow-btn:disabled {
  color: #d5d8e2;
  cursor: not-allowed;
}

/* 空槽：虚线框按钮，点击直接进添加流程 */
.empty-add {
  width: 100%;
  padding: 9px 12px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  background: #fcfcfd;
  border: 1px dashed var(--ah-border, #d9dcea);
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}

.empty-add:hover {
  color: var(--ah-primary, #635bff);
  background: rgba(99, 91, 255, 0.04);
  border-color: var(--ah-primary, #635bff);
}

.empty-text {
  margin: 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}
</style>
