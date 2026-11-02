/**
 * 工具源与工具项。所属域：tool。
 * 接口只返回掩码；创建源和重置鉴权头都不会把明文放进响应，前端也不再依赖明文做提示。
 */
import request from '@/utils/request'
import type { PageResult, Result } from '@/types'
import type { ItemBatchStatusResult, ProbeResult, SyncResult, ToolItemRow, ToolSource } from '@/types/tool'

/** 工具源卡片。 */
export function listSources() {
  return request.get<Result<ToolSource[]>>('/api/tool/sources')
}

/** 新建 MCP 源。 */
export function createSource(data: Record<string, unknown>) {
  return request.post<Result<ToolSource>>('/api/tool/sources', data)
}

/** 修改名称、地址和备注，不提交鉴权头。 */
export function updateSource(id: number, data: Record<string, unknown>) {
  return request.put<Result<ToolSource>>(`/api/tool/sources/${id}`, data)
}

/** 逻辑删除 MCP 源及其工具项。内置源会被拒绝。 */
export function deleteSource(id: number) {
  return request.delete<Result<null>>(`/api/tool/sources/${id}`)
}

/** 启停工具源。 */
export function changeSourceStatus(id: number, status: number) {
  return request.put<Result<ToolSource>>(`/api/tool/sources/${id}/status`, { status })
}

/** 重置鉴权头。读超时略长于同步，避免前端先断开。 */
export function resetSourceAuth(id: number, authHeader: string) {
  return request.post<Result<ToolSource>>(`/api/tool/sources/${id}/reset-auth`, { authHeader })
}

/** MCP 同步（SSE / Streamable HTTP）。超时略长于后端读取上限。 */
export function syncSource(id: number) {
  return request.post<Result<SyncResult>>(`/api/tool/sources/${id}/sync`, null, { timeout: 25000 })
}

/**
 * 新建 / 编辑弹窗测 MCP 链接（按 transport）。不写库。
 * 编辑且鉴权头留空时传 sourceId，后端用库存密文。
 */
export function probeSource(data: {
  sourceId?: number
  baseUrl?: string
  authHeader?: string
  transport?: string
}) {
  return request.post<Result<ProbeResult>>('/api/tool/sources/probe', data, { timeout: 25000 })
}

/** 工具项分页。未选源时不传 sourceId，表示全部。 */
export function pageItems(params: {
  current: number
  size: number
  sourceId?: number
  type?: string
  status?: number
  keyword?: string
}) {
  return request.get<Result<PageResult<ToolItemRow>>>('/api/tool/items', { params })
}

/**
 * 拉取全部工具项（含已停用），供 Agent「工具绑定」选择器分组展示。
 * <p>
 * 后端 size 上限 100，超出时必须按页累加：只取第一页会让排在后面的工具凭空消失。
 * 不传 status，停用项也要拿回来（由前端置灰展示，而不是装作不存在）。
 * 最多 20 页（2000 条）防御异常 total 造成死循环。
 *
 * @return 全量工具项，按接口原顺序（id 倒序）
 */
export async function listAllItems(): Promise<ToolItemRow[]> {
  const size = 100
  const maxPages = 20
  const rows: ToolItemRow[] = []
  for (let page = 1; page <= maxPages; page += 1) {
    const { data } = await pageItems({ current: page, size })
    const result = data.data
    const records = result?.records || []
    rows.push(...records)
    // 本页没满或已累计到总数，说明到底了
    if (records.length < size || rows.length >= (result?.total ?? 0)) {
      break
    }
  }
  return rows
}

/** 新建工具项，后端会把状态固定为停用。 */
export function createItem(data: Record<string, unknown>) {
  return request.post<Result<ToolItemRow>>('/api/tool/items', data)
}

/** 编辑标题、说明和 schema。 */
export function updateItem(id: number, data: Record<string, unknown>) {
  return request.put<Result<ToolItemRow>>(`/api/tool/items/${id}`, data)
}

/** 逻辑删除工具项。 */
export function deleteItem(id: number) {
  return request.delete<Result<null>>(`/api/tool/items/${id}`)
}

/** 启停工具项，1 进入白名单。 */
export function changeItemStatus(id: number, status: number) {
  return request.put<Result<ToolItemRow>>(`/api/tool/items/${id}/status`, { status })
}

/**
 * 源内批量启停：一次把该 MCP 源下全部未删除工具项置为启用或停用。
 * 只作用于该源，不下架、不改 schema；内置源与非 MCP 源由后端拒绝。
 */
export function changeAllItemStatus(sourceId: number, status: number) {
  return request.put<Result<ItemBatchStatusResult>>('/api/tool/items/batch-status', { sourceId, status })
}
