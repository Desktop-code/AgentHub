/**
 * 把试跑事件收成对话时间线。所属域：runtime。
 * 同一轮的思考、工具和回答进同一张助手卡片。没有正文的思考不生成块。
 */
import type {
  AssistantBlock,
  ChatTurn,
  TrialChoiceView,
  TrialEvent,
  TrialFileView,
  TrialPlanView,
  TrialPreprocessView,
  TrialStepView,
  TrialWorkflowLimitsView,
  TrialWorkflowNodeView,
  TrialWorkflowStepView,
  TrialWorkflowView
} from '@/types/runtime'
import { readUserMessage } from './userMessageImages.ts'
import { renderSimpleMarkdown } from '../../utils/simpleMarkdown.ts'
import { nextBlockId } from './trialTimeline.ts'
import {
  parseChildTitle as parseFixedChildTitle,
  parseRouteParticipant
} from './workbench/workbenchRouteText.ts'

/**
 * 按 seq 回放。用户单独成气泡，其后的步骤并进一张助手卡片。
 * @param events 同一会话的事件，顺序不限
 * @return 对话区条目
 */
export function turnsFromEvents(events: TrialEvent[]): ChatTurn[] {
  const ordered = [...events].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  const turns: ChatTurn[] = []
  let assistant: ChatTurn | undefined
  // 上一条 begin 身份：其后的思考 / 工具 / 正文归它，直到下一条身份行。没有身份行就不认人。
  let active: ReplaySpeaker | undefined
  // 本轮是否已经出现过入口身份：没出现过却先来一位专家 / 工作流，说明这是断点续作，不是又判了一次入口
  let turnHasEntry = false
  for (const item of ordered) {
    if (item.eventType === 'user') {
      // 附图在摘要清单里，正文仍是原话。只发图时正文为空，气泡仍要留下。
      const message = readUserMessage(item.contentText, item.summary)
      assistant = undefined
      active = undefined
      turnHasEntry = false
      if (message.text || message.images.length || message.attachments.length) {
        turns.push(userTurn(item.id, message.text, message.images, message.attachments))
      }
      continue
    }
    if (item.eventType === 'participant') {
      const who = readParticipantEvent(item)
      // 只认 begin：begin=false 是直播增量定向，回放不靠它换人
      if (who?.begin) {
        active = who
        if (who.kind === 'gateway') {
          turnHasEntry = true
          assistant = ensureReplayTurn(turns, assistant, item.id)
          assistant.entryParticipantId = who.id
        } else if (who.kind === 'workflow' && replayHasWaitingCard(assistant, who)) {
          /*
           * 等待回复的续跑（库里没有新的用户消息可作分隔）：这条身份紧跟上一轮的等待卡。
           * 必须另起一张续作轮，不能并回等待那一轮：并回去旧卡就无法被
           * {@link absorbWaitingWorkflowCards} 收走，等待正文也会留在同一条消息里。
           */
          assistant = ensureReplayTurn(turns, undefined, item.id)
          assistant.continuation = true
        } else if (who.kind !== 'conclusion' && !assistant) {
          /*
           * 本轮第一条身份就是专家 / 工作流：这是「等待回复续跑」或「点选项续作」。
           * 后端不会为这种轮次推入口身份，也没有入口判定；回放同样不能凭一条 begin 占位
           * 反推出「智能网关 → X」。标记续作轮，投影层据此不合成入口块。
           */
          assistant = ensureReplayTurn(turns, assistant, item.id)
          if (!turnHasEntry) {
            assistant.continuation = true
          }
        }
      } else {
        /*
         * 参与者终态（begin=false + state）：把「这一位已经结束」落到它自己的 route 块上。
         * 进行中一轮的内存快照现在带这一帧；缺了它，刷新后所有成员只能等整轮收尾才停转。
         * 只收口已经存在的助手轮，不凭一条终态凭空造轮。
         */
        const end = readParticipantEnd(item)
        if (end && assistant) {
          appendParticipantEnd(assistant, end)
        }
      }
      const block = participantBeginBlock(item)
      if (!block) {
        continue
      }
      assistant = ensureReplayTurn(turns, assistant, item.id)
      pushBlock(assistant, block, item.sessionId)
      continue
    }
    const block = blockFromEvent(item)
    if (!block) {
      continue
    }
    // 落库的思考 / 正文不带身份列：跟着上一条 participant 行走，不从路由文案猜作者
    carryReplayIdentity(block, active)
    assistant = ensureReplayTurn(turns, assistant, item.id)
    pushBlock(assistant, block, item.sessionId)
    if (block.kind === 'error') {
      assistant.status = 'error'
      assistant.phase = 'error'
    }
  }
  // 等待回复续跑：把上一轮那张「停在询问」的卡收到续跑轮这一张上，避免同一条运行出现两张卡
  absorbWaitingWorkflowCards(turns)
  return turns
}

/**
 * 续跑轮接管上一轮的等待卡：同一条工作流只保留续跑轮这一张执行卡。所属域：runtime（前端）。
 *
 * <p>为什么必须跨轮收口：用户回答后时间线多了一轮（先是用户气泡，再是助手轮），
 * 助手轮里的工作流快照是**同一条运行**的完整节点表（含已经结束的询问节点）。
 * 若两轮各画一张卡，页面上就是「旧的还在问、下面又起一条从第一个条件节点开始的执行流」，
 * 看起来像入口新分发了一条运行——实际并没有。
 *
 * <p>接管条件（缺一不可）：
 * <ul>
 *   <li>本轮是续作轮（{@code continuation=true}）；</li>
 *   <li>本轮确有工作流卡；</li>
 *   <li>更早的某轮有一张**同编码且仍在等待回复**的卡（卡片头或任一节点 WAITING_REPLY）。</li>
 * </ul>
 * 已完成 / 失败 / 取消的旧卡不动；单专家的选项续作没有工作流卡，也不会被接管。
 *
 * <p>接管的动作只是「把旧卡拿掉」，不搬动新卡：新卡本来就带着完整节点表与续跑种子正文
 * （问句在种子里），因此执行卡仍在续跑轮上继续往下长，旧选项随旧卡一起消失。
 * 旧轮里那段等待正文一同去掉，避免问句在两处重复；旧轮其余内容（入口卡、身份行）原样保留。
 *
 * @param turns 时间线（原地修改）
 * @return true 表示确实接管过，调用方需要刷新这一轮之外的投影
 */
export function absorbWaitingWorkflowCards(turns: ChatTurn[]): boolean {
  let changed = false
  for (let i = 0; i < turns.length; i += 1) {
    const turn = turns[i]
    if (!turn || turn.role !== 'assistant' || turn.continuation !== true) {
      continue
    }
    const fresh = (Array.isArray(turn.blocks) ? turn.blocks : [])
      .filter((block) => block?.kind === 'workflow' && !!block.workflow)
    for (const card of fresh) {
      const target = card.workflow as TrialWorkflowView
      const hostIndex = findWaitingCardHost(turns, i, target)
      if (hostIndex < 0) {
        continue
      }
      const host = turns[hostIndex]
      const waiting = waitingCardOf(host, target)
      /*
       * 只有「等待的那个节点在新卡里已经结束 / 正在跑」才算同一条运行续上了。
       * 若它变成 SKIPPED / PENDING，说明这句回复被当成新问题、新开了一条运行并跳过了询问；
       * 这时绝不能收旧卡，否则会把「真的又开了一条运行」藏起来。
       */
      if (!waiting || !continuedSameRun(waiting, target)) {
        continue
      }
      const takenAnswer = (target.answer || '').trim()
      const blocks = Array.isArray(host.blocks) ? host.blocks : []
      // 先定位旧卡：只有它之后的等待正文才跟着卡走，卡之前的入口 / 推理块一字不动
      const cardIndex = blocks.findIndex(
        (block) => block?.kind === 'workflow' && block.workflow && sameWorkflow(block.workflow, target)
      )
      if (cardIndex < 0) {
        continue
      }
      host.blocks = blocks.filter((block, index) => {
        if (index === cardIndex) {
          // 只拿掉这一张卡：同轮别的卡（例如入口卡）与后续轮次都不受影响
          return false
        }
        if (index <= cardIndex || block?.kind !== 'assistant') {
          return true
        }
        /*
         * 旧轮的等待正文与旧卡是同一份内容：卡片带正文时投影本来就把这段藏起来，
         * 卡被拿走后它会变成一段孤立正文（问句在续跑种子里已有一份）。
         * 只清卡片之后、且是纯正文的那几块；更早的文本属于入口 / 前序说明，保留。
         */
        if (takenAnswer) {
          return false
        }
        /*
         * 残缺卡没有正文可接：只把「等待你的回复」那句平台提示带走，
         * 否则它会以一条孤立说明的形式留在旧轮里，看起来像还在等。
         */
        return !(target.degraded && (block.text || '').includes('等待你的回复'))
      })
      // 已结算轮次的投影签名被缓存过：改块后必须让投影重算，否则旧卡还会留在页面上
      host.revision = (host.revision ?? 0) + 1
      changed = true
    }
  }
  return changed
}

/**
 * 找本轮之前最近一张「同一工作流、仍在等待回复」的卡所在轮。
 *
 * @param turns  时间线
 * @param before 续跑轮下标（只往前找）
 * @param target 续跑轮上的新卡（按编码 / 名字认同一张）
 * @return 旧卡的轮下标；没有为 -1
 */
function findWaitingCardHost(turns: ChatTurn[], before: number, target: TrialWorkflowView): number {
  for (let i = before - 1; i >= 0; i -= 1) {
    const turn = turns[i]
    if (!turn || turn.role !== 'assistant') {
      continue
    }
    for (const block of Array.isArray(turn.blocks) ? turn.blocks : []) {
      if (block?.kind === 'workflow' && block.workflow
        && sameWorkflow(block.workflow, target) && workflowWaitsReply(block.workflow)) {
        return i
      }
    }
  }
  return -1
}

/**
 * 取某一轮里「同一工作流、仍在等待回复」的那张卡。
 *
 * @param turn   候选轮（只认助手轮）
 * @param target 续跑轮上的新卡（按编码 / 名字认同一张）
 * @return 旧卡视图；没有为 undefined
 */
function waitingCardOf(turn: ChatTurn, target: TrialWorkflowView): TrialWorkflowView | undefined {
  if (!turn || turn.role !== 'assistant') {
    return undefined
  }
  for (const block of Array.isArray(turn.blocks) ? turn.blocks : []) {
    if (block?.kind === 'workflow' && block.workflow
      && sameWorkflow(block.workflow, target) && workflowWaitsReply(block.workflow)) {
      return block.workflow
    }
  }
  return undefined
}

/**
 * 续跑卡是否确实接着那张等待卡往下走（判定「同一条运行」的可证据化口径）。
 *
 * <p>等待中的节点在续跑后必然被回复收口：状态只会是 SUCCESS（已结束）或 RUNNING（正在跑）。
 * 若在新卡里找不到同编码节点，或它变成 SKIPPED / PENDING，就不是同一条运行的续跑——
 * 多半是这句回复被当成新问题、新运行直接跳过了询问。此时收掉旧卡会把问题藏起来。
 *
 * @param waiting 旧卡（仍在等待回复）
 * @param fresh   续跑轮上的新卡
 * @return true 表示同一个等待节点已被答复并继续
 */
function continuedSameRun(waiting: TrialWorkflowView, fresh: TrialWorkflowView): boolean {
  /*
   * 残缺卡（历史事件被截断）没有节点表可用：摘要反推出的终态就是证据——
   * 这一条运行确实已经结束在后续状态，同一条运行就被这句话续上了。
   * 不放行的话，上一轮的等待卡会留在页面上，看起来像又问了一遍。
   */
  if (fresh.degraded) {
    const status = (fresh.status || '').toUpperCase()
    return status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELLED'
  }
  const waitingCodes = (Array.isArray(waiting.nodes) ? waiting.nodes : [])
    .filter((node) => (node?.status || '').toUpperCase() === 'WAITING_REPLY')
    .map((node) => (node?.code || '').trim())
    .filter(Boolean)
  if (!waitingCodes.length) {
    return false
  }
  const freshNodes = Array.isArray(fresh.nodes) ? fresh.nodes : []
  return waitingCodes.every((code) => {
    const node = freshNodes.find((item) => (item?.code || '').trim() === code)
    const status = (node?.status || '').toUpperCase()
    return status === 'SUCCESS' || status === 'RUNNING'
  })
}

/**
 * 卡片是否仍停在「等待用户回复」：卡片头 WAITING_REPLY，或任一节点 WAITING_REPLY。
 *
 * <p>直播里节点先落等待、卡片头稍后才收口，只看卡片头会漏掉那一拍。
 *
 * @param view 工作流卡片视图
 * @return true 表示这张卡还在等人回复
 */
function workflowWaitsReply(view: TrialWorkflowView | undefined): boolean {
  if (!view) {
    return false
  }
  if ((view.status || '').toUpperCase() === 'WAITING_REPLY') {
    return true
  }
  return (Array.isArray(view.nodes) ? view.nodes : [])
    .some((node) => (node?.status || '').toUpperCase() === 'WAITING_REPLY')
}

/**
 * 本轮是否已有一张「同一工作流、仍停在等待回复」的卡。所属域：runtime（前端回放）。
 *
 * <p>工作流停等的回复不再落成用户消息，回放时续跑身份会紧跟在旧等待卡之后。
 * 用它识别这个轮边界：另起一张续作轮，旧卡才能被 {@link absorbWaitingWorkflowCards} 收走，
 * 等待正文也不会和续跑结果挤在同一条消息里。
 *
 * @param turn 当前助手轮；可空
 * @param who  刚读到的 participant begin 身份
 * @return true 表示这条身份是同一工作流的续跑，应当另起一轮
 */
function replayHasWaitingCard(turn: ChatTurn | undefined, who: ReplaySpeaker & { routeKey?: string }): boolean {
  if (!turn) {
    return false
  }
  const code = (who.routeKey || '').trim()
  const name = (who.name || '').trim()
  return (Array.isArray(turn.blocks) ? turn.blocks : []).some((block) => {
    if (block?.kind !== 'workflow' || !block.workflow || !workflowWaitsReply(block.workflow)) {
      return false
    }
    return code
      ? (block.workflow.code || '').trim() === code
      : (block.workflow.name || '').trim() === name
  })
}

/**
 * 本轮真正代表「入口判了一次路由」的文案。所属域：runtime（前端）。
 *
 * <p>为什么不能把所有 `route` 块都算进来：participant begin 帧也会被投影成一条
 * 「路由到 X」占位块（见 {@link participantBeginBlock}），它只负责把卡片挂到参与者，
 * 不是入口判定。等待回复续跑 / 点选项续作时，后端只推这条身份 begin，
 * 若把它当成入口路线，投影层就会凭空合成「智能网关 → X」「已分发给 X」——
 * 页面上看起来像是入口又新开了一条运行，实际仍是同一条。
 *
 * <p>新问题轮保留 begin 占位：那一轮本来就有入口身份帧，占位只是把目标名提前带出来，
 * 不会多造一次入口判定；续作轮才必须排除，因为整轮根本没有入口。
 *
 * @param blocks       本轮聊天块；可空
 * @param continuation 是否断点续作轮（等待回复续跑 / 点选项续作）
 * @return 入口投影可用的路由文案；续作轮排除 begin 占位
 */
export function entryRouteTextsOf(blocks: AssistantBlock[] | undefined, continuation = true): string[] {
  return (Array.isArray(blocks) ? blocks : [])
    .filter((block) => block && block.kind === 'route' && (!continuation || block.begin !== true))
    .map((block) => block.text)
}

/** 回放里当前说话人：来自 event_type=participant 的 begin 帧。depth 随帧保留，缺了不补。 */
type ReplaySpeaker = { id: string; name: string; kind: string; depth?: number }

/**
 * 回放轮次不存在时补一张已结束的助手卡。
 *
 * @param turns     已收好的对话
 * @param assistant 当前助手轮；已有则原样返回
 * @param eventId   用来生成卡片 key 的事件 id
 * @return 当前助手轮
 */
function ensureReplayTurn(turns: ChatTurn[], assistant: ChatTurn | undefined, eventId: number): ChatTurn {
  if (assistant) {
    return assistant
  }
  // 历史回放已是终态，phase 必须 done，禁止一直显示「任务规划中」
  const created = assistantTurn(`e-${eventId}`, 'done')
  created.phase = 'done'
  turns.push(created)
  return created
}

/**
 * 把上一条参与者身份贴到本块上。
 *
 * <p>入口身份可以贴到随后的判定推理上（入口块按 id 认领）。
 * 汇总身份不贴：那一段靠「方案结论：」标记进结论卡，贴到专家上会串块。
 * 路由行只在名字对得上当前参与者时贴，避免把上一位的 id 盖到下一位的「路由到」上。
 *
 * @param block  本事件转出的块
 * @param active 上一条 begin 身份；没有则本块保持无身份
 */
function carryReplayIdentity(block: AssistantBlock, active: ReplaySpeaker | undefined) {
  if (!active || block.participantId || active.kind === 'conclusion') {
    return
  }
  if (block.kind === 'route') {
    if (active.kind === 'gateway') {
      return
    }
    const name = parseRouteParticipant(block.text)?.name || ''
    if (!name || name !== active.name) {
      return
    }
    applyReplaySpeaker(block, active)
    return
  }
  // 工作流快照跟着当时的 begin 走；对不上卡片时投影再按名字 / 编码对
  if (block.kind === 'workflow') {
    applyReplaySpeaker(block, active)
    if (!block.participantKind) {
      block.participantKind = 'workflow'
    }
    return
  }
  if (
    block.kind !== 'reasoning' &&
    block.kind !== 'tool' &&
    block.kind !== 'assistant' &&
    block.kind !== 'file' &&
    block.kind !== 'choice' &&
    block.kind !== 'error'
  ) {
    return
  }
  applyReplaySpeaker(block, active)
}

/**
 * 把 begin 帧上的身份、kind、depth 贴到块上。已有的不覆盖。
 *
 * @param block  本事件转出的块
 * @param active 上一条 begin 身份
 */
function applyReplaySpeaker(block: AssistantBlock, active: ReplaySpeaker) {
  block.participantId = active.id
  block.participantName = active.name
  if (!block.participantKind && active.kind) {
    block.participantKind = active.kind
  }
  if (block.participantDepth == null && typeof active.depth === 'number') {
    block.participantDepth = active.depth
  }
}

/** 收尾事件类型：助手正文或失败说明。出现任一即表示这一轮已结束 */
const CLOSED_EVENT_TYPES = new Set(['assistant', 'error'])

/**
 * 计划事件里的「本轮已收束」状态。
 * 待批准（PENDING）、已取消（REJECTED）、已完成（DONE）都不会再有后续助手正文；
 * 只有 EXECUTING / FAILED 仍可能跟着步骤与终答，故不在此列。
 * 不加这条判断时，停在待批准的轮次会被当成「还在跑」而一直空转轮询。
 */
const CLOSED_PLAN_STATES = new Set(['PENDING', 'REJECTED', 'DONE'])

/**
 * 事件流是否还有未收尾的一轮。
 * 后端在本轮开始时只落 user；思考、工具、助手正文都要等本轮跑完才入库。
 * 因此判据是「最后一条 user 之后还没有 assistant / error」，而不是只看末尾事件类型——
 * 末尾若再跟一条 system（装配说明、子 Agent 失败提示），用末尾判断会把已结束的轮次误判成进行中。
 * @param events 同一会话已落库事件，顺序不限
 * @return true 表示还应继续续订
 */
export function hasOpenTurn(events: TrialEvent[]): boolean {
  const ordered = [...events].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  let open = false
  for (const item of ordered) {
    if (item.eventType === 'user') {
      open = true
    } else if (CLOSED_EVENT_TYPES.has(item.eventType)) {
      open = false
    } else if (item.eventType === 'plan' && CLOSED_PLAN_STATES.has(parsePlan(item.contentText)?.state || '')) {
      // 计划停在待批准/已取消/已完成：这一轮没有后续正文，不能再续订
      open = false
    }
  }
  return open
}

/**
 * 这张工作流卡此刻是否**真的**还在等用户回复（判定口径，不是历史识别口径）。所属域：runtime（前端）。
 *
 * <p>为什么不能只看卡片头：刷新回放补全（{@code WorkflowCardRebuilder.fill}）会按库里
 * 真实状态校准节点，但**卡片头保留骨架里那一刻的 {@code WAITING_REPLY}**——
 * 它当时确实在等，改它会让「同一条运行的续跑」认不出来。于是刷新落在续跑中途时，
 * 卡片头说「等待输入」，节点却已经全部走过询问、选项也已被清掉：
 * <ul>
 *   <li>任一节点仍是 {@code WAITING_REPLY}：用户确实还没答，必须按「等回复」处理；</li>
 *   <li>卡片头不是 {@code WAITING_REPLY}：本来就不在等；</li>
 *   <li>卡片头写等待、没有任何节点表（历史事件被截断的残缺卡）：没有反证，仍按等待处理；</li>
 *   <li>其余（节点都已非等待、选项已清）：这一拍是「已答复、正在续跑」，不算在等。</li>
 * </ul>
 *
 * <p>与 {@link workflowWaitsReply} 的区别：那一条要认「这是一张等待卡」（历史卡接管用），
 * 所以保留卡片头口径；这一条只回答「现在还要不要用户回答」，
 * 供续订判定与输入框「下一句是答复还是新问题」使用。
 *
 * @param view 工作流卡片视图；可空
 * @return true 表示仍需用户回复
 */
export function workflowStillWaitingForReply(view: TrialWorkflowView | undefined): boolean {
  if (!view) {
    return false
  }
  const nodes = Array.isArray(view.nodes) ? view.nodes : []
  // 任一节点在等待：不论卡片头写什么，都必须按「等回复」处理
  if (nodes.some((node) => (node?.status || '').toUpperCase() === 'WAITING_REPLY')) {
    return true
  }
  if ((view.status || '').toUpperCase() !== 'WAITING_REPLY') {
    return false
  }
  // 卡片头写等待但没有节点表：拿不到反证，按仍在等处理（不把残缺卡当成已答复）
  if (!nodes.length) {
    return true
  }
  // 卡片头停在等待、节点都不在等：只要选项还在，就说明用户还没答
  return !!view.choice
}

/**
 * 这张卡是否处于「询问已被答复、后续正在续跑」这一拍。所属域：runtime（前端判定）。
 *
 * <p>证据链（缺一不可，全部来自刀 B 校准后的骨架）：
 * <ol>
 *   <li>卡片头仍是骨架里的 {@code WAITING_REPLY}（历史语义，刀 B 有意不改它）；</li>
 *   <li>卡片不再带 {@code choice}、也没有任何节点在 {@code WAITING_REPLY}——询问已被答复；</li>
 *   <li>至少有节点表，且其中至少一个节点记着 {@code reply}（「我答了什么」的持久真源）。</li>
 * </ol>
 *
 * <p>为什么不要求「还有节点在跑」：运行刚收口、终态卡还没落库的那一拍，
 * 节点可能已经全部结束，但库里仍是这张等待骨架。若此时提前停轮询，页面就会
 * 停在「等待输入」上不再自愈。真正跑完的那张卡由后端写成 {@code SUCCESS} / {@code FAILED}，
 * 卡片头不再是 {@code WAITING_REPLY}，本条判据自然失效；轮询另有 330 秒硬顶兜底。
 *
 * @param view 工作流卡片视图；可空
 * @return true 表示询问已答复、这一轮还没落终态
 */
export function workflowReplyAnswered(view: TrialWorkflowView | undefined): boolean {
  if (!view || (view.status || '').toUpperCase() !== 'WAITING_REPLY' || view.choice) {
    return false
  }
  const nodes = Array.isArray(view.nodes) ? view.nodes : []
  if (!nodes.length) {
    return false
  }
  if (nodes.some((node) => (node?.status || '').toUpperCase() === 'WAITING_REPLY')) {
    return false
  }
  return nodes.some((node) => (node?.reply || '').trim().length > 0)
}

/**
 * 最后一条用户消息之后、最后一张工作流卡的视图。
 *
 * <p>按「最后一条 user」切段是这套判定的共同前提：更早轮次的卡不能把新一轮当成续跑。
 *
 * @param events 同一会话已落库事件（可含内存增量），顺序不限
 * @return 卡片视图；没有工作流卡时为 undefined
 */
function lastWorkflowCardOf(events: TrialEvent[]): TrialWorkflowView | undefined {
  const ordered = [...events].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
  let lastUser = -1
  for (let i = 0; i < ordered.length; i += 1) {
    if (ordered[i].eventType === 'user') {
      lastUser = i
    }
  }
  let card: TrialWorkflowView | undefined
  for (let i = lastUser + 1; i < ordered.length; i += 1) {
    const item = ordered[i]
    if (item.eventType !== 'workflow') {
      continue
    }
    // 同一条运行可能留下多张骨架：取最后一张，即刀 B 校准过的那张
    const view = parseWorkflowEvent(item.contentText, item.sessionId)
    if (view) {
      card = view
    }
  }
  return card
}

/**
 * 落库事件末尾是否处于「工作流点选已答复、运行仍在续跑」。所属域：runtime（前端续订判定）。
 *
 * <p>为什么需要它：{@link hasOpenTurn} 只认 assistant / error 收口。工作流点选的续跑
 * **不落 USER 事件**，续跑期间也不落任何事件，于是落库末尾正是那条
 * 「工作流正在等待你的回复」的 assistant —— 刷新后会被判成「这一轮已写完」，
 * 不续订 {@code /live-events}，页面停在回放上不会自愈。
 *
 * <p>只看工作流卡：MA 的方案 / 子专家停等不会命中，收口口径保持原样。
 *
 * @param events 同一会话已落库事件（可含内存增量），顺序不限
 * @return true 表示应当继续续订这一轮的后续进度
 */
export function workflowReplyResuming(events: TrialEvent[]): boolean {
  return workflowReplyAnswered(lastWorkflowCardOf(events))
}

/**
 * 已答复的续跑卡上是否还有节点在跑（PENDING / RUNNING）。
 *
 * <p>用途只有一个：轮询侧据此区分「运行确实在推进」与「运行刚结束、终态卡还没落库」。
 * 后者不该把整段 {@code RESUME_MAX_MS} 全耗在短轮询上。
 *
 * @param events 同一会话已落库事件
 * @return true 表示卡上还有未结束的节点
 */
export function workflowReplyProgressing(events: TrialEvent[]): boolean {
  const card = lastWorkflowCardOf(events)
  if (!workflowReplyAnswered(card)) {
    return false
  }
  return (card?.nodes || []).some((node) => {
    const status = (node?.status || '').toUpperCase()
    return status === 'PENDING' || status === 'RUNNING'
  })
}

/**
 * 把重进取回的时间线标成「进行中」：末尾是用户气泡就补一张进行中的助手卡，
 * 否则把最后一张助手卡改成 streaming。阶段按末尾事件类型推断，不新造协议。
 * @param turns   {@link turnsFromEvents} 回放结果
 * @param events  同一批落库事件，用来推断阶段与等待起点
 * @return 新的数组（末尾卡片被改为 streaming）
 */
export function markLiveTail(turns: ChatTurn[], events: TrialEvent[]): ChatTurn[] {
  const phase = phaseOfTail(tailEvent(events)?.eventType)
  const startTime = lastUserTime(events)
  const list = [...turns]
  const last = list[list.length - 1]
  if (!last || last.role !== 'assistant') {
    // 只有 user 落库时还没有助手卡，补一张空卡让粘性状态条有挂载点
    const live = assistantTurn('live-tail', 'streaming')
    live.phase = phase
    live.startTime = startTime
    list.push(live)
    return list
  }
  last.status = 'streaming'
  last.phase = phase
  last.startTime = startTime
  return list
}

/** seq 最大的一条；空数组返回 undefined。 */
function tailEvent(events: TrialEvent[]): TrialEvent | undefined {
  let tail: TrialEvent | undefined
  for (const item of events) {
    if (!tail || (item.seq ?? 0) >= (tail.seq ?? 0)) {
      tail = item
    }
  }
  return tail
}

/** 末尾事件换成粘性状态条文案的阶段码。 */
function phaseOfTail(type?: string) {
  if (type === 'tool') {
    return 'tool'
  }
  if (type === 'vision') {
    // 图片文字已提取、主模型还没开始：状态条应显示「识别图片文字」而不是「规划中」
    return 'vision'
  }
  if (type === 'preprocess') {
    return 'preprocess'
  }
  if (type === 'reasoning' || type === 'route') {
    return 'reasoning'
  }
  if (type === 'assistant') {
    return 'answering'
  }
  if (type === 'plan') {
    return 'planning'
  }
  if (type === 'step') {
    return 'tool'
  }
  return 'planning'
}

/**
 * 未收尾的一轮是否已经太旧。库里遗留的僵尸 RUNNING 不该让重进一直转，
 * 因此只在末尾事件足够新时自动续订；时间缺失或解析出未来时间（时区差异）时按「新」处理。
 * @param events   已落库事件
 * @param maxAgeMs 允许的最大静默时长
 * @return true 表示过旧，不要自动续订
 */
export function openTurnStale(events: TrialEvent[], maxAgeMs: number): boolean {
  const tail = tailEvent(events)
  const at = parseEventTime(tail?.createTime)
  if (!at) {
    return false
  }
  return Date.now() - at > maxAgeMs
}

/**
 * 等待起点：本轮最后一条 user 事件的落库时间。
 * 每轮轮询都会重放时间线，用落库时间当锚点，避免「已等待」每次刷新归零。
 */
function lastUserTime(events: TrialEvent[]): number {
  let best = 0
  let at = 0
  for (const item of events) {
    if (item.eventType !== 'user') {
      continue
    }
    const seq = item.seq ?? 0
    if (seq >= best) {
      best = seq
      at = parseEventTime(item.createTime)
    }
  }
  return at || Date.now()
}

/** 服务端时间可能是「yyyy-MM-dd HH:mm:ss」，统一成毫秒。 */
function parseEventTime(value?: string): number {
  if (!value) {
    return 0
  }
  const time = new Date(value.replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? 0 : time
}

/** 发送时先放一张进行中的助手卡片，等内容到达后再填。 */
export function assistantTurn(key: string, status: ChatTurn['status']): ChatTurn {
  return {
    key,
    role: 'assistant',
    text: '',
    blocks: [],
    phase: 'planning',
    status,
    startTime: Date.now()
  }
}

/**
 * 记下这一轮的结束说明。同一句不重复插。
 *
 * @param turn 助手轮
 * @param text 给人看的结束说明，例如「已中断」
 */
export function appendError(turn: ChatTurn, text: string) {
  const message = (text || '').trim()
  if (!message) {
    return
  }
  if (turn.blocks.some((block) => block.kind === 'error' && block.text === message)) {
    return
  }
  turn.blocks.push({ id: nextBlockId(turn), kind: 'error', text: message })
}

/** 连续的思考或回答拼进同一块，避免每个 token 一块。 */
export function appendText(
  turn: ChatTurn,
  kind: 'reasoning' | 'assistant',
  text: string,
  participantId?: string,
  participantName?: string
) {
  if (!text) {
    return
  }
  const last = turn.blocks[turn.blocks.length - 1]
  /*
   * 同类型且同一位参与者才合并：并行 / 交错时两位参与者的 token 会相邻到达，
   * 只按类型合并会把 B 的话接进 A 的块里（串块）。身份不同就另起一块。
   */
  if (last && last.kind === kind && (last.participantId || '') === (participantId || '')) {
    last.text += text
    return
  }
  turn.blocks.push({ id: nextBlockId(turn), kind, text, participantId, participantName })
}

/**
 * 参与者「开始」帧：该步一开始就补一条占位路由块，块随 begin 立刻出现，不等思考 / 工具。
 *
 * <p>为什么入口与汇总不在这里建块：入口块由判定推理 / 判定句建立，汇总由「方案结论：X」
 * 建立；给它们补占位路由行反而会多出一位不存在的专家。
 *
 * <p>真实路由行随后到达时由 {@link appendRoute} 原位补全这条块（补上「依据」），
 * 因此页面上不会出现同一参与者的两块。
 *
 * @param turn  目标助手轮
 * @param chunk 身份帧（只处理 begin=true）
 */
export function appendParticipantBegin(
  turn: ChatTurn,
  chunk: { id?: string; name?: string; kind?: string; routeKey?: string; depth?: number; begin?: boolean }
) {
  if (!chunk || chunk.begin !== true) {
    return
  }
  const id = (chunk.id || '').trim()
  const name = (chunk.name || '').trim()
  const kind = (chunk.kind || '').trim()
  if (!id || !name || kind === 'gateway' || kind === 'conclusion') {
    return
  }
  // depth 只在帧上真有数字时保留。缺了不猜，投影就不会给它认父
  const depth = typeof chunk.depth === 'number' ? chunk.depth : undefined
  // 同一位参与者重复 begin（重连 / 回放）：保留第一块，不重复出块
  const existing = turn.blocks.find(
    (block) => block.kind === 'route' && (block.participantId === id || routeNameOf(block.text) === name)
  )
  if (existing) {
    // 该参与者的路由行已经先到（单意图会在开跑前先推一条）：只补身份，不再造占位块
    existing.participantId = existing.participantId || id
    existing.participantName = existing.participantName || name
    if (!existing.participantKind && kind) {
      existing.participantKind = kind
    }
    if (existing.participantDepth == null && depth != null) {
      existing.participantDepth = depth
    }
    return
  }
  const key = (chunk.routeKey || '').trim()
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'route',
    text: key ? `路由到 ${name}（${key}）` : `路由到 ${name}`,
    participantId: id,
    participantName: name,
    participantKind: kind || undefined,
    participantDepth: depth,
    begin: true
  })
}

/**
 * 参与者终态：把「这一步结束了」落到时间线上。所属域：runtime（前端）。
 *
 * <p>为什么必须落块：进行态是按参与者算的，而身份写在 route 块上。没有终态，投影只能拿
 * 「整轮是否还在流式」当进行态——一位已经跑完的专家仍会被画成「正在作答」，一直转到整轮结束。
 *
 * <p>落法：
 * <ul>
 *   <li><b>done</b>：只给这条 route 块打上 closed；有终态说明（一个字都没产出时后端会给）才补一条正文；</li>
 *   <li><b>failed / aborted</b>：打 closed，并写一条带身份的错误行，卡片据此收口而不是继续转圈。</li>
 * </ul>
 * 同一参与者重复收到终态（并行多路 / 重连）只认第一次，避免错误行叠两条。
 *
 * @param turn  目标助手轮
 * @param chunk 终态身份帧（id / name / state / note）
 */
export function appendParticipantEnd(
  turn: ChatTurn,
  chunk: { id?: string; name?: string; state?: string; note?: string }
) {
  if (!turn || !chunk || !chunk.state) {
    return
  }
  const state = participantStateOf(chunk.state)
  const id = (chunk.id || '').trim()
  if (!state || !id) {
    return
  }
  const name = (chunk.name || '').trim()
  const routeBlock = turn.blocks.find(
    (block) => block.kind === 'route' && (block.participantId === id || routeNameOf(block.text) === name)
  )
  // 已经收过口：重复终态帧不再补错误行
  if (routeBlock?.closed) {
    return
  }
  if (routeBlock) {
    routeBlock.participantId = routeBlock.participantId || id
    routeBlock.participantName = routeBlock.participantName || name || undefined
    routeBlock.closed = state
  }
  const note = (chunk.note || '').trim()
  if (state === 'done') {
    /*
     * 正常结束：正文已经流过时什么都不补（终答就在卡上）。
     * 一个字都没产出才补一句终态说明——空结论也要终态，不能留一个一直转圈的空壳。
     */
    if (note) {
      turn.blocks.push({
        id: nextBlockId(turn),
        kind: 'assistant',
        text: note,
        participantId: id,
        participantName: name || undefined
      })
    }
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'error',
    text: note || (state === 'aborted' ? '本轮已中止' : '本轮执行失败'),
    participantId: id,
    participantName: name || undefined
  })
}

/**
 * 规范化参与者终态：只认三个值，其它（含历史数据）一律丢弃，不猜。
 *
 * @param raw 后端帧上的 state
 * @return 合法终态；不合法时 undefined
 */
function participantStateOf(raw: string): 'done' | 'failed' | 'aborted' | undefined {
  const value = (raw || '').trim().toLowerCase()
  if (value === 'done' || value === 'failed' || value === 'aborted') {
    return value
  }
  return undefined
}

/**
 * 图片文字提取结果。每轮只有一条，整段插入；重复到达时以最新为准（避免刷新后续订重复堆叠）。
 * @param turn 目标助手轮
 * @param text 识别文本
 */
export function appendVision(turn: ChatTurn, text: string) {
  const body = (text || '').trim()
  if (!body) {
    return
  }
  const existing = turn.blocks.find((block) => block.kind === 'vision')
  if (existing) {
    existing.text = body
    return
  }
  // 识别结果排在所有块之前：它是本轮的事实素材，读起来应当在思考与终答之前
  turn.blocks.unshift({ id: nextBlockId(turn), kind: 'vision', text: body })
}

/**
 * 入口预处理步骤。每轮只有一块，进行中 / 完成 / 失败原位覆盖，刷新后仍是同一份快照。
 *
 * @param turn 目标助手轮
 * @param view 预处理快照
 */
export function appendPreprocess(turn: ChatTurn, view: TrialPreprocessView) {
  if (!view) {
    return
  }
  const text = preprocessDisplayText(view)
  const existing = turn.blocks.find((block) => block.kind === 'preprocess')
  if (existing) {
    existing.text = text
    existing.preprocess = view
    return
  }
  turn.blocks.unshift({ id: nextBlockId(turn), kind: 'preprocess', text, preprocess: view })
}

/**
 * 过程里回显的短句：进行中用 hint，完成后用识别正文或摘要。
 *
 * <p>这是块上的原文，给回放解析用。对话框里的可读排版走 {@link presentPreprocess}，
 * 不把 PDF 抽出来的单字换行原样铺开。
 *
 * @param view 预处理快照
 * @return 展示文本
 */
export function preprocessDisplayText(view: TrialPreprocessView): string {
  if (view.status === 'running') {
    return (view.hint || '正在识别').trim()
  }
  return (view.text || view.summary || '').trim()
}

/** 回传摘要默认露出的前段字数。整份正文仍可展开，不一次铺满对话框 */
const PREPROCESS_PREVIEW_CHARS = 280

/** 默认露出的行数。和字数一起卡，长票面只留前段 */
const PREPROCESS_PREVIEW_LINES = 6

/**
 * 入口预处理在对话框里的样子。直播和回放都读这一份，不按页面各写一套。
 *
 * <p>title 是一行状态（正在抽字 / 已抽字并回传）。preview 是收起时的前段，
 * full 是横排后的全文。原文仍留在块上，这里只改给人看的换行。
 */
/** 识别框里的一条。按附件名排列，正文默认折叠 */
export interface PreprocessItem {
  /** 附件名，或图片序号、语音 */
  name: string
  /** 横排后的识别正文 */
  body: string
}

export interface PreprocessPresentation {
  /** 一行标题：正在抽字 / 已抽字并回传 / 已识图并回传 / 已转写并回传 / 失败 */
  title: string
  /** 收起时的前段；进行中为空 */
  preview: string
  /** 横排后的全文；进行中为空 */
  full: string
  /** 按附件拆开的条目。同一个框里按这个顺序排 */
  items: PreprocessItem[]
  /** 前段短于全文时才出现展开 */
  expandable: boolean
  /** 失败：正文是错误说明，不按抽字排版 */
  failed: boolean
  /** 还在抽字 / 识图 / 转写 */
  running: boolean
}

/**
 * 把预处理快照收成对话框里的一行标题 + 可展开前段。
 *
 * <p>只改展示。发给意图模型的原文、落库正文都不经过这里。
 * 没有快照时返回空，调用方不画空步骤。
 *
 * @param view 预处理快照；空则不展示
 * @return 展示结构；进行中只有标题
 */
export function presentPreprocess(view?: TrialPreprocessView | null): PreprocessPresentation | undefined {
  if (!view) {
    return undefined
  }
  const running = view.status === 'running'
  const failed = view.status === 'failed'
  const title = preprocessTitleOf(view)
  if (running) {
    return { title, preview: '', full: '', items: [], expandable: false, failed: false, running: true }
  }
  const raw = failed ? (view.summary || view.text || '').trim() : (view.text || view.summary || '').trim()
  const full = failed ? raw : reflowExtractedText(raw)
  const preview = clipPreprocessPreview(full)
  const items = failed
    ? (full ? [{ name: '识别失败', body: full }] : [])
    : splitPreprocessItems(raw)
  return {
    title,
    preview,
    full,
    items,
    expandable: preview !== full,
    failed,
    running: false
  }
}

/**
 * 一行标题。任务类型决定用抽字 / 识图 / 转写哪一句，不把摘要整段当标题。
 *
 * @param view 预处理快照
 * @return 标题
 */
function preprocessTitleOf(view: TrialPreprocessView): string {
  if (view.status === 'running') {
    return (view.hint || '正在识别附件').trim()
  }
  if (view.status === 'failed') {
    return '附件识别失败'
  }
  const tasks = Array.isArray(view.tasks) ? view.tasks : []
  const file = tasks.includes('file')
  const vision = tasks.includes('vision')
  const speech = tasks.includes('speech')
  if (file && !vision && !speech) {
    return '已抽字并回传'
  }
  if (vision && !file && !speech) {
    return '已识图并回传'
  }
  if (speech && !file && !vision) {
    return '已转写并回传'
  }
  if (tasks.length) {
    return '已识别并回传'
  }
  const summary = view.summary || ''
  if (summary.startsWith('已抽字')) {
    return '已抽字并回传'
  }
  if (summary.startsWith('已识图')) {
    return '已识图并回传'
  }
  if (summary.startsWith('已转写')) {
    return '已转写并回传'
  }
  return '已识别并回传'
}

/**
 * 把抽字碎片收成横排段落。
 *
 * <p>PDF 常把「备注销售方」抽成一行一字。这里把连续单字行拼回一句话，
 * 空行仍作段落。机器头【附件正文】不进预览，标题已经说明这是回传。
 *
 * @param raw 识别原文
 * @return 横排后的正文
 */
export function reflowExtractedText(raw: string): string {
  const lines = (raw || '').replace(/\r\n/g, '\n').split('\n')
  const out: string[] = []
  let singles: string[] = []
  const flush = () => {
    if (!singles.length) {
      return
    }
    out.push(singles.join(''))
    singles = []
  }
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) {
      flush()
      if (out.length && out[out.length - 1] !== '') {
        out.push('')
      }
      continue
    }
    if (trimmed === '【附件正文】' || trimmed === '【语音转写】' || trimmed === '【图片文字】') {
      flush()
      continue
    }
    // 一个字一行是抽字碎片，先攒起来再横排；正常句子原样留
    if ([...trimmed].length === 1) {
      singles.push(trimmed)
      continue
    }
    flush()
    out.push(trimmed)
  }
  flush()
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

/**
 * 把回传正文拆成按附件名排列的条目。
 *
 * <p>只认「文件「名」」、[图N内容] 和【语音转写】。这些标记前面的手打文字不进识别框。
 * 没有标记时收成一条，避免整段铺开。正文经过横排，不保留单字换行。
 *
 * @param raw 预处理原文
 * @return 按出现顺序的条目；空原文为空数组
 */
export function splitPreprocessItems(raw: string): PreprocessItem[] {
  const text = (raw || '').replace(/\r\n/g, '\n')
  const marks: { index: number; end: number; name: string }[] = []
  const fileRe = /文件「([^」]+)」：/g
  let match: RegExpExecArray | null
  while ((match = fileRe.exec(text))) {
    marks.push({
      index: match.index,
      end: match.index + match[0].length,
      name: match[1].trim() || '未命名文件'
    })
  }
  const imageRe = /\[图(\d+)内容\]:\s*/g
  while ((match = imageRe.exec(text))) {
    marks.push({
      index: match.index,
      end: match.index + match[0].length,
      name: `图片${match[1]}`
    })
  }
  const speechAt = text.indexOf('【语音转写】')
  if (speechAt >= 0) {
    marks.push({ index: speechAt, end: speechAt + '【语音转写】'.length, name: '语音' })
  }
  marks.sort((left, right) => left.index - right.index)
  if (!marks.length) {
    const body = reflowExtractedText(text)
    return body ? [{ name: '识别结果', body }] : []
  }
  const items: PreprocessItem[] = []
  for (let i = 0; i < marks.length; i += 1) {
    const stop = i + 1 < marks.length ? marks[i + 1].index : text.length
    const body = reflowExtractedText(text.slice(marks[i].end, stop))
    items.push({ name: marks[i].name, body })
  }
  return items
}

/**
 * 按用户发送顺序重排识别条目。直播和回放都拿用户消息上的附件清单，不另记一份顺序。
 *
 * <p>文件按名字对上；图片按出现次序对上「图片N」；录音对上「语音」。
 * 对不上的条目留在后面，不丢掉。
 *
 * @param items       从正文拆出的条目
 * @param attachments 本轮用户附件，按发送顺序
 * @return 排好的条目
 */
export function orderPreprocessItems(
  items: PreprocessItem[],
  attachments: { kind: string; name?: string }[]
): PreprocessItem[] {
  const source = Array.isArray(items) ? items : []
  const list = Array.isArray(attachments) ? attachments : []
  if (!source.length || !list.length) {
    return source
  }
  const pool = source.slice()
  const take = (match: (item: PreprocessItem) => boolean) => {
    const index = pool.findIndex(match)
    if (index < 0) {
      return undefined
    }
    return pool.splice(index, 1)[0]
  }
  const ordered: PreprocessItem[] = []
  let imageSeq = 0
  for (const attachment of list) {
    const name = (attachment.name || '').trim()
    if (attachment.kind === 'image') {
      imageSeq += 1
      const hit = take((item) => item.name === `图片${imageSeq}` || (!!name && item.name === name))
      if (hit) {
        ordered.push(name ? { ...hit, name } : hit)
      }
      continue
    }
    const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
    const audio = ['webm', 'wav', 'mp3', 'm4a', 'ogg', 'opus'].includes(ext)
    const hit = audio
      ? take((item) => item.name === name || item.name === '语音')
      : take((item) => item.name === name)
    if (hit) {
      ordered.push(name ? { ...hit, name } : hit)
    }
  }
  return [...ordered, ...pool]
}

/**
 * 把预处理挂到已有的入口卡上，不另起一张。
 *
 * <p>首页网关卡、MA 路由卡都走这里：先识别回传，再在同一张卡上留意图摘要。
 * 还没有入口卡时不造壳，调用方自己决定要不要补一张入口卡。
 *
 * @param cards     已有卡片
 * @param view      预处理快照；没有则原样返回
 * @param entryKind 入口卡的 kind（gateway / router）
 * @return 挂好的卡片，以及给人看的投影；shown 为空表示本轮没有预处理
 */
export function attachPreprocessToCard<T extends { kind: string; preprocess?: PreprocessPresentation }>(
  cards: T[],
  view: TrialPreprocessView | null | undefined,
  entryKind: string
): { cards: T[]; shown?: PreprocessPresentation } {
  const shown = presentPreprocess(view)
  if (!shown) {
    return { cards }
  }
  const index = cards.findIndex((card) => card.kind === entryKind)
  if (index < 0) {
    return { cards, shown }
  }
  const next = cards.slice()
  next[index] = { ...next[index], preprocess: shown }
  return { cards: next, shown }
}

/**
 * 前段截取。超行或超字才截，并补省略号，和全文区分开以便出现「展开」。
 *
 * @param full 横排全文
 * @return 默认露出的前段
 */
function clipPreprocessPreview(full: string): string {
  if (!full) {
    return ''
  }
  const lines = full.split('\n')
  const head = lines.slice(0, PREPROCESS_PREVIEW_LINES).join('\n')
  if (lines.length <= PREPROCESS_PREVIEW_LINES && head.length <= PREPROCESS_PREVIEW_CHARS) {
    return full
  }
  const cut = head.length > PREPROCESS_PREVIEW_CHARS ? head.slice(0, PREPROCESS_PREVIEW_CHARS) : head
  return `${cut.replace(/\n+$/, '')}…`
}

/** 工具按一次调用一块。参数行和结果分开。 */
export function appendTool(
  turn: ChatTurn,
  line: string,
  result?: string,
  participantId?: string,
  participantName?: string
) {
  const text = (line || '').trim()
  const detail = (result || '').trim()
  if (!text && !detail) {
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'tool',
    text: text || detail,
    detail: detail && detail !== text ? detail : undefined,
    participantId,
    participantName
  })
}

/**
 * 工具**开始**执行：追加一条占位块，等结果到达时原位补全。
 *
 * @param turn 目标助手轮
 * @param name 工具名
 * @param participantId   该工具属于哪一位参与者（可空：历史流）
 * @param participantName 参与者展示名（可空）
 */
export function appendToolStart(
  turn: ChatTurn,
  name: string,
  participantId?: string,
  participantName?: string
) {
  const tool = (name || '').trim()
  if (!tool) {
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'tool',
    text: '',
    toolName: tool,
    toolState: 'running',
    participantId,
    participantName
  })
}

/**
 * 工具执行结束：把最近一条同名「正在调用」块补成成功 / 失败。
 *
 * <p>为什么按名字找最近一条 running：同一轮里同名工具可能被连续调用多次，
 * 用「最后一条还在跑的」才对应得上引擎的完成顺序；找不到就退回追加一条普通结果块。
 *
 * @param turn     目标助手轮
 * @param name     工具名
 * @param line     展示行（含参数）
 * @param result   结果正文；可空
 * @param success  是否成功
 * @return true 表示已就地补全
 */
export function completeTool(
  turn: ChatTurn,
  name: string,
  line: string,
  result: string | undefined,
  success: boolean
): boolean {
  const tool = (name || '').trim()
  const text = (line || '').trim()
  const detail = (result || '').trim()
  for (let i = turn.blocks.length - 1; i >= 0; i -= 1) {
    const block = turn.blocks[i]
    if (block.kind !== 'tool' || block.toolState !== 'running') {
      continue
    }
    if (tool && (block.toolName || '').trim() !== tool) {
      continue
    }
    block.toolState = success ? 'done' : 'error'
    block.text = text || block.text || tool
    block.detail = detail && detail !== text ? detail : undefined
    block.toolArgsText = block.toolArgsText || text
    return true
  }
  return false
}

/**
 * 产物块：同一参与者写同名文件只保留最新一条。
 *
 * <p>为什么要去重：Agent 常分多次写同一个文件（先写骨架再追加正文），
 * 每次都会推一条 file_generated。若逐条追加，聊天区会刷出一排指向同一文件的卡片，
 * 用户既看不出区别、也点不到「正确」的那个。
 * 覆盖更新还能同步刷新体积——最终卡片展示的必然是最后一次写入后的大小。
 *
 * <p>去重键必须带参与者：两位专家各写一份「报告.md」是两份产物，不能互相覆盖，
 * 否则卡片会先挂 A 再改挂 B。身份也在这里落到块上，投影层不再按「最后一位」猜作者。
 *
 * @param turn            目标助手轮
 * @param file            产物视图（含后端拼好的 downloadUrl）
 * @param participantId   写出该文件的参与者 id（可空：旧事件 / 单专家轮次）
 * @param participantName 写出该文件的参与者展示名（可空）
 */
export function appendFile(
  turn: ChatTurn,
  file: TrialFileView,
  participantId?: string,
  participantName?: string
) {
  const name = (file.fileName || '').trim()
  if (!name) {
    return
  }
  const ownerId = (participantId || '').trim()
  const ownerName = (participantName || '').trim()
  const next: TrialFileView = { fileName: name, size: file.size, downloadUrl: file.downloadUrl }
  const existing = turn.blocks.find(
    (block) =>
      block.kind === 'file' &&
      block.file?.fileName === name &&
      (block.participantId || '').trim() === ownerId
  )
  if (existing) {
    // 原位覆盖：位置不变，用户已看到的卡片不会跳到别处
    existing.file = next
    existing.text = fileNameOf(next.fileName)
    // 身份只补不改：后续帧没带身份时保留首次落下的作者
    if (ownerId) {
      existing.participantId = ownerId
      existing.participantName = ownerName || existing.participantName
    }
    return
  }
  const block: AssistantBlock = { id: nextBlockId(turn), kind: 'file', text: fileNameOf(name), file: next }
  if (ownerId) {
    block.participantId = ownerId
    block.participantName = ownerName || undefined
  }
  turn.blocks.push(block)
}

/**
 * 结构化选项块：本轮需要用户拍板时补一条，chips 由它渲染。
 *
 * <p>为什么单独成块而不是塞回正文：正文里不能出现裸 JSON；而且它必须在刷新 / 回放
 * 时有确定位置（紧跟助手正文之后），投影层据此把 chips 挂到同一位参与者的气泡下方。
 *
 * <p>同一轮只保留一条：后端最多推一次，重连 / 重复回放时原位覆盖即可，不叠加空栏。
 *
 * @param turn        目标助手轮
 * @param choice      结构化选项（question + 2~6 项）
 * @param participantId   该选项属于哪一位参与者（可空：续作轮没有身份帧）
 * @param participantName 参与者展示名（可空）
 */
export function appendChoice(
  turn: ChatTurn,
  choice: TrialChoiceView,
  participantId?: string,
  participantName?: string
) {
  const options = Array.isArray(choice?.options) ? choice.options.filter((one) => !!one?.id && !!one?.label) : []
  const reply = (choice?.reply || '').trim()
  const waiting = choice?.waiting === true
  // 普通选项少于两项不出 chips；回复态没有选项但有「已答复」文案，必须留下这条记录
  if (options.length < 2 && !reply) {
    return
  }
  const text = (choice.question || '').trim()
  const view: TrialChoiceView = reply
    ? { question: text, options: [], reply }
    : { question: text, options, waiting }
  /*
   * 同一轮里可能有多位专家先后询问（方案续跑复用等待那一轮）：
   * 有身份时只覆盖**同一位**的旧选项块，不能把上一位已答复的「您的选择」抹掉。
   * 没有身份时沿用旧口径（只认第一块），单专家 / 工作流行为不变。
   */
  const ownerName = (participantName || '').trim()
  const ownerId = (participantId || '').trim()
  const existing = turn.blocks.find((block) => {
    if (block.kind !== 'choice') {
      return false
    }
    if (!ownerName && !ownerId) {
      return true
    }
    return (!!ownerName && (block.participantName || '').trim() === ownerName)
      || (!!ownerId && (block.participantId || '').trim() === ownerId)
  })
  if (existing) {
    existing.text = text
    existing.choice = view
    if (ownerId) {
      existing.participantId = ownerId
    }
    if (ownerName) {
      existing.participantName = ownerName
    }
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'choice',
    text,
    choice: view,
    participantId,
    participantName
  })
}

/**
 * 工作流执行卡片：把一份状态快照收成 `workflow` 块。
 *
 * <p>为什么原位覆盖而不是追加：卡片是**状态快照**（同一份时间轴从「运行中」变成
 * 「已完成」），追加会让页面上出现两遍同样的流程，刷新回放更是一串互相矛盾的旧状态。
 *
 * <p>识别同一个工作流按「编码优先、名字兜底」：同一轮里同名同编码只会有一条。
 *
 * @param turn 目标助手轮
 * @param raw  SSE 帧 / 落库正文解析出的快照；结构不完整时整块丢弃（不画空卡片）
 */
export function appendWorkflow(
  turn: ChatTurn,
  raw: WorkflowSnapshotInput | undefined,
  participantId?: string,
  participantName?: string,
  sessionId?: number
) {
  const workflow = normalizeWorkflow(raw, sessionId)
  if (!workflow) {
    return
  }
  const existing = turn.blocks.find((block) => block.kind === 'workflow' && sameWorkflow(block.workflow, workflow))
  if (existing) {
    /*
     * 正文只增不减：正文是从源端按节点增量长出来的，一份不带正文的旧快照
     * （收尾期乱序到达 / 重连重放）不得把已经流出的文字就地清空。
     * 只在「新快照确实带来了文字」时替换，避免「有文件就清空文字」互相打架。
     */
    const previousAnswer = (existing.workflow?.answer || '').trim()
    const nextAnswer = (workflow.answer || '').trim()
    if (!nextAnswer && previousAnswer) {
      workflow.answer = existing.workflow?.answer || ''
    }
    /*
     * 可点选项与正文同一纪律：只在「这一拍确实带了选项」时替换。
     * 停等之后的节点状态拍（引擎每迁一次节点就推一份快照）不会重复带 choice，
     * 若按整份覆盖，chips 会在问句下面闪一下就消失——用户只看到问句、点不到选项。
     * 反过来，续跑后卡片转 SUCCESS / RUNNING 时必须能清掉：那两态由后端显式不带 choice，
     * 且没有任何节点还在 WAITING_REPLY，再保留就是一组点不动的旧按钮。
     */
    const keepChoice = !workflow.choice && existing.workflow?.choice
    if (keepChoice) {
      const status = (workflow.status || '').toUpperCase()
      const waitingNode = (Array.isArray(workflow.nodes) ? workflow.nodes : []).some(
        (node) => (node?.status || '').toUpperCase() === 'WAITING_REPLY'
      )
      // 只认「仍在等待回复」：卡片头或任一节点处于 WAITING_REPLY 才留 chips
      if (status === 'WAITING_REPLY' || waitingNode) {
        workflow.choice = existing.workflow?.choice
      }
    }
    /*
     * 节点内过程同样只增不减：收尾期的旧快照 / 重连重放可能不带 steps/output/files，
     * 若整份覆盖，用户正在看的思考会闪空。只在旧节点确实有该字段时补齐。
     *
     * <p>对位必须带上轮次：同一节点在循环里会出现多行，只按编码对位会让第 2 轮
     * 拿到第 1 轮的正文，回放看起来就是「上一轮被覆盖了」。
     */
    const previousNodes = new Map(
      (existing.workflow?.nodes || []).map((node) => [
        `${(node.code || node.name || '').trim()}#${node.attempt && node.attempt > 0 ? node.attempt : 1}`,
        node
      ])
    )
    for (const node of workflow.nodes) {
      const previous = previousNodes.get(
        `${(node.code || node.name || '').trim()}#${node.attempt && node.attempt > 0 ? node.attempt : 1}`
      )
      if (!previous) {
        continue
      }
      if (!(node.output || '').trim() && (previous.output || '').trim()) {
        node.output = previous.output
      }
      if (!node.steps?.length && previous.steps?.length) {
        node.steps = previous.steps
      }
      if (!node.files?.length && previous.files?.length) {
        node.files = previous.files
      }
      // 用户回复同理：不带 reply 的后续快照不得把询问节点里已经记下的选择冲掉
      if (!(node.reply || '').trim() && (previous.reply || '').trim()) {
        node.reply = previous.reply
      }
    }
    /*
     * 时限同理：某些补帧（取消补状态 / 反向合并）不带 limits，
     * 直接整份覆盖会把卡片上的「上限 / 剩余」抹掉。只有新帧没带时限时才沿用上一份。
     */
    const previousLimits = existing.workflow?.limits
    existing.workflow = workflow
    if (!workflow.limits && previousLimits) {
      workflow.limits = previousLimits
    }
    if (!existing.participantId && participantId) {
      existing.participantId = participantId
    }
    existing.participantName = existing.participantName || participantName || workflow.name
    if (!existing.participantKind) {
      existing.participantKind = 'workflow'
    }
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'workflow',
    text: workflow.name || workflow.code,
    workflow,
    participantId: participantId || undefined,
    participantName: participantName || workflow.name || workflow.code,
    participantKind: 'workflow'
  })
}

/**
 * 两个卡片快照是否指向同一次工作流运行。
 *
 * @param left  已有块上的视图
 * @param right 新到的视图
 * @return true 表示同一块，应原位覆盖
 */
function sameWorkflow(left: TrialWorkflowView | undefined, right: TrialWorkflowView) {
  const code = (right.code || '').trim()
  if (code) {
    return (left?.code || '').trim() === code
  }
  return (left?.name || '').trim() === (right.name || '').trim()
}

/**
 * 卡片快照的**输入**形状：SSE 帧 / 落库 JSON / 内存快照回放都可能只带部分字段，
 * 因此这里按 unknown 收、逐字段校验，不信任任何上游。
 */
export interface WorkflowSnapshotInput {
  name?: unknown
  code?: unknown
  status?: unknown
  answer?: unknown
  nodes?: unknown
  /** 停等询问的可点选项（与 CHOICE 事件同形：question + options[{id,label}]） */
  choice?: unknown
  /** 节点上的可选字段（归一化时逐个校验） */
  type?: unknown
  note?: unknown
  output?: unknown
  steps?: unknown
  files?: unknown
  /** 用户对询问节点的回复（续跑后才有） */
  reply?: unknown
  /** 同一节点的第几轮（循环再次进入时每一轮各占一条） */
  attempt?: unknown
  /** 所属循环节点编码；不在循环体里为空串 */
  group?: unknown
  /** 循环节点已进入的第几轮 */
  loopRound?: unknown
  /** 循环节点配置的总次数 */
  loopTotal?: unknown
  /** 循环节点最近一次判定：BODY / EXIT / FAIL */
  loopDecision?: unknown
  /** 运行时限（整段 / 当前节点上限与各自截止，绝对时刻） */
  limits?: unknown
}

/**
 * 归一化卡片快照：只接受真实字段，状态一律大写，节点按图顺序。
 *
 * <p>边界：名字与编码都为空时整块丢弃——一张没有名字的卡片无法归属到任何工作流。
 *
 * @param raw 原始快照（SSE 帧 / 落库 JSON / 快照回放）
 * @return 归一化视图；不可用时为 undefined
 */
export function normalizeWorkflow(
  raw: WorkflowSnapshotInput | undefined,
  sessionId?: number
): TrialWorkflowView | undefined {
  if (!raw || typeof raw !== 'object') {
    return undefined
  }
  const name = typeof raw.name === 'string' ? raw.name.trim() : ''
  const code = typeof raw.code === 'string' ? raw.code.trim() : ''
  if (!name && !code) {
    return undefined
  }
  const rawNodes = Array.isArray(raw.nodes) ? raw.nodes : []
  const nodes: TrialWorkflowNodeView[] = rawNodes
    .filter((node) => !!node && typeof node === 'object')
    .map((node) => {
      const one = node as WorkflowSnapshotInput
      return {
        code: typeof one.code === 'string' ? one.code.trim() : '',
        name: typeof one.name === 'string' ? one.name.trim() : '',
        type: typeof one.type === 'string' ? one.type.trim().toUpperCase() : '',
        status: typeof one.status === 'string' && one.status.trim() ? one.status.trim().toUpperCase() : 'PENDING',
        note: typeof one.note === 'string' ? one.note.trim() : '',
        output: typeof one.output === 'string' ? one.output : '',
        steps: normalizeWorkflowSteps(one.steps),
        files: normalizeWorkflowNodeFiles(one.files, sessionId),
        // 续跑后询问节点上的用户回复；首次发起 / 非询问节点没有该字段
        reply: typeof one.reply === 'string' ? one.reply.trim() : '',
        /*
         * 循环展示字段：轮次与所属循环只用于把同一循环的多轮圈进一个框。
         * 缺省按「第 1 轮 / 不在循环里」，旧快照因此仍按原来那样平铺。
         */
        attempt: typeof one.attempt === 'number' && one.attempt > 0 ? Math.floor(one.attempt) : undefined,
        group: typeof one.group === 'string' ? one.group.trim() : '',
        loopRound: typeof one.loopRound === 'number' && one.loopRound > 0 ? Math.floor(one.loopRound) : 0,
        loopTotal: typeof one.loopTotal === 'number' && one.loopTotal > 0 ? Math.floor(one.loopTotal) : 0,
        loopDecision: typeof one.loopDecision === 'string' ? one.loopDecision.trim().toUpperCase() : ''
      }
    })
  return {
    name,
    code,
    status: typeof raw.status === 'string' && raw.status.trim() ? raw.status.trim().toUpperCase() : 'RUNNING',
    answer: typeof raw.answer === 'string' ? raw.answer : '',
    nodes,
    choice: normalizeWorkflowChoice(raw.choice),
    limits: normalizeWorkflowLimits(raw.limits)
  }
}

/**
 * 归一化卡片上的运行时限。
 *
 * <p>只要「整段截止」这一个硬条件：没有它就无法算剩余，整块丢弃（卡片退回不展示时限）。
 * 其余字段逐个校验类型，避免前端拿字符串做减法得出 NaN 分钟。
 *
 * @param raw limits 原始字段（SSE 帧 / 落库 JSON）
 * @return 时限视图；没有或不合格时为 undefined
 */
function normalizeWorkflowLimits(raw: unknown): TrialWorkflowLimitsView | undefined {
  if (!raw || typeof raw !== 'object') {
    return undefined
  }
  const one = raw as Record<string, unknown>
  const num = (value: unknown): number | undefined =>
    typeof value === 'number' && Number.isFinite(value) ? value : undefined
  const runDeadlineAt = num(one.runDeadlineAt)
  if (!runDeadlineAt || runDeadlineAt <= 0) {
    return undefined
  }
  const text = (value: unknown): string | undefined => {
    const str = typeof value === 'string' ? value.trim() : ''
    return str || undefined
  }
  return {
    runLimitMs: num(one.runLimitMs),
    runDeadlineAt,
    waitingAt: num(one.waitingAt),
    nodeCode: text(one.nodeCode) ?? null,
    nodeName: text(one.nodeName) ?? null,
    nodeLimitMs: num(one.nodeLimitMs) ?? null,
    nodeDeadlineAt: num(one.nodeDeadlineAt) ?? null
  }
}

/**
 * 归一化节点过程步：只认 REASONING / TOOL，空内容丢弃，顺序保持后端给的先后。
 *
 * @param raw 节点 steps 字段
 * @return 过程步；没有或结构不合格时为空表
 */
function normalizeWorkflowSteps(raw: unknown): TrialWorkflowStepView[] {
  if (!Array.isArray(raw)) {
    return []
  }
  const steps: TrialWorkflowStepView[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') {
      continue
    }
    const one = item as { type?: unknown; name?: unknown; content?: unknown; state?: unknown }
    const type = typeof one.type === 'string' ? one.type.trim().toUpperCase() : ''
    if (type !== 'REASONING' && type !== 'TOOL') {
      continue
    }
    const content = typeof one.content === 'string' ? one.content : ''
    if (type === 'REASONING' && !content.trim()) {
      continue
    }
    const state = typeof one.state === 'string' ? one.state.trim().toLowerCase() : ''
    steps.push({
      type,
      name: typeof one.name === 'string' ? one.name.trim() : '',
      content,
      state: state === 'running' || state === 'error' || state === 'done' ? state : type === 'TOOL' ? 'done' : undefined
    })
  }
  return steps
}

/**
 * 归一化节点文件行：只存文件名 / 体积，下载地址按当前会话现拼。
 *
 * <p>不把 downloadUrl 写进快照：会话复制或迁移后旧地址会失效，回放应读当前会话。
 *
 * @param raw       节点 files 字段
 * @param sessionId 会话主键；缺失时地址为空串，FileCard 会置灰
 * @return 文件视图；没有或结构不合格时为空表
 */
function normalizeWorkflowNodeFiles(raw: unknown, sessionId?: number): TrialFileView[] {
  if (!Array.isArray(raw)) {
    return []
  }
  const files: TrialFileView[] = []
  const seen = new Set<string>()
  for (const item of raw) {
    if (!item || typeof item !== 'object') {
      continue
    }
    const one = item as { fileName?: unknown; size?: unknown }
    const fileName = typeof one.fileName === 'string' ? one.fileName.trim() : ''
    if (!fileName || seen.has(fileName)) {
      continue
    }
    seen.add(fileName)
    const size = typeof one.size === 'number' && Number.isFinite(one.size) ? one.size : -1
    files.push({
      fileName,
      size,
      downloadUrl: sessionId ? buildDownloadUrl(sessionId, fileName) : ''
    })
  }
  return files
}

/**
 * 归一化卡片上的可点选项。
 *
 * <p>与 CHOICE 事件同一套边界（2~6 项、id / label 非空、id 不重复），不合格整份丢弃：
 * 宁可这一张卡没有芯片，也不渲染一组点了会对错人的按钮。
 *
 * @param raw 原始选项字段（SSE 帧 / 落库 JSON）
 * @return 选项视图；没有或不合格时为 undefined
 */
function normalizeWorkflowChoice(raw: unknown): TrialChoiceView | undefined {
  if (!raw || typeof raw !== 'object') {
    return undefined
  }
  const data = raw as { question?: unknown; options?: unknown }
  if (!Array.isArray(data.options)) {
    return undefined
  }
  const options: { id: string; label: string }[] = []
  for (const item of data.options) {
    if (!item || typeof item !== 'object') {
      return undefined
    }
    const one = item as { id?: unknown; label?: unknown }
    const id = typeof one.id === 'string' ? one.id.trim() : ''
    const label = typeof one.label === 'string' ? one.label.trim() : ''
    if (!id || !label) {
      return undefined
    }
    if (options.some((row) => row.id.toLowerCase() === id.toLowerCase())) {
      return undefined
    }
    options.push({ id, label })
  }
  if (options.length < 2 || options.length > 6) {
    return undefined
  }
  return { question: typeof data.question === 'string' ? data.question.trim() : '', options }
}

/**
 * 卡片标题用的文件名：只取末段，避免子目录把卡片撑宽。
 *
 * @param fileName 相对工作区根的路径
 * @return 末段文件名
 */
export function fileNameOf(fileName: string) {
  const normalized = (fileName || '').replace(/\\/g, '/')
  const slash = normalized.lastIndexOf('/')
  return slash >= 0 ? normalized.slice(slash + 1) : normalized
}

/**
 * 体积展示。与服务端 GeneratedFile#humanSize 口径一致，避免前后端两套数字。
 *
 * @param size 字节数；< 0 表示未知
 * @return 如 {@code 2.0 KB} / {@code 大小未知}
 */
export function formatFileSize(size: number) {
  if (!Number.isFinite(size) || size < 0) {
    return '大小未知'
  }
  if (size < 1024) {
    return `${size} B`
  }
  const kb = size / 1024
  if (kb < 1024) {
    return `${kb.toFixed(1)} KB`
  }
  return `${(kb / 1024).toFixed(1)} MB`
}

/**
 * 思考过程折叠区内的片段：路由 + 推理 + 工具，保持事件顺序。
 * 展示层合成一个「思考过程」头，不再按片段拆多个同名头。
 */
export function processBlocks(blocks: AssistantBlock[]): AssistantBlock[] {
  return blocks.filter((block) => block.kind === 'route' || block.kind === 'reasoning' || block.kind === 'tool')
}

/** 思考时间线一步。连续 reasoning 在入库/追加时已合并，这里不再按 token 拆。 */
export type ProcessStep = {
  type: 'route' | 'reasoning' | 'tool_call' | 'tool_result'
  title: string
  text: string
  /**
   * 工具块状态（仅 tool_call）：`running` 表示引擎已开始、结果还没回来。
   *
   * <p>缺省＝已完成。前端据此把「正在调用」画成蓝色胶囊，而不是等结果才出现。
   */
  state?: 'running' | 'done' | 'error'
}

/**
 * 多 Agent 思考分块。进行中与结束后同一结构，禁止收束拍扁成单时间线。
 * decision：父级完整路由列表；agent：标题固定「路由到 {名}」+ route_key，块内仅该子步骤。
 */
export type ProcessLane = {
  kind: 'decision' | 'agent' | 'note'
  /** 块标题。agent 为「路由到 {显示名}」 */
  title: string
  routeKey?: string
  /** 该泳道对应的参与者身份（begin 帧给的 id）；历史数据为空 */
  participantId?: string
  /**
   * begin 帧的 kind。方案 / 工作流 / 专家的父子只认它，不认候选池类型。
   * 历史泳道没有该字段时不回填。
   */
  participantKind?: string
  /**
   * begin 帧的 depth。更深的 scheme 才是父块。没有数字就不认父。
   */
  participantDepth?: number
  /** 决策 JSON / 依据 / 其它路由说明 */
  body?: string
  /**
   * 该参与者是否已经结束：done / failed / aborted（来自后端 participant 终态帧）。
   *
   * <p>缺省＝仍在进行。投影据此按人放进行态，而不是跟着整轮 streaming 一起转。
   */
  closed?: 'done' | 'failed' | 'aborted'
  steps: ProcessStep[]
}

/**
 * 按事件顺序拆成时间线（单意图或兼容旧数据）。
 * 多意图请用 {@link processLanes}，避免第二路塞进第一路依据。
 */
export function processSteps(blocks: AssistantBlock[]): ProcessStep[] {
  const steps: ProcessStep[] = []
  for (const lane of processLanes(blocks)) {
    if (lane.kind === 'decision' || lane.kind === 'note') {
      if (lane.body?.trim()) {
        steps.push({ type: 'route', title: lane.title, text: lane.body.trim() })
      }
      continue
    }
    const meta: string[] = []
    // route_key 是内部定位键，不对终端用户展示（工作台 / 运行气泡）
    if (lane.body?.trim()) {
      meta.push(lane.body.trim())
    }
    steps.push({ type: 'route', title: lane.title, text: meta.join('\n') })
    steps.push(...lane.steps)
  }
  return steps
}

/**
 * 调试皮肤把「没有参与者身份、又没被任何泳道接住」的推理和工具收成的那一条标题。
 *
 * <p>只给 {@link lanesForDebugSkin} 使用。工作台投影不读它，因此不会多出一张匿名卡。
 */
export const DEBUG_THINK_TITLE = '思考过程'

/**
 * 多路由按子 Agent 分块；单路由仍一块。
 * 一条事件里用分号拼起来的多段「路由到」，仍按段切开。
 *
 * <p>参与者卡只从带身份的 begin / 路由行建立。一个 begin id 全程只有一条泳道。
 * 思考和工具只追加到这条已有泳道上；begin 还没到的增量按 id 暂存，等这条泳道出现再并进去。
 * 没有 begin 的块不按名字开卡。没有身份的推理 / 工具也不在这里收成匿名「执行」泳道。
 *
 * <p>「方案结论：」之后的增量不进任何参与者泳道。它们属于这一条汇总记录，
 * 不写回方案卡（否则同一段正文会出现两次），也不按块上的名字再开一张。
 *
 * <p>单 Agent 调试皮肤要看见这些被丢掉的过程，走 {@link lanesForDebugSkin}，不要放宽本函数。
 */
export function processLanes(blocks: AssistantBlock[]): ProcessLane[] {
  return projectProcessLanes(blocks).lanes
}

/**
 * 调试皮肤的思考泳道。所属域：runtime（前端投影）。
 *
 * <p>工作台继续用 {@link processLanes}：无身份过程不建卡。
 * 调试皮肤的「思考过程」折叠只认泳道是否非空，单 Agent 又没有 begin 帧，
 * 所以这里在严格投影之后，把被丢掉的无身份推理和工具收成一条，标题固定为
 * {@link DEBUG_THINK_TITLE}。已挂到某位参与者身上的步骤不重复收。
 *
 * @param blocks 一轮助手块（含终答；终答不会进这条泳道）
 * @return 严格泳道；若确有被丢掉的过程，末尾再加一条「思考过程」
 */
/**
 * 调试皮肤里，哪一块才是这一轮的终答。
 * 后面还有工具或推理的助手正文是中途说明，下标为 -1，表示现在还没有终答。
 *
 * @param blocks 一轮助手块
 * @return 终答块下标；没有则 -1
 */
export function finalAnswerIndex(blocks: AssistantBlock[]): number {
  let last = -1
  for (let i = 0; i < blocks.length; i += 1) {
    if (blocks[i].kind === 'assistant' && (blocks[i].text || '').trim()) {
      last = i
    }
  }
  if (last < 0) {
    return -1
  }
  for (let i = last + 1; i < blocks.length; i += 1) {
    const kind = blocks[i].kind
    if (kind === 'tool' || kind === 'reasoning' || kind === 'step') {
      return -1
    }
  }
  return last
}

/**
 * 调试皮肤把工具前的助手正文收进思考。终答那一块保持原样，避免同一段既在推理里又当终稿。
 * 工作台不走这里。
 *
 * @param blocks 一轮助手块
 * @return 给思考泳道用的块；没有中途说明时返回原数组
 */
export function foldInterimAnswers(blocks: AssistantBlock[]): AssistantBlock[] {
  const finalIndex = finalAnswerIndex(blocks)
  let changed = false
  const next = blocks.map((block, index) => {
    if (block.kind === 'assistant' && index !== finalIndex) {
      changed = true
      return { ...block, kind: 'reasoning' as const }
    }
    return block
  })
  return changed ? next : blocks
}

export function lanesForDebugSkin(blocks: AssistantBlock[]): ProcessLane[] {
  const projected = projectProcessLanes(blocks)
  const steps: ProcessStep[] = []
  for (const block of projected.dropped) {
    appendBlockSteps(steps, block)
  }
  if (!steps.length) {
    return projected.lanes
  }
  return [
    ...projected.lanes,
    {
      kind: 'note',
      title: DEBUG_THINK_TITLE,
      steps
    }
  ]
}

/**
 * 与 {@link processLanes} 同一次扫描。`dropped` 只记录没有身份、且没有当前参与者可挂的推理 / 工具。
 * 调用方不得把这份列表再塞回工作台建卡。
 *
 * @param blocks 一轮里会进入思考时间线的块来源
 * @return 严格泳道，以及被严格规则丢掉的过程块（保持到达顺序）
 */
function projectProcessLanes(blocks: AssistantBlock[]): { lanes: ProcessLane[]; dropped: AssistantBlock[] } {
  const lanes: ProcessLane[] = []
  const dropped: AssistantBlock[] = []
  let current: ProcessLane | undefined
  // 身份 → 泳道。思考 / 工具按参与者 id 归位，不按「最后一条 route 文案」认人。
  const byParticipant = new Map<string, ProcessLane>()
  // begin 还没到的思考 / 工具，按 id 暂存。对上 begin 再并进那一条，对不上也不按名字建卡。
  const heldSteps = new Map<string, ProcessStep[]>()
  // 身份帧还没到时先记下的路由依据，对上同名身份泳道后再并进去；对不上就丢掉，不据此建卡。
  const pendingRoutes: ProcessLane[] = []
  /*
   * 是否处于「汇总」阶段：`方案结论：X` 之后的过程事件属于汇总那条独立记录。
   * 不能回填到上一位子专家，也不能因为原泳道被暂停查找就按名字再开一张。
   */
  let conclusionPhase = false

  for (const block of processBlocks(blocks)) {
    if (block.kind === 'route') {
      for (const piece of splitRoutePieces(block.text)) {
        // 汇总标记不是参与者：从这里起，后续过程事件不再归属上一位
        if (piece.startsWith('方案结论：')) {
          conclusionPhase = true
          current = undefined
          continue
        }
        const lane = routeLane(piece)
        if (!lane) {
          continue
        }
        // 终态随身份行一起到达：泳道据此退出进行态（整轮还在跑，但这一位已经结束）
        if (block.closed) {
          lane.closed = block.closed
        }
        // kind / depth 跟 begin 块走，后面归组只读泳道上的这两项
        copyParticipantShape(lane, block)
        if (lane.kind !== 'agent') {
          // 决策 / 说明不是参与者，入列但不成为当前作者
          lanes.push(lane)
          current = undefined
          continue
        }
        conclusionPhase = false
        const existing = findAgentLane(lanes, lane)
        // 同名且是同一位才并进已有泳道。另一位即使展示名碰巧相同，也不能把已贴上的身份改挂过去。
        const samePerson = !!existing?.participantId
          && (!block.participantId || existing.participantId === block.participantId)
        if (existing && samePerson) {
          if (!existing.body && lane.body) {
            existing.body = lane.body
          }
          if (block.participantId) {
            existing.participantId = block.participantId
            byParticipant.set(block.participantId, existing)
            // begin 之前先到的思考 / 工具并进这一张，不另开
            absorbHeldSteps(existing, heldSteps, block.participantId)
          }
          // 终态只增不减：先到的 done 不会被后到的旧数据抹掉
          if (!existing.closed && block.closed) {
            existing.closed = block.closed
          }
          copyParticipantShape(existing, block)
          current = existing
          continue
        }
        if (!block.participantId) {
          // 没有身份：不新建参与者，只暂存依据
          rememberPendingRoute(pendingRoutes, lane)
          continue
        }
        lane.participantId = block.participantId
        copyParticipantShape(lane, block)
        const pending = takePendingRoute(pendingRoutes, lane)
        if (pending?.body && !lane.body) {
          lane.body = pending.body
        }
        absorbHeldSteps(lane, heldSteps, block.participantId)
        lanes.push(lane)
        byParticipant.set(block.participantId, lane)
        current = lane
      }
      continue
    }
    /*
     * 汇总标记之后：思考 / 工具只留给「方案结论：」那一条记录。
     * 不写回已经 begin 的方案或子专家（分流卡保持原样，正文也不会写第二遍），
     * 也不按块上的名字再开泳道。
     */
    if (conclusionPhase) {
      // 汇总之后的过程不进参与者泳道；调试皮肤稍后从 dropped 收成一条思考过程
      dropped.push(block)
      continue
    }
    const id = (block.participantId || '').trim()
    const begun = id ? byParticipant.get(id) : undefined
    if (begun) {
      // 同一个 begin id：后续增量回到这一张，不再新开
      current = begun
      appendBlockSteps(current.steps, block)
      continue
    }
    if (id) {
      // 还没有 begin：按 id 暂存。名字不能单独成为一张卡。
      holdUntilBegin(heldSteps, block)
      current = undefined
      continue
    }
    // 没有身份的过程事件不建卡。只有当前已经是某位参与者时才顺进去，避免挂错人之后又造一张。
    // 丢掉的块交给调试皮肤，不在这里补匿名「执行」泳道。
    if (!current || current.kind !== 'agent') {
      dropped.push(block)
      continue
    }
    appendBlockSteps(current.steps, block)
  }
  // 全程没有 begin 接住的过程：不按名字开卡，只留在最前给入口思考认领
  promoteUnbegunEntry(lanes, heldSteps)
  return { lanes, dropped }
}

/**
 * 把一条思考或工具块写进已有步骤。不新建泳道。
 *
 * @param steps 目标步骤（已有泳道或 begin 前的暂存）
 * @param block 思考 / 工具块
 */
function appendBlockSteps(steps: ProcessStep[], block: AssistantBlock) {
  if (block.kind === 'reasoning') {
    // 同组内连续推理合并为一步（防重推路由把流式拆成一词一步）
    appendReasoningStep(steps, block.text)
    return
  }
  const line = (block.text || '').trim()
  const result = (block.detail || '').trim()
  // 工具开始、结果还没回来：只记一条 running 胶囊，工具名在 toolName 上
  if (block.toolState === 'running' && !line) {
    const name = (block.toolName || '').trim()
    if (name) {
      steps.push({ type: 'tool_call', title: `调用工具 ${name}`, text: '', state: 'running' })
    }
    return
  }
  if (line) {
    steps.push({
      type: 'tool_call',
      title: toolCallTitle(line),
      text: toolCallBody(line),
      state: block.toolState === 'error' ? 'error' : 'done'
    })
  }
  if (result) {
    steps.push({ type: 'tool_result', title: '工具结果', text: result })
  }
}

/**
 * begin 还没出现时，按参与者 id 暂存思考 / 工具。
 *
 * @param held  id → 暂存步骤
 * @param block 带身份、但还没有泳道的块
 */
function holdUntilBegin(held: Map<string, ProcessStep[]>, block: AssistantBlock) {
  const id = (block.participantId || '').trim()
  if (!id) {
    return
  }
  const bucket = held.get(id) || []
  appendBlockSteps(bucket, block)
  if (bucket.length) {
    held.set(id, bucket)
  }
}

/**
 * 把某 id 在 begin 之前暂存的步骤并进它的唯一泳道，然后清掉暂存。
 *
 * @param lane 已经按 begin 建立的泳道
 * @param held 暂存
 * @param id   begin id
 */
function absorbHeldSteps(lane: ProcessLane, held: Map<string, ProcessStep[]>, id: string) {
  const steps = held.get(id)
  if (!steps?.length) {
    return
  }
  lane.steps.push(...steps)
  held.delete(id)
}

/**
 * 没有任何 begin 接住的过程，收成标题不是「路由到」的泳道，放在最前。
 *
 * <p>入口判定没有参与者 begin。这些推理只能给入口块认领，不能变成「路由到 {名}」的参与者卡。
 * 已经 begin 过的 id 不会留在这里。
 *
 * @param lanes 已建立的泳道（就地把入口思考插到最前）
 * @param held  仍未被 begin 接住的步骤
 */
function promoteUnbegunEntry(lanes: ProcessLane[], held: Map<string, ProcessStep[]>) {
  const entryLanes: ProcessLane[] = []
  for (const [id, steps] of held) {
    if (!steps.length) {
      continue
    }
    entryLanes.push({
      kind: 'agent',
      title: '执行',
      participantId: id,
      steps
    })
  }
  if (entryLanes.length) {
    lanes.unshift(...entryLanes)
  }
}

/**
 * 同名同 key 的参与者泳道。身份帧与稍后的「路由到」要并进同一块。
 *
 * @param lanes 已建立的泳道
 * @param lane  待并入的参与者块
 * @return 已有块；没有时为 undefined
 */
function findAgentLane(lanes: ProcessLane[], lane: ProcessLane): ProcessLane | undefined {
  return lanes.find(
    (item) => item.kind === 'agent' && item.title === lane.title && (item.routeKey || '') === (lane.routeKey || '')
  )
}

/**
 * 暂存一条还没有身份的路由依据。同名同 key 只留一份，后到的依据补进空位。
 *
 * @param pending 暂存列表
 * @param lane    本条路由
 */
function rememberPendingRoute(pending: ProcessLane[], lane: ProcessLane) {
  const existing = findAgentLane(pending, lane)
  if (!existing) {
    pending.push(lane)
    return
  }
  if (!existing.body && lane.body) {
    existing.body = lane.body
  }
}

/**
 * 取出并删除与身份泳道同名的暂存依据。
 *
 * @param pending 暂存列表
 * @param lane    已有身份的参与者块
 * @return 暂存依据；没有时为 undefined
 */
function takePendingRoute(pending: ProcessLane[], lane: ProcessLane): ProcessLane | undefined {
  const index = pending.findIndex(
    (item) => item.title === lane.title && (item.routeKey || '') === (lane.routeKey || '')
  )
  if (index < 0) {
    return undefined
  }
  const [found] = pending.splice(index, 1)
  return found
}

/**
 * 旧数据可能把多条「路由到」用中文分号拼成一条；新数据一条事件一段。
 *
 * <p>固定模板 / 串行模板的子块标题是「固定并行 1/2 · 某专家（key）」这一类，
 * 也要按分号切开，否则多个子会被当成一段说明。
 */
function splitRoutePieces(raw: string): string[] {
  const text = (raw || '').trim()
  if (!text) {
    return []
  }
  if (text.startsWith('路由决策')) {
    return [text]
  }
  return text
    .split(/；(?=路由到|固定并行|固定串行|路由后串行|竞争选优)/)
    .map((part) => part.trim())
    .filter(Boolean)
}

/**
 * 固定模板 / 串行模板的子块标题解析。
 *
 * <p>这些子块不是判路由出来的，但同样是「这一位参与者开始作答」的真实事件：
 * 后端写成「固定并行 1/2 · 制度知识专家（policy）」。只把它当说明句，
 * 消息流就会看不见这些专家。这里归一成与「路由到 X」同构的参与者块。
 *
 * <p>必须用**窄口径**（只在「固定并行 / 固定串行 / 路由后串行 / 竞争选优」前缀上匹配）：
 * 宽口径会把「路由到 X（key），依据：…」整句一起吃掉，`依据` 永远进不了 {@code lane.body}，
 * 方案卡的内容框就只剩一句状态话。
 *
 * @param raw 一行路由摘要
 * @return 子块标题解析结果；不是模板子块时为 undefined
 */
function parseChildTitle(raw: string): { name: string; key: string } | undefined {
  return parseFixedChildTitle(raw)
}

/**
 * 把落库/SSE 的路由摘要收成一块：决策 / 子 Agent / 其它说明。
 */
function routeLane(raw: string): ProcessLane | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  if (text.startsWith('路由决策')) {
    const body = text.replace(/^路由决策\s*/, '').trim()
    return { kind: 'decision', title: '路由决策', body: body || text, steps: [] }
  }
  // 固定模板 / 串行模板的子块：同样是某位专家开始作答，归一成参与者块
  const child = parseChildTitle(text)
  if (child?.name) {
    return {
      kind: 'agent',
      title: `路由到 ${child.name}`,
      routeKey: child.key || undefined,
      steps: []
    }
  }
  const matched = /^路由到\s*(.+?)(?:（([^）]*)）)?(?:，依据[：:]\s*(.*))?$/s.exec(text)
  if (!matched) {
    return { kind: 'note', title: '路由', body: text, steps: [] }
  }
  const name = (matched[1] || '').trim().replace(/，$/, '')
  const key = (matched[2] || '').trim()
  const reason = (matched[3] || '').trim()
  const title = name ? `路由到 ${name}` : '路由到'
  const lines: string[] = []
  if (reason) {
    lines.push(`依据：${reason}`)
  }
  return {
    kind: 'agent',
    title,
    routeKey: key || undefined,
    body: lines.join('\n') || undefined,
    steps: []
  }
}

/**
 * 流式收到路由事件时追加一块；多意图可连续多条「路由到…」。
 *
 * <p>若该参与者在 begin 时已补过占位路由行（只有名字与 route_key），这里**原位补全**
 * 这条块（补上「依据」），不另起一条——同一个参与者只会出一块。
 *
 * <p>并行子重推本子路由：只要「最近一条路由」已是同文案（中间可夹推理/工具），就跳过，
 * 避免打断 appendText 合并。
 *
 * @param turn    目标助手轮
 * @param summary 路由摘要原文
 * @param participantId   当前归属身份（可空：历史流 / 决策块）
 * @param participantName 当前归属展示名（可空）
 * @param begin           true 表示这是 begin 占位行，稍后的真实路由要原位补全
 */
export function appendRoute(
  turn: ChatTurn,
  summary: string,
  participantId?: string,
  participantName?: string,
  begin?: boolean,
  identity?: { participantKind?: string; participantDepth?: number }
) {
  const text = (summary || '').trim()
  if (!text) {
    return
  }
  // 身份只在「这条路由确实是这位参与者」时写入，避免把入口的 kind / depth 贴到决策行上
  const sameOwner = routeNameOf(text) !== '' && routeNameOf(text) === (participantName || '').trim()
  // 同一位已经有路由行（begin 占位，或回放里身份已经贴上）：原位补全依据，不另起一块
  const placeholder = text.startsWith('路由到')
    ? [...turn.blocks].reverse().find((block) => block.kind === 'route'
      && sameRouteTarget(block.text, text)
      && (block.begin === true || (!!participantId && block.participantId === participantId)))
    : undefined
  if (placeholder) {
    placeholder.text = text
    placeholder.begin = false
    if (!placeholder.participantId && participantId && sameOwner) {
      placeholder.participantId = participantId
    }
    if (!placeholder.participantName && participantName && sameOwner) {
      placeholder.participantName = participantName
    }
    if (sameOwner) {
      copyParticipantShape(placeholder, identity)
    }
    return
  }
  const active = latestRouteText(turn.blocks)
  if (active === text) {
    return
  }
  turn.blocks.push({
    id: nextBlockId(turn),
    kind: 'route',
    text,
    participantId: sameOwner ? participantId : undefined,
    participantName: sameOwner ? participantName : undefined,
    participantKind: sameOwner ? identity?.participantKind : undefined,
    participantDepth: sameOwner ? identity?.participantDepth : undefined,
    begin: begin === true ? true : undefined
  })
}

/**
 * 把 begin 上的 kind / depth 补到已有块。已有值不覆盖。
 *
 * @param target 路由块或泳道
 * @param source kind / depth；可空
 */
function copyParticipantShape(
  target: { participantKind?: string; participantDepth?: number },
  source?: { participantKind?: string; participantDepth?: number }
) {
  if (!source) {
    return
  }
  if (!target.participantKind && source.participantKind) {
    target.participantKind = source.participantKind
  }
  if (target.participantDepth == null && typeof source.participantDepth === 'number') {
    target.participantDepth = source.participantDepth
  }
}

/**
 * 两条路由行是否指向同一位参与者（名字相同；双方都有 key 时 key 也必须相同）。
 *
 * @param left  已有路由行
 * @param right 新到的路由行
 * @return true 表示可以原位补全
 */
function sameRouteTarget(left: string, right: string): boolean {
  const a = parseRouteParticipant(left)
  const b = parseRouteParticipant(right)
  if (!a?.name || !b?.name || a.name !== b.name) {
    return false
  }
  const leftKey = (a.key || '').trim()
  const rightKey = (b.key || '').trim()
  return !leftKey || !rightKey || leftKey === rightKey
}

/**
 * 路由行里的展示名；解析不出时为空串（决策块 / 说明行不算参与者）。
 *
 * @param raw 路由行
 * @return 展示名
 */
function routeNameOf(raw: string): string {
  return (parseRouteParticipant(raw)?.name || '').trim()
}

/**
 * 从块列表末尾往前找最近一条路由正文；没有则空串。
 * 中间的推理/工具不改变「当前活动子路由」，供 laneBound 重推去重。
 */
function latestRouteText(blocks: AssistantBlock[]): string {
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    const block = blocks[i]
    if (block.kind === 'route') {
      return (block.text || '').trim()
    }
  }
  return ''
}

/**
 * 同 lane 连续推理追加到上一步；工具/路由边界后开新「推理」步。
 * 空正文忽略；首段 trim 两端，续段保留原增量空白以免吃掉词间空格。
 */
function appendReasoningStep(steps: ProcessStep[], raw: string) {
  if (!(raw || '').trim()) {
    return
  }
  const last = steps[steps.length - 1]
  if (last && last.type === 'reasoning') {
    last.text += raw
    return
  }
  steps.push({ type: 'reasoning', title: '推理', text: raw.trim() })
}

/** 从「调用工具 name，参数 …」里抽出标题，失败后缀仍挂在 name 后。 */
function toolCallTitle(line: string): string {
  const failed = /·\s*失败/.test(line)
  const matched = /^调用工具\s+([^\s，,·]+)/.exec(line)
  if (matched) {
    return failed ? `调用工具 ${matched[1]} · 失败` : `调用工具 ${matched[1]}`
  }
  return failed ? '调用工具 · 失败' : '调用工具'
}

/** 参数正文；没有参数时给一行说明，避免步骤空壳。 */
function toolCallBody(line: string): string {
  const cleaned = line.replace(/\s*·\s*失败\s*$/, '')
  const idx = cleaned.indexOf('，参数 ')
  if (idx >= 0) {
    return cleaned.slice(idx + '，参数 '.length).trim() || '（无参数）'
  }
  const stripped = cleaned.replace(/^调用工具\s+[^\s，,·]+\s*/, '').trim()
  return stripped || '（无参数）'
}

/**
 * 思考正文按二级标题切开；终态与终答同一套 renderSimpleMarkdown（加粗/列表/表格）。
 * 流式过程由 ChatPanel 走 renderStreamingMarkdown，避免半截表刷源码。
 * 「分析推理」段单独浅底，便于和普通思考区分。
 * @param source 引擎给出的思考
 */
export function reasoningSections(source: string): { tone: 'plain' | 'analysis'; html: string }[] {
  const text = (source || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim()
  if (!text) {
    return []
  }
  return text
    .split(/\n(?=## )/)
    .map((chunk) => chunk.trim())
    .filter(Boolean)
    .map((chunk) => {
      const title = /^##\s+(.+)$/m.exec(chunk)?.[1] || ''
      return {
        tone: title.includes('分析推理') ? 'analysis' as const : 'plain' as const,
        html: renderSimpleMarkdown(chunk)
      }
    })
}

/**
 * 计划执行模式里「一步」的展示结构。
 * steps 只放这一步内部的思考与工具，按事件顺序；不含其它步的内容。
 */
export interface PlanStepSection {
  seq: number
  title: string
  state: string
  text: string
  /** 时间线一行文案，服务端拼好；缺失时由展示层兜底 */
  line?: string
  steps: ProcessStep[]
}

/**
 * 计划执行模式一轮的分段视图。顺序即渲染顺序，不允许再按块位置自由浮排：
 * 计划阶段思考 → 计划卡 → 逐步执行（每步含自己的思考） → 终答。
 */
export interface TurnSections {
  /** 计划卡出现之前的思考（规划阶段的推理） */
  planPhase: ProcessStep[]
  /** 本轮的计划卡；没有计划时为 undefined */
  plan?: TrialPlanView
  /** 按 seq 升序的步骤进度 */
  execution: PlanStepSection[]
  /** 终答正文；可能为空（待批准或失败） */
  answer: string
  /** 本轮生成的可下载文件。渲染在终答之后，是「产物」而不是「过程」 */
  files: TrialFileView[]
  /** 装配说明等非错误提示，排在终答之后 */
  notes: string[]
  /** 失败说明，排在最后 */
  errors: string[]
}

/**
 * 这一轮是否是计划执行模式（含计划卡或步骤块）。
 * 是则走分段渲染，否则沿用 Router/ReAct 的「思考过程 + 块顺序」渲染。
 * @param item 助手轮
 */
export function isPlanFlow(item: ChatTurn): boolean {
  return item.blocks.some((block) => block.kind === 'plan' || block.kind === 'step')
}

/**
 * 把一轮的块切成四段：计划阶段思考 / 计划卡 / 逐步执行 / 终答。
 *
 * 归属规则（与后端落库顺序一一对应）：
 * - 计划卡之前的推理与工具归「计划阶段」；
 * - 某个 STEP 块（RUNNING）之后、下一个 STEP 之前的推理与工具归该步；
 * - 计划卡之后、第一个 STEP 之前的零散推理也归计划阶段，避免被丢掉；
 * - assistant 块一律进终答，绝不插到计划卡之前。
 *
 * @param blocks 该轮的块，按事件顺序
 * @return 分段视图
 */
export function turnSections(blocks: AssistantBlock[]): TurnSections {
  const planPhaseBlocks: AssistantBlock[] = []
  const buckets: { seq: number; title: string; state: string; text: string; line?: string; blocks: AssistantBlock[] }[] = []
  const notes: string[] = []
  const errors: string[] = []
  const files: TrialFileView[] = []
  let plan: TrialPlanView | undefined
  let current: (typeof buckets)[number] | undefined
  let answer = ''
  for (const block of blocks) {
    if (block.kind === 'plan' && block.plan) {
      plan = block.plan
      // 计划卡之后的推理不再算规划阶段；下一条 STEP 会重新开桶
      current = undefined
      continue
    }
    if (block.kind === 'step' && block.step) {
      const view = block.step
      // 同一 seq 的 RUNNING/DONE 是状态推进，必须合并成一个桶，不能新增行
      let bucket = buckets.find((item) => item.seq === view.seq)
      if (!bucket) {
        bucket = { seq: view.seq, title: view.title, state: view.state, text: view.text, line: view.line, blocks: [] }
        buckets.push(bucket)
      } else {
        bucket.state = view.state
        bucket.text = view.text
        bucket.line = view.line || bucket.line
        bucket.title = view.title || bucket.title
      }
      current = bucket
      continue
    }
    if (block.kind === 'assistant') {
      answer += block.text
      continue
    }
    if (block.kind === 'system') {
      notes.push(block.text)
      continue
    }
    if (block.kind === 'error') {
      errors.push(block.text)
      continue
    }
    if (block.kind === 'vision' || block.kind === 'preprocess') {
      // 识别结果是本轮素材，不属于任何执行步；由模板在卡片顶部单独渲染，这里直接跳过桶分配
      continue
    }
    if (block.kind === 'file') {
      // 产物不属于某一步的思考过程：由模板在卡片下方统一渲染下载卡片，这里不进任何桶
      if (block.file) {
        // 同一文件多次生成时按文件名去重，与 appendFile 的口径一致
        const index = files.findIndex((item) => item.fileName === block.file?.fileName)
        if (index >= 0) {
          files[index] = block.file
        } else {
          files.push(block.file)
        }
      }
      continue
    }
    if (block.kind === 'route' || block.kind === 'reasoning' || block.kind === 'tool') {
      if (current) {
        current.blocks.push(block)
      } else {
        planPhaseBlocks.push(block)
      }
    }
  }
  return {
    planPhase: simpleProcessSteps(planPhaseBlocks),
    plan,
    execution: buckets
      .slice()
      .sort((a, b) => a.seq - b.seq)
      .map((bucket) => ({
        seq: bucket.seq,
        title: bucket.title,
        state: bucket.state,
        text: bucket.text,
        line: bucket.line,
        steps: simpleProcessSteps(bucket.blocks)
      })),
    answer: answer.trim(),
    files,
    notes,
    errors
  }
}

/**
 * 扁平时间线：不生成「路由到 / 执行」这类分块头。
 * 计划阶段的推理与单步内部思考都不含路由语义，套 lane 头只会多出无信息的空标题。
 *
 * @param blocks 思考 / 工具 / 路由块，按事件顺序
 * @return 时间线步骤
 */
export function simpleProcessSteps(blocks: AssistantBlock[]): ProcessStep[] {
  const steps: ProcessStep[] = []
  for (const block of blocks) {
    if (block.kind === 'reasoning') {
      appendReasoningStep(steps, block.text)
      continue
    }
    if (block.kind === 'route') {
      const text = (block.text || '').trim()
      if (text) {
        steps.push({ type: 'route', title: '路由', text })
      }
      continue
    }
    const line = block.text.trim()
    const result = (block.detail || '').trim()
    // 工具开始（无结果）：只画「正在调用」胶囊
    if (block.toolState === 'running' && !line) {
      const name = (block.toolName || '').trim()
      if (name) {
        steps.push({ type: 'tool_call', title: `调用工具 ${name}`, text: '', state: 'running' })
      }
      continue
    }
    if (line) {
      steps.push({
        type: 'tool_call',
        title: toolCallTitle(line),
        text: toolCallBody(line),
        state: block.toolState === 'error' ? 'error' : 'done'
      })
    }
    if (result) {
      steps.push({ type: 'tool_result', title: '工具结果', text: result })
    }
  }
  return steps
}

/**
 * 就地推进计划卡状态，并同步「待批准」标记。
 *
 * 用于用户点「批准执行」后立刻把卡片从「待批准」改成「执行中」：
 * 批准接口要跑完整轮才返回，若等响应再刷新，界面会长时间停在「待批准」并只转个按钮。
 *
 * @param turn   该轮助手卡片
 * @param planId 目标计划；为空时取最后一个计划块
 * @param state  新状态（EXECUTING / REJECTED / DONE / FAILED）
 * @return 命中的计划块；没有时为 undefined
 */
export function advancePlanState(turn: ChatTurn, planId: string | undefined, state: string) {
  let hit: AssistantBlock | undefined
  for (let i = turn.blocks.length - 1; i >= 0; i -= 1) {
    const block = turn.blocks[i]
    if (block.kind !== 'plan' || !block.plan) {
      continue
    }
    if (!planId || block.plan.planId === planId) {
      hit = block
      break
    }
  }
  if (!hit || !hit.plan) {
    return undefined
  }
  hit.plan = { ...hit.plan, state }
  hit.text = hit.plan.summary || planText(hit.plan)
  // 待批准是唯一需要人工介入的状态；推进到其它状态后按钮必须立刻消失
  turn.planPending = state === 'PENDING'
  return hit
}

/** 左栏时间。空值返回空串。 */
export function formatRelativeTime(value?: string) {
  if (!value) {
    return ''
  }
  const date = new Date(value.replace(' ', 'T'))
  const time = date.getTime()
  if (Number.isNaN(time)) {
    return value
  }
  const delta = Date.now() - time
  if (delta < 60_000) {
    return '刚刚'
  }
  if (delta < 3_600_000) {
    return `${Math.floor(delta / 60_000)} 分钟前`
  }
  if (delta < 86_400_000) {
    return `${Math.floor(delta / 3_600_000)} 小时前`
  }
  if (delta < 7 * 86_400_000) {
    return `${Math.floor(delta / 86_400_000)} 天前`
  }
  const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`)
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 给助手轮打上「编排调度卡」前端标记（ROUTER_TRACE）。
 *
 * <p>只影响前端时间线：不写入 agent_run_event、不参与 {@link turnsFromEvents} 回放，
 * 刷新后由试跑状态重新判定再挂上。插到块列表最前，保证「调度卡 → 思考 → 终答」的阅读顺序。
 *
 * @param turn 目标助手轮；用户轮直接忽略
 */
export function markRouterTrace(turn: ChatTurn) {
  if (turn.role !== 'assistant') {
    return
  }
  if (turn.blocks.some((block) => block.kind === 'ROUTER_TRACE')) {
    return
  }
  turn.blocks.unshift({ id: nextBlockId(turn), kind: 'ROUTER_TRACE', text: '正在调度…' })
}

/**
 * 这一轮是否带编排调度卡标记。
 * 仅用于展示层判位，不参与任何落库/回放逻辑。
 * @param turn 助手轮
 */
export function hasRouterTrace(turn: ChatTurn): boolean {
  return turn.role === 'assistant' && turn.blocks.some((block) => block.kind === 'ROUTER_TRACE')
}

/**
 * @param id          用户事件 id，用来生成稳定 key
 * @param text        气泡文字，只发图或只发文件时为空串
 * @param images      按发送顺序的图片地址，可空
 * @param attachments 图和文件的添加顺序，可空
 */
function userTurn(
  id: number,
  text: string,
  images: string[] = [],
  attachments: ChatTurn['attachments'] = []
): ChatTurn {
  return {
    key: `u-${id}`,
    role: 'user',
    text,
    images: images.length ? images : undefined,
    attachments: attachments.length ? attachments : undefined,
    blocks: [],
    phase: 'done',
    status: 'done',
    startTime: 0
  }
}

function pushBlock(turn: ChatTurn, block: AssistantBlock, sessionId?: number) {
  // 回放与直播共用 appendText / appendRoute：块上已经贴好的身份必须跟着走，不能在这里丢掉
  if (block.kind === 'reasoning' || block.kind === 'assistant') {
    appendText(turn, block.kind, block.text, block.participantId, block.participantName)
    return
  }
  if (block.kind === 'route') {
    appendRoute(turn, block.text, block.participantId, block.participantName, block.begin, {
      participantKind: block.participantKind,
      participantDepth: block.participantDepth
    })
    return
  }
  // 计划与步骤也走 upsert：回放的是一串状态事件，必须原位合并而不是堆多张卡/多行
  if (block.kind === 'plan' && block.plan) {
    upsertPlan(turn, block.plan)
    return
  }
  if (block.kind === 'step' && block.step) {
    upsertStep(turn, block.step)
    return
  }
  // 产物也走去重：同一参与者同一文件多次生成只保留最新一条卡片。
  // 身份直接随块传入，刷新后文件仍挂原参与者，不再事后按「最后一位」回填。
  if (block.kind === 'file' && block.file) {
    appendFile(turn, block.file, block.participantId, block.participantName)
    return
  }
  // 选项也走去重：同一轮只保留一条 chips，重复回放原位覆盖
  if (block.kind === 'choice' && block.choice) {
    appendChoice(turn, block.choice, block.participantId, block.participantName)
    return
  }
  // 工作流卡片同理：同一轮同一工作流只保留最新一份快照
  if (block.kind === 'workflow' && block.workflow) {
    appendWorkflow(turn, block.workflow, block.participantId, block.participantName, sessionId)
    return
  }
  // 其余块（vision / system / error / ROUTER_TRACE）直接入列：补齐稳定 id 再 push，
  // 否则模板只能退回下标 key，插入视觉块时会整表重挂
  turn.blocks.push({ ...block, id: block.id || nextBlockId(turn) })
}

function blockFromEvent(item: TrialEvent): AssistantBlock | undefined {
  if (item.eventType === 'reasoning') {
    const text = displayText(item)
    return text ? { kind: 'reasoning', text } : undefined
  }
  if (item.eventType === 'tool') {
    const raw = displayText(item) || (item.summary || '').trim()
    if (!raw) {
      return undefined
    }
    const split = raw.indexOf('\n\n')
    if (split < 0) {
      return { kind: 'tool', text: raw }
    }
    const detail = raw.slice(split + 2).trim()
    return {
      kind: 'tool',
      text: raw.slice(0, split).trim(),
      detail: detail || undefined
    }
  }
  if (item.eventType === 'assistant') {
    const text = displayText(item)
    return text ? { kind: 'assistant', text } : undefined
  }
  if (item.eventType === 'vision') {
    const text = displayText(item)
    return text ? { kind: 'vision', text } : undefined
  }
  if (item.eventType === 'preprocess') {
    const view = parsePreprocessEvent(item.contentText)
    if (view) {
      return { kind: 'preprocess', text: preprocessDisplayText(view), preprocess: view }
    }
    const fallback = (item.summary || '').trim()
    return fallback ? { kind: 'preprocess', text: fallback } : undefined
  }
  if (item.eventType === 'route') {
    const text = displayText(item)
    return text ? { kind: 'route', text } : undefined
  }
  if (item.eventType === 'plan') {
    // 正文是结构化 JSON；解析失败时退回摘要，至少让时间线知道这里有一份计划
    const plan = parsePlan(item.contentText)
    if (plan) {
      return { kind: 'plan', text: plan.summary || planText(plan), plan }
    }
    const fallback = (item.summary || '').trim()
    return fallback ? { kind: 'system', text: fallback } : undefined
  }
  if (item.eventType === 'step') {
    const step = parseStep(item.contentText)
    if (step) {
      return { kind: 'step', text: step.line || stepText(step), step }
    }
    const fallback = (item.summary || '').trim()
    return fallback ? { kind: 'step', text: fallback } : undefined
  }
  if (item.eventType === 'system') {
    const text = displayText(item)
    return text ? { kind: 'system', text } : undefined
  }
  if (item.eventType === 'file') {
    // 正文是 {"fileName","size","participantId","participantName"}；
    // downloadUrl 落库时不存（会话主键在路径里），故按当前会话现拼
    const file = parseFile(item.contentText, item.sessionId)
    if (!file) {
      const fallback = (item.summary || '').trim()
      return fallback ? { kind: 'system', text: fallback } : undefined
    }
    // 身份以落库字段为准：回放不再靠 carryReplayIdentity 猜作者
    const owner = fileOwnerOf(item.contentText)
    return {
      kind: 'file',
      text: fileNameOf(file.fileName),
      file,
      participantId: owner.id || undefined,
      participantName: owner.name || undefined
    }
  }
  if (item.eventType === 'choice') {
    /*
     * 结构化选项：正文是 {"question","options"}（与 SSE 帧同形），摘要是问题句。
     * 解析不出合法结构时整块丢弃——回放也不能凭空造一组按钮。
     */
    const choice = parseChoiceEvent(item.contentText)
    return choice ? { kind: 'choice', text: choice.question, choice } : undefined
  }
  if (item.eventType === 'workflow') {
    /*
     * 工作流执行卡片：正文是 {name,code,status,answer,nodes} 的 JSON（与 SSE 帧同形）。
     * 正文残缺（历史截断）时不再整块丢弃：用事件摘要合成一张「已结束」占位卡，
     * 让这一轮仍有收口标记，旧的等待卡也能被接管。
     */
    const workflow = parseWorkflowEvent(item.contentText, item.sessionId)
      || placeholderWorkflow(item.summary)
    return workflow ? { kind: 'workflow', text: workflow.name || workflow.code, workflow } : undefined
  }
  if (item.eventType === 'error') {
    const text = displayText(item)
    return text ? { kind: 'error', text } : undefined
  }
  /*
   * 参与者身份帧（后端只记 begin）：刷新时读的是进行中一轮的内存快照，
   * 此刻该步可能还没产出任何思考 / 工具。补一条占位路由块，页面才能与实时一致地先出块。
   * begin=false 的归属切换不建块（那是给实时增量定向用的，回放不需要）。
   */
  if (item.eventType === 'participant') {
    return participantBeginBlock(item)
  }
  return undefined
}

/**
 * 身份帧 → 占位路由块。所属域：runtime（前端）。
 *
 * <p>只认 begin=true 且 id / 名字齐全的帧；入口与汇总是「块由内容建立」的两位，
 * 它们不吃占位（补了会多出一位不存在的参与者）。与 {@link appendParticipantBegin} 同口径。
 *
 * @param item 落库 / 快照里的 participant 事件
 * @return 占位路由块；不入列时返回 undefined
 */
function participantBeginBlock(item: TrialEvent): AssistantBlock | undefined {
  const who = readParticipantEvent(item)
  // 入口与汇总结论不占专家位：入口靠身份 id 认思考，结论靠「方案结论：」标记
  if (!who?.begin || who.kind === 'gateway' || who.kind === 'conclusion') {
    return undefined
  }
  return {
    kind: 'route',
    text: who.routeKey ? `路由到 ${who.name}（${who.routeKey}）` : `路由到 ${who.name}`,
    participantId: who.id,
    participantName: who.name,
    participantKind: who.kind || undefined,
    participantDepth: who.depth,
    begin: true
  }
}

/**
 * 读一条 participant 事件正文。半截 JSON 直接放弃，不让回放整轮失败。
 *
 * @param item 落库事件
 * @return 身份；不是 begin 或字段不全时为 undefined
 */
function readParticipantEvent(item: TrialEvent): (ReplaySpeaker & { begin: boolean; routeKey: string }) | undefined {
  const raw = (item.contentText || '').trim()
  if (!raw || raw[0] !== '{') {
    return undefined
  }
  let chunk: { id?: string; name?: string; kind?: string; routeKey?: string; begin?: boolean; depth?: number }
  try {
    chunk = JSON.parse(raw)
  } catch {
    return undefined
  }
  const id = (chunk?.id || '').trim()
  const name = (chunk?.name || '').trim()
  if (!chunk || chunk.begin !== true || !id || !name) {
    return undefined
  }
  return {
    id,
    name,
    kind: (chunk.kind || '').trim(),
    routeKey: (chunk.routeKey || '').trim(),
    begin: true,
    depth: typeof chunk.depth === 'number' ? chunk.depth : undefined
  }
}

/**
 * 读一条 participant 终态事件（{@code begin=false} + {@code state}）。
 *
 * <p>为什么要单独解析：{@link readParticipantEvent} 只认 begin 帧，终态帧不能用来换人，
 * 但它必须能把「这一位已结束」落到已有的 route 块上，否则刷新回放（进行中一轮的内存快照）
 * 里所有成员都只能等整轮结束才停转。
 *
 * <p>只认带 state 的帧：{@code begin=false} 但没有 state 的是纯归属切换，不是收口，
 * 老数据 / 老前端都按忽略处理，行为不变。
 *
 * @param item 事件（内存快照 / 落库）
 * @return 终态身份；不是终态帧或字段不全时为 undefined
 */
function readParticipantEnd(
  item: TrialEvent
): { id: string; name: string; state: string; note: string } | undefined {
  const raw = (item.contentText || '').trim()
  if (!raw || raw[0] !== '{') {
    return undefined
  }
  let chunk: { id?: string; name?: string; state?: string; note?: string; begin?: boolean }
  try {
    chunk = JSON.parse(raw)
  } catch {
    return undefined
  }
  const id = (chunk?.id || '').trim()
  const state = (chunk?.state || '').trim()
  // begin=true 是开始帧；没有 state 的是纯归属帧。两者都不是「结束」
  if (!chunk || chunk.begin === true || !id || !state) {
    return undefined
  }
  return { id, name: (chunk.name || '').trim(), state, note: (chunk.note || '').trim() }
}

/** 展示优先用正文，旧数据没有 contentText 时退回短摘要。 */
function displayText(item: TrialEvent): string {
  const full = (item.contentText || '').trim()
  if (full) {
    return full
  }
  return (item.summary || '').trim()
}

/**
 * 解析落库的工作流卡片事件正文（{@code {"name","code","status","answer","nodes"}}）。
 *
 * <p>与 SSE 帧同形，因此直接复用 {@link normalizeWorkflow}：两条来源一套口径，
 * 不会出现「实时看得到、刷新后字段对不上」。
 *
 * @param raw 事件正文 JSON，可空
 * @return 归一化视图；空正文 / 半截 JSON / 缺名字与编码时为 undefined
 */
/**
 * 解析入口预处理事件正文（与 SSE `preprocess` 帧同一形状）。
 *
 * @param raw 事件正文 JSON，可空
 * @return 快照；半截 JSON 放弃，不让回放整轮失败
 */
export function parsePreprocessEvent(raw: string | undefined): TrialPreprocessView | undefined {
  const text = (raw || '').trim()
  if (!text || text.charAt(0) !== '{') {
    return undefined
  }
  try {
    const node = JSON.parse(text) as {
      status?: unknown
      tasks?: unknown
      hint?: unknown
      summary?: unknown
      text?: unknown
    }
    const tasks = Array.isArray(node.tasks)
      ? node.tasks.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
      : []
    return {
      status: typeof node.status === 'string' && node.status.trim() ? node.status.trim() : 'running',
      tasks,
      hint: typeof node.hint === 'string' ? node.hint : '',
      summary: typeof node.summary === 'string' ? node.summary : '',
      text: typeof node.text === 'string' ? node.text : ''
    }
  } catch {
    return undefined
  }
}

export function parseWorkflowEvent(
  raw: string | undefined,
  sessionId?: number
): TrialWorkflowView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    return normalizeWorkflow(JSON.parse(text) as WorkflowSnapshotInput, sessionId)
  } catch {
    // 半截 JSON（列宽截断 / 历史脏数据）：交给 placeholderWorkflow 出占位卡
    return undefined
  }
}

/** 事件摘要里的工作流名：后端摘要固定是「工作流「名字」状态」 */
const WORKFLOW_SUMMARY_NAME = /「([^」]+)」/

/**
 * 由事件摘要合成一张占位卡（回放兜底）。所属域：runtime（前端）。
 *
 * <p>为什么不能继续「解析失败就整块丢弃」：执行卡片是这一轮唯一的收口标记。
 * 丢掉它之后，上一轮停在「等待你的回复」的旧卡就没有新卡来接管，
 * 页面看起来像又退回去问了一遍——这正是历史截断事故的可见症状。
 *
 * <p>只从摘要里取「名字 + 状态」，不猜节点：没有名字时宁可不画卡，
 * 也不画一张无法归属到任何工作流的空卡。
 *
 * @param summary 事件摘要（形如「工作流「X」已完成」）；可空
 * @returns 占位卡；摘要里没有可用名字时为 undefined
 */
export function placeholderWorkflow(summary: string | undefined): TrialWorkflowView | undefined {
  const text = (summary || '').trim()
  if (!text) {
    return undefined
  }
  const matched = WORKFLOW_SUMMARY_NAME.exec(text)
  const name = (matched?.[1] || '').trim()
  if (!name) {
    return undefined
  }
  // 状态只按摘要里的固定短语反推；认不出时给 UNKNOWN（前端按「详情不可用」展示，不转圈）
  let status = 'UNKNOWN'
  if (text.includes('已完成')) {
    status = 'SUCCESS'
  } else if (text.includes('运行失败')) {
    status = 'FAILED'
  } else if (text.includes('已取消') || text.includes('已中断')) {
    status = 'CANCELLED'
  } else if (text.includes('等待你的回复')) {
    status = 'WAITING_REPLY'
  }
  return {
    name,
    code: '',
    status,
    answer: '',
    nodes: [],
    degraded: true
  }
}

/**
 * 解析 CHOICE 事件正文（与 SSE `choice` 帧同一形状）。
 *
 * <p>边界与后端一致：`options` 必须是 2~6 项、每项 id / label 非空且 id 不重复，
 * 任何一条不合格就整份作废——宁可这一轮没有 chips，也不渲染一组点了会对错人的按钮。
 *
 * @param raw 事件正文 JSON，可空
 * @return 结构化选项；解析失败或越界时为 undefined
 */
export function parseChoiceEvent(raw: string | undefined): TrialChoiceView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  let data: { question?: unknown; options?: unknown; reply?: unknown; waiting?: unknown }
  try {
    data = JSON.parse(text) as { question?: unknown; options?: unknown; reply?: unknown; waiting?: unknown }
  } catch {
    // 半截 JSON（快照写入时被截断）：不出块，正文照常展示
    return undefined
  }
  if (!data) {
    return undefined
  }
  const question = typeof data.question === 'string' ? data.question.trim() : ''
  const reply = typeof data.reply === 'string' ? data.reply.trim() : ''
  const waiting = data.waiting === true
  // 回复态允许 options 缺省 / 为空（选项已清掉），但必须有回复文案；普通选项没有 reply
  if (!Array.isArray(data.options)) {
    return reply ? { question, options: [], reply, waiting: false } : undefined
  }
  const options: { id: string; label: string }[] = []
  for (const item of data.options) {
    if (!item || typeof item !== 'object') {
      return reply ? { question, options: [], reply, waiting: false } : undefined
    }
    const one = item as { id?: unknown; label?: unknown }
    const id = typeof one.id === 'string' ? one.id.trim() : ''
    const label = typeof one.label === 'string' ? one.label.trim() : ''
    if (!id || !label) {
      return reply ? { question, options: [], reply, waiting: false } : undefined
    }
    if (options.some((row) => row.id.toLowerCase() === id.toLowerCase())) {
      return reply ? { question, options: [], reply, waiting: false } : undefined
    }
    options.push({ id, label })
  }
  if (options.length === 0) {
    return reply ? { question, options: [], reply, waiting: false } : undefined
  }
  if (options.length < 2 || options.length > 6) {
    return reply ? { question, options: [], reply, waiting: false } : undefined
  }
  return { question, options, reply: reply || undefined, waiting }
}

/**
 * 从 FILE 正文里取「谁写的」。旧事件没有这两个字段，返回空串交给调用方走历史兜底。
 *
 * @param raw 事件正文 JSON
 * @returns 身份；缺失或解析失败时两项都是空串
 */
export function fileOwnerOf(raw: string | undefined): { id: string; name: string } {
  const text = (raw || '').trim()
  if (!text) {
    return { id: '', name: '' }
  }
  try {
    const data = JSON.parse(text) as { participantId?: unknown; participantName?: unknown }
    return {
      id: String(data?.participantId || '').trim(),
      name: String(data?.participantName || '').trim()
    }
  } catch {
    return { id: '', name: '' }
  }
}

/**
 * 解析 FILE 事件正文，并现场拼出下载地址。
 *
 * <p>为什么落库只存 fileName + size：会话主键在下载路径里，
 * 存进正文会随会话复制/迁移而失效；地址是「派生值」，回放时按当前会话现拼更稳。
 *
 * @param raw       事件正文 JSON
 * @param sessionId 事件所属会话；缺失时 downloadUrl 为空串，卡片仍可展示但置灰
 * @return 产物视图；解析失败时为 undefined
 */
export function parseFile(raw: string | undefined, sessionId?: number): TrialFileView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    const data = JSON.parse(text) as { fileName?: unknown; size?: unknown }
    const fileName = String(data?.fileName || '').trim()
    if (!fileName) {
      return undefined
    }
    const size = Number(data?.size)
    return {
      fileName,
      size: Number.isFinite(size) ? size : -1,
      // 会话主键缺失（异常快照）时不编造地址，交给卡片按「不可下载」渲染
      downloadUrl: sessionId ? buildDownloadUrl(sessionId, fileName) : ''
    }
  } catch {
    return undefined
  }
}

/**
 * 拼下载地址。编码规则与后端 TrialChunkWriter 的 downloadUrl 保持一致，
 * 保证「实时推送的卡片」与「刷新后回放的卡片」点下去打开的是同一个地址。
 *
 * @param sessionId 会话主键
 * @param fileName  相对工作区根的文件名
 * @return 以 /api 开头的下载路径
 */
export function buildDownloadUrl(sessionId: number, fileName: string) {
  return `/api/runtime/workspace/download?sessionId=${sessionId}&fileName=${encodeURIComponent(fileName)}`
}

/**
 * 解析 PLAN 事件/SSE 帧的 JSON 正文。
 * 字段与后端 PlanJson 一致；形状不对时返回 undefined，由调用方退回摘要展示。
 *
 * @param raw content_text 或 SSE data
 * @return 计划视图；解析失败为 undefined
 */
export function parsePlan(raw?: string): TrialPlanView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    const data = JSON.parse(text) as Partial<TrialPlanView>
    if (!data || typeof data !== 'object') {
      return undefined
    }
    const steps = Array.isArray(data.steps) ? data.steps : []
    return {
      planId: String(data.planId || ''),
      state: String(data.state || 'FAILED'),
      summary: typeof data.summary === 'string' ? data.summary : undefined,
      steps: steps.map((item) => ({
        seq: Number(item?.seq) || 0,
        title: String(item?.title || ''),
        detail: String(item?.detail || '')
      }))
    }
  } catch {
    return undefined
  }
}

/**
 * 解析 STEP 事件/SSE 帧的 JSON 正文。
 *
 * @param raw content_text 或 SSE data
 * @return 单步视图；解析失败为 undefined
 */
export function parseStep(raw?: string): TrialStepView | undefined {
  const text = (raw || '').trim()
  if (!text) {
    return undefined
  }
  try {
    const data = JSON.parse(text) as Partial<TrialStepView>
    if (!data || typeof data !== 'object') {
      return undefined
    }
    return {
      seq: Number(data.seq) || 0,
      title: String(data.title || ''),
      state: String(data.state || 'FAILED'),
      text: String(data.text || ''),
      line: typeof data.line === 'string' ? data.line : undefined
    }
  } catch {
    return undefined
  }
}

/** 计划块兜底文案：结构化数据在但服务端没给 summary 时用。 */
export function planText(plan: TrialPlanView): string {
  return `计划共 ${plan.steps.length} 步 · ${planStateText(plan.state)}`
}

/** 计划状态中文。未知状态不伪装成完成。 */
export function planStateText(state?: string): string {
  if (state === 'PENDING') return '待批准'
  if (state === 'EXECUTING') return '执行中'
  if (state === 'REJECTED') return '已取消'
  if (state === 'DONE') return '已完成'
  return '执行失败'
}

/** 单步状态中文。 */
export function stepStateText(state?: string): string {
  if (state === 'RUNNING') return '执行中'
  if (state === 'RETRYING') return '重试中'
  if (state === 'DONE') return '完成'
  return '失败'
}

/** 单步块兜底文案。 */
function stepText(step: TrialStepView): string {
  return `第 ${step.seq} 步 · ${step.title} · ${stepStateText(step.state)}`
}

/**
 * 把计划状态推进写到同一张计划卡上：同一 planId 只保留一份，避免时间线堆出多张矛盾的计划。
 * 状态更新事件不带 steps，此时沿用已有步骤。</br>
 *
 * @param turn 本轮的助手卡片
 * @param next 新到的计划视图
 */
export function upsertPlan(turn: ChatTurn, next: TrialPlanView) {
  const existing = turn.blocks.find((block) => block.kind === 'plan' && block.plan?.planId === next.planId)
  if (existing && existing.plan) {
    existing.plan = {
      planId: next.planId,
      state: next.state,
      summary: next.summary,
      // 状态事件刻意不带 steps；只有首次计划才写步骤
      steps: next.steps.length ? next.steps : existing.plan.steps
    }
    existing.text = existing.plan.summary || planText(existing.plan)
  } else {
    const plan: TrialPlanView = {
      planId: next.planId,
      state: next.state,
      summary: next.summary,
      steps: next.steps
    }
    turn.blocks.push({ id: nextBlockId(turn), kind: 'plan', text: plan.summary || planText(plan), plan })
  }
  turn.planId = next.planId
  // 待批准是唯一需要人工介入的状态；其它状态按钮都应消失
  turn.planPending = next.state === 'PENDING'
}

/**
 * 单步进度：同一 seq 原位更新状态与结果，不追加新块。
 *
 * @param turn 本轮的助手卡片
 * @param next 新到的单步视图
 */
export function upsertStep(turn: ChatTurn, next: TrialStepView) {
  const existing = turn.blocks.find((block) => block.kind === 'step' && block.step?.seq === next.seq)
  if (existing) {
    existing.step = next
    existing.text = next.line || stepText(next)
    return
  }
  turn.blocks.push({ id: nextBlockId(turn), kind: 'step', text: next.line || stepText(next), step: next })
}
