<template>
  <!--
    执行链路主视图。所属域：obs。
    顶部拓扑仅在放大（showTopology）时出现；下方列表区为「并行容器 + 可折叠节点卡」。
    分组规则与拓扑同源，禁止另算一套。
  -->
  <div ref="rootRef" class="trace-flow">
    <!-- 顶图与瀑布同用「泳道可见 spans」做并组；包络只服务甘特条 -->
    <TraceTopology
      v-if="showTopology"
      :spans="displaySpans"
      :active-id="activeSpanId"
      @select="onTopologySelect"
    />

    <div class="trace-flow__list">
      <TraceListView
        :blocks="rootBlocks"
        :index="tree"
        :is-wide-mode="isWideMode"
        :gantt-of="ganttOf"
        :active-span-id="activeSpanId"
        :expand-ids="expandIds"
        :retrieve-of="retrieveOf"
        @open-retrieve="onOpenRetrieve"
      />
    </div>

    <!-- 回到顶端：sticky 贴在滚动容器右下角，滚动超过一屏才出现，不占列表布局 -->
    <div class="trace-flow__totop" :class="{ 'is-visible': showToTop }">
      <el-tooltip content="回到顶端" placement="left" :show-after="200">
        <el-button circle class="trace-flow__totop-btn" aria-label="回到顶端" @click="scrollToTop">
          <el-icon :size="16"><ArrowUp /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <!-- 检索明细：整条泳道只有一个抽屉实例，由 TOOL 卡入口事件驱动 -->
    <TraceRetrieveDrawer
      v-model="retrieveDrawerOpen"
      :tool-name="retrieveToolName"
      :steps="retrieveSteps"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowUp } from '@element-plus/icons-vue'
import type { ObsSpan } from '@/types/obs'
import { envelopSpans, ganttAxis, spanBar } from '@/utils/traceParallel'
import { ancestorIdsOf, buildTraceTree, treeBlocksOf } from '@/utils/traceGraphLayout'
import TraceTopology from '@/components/obs/TraceTopology.vue'
import TraceListView from '@/components/obs/TraceListView.vue'
import TraceRetrieveDrawer from '@/components/obs/TraceRetrieveDrawer.vue'

const props = withDefaults(
  defineProps<{
    spans: ObsSpan[]
    /** 放大抽屉宽屏模式，下传给卡片 */
    isWideMode?: boolean
    /** 是否显示顶部拓扑；三栏窄栏为 false，仅放大抽屉为 true */
    showTopology?: boolean
  }>(),
  { isWideMode: false, showTopology: false }
)

/**
 * 挂在 TOOL 下的检索子步：按父 id 归组。
 * <p>这些子步只进「检索明细」抽屉，不进主泳道与拓扑——主泳道保持工具一层。
 */
const retrieveByTool = computed(() => {
  const typeById = new Map(props.spans.map((span) => [span.id, span.spanType]))
  const grouped = new Map<number, ObsSpan[]>()
  for (const span of props.spans) {
    if (span.spanType !== 'RETRIEVE' || span.parentId == null) {
      continue
    }
    // 只认父节点确实是 TOOL 的子步：别处若出现 RETRIEVE，仍按普通步骤展示
    if (typeById.get(span.parentId) !== 'TOOL') {
      continue
    }
    const list = grouped.get(span.parentId) ?? []
    list.push(span)
    grouped.set(span.parentId, list)
  }
  return grouped
})

/** 被抽屉接管的子步 id：用于从泳道 / 拓扑里剔除 */
const hiddenRetrieveIds = computed(() => {
  const ids = new Set<number>()
  for (const list of retrieveByTool.value.values()) {
    for (const span of list) {
      ids.add(span.id)
    }
  }
  return ids
})

/** 泳道 / 拓扑实际渲染的步骤：排除检索中间态，保证工具卡只有一层 */
const displaySpans = computed(() =>
  props.spans.filter((span) => !hiddenRetrieveIds.value.has(span.id))
)

/** 甘特用包络后的 latency；并组用泳道可见 spans，避免父包络把先后步时间窗撑到相交 */
const envelope = computed(() => envelopSpans(displaySpans.value))
const axis = computed(() => ganttAxis(envelope.value.spans))

/** 树索引：父→子 + 根列表 + 模板上下文，一次算好供递归列表复用 */
const tree = computed(() => buildTraceTree(displaySpans.value))

/**
 * 取某个 TOOL 下的检索子步（顺序不限，抽屉内再按执行序分段）。
 *
 * @param span 当前步骤
 * @return 检索子步；非 TOOL / 无子步时为空数组
 */
function retrieveOf(span: ObsSpan): ObsSpan[] {
  if (span.spanType !== 'TOOL') {
    return []
  }
  return retrieveByTool.value.get(span.id) ?? []
}

/** 检索明细抽屉开关 */
const retrieveDrawerOpen = ref(false)
/** 抽屉标题里的工具名 */
const retrieveToolName = ref('')
/** 抽屉展示的子步快照：开抽屉时定格，避免中途重渲染抖动 */
const retrieveSteps = ref<ObsSpan[]>([])

/**
 * 打开检索明细：只有真拿到子步才开抽屉，避免空抽屉。
 *
 * @param span 被点的 TOOL 步骤
 */
function onOpenRetrieve(span: ObsSpan) {
  const steps = retrieveOf(span)
  if (!steps.length) {
    return
  }
  retrieveToolName.value = span.toolName || span.name || ''
  retrieveSteps.value = steps
  retrieveDrawerOpen.value = true
}

/** 顶层块：ROUTE 在并行块前、汇总在块后由 groupSiblingSpans 保证 */
const rootBlocks = computed(() => treeBlocksOf(null, tree.value))

/** 上方拓扑最近点中的节点 id；null 表示未点过 */
const activeSpanId = ref<number | null>(null)

/**
 * 被点节点的祖先 id 集合：列表里这些卡必须自动展开，
 * 否则目标卡还藏在折叠的 AGENT Body 里，滚动过去只会看到一片折叠卡。
 */
const expandIds = computed(() =>
  activeSpanId.value == null
    ? new Set<number>()
    : ancestorIdsOf(activeSpanId.value, tree.value)
)

/**
 * 拓扑点选：只记 id，真正的展开/滚动/高亮由对应卡片自己处理。
 *
 * @param spanId 被点的步骤 id
 */
function onTopologySelect(spanId: number) {
  // 重复点同一个节点：先清空再回填，否则卡片 watch 拿到的值没变化，第二次就滚不动了
  if (activeSpanId.value === spanId) {
    activeSpanId.value = null
    void nextTick(() => {
      activeSpanId.value = spanId
    })
    return
  }
  activeSpanId.value = spanId
}

// 换 trace 时清空选中，避免新链路里同 id 的节点被误高亮；检索抽屉也一并收起
watch(
  () => props.spans,
  () => {
    activeSpanId.value = null
    retrieveDrawerOpen.value = false
    retrieveSteps.value = []
    retrieveToolName.value = ''
  }
)

/**
 * 单步的甘特条：用包络后的 latency 对齐统一时间轴。
 *
 * @param span 原始步骤
 * @return 甘特条；无时间字段时返回 null
 */
function ganttOf(span: ObsSpan) {
  const enriched = envelope.value.byId.get(span.id) ?? span
  return axis.value ? spanBar(enriched, axis.value) : null
}

/** 实际滚动容器：三栏是 .col-body--scroll，放大抽屉是 .el-drawer__body，靠运行时向上找 */
const scrollHost = ref<HTMLElement | null>(null)
/** 滚过一屏才显示「回到顶端」，避免列表顶部也挂个按钮碍眼 */
const showToTop = ref(false)
/** 出现阈值：滚过约 1.5 屏，短链路不弹按钮 */
const TO_TOP_THRESHOLD_PX = 600

/**
 * 向上找第一个真正纵向滚动的祖先。
 * <p>本组件在两处复用（三栏窄栏 / 放大抽屉），写死某个 class 会在另一处失效；
 * 这里按计算样式判断，谁在滚就绑谁。首轮优先「当前确实能滚」的那个，
 * 其次退回第一个 overflow:auto 的祖先——内容还在加载时高度可能暂时不够，
 * 等卡片展开/数据到位后高度会变，靠 ResizeObserver 再校正。
 */
function findScrollHost(el: HTMLElement | null): HTMLElement | null {
  let fallback: HTMLElement | null = null
  let node = el?.parentElement ?? null
  while (node) {
    const style = window.getComputedStyle(node)
    if (/(auto|scroll|overlay)/.test(style.overflowY)) {
      if (node.scrollHeight > node.clientHeight + 4) {
        return node
      }
      fallback ??= node
    }
    node = node.parentElement
  }
  return fallback
}

const rootRef = ref<HTMLElement | null>(null)
/** 内容尺寸监听：卡片展开/收起导致高度变化时重新判定滚动容器（延迟挂载场景兜底） */
let resizeObserver: ResizeObserver | null = null

/** 滚动回调：只读 scrollTop，不做布局测量，避免滚动时反复 reflow */
function onScroll() {
  const host = scrollHost.value
  showToTop.value = host != null && host.scrollTop > TO_TOP_THRESHOLD_PX
}

/** 绑/解滚动监听；容器变化（抽屉打开、列表重挂）时重绑，避免监听丢了 */
function syncScrollHost() {
  const next = findScrollHost(rootRef.value)
  if (next === scrollHost.value) {
    onScroll()
    return
  }
  scrollHost.value?.removeEventListener('scroll', onScroll)
  scrollHost.value = next
  next?.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
}

/**
 * 回到顶端：滚的是外层滚动容器而不是组件自身，
 * 所以用 scrollTo(0) 而不是 scrollIntoView（后者会把拓扑标题也顶出视口计算范围）。
 */
function scrollToTop() {
  const host = scrollHost.value
  if (!host) {
    return
  }
  host.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  // 抽屉是异步挂载的，DOM 到位前找不到滚动祖先，等一帧再绑
  void nextTick(syncScrollHost)
  // 展开/收起卡片、换 trace 都会改内容高度：高度一变就重新判定滚动容器与显隐
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => syncScrollHost())
    if (rootRef.value) {
      resizeObserver.observe(rootRef.value)
    }
  }
})

onBeforeUnmount(() => {
  scrollHost.value?.removeEventListener('scroll', onScroll)
  scrollHost.value = null
  resizeObserver?.disconnect()
  resizeObserver = null
})

// 换 trace 或列表高度变化（展开/收起卡片）都可能导致滚动容器变化，重新判定一次
watch(
  () => [displaySpans.value.length, props.showTopology] as const,
  () => {
    void nextTick(syncScrollHost)
  }
)
</script>

<style scoped>
.trace-flow {
  min-width: 0;
}

.trace-flow__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

/*
 * 回到顶端：sticky 贴着滚动容器的右下角，随内容一起到底部时自然归位。
 * 外层 pointer-events:none，只有按钮本身可点，避免挡到卡片点击。
 */
.trace-flow__totop {
  position: sticky;
  bottom: 16px;
  z-index: 6;
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
  pointer-events: none;
  opacity: 0;
  visibility: hidden;
  transform: translateY(6px);
  transition: opacity 0.18s ease, transform 0.18s ease, visibility 0.18s;
}

.trace-flow__totop.is-visible {
  opacity: 1;
  visibility: visible;
  transform: none;
}

/* 半透明白底 + 淡描边：压在卡片上也不糊住正文 */
.trace-flow__totop-btn {
  pointer-events: auto;
  width: 36px;
  height: 36px;
  background: rgba(255, 255, 255, 0.92);
  border-color: var(--ah-border, #e4e7ec);
  box-shadow: 0 4px 12px rgba(16, 24, 40, 0.12);
  backdrop-filter: blur(2px);
}

.trace-flow__totop-btn:hover {
  color: var(--ah-primary, #635bff);
  border-color: #c7c2f5;
}
</style>
