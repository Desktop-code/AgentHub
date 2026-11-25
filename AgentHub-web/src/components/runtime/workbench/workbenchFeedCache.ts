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
 * 因此签名必须覆盖：参与者身份、begin 占位标记、参与者终态、工作流状态与正文长度，
 * 以及工作流**节点过程**（节点正文 / 文件 / 思考与工具步）——它们同样是原位增长。
 *
 * <p>线程 / 生命周期：只在单线程（浏览器主线程）用；切会话时由调用方 {@link #clear()}。
 */
import type { AssistantBlock, ChatTurn, TrialWorkflowNodeView } from '@/types/runtime'
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
    /*
     * 跨轮接管（续跑轮收走上一轮的等待卡）会改写已结算轮的块：块本身可能一条不少
     * （拿掉工作流卡又补上别的内容时长度恰好相同），只有这个计数能保证签名必变。
     */
    + `|r${turn.revision ?? 0}`
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
      /*
       * 可点选项必须进签名：它在卡片状态**仍是 RUNNING / WAITING_REPLY** 时才出现，
       * 节点状态与正文长度可能都不变，漏掉它直播这一拍会命中缓存、chips 要等收尾才出来。
       */
      signature += `:${block.workflow.choice?.options.map((option) => option.id).join(',') || ''}`
      /*
       * 节点过程同样必须进签名：思考 / 工具步与节点正文都是**原位增长**——
       * 节点状态一直是 RUNNING、卡级 answer 一个字没变，只有最后一段思考或节点正文在变长。
       * 漏掉这一段，签名不变 → 命中缓存 → 卡片仍握着投影那一刻的旧快照，
       * 页面上就是「节点思考和正文要等节点结束才一次性出现」。
       */
      signature += `|wfn:${workflowNodeProcessSignature(block.workflow.nodes)}`
    }
  }
  return signature
}

/**
 * 工作流节点过程的浅签名：逐节点的正文长度、文件条数，以及每一步的类型 / 状态 / 正文长度。
 *
 * <p>为什么单独抽出来：签名每帧都要算，必须保持「只拼长度与状态、不序列化全文」的便宜口径；
 * 单独成函数也让「节点过程要不要作废缓存」这一条判定只有一处实现。
 *
 * <p>只拼 {@code content.length} 而不拼内容：正文内容变了长度必然变，
 * 长度没变时展示也没有可更新的差异，没必要为它把整段思考搬进签名。
 *
 * @param nodes 工作流卡片上的节点快照
 * @return 形如「节点码:步数:步签名:正文长度:文件数|…」的浅签名
 */
function workflowNodeProcessSignature(nodes: TrialWorkflowNodeView[]): string {
  const parts: string[] = []
  for (const node of nodes) {
    const steps = Array.isArray(node.steps) ? node.steps : []
    // 步签名只留类型 / 状态 / 正文长度：工具步靠 state 变化推进，思考靠 length 增长
    let stepSignature = ''
    for (const step of steps) {
      stepSignature += `${step.type}:${(step.content || '').length}:${step.state || ''},`
    }
    const files = Array.isArray(node.files) ? node.files.length : 0
    /*
     * 循环字段必须一起进签名：框的标题（第几轮 / 总共几次 / 进行中还是次数用尽）与
     * 「哪个体节点在哪个框里」都由它们决定，节点状态和正文长度却可能都没变。
     * 漏掉就会命中缓存，框停在旧轮次。
     */
    const loop = `${node.group || ''}:${node.loopRound || 0}:${node.loopTotal || 0}:${node.loopDecision || ''}`
    parts.push(
      `${node.code || node.name}#${node.attempt && node.attempt > 0 ? node.attempt : 1}`
        + `:${steps.length}:${stepSignature}:${(node.output || '').length}:${files}:${loop}`
    )
  }
  return parts.join('|')
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
      /*
       * 结算缓存键带上接管代次：同一轮被续跑轮改写后，旧签名不再命中，
       * 必须重扫块才能把拿掉的等待卡反映到页面上。
       */
      const settledKey = `${turn.key}#${turn.revision ?? 0}`
      if (turn.status === 'streaming') {
        // 仍在流式：内容每帧都在长，签名必须现算，同时作废它可能残留的旧缓存
        this.settled.delete(settledKey)
      } else {
        const cached = this.settled.get(settledKey)
        if (cached !== undefined) {
          signatures.set(turn.key, cached)
          continue
        }
      }
      const signature = turnFeedSignature(turn, entry, candidates)
      signatures.set(turn.key, signature)
      if (turn.status !== 'streaming') {
        this.settled.set(settledKey, signature)
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
