<template>
  <ResourceSelector
    :visible="modelValue"
    :model-value="pickedIds"
    :data-source="groups"
    :title="`添加工具 · ${boundIds.length ? `已绑定 ${boundIds.length} 个` : '从工具目录选择'}`"
    search-placeholder="搜索工具中文名或英文代码"
    :loading="loading"
    :confirming="saving"
    empty-text="工具目录里还没有可绑定的工具"
    @update:model-value="pickedIds = $event"
    @update:visible="emit('update:modelValue', $event)"
    @confirm="submit"
  >
    <!-- 左侧选项：中文名加粗 + 英文代码灰色小字；MCP 工具补一个来源标签 -->
    <template #item="{ item }">
      <div class="tool-name" :title="item.name">{{ item.name }}</div>
      <div class="tool-code" :title="item.code">{{ item.code }}</div>
      <div class="tool-meta">
        <el-tag v-if="item.extra" size="small" effect="plain" class="tool-source">{{ item.extra }}</el-tag>
        <span v-if="item.description" class="tool-desc" :title="item.description">{{ item.description }}</span>
      </div>
    </template>

    <!-- 右侧已选：与左栏保持同一信息口径，只去掉描述，节省纵向空间 -->
    <template #selected-item="{ item }">
      <span class="tool-chip-name" :title="item.name">{{ item.name }}</span>
      <span class="tool-chip-code" :title="item.code">{{ item.code }}</span>
    </template>
  </ResourceSelector>
</template>

<script setup lang="ts">
/**
 * 工具绑定选择器（双栏穿梭）。所属域：agent。
 *
 * 职责边界：本组件的弹窗壳已换成通用的 {@link ResourceSelector}，
 * 这里只做「拉工具目录 → 映射成分组 → 提交绑定」三件事，不再自己画下拉框。
 *
 * 数据口径：
 * - 候选来自 `/api/tool/items` 全量（含停用项），按来源类型分为「内置工具」「MCP 工具源」两组；
 * - 停用工具置 `disabled`，在选择器里可见但不可新勾选——用户能看见「有这个东西但用不了」，
 *   比直接隐藏更容易排查「为什么列表里找不到」；
 * - 已绑定的工具不进候选，避免重复绑定撞后端唯一约束。
 */
import { ref, watch } from 'vue'
import { listAllItems } from '@/api/tool'
import ResourceSelector from '@/components/common/ResourceSelector.vue'
import type { ResourceGroup } from '@/types/resource'
import { buildToolGroups } from '@/utils/bindResource'

const props = defineProps<{
  /** 弹窗开关 */
  modelValue: boolean
  /** 当前 Agent 已绑定的工具项 id，用于剔除候选 */
  boundIds: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** 确认后交给详情页批量创建绑定；done 用于收起确认按钮 loading */
  submit: [toolItemIds: number[], done: () => void]
}>()

const loading = ref(false)
const saving = ref(false)
/** 选择器回写的已选 id；只在确认时才会真正提交绑定 */
const pickedIds = ref<(number | string)[]>([])
/** 已映射好的分组数据源，打开弹窗时才拉取 */
const groups = ref<ResourceGroup[]>([])

/**
 * 打开时重置临时态并拉最新目录。
 * 每次打开都重拉：工具可能在别处被停用或删除，缓存会让用户选到已经失效的工具。
 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    pickedIds.value = []
    saving.value = false
    groups.value = []
    void load()
  }
)

/**
 * 拉全量工具项并按来源类型分组。
 * <p>
 * 分组与置灰规则在 {@link buildToolGroups} 里（可单测），这里只管取数与错误兜底。
 */
async function load(): Promise<void> {
  loading.value = true
  try {
    const rows = await listAllItems()
    groups.value = buildToolGroups(rows, props.boundIds)
  } catch {
    // 请求失败由 request 拦截器统一提示；这里保持空列表，选择器会显示空态
    groups.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 确认添加：把选中 id 交给详情页逐条创建绑定。
 * 选择器已禁用在无选中时的确认按钮，这里只兜底防并发。
 */
function submit(ids: (number | string)[]): void {
  if (!ids.length || saving.value) {
    return
  }
  saving.value = true
  emit(
    'submit',
    ids.map((id) => Number(id)),
    () => {
      saving.value = false
    }
  )
}
</script>

<style scoped>
/* 左栏选项：中文名 / 英文码 / 来源与说明三行，行高压紧保证一屏能多列几项 */
.tool-name {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
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

.tool-source {
  flex: none;
  --el-tag-font-size: 11px;
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

/* 右侧已选：名称与代码同一行，紧凑但可辨认 */
.tool-chip-name {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-chip-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
