/**
 * 工具胶囊的聚合规则。所属域：runtime（前端展示）。
 *
 * <p>为什么单独成纯模块：同一参与者、同一工具名可能被引擎并行扇出多路（`searchKnowledgeBase`
 * 一次打出 5 路很常见）。逐条并列会堆出一片蓝条 / 红条，真正的首错被淹没。
 * 这里把「按工具名聚合 + 首错优先」写成可单测的纯函数，组件只负责渲染。
 *
 * <p>不依赖 Vue、不依赖 `@/` 别名，可在 node 里直接测。
 */

/** 聚合所需的一步（与 ProcessStep 的展示字段同形，避免拉入整个类型模块） */
export interface ToolStepLike {
  type: string
  title?: string
  text?: string
  state?: 'running' | 'done' | 'error'
}

/** 聚合后的胶囊 */
export interface ToolPill {
  /** 参与者 key + 工具名，保证同轮同名只一条 */
  key: string
  name: string
  /** 还有 running → running；否则有失败 → error；否则 done */
  state: 'running' | 'done' | 'error'
  /** 调用总次数 */
  total: number
  /** running 中的次数 */
  running: number
  /** 失败次数 */
  failed: number
  /** 成功次数 */
  ok: number
  /** 首错短句（不含「撞限」之外的改写） */
  firstError: string
  /** 逐次入参 / 出参 + 首错 */
  detail: string
}

/**
 * 从工具结果正文里摘出后端附带的「首次失败原因」。
 *
 * @param text 工具结果正文
 * @return 一句话；没有该段时为空串
 */
export function firstFailureOf(text: string): string {
  const matched = /首次失败原因（([^）]*)）：([\s\S]+)$/.exec((text || '').trim())
  if (!matched) {
    return ''
  }
  const body = (matched[2] || '').trim().split('\n')[0].trim()
  return body.length > 120 ? `${body.slice(0, 120)}…` : body
}

/**
 * 工具标题里的工具名。
 *
 * <p>后端真实格式有两段（见 `TrialTexts.toolDisplay`）：
 * <ul>
 *   <li>{@code 调用工具 searchKnowledgeBase，参数 {"query":"年假"}}；</li>
 *   <li>没有参数时只有 {@code 调用工具 searchKnowledgeBase}。</li>
 * </ul>
 * 失败时再拼 {@code · 失败}。若把「，参数 …」一起当名字，每次调用都会因为参数不同
 * 变成不同工具名，同名并行就永远合并不了——这是原先胶囊堆成一片的直接原因。
 *
 * @param title 工具步骤标题
 * @return 纯工具名；认不出时返回「工具」
 */
export function toolNameOf(title: string): string {
  const text = (title || '').trim()
  if (!text) {
    return '工具'
  }
  // 去掉失败后缀
  let name = text.replace(/\s*·\s*失败\s*$/, '').trim()
  // 去掉「调用工具 」前缀
  name = name.replace(/^调用工具\s*/, '').trim()
  // 切掉参数段：「，参数 …」/ ", params …"
  const argsAt = name.search(/[，,]\s*参数/)
  if (argsAt >= 0) {
    name = name.slice(0, argsAt).trim()
  }
  // 兼容「工具名（{...}）」这种括号带参数的写法
  name = name.replace(/[（(]\s*[{[]?[\s\S]*$/, '').trim()
  return name || '工具'
}

/**
 * 工具调用行里的参数段（用于详情展示）。
 *
 * @param title 工具步骤标题
 * @return 参数字符串；没有参数时为空串
 */
export function toolArgsOf(title: string): string {
  const matched = /[，,]\s*参数\s*([\s\S]+)$/.exec((title || '').trim())
  return matched ? (matched[1] || '').trim() : ''
}

/**
 * 按工具名聚合胶囊。
 *
 * <p>规则（与拍板一致）：
 * <ul>
 *   <li>同一工具名的所有调用合成一条，计数正确；不同工具名分开；</li>
 *   <li>还有 running 时整条显示 running（不再并列多条蓝条）；</li>
 *   <li>全部结束后：有失败就是 error（写明成功 / 失败次数），全成功是 done（写总次数）；</li>
 *   <li>首错只取**第一条非熔断失败**的原因，后续撞限条目不得覆盖它；</li>
 *   <li>详情保留每一次的入参与出参，不丢任何一次结果。</li>
 * </ul>
 *
 * @param cardKey 参与者 key（用作聚合胶囊的 key 前缀）
 * @param steps   该参与者的思考 / 工具步
 * @return 胶囊列表，按工具首次出现顺序
 */
export function aggregateToolPills(cardKey: string, steps: ToolStepLike[]): ToolPill[] {
  const list = Array.isArray(steps) ? steps : []
  const order: string[] = []
  const groups = new Map<string, {
    total: number
    running: number
    failed: number
    ok: number
    firstError: string
    detail: string[]
  }>()

  const ensure = (name: string) => {
    let group = groups.get(name)
    if (!group) {
      group = { total: 0, running: 0, failed: 0, ok: 0, firstError: '', detail: [] }
      groups.set(name, group)
      order.push(name)
    }
    return group
  }

  for (let i = 0; i < list.length; i += 1) {
    const step = list[i]
    if (!step || step.type !== 'tool_call') {
      continue
    }
    const title = (step.title || '').trim()
    const name = toolNameOf(title)
    const group = ensure(name)
    group.total += 1

    // 还在跑：只计 running，不提前画成功 / 失败
    if (step.state === 'running') {
      group.running += 1
      continue
    }

    const next = list[i + 1]
    const hasResult = next && next.type === 'tool_result'
    const detail = hasResult ? (next.text || '').trim() : ''
    const failed = step.state === 'error' || title.includes('失败')
    if (failed) {
      group.failed += 1
      // 首错只认第一条真正的原因；撞限文案不覆盖它
      if (!group.firstError) {
        group.firstError = firstFailureOf(detail) || shortOf(detail || title)
      }
    } else {
      group.ok += 1
    }
    group.detail.push(`第 ${group.total} 次\n入参：${(step.text || '').trim() || '（无）'}\n出参：${detail || '（无）'}`)
    if (hasResult) {
      i += 1
    }
  }

  return order.map((name) => {
    const group = groups.get(name)!
    const state: ToolPill['state'] =
      group.running > 0 ? 'running' : group.failed > 0 ? 'error' : 'done'
    return {
      key: `${cardKey}:tool:${name}`,
      name,
      state,
      total: group.total,
      running: group.running,
      failed: group.failed,
      ok: group.ok,
      firstError: group.firstError,
      detail: group.detail.join('\n\n')
    }
  })
}

/**
 * 把一段失败正文压成一句短说明。
 *
 * @param raw 失败正文
 * @return 一句话；空正文给「未记录到首错」
 */
function shortOf(raw: string): string {
  const text = (raw || '').trim()
  if (!text) {
    return '未记录到首错'
  }
  const first = text.split('\n')[0].trim()
  return first.length > 120 ? `${first.slice(0, 120)}…` : first
}
