<template>
  <!--
    多智能体独立试跑页（MA-UI 刀2）。所属域：runtime（展示层）。
    全屏对话壳：页头给返回 / Agent 名 / 模板摘要，正文直接复用试跑面板；
    编排调度由对话流内的紧凑时间线（RouterTimeline）渲染，详细甘特图在右侧抽屉。
  -->
  <div class="ma-trial">
    <PageHeader :title="pageTitle" :description="headerDesc">
      <template #extra>
        <el-button @click="goBack">返回编排</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="pageError" :title="pageError" type="error" show-icon :closable="false" class="block-alert" />
    <!-- 停用只挡发送，不挡看历史：后端也会拒新建会话，这里先说明原因 -->
    <el-alert
      v-if="agent && agent.status !== 1"
      title="该 Agent 已停用：只能查看历史会话，启用后才能继续试跑"
      type="warning"
      show-icon
      :closable="false"
      class="block-alert"
    />

    <div v-loading="loading" class="ma-trial__body">
      <!--
        variant=drawer：不渲染 Agent 下拉，agentId / runMode 由本页钉死；
        历史会话列表、发送、续订、批准等能力与抽屉里完全同一套。
      -->
      <TrialPanel
        v-if="ready"
        :key="agentId"
        variant="drawer"
        :agent-id="agentId"
        :run-mode="agent?.runMode || ''"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'MaTrial' })

/**
 * 多智能体（编排根 ROUTER）独立试跑页。所属域：runtime（前端）。
 *
 * <p>职责：给多智能体开一个全屏对话壳，让试跑不挤在列表旁的小抽屉里。
 * 数据与执行全部复用现网：会话/事件走 runtime 接口，编排泳道走 obs span 树，
 * 引擎仍是 RouterTrialExecutor —— 本页不新增任何执行语义。
 *
 * <p>边界：
 * - 只接受 run_mode=ROUTER 的 Agent；其它模式（ReAct / 计划执行）或不存在时中文提示并回列表；
 * - 无试跑权限、缺少 agentId 同样回列表，不显示半截空壳；
 * - 不做第二套引擎、不落新表、不改 MA 模板逻辑。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { AgentDetail } from '@/types/agent'
import { getAgent } from '@/api/agent'
import { useUserStore } from '@/stores/user'
import PageHeader from '@/components/PageHeader.vue'
import TrialPanel from '@/components/runtime/TrialPanel.vue'
import { runModeLabel } from '@/composables/agentViewFlip'
import { maTemplateHint, maTemplateLabel } from '@/utils/maList'
import { MA_TRIAL_BASE_PATH, maTrialEntryError } from '@/utils/maTrial'

/** 返回目标：多智能体列表（没有来源历史时的兜底落点） */
const MA_LIST_PATH = MA_TRIAL_BASE_PATH

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const loading = ref(false)
const pageError = ref('')
const agent = ref<AgentDetail>()

/**
 * 路径上的 Agent 主键。非法值按 0 处理：交给 load 统一走「回列表」，
 * 不在模板里散落 undefined 判断。
 */
const agentId = computed(() => {
  const raw = Number(route.params.agentId)
  return Number.isFinite(raw) && raw > 0 ? raw : 0
})

/** 试跑权限：执行与只读二选一，和 Agent 详情「对话」按钮同一口径 */
const canTrial = computed(() => user.hasPerm('runtime:trial:run') || user.hasPerm('runtime:trial:list'))

/** 只有拿到 Router 详情才挂试跑面板，避免用错模式去开会话 */
const ready = computed(() => !!agent.value && agent.value.runMode === 'ROUTER')

const pageTitle = computed(() => agent.value?.name || '多智能体试跑')

/** 页头摘要：模式 + 模板（含中文说明）+ 候选数，一眼看出在试谁的编排 */
const headerDesc = computed(() => {
  const detail = agent.value
  if (!detail) {
    return '全屏试跑：对话在下方，编排调度卡在消息流内'
  }
  const template = detail.runtimeConfig?.maTemplate
  const parts = [runModeLabel(detail.runMode), maTemplateLabel(template)]
  if (typeof detail.routeCount === 'number') {
    parts.push(`候选 ${detail.routeCount} 个`)
  }
  parts.push(maTemplateHint(template))
  return parts.join(' · ')
})

/** 最近一次加载的令牌：连点不同 Agent 时丢弃迟到的响应，避免详情串台 */
let loadToken = 0

/**
 * 中文提示并退回多智能体列表。
 * 用于「不该进本页」的三种情况：缺 id、无权限、非 Router。
 *
 * @param message 提示文案
 */
function backToList(message: string) {
  ElMessage.warning(message)
  void router.replace(MA_LIST_PATH)
}

/**
 * 拉 Agent 详情并做进入校验。
 *
 * @param id Agent 主键；0 表示路径非法
 */
async function load(id: number) {
  // 缺 id / 无权限先拦，规则与入口共用同一份纯函数，避免两处口径漂移
  const early = maTrialEntryError({ agentId: id, canTrial: canTrial.value })
  if (early) {
    backToList(early)
    return
  }
  const mine = ++loadToken
  loading.value = true
  pageError.value = ''
  try {
    const { data } = await getAgent(id)
    // 请求期间已切到别的 Agent：丢弃迟到结果
    if (mine !== loadToken) {
      return
    }
    const detail = data.data
    if (!detail) {
      backToList('Agent 不存在或已删除，已返回多智能体列表')
      return
    }
    // 本页只服务编排根：ReAct / 计划执行没有一层编排，进来也看不到泳道
    const reject = maTrialEntryError({ agentId: id, canTrial: canTrial.value, runMode: detail.runMode })
    if (reject) {
      backToList(reject)
      return
    }
    agent.value = detail
  } catch {
    if (mine === loadToken) {
      pageError.value = 'Agent 详情加载失败'
    }
  } finally {
    if (mine === loadToken) {
      loading.value = false
    }
  }
}

/**
 * 返回来源页。
 *
 * <p>优先 router.back()：无论从多智能体列表还是 Agent 详情的「编排」Tab 进来，
 * 都回到用户来的那一步；上一页在 KeepAlive 里，未保存的配置草稿原样保留。
 * 直接打开本页链接（没有来源历史）时退回多智能体列表。
 */
function goBack() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  void router.push(MA_LIST_PATH)
}

// 换 Agent 时重新校验并重载（同一 cacheName 复用组件，不靠重挂）
watch(
  agentId,
  (id) => {
    // 先把上一个详情摘掉：新 id 还没校验通过时，页头与试跑面板不能继续挂着旧 Agent
    if (agent.value && agent.value.id !== id) {
      agent.value = undefined
    }
    void load(id)
  },
  { immediate: true }
)
</script>

<style scoped>
/* 撑满主区：页头固定，对话区自己滚，不让整页跟着消息长短上下抖 */
.ma-trial {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 480px;
  min-width: 0;
}

.ma-trial__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.block-alert {
  margin-bottom: 12px;
}
</style>
