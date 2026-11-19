/**
 * 用户消息附件：剪贴板取图或文件，以及从事件摘要还原地址。所属域：runtime。
 *
 * 粘贴和「选择文件」进同一份待发列表。回看只读用户事件上的清单，不另请求。
 * 纯图片仍是 ahimg；一旦带了非图片文件，摘要是 ahfile，图和文件按添加顺序排列。
 */

/** 与后端 UserMessageImages.PREFIX 一致 */
const PREFIX = 'ahimg:'

/** 与后端 UserMessageFiles.PREFIX 一致 */
const FILE_PREFIX = 'ahfile:'

/** 单文件解码后上限，对齐 multipart 12MB */
export const MAX_FILE_BYTES = 12 * 1024 * 1024

/**
 * 一轮附件总数，与后端 UserMessageFiles.MAX_ATTACHMENTS 一致。
 * 图片和文件加在一起算，待发、拖入、粘贴、发送都用这一数。
 */
export const MAX_USER_ATTACHMENTS = 9

/** 已发送和待发默认直接露出的个数，其余折叠 */
export const ATTACHMENT_PREVIEW_COUNT = 3

/** 单轮非图片文件个数。与附件总数同一上限，合计另卡在 MAX_USER_ATTACHMENTS */
export const MAX_USER_FILES = MAX_USER_ATTACHMENTS

/** 允许的文档扩展名。可执行文件不在这里。录音也走文件通道，入口预处理再转写。 */
const DOC_EXT = new Set([
  'pdf',
  'txt',
  'csv',
  'xlsx',
  'xls',
  'docx',
  'doc',
  'pptx',
  'ppt',
  'zip',
  'webm',
  'wav',
  'mp3',
  'm4a',
  'ogg',
  'opus'
])

/** 明确拒绝的可执行扩展名 */
const BLOCKED_EXT = new Set(['exe', 'msi', 'bat', 'cmd', 'ps1', 'dll'])

/** 走附图链路的扩展名。空 MIME 的 png 也不能当成普通文件。 */
const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'webp', 'gif'])

/** 气泡或待发列表里的一条附件 */
export interface UserAttachmentView {
  kind: 'image' | 'file'
  url: string
  name?: string
  size?: number
}

/** 随消息提交的一个非图片文件 */
export interface OutgoingFile {
  name: string
  size: number
  dataUrl: string
}

/** 剪贴板里的一个文件，以及它该走图片还是文档 */
export interface ClipboardPick {
  file: File
  kind: 'image' | 'file' | 'blocked' | 'unsupported'
}

/** 剪贴板里能变成 File 的一项。测试用普通对象即可，不必真的构造 DataTransfer。 */
export interface ClipboardFileItem {
  kind: string
  type: string
  getAsFile: () => File | null
}

/** 只取粘贴需要的字段，避免组件直接依赖整份 DataTransfer。 */
export interface ClipboardImageSource {
  items?: ArrayLike<ClipboardFileItem> | null
  files?: ArrayLike<File> | null
}

/**
 * 按剪贴板顺序取出图片文件。没有图片时返回空数组，调用方应保持原来的文字粘贴。
 *
 * @param data 粘贴事件上的 clipboardData，可空
 * @return 图片文件，顺序与剪贴板一致
 */
export function clipboardImageFiles(data: ClipboardImageSource | null | undefined): File[] {
  if (!data) {
    return []
  }
  const fromItems = filesFromItems(data.items)
  if (fromItems.length) {
    return fromItems
  }
  const files = data.files
  if (!files || files.length === 0) {
    return []
  }
  const images: File[] = []
  for (let i = 0; i < files.length; i += 1) {
    const file = files[i]
    if (file && file.type.startsWith('image/')) {
      images.push(file)
    }
  }
  return images
}

/**
 * 按剪贴板顺序取出文件。图片仍标成 image，不把图当成普通文件。
 * 没有文件时返回空数组，调用方应保持原来的文字粘贴。
 *
 * @param data 粘贴事件上的 clipboardData，可空
 * @return 按出现顺序的文件；文字项不在结果里
 */
export function clipboardAttachments(data: ClipboardImageSource | null | undefined): ClipboardPick[] {
  if (!data) {
    return []
  }
  const fromItems = picksFromItems(data.items)
  if (fromItems.length) {
    return fromItems
  }
  const files = data.files
  if (!files || files.length === 0) {
    return []
  }
  const picks: ClipboardPick[] = []
  for (let i = 0; i < files.length; i += 1) {
    const file = files[i]
    if (file) {
      picks.push({ file, kind: classifyUserFile(file) })
    }
  }
  return picks
}

/**
 * 判断一个文件该进图片、文档，还是直接拒绝。
 * 剪贴板类型是 image/* 时优先当图片，避免资源管理器里的 png 被当成未知文件。
 *
 * @param file 浏览器 File
 * @return image 走贴图；file 走文档；blocked 是可执行文件；unsupported 不在允许列表
 */
export function classifyUserFile(file: File): ClipboardPick['kind'] {
  const type = (file.type || '').toLowerCase()
  const ext = extensionOf(file.name)
  if (type.startsWith('image/') || IMAGE_EXT.has(ext)) {
    return 'image'
  }
  if (BLOCKED_EXT.has(ext)) {
    return 'blocked'
  }
  if (DOC_EXT.has(ext)) {
    return 'file'
  }
  return 'unsupported'
}

/**
 * 从用户事件还原正文和附件。纯图片只认 ahimg；带文件认 ahfile。
 * 认不出清单时按旧口径：优先正文，没有正文再用摘要。
 *
 * @param contentText 事件正文，可空
 * @param summary     事件摘要，可空
 * @return text 为气泡文字；images 只含图片地址；attachments 按添加顺序含图和文件
 */
export function readUserMessage(contentText?: string | null, summary?: string | null): {
  text: string
  images: string[]
  attachments: UserAttachmentView[]
} {
  const content = (contentText || '').trim()
  const raw = (summary || '').trim()
  if (raw.startsWith(PREFIX)) {
    const images = imagesOf(raw)
    if (images.length) {
      return {
        text: content,
        images,
        attachments: images.map((url) => ({ kind: 'image', url }))
      }
    }
  }
  if (raw.startsWith(FILE_PREFIX)) {
    const attachments = attachmentsOf(raw)
    if (attachments.length) {
      return {
        text: content,
        images: attachments.filter((item) => item.kind === 'image').map((item) => item.url),
        attachments
      }
    }
    // 清单前缀在，但条数对不上：不要把摘要当正文，也不要留空白占位
    return {
      text: content,
      images: [],
      attachments: [{ kind: 'file', name: '附件加载失败', url: '', size: -1 }]
    }
  }
  return { text: content || raw, images: [], attachments: [] }
}

/**
 * 发送前把待发顺序收成气泡能直接画的列表。data URI 只用于当次展示，刷新后换成落盘地址。
 *
 * @param images 图片 data URI，可空
 * @param files  文件，可空
 * @param order  添加顺序，可空；缺省时图片在前、文件在后
 * @return 按添加顺序的附件
 */
export function pendingAttachments(
  images: string[] | undefined,
  files: OutgoingFile[] | undefined,
  order: Array<'image' | 'file'> | undefined
): UserAttachmentView[] {
  const imageQueue = [...(images || [])]
  const fileQueue = [...(files || [])]
  const kinds = order && order.length
    ? order
    : [
        ...imageQueue.map(() => 'image' as const),
        ...fileQueue.map(() => 'file' as const)
      ]
  const items: UserAttachmentView[] = []
  for (const kind of kinds) {
    if (kind === 'image') {
      const url = imageQueue.shift()
      if (url) {
        items.push({ kind: 'image', url })
      }
      continue
    }
    const file = fileQueue.shift()
    if (file) {
      items.push({ kind: 'file', url: file.dataUrl, name: file.name, size: file.size })
    }
  }
  return items
}

/**
 * 给 img / 预览用的地址。data URI 与 http(s) 原样返回；相对路径拼 API 根。
 *
 * @param raw 事件里的地址或发送前的 data URI
 * @return 可直接作为 src 的字符串
 */
export function resolveUserImageUrl(raw: string): string {
  const value = (raw || '').trim()
  if (!value) {
    return ''
  }
  if (value.startsWith('data:') || /^https?:\/\//i.test(value)) {
    return value
  }
  // 与头像同一口径：库里存 /uploads/...，开发期前端在 5173，需拼 VITE_API_BASE
  const env = import.meta.env as { VITE_API_BASE?: string } | undefined
  const base = (env?.VITE_API_BASE || '').replace(/\/$/, '')
  return value.startsWith('/') ? `${base}${value}` : `${base}/${value}`
}

/**
 * @param summary 事件摘要
 * @return 清单里的地址；不是清单时为空数组
 */
function imagesOf(summary?: string | null): string[] {
  const raw = (summary || '').trim()
  if (!raw.startsWith(PREFIX)) {
    return []
  }
  const countColon = raw.indexOf(':', PREFIX.length)
  if (countColon < 0) {
    return []
  }
  const count = Number(raw.slice(PREFIX.length, countColon))
  if (!Number.isInteger(count) || count < 1 || count > MAX_USER_ATTACHMENTS) {
    return []
  }
  const unpacked = unpackManifest(raw.slice(countColon + 1))
  if (unpacked.parts.length !== count) {
    return []
  }
  const urls: string[] = []
  for (const part of unpacked.parts) {
    const url = expandStored(part, unpacked.session, '/uploads/trial-images/')
    if (!url.startsWith('/uploads/trial-images/') && !/^https?:\/\//i.test(url)) {
      return []
    }
    urls.push(url)
  }
  return urls
}

/**
 * 解析 ahfile 清单。任何一条不合法就整单作废，避免半截文件名被当成附件。
 *
 * @param summary 以 ahfile: 开头的摘要
 * @return 按添加顺序的附件；认不出时为空数组
 */
function attachmentsOf(summary: string): UserAttachmentView[] {
  const countColon = summary.indexOf(':', FILE_PREFIX.length)
  if (countColon < 0) {
    return []
  }
  const count = Number(summary.slice(FILE_PREFIX.length, countColon))
  if (!Number.isInteger(count) || count < 1 || count > MAX_USER_ATTACHMENTS) {
    return []
  }
  const unpacked = unpackManifest(summary.slice(countColon + 1))
  if (unpacked.parts.length !== count) {
    return []
  }
  const items: UserAttachmentView[] = []
  for (const part of unpacked.parts) {
    const item = oneAttachment(part, unpacked.session)
    if (!item) {
      return []
    }
    items.push(item)
  }
  return items
}

/**
 * 默认露出前 3 个。展开后全部可见。待发和已发送共用这一刀，不按页面各写。
 *
 * @param items    附件
 * @param expanded 是否展开其余
 * @return shown 当前要画的；rest 是被折叠的个数
 */
export function previewAttachments<T>(items: T[], expanded: boolean): { shown: T[]; rest: number } {
  const list = Array.isArray(items) ? items : []
  if (expanded || list.length <= ATTACHMENT_PREVIEW_COUNT) {
    return { shown: list, rest: 0 }
  }
  return {
    shown: list.slice(0, ATTACHMENT_PREVIEW_COUNT),
    rest: list.length - ATTACHMENT_PREVIEW_COUNT
  }
}

/**
 * 新清单在条数后多写一次会话号，旧清单直接是条目。
 *
 * @param rest 条数冒号后面的内容
 * @return 会话号和条目
 */
function unpackManifest(rest: string): { session: string; parts: string[] } {
  const compact = /^(\d+):([\s\S]*)$/.exec(rest)
  if (compact) {
    return { session: compact[1], parts: compact[2] ? compact[2].split('|') : [] }
  }
  return { session: '', parts: rest ? rest.split('|') : [] }
}

/**
 * 短文件名配会话号拼回地址。完整地址原样返回。
 *
 * @param location 短名或完整地址
 * @param session  会话号，旧清单为空
 * @param prefix   目录前缀
 * @return 可打开的地址
 */
function expandStored(location: string, session: string, prefix: string): string {
  if (!location) {
    return ''
  }
  if (location.startsWith('/') || /^https?:\/\//i.test(location)) {
    return location
  }
  if (!session || location.includes('/')) {
    return location
  }
  return `${prefix}${session}/${location}`
}

/**
 * @param part 一条 i,地址 或 f,名字,字节,地址
 * @return 认不出时 null
 */
function oneAttachment(part: string, session = ''): UserAttachmentView | null {
  if (part.startsWith('i,')) {
    const url = expandStored(part.slice(2), session, '/uploads/trial-images/')
    if (!url.startsWith('/uploads/trial-images/') && !/^https?:\/\//i.test(url)) {
      return null
    }
    return { kind: 'image', url }
  }
  if (!part.startsWith('f,')) {
    return null
  }
  const fields = part.split(',')
  if (fields.length !== 4) {
    return null
  }
  const size = Number(fields[2])
  const url = expandStored(fields[3], session, '/uploads/trial-files/')
  const name = unescapeName(fields[1])
  if (!name || !Number.isFinite(size) || size < 0) {
    return null
  }
  if (!url.startsWith('/uploads/trial-files/') && !/^https?:\/\//i.test(url)) {
    return null
  }
  return { kind: 'file', name, size, url }
}

/**
 * 从左往右还原分隔符转义，和后端一致。
 * 不能先全局替换 %7C：文件名里的百分号会先被写成 %25，再碰到后面的 7C 就会被拆成竖线。
 *
 * @param raw 摘要里的文件名
 * @return 展示名
 */
function unescapeName(raw: string): string {
  let out = ''
  for (let i = 0; i < raw.length; i += 1) {
    if (raw[i] === '%' && i + 2 < raw.length) {
      const hex = raw.slice(i + 1, i + 3).toUpperCase()
      if (hex === '25') {
        out += '%'
        i += 2
        continue
      }
      if (hex === '7C') {
        out += '|'
        i += 2
        continue
      }
      if (hex === '2C') {
        out += ','
        i += 2
        continue
      }
    }
    out += raw[i]
  }
  return out
}

/**
 * @param name 文件名，可空
 * @return 小写扩展名；没有点时为空串
 */
function extensionOf(name: string | undefined): string {
  const value = (name || '').trim()
  const slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'))
  const base = slash >= 0 ? value.slice(slash + 1) : value
  const dot = base.lastIndexOf('.')
  if (dot < 0 || dot === base.length - 1) {
    return ''
  }
  return base.slice(dot + 1).toLowerCase()
}

/**
 * items 里 kind=file 的按出现顺序取。文字项忽略。没有文件项时返回空数组，
 * 调用方再去看 data.files，避免同一份文件被算两次。
 *
 * @param items 剪贴板 items，可空
 * @return 按出现顺序的文件
 */
function picksFromItems(items: ArrayLike<ClipboardFileItem> | null | undefined): ClipboardPick[] {
  if (!items || items.length === 0) {
    return []
  }
  let sawFile = false
  const picks: ClipboardPick[] = []
  for (let i = 0; i < items.length; i += 1) {
    const item = items[i]
    if (!item || item.kind !== 'file') {
      continue
    }
    sawFile = true
    const file = item.getAsFile()
    if (file) {
      picks.push({ file, kind: classifyUserFile(file) })
    }
  }
  return sawFile ? picks : []
}

/**
 * items 里 kind=file 且类型是图片的，按出现顺序取。文字项忽略。
 *
 * @param items 剪贴板 items，可空
 * @return 图片文件
 */
function filesFromItems(items: ArrayLike<ClipboardFileItem> | null | undefined): File[] {
  if (!items || items.length === 0) {
    return []
  }
  const images: File[] = []
  for (let i = 0; i < items.length; i += 1) {
    const item = items[i]
    if (!item || item.kind !== 'file' || !item.type.startsWith('image/')) {
      continue
    }
    const file = item.getAsFile()
    if (file) {
      images.push(file)
    }
  }
  return images
}
