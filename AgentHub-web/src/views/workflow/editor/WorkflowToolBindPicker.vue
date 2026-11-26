<template>
  <!--
    从「绑定工具」输入框展开：固定宽度浮层，单击即选中收起。
    不做双栏多选，也不改 ToolBindDialog / ResourceSelector 的确认语义。
  -->
  <el-popover
    :visible="panelOpen"
    :width="PANEL_WIDTH"
    placement="bottom-start"
    trigger="click"
    :disabled="disabled"
    popper-class="wf-tool-bind-popper"
    :teleported="true"
    @update:visible="onPanelVisible"
  >
    <template #reference>
      <!--
        外层承接 popover 的 click；清除按钮单独 stop，避免「清除」把浮层又点开。
      -->
      <div class="wf-tool-bind-ref" :class="{ 'is-disabled': disabled }">
        <el-input
          class="wf-tool-bind-trigger"
          :model-value="displayLabel"
          readonly
          :clearable="!!displayLabel && !disabled"
          :placeholder="placeholder"
          :disabled="disabled"
          @clear="onClear"
        >
          <template #suffix>
            <el-icon
              v-if="!displayLabel || disabled"
              class="wf-tool-bind-caret"
              :class="{ 'is-open': panelOpen }"
            >
              <ArrowDown />
            </el-icon>
          </template>
        </el-input>
      </div>
    </template>

    <div class="wf-tool-bind-panel">
      <header class="wf-tool-bind-head">
        <div class="wf-tool-bind-title">
          可选资源
          <span class="wf-tool-bind-count">{{ totalCount }}</span>
        </div>
        <el-input
          v-model="keyword"
          class="wf-tool-bind-search"
          :placeholder="searchPlaceholder"
          :prefix-icon="Search"
          clearable
          @click.stop
        />
      </header>

      <div v-loading="loading" class="wf-tool-bind-list">
        <template v-if="filteredGroups.length">
          <div v-for="group in filteredGroups" :key="group.groupName" class="wf-tool-bind-group">
            <div class="wf-tool-bind-group-head">
              <span class="wf-tool-bind-group-name">{{ group.groupName }}</span>
              <span class="wf-tool-bind-group-count">{{ group.items.length }}</span>
            </div>
            <div
              v-for="item in group.items"
              :key="item.id"
              class="wf-tool-bind-option"
              :class="{ 'is-checked': isSelected(item.id) }"
              @click="onPick(item)"
            >
              <div class="wf-tool-bind-option-body">
                <div class="tool-name" :title="item.name">{{ item.name }}</div>
                <div v-if="item.code" class="tool-code" :title="item.code">{{ item.code }}</div>
                <div class="tool-meta">
                  <el-tag
                    v-if="item.extra"
                    size="small"
                    effect="plain"
                    class="tool-tag"
                  >
                    {{ item.extra }}
                  </el-tag>
                  <span
                    v-if="item.description"
                    class="tool-desc"
                    :title="item.description"
                  >
                    {{ item.description }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </template>
        <el-empty
          v-else
          class="wf-tool-bind-empty"
          :image-size="64"
          :description="keyword.trim() ? '没有匹配的资源' : emptyText"
        />
      </div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
/**
 * 工作流画布「绑定工具」单选浮层。所属域：workflow（编辑器）。
 *
 * 职责：从 TOOL 节点属性栏展开固定宽度浮层，按可选资源左栏口径展示分组与行排版，
 * 单击一行即绑一个工具并收起。筛选 / 分组复用 bindResource，不搬 ResourceSelector 双栏弹窗。
 *
 * 约束：
 * - 只存工具项 id；不改运行时调工具方式；
 * - 停用工具不进候选列表；节点若已绑停用工具，输入框仍显示当前展示名，可清除后改绑；
 * - 不改 Agent 侧 ToolBindDialog 的多选确认语义（那边停用项仍可见但不可新选）。
 */
import { computed, ref, watch } from 'vue'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import type { ResourceOption } from '@/types/resource'
import type { ToolItemRow } from '@/types/tool'
import { buildToolGroups, filterResourceGroups } from '@/utils/bindResource'

/** 浮层最大宽度：对齐可选资源左栏体感，避免被长 API 名撑满 */
const PANEL_WIDTH = 380

const props = withDefaults(
  defineProps<{
    /** 当前绑定的工具项 id；空表示未绑 */
    modelValue?: number | string | null
    /** 工具目录全量（含停用），与画布既有 listAllItems 同源 */
    tools: ToolItemRow[]
    /** 只读 / 无编辑权时禁用展开与清除 */
    disabled?: boolean
    /** 目录加载中 */
    loading?: boolean
    /** 触发框占位 */
    placeholder?: string
    /** 搜索占位，与 Agent 配工具口径一致 */
    searchPlaceholder?: string
    /** 无数据空态文案 */
    emptyText?: string
    /**
     * 工具已从目录消失时的回退展示（如节点上缓存的 toolTitle）。
     * 不另造文案口径，由父层按现有字段传入。
     */
    fallbackLabel?: string
  }>(),
  {
    modelValue: undefined,
    disabled: false,
    loading: false,
    placeholder: '选择工具项',
    searchPlaceholder: '搜索工具中文名或英文代码',
    emptyText: '工具目录里还没有可绑定的工具',
    fallbackLabel: ''
  }
)

const emit = defineEmits<{
  /** 选中或清除后的工具项 id；清除时为 undefined */
  'update:modelValue': [value: number | undefined]
}>()

const panelOpen = ref(false)
const keyword = ref('')
/** 刚点过清除时短暂吞掉随后的 visible=true，避免 clear 冒泡把浮层又打开 */
let suppressOpenUntil = 0

/** 打开时清搜索词，避免上次关键字残留误导 */
watch(panelOpen, (open) => {
  if (open) {
    keyword.value = ''
  }
})

/**
 * 同步浮层显隐；禁用或刚清除时拒绝打开。
 *
 * @param open 目标显隐
 */
function onPanelVisible(open: boolean): void {
  if (props.disabled) {
    panelOpen.value = false
    return
  }
  if (open && Date.now() < suppressOpenUntil) {
    panelOpen.value = false
    return
  }
  panelOpen.value = open
}

/**
 * 候选分组：只收启用中的工具（status === 1）。
 * 停用项不出现在浮层里；已绑定的停用工具仍由 selectedTool 在输入框展示。
 * 不剔除当前选中项，方便看到选中底色。
 */
const groups = computed(() =>
  buildToolGroups(
    props.tools.filter((item) => item.status === 1),
    []
  )
)

/** 候选项总数（未按关键字过滤），给标题旁计数 */
const totalCount = computed(() =>
  groups.value.reduce((sum, group) => sum + group.items.length, 0)
)

/** 搜索后的分组；命中为空的组整段隐藏 */
const filteredGroups = computed(() => filterResourceGroups(groups.value, keyword.value))

/** 当前选中工具行，用于输入框展示名 */
const selectedTool = computed(() => {
  const id = props.modelValue
  if (id === undefined || id === null || id === '') {
    return undefined
  }
  const num = Number(id)
  return props.tools.find((item) => item.id === num)
})

/**
 * 输入框文案：优先展示名（title），缺则英文码；目录里找不到时用父层回退。
 * 已绑的停用工具不进列表，但输入框仍显示它的展示名。
 */
const displayLabel = computed(() => {
  const tool = selectedTool.value
  if (tool) {
    return tool.title?.trim() || tool.name
  }
  if (props.modelValue !== undefined && props.modelValue !== null && props.modelValue !== '') {
    return props.fallbackLabel?.trim() || String(props.modelValue)
  }
  return ''
})

/**
 * 是否当前选中行。
 *
 * @param id 候选项 id
 */
function isSelected(id: number | string): boolean {
  if (props.modelValue === undefined || props.modelValue === null || props.modelValue === '') {
    return false
  }
  return Number(props.modelValue) === Number(id)
}

/**
 * 单击一行：回写工具项 id 并收起。列表里只有启用项。
 *
 * @param item 点中的候选项
 */
function onPick(item: ResourceOption): void {
  emit('update:modelValue', Number(item.id))
  panelOpen.value = false
  keyword.value = ''
}

/** 清除绑定：保留输入框清除按钮语义，并短暂抑制随后的误开浮层 */
function onClear(): void {
  suppressOpenUntil = Date.now() + 200
  emit('update:modelValue', undefined)
  panelOpen.value = false
  keyword.value = ''
}
</script>

<style scoped>
.wf-tool-bind-ref {
  width: 100%;
}

.wf-tool-bind-ref.is-disabled {
  cursor: not-allowed;
}

.wf-tool-bind-trigger {
  width: 100%;
}

.wf-tool-bind-trigger :deep(.el-input__wrapper) {
  cursor: pointer;
}

.wf-tool-bind-ref.is-disabled .wf-tool-bind-trigger :deep(.el-input__wrapper) {
  cursor: not-allowed;
}

.wf-tool-bind-caret {
  color: var(--ah-text-muted);
  transition: transform 0.15s ease;
}

.wf-tool-bind-caret.is-open {
  transform: rotate(180deg);
}

.wf-tool-bind-panel {
  display: flex;
  flex-direction: column;
  /* 固定体量：宽由 popover width 管，高限制避免刷屏 */
  max-height: 420px;
  min-width: 0;
}

.wf-tool-bind-head {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 2px 0 10px;
  border-bottom: 1px solid var(--ah-border);
}

.wf-tool-bind-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 600;
}

.wf-tool-bind-count {
  color: var(--ah-text-muted);
  font-size: 12px;
  font-weight: 500;
}

.wf-tool-bind-search :deep(.el-input__wrapper) {
  height: 30px;
}

.wf-tool-bind-list {
  flex: 1 1 auto;
  min-height: 120px;
  max-height: 340px;
  overflow: auto;
  padding: 8px 2px 4px 0;
}

.wf-tool-bind-group + .wf-tool-bind-group {
  margin-top: 10px;
}

.wf-tool-bind-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.wf-tool-bind-group-count {
  padding: 0 6px;
  border-radius: 8px;
  background: var(--ah-neutral-tint);
  color: var(--ah-text-muted);
  font-size: 12px;
  font-weight: 500;
}

/* 行排版对齐 ToolBindDialog / 可选资源左栏 */
.wf-tool-bind-option {
  display: flex;
  align-items: flex-start;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.wf-tool-bind-option:hover:not(.is-disabled) {
  background: var(--ah-primary-tint);
  border-color: var(--el-color-primary-light-8);
}

.wf-tool-bind-option.is-checked {
  background: var(--ah-primary-tint);
  border-color: var(--el-color-primary-light-7);
}

.wf-tool-bind-option-body {
  flex: 1 1 auto;
  min-width: 0;
  line-height: 1.4;
}

.tool-name {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  min-width: 0;
}

.tool-tag {
  flex: none;
  --el-tag-font-size: 12px;
  --el-tag-padding: 0 5px;
  --el-tag-height: 18px;
}

.tool-desc {
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-tool-bind-empty {
  padding: 12px 0;
}
</style>

<!-- popper 挂到 body：scoped 管不到外层宽度，这里只锁 max-width -->
<style>
.wf-tool-bind-popper.el-popover.el-popper {
  max-width: 380px;
  padding: 12px;
  box-sizing: border-box;
}
</style>
