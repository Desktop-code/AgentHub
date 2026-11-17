/**
 * 参与者身份帧在「一次 JSON.parse 之后」的形状判断。所属域：runtime（前端）。
 *
 * <p>SSE 出站只序列化一次，解析结果必须是对象。若出站先把 JSON 收成字符串再编码，
 * 一次解析得到的是字符串：这里不二次解析，调用方就收不到 begin，也不会按路由名补卡。
 *
 * <p>字段与 `TrialParticipantChunk` 一致。类型写在这里，避免和 SSE 派发互相引用。
 */
export interface ParsedParticipantFrame {
  /** 参与者标识 */
  id: string
  /** 展示名 */
  name: string
  /** gateway / scheme / agent / workflow / conclusion */
  kind: string
  /** 候选 route_key；可空 */
  routeKey?: string
  /** 嵌套层级 */
  depth?: number
  /** true＝该步开始 */
  begin: boolean
  /** 终态；可空 */
  state?: string
  /** 终态说明；可空 */
  note?: string
}

/**
 * 取出一次解析后的身份帧。
 *
 * @param data `JSON.parse` 的结果
 * @return 对象帧；字符串、空值一律不当身份，调用方不建卡
 */
export function asParticipantChunk(data: unknown): ParsedParticipantFrame | undefined {
  // 字符串是被再编码过的帧。再解析一次会把错误出站悄悄接住，这里拒绝。
  if (!data || typeof data !== 'object') {
    return undefined
  }
  return data as ParsedParticipantFrame
}
