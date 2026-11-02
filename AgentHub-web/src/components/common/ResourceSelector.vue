<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="880px"
    class="resource-selector"
    :close-on-click-modal="false"
    @update:model-value="onVisibleChange"
  >
    <div class="rs-body">
      <!-- ============ 左栏：可选资源（分组 + 搜索 + 勾选） ============ -->
      <section class="rs-pane rs-pane--source">
        <header class="rs-pane-head">
          <div class="rs-pane-toolbar">
            <div class="rs-pane-title">
              可选资源
              <span class="rs-count">{{ totalCount }}</span>
            </div>
          </div>
          <el-input
            v-model="keyword"
            class="rs-search"
            :placeholder="searchPlaceholder"
            :prefix-icon="Search"
            clearable
          />
        </header>

        <div v-loading="loading" class="rs-list">
          <template v-if="filteredGroups.length">
            <div v-for="group in filteredGroups" :key="group.groupName" class="rs-group">
              <div class="rs-group-head">
                <span class="rs-group-name">{{ group.groupName }}</span>
                <span class="rs-group-count">{{ group.items.length }}</span>
              </div>
              <!--
                整行可点：命中区域更大，勾选框本身也保留。
                行点击里会跳过 checkbox 热区，避免与 @change 叠加导致一次点击翻两次。
                键盘可达性交给 checkbox 本体，行不重复挂 role，避免读屏读到两层控件。
              -->
              <div
                v-for="item in group.items"
                :key="item.id"
                class="rs-option"
                :class="{ 'is-checked': isChecked(item.id), 'is-disabled': isBlocked(item) }"
                @click="onRowClick(item, $event)"
              >
                <el-checkbox
                  class="rs-option-check"
                  :model-value="isChecked(item.id)"
                  :disabled="isBlocked(item)"
                  @change="toggle(item)"
                  @click.stop
                />
                <div class="rs-option-body">
                  <!-- 业务自定义：工具展示中文名 + 英文代码，Skill 展示标题 + 简介 + 关键词 -->
                  <slot name="item" :item="item" :checked="isChecked(item.id)">
                    <div class="rs-option-title" :title="item.name">{{ item.name }}</div>
                    <div v-if="item.code" class="rs-option-code" :title="item.code">{{ item.code }}</div>
                    <div v-if="item.description" class="rs-option-desc" :title="item.description">
                      {{ item.description }}
                    </div>
                  </slot>
                </div>
              </div>
            </div>
          </template>
          <el-empty
            v-else
            class="rs-empty"
            :image-size="72"
            :description="keyword ? '没有匹配的资源' : emptyText"
          />
        </div>
      </section>

      <div class="rs-divider" aria-hidden="true" />

      <!-- ============ 右栏：已选区（数量徽标 + 逐条移除） ============ -->
      <section class="rs-pane rs-pane--selected">
        <header class="rs-pane-head">
          <div class="rs-pane-toolbar">
            <div class="rs-pane-title">
              已选
              <span class="rs-badge" :class="{ 'is-empty': !draft.length }">{{ draft.length }}</span>
            </div>
            <button v-if="draft.length" type="button" class="rs-clear" @click="clearAll">清空</button>
          </div>
          <!--
            与左栏搜索框同高的占位说明：两栏列表必须从同一水平线起排，
            否则右侧会整体上移，看起来像两栏没对齐。
          -->
          <p class="rs-pane-hint">点右侧 × 可移除该项</p>
        </header>

        <div class="rs-list">
          <template v-if="selectedOptions.length">
            <div v-for="item in selectedOptions" :key="item.id" class="rs-chip" :class="{ 'is-missing': item.missing }">
              <div class="rs-chip-body">
                <slot name="selected-item" :item="item" :remove="() => removeSelected(item)">
                  <span class="rs-chip-name" :title="item.name">{{ item.name }}</span>
                  <span v-if="item.code" class="rs-chip-code" :title="item.code">{{ item.code }}</span>
                </slot>
              </div>
              <button
                type="button"
                class="rs-chip-remove"
                :aria-label="`移除 ${item.name}`"
                :title="`移除 ${item.name}`"
                @click="removeSelected(item)"
              >
                ×
              </button>
            </div>
          </template>
          <el-empty v-else class="rs-empty" :image-size="72" description="还没有选择资源" />
        </div>
      </section>
    </div>

    <template #footer>
      <el-button :disabled="confirming" @click="cancel">取消</el-button>
      <el-button type="primary" :loading="confirming" :disabled="!draft.length" @click="confirm">
        确认添加
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 双栏穿梭资源选择器（弹窗）。所属域：前端通用（components/common）。
 *
 * 解决的问题：业务里「多选 + 英文代码看不懂 + 无分组 + 无描述」的下拉框体验太差。
 * 左侧按 groupName 分组展示全部候选项并带搜索，右侧实时汇总已选，叉号即取消勾选。
 *
 * 契约：
 * - `v-model`（modelValue）：已选 ID 数组。**只在「确认添加」时**回写并抛 confirm，
 *   取消则丢弃本次勾选——这是「选完再提交」的语义，调用方不必自己去回滚草稿。
 * - `v-model:visible`：弹窗开关。每次打开都从 modelValue 重建草稿，
 *   因此纯「新增绑定」场景只需把 modelValue 保持为空数组。
 * - 勾选状态与右侧列表是同一条 draft 数组的两面，天然双向同步，无需额外搬运。
 *
 * 不做的事：不请求接口、不认识业务字段。数据由调用方映射成 ResourceGroup[]，
 * 展示细节交给 item / selected-item 插槽。
 */
import { computed, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { ResourceGroup, ResourceOption } from '@/types/resource'

const props = withDefaults(
  defineProps<{
    /** 已选 ID 数组（v-model） */
    modelValue: (number | string)[]
    /** 可选数据源，按 groupName 分组 */
    dataSource: ResourceGroup[]
    /** 弹窗标题，如「添加工具」 */
    title: string
    /** 搜索框占位符 */
    searchPlaceholder?: string
    /** 弹窗开关（v-model:visible） */
    visible?: boolean
    /** 数据源是否加载中；为 true 时左栏显示 loading 遮罩 */
    loading?: boolean
    /** 左栏无数据（且未搜索）时的空文案 */
    emptyText?: string
    /** 确认按钮 loading，由调用方的提交请求驱动 */
    confirming?: boolean
  }>(),
  {
    searchPlaceholder: '搜索名称或代码',
    visible: false,
    loading: false,
    emptyText: '暂无可选资源',
    confirming: false
  }
)

const emit = defineEmits<{
  /** 确认后的已选 ID 数组 */
  'update:modelValue': [ids: (number | string)[]]
  /** 弹窗开关 */
  'update:visible': [visible: boolean]
  /** 确认添加，与 update:modelValue 携带同一份 ID；调用方在这里发起绑定请求 */
  confirm: [ids: (number | string)[]]
  /** 取消 / 关闭，调用方可据此清掉临时态 */
  cancel: []
}>()

/** 左栏搜索词；只过滤左栏，右侧已选不受影响 */
const keyword = ref('')
/** 本次弹窗内的草稿勾选，确认前不写回 modelValue */
const draft = ref<(number | string)[]>([])

/**
 * 打开时用 modelValue 重建草稿。
 * 必须放在 visible 的 watch 里而不是 computed：草稿要能被用户在本轮里改来改去，
 * 直接绑 modelValue 会让「取消」失去回滚能力。
 */
watch(
  () => props.visible,
  (open) => {
    if (!open) {
      return
    }
    draft.value = [...props.modelValue]
    keyword.value = ''
  },
  { immediate: true }
)

/**
 * 全量候选项索引，供 O(1) 判断勾选态与回填右侧列表。
 * dataSource 变化（如重新拉取）时自动重建。
 */
const optionIndex = computed(() => {
  const map = new Map<number | string, ResourceOption>()
  for (const group of props.dataSource) {
    for (const item of group.items) {
      map.set(item.id, item)
    }
  }
  return map
})

/** 候选项总数，用于左栏标题旁的计数 */
const totalCount = computed(() =>
  props.dataSource.reduce((sum, group) => sum + group.items.length, 0)
)

/**
 * 搜索后的分组：命中为空的分组整体隐藏；分组顺序与组内顺序保持数据源原样。
 * 搜索范围覆盖名称、代码、说明与标签，避免只记得英文名时搜不到。
 */
const filteredGroups = computed<ResourceGroup[]>(() => {
  const word = keyword.value.trim().toLowerCase()
  if (!word) {
    return props.dataSource
  }
  const hit = (item: ResourceOption) =>
    [item.name, item.code, item.description, item.extra, ...(item.tags || [])]
      .filter((text): text is string => !!text)
      .some((text) => text.toLowerCase().includes(word))
  return props.dataSource
    .map((group) => ({ groupName: group.groupName, items: group.items.filter(hit) }))
    .filter((group) => group.items.length > 0)
})

/**
 * 右侧已选列表：按勾选先后顺序回填完整对象。
 * 数据源里找不到的 id（目标已被删除）合成一条 missing 占位，
 * 保证它仍出现在右侧、可被移除，而不是默默消失。
 */
const selectedOptions = computed<ResourceOption[]>(() =>
  draft.value.map((id) => optionIndex.value.get(id) ?? {
    id,
    name: '资源已删除',
    code: String(id),
    disabled: true,
    missing: true
  })
)

/**
 * 某项当前是否被勾选。
 *
 * @param id 候选项 id
 */
function isChecked(id: number | string): boolean {
  return draft.value.includes(id)
}

/**
 * 某项是否禁止「新勾选」。
 * 已勾选的停用项放行，否则会出现「勾上了却取消不掉」的死角。
 *
 * @param item 候选项
 */
function isBlocked(item: ResourceOption): boolean {
  return item.disabled === true && !isChecked(item.id)
}

/**
 * 勾选 / 取消勾选一项（左右两栏共用同一个入口）。
 * 新增项追加到末尾，右侧按勾选顺序排列，与提交顺序一致。
 *
 * @param item 候选项
 */
function toggle(item: ResourceOption): void {
  if (isBlocked(item)) {
    return
  }
  const index = draft.value.indexOf(item.id)
  if (index >= 0) {
    draft.value = draft.value.filter((id) => id !== item.id)
    return
  }
  draft.value = [...draft.value, item.id]
}

/**
 * 行点击：点 checkbox 本体时不处理（交给 @change），其余区域整行切换。
 *
 * @param item  候选项
 * @param event 点击事件，用于排除 checkbox 热区
 */
function onRowClick(item: ResourceOption, event: Event): void {
  const target = event.target as HTMLElement | null
  if (target?.closest('.el-checkbox')) {
    return
  }
  toggle(item)
}

/**
 * 右侧叉号移除：等价于取消左侧勾选，两侧共用 draft，天然同步。
 *
 * @param item 右侧已选项
 */
function removeSelected(item: ResourceOption): void {
  draft.value = draft.value.filter((id) => id !== item.id)
}

/** 一键清空本次勾选（不动已落库的绑定，绑定解绑仍在业务表格里做）。 */
function clearAll(): void {
  draft.value = []
}

/**
 * 弹窗开关同步。
 * 关闭（点右上角 ×、遮罩或 ESC）按取消处理：草稿丢弃，避免下次打开残留脏勾选。
 *
 * @param open 目标开关值
 */
function onVisibleChange(open: boolean): void {
  if (!open) {
    draft.value = []
    keyword.value = ''
  }
  emit('update:visible', open)
}

/** 确认添加：回写 v-model 并抛出 confirm，由调用方发请求；成功后自行关闭弹窗。 */
function confirm(): void {
  const ids = [...draft.value]
  emit('update:modelValue', ids)
  emit('confirm', ids)
}

/** 取消：丢弃草稿并关闭。 */
function cancel(): void {
  draft.value = []
  keyword.value = ''
  emit('update:visible', false)
  emit('cancel')
}
</script>

<style scoped>
/* 弹窗内容留白收紧，两栏能占满高度 */
.resource-selector :deep(.el-dialog__body) {
  padding: 0 16px 4px;
}

.rs-body {
  display: flex;
  align-items: stretch;
  gap: 0;
  height: 460px;
  min-height: 0;
}

.rs-pane {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

/* 左栏略宽：候选项信息量更大 */
.rs-pane--source {
  flex: 1.15 1 0;
}

.rs-pane-head {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 4px 0 10px;
  border-bottom: 1px solid var(--ah-border);
}

.rs-pane-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 22px;
}

.rs-pane-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 650;
  color: var(--ah-text);
}

.rs-count {
  color: var(--ah-text-muted);
  font-size: 12px;
  font-weight: 500;
}

/* 已选数量徽标：有选中时主色，空态灰化避免喧宾夺主 */
.rs-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--ah-primary);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.rs-badge.is-empty {
  background: #e5e7eb;
  color: #6b7280;
}

.rs-clear {
  flex: none;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ah-primary);
  font-size: 12px;
  cursor: pointer;
}

.rs-clear:hover {
  color: var(--ah-primary-hover);
  text-decoration: underline;
}

.rs-search :deep(.el-input__wrapper) {
  height: 30px;
}

/* 右栏占位说明：占住与搜索框同高的位置，保证两栏列表起点对齐 */
.rs-pane-hint {
  height: 30px;
  margin: 0;
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 30px;
}

.rs-list {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 8px 2px 8px 0;
}

/* 分组标题：小字灰色 + 分隔线，弱于选项本身 */
.rs-group + .rs-group {
  margin-top: 10px;
}

.rs-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.rs-group-count {
  padding: 0 6px;
  border-radius: 8px;
  background: #f3f4f6;
  color: var(--ah-text-muted);
  font-size: 11px;
  font-weight: 500;
}

/* 选项行：默认无边框，悬浮/选中才给底色，密集列表也更干净 */
.rs-option {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.rs-option:hover:not(.is-disabled) {
  background: #f7f7ff;
  border-color: #e4e2ff;
}

.rs-option.is-checked {
  background: #f4f3ff;
  border-color: #d5d3ff;
}

.rs-option.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.rs-option-check {
  flex: none;
  height: 20px;
  margin-top: 1px;
}

.rs-option-body {
  flex: 1 1 auto;
  min-width: 0;
  line-height: 1.4;
}

.rs-option-title {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rs-option-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rs-option-desc {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 2px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/* 中缝：1px 竖线 + 两侧呼吸位，代替两个重边框盒子 */
.rs-divider {
  flex: none;
  width: 1px;
  margin: 4px 16px;
  background: var(--ah-border);
}

/* 右侧已选项：紧凑胶囊，名称 + 代码 + 叉号 */
.rs-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px 7px 10px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: #fff;
}

.rs-chip + .rs-chip {
  margin-top: 6px;
}

.rs-chip:hover {
  border-color: #c5c2ff;
}

.rs-chip.is-missing {
  border-style: dashed;
  background: #fffbf5;
}

.rs-chip-body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
}

.rs-chip-name {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rs-chip-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rs-chip-remove {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #9ca3af;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.rs-chip-remove:hover {
  color: #ef4444;
  background: #fef2f2;
}

.rs-empty {
  padding: 24px 0;
}

/* 窄屏：两栏改上下堆叠，避免各栏被压到读不出内容 */
@media (max-width: 720px) {
  .rs-body {
    height: auto;
    flex-direction: column;
  }

  .rs-pane {
    height: 240px;
  }

  .rs-divider {
    width: auto;
    height: 1px;
    margin: 12px 0;
  }
}
</style>
