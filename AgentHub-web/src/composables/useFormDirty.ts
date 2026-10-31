/**
 * 表单脏检查公共 composable。所属域：前端基础设施。
 *
 * 职责：给「取消 + 保存」底栏的配置页提供统一的 snapshot / isDirty / markClean / reset。
 * 约束：
 * <ul>
 *   <li>只比「填写 / 选择类」字段；开关（启停、enable 类）一律即时落库，不进快照；</li>
 *   <li>比较走稳定序列化（对象键递归排序），避免引用换新或键序不同误判脏；</li>
 *   <li>快照必须是可 JSON 化的纯数据（用 snapshot() 现拼，不要直接塞 reactive 对象）；</li>
 *   <li>接口回填 / 保存成功后必须调用 markClean()，否则异步回填会被误判为脏。</li>
 * </ul>
 * 本文件不依赖 Element Plus，便于用 node:test 直接跑单测。
 */
import { computed, ref, type Ref } from 'vue'

/**
 * 稳定序列化：递归按对象键升序后 JSON 化。
 * 仅用于脏比较，不要拿它做业务传输。
 *
 * @param value 任意可 JSON 化数据
 * @return 稳定字符串（同值必同串）
 */
export function stableKey(value: unknown): string {
  return JSON.stringify(canonicalize(value))
}

/**
 * 递归排序对象键，数组保序（顺序本身是业务语义，不能重排）。
 *
 * @param value 原始值
 * @return 键有序的新结构
 */
function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => canonicalize(item))
  }
  if (value && typeof value === 'object') {
    const source = value as Record<string, unknown>
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(source).sort()) {
      sorted[key] = canonicalize(source[key])
    }
    return sorted
  }
  return value
}

/**
 * 纯数据深拷贝：冻结快照，撤销时不会被后续编辑改到。
 *
 * @param value 由 snapshot() 拼出的普通对象
 * @return 与入参结构一致的新对象
 */
export function clonePlain<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

export interface FormDirtyApi<S> {
  /** 最近一次「已落库」快照；null 表示尚未 markClean（此时一律不脏） */
  baseline: Ref<S | null>
  /** 相对快照是否有未提交改动 */
  isDirty: Ref<boolean>
  /** 把当前值记为新的已落库快照 */
  markClean: () => void
  /**
   * 显式指定已落库快照。
   * 用于「服务端值已更新、但表单里还留着用户草稿」的场景：
   * 直接用 markClean 会把草稿误当基线，导致改动凭空消失。
   *
   * @param next 已落库值；null 表示暂无可比对象（此后一律不脏）
   */
  setBaseline: (next: S | null) => void
  /**
   * 只把快照里的部分字段更新为已落库值（其它字段的草稿脏态保持不变）。
   * 用于「某个子域走了独立保存链路」的场景（如提示词弹窗自带保存），
   * 不能用 markClean：那会把用户同一页其它未提交的文本改动一起洗白。
   *
   * @param partial 已落库的字段
   */
  patchBaseline: (partial: Partial<S>) => void
  /** 丢弃草稿：把快照回填进表单 */
  reset: () => void
  /** 清空快照（组件关闭 / 无可比对象时用） */
  forget: () => void
}

/**
 * 创建一组脏检查 API。
 *
 * @param snapshot 现拼当前草稿的纯数据；表单未就绪时返回 null
 * @param restore  把快照写回表单（只回写文本 / 选择类字段，开关不动）
 * @return 脏状态与操作方法
 */
export function useFormDirty<S>(
  snapshot: () => S | null,
  restore: (snap: S) => void
): FormDirtyApi<S> {
  const baseline = ref<S | null>(null) as Ref<S | null>

  const isDirty = computed(() => {
    const current = snapshot()
    if (current === null || baseline.value === null) {
      return false
    }
    return stableKey(current) !== stableKey(baseline.value)
  })

  /** 接口回填 / 保存成功后调用，把当前值定为干净基线 */
  function markClean() {
    const current = snapshot()
    baseline.value = current === null ? null : clonePlain(current)
  }

  /**
   * 用已落库值显式覆盖基线（不采信表单当前值）。
   *
   * @param next 已落库值；null 表示清空
   */
  function setBaseline(next: S | null) {
    baseline.value = next === null ? null : clonePlain(next)
  }

  /**
   * 只更新指定字段的基线：其它字段的草稿继续参与脏比较。
   * 典型场景：页内某个弹窗自带保存链路，保存成功后只该洗白它改的那几列。
   *
   * @param partial 已落库字段
   */
  function patchBaseline(partial: Partial<S>) {
    if (baseline.value === null) {
      markClean()
      return
    }
    baseline.value = { ...baseline.value, ...clonePlain(partial) }
  }

  /** 取消：恢复上次成功保存的快照，随后 isDirty 自然归零 */
  function reset() {
    if (baseline.value === null) {
      return
    }
    restore(clonePlain(baseline.value))
  }

  function forget() {
    baseline.value = null
  }

  return { baseline, isDirty, markClean, setBaseline, patchBaseline, reset, forget }
}

/**
 * 基于「普通 reactive 表单对象」的脏检查快捷版。
 * <p>
 * 与 {@link useFormDirty} 的区别：直接接收取表单的函数，快照取它、取消时逐键写回它，
 * 不必每个弹窗手写一遍 snapshot / restore。适合字段少、无嵌套结构的编辑弹窗。
 * 传函数而不是对象引用，是为了兼容「编辑对象切换时整体替换表单对象」的写法。
 *
 * @param source 返回当前表单对象（每次调用都返回最新引用）
 * @param ignore 不参与脏比较的键（如纯展示字段、非受控的派生值）
 * @return 脏状态与操作方法（接口同 FormDirtyApi）
 */
export function useObjectDirty<T extends Record<string, unknown>>(
  source: () => T | null,
  ignore: string[] = []
): FormDirtyApi<T> {
  /** 浅拷贝一份纯对象，并剔除不参与比较的键 */
  function pick(): T | null {
    const form = source()
    if (form === null) {
      return null
    }
    const plain = clonePlain(form) as T
    for (const key of ignore) {
      delete plain[key]
    }
    return plain
  }

  return useFormDirty<T>(pick, (snap) => {
    const form = source()
    if (!form) {
      return
    }
    // 逐键写回 reactive 对象，保留响应式引用（整体赋值会断开 v-model 的绑定）
    for (const key of Object.keys(snap) as Array<keyof T>) {
      form[key] = snap[key]
    }
  })
}
