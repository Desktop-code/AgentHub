/**
 * 试跑会话的「上次打开」记忆。所属域：runtime（前端）。
 *
 * 抽屉关闭即卸载 TrialPanel，重进是全新实例；刷新页面更是整个 SPA 重来。
 * 靠本模块按 Agent 记住 sessionId，让重进 / 刷新后都能立刻回到该会话拉 events。
 *
 * 只记**已落库**的会话：前端临时「新对话」用负 id，从不写这里，
 * 因此「新建会话但不提问就刷新」自然什么都不留 —— 后端也确实没有这条会话，
 * 不该凭空造一条历史记录出来。
 *
 * 存 sessionStorage 而非 localStorage：同一标签页刷新仍在，新开标签页互不干扰。
 * 只存会话主键，不含任何对话正文。
 */
const STORAGE_KEY = 'agenthub_trial_last_session'

/** 内存副本，避免每次读取都解析 JSON。 */
let cache: Record<string, number> | null = null

/**
 * 读整张表。sessionStorage 不可用（隐私模式、单测环境）时退回空表，不让试跑页崩。
 * @return agentId → sessionId
 */
function table(): Record<string, number> {
  if (cache) {
    return cache
  }
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    const parsed = raw ? (JSON.parse(raw) as unknown) : null
    cache = parsed && typeof parsed === 'object' ? (parsed as Record<string, number>) : {}
  } catch {
    // 存的不是合法 JSON 就当没记过，下次写入会覆盖
    cache = {}
  }
  return cache
}

/** 写回。失败（配额满等）只影响下次重进，不影响本次会话。 */
function persist() {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(table()))
  } catch {
    // 忽略：退化成「本次打开有效」
  }
}

/**
 * 记住当前 Agent 正在看的会话。
 * 只该在**会话确已落库**后调用：`send` 里 create 成功、或在列表里点开历史会话。
 * 前端临时草稿（负 id）会被直接忽略，保证「新建不提问就刷新」不留痕迹。
 * @param agentId  Agent 主键，未选中时调用方不应调用
 * @param sessionId 落库会话 id
 */
export function rememberTrialSession(agentId: number, sessionId: number) {
  if (!agentId || sessionId == null || sessionId < 0) {
    return
  }
  table()[String(agentId)] = sessionId
  persist()
}

/**
 * 取该 Agent 上次看的会话。
 * @param agentId Agent 主键
 * @return 没有记录时为 undefined
 */
export function recallTrialSession(agentId?: number): number | undefined {
  if (!agentId) {
    return undefined
  }
  const id = table()[String(agentId)]
  return typeof id === 'number' && id > 0 ? id : undefined
}

/**
 * 会话已删除、或不属于当前 Agent 时清掉记忆，避免重进反复拉不存在的 id。
 * @param sessionId 会话 id
 */
export function forgetTrialSession(sessionId: number) {
  if (sessionId == null || sessionId < 0) {
    return
  }
  let changed = false
  for (const [agentId, id] of Object.entries(table())) {
    if (id === sessionId) {
      delete table()[agentId]
      changed = true
    }
  }
  if (changed) {
    persist()
  }
}
