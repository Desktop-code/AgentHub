<template>
  <aside class="sessions" :class="{ 'is-workbench': isWorkbench }">
    <div v-if="!isWorkbench" class="sessions-title">历史会话</div>
    <div class="session-ops" :class="{ 'is-workbench': isWorkbench }">
      <el-button
        v-if="canRun"
        :size="isWorkbench ? 'default' : 'small'"
        type="primary"
        class="session-create"
        :disabled="!canCreate || sending || resuming"
        @click="emit('create')"
      >
        {{ isWorkbench ? '+ 新建对话' : '+ 新会话' }}
      </el-button>
      <el-button
        v-if="!isWorkbench"
        size="small"
        :disabled="!session || isTemp(session) || sending || resuming"
        @click="emit('clear')"
      >
        清空当前
      </el-button>
      <!-- 工作台：清空收入次要操作，避免顶栏两按钮抢「新建」 -->
      <el-button
        v-else
        link
        type="info"
        class="session-clear-link"
        :disabled="!session || isTemp(session) || sending || resuming"
        @click="emit('clear')"
      >
        清空当前
      </el-button>
    </div>
    <!--
      工作台左栏工具行：模糊搜索 + 收起。
      搜索只作用于「已加载」的会话（本地过滤，不触发请求），收起由父级改宽度。
    -->
    <div v-if="isWorkbench" class="session-toolbar">
      <el-input
        v-model="keyword"
        class="session-search"
        size="small"
        clearable
        :prefix-icon="Search"
        placeholder="搜索历史会话"
        aria-label="搜索历史会话"
      />
      <button
        type="button"
        class="session-collapse"
        title="收起历史栏"
        aria-label="收起历史栏"
        @click="emit('toggleCollapse')"
      >
        <el-icon><Fold /></el-icon>
      </button>
    </div>
    <p v-if="!groups.length && !historyLoading" class="session-hint">
      {{ isWorkbench ? '发出第一条消息后会出现在这里' : '提问后才会保存到历史会话' }}
    </p>
    <!-- 搜索态下无命中：明确是「搜不到」而不是「还没有会话」 -->
    <p v-else-if="searching && !visibleGroups.length" class="session-hint">
      没有匹配的会话
    </p>
    <!-- 本地过滤的边界要说清：还有分页没取，用户可以点底部「加载更多」扩大范围 -->
    <p v-if="searching && hasMore" class="session-search-scope">仅搜索已加载的会话</p>
    <!--
      滚动容器：触底自动加载下一页（主交互）；
      底部同时保留「加载更多」按钮，兼顾键盘/触控板不便滚到底的情况。
    -->
    <div ref="listRef" v-loading="historyLoading" class="session-list" @scroll.passive="onScroll">
      <section v-for="group in visibleGroups" :key="group.label" class="session-group">
        <div class="group-label">{{ group.label }}</div>
        <div
          v-for="item in group.items"
          :key="item.id"
          class="session-item"
          :class="{ 'is-active': item.id === activeId, 'is-running': isRunning(item) }"
          :title="tooltipOf(item)"
          @click="emit('open', item.id)"
        >
          <div class="session-main">
            <span class="session-name">{{ titleOf(item) }}</span>
            <span class="session-time">{{ subtitleOf(item) }}</span>
          </div>
          <el-dropdown
            v-if="canRun && !isTemp(item)"
            trigger="click"
            @command="(cmd: string) => emit('command', cmd, item)"
          >
            <button type="button" class="session-more" aria-label="会话操作" @click.stop>
              <el-icon><MoreFilled /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="remove" divided class="session-remove-item">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <!-- 本轮还在执行：贴在行最右侧，结束后自动消失 -->
          <span v-if="isRunning(item)" class="session-running" title="本轮执行中">
            <el-icon class="is-loading"><Loading /></el-icon>
          </span>
        </div>
      </section>

      <!-- 分页尾部：搜索态仍保留「加载更多」（把更早的分页取进来继续搜）；
           只有「没有更多了」在搜索态收起，避免与「没有匹配」并列造成误读 -->
      <div v-if="hasMore" class="session-more-row">
        <el-button link type="primary" :loading="loadingMore" @click="emit('loadMore')">
          {{ loadingMore ? '加载中…' : searching ? '加载更多会话继续搜索' : '加载更多' }}
        </el-button>
      </div>
      <p v-else-if="visibleGroups.length && !searching" class="session-end">没有更多了</p>
    </div>
  </aside>
</template>

<script setup lang="ts">
/**
 * 试跑左栏「历史会话」纯展示组件。所属域：runtime（前端）。
 *
 * 职责：按「今日 / 7天内 / 30天内 / 更早」分组渲染会话行，提供新会话、清空当前、行内重命名/删除入口；
 * 以及「本轮执行中」的转圈状态、历史会话的触底分页（发 loadMore 事件）；
 * 工作台形态额外提供本地模糊搜索与「收起历史栏」转发（宽度仍归父级）。
 *
 * 约束（结构边界）：
 * - **不持有业务状态、不发请求**：分组、标题文案、执行中判定、页码与 total 全部由父组件（TrialPanel）
 *   算好传入，本组件只负责渲染与转发点击 / 触底，保证拆分前后行为完全等价；
 * - 结果 / 失败的提示由父组件统一处理，这里不弹 toast；
 * - `.sessions` 的宽度与右边框由父级 scoped 样式控制（父级样式可作用于子组件根元素），
 *   本文件只负责内部元素样式。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { MoreFilled, Loading, Search, Fold } from '@element-plus/icons-vue'
import { filterSessionGroups } from './sessionSearch'
import type { SessionGroup } from './sessionGroup'
import type { TrialSession } from '@/types/runtime'

const props = withDefaults(
  defineProps<{
    /** 已分好组、排好序的会话列表；空数组显示提示文案 */
    groups: SessionGroup[]
    /** 当前选中的会话 id；与行 id 相等时展示选中态 */
    activeId?: number
    /** 会话归属（Agent id 或方案 id）；为空时「新会话」不可用 */
    ownerId?: number
    /**
     * 是否允许新建会话。缺省按 ownerId 判断（调试入口的旧行为）。
     * 工作台没有归属 id，改用「默认接待是否就绪」控制同一个按钮。
     */
    canCreate?: boolean
    /** 是否有试跑权限：控制「新会话」按钮与行内操作菜单 */
    canRun: boolean
    /** 当前选中的会话；为空时「清空当前」不可用 */
    session?: TrialSession
    /** 本轮是否在发送中 */
    sending: boolean
    /** 当前会话是否在续订中 */
    resuming: boolean
    /** 历史列表加载中（第 1 页 / 重置） */
    historyLoading: boolean
    /** 是否还有下一页（服务端 total > 已加载条数） */
    hasMore?: boolean
    /** 正在加载下一页（只驱动底部按钮的 loading，不盖整栏） */
    loadingMore?: boolean
    /**
     * 展示形态：trial=调试左栏；workbench=工作台中栏（新建文案 / 选中色 / 清空弱化）。
     * 不改触底分页与数据源协议。
     */
    variant?: 'trial' | 'workbench'
    /** 是否前端临时草稿会话（未落库）：临时会话不可清空/重命名/删除 */
    isTemp: (item: TrialSession) => boolean
    /** 该行是否正在执行（决定转圈与「不显示更多菜单」） */
    isRunning: (item: TrialSession) => boolean
    /** 行主标题 */
    titleOf: (item: TrialSession) => string
    /** 行副标题（预览 + 时间） */
    subtitleOf: (item: TrialSession) => string
    /** 行 hover 提示 */
    tooltipOf: (item: TrialSession) => string
  }>(),
  {
    variant: 'trial'
  }
)

/** 工作台中栏：文案与选中色走沉浸样式，调试形态保持原样 */
const isWorkbench = computed(() => props.variant === 'workbench')

const emit = defineEmits<{
  /** 点「+ 新会话」 */
  (e: 'create'): void
  /** 点「清空当前」 */
  (e: 'clear'): void
  /** 点某行：切到该会话 */
  (e: 'open', id: number): void
  /** 行内下拉：rename / remove */
  (e: 'command', command: string, item: TrialSession): void
  /** 触底或点「加载更多」：请求下一页 */
  (e: 'loadMore'): void
  /** 点「收起历史栏」：宽度由父级控制，本组件只转发 */
  (e: 'toggleCollapse'): void
}>()

/**
 * 搜索关键字（仅工作台可见）。本地过滤已加载会话：
 * 不触发请求、不改分页，父级无需感知。
 */
const keyword = ref('')
/** 是否处于搜索态：决定空文案与分页尾部是否让位 */
const searching = computed(() => keyword.value.trim().length > 0)

/**
 * 参与检索的展示文案：标题 + 副标题 + hover 提示。
 * 副标题含预览与时间，用户常按内容关键词回忆会话，因此一并纳入匹配。
 */
function searchTextOf(item: TrialSession): string {
  return [props.titleOf(item), props.subtitleOf(item), props.tooltipOf(item)].join(' ')
}

/** 过滤后的分组；关键字为空时原样返回父级分组 */
const visibleGroups = computed(() => filterSessionGroups(props.groups, searchTextOf, keyword.value))

/** 滚动容器；触底判定与「内容不足一屏时自动补一页」都要读它 */
const listRef = ref<HTMLElement>()
/** 距底部多少像素内触发加载：留一点余量，让下一页在用户滚到底前就开始取 */
const NEAR_BOTTOM_PX = 96

/**
 * 滚动触底：接近底部且还有下一页时发一次 loadMore。
 * 并发保护在父组件（loadingMore / 无下一页直接 return），这里不重复建状态。
 *
 * @param evt 滚动事件（监听在自身容器上）
 */
function onScroll(evt: Event) {
  const el = evt.target as HTMLElement | null
  if (!el || !props.hasMore || props.loadingMore) {
    return
  }
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - NEAR_BOTTOM_PX) {
    emit('loadMore')
  }
}

/**
 * 内容没占满一屏时，用户根本滚不动，触底事件不会发生。
 * 这里在分组 / 是否有下一页变化后补一次判定：仍然没滚动条就主动取下一页，
 * 直到填满或没有下一页。父组件每页都会推进「已加载条数」，因此不会死循环。
 */
watch(
  () => [props.groups, props.hasMore, props.loadingMore, props.historyLoading] as const,
  async () => {
    await nextTick()
    const el = listRef.value
    // 首屏 / 重置还在加载时不要抢占：等它落地后本 watch 会因 hasMore / groups 变化再判一次
    if (!el || !props.hasMore || props.loadingMore || props.historyLoading) {
      return
    }
    if (el.scrollHeight <= el.clientHeight + 4) {
      emit('loadMore')
    }
  }
)
</script>

<style scoped>
.sessions-title {
  margin-bottom: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ah-border);
  font-size: 14px;
  font-weight: 650;
}

.session-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

/* 工作台：新建对话顶栏固定、占满宽；清空弱化为 link */
.session-ops.is-workbench {
  flex-wrap: nowrap;
  align-items: center;
  margin-bottom: 12px;
}

/* 搜索 + 收起同一行：输入框占满，收起按钮固定在最右 */
.session-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}

.session-search {
  flex: 1;
  min-width: 0;
}

/* 收起按钮：与输入框同高的小方钮，只放图标，避免和「新建」抢视觉 */
.session-collapse {
  display: grid;
  flex: none;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--ah-border-soft, #e8eaef);
  border-radius: 8px;
  background: #fff;
  color: var(--ah-text-secondary);
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.session-collapse:hover {
  background: #f5f3ff;
  color: var(--ah-primary, #635bff);
}

.session-ops.is-workbench .session-create {
  flex: 1;
  min-width: 0;
}

.session-clear-link {
  flex: none;
  padding: 0 4px;
  font-size: 12px;
}

/* 工作台选中：浅紫底 #eef2ff + 主题色字（比调试略淡一档描边） */
.sessions.is-workbench .session-item.is-active {
  background: #eef2ff;
  border-color: transparent;
  box-shadow: none;
}

.sessions.is-workbench .session-item.is-active .session-name {
  color: var(--ah-primary, #635bff);
}

.session-hint {
  margin: 0 0 8px;
  color: var(--ah-text-muted);
  font-size: 12px;
  line-height: 1.4;
}

/* 搜索范围说明：比正文更弱一档，避免被误读成结果数量 */
.session-search-scope {
  margin: -4px 0 8px;
  color: var(--ah-text-muted);
  font-size: 11px;
  line-height: 1.3;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

/* 分页尾部：留出点击区，避免贴边误触最后一行 */
.session-more-row {
  display: flex;
  justify-content: center;
  padding: 6px 0 10px;
}

.session-end {
  margin: 4px 0 10px;
  color: var(--ah-text-muted);
  font-size: 12px;
  text-align: center;
}

.session-group + .session-group {
  margin-top: 12px;
}

.group-label {
  margin: 0 0 4px 8px;
  color: var(--ah-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
}

/* 悬停比选中浅一档，避免「鼠标扫过」和「已选中」长得一样 */
.session-item:hover {
  background: #f7f6ff;
}

/* 选中态：比悬停明显加深一档，仍克制（浅紫底 + 主色描边 + 左侧色条） */
.session-item.is-active {
  background: #e8e4ff;
  border-color: #cdc6ff;
  box-shadow: inset 3px 0 0 var(--ah-primary, #635bff);
}

.session-item.is-active .session-name {
  color: #3d34c9;
  font-weight: 650;
}

.session-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.session-name {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 副标题：预览 + 时间。预览可能很长，同样单行省略，不能把行高撑开 */
.session-time {
  overflow: hidden;
  color: var(--ah-text-muted);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 本轮执行中：贴在该行最右侧，小号转圈；行内操作按钮的显隐优先级更低 */
.session-running {
  display: inline-flex;
  flex: none;
  align-items: center;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

.session-more {
  display: none;
  flex: none;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary);
  cursor: pointer;
}

.session-item:hover .session-more,
.session-item.is-active .session-more {
  display: grid;
}

/* 执行中优先显示转圈，不显示三点菜单，避免右侧两个图标挤在一起 */
.session-item.is-running .session-more,
.session-item.is-running:hover .session-more,
.session-item.is-running.is-active .session-more {
  display: none;
}

.session-more:hover {
  background: #ebe9ff;
  color: var(--ah-text);
}
</style>

<style>
/* 下拉挂到 body，需非 scoped 才能把删除项染红 */
.session-remove-item {
  color: var(--el-color-danger) !important;
}

.session-remove-item:hover,
.session-remove-item:focus {
  color: var(--el-color-danger) !important;
  background-color: var(--el-color-danger-light-9) !important;
}
</style>
