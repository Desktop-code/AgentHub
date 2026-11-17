/**
 * 工作台结果框里给人看的正文。所属域：runtime（前端展示）。
 *
 * <p>知识库检索的真实返回是给模型看的紧凑 JSON（content / title / source / score / hitBy）。
 * 直播 SSE 解析一次后就是这段原文，展示层收成引用文本。回放读的是同一段落库正文，
 * 但落库常多包一层引号、把引号写成反斜杠、或把命中塞进 text 字段，旧判定认不出，
 * 结果框就又铺出 JSON。这里仍只在这一条入口收口，不另写回放清洗，不改落库、不改工具返回。
 *
 * <p>不依赖 Vue、不依赖 `@/` 别名，可在 node 里直接测。
 */

/** 一条可引用的命中：分数、命中方式、内部 id 不进正文 */
interface CitationHit {
  title: string
  content: string
  source: string
  /** 正文已经是展开后的引用 Markdown，formatHit 不再套一层标题 */
  expanded?: boolean
}

/** 展开嵌套检索结构的层数上限：多包一层引号或 text 字段里再套一层就够了，再深当普通正文 */
const MAX_UNWRAP_DEPTH = 2

/**
 * 把结果框原文收成用户可见的正文。
 *
 * <p>直播终态与回放共用这一条：整段、围栏里、人话中嵌着的检索 JSON，
 * 以及落库多包的一层字符串，都换成「标题 + 正文 + 来源」。
 * 认不出是检索结构的文字（包括用户明确要的其它 JSON）保持原样。
 *
 * @param raw 结果框原文，可空
 * @return 可交给现有 Markdown 渲染的文本
 */
export function presentVisibleResult(raw: string): string {
  return presentOnce(raw || '', 0).trim()
}

/**
 * 一层展开。depth 用来拦住 text 字段里再套 text 字段的循环。
 *
 * @param raw   这一层的原文
 * @param depth 已经展开过的层数
 * @return 可见正文；这一层没有检索结构时尽量保持原文
 */
function presentOnce(raw: string, depth: number): string {
  const source = raw || ''
  if (!source.trim() || depth > MAX_UNWRAP_DEPTH) {
    return source.trim()
  }
  // 落库截断标记贴在 JSON 尾巴上时，先剥掉再认结构；标记本身不是给用户看的正文
  const peeled = stripStorageTail(source.trim())
  const unfenced = unwrapWholeFence(peeled)
  if (unfenced !== peeled) {
    const cited = citePayload(unfenced, depth)
    if (cited != null) {
      return cited.trim()
    }
  }
  // 多包的一层引号 / 反斜杠转义：只有展开后确实是检索结构才替换，其它 JSON 保持原样
  const decoded = decodeRetrievalText(peeled, depth)
  if (decoded != null) {
    return decoded
  }
  const whole = citePayload(peeled, depth)
  if (whole != null) {
    return whole.trim()
  }
  const embedded = replaceEmbedded(peeled, depth)
  if (embedded.trim() !== peeled) {
    return embedded.trim()
  }
  // 截断把数组切坏、整段 parse 失败时，仍把已经写全的标题/正文/来源捞出来
  const loose = looseRetrieval(peeled)
  if (loose != null) {
    return loose
  }
  return source.trim()
}

/**
 * 整段就是一个 json 围栏时摘掉围栏，方便整段判定。
 *
 * @param text 已 trim 的原文
 * @return 围栏内正文；不是整段围栏时原样返回
 */
function unwrapWholeFence(text: string): string {
  const matched = /^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/.exec(text)
  return matched ? matched[1].trim() : text
}

/**
 * 这一段若是检索结构，返回替换后的引用文本；不是则返回 null（调用方保留原文）。
 *
 * <p>空串表示「这是内部结构，但没有可给人看的段落」，结果框不铺 JSON。
 *
 * @param text  可能是 JSON 的一段
 * @param depth 已经展开过的层数
 * @return 引用文本、空串，或 null
 */
function citePayload(text: string, depth: number): string | null {
  const parsed = tryParseJson(text)
  if (parsed === undefined) {
    return null
  }
  const hits = hitsToMarkdown(parsed, depth)
  if (hits != null) {
    return hits
  }
  if (!looksLikeRetrievalPayload(parsed)) {
    return null
  }
  // 结构对得上，但条目里没有标题/正文：不要把对象再 stringify 回去
  return ''
}

/**
 * 解析 JSON。失败返回 undefined，与「解析出 null」区分开。
 * 只接受对象或数组：多包的一层引号由 unwrapEncoded 解开，这里不把普通字符串当结构。
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
 * 检索命中收成引用 Markdown。不是这种结构时返回 null。
 *
 * @param parsed 已解析的 JSON
 * @param depth  已经展开过的层数
 * @return 引用文本；空数组或认不出时为 null
 */
function hitsToMarkdown(parsed: unknown, depth: number): string | null {
  if (Array.isArray(parsed)) {
    const notes: string[] = []
    const hits: CitationHit[] = []
    for (const item of parsed) {
      if (typeof item === 'string') {
        const note = item.trim()
        if (note) {
          notes.push(note)
        }
        continue
      }
      const hit = asHit(item, depth)
      if (!hit) {
        return null
      }
      hits.push(hit)
    }
    if (!hits.length) {
      return null
    }
    const body = hits.map(formatHit).filter((line) => line.length > 0).join('\n\n')
    return notes.length ? `${body}\n\n${notes.join('\n')}` : body
  }
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    for (const key of ['hits', 'results', 'documents', 'chunks']) {
      if (Array.isArray(obj[key])) {
        return hitsToMarkdown(obj[key], depth)
      }
    }
    const one = asHit(parsed, depth)
    if (one && (hasRetrievalMark(obj) || one.expanded)) {
      return formatHit(one)
    }
  }
  return null
}

/**
 * 从一条对象里取标题、正文、来源。分数和命中方式丢掉。
 *
 * <p>正文若自己又是一段检索 JSON（落库把命中塞进 text / content 字符串），
 * 展开成引用，不再把那段 JSON 当成人话铺出来。
 *
 * @param row   可能是一条命中
 * @param depth 已经展开过的层数
 * @return 命中；没有可读标题或正文时为 null
 */
function asHit(row: unknown, depth: number): CitationHit | null {
  if (!row || typeof row !== 'object' || Array.isArray(row)) {
    return null
  }
  const obj = row as Record<string, unknown>
  if (typeof obj.route_key === 'string' || typeof obj.routeKey === 'string' || typeof obj.sub_query === 'string') {
    return null
  }
  const title = textOf(obj.title) || textOf(obj.docTitle) || textOf(obj.document)
  const content = textOf(obj.content) || textOf(obj.text) || textOf(obj.chunk)
  const source = textOf(obj.source)
  if (content && depth < MAX_UNWRAP_DEPTH) {
    const inner = citePayload(content, depth + 1)
    if (inner != null) {
      return { title: '', content: inner, source: '', expanded: true }
    }
  }
  if (!title && !content) {
    return null
  }
  return { title, content, source }
}

/**
 * 这条对象带检索字段，而不是随便一个带 title 的业务 JSON。
 *
 * @param obj 对象
 * @return true 表示按检索命中展示
 */
function hasRetrievalMark(obj: Record<string, unknown>): boolean {
  return 'source' in obj || 'score' in obj || 'hitBy' in obj || 'hit_by' in obj || 'chunkId' in obj || 'docId' in obj
}

/**
 * 值里面是否出现检索字段名。用来拦住「不是命中列表、但仍是检索结构」的对象。
 *
 * @param parsed 已解析 JSON
 * @return true 表示不该把原文铺给用户
 */
function looksLikeRetrievalPayload(parsed: unknown): boolean {
  let blob = ''
  try {
    blob = JSON.stringify(parsed)
  } catch {
    return false
  }
  return /"(?:content|hitBy|hit_by|score|source|chunkId|docId|documents|hits|chunks)"/.test(blob)
}

/**
 * 一条命中写成引用段：标题加粗，正文原样，来源单独一行。不写分数。
 *
 * @param hit 命中
 * @return Markdown；空命中为空串
 */
function formatHit(hit: CitationHit): string {
  // 内层已经收成引用：外层不要再套标题，否则同一条命中会写两遍
  if (hit.expanded) {
    return hit.content.trim()
  }
  const parts: string[] = []
  if (hit.title) {
    parts.push(`**${hit.title}**`)
  }
  if (hit.content) {
    parts.push(hit.content)
  }
  if (hit.source) {
    parts.push(`来源：${hit.source}`)
  }
  return parts.join('\n\n')
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
 * 把正文里嵌着的检索 JSON 换成引用文本，周围的人话保留。
 *
 * @param source 原文
 * @param depth  已经展开过的层数
 * @return 替换后的文本
 */
function replaceEmbedded(source: string, depth: number): string {
  let out = ''
  let index = 0
  while (index < source.length) {
    const ch = source[index]
    if (ch === '[' || ch === '{') {
      const end = matchingBracketEnd(source, index)
      if (end > index) {
        const slice = source.slice(index, end + 1)
        const cited = citePayload(slice, depth)
        if (cited != null) {
          out += cited
          index = end + 1
          continue
        }
        // 括号配对了但不是合法 JSON（尾逗号、截断后的残段）：能捞出命中就替换这一段
        const loose = looseRetrieval(slice)
        if (loose != null) {
          out += loose
          index = end + 1
          continue
        }
      }
    }
    out += ch
    index += 1
  }
  return out
}

/**
 * 落库正文尾部的截断说明。只认系统标记，避免把用户自己写的「已截断」三个字删掉。
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
 * 把多包的一层 JSON 字符串解成检索引用。解出来不是检索结构时返回 null，原文不动。
 *
 * @param text  已 trim 的原文
 * @param depth 已经展开过的层数
 * @return 引用文本；不是这种编码时为 null
 */
function decodeRetrievalText(text: string, depth: number): string | null {
  if (depth >= MAX_UNWRAP_DEPTH) {
    return null
  }
  const inner = unwrapEncoded(text)
  if (inner == null) {
    return null
  }
  const cited = citePayload(inner.trim(), depth + 1)
  if (cited != null) {
    return cited.trim()
  }
  const embedded = replaceEmbedded(inner, depth + 1)
  if (embedded.trim() !== inner.trim()) {
    return embedded.trim()
  }
  const loose = looseRetrieval(inner.trim())
  return loose
}

/**
 * 认出「字符串里才是 JSON」的两种落库形态：整段是 JSON 字符串，或引号被写成反斜杠。
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
  // 落库把 " 存成 \"，整段不再是合法 JSON。包回一层引号才能还原直播解析后的原文
  if (text.includes('\\"') && (text.startsWith('[') || text.startsWith('{') || text.startsWith('"'))) {
    try {
      const value = JSON.parse(`"${text}"`) as unknown
      if (typeof value === 'string' && value.trim() && value !== text) {
        return value
      }
    } catch {
      // 转义不完整：保持原文，不当成检索结构硬拆
    }
  }
  return null
}

/**
 * 半截检索 JSON 里已经写全的标题、正文、来源。没有 score / hitBy 这类检索标记时不猜，
 * 以免把带 title 的普通业务 JSON 收成引用。
 *
 * @param text 可能是切坏的检索 JSON
 * @return 引用文本；认不出时为 null
 */
function looseRetrieval(text: string): string | null {
  const trimmed = (text || '').trim()
  if (!trimmed || (trimmed[0] !== '{' && trimmed[0] !== '[')) {
    return null
  }
  if (!/"hitBy"|"hit_by"|"score"/.test(trimmed)) {
    return null
  }
  if (!/"content"|"title"|"text"/.test(trimmed)) {
    return null
  }
  const hit: CitationHit = {
    title: jsonStringField(trimmed, 'title') || jsonStringField(trimmed, 'docTitle'),
    content: jsonStringField(trimmed, 'content') || jsonStringField(trimmed, 'text'),
    source: jsonStringField(trimmed, 'source')
  }
  if (!hit.title && !hit.content) {
    return null
  }
  // 字段本身又是检索 JSON 时继续展开，避免半截修复后又把 JSON 串写进正文
  if (hit.content) {
    const inner = citePayload(hit.content, 1)
    if (inner != null) {
      return inner.trim()
    }
  }
  return formatHit(hit).trim() || null
}

/**
 * 取一个 JSON 字符串字段的已闭合值。没闭合（截在字符串中间）时不要捞半句，以免拼出残句冒充正文。
 *
 * @param text 原文
 * @param key  字段名
 * @return 反转义后的值；没有完整字段时为空串
 */
function jsonStringField(text: string, key: string): string {
  const matched = new RegExp(`"${key}"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)"`).exec(text)
  if (!matched) {
    return ''
  }
  return unescapeJsonString(matched[1])
}

/**
 * 还原 JSON 字符串里的常见转义。只处理字段值，不重新解析整段。
 *
 * @param value 正则抓到的字段原文
 * @return 给人看的文本
 */
function unescapeJsonString(value: string): string {
  return value.replace(/\\(["\\/bfnrt]|u[0-9a-fA-F]{4})/g, (_full, seq: string) => {
    switch (seq[0]) {
      case '"':
        return '"'
      case '\\':
        return '\\'
      case '/':
        return '/'
      case 'n':
        return '\n'
      case 'r':
        return '\r'
      case 't':
        return '\t'
      case 'b':
        return '\b'
      case 'f':
        return '\f'
      case 'u':
        return String.fromCharCode(Number.parseInt(seq.slice(1), 16))
      default:
        return _full
    }
  })
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
