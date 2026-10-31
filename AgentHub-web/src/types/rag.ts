/**
 * 知识库类型。所属域：rag。
 * 与后端 RagKbResp 对齐；Collection 名是内部实现细节，不在契约里。
 */

/** READY 可用 / INIT_FAILED 建集失败 / DISABLED 停用 */
export type RagKbStatus = 'READY' | 'INIT_FAILED' | 'DISABLED'

/** DENSE_BM25 支持向量+关键词；DENSE_ONLY 仅向量（本机 Milvus Lite 会降级到这个） */
export type RagStoreCapability = 'DENSE_BM25' | 'DENSE_ONLY'

export interface RagKbRow {
  id: number
  name: string
  description?: string
  /** 一期恒为 LOCAL_MILVUS */
  storeType: string
  /** 建集失败时为 null */
  storeCapability?: RagStoreCapability | null
  embeddingModelId: number
  embeddingModelName?: string | null
  vectorDimension: number
  rerankModelId?: number | null
  rerankModelName?: string | null
  isolationMode: string
  visibility: string
  retrievalConfig?: string | null
  ingestConfig?: string | null
  status: RagKbStatus | string
  /** 初始化失败原因；成功时为 null */
  errorMsg?: string | null
  createTime?: string
  updateTime?: string
}

/** 状态中文与标签样式，供列表/详情共用，避免两处写死字符串 */
export const KB_STATUS_LABEL: Record<string, string> = {
  READY: '就绪',
  INIT_FAILED: '初始化失败',
  DISABLED: '已停用'
}

/** 能力中文说明 */
export const KB_CAPABILITY_LABEL: Record<string, string> = {
  DENSE_BM25: '向量 + 关键词',
  DENSE_ONLY: '仅向量'
}

/** 可见性中文 */
export const KB_VISIBILITY_LABEL: Record<string, string> = {
  PUBLIC: '公开',
  PRIVATE: '私有',
  RESTRICTED: '指定授权'
}

/** 隔离模式中文 */
export const KB_ISOLATION_LABEL: Record<string, string> = {
  PHYSICAL: '物理隔离（一库一集合）',
  LOGICAL: '逻辑隔离（共集合，二期）',
  PUBLIC: '公开池'
}

/** 文档状态中文 */
export const DOC_STATUS_LABEL: Record<string, string> = {
  PENDING: '待入库',
  RUNNING: '入库中',
  READY: '可检索',
  FAILED: '失败'
}

/** 文档响应；含入库任务进度 */
export interface RagDocumentRow {
  id: number
  kbId: number
  title: string
  sourceUri?: string | null
  mime?: string | null
  byteSize?: number | null
  chunkCount?: number | null
  /** PENDING / RUNNING / READY / FAILED */
  status: string
  errorMsg?: string | null
  version?: number | null
  /** 任务状态，与 status 同步推进 */
  taskStatus?: string | null
  /** 解析 / 切片 / 向量化 / 写入 */
  stage?: string | null
  /** 0-100 */
  progress?: number | null
  attempts?: number | null
  createTime?: string
  updateTime?: string
}

/**
 * 切片行。所属域：rag。
 * 与后端 RagChunkResp 对齐：只有序号、Token 估算与完整正文，不含向量与内部主键细节。
 */
export interface RagChunkRow {
  id: number
  /** 文档内序号，从 0 起；展示用 chunkIndex + 1 */
  chunkIndex: number
  /** Token 估算，展示参考值 */
  tokenEst?: number | null
  /** 完整正文 */
  content: string
}

/** Agent 知识库绑定 */
export interface KbBindRow {
  id: number
  agentId: number
  kbId: number
  /** 1 启用 0 停用 */
  status: number
  /** 检索覆盖项 JSON，可空 */
  overridesJson?: string | null
  kbName?: string | null
  kbStatus?: string | null
  kbCapability?: string | null
  kbVisibility?: string | null
  vectorDimension?: number | null
}

/**
 * Agent 检索链路的重排配置。所属域：rag。
 * 存储仍是 agent_model_bind(purpose=RERANK)，但配置入口只在「知识库」Tab；
 * 未配置时各字段为空对象。
 */
export interface AgentRerank {
  /** 绑定 id；未配置为 null */
  bindId?: number | null
  /** 重排模型实例 id；未配置为 null */
  modelInstanceId?: number | null
  displayName?: string | null
  modelId?: string | null
}
