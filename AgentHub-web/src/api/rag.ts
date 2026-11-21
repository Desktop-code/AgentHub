/**
 * 知识库接口。所属域：rag。
 * 只暴露「知识库」语义：不返回 Collection 名，也不返回任何模型 Key。
 * 新建/重建在服务端同步建 Collection，耗时可能在数秒级，故单独放长超时。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type {
  RagChunkRow,
  RagDocumentRow,
  RagKbRow,
  RagParseTextRow,
  RagRechunkPreviewRow
} from '@/types/rag'

/** 分页。size 不超过 100。 */
export function pageKbs(params: {
  current: number
  size: number
  keyword?: string
  status?: string
  visibility?: string
}) {
  return request.get<Result<PageResult<RagKbRow>>>('/api/rag/kbs', { params })
}

/** 详情。 */
export function getKb(id: number) {
  return request.get<Result<RagKbRow>>(`/api/rag/kbs/${id}`)
}

/**
 * 新建并立即建 Collection。
 * 建集失败不会抛错，返回 status=INIT_FAILED 且带 errorMsg，页面据此提示「可重建」。
 * 超时放宽到 30s：Milvus 建集 + 加载集合可能超过默认 15s。
 */
export function createKb(data: Record<string, unknown>) {
  return request.post<Result<RagKbRow>>('/api/rag/kbs', data, { timeout: 30000 })
}

/** 改资料与检索参数（Embedding 与维度不可改）。 */
export function updateKb(id: number, data: Record<string, unknown>) {
  return request.put<Result<RagKbRow>>(`/api/rag/kbs/${id}`, data)
}

/** 重建索引（后端建集合）。初始化失败恢复、Milvus 数据被清理后修复。 */
export function rebuildKb(id: number) {
  return request.post<Result<RagKbRow>>(`/api/rag/kbs/${id}/rebuild`, null, { timeout: 30000 })
}

/** 删除知识库（先删集合再逻辑删记录；级联清理文档/切片/绑定）。 */
export function deleteKb(id: number) {
  return request.delete<Result<null>>(`/api/rag/kbs/${id}`, { timeout: 30000 })
}

/** 文档分页。keyword 只匹配标题。 */
export function pageDocuments(kbId: number, params: { current: number; size: number; keyword?: string }) {
  return request.get<Result<PageResult<RagDocumentRow>>>(`/api/rag/kbs/${kbId}/documents`, { params })
}

/**
 * 上传文档并异步入库。返回的是「已受理」（status=PENDING），后续靠列表轮询看进度。
 * 超时放宽到 60s：上传 + 落盘 + 建任务，但不含解析与向量化。
 */
export function uploadDocument(kbId: number, file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<Result<RagDocumentRow>>(`/api/rag/kbs/${kbId}/documents`, form, {
    timeout: 60000,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 重试失败的入库任务。 */
export function retryDocument(kbId: number, docId: number) {
  return request.post<Result<RagDocumentRow>>(`/api/rag/kbs/${kbId}/documents/${docId}/retry`)
}

/** 删除文档：级联清向量、切片、任务与原文件。 */
export function deleteDocument(kbId: number, docId: number) {
  return request.delete<Result<null>>(`/api/rag/kbs/${kbId}/documents/${docId}`, { timeout: 30000 })
}

/**
 * 查看某文档的全部切片。只读接口，按序号升序返回。
 * 尚未入库完成或已失败时返回空数组，不报错。
 */
export function listDocumentChunks(kbId: number, docId: number) {
  return request.get<Result<RagChunkRow[]>>(`/api/rag/kbs/${kbId}/documents/${docId}/chunks`)
}

/**
 * 按新片长重新切片并重跑入库。返回「已受理」（status=PENDING）。
 * 后端会先清旧切片与旧向量再入队，前端随后靠文档列表轮询看新进度。
 */
export function rechunkDocument(kbId: number, docId: number, data: { chunkSize: number; chunkOverlap?: number }) {
  return request.post<Result<RagDocumentRow>>(
    `/api/rag/kbs/${kbId}/documents/${docId}/rechunk`,
    data,
    { timeout: 30000 }
  )
}

/**
 * 读取本轮入库实际使用的解析文本，用于左侧原文对照。
 * 老文档无产物时后端从原文件只读再解析，不落库、不改切片与向量。
 */
export function getDocumentParseText(kbId: number, docId: number) {
  return request.get<Result<RagParseTextRow>>(`/api/rag/kbs/${kbId}/documents/${docId}/parse`)
}

/**
 * 重新切片预览：只按片长/重叠重算，不写切片、不动向量、不改文档状态。
 * 确认覆盖仍调用 rechunkDocument。
 */
export function previewRechunkDocument(
  kbId: number,
  docId: number,
  data: { chunkSize: number; chunkOverlap?: number }
) {
  return request.post<Result<RagRechunkPreviewRow>>(
    `/api/rag/kbs/${kbId}/documents/${docId}/rechunk/preview`,
    data,
    { timeout: 30000 }
  )
}

/**
 * 单条切片编辑：只更新该片正文与章节路径，并只重写这一片向量。
 * 空正文由前后端双重拦截；权限沿用 rag:doc:rechunk。
 */
export function updateDocumentChunk(
  kbId: number,
  docId: number,
  chunkId: number,
  data: { content: string; sectionPath?: string | null }
) {
  return request.put<Result<RagChunkRow>>(
    `/api/rag/kbs/${kbId}/documents/${docId}/chunks/${chunkId}`,
    data,
    { timeout: 30000 }
  )
}

