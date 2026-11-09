/**
 * 助手回复的轻量 Markdown。所属域：runtime 展示。
 * 先抽出 HTML/围栏里的表转成安全表格，再转义其余正文，最后排标题、引用、分隔线、列表、代码、
 * 链接、加粗/斜体、GFM 管道表。
 * 普通代码围栏内仍保持原文；不执行模型带来的脚本标签。
 * 流式过程用 {@link renderStreamingMarkdown}（安全前缀 + 缓冲尾）；流结束后改回本函数终渲。
 *
 * 调用方：ChatPanel（Agent 对话）、MaRunFlow（多智能体运行）、ProcessTimeline、WorkflowTrialDrawer。
 * 改这里等于同时改这几个页面的正文口径，保持同一套语法与样式约定。
 *
 * 流式热点（对话区逐帧重渲）请走 {@link renderMarkdownMemo}：按渲染位置缓存结果，
 * 只有正在增长的那一块会重算，算法与上面两个入口完全一致。
 */

type ColumnAlign = 'left' | 'center' | 'right'

/** 流式表尚未凑齐完整行时的占位，避免刷出标签原文。 */
const TABLE_PENDING = '<p class="md-table-pending">表格生成中…</p>'

/**
 * 转义后再排版。空串原样返回。
 * 支持：h1~h6、`>` 引用、`---` 分隔线、有序/无序列表、围栏代码、
 * GFM 管道表、裸 `<table>`、`[文本](链接)`、`**加粗**`、`*斜体*`、`` `行内代码` ``。
 *
 * @param source Markdown 原文
 * @return 可直接 v-html 的 HTML；原文里的 HTML 标签已被转义，只有白名单链接会还原成 `<a>`
 */
export function renderSimpleMarkdown(source: string): string {
  const normalized = normalizePipes(source || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const prepared = preprocessTables(normalized)
  return renderBlocks(escapeHtml(prepared.text).split('\n'), prepared.slots)
}

/**
 * 逐行扫块级语法并输出 HTML。
 *
 * <p>引用块会剥掉一层 `&gt;` 后递归回本函数，所以嵌套引用、引用内列表/代码块都能正确成形。
 * 传入的是**已转义**的行，故所有标记判断都基于实体形式（`>` 写作 `&gt;`）。
 *
 * @param lines 已转义源码行
 * @param slots 预处理阶段抽出的表格 HTML 槽位，按 `@@TABLEi@@` 占位回填
 * @return 块级 HTML 片段
 */
function renderBlocks(lines: string[], slots: string[]): string {
  const html: string[] = []
  let list: 'ul' | 'ol' | null = null
  let paragraph: string[] = []
  let fence: string[] | null = null
  let fenceLang = ''

  const closeList = () => {
    if (!list) {
      return
    }
    html.push(list === 'ul' ? '</ul>' : '</ol>')
    list = null
  }

  const flushParagraph = () => {
    if (!paragraph.length) {
      return
    }
    html.push(`<p>${paragraph.map(inline).join('<br>')}</p>`)
    paragraph = []
  }

  let index = 0
  while (index < lines.length) {
    const line = lines[index]
    if (fence) {
      if (line.startsWith('```')) {
        html.push(renderFence(fence.join('\n'), fenceLang))
        fence = null
        fenceLang = ''
      } else {
        fence.push(line)
      }
      index += 1
      continue
    }
    if (line.startsWith('```')) {
      flushParagraph()
      closeList()
      fence = []
      fenceLang = line.slice(3).trim().toLowerCase()
      index += 1
      continue
    }
    const slot = /^@@TABLE(\d+)@@$/.exec(line.trim())
    if (slot) {
      flushParagraph()
      closeList()
      const piece = slots[Number(slot[1])]
      if (piece) {
        html.push(piece)
      }
      index += 1
      continue
    }
    // 标题：1~6 级都认，避免 `####` 原样漏到正文
    const heading = /^(#{1,6}) (.+)$/.exec(line)
    if (heading) {
      flushParagraph()
      closeList()
      const level = heading[1].length
      html.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      index += 1
      continue
    }
    // 引用块：连续 `>` 行整体进 blockquote；剥一层标记后递归，嵌套/列表/代码块自然成立
    if (/^\s{0,3}&gt;/.test(line)) {
      flushParagraph()
      closeList()
      const inner: string[] = []
      while (index < lines.length) {
        const quoted = /^\s{0,3}&gt;\s?(.*)$/.exec(lines[index])
        if (!quoted) {
          break
        }
        inner.push(quoted[1])
        index += 1
      }
      html.push(`<blockquote>${renderBlocks(inner, slots)}</blockquote>`)
      continue
    }
    // 分隔线：--- / *** / ___（允许字符间留空格），必须在表格与列表之前判断
    if (/^\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$/.test(line)) {
      flushParagraph()
      closeList()
      html.push('<hr>')
      index += 1
      continue
    }
    const table = takeTable(lines, index)
    if (table) {
      flushParagraph()
      closeList()
      html.push(table.html)
      index = table.next
      continue
    }
    const unordered = /^[-*] (.+)$/.exec(line)
    const ordered = /^\d+\. (.+)$/.exec(line)
    if (unordered) {
      flushParagraph()
      if (list !== 'ul') {
        closeList()
        html.push('<ul>')
        list = 'ul'
      }
      html.push(`<li>${inline(unordered[1])}</li>`)
      index += 1
      continue
    }
    if (ordered) {
      flushParagraph()
      if (list !== 'ol') {
        closeList()
        html.push('<ol>')
        list = 'ol'
      }
      html.push(`<li>${inline(ordered[1])}</li>`)
      index += 1
      continue
    }
    if (!line.trim()) {
      flushParagraph()
      closeList()
      index += 1
      continue
    }
    closeList()
    paragraph.push(line)
    index += 1
  }
  flushParagraph()
  closeList()
  if (fence && fence.length) {
    html.push(renderFence(fence.join('\n'), fenceLang))
  }
  return html.join('')
}

/**
 * 流式渲染：安全前缀立刻走 Markdown；表用「完整行渐进成表」，半行/半标签留缓冲不展示。
 * 未闭合代码围栏按代码块，不把围栏内当表。流式结束后请改用 {@link renderSimpleMarkdown}。
 */
export function renderStreamingMarkdown(source: string): string {
  const normalized = normalizePipes(source || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  if (!normalized) {
    return ''
  }
  const cut = cutStreamingTail(normalized)
  const parts: string[] = []
  if (cut.safePrefix) {
    parts.push(renderSimpleMarkdown(cut.safePrefix))
  }
  if (cut.progressiveHtml) {
    parts.push(cut.progressiveHtml)
  }
  return parts.join('')
}

/**
 * 渲染槽缓存上限。超出后按插入顺序淘汰最早的一条，被淘汰的位置下次重算即可。
 */
const SLOT_CACHE_LIMIT = 512

/**
 * 渲染槽缓存：一个「渲染位置」（轮次 + 块 + 模式）只保留最新一份 HTML。
 *
 * <p>为什么需要：流式期每个 delta 都会让整棵消息树重渲，历史块内容一字未变，
 * 却会跟着重跑一遍转义 / 切块 / 正则，轮次越多越贵。命中缓存后历史块直接复用上次结果，
 * 只有真正在增长的那一块会重算。
 *
 * <p>键分两层：
 * - 槽键（slot）：由调用方用「轮次 key + 块序号 + 模式」拼出，保证不会跨位置串用；
 * - 内容签名（signature）：`流式/终态 + 长度 + FNV-1a`，内容一变即不命中；
 *   流式与终态也永不互相命中（两者 HTML 不同，混用会把半截表刷回源码或反过来）。
 */
const slotCache = new Map<string, { signature: string; html: string }>()

/**
 * 内容签名：模式 + 长度 + 32 位 FNV-1a。
 *
 * <p>不直接把全文当 Map 键，是为了避免每次命中都拿整段正文去比较；
 * 长度能挡住绝大多数变化，哈希兜住「长度相同但内容不同」的重放 / 快照恢复场景。
 *
 * @param source    Markdown 原文
 * @param streaming 是否流式渲染
 * @return 可比较的签名串
 */
function markdownSignatureOf(source: string, streaming: boolean): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return `${streaming ? 'S' : 'F'}${source.length}#${(hash >>> 0).toString(36)}`
}

/**
 * 带缓存的 Markdown 渲染。输出与 {@link renderStreamingMarkdown} / {@link renderSimpleMarkdown}
 * 完全一致，只是同位置同内容不再重复解析（算法本身未改）。
 *
 * @param slot      渲染位置键；同一位置必须复用同一个 key，否则缓存形同虚设
 * @param source    Markdown 原文
 * @param streaming 是否流式渲染（流式与终态结果不同，必须分开缓存）
 * @return 可直接 v-html 的 HTML
 */
export function renderMarkdownMemo(slot: string, source: string, streaming = false): string {
  const text = source || ''
  const signature = markdownSignatureOf(text, streaming)
  const hit = slotCache.get(slot)
  if (hit && hit.signature === signature) {
    return hit.html
  }
  const html = streaming ? renderStreamingMarkdown(text) : renderSimpleMarkdown(text)
  // 一个槽只留最新一份：流式中每帧签名都会变，旧值留着只会白占内存
  slotCache.delete(slot)
  slotCache.set(slot, { signature, html })
  if (slotCache.size > SLOT_CACHE_LIMIT) {
    const oldest = slotCache.keys().next()
    if (!oldest.done) {
      slotCache.delete(oldest.value)
    }
  }
  return html
}

/**
 * 清空渲染槽缓存。整段会话被替换（切会话 / 重置时间线）或单测隔离时调用，
 * 避免旧轮次的 HTML 长期占位；不调用也不会渲染错误，只是多占一点内存。
 */
export function clearMarkdownMemo(): void {
  slotCache.clear()
}

/**
 * 切出可终渲前缀与表渐进 HTML；未完成半行/半标签不进入返回值。
 */
function cutStreamingTail(source: string): { safePrefix: string; progressiveHtml: string } {
  const fence = findUnclosedFence(source)
  if (fence) {
    // 围栏未闭合：按代码块展示已到达正文，勿把内部当表解析
    return {
      safePrefix: source.slice(0, fence.openStart),
      progressiveHtml: `<pre><code>${escapeHtml(fence.body)}</code></pre>`
    }
  }

  const htmlOpen = findOpenHtmlTableStart(source)
  if (htmlOpen >= 0) {
    return {
      safePrefix: source.slice(0, htmlOpen),
      progressiveHtml: progressiveHtmlTable(source.slice(htmlOpen))
    }
  }

  const partialTag = findPartialTableTagAtEnd(source)
  if (partialTag >= 0) {
    return {
      safePrefix: source.slice(0, partialTag),
      progressiveHtml: TABLE_PENDING
    }
  }

  const gfm = cutOpenGfmTable(source)
  if (gfm) {
    return gfm
  }

  return { safePrefix: source, progressiveHtml: '' }
}

/**
 * 行扫描找未闭合 ``` 围栏；返回开围栏起点与已到达的正文。
 */
function findUnclosedFence(source: string): { openStart: number; body: string } | null {
  let openStart = -1
  let offset = 0
  const lines = source.split('\n')
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    if (openStart < 0) {
      if (line.startsWith('```')) {
        openStart = offset
        offset += line.length + 1
        continue
      }
    } else if (line.startsWith('```')) {
      openStart = -1
    }
    offset += line.length + 1
  }
  if (openStart < 0) {
    return null
  }
  const openLineEnd = source.indexOf('\n', openStart)
  const bodyStart = openLineEnd < 0 ? source.length : openLineEnd + 1
  return { openStart, body: source.slice(bodyStart) }
}

/**
 * 围栏外最后一个未配对的 `<table`；有则整段进入表缓冲。
 */
function findOpenHtmlTableStart(source: string): number {
  let inFence = false
  let openAt = -1
  let depth = 0
  let offset = 0
  const lines = source.split('\n')
  for (const line of lines) {
    if (line.startsWith('```')) {
      inFence = !inFence
      offset += line.length + 1
      continue
    }
    if (!inFence) {
      const tableTag = /<table\b/gi
      const closeTag = /<\/table>/gi
      let cursor = 0
      while (cursor < line.length) {
        tableTag.lastIndex = cursor
        closeTag.lastIndex = cursor
        const openMatch = tableTag.exec(line)
        const closeMatch = closeTag.exec(line)
        const nextOpen = openMatch ? openMatch.index : -1
        const nextClose = closeMatch ? closeMatch.index : -1
        if (nextOpen < 0 && nextClose < 0) {
          break
        }
        // 先处理更靠前的开/闭标签，嵌套时 depth 归零才清空 openAt
        if (nextClose < 0 || (nextOpen >= 0 && nextOpen <= nextClose)) {
          if (depth === 0) {
            openAt = offset + nextOpen
          }
          depth += 1
          cursor = nextOpen + openMatch![0].length
        } else {
          depth = Math.max(0, depth - 1)
          if (depth === 0) {
            openAt = -1
          }
          cursor = nextClose + closeMatch![0].length
        }
      }
    }
    offset += line.length + 1
  }
  return openAt
}

/**
 * 尾部正在打出的 `<table` 残缺前缀（尚未形成完整开标签）。
 * 只拦截能拼成 table 的路径，避免把普通 `<` 或其它标签残片当成表。
 */
function findPartialTableTagAtEnd(source: string): number {
  const matched = /<[A-Za-z][^>]*$/.exec(source)
  if (!matched || matched.index === undefined) {
    return -1
  }
  const lower = matched[0].toLowerCase()
  if (lower === '<') {
    return -1
  }
  // `<t`…`<table` 或 `<table …`（尚无 >）
  if ('<table'.startsWith(lower) || (lower.startsWith('<table') && !lower.includes('>'))) {
    return matched.index
  }
  return -1
}

/**
 * 尾部进行中的 GFM 管道表：完整行并入表；半行丢弃；表头未齐前可占位。
 */
function cutOpenGfmTable(source: string): { safePrefix: string; progressiveHtml: string } | null {
  const endsWithNewline = source.endsWith('\n')
  const rawLines = source.split('\n')
  let incomplete: string | null = null
  let lines = rawLines
  if (!endsWithNewline) {
    incomplete = rawLines[rawLines.length - 1] ?? ''
    lines = rawLines.slice(0, -1)
  } else if (rawLines.length > 0 && rawLines[rawLines.length - 1] === '') {
    // `split('\n')` 在尾换行时多出空段，去掉后「末行」才是真实完整行
    lines = rawLines.slice(0, -1)
  }

  // 从后往前找「一直延伸到末尾」的管道表
  let tableStart = -1
  let tableEnd = -1
  let index = 0
  while (index < lines.length) {
    const escaped = lines.map((line) => escapeHtml(line))
    const table = takeTable(escaped, index)
    if (table) {
      if (table.next >= lines.length) {
        tableStart = index
        tableEnd = table.next
      }
      index = table.next
      continue
    }
    index += 1
  }

  if (tableStart >= 0) {
    // 已有表头+分隔+完整行：立刻上表；半行不展示
    const safePrefix = lines.slice(0, tableStart).join('\n')
    const tableLines = lines.slice(tableStart, tableEnd).map((line) => escapeHtml(line))
    const built = takeTable(tableLines, 0)
    return {
      safePrefix: safePrefix ? `${safePrefix}\n` : '',
      progressiveHtml: built?.html ?? TABLE_PENDING
    }
  }

  // 末尾完整行像表头，尚无分隔行：缓冲，避免 `| a | b |` 当段落刷出去
  if (lines.length > 0) {
    const last = lines[lines.length - 1]
    const trimmed = last.trim()
    const header = splitTableRow(escapeHtml(last))
    const strongHeader = !!header && header.length > 0 && trimmed.startsWith('|') && trimmed.includes('|', 1)
    const incompleteIsNormal =
      incomplete !== null && incomplete !== '' && !isPotentialGfmTableLine(incomplete)
    // 半行已是普通正文则放行；否则（无半行 / 半行像表）继续等分隔行
    if (strongHeader && !incompleteIsNormal) {
      const safePrefix = lines.slice(0, -1).join('\n')
      return {
        safePrefix: safePrefix ? `${safePrefix}\n` : '',
        progressiveHtml: TABLE_PENDING
      }
    }
  }

  // 只有半行、且像管道表：整段缓冲
  if (lines.length === 0 && incomplete !== null && isPotentialGfmTableLine(incomplete)) {
    return { safePrefix: '', progressiveHtml: TABLE_PENDING }
  }

  return null
}

/** 半行是否像 GFM 表行或分隔行，用于决定是否进入缓冲。 */
function isPotentialGfmTableLine(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed) {
    return false
  }
  if (trimmed.includes('|')) {
    return true
  }
  return /^:?[-–—]{2,}:?$/.test(trimmed)
}

/**
 * 从未闭合的 HTML 表缓冲里抽出完整 `<tr>`，逐行追加成 md-table；无完整行则占位。
 */
function progressiveHtmlTable(buffer: string): string {
  const rows = extractHtmlTableRows(buffer)
  if (!rows.length) {
    return TABLE_PENDING
  }
  return rowsToTableHtml(rows)
}

/** 解析缓冲内全部完整 `<tr>…</tr>`，残缺尾行忽略。 */
function extractHtmlTableRows(tableHtml: string): { cells: string[]; header: boolean }[] {
  const rows: { cells: string[]; header: boolean }[] = []
  const rowRe = /<tr\b[^>]*>([\s\S]*?)<\/tr>/gi
  let rowMatch = rowRe.exec(tableHtml)
  while (rowMatch) {
    const rowHtml = rowMatch[1]
    const cells: string[] = []
    let header = false
    const cellRe = /<(th|td)\b[^>]*>([\s\S]*?)<\/\1>/gi
    let cellMatch = cellRe.exec(rowHtml)
    while (cellMatch) {
      if (cellMatch[1].toLowerCase() === 'th') {
        header = true
      }
      cells.push(htmlCellToText(cellMatch[2]))
      cellMatch = cellRe.exec(rowHtml)
    }
    if (cells.length) {
      rows.push({ cells, header })
    }
    rowMatch = rowRe.exec(tableHtml)
  }
  return rows
}

/** 行集合转成与静态一致的 md-table（首行 th 或首行作表头）。 */
function rowsToTableHtml(rows: { cells: string[]; header: boolean }[]): string {
  const headerRow = rows.find((row) => row.header) ?? rows[0]
  const header = headerRow.cells
  const body = rows.filter((row) => row !== headerRow).map((row) => fitColumns(row.cells, header.length, ''))
  const align = header.map(() => 'left' as ColumnAlign)
  return renderTable(header, align, body)
}

/**
 * 把裸 HTML 表和「整段都是表」的代码围栏先换成占位符，避免转义后页面露出 &lt;table&gt; 字样。
 */
function preprocessTables(source: string): { text: string; slots: string[] } {
  const slots: string[] = []
  let text = source

  text = text.replace(/```([^\n`]*)\n([\s\S]*?)```/g, (full, lang: string, body: string) => {
    const langName = (lang || '').trim().toLowerCase()
    const trimmed = body.trim()
    if (!trimmed) {
      return full
    }
    if (langName === 'html' || langName === 'htm' || /<table\b/i.test(trimmed)) {
      const matched = /<table\b[^>]*>[\s\S]*?<\/table>/i.exec(trimmed)
      if (matched && looksLikeOnlyTable(trimmed, matched[0])) {
        const index = slots.length
        slots.push(htmlTableToSafe(matched[0]))
        return `\n\n@@TABLE${index}@@\n\n`
      }
    }
    if (langName === '' || langName === 'md' || langName === 'markdown' || langName === 'text' || langName === 'gfm') {
      const pipeHtml = pipeBlockToTable(trimmed)
      if (pipeHtml) {
        const index = slots.length
        slots.push(pipeHtml)
        return `\n\n@@TABLE${index}@@\n\n`
      }
    }
    return full
  })

  text = text.replace(/<table\b[^>]*>[\s\S]*?<\/table>/gi, (match) => {
    const index = slots.length
    slots.push(htmlTableToSafe(match))
    return `\n\n@@TABLE${index}@@\n\n`
  })

  return { text, slots }
}

/** 围栏正文去掉表后是否只剩空白；是才整段当表，避免误伤夹了说明的 html 围栏。 */
function looksLikeOnlyTable(block: string, tableHtml: string): boolean {
  return block.replace(tableHtml, '').trim() === ''
}

/** 关闭围栏时再兜一层：预处理漏掉的表仍尽量渲成表，而不是 pre 里一堆标签字。 */
function renderFence(body: string, lang: string): string {
  const trimmed = body.trim()
  if (!trimmed) {
    return '<pre><code></code></pre>'
  }
  // 此处 body 已转义，HTML 表会以 &lt;table 形式出现
  if (lang === 'html' || lang === 'htm' || trimmed.includes('&lt;table')) {
    const decoded = decodeBasicEntities(trimmed)
    const matched = /<table\b[^>]*>[\s\S]*?<\/table>/i.exec(decoded)
    if (matched && looksLikeOnlyTable(decoded, matched[0])) {
      return htmlTableToSafe(matched[0])
    }
  }
  const pipeHtml = pipeBlockToTable(decodeBasicEntities(trimmed))
  if (pipeHtml && (lang === '' || lang === 'md' || lang === 'markdown' || lang === 'text' || lang === 'gfm')) {
    return pipeHtml
  }
  return `<pre><code>${trimmed}</code></pre>`
}

/** 整段若是 GFM 管道表则渲成表 HTML，否则 null。 */
function pipeBlockToTable(block: string): string | null {
  const lines = escapeHtml(normalizePipes(block)).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  let start = 0
  while (start < lines.length && !lines[start].trim()) {
    start += 1
  }
  const table = takeTable(lines, start)
  if (!table) {
    return null
  }
  const trailing = lines.slice(table.next)
  if (!trailing.every((line) => !line.trim())) {
    return null
  }
  return table.html
}

/**
 * 只保留表结构，单元格去标签后再转义，统一走 md-table 样式。
 * 不保留 onclick 等属性，避免把模型 HTML 当脚本执行。
 */
function htmlTableToSafe(tableHtml: string): string {
  const rows = extractHtmlTableRows(tableHtml)
  if (!rows.length) {
    return `<pre><code>${escapeHtml(tableHtml)}</code></pre>`
  }
  return rowsToTableHtml(rows)
}

/** 单元格内去掉标签，实体还原后再转义，供 inline 使用。 */
function htmlCellToText(html: string): string {
  const text = html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p>/gi, '\n')
    .replace(/<[^>]+>/g, '')
  return escapeHtml(decodeBasicEntities(text).trim())
}

function decodeBasicEntities(text: string): string {
  return text
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
}

/**
 * 从表头行开始吃掉一张 GFM 表。
 * 分隔行列数可与表头不一致（按表头补齐/截断）；表头与分隔行之间允许少量空行。
 */
function takeTable(lines: string[], start: number): { html: string; next: number } | null {
  const header = splitTableRow(lines[start])
  if (!header || header.length === 0) {
    return null
  }
  let delimAt = start + 1
  while (delimAt < lines.length && delimAt <= start + 2 && !lines[delimAt].trim()) {
    delimAt += 1
  }
  const rawDelim = splitTableRow(lines[delimAt] ?? '')
  if (!rawDelim || rawDelim.length === 0 || !rawDelim.every(isDelimiterCell)) {
    return null
  }
  const delimiter = fitColumns(rawDelim, header.length, '---')
  const align = delimiter.map(columnAlign)
  const rows: string[][] = []
  let next = delimAt + 1
  while (next < lines.length) {
    const rowLine = lines[next]
    if (!rowLine.trim() || rowLine.startsWith('```') || /^@@TABLE\d+@@$/.test(rowLine.trim())) {
      break
    }
    const cells = splitTableRow(rowLine)
    if (!cells || cells.length === 0) {
      break
    }
    rows.push(fitColumns(cells, header.length, ''))
    next += 1
  }
  return { html: renderTable(header, align, rows), next }
}

function normalizePipes(source: string): string {
  return source.replace(/\uFF5C/g, '|')
}

function fitColumns(cells: string[], size: number, pad: string): string[] {
  if (cells.length === size) {
    return cells
  }
  if (cells.length > size) {
    return cells.slice(0, size)
  }
  const next = cells.slice()
  while (next.length < size) {
    next.push(pad)
  }
  return next
}

function splitTableRow(line: string): string[] | null {
  const source = line.trim()
  if (!source.includes('|')) {
    return null
  }
  const cells: string[] = []
  let current = ''
  let inCode = false
  let sawPipe = false
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i]
    if (ch === '`') {
      inCode = !inCode
      current += ch
      continue
    }
    if (!inCode && ch === '\\' && source[i + 1] === '|') {
      current += '|'
      i += 1
      continue
    }
    if (!inCode && ch === '|') {
      sawPipe = true
      cells.push(current.trim())
      current = ''
      continue
    }
    current += ch
  }
  if (!sawPipe) {
    return null
  }
  cells.push(current.trim())
  if (source.startsWith('|')) {
    cells.shift()
  }
  if (source.endsWith('|') && !source.endsWith('\\|') && cells.length > 0 && cells[cells.length - 1] === '') {
    cells.pop()
  }
  return cells
}

function isDelimiterCell(cell: string): boolean {
  return /^:?[-–—]+:?$/.test(cell)
}

function columnAlign(cell: string): ColumnAlign {
  const normalized = cell.replace(/[–—]/g, '-')
  const left = normalized.startsWith(':')
  const right = normalized.endsWith(':')
  if (left && right) {
    return 'center'
  }
  if (right) {
    return 'right'
  }
  return 'left'
}

function renderTable(header: string[], align: ColumnAlign[], rows: string[][]): string {
  const head = header.map((cell, i) => `<th${alignAttr(align[i] ?? 'left')}>${inline(cell)}</th>`).join('')
  const body = rows
    .map((row) => {
      const cells = header
        .map((_, i) => `<td${alignAttr(align[i] ?? 'left')}>${inline(row[i] ?? '')}</td>`)
        .join('')
      return `<tr>${cells}</tr>`
    })
    .join('')
  const tbody = body ? `<tbody>${body}</tbody>` : ''
  return `<div class="md-table-wrap"><table class="md-table"><thead><tr>${head}</tr></thead>${tbody}</table></div>`
}

function alignAttr(align: ColumnAlign): string {
  if (align === 'left') {
    return ''
  }
  return ` class="align-${align}"`
}

/**
 * 行内语法：先抽出代码片段与反斜杠转义（避免内部的 `*`/`_` 被当成强调），
 * 再排图片、链接、加粗、斜体。
 *
 * @param text 已转义的单个文本片段
 * @return 行内 HTML；不合法链接/图片退回原文
 */
function inline(text: string): string {
  const codes: string[] = []
  // 行内代码用占位符暂存，防止代码里的 `*` `_` 触发强调替换
  let out = text.replace(/`([^`]+)`/g, (_full, code: string) => {
    codes.push(`<code>${code}</code>`)
    return `\u0000C${codes.length - 1}\u0000`
  })
  // 反斜杠转义也先暂存，否则 `\*` 会先被去掉反斜杠再当成斜体
  const escapes: string[] = []
  out = out.replace(/\\([\\`*_[\]()~#>+\-.!])/g, (_full, ch: string) => {
    escapes.push(ch)
    return `\u0001E${escapes.length - 1}\u0001`
  })
  // 图片先于链接处理，否则 `![alt](src)` 会被链接语法吃掉、留下一个 `!`
  out = out.replace(/!\[([^\]\n]*)\]\(([^)\s]+)\)/g, (full: string, alt: string, url: string) => {
    const src = safeImageSrc(url)
    return src
      ? `<img src="${src}" alt="${alt}" loading="lazy" referrerpolicy="no-referrer">`
      : full
  })
  // 链接只放行白名单协议，防止模型输出 javascript: / data: 之类的可执行地址；不合格时原样退回文本
  out = out.replace(/\[([^\]\n]+)\]\(([^)\s]+)\)/g, (full: string, label: string, url: string) => {
    const href = safeHref(url)
    return href
      ? `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`
      : full
  })
  out = out
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    .replace(/\*([^*\n]+)\*/g, '<em>$1</em>')
    // 下划线斜体只在词边界生效，避免把 sys_user 这类标识符当强调
    .replace(/(^|[^\w_])_([^_\n]+)_(?![\w_])/g, '$1<em>$2</em>')
  // 还原转义字符（只留字符本身），再还原行内代码
  out = out.replace(/\u0001E(\d+)\u0001/g, (_full, index: string) => escapes[Number(index)] ?? '')
  return out.replace(/\u0000C(\d+)\u0000/g, (_full, index: string) => codes[Number(index)] ?? '')
}

/**
 * 链接地址白名单：只放行 http(s)、mailto、站点相对路径与锚点。
 *
 * @param url Markdown 括号里的地址（已转义实体）
 * @return 可安全写入 href 的原值；协议不合格返回空串（调用方退回纯文本）
 */
function safeHref(url: string): string {
  const value = url.trim()
  if (!value) {
    return ''
  }
  // 地址里可能带 &amp; 实体，判断协议前先解一层
  const probe = decodeBasicEntities(value).trim().toLowerCase()
  if (probe.startsWith('http://') || probe.startsWith('https://') || probe.startsWith('mailto:')) {
    return value
  }
  // 相对路径、锚点、协议相对写法（//host）都按站内处理
  if (probe.startsWith('/') || probe.startsWith('#') || probe.startsWith('./') || probe.startsWith('../')) {
    return value
  }
  return ''
}

/**
 * 图片地址白名单：复用 {@link safeHref}，但排除 mailto（对 img 无意义）。
 *
 * @param url Markdown 图片括号里的地址
 * @return 可安全写入 src 的原值；不合格返回空串
 */
function safeImageSrc(url: string): string {
  const href = safeHref(url)
  return href.toLowerCase().startsWith('mailto:') ? '' : href
}

function escapeHtml(source: string): string {
  return source
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
