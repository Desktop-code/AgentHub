/**
 * 试跑抽屉的跨页状态。所属域：runtime。
 * 详情运行模式行的「对话」写这里，壳层只挂一个抽屉。侧栏不再提供试跑入口，也不跳 /agent/trial。
 *
 * <p>挂载策略（性能刀5）：抽屉整树（TrialPanel + ChatPanel + maRunView…）体积大，
 * 绝大多数会话根本不会运行，没必要跟着壳首屏一起下载。因此：
 * <ul>
 *   <li>初始 {@link useAgentTrial} 的 `mounted` 为 false → 壳层用 `v-if` 不渲染抽屉，
 *       浏览器也就不会取抽屉的异步 chunk；</li>
 *   <li>第一次调用 {@link openAgentTrial} 时才置 `mounted = true` 触发挂载，并等 chunk 就绪、
 *       抽屉以关闭态完成首帧之后才置 `open = true`，保证首开不是「空壳等一下再弹内容」；</li>
 *   <li>之后常驻：关闭只置 `open = false`，不卸载，关闭动画与二次打开体验与现网一致。</li>
 * </ul>
 */
import { nextTick, ref } from 'vue'

/** 旧菜单路径。侧栏、工作台和登录后的第一个页面都要丢掉它。 */
export const TRIAL_MENU_PATH = '/agent/trial'

export interface TrialTarget {
  agentId?: number
  name: string
  runMode: string
}

const open = ref(false)
const target = ref<TrialTarget>({ name: '', runMode: '' })

/**
 * 抽屉是否已经挂载过。false 时壳层不渲染它（`v-if`），因此不会下载抽屉 chunk。
 * 一旦置 true 就再也不回退：首次打开之后常驻，保证关闭动画与二次打开零等待。
 */
const mounted = ref(false)

/** 首次打开正在等 chunk：期间重复调用只更新 target，不抢跑 open（否则会开了个空壳） */
let firstOpenPending = false

/**
 * 抽屉 chunk 的共用工厂。壳层 `defineAsyncComponent` 与这里的「等就绪」用同一个说明符，
 * 打出来的仍是同一个异步 chunk；模块级缓存保证重复调用不会第二次发请求。
 */
let drawerChunk: ReturnType<typeof importDrawer> | null = null

/** 动态引入抽屉组件（不执行不下载） */
function importDrawer() {
  return import('@/components/agent/AgentTrialDrawer.vue')
}

/**
 * 预取 / 复用抽屉 chunk。
 *
 * <p>只给壳层的 `defineAsyncComponent` 与本文件的首次打开链路用；
 * 静态 import 本文件不会触发下载，因而不会把收益吃掉。
 *
 * @return 抽屉模块的加载 Promise（同一模块只加载一次）
 */
export function preloadTrialDrawer() {
  if (!drawerChunk) {
    drawerChunk = importDrawer()
  }
  return drawerChunk
}

/**
 * 等一帧：让抽屉先以「关闭态」完成首帧渲染，随后再置 open 才有正常的滑入动画。
 * 没有 rAF 的环境（如单测）退化成宏任务，语义一致。
 */
function nextFrame() {
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    return new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()))
  }
  return new Promise<void>((resolve) => setTimeout(resolve, 0))
}

/**
 * 打开指定 Agent 的试跑抽屉。
 *
 * <p>首次调用会先挂载抽屉并等 chunk 就绪，再置可见；第二次及以后同步可见，
 * 与改造前「壳一挂载抽屉就在」的打开体感一致。
 *
 * @param next 至少要有 agentId；名称和运行模式用于标题和徽章
 */
export function openAgentTrial(next: { agentId: number; name?: string; runMode?: string }) {
  // 同一个 Agent 且调用方只带了 agentId（如 `?trial=1` 的路由守卫）时沿用已有名称/模式：
  // 首开是异步挂载的，open 还没置 true，老守卫拦不住这次重复调用，不能把标题与徽章冲成空
  const sameAgent = target.value.agentId === next.agentId
  target.value = {
    agentId: next.agentId,
    name: next.name || (sameAgent ? target.value.name : '') || '',
    runMode: next.runMode || (sameAgent ? target.value.runMode : '') || ''
  }
  // 已经挂载过（二次及以后）：即刻可见，不必等任何东西
  if (mounted.value) {
    open.value = true
    return
  }
  // 首次：先置挂载位，让壳层开始渲染抽屉（异步组件同时开始取 chunk）
  mounted.value = true
  // 首开链路已在跑：只更新 target 即可，避免第二次调用抢跑把 open 提前置 true
  if (firstOpenPending) {
    return
  }
  firstOpenPending = true
  void preloadTrialDrawer()
    // 取 chunk 失败交给 defineAsyncComponent 的错误兜底；这里只保证不会卡在「永不打开」
    .catch(() => undefined)
    // 等抽屉以关闭态挂上
    .then(() => nextTick())
    // 再等一帧，open 才有滑入动画
    .then(nextFrame)
    .then(() => {
      open.value = true
    })
    .finally(() => {
      firstOpenPending = false
    })
}

/** 关闭抽屉。地址栏上的 trial 参数由壳层清掉。不卸载，保持常驻。 */
export function closeAgentTrial() {
  open.value = false
}

/**
 * 给壳层和页面共用同一份开关。在组件 setup 里调用。
 *
 * @return `mounted` 供壳层做 `v-if`（首开前不渲染抽屉），其余与改造前一致
 */
export function useAgentTrial() {
  return { open, target, mounted, openAgentTrial, closeAgentTrial }
}
