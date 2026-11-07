/**
 * 试跑时间线的两个纯数据辅助：块级稳定 id 与快照浅签名。所属域：runtime（前端）。
 *
 * <p>为什么单独成文件而不是留在 `trialMessages` / `trialStream`：
 * 这两件事都只依赖 `ChatTurn` 结构，不碰响应式、不碰存储，抽出来才能在不加载 Vue 的前提下单测。
 * 调用方：`trialMessages`（追加块时取号）、`trialStream`（读快照补号、写盘前算签名）。
 */
import type { ChatTurn } from '@/types/runtime'

/**
 * 分配一个本会话内稳定的块级 id。
 *
 * <p>为什么不能再用数组下标当渲染身份：视觉块 / 编排调度卡是 {@code unshift} 进队首的，
 * 下标身份会整体位移，导致后面所有块的 DOM 被卸装重建（Markdown 槽缓存同时失效）。
 * 这里用「轮 key + 该轮自增序号」：已存在的块永远不换号，只有新增块才取新号。
 *
 * @param turn 目标助手轮（会顺手推进它自己的 {@code blockSeq}）
 * @return 稳定 id，形如 {@code e-12#b3}
 */
export function nextBlockId(turn: ChatTurn): string {
  const seq = (turn.blockSeq ?? 0) + 1
  turn.blockSeq = seq
  return `${turn.key}#b${seq}`
}

/**
 * 给缺 id 的块补上稳定 id，并返回同一个数组。
 *
 * <p>只在「读刷新快照 / 外部导入时间线」这类边界跑一次：老版本快照没有 {@code id} 字段，
 * 原样渲染会退回下标 key，插入视觉块时又会整表重挂。补号顺序与流式新增完全一致。
 *
 * @param turns 时间线（原地补齐）
 * @return 补齐后的同一条时间线
 */
export function ensureBlockIds(turns: ChatTurn[]): ChatTurn[] {
  for (const turn of turns) {
    if (!Array.isArray(turn.blocks)) {
      turn.blocks = []
      continue
    }
    for (const block of turn.blocks) {
      if (!block.id) {
        block.id = nextBlockId(turn)
      }
    }
  }
  return turns
}

/**
 * 快照内容浅签名：只看结构与长度，不读正文、不做 JSON 序列化。
 *
 * <p>为什么需要：刷新快照要在流式期反复重写，而单次序列化的是整段历史。
 * 这里用 O(轮数) 的遍历算出「是否有变化」，签名没变就完全不进序列化。
 * 只看末尾轮的文本长度是够的——流式期只有末尾一轮在增长；
 * 历史轮被事件回放整体替换时轮数 / 块数也会变，签名同样会失效。
 *
 * @param turns 时间线
 * @return 可比较的签名串
 */
export function snapshotSignature(turns: ChatTurn[]): string {
  let blocks = 0
  for (const turn of turns) {
    blocks += turn.blocks?.length ?? 0
  }
  const tail = turns[turns.length - 1]
  if (!tail) {
    return `${turns.length}|${blocks}`
  }
  let chars = 0
  for (const block of tail.blocks ?? []) {
    chars += (block.text?.length ?? 0) + (block.detail?.length ?? 0)
  }
  return `${turns.length}|${blocks}|${tail.key}|${tail.status}|${tail.phase}|${chars}`
}
