<template>
  <ResourceSelector
    :visible="modelValue"
    :model-value="pickedIds"
    :data-source="groups"
    :title="`添加技能 · ${boundIds.length ? `已绑定 ${boundIds.length} 个` : '从技能库选择'}`"
    search-placeholder="搜索技能标题、编码或触发关键词"
    :loading="loading"
    :confirming="saving"
    empty-text="技能库里还没有可绑定的技能"
    @update:model-value="pickedIds = $event"
    @update:visible="emit('update:modelValue', $event)"
    @confirm="submit"
  >
    <!-- 左侧选项：标题（加粗）+ 编码（灰小字）+ 简介（两行截断）+ 触发关键词 Tag；右上角标出生效版本 -->
    <template #item="{ item }">
      <div class="skill-option">
        <span class="skill-version" :class="{ 'is-unpublished': !item.extra }">{{ item.extra || '未发布' }}</span>
        <div class="skill-title" :title="item.name">{{ item.name }}</div>
        <div class="skill-code" :title="item.code">{{ item.code }}</div>
        <div v-if="item.description" class="skill-desc" :title="item.description">{{ item.description }}</div>
        <!-- 关键词只显示前 3 个：再多会把选项撑高，搜索框仍可按全部关键词命中 -->
        <div v-if="item.tags?.length" class="skill-tags">
          <el-tag
            v-for="word in item.tags.slice(0, 3)"
            :key="word"
            size="small"
            effect="plain"
            type="info"
            class="skill-tag"
          >
            {{ word }}
          </el-tag>
          <span v-if="item.tags.length > 3" class="skill-tag-more">+{{ item.tags.length - 3 }}</span>
        </div>
      </div>
    </template>

    <!-- 右侧已选：标题 + 编码即可，简介与关键词在左栏已看过 -->
    <template #selected-item="{ item }">
      <span class="skill-chip-title" :title="item.name">{{ item.name }}</span>
      <span class="skill-chip-code" :title="item.code">{{ item.code }}</span>
    </template>
  </ResourceSelector>
</template>

<script setup lang="ts">
/**
 * 技能绑定选择器（双栏穿梭）。所属域：agent。
 *
 * 职责边界：弹窗壳复用通用 {@link ResourceSelector}，这里只负责
 * 「拉技能卡片 → 映射成单个「技能库」分组 → 提交绑定」。
 *
 * 数据口径：
 * - 卡片接口（不含正文）足够展示标题 / 简介 / 触发关键词 / 生效版本；
 * - 全部技能归入一组：当前技能没有二级分类，硬分组只会制造空标题；
 * - 停用技能置 `disabled`，可见但不可新勾选；已绑定的不进候选。
 */
import { ref, watch } from 'vue'
import { listAllSkills } from '@/api/skill'
import ResourceSelector from '@/components/common/ResourceSelector.vue'
import type { ResourceGroup } from '@/types/resource'
import { buildSkillGroups } from '@/utils/bindResource'

const props = defineProps<{
  /** 弹窗开关 */
  modelValue: boolean
  /** 当前 Agent 已绑定的 Skill id，用于剔除候选 */
  boundIds: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  /** 确认后交给详情页批量创建绑定；done 用于收起确认按钮 loading */
  submit: [skillIds: number[], done: () => void]
}>()

const loading = ref(false)
const saving = ref(false)
/** 选择器回写的已选 id；确认时才提交绑定 */
const pickedIds = ref<(number | string)[]>([])
/** 分组数据源 */
const groups = ref<ResourceGroup[]>([])

/**
 * 打开时重置并拉最新 Skill 列表。
 * 每次重拉而不是复用缓存：Skill 可能刚被停用，选到失效项会在提交时被后端拒绝。
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
 * 拉全量 Skill 卡片并映射成单个「Skill 库」分组。
 * 映射与剔除规则在 {@link buildSkillGroups} 里（可单测）。
 */
async function load(): Promise<void> {
  loading.value = true
  try {
    const rows = await listAllSkills()
    groups.value = buildSkillGroups(rows, props.boundIds)
  } catch {
    // 统一由 request 拦截器提示错误；这里留空列表让选择器显示空态
    groups.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 确认添加：id 交给详情页逐条创建绑定。
 *
 * @param ids 选中的 Skill id
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
/* 左栏选项外层：作为版本角标的定位参照 */
.skill-option {
  position: relative;
  padding-right: 52px;
}

/* 生效版本角标：卡片右上角，未发布用灰底 */
.skill-version {
  position: absolute;
  top: 0;
  right: 0;
  padding: 0 6px;
  border: 1px solid #d1fae5;
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
}

.skill-version.is-unpublished {
  border-color: #e5e7eb;
  background: #f9fafb;
  color: #9ca3af;
}

/* 左栏选项：标题 / 编码 / 简介 / 关键词，信息比工具多一行 */
.skill-title {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-desc {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 3px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
}

/* 关键词 Tag：浅灰底，信息层级低于标题与简介 */
.skill-tag {
  --el-tag-font-size: 11px;
  --el-tag-padding: 0 5px;
  --el-tag-height: 18px;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f9fafb;
  --el-tag-text-color: #6b7280;
}

.skill-tag-more {
  color: var(--ah-text-muted);
  font-size: 11px;
}

/* 右侧已选：标题与编码同列排布 */
.skill-chip-title {
  overflow: hidden;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-chip-code {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
