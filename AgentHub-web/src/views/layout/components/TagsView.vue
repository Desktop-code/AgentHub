<template>
  <!-- 多页签条：紧贴右侧主区顶；40px 定高（≤45px），浅灰底，不参与主区滚动 -->
  <div class="tags-view">
    <!-- 横向滚动：标签多时只在这里滚，不影响主区；原生隐藏滚动条，靠滚轮/触摸拖动 -->
    <el-scrollbar ref="scrollRef" class="tags-scroll" :always="false">
      <div class="tags-track">
        <div
          v-for="tag in visitedViews"
          :key="tag.key"
          class="tag-item"
          :class="{ 'is-active': isActive(tag) }"
          :title="tag.title"
          @click="onClickTag(tag)"
        >
          <span class="tag-text">{{ tag.title }}</span>
          <!-- 固定页签（工作台）不渲染关闭按钮，避免「关闭所有」后主区无落点 -->
          <el-icon v-if="!tag.affix" class="tag-close" @click.stop="onCloseTag(tag)">
            <Close />
          </el-icon>
        </div>
      </div>
    </el-scrollbar>

    <!-- 右侧批量操作：关闭其他 / 关闭所有 -->
    <div class="tags-actions">
      <el-dropdown trigger="click" placement="bottom-end" @command="onCommand">
        <button type="button" class="tags-more" title="标签操作" aria-label="标签操作">
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="others">关闭其他</el-dropdown-item>
            <el-dropdown-item command="all">关闭所有</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 多页签导航条。所属域：前端布局（layout）。
 *
 * 职责：把访问过的路由渲染成横向标签，支持点击切换、单个关闭、关闭其他、关闭所有。
 * 状态全部落在 stores/tagsView.ts，本组件只负责渲染与路由跳转，不自行维护标签数组。
 * 视觉：紧贴右侧主区顶，定高 40px（≤45px），与内容区同色浅灰；不改缓存协议。
 *
 * 关键约束：
 * 1. 标签唯一键是 path：同一页面换 query 只更新标签，不新增标签。
 * 2. 关闭激活页后要立刻跳走，否则 KeepAlive 已移出缓存、主区会停在空白页。
 * 3. 本组件不感知 KeepAlive，缓存名单由 store 的 cachedViews 派生。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Close } from '@element-plus/icons-vue'
import { useTagsViewStore, type TagView } from '@/stores/tagsView'

const route = useRoute()
const router = useRouter()
const tags = useTagsViewStore()

/** el-scrollbar 实例，用于把激活标签滚进可视区 */
const scrollRef = ref<{ wrapRef?: HTMLElement } | null>(null)

const visitedViews = computed(() => tags.visitedViews)

/**
 * 当前页签的 key：与 store 的归并规则保持一致（cacheName 优先）。
 * 用于判断点击/关闭的对象是不是当前页。
 */
const activeKey = computed(() => {
  const name = route.meta?.cacheName
  if (typeof name === 'string' && name.trim()) {
    return name.trim()
  }
  return `path:${route.path}`
})

/**
 * 是否为当前激活标签。
 * 按 key 比较而非 fullPath：试跑参数变化时仍应高亮同一个标签；
 * Agent 列表与详情共用 key，翻卡换 URL 后标签不应跳成两个。
 *
 * @param tag 待判断的标签
 * @return true 表示当前激活
 */
function isActive(tag: TagView) {
  return tag.key === activeKey.value
}

/**
 * 把激活标签滚动到可视范围内。
 * 直接操作 scrollbar 的 wrap 元素，避免 scrollIntoView 连带滚动外层主区。
 */
async function scrollActiveIntoView() {
  await nextTick()
  const wrap = scrollRef.value?.wrapRef
  if (!wrap) {
    return
  }
  const active = wrap.querySelector('.tag-item.is-active') as HTMLElement | null
  if (!active) {
    return
  }
  const left = active.offsetLeft
  const right = left + active.offsetWidth
  if (left < wrap.scrollLeft) {
    wrap.scrollLeft = left - 8
  } else if (right > wrap.scrollLeft + wrap.clientWidth) {
    wrap.scrollLeft = right - wrap.clientWidth + 8
  }
}

/**
 * 点击标签切换页面。
 * 已在该页时不重复 push，避免 vue-router 抛重复导航告警。
 *
 * @param tag 目标标签
 */
function onClickTag(tag: TagView) {
  if (tag.fullPath === route.fullPath) {
    return
  }
  router.push(tag.fullPath)
}

/**
 * 跳转到指定地址；与当前地址相同时不重复导航。
 * 「关闭所有」在已处于工作台时会给出相同目标，直接 push 会触发 vue-router 重复导航告警。
 *
 * @param path 目标完整路径
 */
function navigateTo(path: string) {
  if (!path || path === route.fullPath) {
    return
  }
  router.push(path)
}

/**
 * 关闭单个标签；关的是当前页时跳到相邻标签。
 *
 * @param tag 要关闭的标签
 */
function onCloseTag(tag: TagView) {
  const result = tags.delView(tag, activeKey.value)
  if (result.closedActive && result.nextPath) {
    navigateTo(result.nextPath)
    return
  }
  // 关的是非激活页：只重排标签条，顺带保证激活标签仍在可视区
  void scrollActiveIntoView()
}

/**
 * 下拉菜单命令分发。
 *
 * @param command others = 关闭其他；all = 关闭所有
 */
function onCommand(command: string | number | object) {
  if (command === 'others') {
    const current = visitedViews.value.find((item) => item.key === activeKey.value)
    // 当前页签尚未登记（如首帧竞态）时，用它自身的路由信息临时构造保留目标
    const keep: TagView =
      current ?? {
        key: activeKey.value,
        path: route.path,
        fullPath: route.fullPath,
        title: '',
        query: { ...route.query }
      }
    const result = tags.delOthersViews(keep, activeKey.value)
    if (result.closedActive && result.nextPath) {
      navigateTo(result.nextPath)
    }
    return
  }
  if (command === 'all') {
    const result = tags.delAllViews()
    if (result.closedActive && result.nextPath) {
      navigateTo(result.nextPath)
    }
  }
}

// 路由变化即登记页签；immediate 保证首屏也有当前页标签
watch(
  () => route.path,
  () => {
    tags.addView(route)
    void scrollActiveIntoView()
  },
  { immediate: true }
)

// query 变化（如 ?agentId= / ?trial=1）只刷新标签上记录的地址，不新增标签
watch(
  () => route.query,
  () => tags.addView(route)
)

onMounted(() => {
  void scrollActiveIntoView()
})
</script>

<style scoped>
/*
 * 轻量页签条：与主区同色浅灰，仅底部分割线；定高 40px（验收上限 45px）。
 * 不改 visitedViews / KeepAlive 协议，只动视觉。
 */
.tags-view {
  display: flex;
  align-items: center;
  flex: none;
  height: 40px;
  max-height: 45px;
  padding: 0 8px 0 12px;
  background: #f5f7fa;
  border-bottom: 1px solid var(--ah-border-soft, #f2f6fc);
  box-sizing: border-box;
}

.tags-scroll {
  flex: 1;
  min-width: 0;
}

.tags-track {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 40px;
  white-space: nowrap;
}

/* 普通标签：更小更紧凑，透明底 */
.tag-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  height: 24px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: var(--ah-radius-md);
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  user-select: none;
}

.tag-item:hover {
  background: rgba(255, 255, 255, 0.72);
  color: var(--ah-text);
}

/* 当前页：品牌色浅底 + 品牌色字，保留关闭按钮 */
.tag-item.is-active {
  background: var(--el-color-primary-light-9, #f4f3ff);
  border-color: transparent;
  color: var(--ah-primary);
  font-weight: 600;
}

.tag-text {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-close {
  font-size: 11px;
  border-radius: 50%;
  color: inherit;
  opacity: 0.55;
  transition: opacity 0.15s ease, background 0.15s ease;
}

.tag-close:hover {
  background: rgba(0, 0, 0, 0.08);
  opacity: 1;
}

.tags-actions {
  display: flex;
  align-items: center;
  flex: none;
  padding-left: 4px;
}

.tags-more {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--ah-radius-md);
  background: transparent;
  color: var(--ah-text-secondary);
  cursor: pointer;
}

.tags-more:hover {
  background: rgba(255, 255, 255, 0.72);
  color: var(--ah-text);
}

/* 隐藏横向滚动条：标签多时靠滚轮 / 触控板横向滑动 */
.tags-scroll :deep(.el-scrollbar__bar.is-horizontal) {
  display: none;
}
</style>
