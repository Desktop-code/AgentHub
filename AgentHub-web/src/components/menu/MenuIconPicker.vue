<template>
  <el-popover
    v-model:visible="panelOpen"
    :width="360"
    placement="bottom-start"
    trigger="click"
    popper-class="menu-icon-popper"
  >
    <template #reference>
      <div class="icon-field" :class="{ 'is-empty': !modelValue }">
        <span class="icon-preview">
          <el-icon v-if="modelValue"><component :is="iconOf(modelValue)" /></el-icon>
          <el-icon v-else class="is-placeholder"><Search /></el-icon>
        </span>
        <span class="icon-name">{{ modelValue || '选择图标' }}</span>
        <el-icon class="icon-caret"><ArrowDown /></el-icon>
      </div>
    </template>

    <div class="icon-panel">
      <el-input v-model="keyword" class="icon-search" size="small" clearable placeholder="搜索图标名，如 user" />
      <div class="icon-groups">
        <section v-for="group in filteredGroups" :key="group.label" class="icon-group">
          <h4>{{ group.label }}</h4>
          <div class="icon-grid">
            <button
              v-for="name in group.items"
              :key="name"
              type="button"
              class="icon-cell"
              :class="{ 'is-active': name === modelValue }"
              :title="name"
              @click="pick(name)"
            >
              <el-icon><component :is="iconOf(name)" /></el-icon>
              <span>{{ name }}</span>
            </button>
          </div>
        </section>
        <p v-if="!filteredGroups.length" class="icon-empty">没有匹配的图标</p>
      </div>
      <!-- 清空只清图标字段，不影响其它菜单属性 -->
      <div class="icon-actions">
        <el-button size="small" text @click="pick('')">清空图标</el-button>
        <el-button size="small" text @click="panelOpen = false">收起</el-button>
      </div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
/**
 * 菜单图标选择器。所属域：前端壳。
 * 交互：点输入框展开面板，按分组展示内置图标，支持按名称搜索，选中即回填并收起。
 * 约束：只产出图标名称字符串（与 sys_menu.icon 一致），不参与表单校验；空值表示不显示图标。
 */
import { computed, ref } from 'vue'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import { MENU_ICON_GROUPS, resolveMenuIcon } from './menuIcons'

const props = defineProps<{
  /** 当前图标名，空串表示未选 */
  modelValue: string
}>()

const emit = defineEmits<{
  /** 回填选中的图标名 */
  (e: 'update:modelValue', value: string): void
}>()

const panelOpen = ref(false)
const keyword = ref('')

/** 按关键词过滤分组；关键词命中分组内任一图标名即保留该组 */
const filteredGroups = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) {
    return MENU_ICON_GROUPS
  }
  return MENU_ICON_GROUPS.map((group) => ({
    label: group.label,
    items: group.items.filter((name) => name.toLowerCase().includes(key))
  })).filter((group) => group.items.length > 0)
})

/**
 * 取图标组件，未登记的存量名称回退 Setting。
 *
 * @param name 图标名
 */
function iconOf(name: string) {
  return resolveMenuIcon(name)
}

/**
 * 选中图标：回填后收起面板并清空搜索词。
 *
 * @param name 图标名，空串表示清空
 */
function pick(name: string) {
  emit('update:modelValue', name)
  panelOpen.value = false
  keyword.value = ''
}
</script>

<style scoped>
.icon-field {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 32px;
  padding: 0 10px;
  cursor: pointer;
  background: var(--ah-input-fill);
  border: 1px solid transparent;
  border-radius: var(--ah-radius-md);
}

.icon-field:hover {
  border-color: var(--el-border-color);
}

.icon-preview {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  flex: none;
  color: var(--ah-primary);
}

.icon-preview .is-placeholder {
  color: var(--ah-text-muted);
}

.icon-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.icon-field.is-empty .icon-name {
  color: var(--ah-text-muted);
}

.icon-caret {
  flex: none;
  color: var(--ah-text-muted);
}

/* 面板内部：搜索 + 分组网格，超长时内部滚动，不撑高弹窗 */
.icon-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.icon-search {
  flex: none;
}

.icon-groups {
  max-height: 300px;
  overflow-y: auto;
}

.icon-group h4 {
  margin: 8px 0 6px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.icon-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 4px;
}

.icon-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 2px;
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--ah-radius-md);
  color: var(--ah-text);
  font-size: 10px;
  line-height: 1.2;
}

.icon-cell:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.icon-cell.is-active {
  background: var(--el-color-primary-light-9);
  border-color: var(--ah-primary);
  color: var(--ah-primary);
}

.icon-cell span {
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.icon-empty {
  margin: 16px 0;
  color: var(--ah-text-muted);
  font-size: 12px;
  text-align: center;
}

.icon-actions {
  display: flex;
  justify-content: space-between;
  border-top: 1px solid var(--ah-border-soft);
  padding-top: 6px;
}
</style>
