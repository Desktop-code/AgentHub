<template>
  <!--
    执行链路右拉抽屉。所属域：obs。
    复用 TraceSpanFlow（拓扑 + 时间轴 + 入出参）；评测/观测共用，禁止跳转调用追踪全页。
  -->
  <el-drawer
    :model-value="modelValue"
    :title="drawerTitle"
    direction="rtl"
    :size="drawerSize"
    append-to-body
    class="trace-link-drawer"
    :modal="true"
    modal-class="trace-link-drawer-mask"
    @update:model-value="onVisible"
  >
    <div v-loading="loading" class="trace-link-drawer__body">
      <!-- 无 traceId：中文空态 + 可复制关联 id，避免白屏跳转 -->
      <div v-if="!traceId" class="empty-box">
        <p class="empty">无追踪数据，无法打开执行链路</p>
        <ul v-if="hasFallbackIds" class="id-list">
          <li v-if="sessionId">
            session：
            <el-button link type="primary" @click="copyText(String(sessionId))">{{ sessionId }}</el-button>
          </li>
          <li v-if="runId != null">
            run：
            <el-button link type="primary" @click="copyText(String(runId))">{{ runId }}</el-button>
          </li>
          <li v-if="resultId != null">
            result：
            <el-button link type="primary" @click="copyText(String(resultId))">{{ resultId }}</el-button>
          </li>
        </ul>
      </div>
      <p v-else-if="!user.hasPerm('obs:span:list')" class="empty">没有步骤查询权限</p>
      <p v-else-if="!loading && spans.length === 0" class="empty">没有步骤</p>
      <div v-else-if="spans.length > 0" class="span-tree span-tree--wide">
        <TraceSpanFlow :spans="spans" :is-wide-mode="true" :show-topology="true" />
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 执行链路右拉。所属域：obs。
 * 按 traceId 拉 spans 并渲染现网 TraceSpanFlow；评测跑批/坏案与观测放大共用。
 * 宽度钉死 DRAWER_SIZE（相对屏宽约 62%，桌面通常 ≥720px）。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listObsSpans } from '@/api/obs'
import type { ObsSpan } from '@/types/obs'
import { useUserStore } from '@/stores/user'
import TraceSpanFlow from '@/components/obs/TraceSpanFlow.vue'

/** 交付钉死：屏宽约 62%（落在 55%～65%）；宽屏下明显宽于窄抽屉 */
const TRACE_LINK_DRAWER_SIZE = '62%'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 业务 traceId；优先据此打开 */
    traceId?: string | null
    /** 无 trace 时展示可复制 */
    sessionId?: string | null
    runId?: number | null
    resultId?: number | null
    /**
     * 父级已持有的 spans（如观测页右栏已加载）；传入则不再请求。
     * 评测侧一般不传，由本组件按 traceId 自拉。
     */
    spans?: ObsSpan[] | null
    /** 覆盖默认宽度；默认 62% */
    size?: string
  }>(),
  {
    traceId: null,
    sessionId: null,
    runId: null,
    resultId: null,
    spans: null,
    size: TRACE_LINK_DRAWER_SIZE
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const user = useUserStore()
const loading = ref(false)
const localSpans = ref<ObsSpan[]>([])

const drawerSize = computed(() => props.size || TRACE_LINK_DRAWER_SIZE)

const drawerTitle = computed(() =>
  props.traceId ? `执行链路 (${props.traceId})` : '执行链路'
)

/** 展示用 spans：父传入优先，否则用自拉结果 */
const spans = computed(() => {
  if (props.spans && props.spans.length > 0) {
    return props.spans
  }
  return localSpans.value
})

const hasFallbackIds = computed(
  () => !!props.sessionId || props.runId != null || props.resultId != null
)

/**
 * 打开时按需拉取 spans；关闭时不清空父传入的 spans，只清本地缓存以免串单。
 */
watch(
  () => [props.modelValue, props.traceId, props.spans] as const,
  async ([open, traceId, parentSpans]) => {
    if (!open) {
      return
    }
    // 父已给 spans：观测放大场景，跳过请求
    if (parentSpans && parentSpans.length > 0) {
      localSpans.value = []
      return
    }
    if (!traceId) {
      localSpans.value = []
      return
    }
    if (!user.hasPerm('obs:span:list')) {
      localSpans.value = []
      return
    }
    loading.value = true
    try {
      const { data } = await listObsSpans(traceId)
      localSpans.value = data.data || []
    } catch {
      localSpans.value = []
      ElMessage.error('加载执行链路失败')
    } finally {
      loading.value = false
    }
  }
)

function onVisible(v: boolean) {
  emit('update:modelValue', v)
  if (!v) {
    localSpans.value = []
  }
}

/** 空态复制关联 id，方便排查 */
function copyText(text: string) {
  void navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}
</script>

<style scoped>
.trace-link-drawer__body {
  min-height: 200px;
}

.span-tree--wide {
  padding: 0 4px 24px;
}

.empty-box {
  padding: 24px 8px;
  text-align: center;
}

.empty {
  margin: 24px 0;
  color: var(--ah-text-secondary, #64748b);
  font-size: 13px;
  text-align: center;
}

.id-list {
  list-style: none;
  margin: 0;
  padding: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.8;
}
</style>

<style>
/* 抽屉挂 body，非 scoped；半透明遮罩 + 可滚内容 */
.trace-link-drawer-mask {
  background-color: rgba(15, 23, 42, 0.45) !important;
}

.trace-link-drawer .el-drawer__body {
  padding-top: 4px;
  overflow: auto;
}

/* 窄屏保底可读宽度，避免 62% 过窄时挤成一列难对照 */
.trace-link-drawer.el-drawer {
  min-width: 720px;
  max-width: 100vw;
}
</style>
