/**
 * 每轮消息流投影的「内容签名 + 投影缓存」闸。所属域：runtime（前端展示）。
 *
 * <p>为什么单独成模块：模板里每轮至少要问三次「有没有分块」（头像让位、分块区、逐块兜底），
 * 每次调用都会重扫全部路由与块。长会话按「轮 × 块」放大，主线程会被拖住，表现就是打开页面
 * 长时间空白。这里每帧只算一遍浅签名，投影按签名命中缓存。
 *
 * <p>为什么需要**闸**（而不是只缓存）：直播里的很多变化是「原位改写」——块的数量、正文长度、
 * 文件名都不变，变的只有身份（{@code participantId}）、占位标记（{@code begin}）
 * 或这位参与者的终态（{@code closed}）。
 * 签名漏掉这类字段时，缓存命中会继续返回旧卡片，「有身份就保留 pending 壳」的判定永远等不到执行。
 * 因此签名必须覆盖：参与者身份、begin 占位标记、参与者终态、工作流状态与正文长度。
 *
 * <p>线程 / 生命周期：只在单线程（浏览器主线程）用；切会话时由调用方 {@link #clear()}。
 */
import type { AssistantBlock, ChatTurn } from '@/types/runtime'
import type { ParticipantCard } from './workbenchParticipantFeed'

/** 一轮的投影缓存条目：签名一致就直接复用卡片 */
interface FeedMemoEntry {
  signature: string
  cards: ParticipantCard[]
}

/**
 * 一轮消息流的浅签名。
 *
 * <p>只拼字段长度与稳定标识，不序列化正文：签名要便宜，才能在每帧都算。
 *
 * @param turn       本轮助手消息
 * @param entry      入口展示名（配置改名要作废缓存）
 * @param candidates 候选池签名（池子变化同样作废）
 * @return 一轮的签名串
 */
export function turnFeedSignature(turn: ChatTurn, entry: string, candidates: string): string {
  const blocks: AssistantBlock[] = Array.isArray(turn.blocks) ? turn.blocks : []
  let signature = `${turn.status}|${entry}|${candidates}|${turn.entryParticipantId || ''}`
    + `|${turn.continuation === true ? 'cont' : ''}`
  for (const block of blocks) {
    /*
     * 身份（participantId）与「这一步刚开始」（begin）必须进签名：
     * begin 是**原位补身份**——块的种类、正文长度、文件名都不变，只多了一个 participantId。
     * 漏掉它，begin 那一拍会命中缓存继续返回旧卡片，方案卡只能等子专家或终答才闪出来。
     */
    signature += `|${block.kind}:${(block.text || '').length}:${(block.detail || '').length}`
      + `:${block.file?.fileName || ''}:${block.participantId || ''}:${block.begin === true ? 'b' : ''}`
      /*
       * 参与者终态（closed）必须进签名：done / failed / aborted 帧到达时，
       * 块的数量、正文长度、文件名**都不变**，只有这一位不再「进行中」。
       * 漏掉它，签名一致 → 命中缓存 → 投影继续按「整轮 streaming」画进行态，
       * 已跑完的专家要等整轮落屏才和最后一位一起停下来。
       */
      + `:${block.closed || ''}`
      // kind / depth 会在 begin 原位补上，正文长度不变。漏进签名就会继续用旧卡片
      + `:${block.participantKind || ''}:${typeof block.participantDepth === 'number' ? block.participantDepth : ''}`
    /*
     * 工作流卡片是**状态快照**：正文长度不变但节点状态在推进；运行中正文还会按节点增量变长。
     * 两者都不进签名时，页面会停在第一拍、或看不到新到的文字。
     */
    if (block.preprocess) {
      // 抽字从「进行中」到「已回传」时正文长度可能不变，状态必须进签名，否则回放停在旧卡片
      signature += `|pp:${block.preprocess.status}:${(block.preprocess.text || '').length}`
    }
    if (block.workflow) {
      signature += `|wf:${block.workflow.name}:${block.workflow.status}:`
        + `${block.workflow.nodes.map((node) => node.status).join(',')}:${(block.workflow.answer || '').length}`
    }
  }
  return signature
}

/**
 * 投影缓存闸：算签名 → 命中复用 → 未命中重算并记住。
 *
 * <p>已结算（非流式）的轮次内容不会再变，签名算一次就记下来，之后每帧直接命中，
 * 不再随流式轮的增量一起重扫全量块。
 */
export class ParticipantFeedCache {
  /** 每轮投影结果的缓存：同一签名只投影一次 */
  private readonly memo = new Map<string, FeedMemoEntry>()
  /** 已结算轮次的签名：轮次结束后不再重扫它的块 */
  private readonly settled = new Map<string, string>()
  /**
   * 已结算签名所属的「全局前缀」（入口名 + 候选集签名）。
   * 两者一变，所有已结算签名都不再对应当前配置，必须整表作废，
   * 否则改名后已结束的轮次会一直用旧标题（缓存省下的是重扫，不是正确性）。
   */
  private prefix = ''

  /**
   * 算一遍本轮所有轮的签名。
   *
   * @param turns      本轮全部消息
   * @param entry      入口展示名
   * @param candidates 候选池签名
   * @return 轮 key → 签名
   */
  signatures(turns: ChatTurn[], entry: string, candidates: string): Map<string, string> {
    const signatures = new Map<string, string>()
    const prefix = `${entry}|${candidates}`
    if (prefix !== this.prefix) {
      this.prefix = prefix
      this.settled.clear()
    }
    for (const turn of turns) {
      if (turn.status === 'streaming') {
        // 仍在流式：内容每帧都在长，签名必须现算，同时作废它可能残留的旧缓存
        this.settled.delete(turn.key)
      } else {
        const cached = this.settled.get(turn.key)
        if (cached !== undefined) {
          signatures.set(turn.key, cached)
          continue
        }
      }
      const signature = turnFeedSignature(turn, entry, candidates)
      signatures.set(turn.key, signature)
      if (turn.status !== 'streaming') {
        this.settled.set(turn.key, signature)
      }
    }
    return signatures
  }

  /**
   * 取某一轮的卡片：签名一致就复用，否则重算。
   *
   * @param turn      本轮助手消息
   * @param signature 本轮签名（缺省算成空串，等同「没有缓存可用」）
   * @param build     真正的投影函数；只在未命中时调用
   * @return 投影结果
   */
  project(turn: ChatTurn, signature: string, build: () => ParticipantCard[]): ParticipantCard[] {
    const cached = this.memo.get(turn.key)
    if (cached && cached.signature === signature) {
      return cached.cards
    }
    const cards = build()
    this.memo.set(turn.key, { signature, cards })
    return cards
  }

  /**
   * 投影失败时的兜底：本轮记成空卡片，避免每帧都重新抛一次。
   *
   * @param turn      本轮助手消息
   * @param signature 失败时的签名
   */
  rememberEmpty(turn: ChatTurn, signature: string) {
    this.memo.set(turn.key, { signature, cards: [] })
  }

  /**
   * 清空全部缓存。切会话 / 换消息数组时必须调用：上一份时间线的测量与签名字节都不再有效。
   */
  clear() {
    this.memo.clear()
    this.settled.clear()
    this.prefix = ''
  }
}
