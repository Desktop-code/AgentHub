/**
 * 跨组件「未保存草稿」登记表。所属域：前端基础设施。
 *
 * 背景：详情组件在 KeepAlive 缓存里，同一个详情实例可能同时存在多个 Agent 的草稿。
 * 父级（列表 / 左栏）在切走之前需要知道「目标/当前对象有没有未提交草稿」，
 * 又不能直接读子组件内部状态，因此用一张按 key 登记的轻量表来解耦。
 *
 * 约定：
 * <ul>
 *   <li>登记方提供 isDirty 判定与 discard（回滚到已落库值）；</li>
 *   <li>查询方只读；是否丢弃由用户确认后由查询方调用 discard；</li>
 *   <li>组件卸载时务必调用返回的注销函数，避免缓存实例失效后仍被查到。</li>
 * </ul>
 */
import { confirmDiscardUnsaved } from '@/utils/unsavedConfirm'

/** 一条登记：判定是否有草稿 + 丢弃草稿 */
interface DraftEntry {
  isDirty: () => boolean
  discard: () => void
}

const registry = new Map<string, DraftEntry>()

/**
 * 登记一份草稿。
 *
 * @param key     登记键（如 `agent:12`）
 * @param entry   脏判定与丢弃实现
 * @return 注销函数
 */
export function registerDraft(key: string, entry: DraftEntry): () => void {
  registry.set(key, entry)
  return () => {
    // 只注销自己那次登记：同 key 可能已被新实例覆盖（同一 Agent 重新挂载）
    if (registry.get(key) === entry) {
      registry.delete(key)
    }
  }
}

/**
 * 该对象当前是否有未提交草稿。
 *
 * @param key 登记键
 * @return true 表示有草稿
 */
export function hasDraft(key: string): boolean {
  const entry = registry.get(key)
  return entry ? entry.isDirty() : false
}

/**
 * 切走前询问一次：有草稿时弹确认，答「丢弃」才回滚并放行。
 *
 * @param key   登记键
 * @param scene 场景名（如「Agent 配置」），**不要**自带「有未保存的更改」
 * @return true 表示允许离开
 */
export async function confirmLeaveDraft(key: string, scene: string): Promise<boolean> {
  const entry = registry.get(key)
  if (!entry || !entry.isDirty()) {
    return true
  }
  const leave = await confirmDiscardUnsaved(scene)
  if (!leave) {
    return false
  }
  entry.discard()
  return true
}
