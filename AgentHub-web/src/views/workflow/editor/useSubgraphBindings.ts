/**
 * 子图节点的「已发布工作流 / 版本 / 入参声明」读取。所属域：workflow 的前端画布层。
 *
 * <p>职责：给属性面板提供可绑定的已发布工作流、某个工作流的版本列表，以及
 * 「某个发布版本的开始节点声明」；并做按 key 的缓存，画布上多个子图节点钉同一版本时只查一次。
 *
 * <p>边界：
 * <ul>
 *   <li>只读，不改任何配置；写回由编辑页负责；</li>
 *   <li>只认发布版本：读声明走 {@code releases/{id}/input-schema}，不读对方草稿；</li>
 *   <li>跨租户由后端统一 404，这里只把失败降级成「读不到声明」，不阻断画布；</li>
 *   <li>并发上限保守：一次最多并发 6 个请求，避免打开一张图就打满后端。</li>
 * </ul>
 */
import { ref } from 'vue'
import {
  getWorkflowReleaseInputSchema,
  listWorkflowReleases,
  pageWorkflowDefs
} from '@/api/workflow'
import type { WorkflowDefRow, WorkflowReleaseRow } from '@/types/workflow'

/** 子图节点的绑定引用（画布 config 里的两个 id） */
export interface SubgraphBindingRef {
  workflowId: number
  releaseId: number
}

/** 单次并发上限：子图节点通常只有个位数，6 足够又不至于打满后端 */
const MAX_CONCURRENCY = 6

/**
 * 子图绑定数据源。
 *
 * @return 选项、加载状态、声明缓存与查询方法
 */
export function useSubgraphBindings() {
  /** 当前租户已发布工作流（HAS_RELEASE）；上限 100 条与列表接口一致 */
  const workflows = ref<WorkflowDefRow[]>([])
  const workflowsLoading = ref(false)
  const workflowsError = ref('')
  let workflowsLoaded = false

  /** 当前选中工作流的版本列表（按版本号倒序，后端已排好） */
  const releases = ref<WorkflowReleaseRow[]>([])
  const releasesLoading = ref(false)
  const releasesError = ref('')
  /** 已拉过的版本列表：一份工作流通常只钉一两个版本，反复选中同一节点不该重复请求 */
  const releaseCache = new Map<number, WorkflowReleaseRow[]>()

  /** releaseId → 声明；null 表示该版本确实没有声明（同样缓存，避免反复请求） */
  const declarationCache = new Map<string, unknown>()
  /** 声明 key → 版本号：画布上其它未选中的子图节点也能显示 vN，不必再拉每个工作流的版本列表 */
  const releaseVersionCache = new Map<string, number>()
  /** 正在请求的 key，避免同一版本并发重复请求 */
  const declarationPending = new Map<string, Promise<void>>()
  const declarationsLoading = ref(false)

  /** 版本列表请求序号：切换工作流时丢弃过期响应 */
  let releaseToken = 0

  /** 声明缓存键：工作流 + 版本双键，避免同 id 版本偶发跨工作流时串数据 */
  function declarationKey(workflowId: number, releaseId: number): string {
    return `${workflowId}:${releaseId}`
  }

  /**
   * 载入已发布工作流（幂等：成功后不再重复请求）。
   *
   * @param force 是否强制刷新（失败后重试 / 打开下拉时手动刷新）
   */
  async function ensureWorkflows(force = false): Promise<void> {
    if (workflowsLoading.value || (workflowsLoaded && !force)) {
      return
    }
    workflowsLoading.value = true
    workflowsError.value = ''
    try {
      const { data } = await pageWorkflowDefs({ current: 1, size: 100, status: 'HAS_RELEASE' })
      workflows.value = data.data?.records || []
      workflowsLoaded = true
    } catch {
      workflows.value = []
      workflowsError.value = '已发布工作流加载失败，暂时无法绑定子图；请刷新页面重试'
    } finally {
      workflowsLoading.value = false
    }
  }

  /**
   * 载入某个工作流的版本列表。
   * <p>切换工作流时旧列表先清空：避免用户看到上一个工作流的版本号并误选。
   * <p>同一工作流已拉过就直接用缓存：画布反复选中同一个子图节点不该每次都打后端。
   *
   * @param workflowId 被调工作流 id；空表示清空
   * @param force      是否强制重新拉取（发布新版本后手动刷新）
   */
  async function loadReleases(workflowId?: number | null, force = false): Promise<void> {
    const id = Number(workflowId)
    releaseToken += 1
    const current = releaseToken
    releasesError.value = ''
    if (!id) {
      releases.value = []
      releasesLoading.value = false
      return
    }
    const cached = releaseCache.get(id)
    if (cached && !force) {
      releases.value = cached
      releasesLoading.value = false
      return
    }
    releases.value = []
    releasesLoading.value = true
    try {
      const { data } = await listWorkflowReleases(id)
      // 过期响应（用户已换绑别的子图）直接丢弃
      if (current !== releaseToken) {
        return
      }
      const list = (data.data || []).filter((item) => Number(item.id) > 0)
      releaseCache.set(id, list)
      releases.value = list
    } catch {
      if (current !== releaseToken) {
        return
      }
      releases.value = []
      releasesError.value = '该工作流的发布版本加载失败；请重试或重新选择工作流'
    } finally {
      if (current === releaseToken) {
        releasesLoading.value = false
      }
    }
  }

  /**
   * 取某发布版本的声明；没有缓存时请求一次。
   *
   * @param workflowId 被调工作流 id
   * @param releaseId  发布版本 id
   * @return 声明对象；该版本没有声明或读取失败时为 null
   */
  async function ensureDeclaration(workflowId: number, releaseId: number): Promise<void> {
    if (!workflowId || !releaseId) {
      return
    }
    const key = declarationKey(workflowId, releaseId)
    if (declarationCache.has(key)) {
      return
    }
    const pending = declarationPending.get(key)
    if (pending) {
      await pending
      return
    }
    const task = (async () => {
      try {
        const { data } = await getWorkflowReleaseInputSchema(workflowId, releaseId)
        declarationCache.set(key, data.data?.inputSchema ?? null)
        // 版本号顺手缓存：其它未选中的子图节点也能显示 vN，不必再拉版本列表
        const version = Number(data.data?.version)
        if (Number.isFinite(version) && version > 0) {
          releaseVersionCache.set(key, version)
        }
      } catch {
        // 读不到就按「没有声明」处理：画布保持自由添加，发布仍由后端拦版本失效
        declarationCache.set(key, null)
      } finally {
        declarationPending.delete(key)
        // 缓存写入后推进版本号，触发画布必填提示重算
        declarationVersion.value += 1
      }
    })()
    declarationPending.set(key, task)
    declarationsLoading.value = true
    try {
      await task
    } finally {
      if (!declarationPending.size) {
        declarationsLoading.value = false
      }
    }
  }

  /**
   * 批量预热声明缓存（画布铺完节点后调用）。
   * <p>只为「已经绑好工作流 + 版本」的子图节点请求，去重后按 {@link MAX_CONCURRENCY}
   * 分批并发；单个失败不影响其它节点。
   *
   * @param refs 节点的绑定引用列表
   */
  async function ensureDeclarations(refs: SubgraphBindingRef[]): Promise<void> {
    const unique = new Map<string, SubgraphBindingRef>()
    refs.forEach((item) => {
      const workflowId = Number(item?.workflowId)
      const releaseId = Number(item?.releaseId)
      if (!workflowId || !releaseId) {
        return
      }
      const key = declarationKey(workflowId, releaseId)
      if (!declarationCache.has(key) && !declarationPending.has(key) && !unique.has(key)) {
        unique.set(key, { workflowId, releaseId })
      }
    })
    const targets = [...unique.values()]
    if (!targets.length) {
      return
    }
    declarationsLoading.value = true
    try {
      for (let index = 0; index < targets.length; index += MAX_CONCURRENCY) {
        const batch = targets.slice(index, index + MAX_CONCURRENCY)
        await Promise.all(batch.map((item) => ensureDeclaration(item.workflowId, item.releaseId)))
      }
    } finally {
      declarationsLoading.value = false
    }
  }

  /**
   * 声明缓存版本号：缓存是普通 Map，不具响应式；每写入一条就 +1，
   * 画布据此重算「必填入参未配」提示。
   */
  const declarationVersion = ref(0)

  /**
   * 读缓存里的声明（同步）。
   *
   * @param workflowId 被调工作流 id
   * @param releaseId  发布版本 id
   * @return 声明对象；没缓存 / 该版本没有声明时为 null
   */
  function declarationFor(workflowId?: number | null, releaseId?: number | null): unknown {
    const workflow = Number(workflowId)
    const release = Number(releaseId)
    if (!workflow || !release) {
      return null
    }
    return declarationCache.get(declarationKey(workflow, release)) ?? null
  }

  /**
   * 读缓存里的发布版本号（同步）。
   * <p>画布上未选中的子图节点无法拿到版本列表，用声明接口顺手缓存的版本号显示 vN；
   * 没缓存时返回 null，卡面不编造版本号。
   *
   * @param workflowId 被调工作流 id
   * @param releaseId  发布版本 id
   * @return 版本号；没缓存返回 null
   */
  function versionFor(workflowId?: number | null, releaseId?: number | null): number | null {
    const workflow = Number(workflowId)
    const release = Number(releaseId)
    if (!workflow || !release) {
      return null
    }
    return releaseVersionCache.get(declarationKey(workflow, release)) ?? null
  }

  /** 重置：切换工作流定义时清空版本与声明缓存，避免串数据 */
  function reset() {
    workflows.value = []
    workflowsLoaded = false
    workflowsError.value = ''
    releases.value = []
    releasesError.value = ''
    releaseCache.clear()
    declarationCache.clear()
    declarationPending.clear()
    releaseVersionCache.clear()
    declarationVersion.value += 1
    releaseToken += 1
  }

  return {
    workflows,
    workflowsLoading,
    workflowsError,
    releases,
    releasesLoading,
    releasesError,
    declarationsLoading,
    declarationVersion,
    ensureWorkflows,
    loadReleases,
    ensureDeclaration,
    ensureDeclarations,
    declarationFor,
    versionFor,
    reset
  }
}
