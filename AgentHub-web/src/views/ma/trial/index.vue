<template>
  <!--
    多智能体运行页（三栏：左会话 / 中协作流 / 右执行链路）。所属域：runtime（展示层）。
    两条入口共用同一个壳：
    - 绑根：/orchestration/multi-agent/:agentId/trial，页头读 Agent 名；
    - 虚拟根：/orchestration/multi-agent/def/:maId/trial，页头读方案 name / code。
    正文复用试跑面板的数据与执行能力，只把展示换成三栏：中栏只出协作卡摘要，
    右栏固定展示当前选中轮次的 Agent → LLM → Tool 执行链路，不再用甘特图抽屉。
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
      title="该 Agent 已停用：只能查看历史会话，启用后才能继续运行"
      type="warning"
      show-icon
      :closable="false"
      class="block-alert"
    />
    <!-- 虚拟根直接读方案：方案停用同样只挡发送，历史会话仍可见 -->
    <el-alert
      v-if="maDef && maDef.enabled !== 1"
      title="该编排方案已停用：只能查看历史会话，启用后才能继续运行"
      type="warning"
      show-icon
      :closable="false"
      class="block-alert"
    />

    <div v-loading="loading" class="ma-trial__body">
      <!--
        variant=ma：不渲染 Agent 下拉，归属由本页钉死；布局切成三栏（会话 / 协作流 / 执行链路）。
        绑根传 agentId，虚拟根传 maId（二者互斥，TrialPanel 内部优先 maId）。
        历史会话列表、发送、续订、批准等能力与抽屉里完全同一套，只换展示层。
      -->
      <TrialPanel
        v-if="ready"
        :key="panelKey"
        variant="ma"
        :agent-id="agentId || undefined"
        :ma-id="maId || undefined"
        :run-mode="shownRunMode"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'MaTrial' })

/**
 * 多智能体独立试跑页。所属域：runtime（前端）。
 *
 * <p>职责：给多智能体开一个全屏三栏运行页，让运行过程不挤在列表旁的小抽屉里：
 * 左栏会话、中栏业务协作流、右栏当前轮次的执行链路。
 * 数据与执行全部复用现网：会话/事件走 runtime 接口，编排泳道走 obs span 树，
 * 引擎仍是 RouterTrialExecutor —— 本页不新增任何执行语义。
 *
 * <p>两条入口的差别只在「页头读谁」与「会话归属」：
 * 绑根读 agent_def（经验：run_mode=ROUTER），虚拟根读 ma_def（方案名 / 编码兜底标题）。
 *
 * <p>边界：
 * - 绑根只接受 run_mode=ROUTER 的 Agent；虚拟根只接受方案；
 * - 无试跑权限、缺少标识同样回列表，不显示半截空壳；
 * - 不做第二套引擎、不落新表、不改 MA 模板逻辑。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { AgentDetail } from '@/types/agent'
import type { MaDefDetail } from '@/types/ma'
import { getAgent } from '@/api/agent'
import { getMaDef } from '@/api/ma'
import { useUserStore } from '@/stores/user'
import PageHeader from '@/components/PageHeader.vue'
import TrialPanel from '@/components/runtime/TrialPanel.vue'
import { runModeLabel } from '@/composables/agentViewFlip'
import { maTemplateHint, maTemplateLabel } from '@/utils/maList'
import {
  MA_TRIAL_BASE_PATH,
  isMaTrialPath,
  maDefTrialEntryError,
  maTrialEntryError
} from '@/utils/maTrial'

/** 返回目标：多智能体列表（没有来源历史时的兜底落点） */
const MA_LIST_PATH = MA_TRIAL_BASE_PATH

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const loading = ref(false)
const pageError = ref('')
const agent = ref<AgentDetail>()
const maDef = ref<MaDefDetail>()

/**
 * 路径上的 Agent 主键。非法值按 0 处理：交给 load 统一走「回列表」，
 * 不在模板里散落 undefined 判断。
 */
const agentId = computed(() => {
  const raw = Number(route.params.agentId)
  return Number.isFinite(raw) && raw > 0 ? raw : 0
})

/**
 * 路径上的方案主键（虚拟根）。与 agentId 互斥：命中 def/ 形态时 agentId 为 0。
 */
const maId = computed(() => {
  const raw = Number(route.params.maId)
  return Number.isFinite(raw) && raw > 0 ? raw : 0
})

/** 虚拟根模式：只有 def/ 入口才带 maId */
const maMode = computed(() => maId.value > 0)

/** 试跑权限：执行与只读二选一，和 Agent 详情「对话」按钮同一口径 */
const canTrial = computed(() => user.hasPerm('runtime:trial:run') || user.hasPerm('runtime:trial:list'))

/** 绑根模式：拿到 Router 详情才挂试跑面板，避免用错模式去开会话 */
const ready = computed(() => {
  if (maMode.value) {
    return !!maDef.value
  }
  return !!agent.value && agent.value.runMode === 'ROUTER'
})

/** 面板重挂键：换 Agent / 换方案都必须清干净旧会话，不能把 A 的会话挂到 B 上 */
const panelKey = computed(() => (maMode.value ? `ma-${maId.value}` : `agent-${agentId.value}`))

const shownRunMode = computed(() => {
  if (maMode.value) {
    return 'ROUTER'
  }
  return agent.value?.runMode || ''
})

const pageTitle = computed(() => {
  if (maMode.value) {
    // 虚拟根没有 Agent 名：标题用方案名，缺失回退编码，最后才用中性文案
    return maDef.value?.name || maDef.value?.code || '多智能体运行'
  }
  return agent.value?.name || '多智能体运行'
})

/** 页头摘要：模式 + 模板（含中文说明）+ 候选数，一眼看出在试谁的编排 */
const headerDesc = computed(() => {
  if (maMode.value) {
    const def = maDef.value
    if (!def) {
      return '三栏运行：左侧会话 · 中间协作流 · 右侧执行明细'
    }
    const parts = [`方案 ${def.code || def.id}`, runModeLabel('ROUTER'), maTemplateLabel(def.template)]
    if (typeof def.candidates?.length === 'number') {
      parts.push(`候选 ${def.candidates.length} 个`)
    }
    parts.push(maTemplateHint(def.template))
    return parts.join(' · ')
  }
  const detail = agent.value
  if (!detail) {
    return '三栏运行：左侧会话 · 中间协作流 · 右侧执行明细'
  }
  const template = detail.runtimeConfig?.maTemplate
  const parts = [runModeLabel(detail.runMode), maTemplateLabel(template)]
  if (typeof detail.routeCount === 'number') {
    parts.push(`候选 ${detail.routeCount} 个`)
  }
  parts.push(maTemplateHint(template))
  return parts.join(' · ')
})

/** 最近一次加载的令牌：连点不同入口时丢弃迟到的响应，避免详情串台 */
let loadToken = 0

/**
 * 已经发起加载的入口键（agent-1 / ma-2）。
 * 只用来判断「换了主体」，不参与渲染；比分别比对 agentId、maId 更不容易漏写。
 */
let panelLoadedKey = ''

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
 * 按入口拉详情并做进入校验。
 *
 * <p>方案运行与旧 Agent 运行各走一次校验：先做纯函数判定（缺 id / 无权限），再拉详情。
 * 方案以 {@code ma_def} 自己为根，不再按历史 {@code root_agent_id} 转到 Agent 运行页。
 *
 * @param id 当前入口主键；0 表示路径非法
 */
async function load(id: number) {
  const defMode = maMode.value
  // 缺 id / 无权限先拦，规则与入口共用同一份纯函数，避免两处口径漂移
  const early = defMode
    ? maDefTrialEntryError({ maId: id, canTrial: canTrial.value })
    : maTrialEntryError({ agentId: id, canTrial: canTrial.value })
  if (early) {
    backToList(early)
    return
  }
  const mine = ++loadToken
  loading.value = true
  pageError.value = ''
  try {
    if (defMode) {
      const { data } = await getMaDef(id)
      // 请求期间已切到别的入口：丢弃迟到结果
      if (mine !== loadToken) {
        return
      }
      const detail = data.data
      if (!detail) {
        backToList('编排方案不存在或已删除，已返回多智能体列表')
        return
      }
      maDef.value = detail
      return
    }
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
      pageError.value = defMode ? '编排方案加载失败' : 'Agent 详情加载失败'
    }
  } finally {
    if (mine === loadToken) {
      loading.value = false
    }
  }
}

/**
 * 当前路径对应的入口主键：虚拟根取 maId，绑根取 agentId。
 *
 * @return 有效主键；0 表示路径非法
 */
function currentEntryId(): number {
  return maMode.value ? maId.value : agentId.value
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

// 换 Agent / 换方案时重新校验并重载（同一 cacheName 复用组件，不靠重挂）
watch(
  () => [route.params.agentId, route.params.maId],
  () => {
    /*
     * KeepAlive 下本页失活后 watch 仍会跑：从试跑页点侧栏「Agent 管理」，
     * route.params 变成目标路由的值（主键 undefined / 0），旧逻辑据此误判「缺少标识」
     * 并 replace 回多智能体列表，把用户刚点的侧栏导航顶掉。
     * 先确认仍停在试跑路由再读 id，别把别的页面的参数当成试跑链接缺 id。
     */
    if (!isMaTrialPath(route.path)) {
      return
    }
    const nextId = currentEntryId()
    const nextKey = maMode.value ? `ma-${nextId}` : `agent-${nextId}`
    // 先把上一个详情摘掉：新入口还没校验通过时，页头与试跑面板不能继续挂着旧主体
    if (panelLoadedKey && panelLoadedKey !== nextKey) {
      agent.value = undefined
      maDef.value = undefined
    }
    panelLoadedKey = nextKey
    void load(nextId)
  },
  { immediate: true }
)
</script>

<style scoped>
/* 撑满主区：页头固定，中栏 / 右栏各自滚动，不让整页跟着消息长短上下抖 */
.ma-trial {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 480px;
  min-width: 0;
  /* 运行页统一白底：三栏与页头共用一块白色工作区，不再让灰底从栏间漏出 */
  padding: 16px 18px 18px;
  background: #fff;
  border-radius: 12px;
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
