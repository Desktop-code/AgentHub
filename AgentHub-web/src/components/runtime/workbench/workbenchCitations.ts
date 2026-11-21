/**
 * 工作台引用区：从已经投影好的工具结果里抽出可展开的出处。所属域：runtime（前端展示）。
 *
 * <p>知识库检索的真实返回是给模型看的紧凑 JSON（content / title / source / score / hitBy），
 * 直播 SSE 与回放落库都进同一条工具事件，再由现有参与者投影写成 tool_result 步。
 * 这里只读这一步的正文，不改发送、不改工具协议、不改事件、也不改结果框里的正文。
 *
 * <p>工具返回故意不带文档 id，现网文档页又需要知识库 id，因此这里只展开片段，不新开文档路由。
 * 认不出检索结构（未检索、无命中文案、失败说明、其它工具 JSON）时返回空列表，调用方不要画空引用区。
 *
 * <p>不依赖 Vue、不依赖 `@/` 别名，可在 node 里直接测。
 */

/** 一条已落在工具结果里的出处。分数只用于去重取舍与按篇排序，不进展示 */
export interface RetrievalCitation {
  title: string
  content: string
  source: string
  /** 检索综合分（工具结果里的 score）；没有分数时为空 */
  score?: number
}

/** 引用区一条可点来源。label 必须能看出是哪一篇（或哪一段），不能只是序号 */
export interface WorkbenchCitationView {
  key: string
  label: string
  title: string
  content: string
  source: string
  /** 同一段重复命中时用来留高分那次；仅排序取舍，不渲染 */
  score?: number
}

/** 展开后某一段。短名取片段开头，文档名放在组标题上，避免每段再重复一遍篇名 */
export interface CitationSnippetView {
  key: string
  label: string
  title: string
  content: string
  source: string
  /** 排序用的检索分；不渲染 */
  score?: number
}

/**
 * 同一篇文档下的片段。
 *
 * <p>归并只看展示字段：优先 title，没有标题才用 source。工具结果里没有文档 id，
 * 这里不补、也不据此改检索返回。
 */
export interface CitationDocumentGroup {
  key: string
  name: string
  /** 该篇去重后的全部片段，按展示排序；不做按篇截断 */
  snippets: CitationSnippetView[]
}

/**
 * 默认收起时的一行，加上点开后的文档组。
 *
 * <p>一篇时摘要就是文档名（多段再附段数）；多篇时写「引用 · N 篇文档」，N 是文档数不是片段数。
 */
export interface CitationBoard {
  summary: string
  documents: CitationDocumentGroup[]
}

/** 投影步里引用区需要的字段。与 ProcessStep 同形，避免把 trialMessages 拉进来 */
interface CitationStep {
  type?: string
  title?: string
  text?: string
  state?: string
}

/** 展开嵌套检索结构的层数上限：多包一层引号或 content 里再套一层就够了 */
const MAX_DEPTH = 2

/**
 * 从一位参与者的工具步里收集引用。
 *
 * <p>只读 type=tool_result 的正文。紧挨着的失败调用（state=error 或标题带「失败」）整段跳过，
 * 避免把失败说明收成出处。
 *
 * <p>同一张卡上模型可能多次调用检索，各次命中会叠在一起。这里按「文档名 + 片段正文」
 * 合并同一段：有分数时留分更高的那次，没有分数时保留先落进来的那次（顺序即落到卡上的顺序）。
 *
 * @param steps 该参与者卡片上已有的过程步；直播和回放都是这份投影
 * @return 可点来源；没有稳定出处时为空数组
 */
export function citationsFromToolSteps(steps: CitationStep[] | undefined): WorkbenchCitationView[] {
  const list = Array.isArray(steps) ? steps : []
  const raw: RetrievalCitation[] = []
  for (let index = 0; index < list.length; index += 1) {
    const step = list[index]
    if (!step || step.type !== 'tool_result') {
      continue
    }
    const prev = list[index - 1]
    if (isFailedCall(prev)) {
      continue
    }
    raw.push(...collectRetrievalCitations(step.text || ''))
  }
  const unique = mergeSameSnippet(raw)
  const nameCount = new Map<string, number>()
  for (const hit of unique) {
    const name = documentNameOf(hit)
    if (!name) {
      continue
    }
    nameCount.set(name, (nameCount.get(name) || 0) + 1)
  }
  return unique.map((hit, index) => {
    const name = documentNameOf(hit)
    // 同名多段时带上片段开头，避免两条都只剩同一个标题
    const label = name
      ? ((nameCount.get(name) || 0) > 1 && hit.content ? `${name} · ${clipLabel(hit.content, 18)}` : name)
      : clipLabel(hit.content, 28)
    return {
      key: `cite-${index}-${label.length}-${hit.content.length}`,
      label,
      title: hit.title,
      content: hit.content,
      source: hit.source,
      score: hit.score
    }
  }).filter((item) => item.label.trim().length > 0)
}

/**
 * 合并同一张卡上多次检索叠出来的重复段。
 *
 * <p>身份 = 文档名（title，缺省回 source）+ 片段正文。正文相同但文档名不同视为不同段，
 * 不跨文档误合并。保留原位（先出现的位置决定顺序），以便摘要与展开顺序稳定。
 *
 * <p>取舍：两条都有数字分时留分高的；任一条缺分则保留先落进来的那次，
 * 不拿 0 分去和真实分数比大小，避免把低分那次的空分当成更高。
 *
 * @param raw 按工具结果先后顺序拼起来的命中
 * @return 每段只留一条
 */
function mergeSameSnippet(raw: RetrievalCitation[]): RetrievalCitation[] {
  const order: string[] = []
  const kept = new Map<string, RetrievalCitation>()
  for (const hit of raw) {
    const identity = snippetIdentity(hit)
    if (!identity) {
      continue
    }
    const previous = kept.get(identity)
    if (!previous) {
      kept.set(identity, hit)
      order.push(identity)
      continue
    }
    // 只有双方都有分数时才比大小；缺分一侧保留先到的那次
    if (typeof previous.score === 'number' && typeof hit.score === 'number' && hit.score > previous.score) {
      kept.set(identity, hit)
    }
  }
  return order.map((identity) => kept.get(identity) as RetrievalCitation)
}

/**
 * 一段的身份：文档名 + 片段正文。
 *
 * <p>不新造协议字段：工具结果里没有片段 id，文档名与正文就是现在最稳的组合。
 * 正文为空时只靠文档名会误合并，这种情况要求文档名与来源同时一致才算同一段。
 *
 * @param hit 一条命中
 * @return 身份串；没有足够信息时为空
 */
function snippetIdentity(hit: { title?: string; source?: string; content?: string; label?: string }): string {
  const name = documentNameOf(hit)
  const content = (hit.content || '').trim()
  if (content) {
    return `${name}\n${content}`
  }
  const source = (hit.source || '').trim()
  if (!name && !source) {
    return ''
  }
  return `${name}\n${source}\n(no-content)`
}

/**
 * 把已经抽出的出处排成「默认一行 + 按文档分组」。
 *
 * <p>不重新解析工具结果。文档名优先用 title，没有标题才用 source；
 * 两者都空时用这条自己的短名，避免把无关片段并成一篇。
 * 同一篇下的短名取片段开头，不再把文档名重复写进每一段。
 *
 * <p>去重已在 {@link citationsFromToolSteps} 完成，这里只排序与分组，不再做按篇截断：
 * 该篇去重后有几段就列几段，摘要的段数与展开后的段数始终一致。
 *
 * @param items {@link citationsFromToolSteps} 的结果
 * @return 摘要与分组；空列表时摘要为空、文档为空
 */
export function arrangeCitations(items: WorkbenchCitationView[] | undefined): CitationBoard {
  const list = dedupeSameSnippet(Array.isArray(items) ? items : [])
  const order: string[] = []
  const groups = new Map<string, CitationDocumentGroup>()
  for (const item of list) {
    const name = documentNameOf(item)
    if (!name) {
      continue
    }
    let group = groups.get(name)
    if (!group) {
      group = { key: `doc-${order.length}-${name.length}`, name, snippets: [] }
      groups.set(name, group)
      order.push(name)
    }
    group.snippets.push(snippetView(item, name, group.snippets.length))
  }
  const documents = order.map((name) => rankSnippets(groups.get(name) as CitationDocumentGroup))
  return { summary: summaryLine(documents), documents }
}

/**
 * 展示层再兜一次同段去重。
 *
 * <p>正常链路在 {@link citationsFromToolSteps} 已经合并过；这里再按同一身份收一遍，
 * 保证无论调用方传的是原始列表还是已合并列表，同一篇同一段都不会画两次。
 * 取舍规则与抽取阶段一致：双方都有分留高分，否则保留先出现的。
 *
 * @param items 出处列表
 * @return 每段只留一条，顺序按首次出现
 */
function dedupeSameSnippet(items: WorkbenchCitationView[]): WorkbenchCitationView[] {
  const order: string[] = []
  const kept = new Map<string, WorkbenchCitationView>()
  for (const item of items) {
    const identity = snippetIdentity(item)
    if (!identity) {
      continue
    }
    const previous = kept.get(identity)
    if (!previous) {
      kept.set(identity, item)
      order.push(identity)
      continue
    }
    if (typeof previous.score === 'number' && typeof item.score === 'number' && item.score > previous.score) {
      kept.set(identity, item)
    }
  }
  return order.map((identity) => kept.get(identity) as WorkbenchCitationView)
}

/**
 * 单篇内的展示顺序：有分从高到低，缺分或同分按落卡先后，不做截断。
 *
 * <p>分数不是每一条都有（老会话 / 无重排时可能只有 0 或序列化时丢了），
 * 缺分按 0 参与排序，同分时靠原始下标保持稳定，不来回抖动。
 * 该篇去重后有多少段就保留多少段。
 *
 * @param group 已按落卡顺序收好片段的文档组
 * @return 排好序的同一文档组（snippets 数量不变）
 */
function rankSnippets(group: CitationDocumentGroup): CitationDocumentGroup {
  if (group.snippets.length <= 1) {
    return group
  }
  group.snippets = group.snippets
    .map((snippet, index) => ({ snippet, index }))
    .sort((left, right) => {
      const diff = scoreOf(right.snippet) - scoreOf(left.snippet)
      // 分数相同（含都没有分）时按原来的顺序，避免同分随机换位
      return diff !== 0 ? diff : left.index - right.index
    })
    .map((row) => row.snippet)
  return group
}

/**
 * 一段的分数。缺失时按 0 处理，仅用于排序，不参与展示。
 *
 * @param snippet 一段
 * @return 数字分；没有分数时为 0
 */
function scoreOf(snippet: CitationSnippetView): number {
  return typeof snippet.score === 'number' && Number.isFinite(snippet.score) ? snippet.score : 0
}

/**
 * 一篇的名字。title 与 source 相同或只有其一都归到这个名字下。
 *
 * <p>只读展示字段，所以抽出的命中与视图都能传进来；不新造文档 id。
 *
 * @param item 一条出处
 * @return 文档名；整条没有可读名字时为空
 */
function documentNameOf(item: { title?: string; source?: string; label?: string }): string {
  const title = (item.title || '').trim()
  if (title) {
    return title
  }
  const source = (item.source || '').trim()
  if (source) {
    return source
  }
  return (item.label || '').trim()
}

/**
 * 组内一段。短名用片段开头，让同一篇下的各段能分开。
 *
 * @param item   一条出处
 * @param docName 所属文档名
 * @param index  组内序号，只用来区分键
 * @return 可点的一段
 */
function snippetView(item: WorkbenchCitationView, docName: string, index: number): CitationSnippetView {
  const content = (item.content || '').trim()
  const source = (item.source || '').trim()
  const fromContent = clipLabel(content, 28)
  let label = fromContent
  if (!label && source && source !== docName) {
    label = source
  }
  if (!label) {
    label = '这段没有留下原文'
  }
  return {
    key: `${item.key}:snip:${index}`,
    label,
    title: item.title,
    content,
    source,
    score: typeof item.score === 'number' && Number.isFinite(item.score) ? item.score : undefined
  }
}

/**
 * 默认那一行。一篇写文档名，多段再附段数；多篇只数文档，不数片段。
 *
 * <p>一篇的段数就是该篇去重后列出的段数（不再有隐藏段），与展开后看到的条数一致。
 *
 * @param documents 已排好序、分好组的文档
 * @return 摘要；没有文档时为空串
 */
function summaryLine(documents: CitationDocumentGroup[]): string {
  if (!documents.length) {
    return ''
  }
  if (documents.length === 1) {
    const doc = documents[0]
    if (doc.snippets.length > 1) {
      return `${doc.name} · ${doc.snippets.length} 段`
    }
    return doc.name
  }
  return `引用 · ${documents.length} 篇文档`
}

/**
 * 紧挨着这条结果的调用是不是失败。失败结果不是出处。
 *
 * @param step 前一步；没有前一步时不算失败
 * @return true 表示跳过紧随其后的工具结果
 */
function isFailedCall(step: CitationStep | undefined): boolean {
  if (!step || step.type !== 'tool_call') {
    return false
  }
  return step.state === 'error' || (step.title || '').includes('失败')
}

/**
 * 把一段工具结果收成出处列表。
 *
 * <p>整段 JSON、落库多包的一层引号、正文里嵌着的检索数组，以及外层数组被截断后
 * 已经写全的对象，都认。没有 score / hitBy 的 JSON 不当成检索，以免把别的工具结果收进来。
 *
 * @param raw 工具结果正文，可空
 * @return 出处；认不出时为空数组
 */
export function collectRetrievalCitations(raw: string): RetrievalCitation[] {
  return walkCitations(raw || '', 0)
}

/**
 * 一层展开。depth 用来拦住 content 里再套 content。
 *
 * @param raw   这一层的原文
 * @param depth 已经展开过的层数
 * @return 这一层能认出的出处
 */
function walkCitations(raw: string, depth: number): RetrievalCitation[] {
  if (depth > MAX_DEPTH) {
    return []
  }
  const peeled = stripStorageTail((raw || '').trim())
  if (!peeled) {
    return []
  }
  const text = unwrapWholeFence(peeled)
  const decoded = unwrapEncoded(text)
  if (decoded && depth < MAX_DEPTH) {
    const inner = walkCitations(decoded, depth + 1)
    if (inner.length) {
      return inner
    }
  }
  const parsed = tryParseJson(text)
  if (parsed !== undefined) {
    const hits = hitsFromParsed(parsed, depth)
    // 空数组是「检索了但没有条目」，不要再往下猜
    if (hits) {
      return hits
    }
  }
  if (!hasRetrievalMark(text)) {
    return []
  }
  const loose = scanObjects(text, depth)
  if (loose.length) {
    return loose
  }
  return scanEmbedded(text, depth)
}

/**
 * 已解析的 JSON 收成出处。不是检索结构时返回 null，调用方保留继续找嵌套的机会。
 *
 * @param parsed 已解析的值
 * @param depth  已经展开过的层数
 * @return 出处列表；结构对不上为 null
 */
function hitsFromParsed(parsed: unknown, depth: number): RetrievalCitation[] | null {
  if (Array.isArray(parsed)) {
    if (!parsed.length) {
      return []
    }
    const hits: RetrievalCitation[] = []
    for (const item of parsed) {
      if (!item || typeof item !== 'object' || Array.isArray(item)) {
        return null
      }
      const obj = item as Record<string, unknown>
      if (!isRetrievalRow(obj)) {
        return null
      }
      hits.push(...rowHits(obj, depth))
    }
    return hits
  }
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    for (const key of ['hits', 'results', 'documents', 'chunks']) {
      if (Array.isArray(obj[key])) {
        const inner = hitsFromParsed(obj[key], depth)
        if (inner && inner.length) {
          return inner
        }
      }
    }
    if (isRetrievalRow(obj)) {
      return rowHits(obj, depth)
    }
  }
  return null
}

/**
 * 一条检索对象。正文若自己又是检索 JSON，展开内层，不再把那段 JSON 当片段。
 *
 * @param obj   一条命中
 * @param depth 已经展开过的层数
 * @return 出处；没有可读标题或正文时为空
 */
function rowHits(obj: Record<string, unknown>, depth: number): RetrievalCitation[] {
  const title = textOf(obj.title) || textOf(obj.docTitle) || textOf(obj.document)
  const content = textOf(obj.content) || textOf(obj.text) || textOf(obj.chunk)
  const source = textOf(obj.source)
  if (content && depth < MAX_DEPTH) {
    const inner = walkCitations(content, depth + 1)
    if (inner.length) {
      return inner
    }
  }
  if (!title && !content) {
    return []
  }
  return [{ title, content, source, score: scoreOfField(obj) }]
}

/**
 * 命中对象的综合分。只认工具真实返回的 score；非数字或缺失返回 undefined。
 *
 * <p>解析失败或空分不能当成 0 参与「留高分」判断，否则会把有分的段换掉。
 *
 * @param obj 一条命中
 * @return 数字分；不是有限数字时为空
 */
function scoreOfField(obj: Record<string, unknown>): number | undefined {
  const raw = obj.score
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return raw
  }
  if (typeof raw === 'string' && raw.trim()) {
    const parsed = Number(raw)
    if (Number.isFinite(parsed)) {
      return parsed
    }
  }
  return undefined
}

/**
 * 这条对象带检索标记。只认 score / hitBy，不因单独的 source 或 title 就当出处。
 *
 * @param obj 对象
 * @return true 表示是检索命中
 */
function isRetrievalRow(obj: Record<string, unknown>): boolean {
  return 'score' in obj || 'hitBy' in obj || 'hit_by' in obj
}

/**
 * 文本里是否出现检索标记。没有标记时不扫描，避免把普通 JSON 拆开猜。
 *
 * @param text 原文
 * @return true 表示值得继续找对象
 */
function hasRetrievalMark(text: string): boolean {
  return /"hitBy"|"hit_by"|"score"/.test(text)
}

/**
 * 外层数组被截断、整段 parse 失败时，把已经闭合的对象捞出来。
 *
 * @param text  可能切坏的检索 JSON
 * @param depth 已经展开过的层数
 * @return 写全的出处
 */
function scanObjects(text: string, depth: number): RetrievalCitation[] {
  const found: RetrievalCitation[] = []
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== '{') {
      continue
    }
    const end = matchingBracketEnd(text, index)
    if (end <= index) {
      continue
    }
    const parsed = tryParseJson(text.slice(index, end + 1))
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const obj = parsed as Record<string, unknown>
      if (isRetrievalRow(obj)) {
        found.push(...rowHits(obj, depth))
        index = end
      }
    }
  }
  return found
}

/**
 * 调用行和结果粘在同一段时，把中间嵌着的检索 JSON 摘出来。周围的人话丢掉。
 *
 * @param text  工具结果原文
 * @param depth 已经展开过的层数
 * @return 嵌套出处
 */
function scanEmbedded(text: string, depth: number): RetrievalCitation[] {
  const found: RetrievalCitation[] = []
  let index = 0
  while (index < text.length) {
    const ch = text[index]
    if (ch === '[' || ch === '{') {
      const end = matchingBracketEnd(text, index)
      if (end > index) {
        const parsed = tryParseJson(text.slice(index, end + 1))
        if (parsed !== undefined) {
          const hits = hitsFromParsed(parsed, depth)
          if (hits && hits.length) {
            found.push(...hits)
            index = end + 1
            continue
          }
        }
      }
    }
    index += 1
  }
  return found
}

/**
 * 落库正文尾部的截断说明。只认系统标记。
 *
 * @param text 已 trim 的原文
 * @return 去掉尾部标记后的文本
 */
function stripStorageTail(text: string): string {
  return text
    .replace(/\n已截断\s*$/u, '')
    .replace(/\n…\[truncated, totalChars=\d+\]\s*$/u, '')
    .replace(/\n+（输出较长，已截断）\s*$/u, '')
    .trim()
}

/**
 * 整段就是一个 json 围栏时摘掉围栏。
 *
 * @param text 已 trim 的原文
 * @return 围栏内正文；不是整段围栏时原样返回
 */
function unwrapWholeFence(text: string): string {
  const matched = /^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/.exec(text)
  return matched ? matched[1].trim() : text
}

/**
 * 解开「字符串里才是 JSON」：整段是 JSON 字符串，或引号被写成反斜杠。
 *
 * @param text 已 trim 的原文
 * @return 解开一层后的文本；解不开时为 null
 */
function unwrapEncoded(text: string): string | null {
  if (text.startsWith('"')) {
    try {
      const value = JSON.parse(text) as unknown
      if (typeof value === 'string' && value.trim() && value !== text) {
        return value
      }
    } catch {
      // 引号开头但不是合法 JSON 字符串，再试反斜杠形态
    }
  }
  if (text.includes('\\"') && (text.startsWith('[') || text.startsWith('{') || text.startsWith('"'))) {
    try {
      const value = JSON.parse(`"${text}"`) as unknown
      if (typeof value === 'string' && value.trim() && value !== text) {
        return value
      }
    } catch {
      // 转义不完整：保持原文
    }
  }
  return null
}

/**
 * 解析 JSON。失败返回 undefined。只接受对象或数组。
 *
 * @param text 一段文本
 * @return 解析值；不是 JSON 时为 undefined
 */
function tryParseJson(text: string): unknown {
  const trimmed = (text || '').trim()
  if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) {
    return undefined
  }
  try {
    return JSON.parse(trimmed) as unknown
  } catch {
    return undefined
  }
}

/**
 * 取字符串字段。数字、对象不算正文。
 *
 * @param value 字段值
 * @return trim 后的文本；不是字符串时为空
 */
function textOf(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * 列表上的短名。压成一行，超长加省略号。
 *
 * @param text 原文
 * @param max  最长字符数
 * @return 短名
 */
function clipLabel(text: string, max: number): string {
  const one = (text || '').replace(/\s+/g, ' ').trim()
  if (!one) {
    return ''
  }
  if (one.length <= max) {
    return one
  }
  return `${one.slice(0, max)}…`
}

/**
 * 从开括号找到配对的闭括号。字符串内的括号不计。
 *
 * @param text  原文
 * @param start 开括号下标
 * @return 闭括号下标；配不上为 -1
 */
function matchingBracketEnd(text: string, start: number): number {
  const open = text[start]
  const close = open === '[' ? ']' : '}'
  let depth = 0
  let inString = false
  let escape = false
  for (let index = start; index < text.length; index += 1) {
    const ch = text[index]
    if (inString) {
      if (escape) {
        escape = false
      } else if (ch === '\\') {
        escape = true
      } else if (ch === '"') {
        inString = false
      }
      continue
    }
    if (ch === '"') {
      inString = true
      continue
    }
    if (ch === open) {
      depth += 1
    } else if (ch === close) {
      depth -= 1
      if (depth === 0) {
        return index
      }
    }
  }
  return -1
}
