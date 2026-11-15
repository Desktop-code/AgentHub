/**
 * 工作台消息流底部的「下一步在等什么」提示。所属域：runtime（前端展示）。
 *
 * <p>为什么单独一个纯模块：这条提示只在消息流最底部出现一行轻量文字，
 * 但它必须严格跟着**真实事件**切换——不能把「思考中」写在一个正在调工具的块上。
 * 判定散在组件里既没法在 node 里测，也容易和参与者分块各说各话。
 *
 * <p>文案只允许四拍：规划中 / 思考中 / 调用工具中 / 作答中。
 * 不造「正在作答」这类钮，不做居中胶囊，不替代各参与者自己的思考框。
 */

/** 四拍文案；不在其中时提示整条消失 */
export const STEP_HINT_PLANNING = '规划中'
export const STEP_HINT_THINKING = '思考中'
export const STEP_HINT_TOOL = '调用工具中'
export const STEP_HINT_ANSWERING = '作答中'

/** 提示判定只需要块的三个字段，避免把整个 AssistantBlock 拖进纯模块 */
export interface StepHintBlock {
  /** 块类型：reasoning / tool / assistant / route / plan / step / vision / file / error / system */
  kind: string
  /** 工具块状态：running＝已开始、结果未回 */
  toolState?: string
  /** 块正文（路由行要按文案区分「交给某位参与者」与「交给工作流」） */
  text?: string
}

/**
 * 消息流底部的步骤提示。
 *
 * <p>判定顺序（先看「在等什么」，再看「上一条真实事件是什么」）：
 * <ol>
 *   <li>本轮不在流式、或停在待批准：提示消失（待批准由批准气泡表达，不编状态）；</li>
 *   <li>有工具正在跑：调用工具中；</li>
 *   <li>尾块是推理：思考中；尾块是正文：作答中；</li>
 *   <li>尾块是已经回来的工具：下一步是模型继续推理，记为思考中；</li>
 *   <li>尾块是路由 / 产物 / 图片文字：继续往前找最近一条能表明拍的块；</li>
 *   <li>尾块是计划 / 步骤：交给计划卡，这里不猜（返回空串）；</li>
 *   <li>一条过程事件都没有：规划中。</li>
 * </ol>
 *
 * @param blocks     本轮块（按到达顺序）
 * @param streaming  本轮是否仍在流式输出
 * @param planPending 是否停在计划待批准
 * @return 提示文案；不该出现时为空串
 */
export function liveStepHintOf(
  blocks: readonly StepHintBlock[] | undefined,
  streaming: boolean,
  planPending = false
): string {
  if (!streaming || planPending) {
    return ''
  }
  const list = Array.isArray(blocks) ? blocks : []
  // 工具正在执行：这是唯一能明确说「在调用工具」的事实
  if (list.some((block) => block && block.kind === 'tool' && block.toolState === 'running')) {
    return STEP_HINT_TOOL
  }
  for (let index = list.length - 1; index >= 0; index -= 1) {
    const block = list[index]
    if (!block) {
      continue
    }
    if (block.kind === 'reasoning') {
      return STEP_HINT_THINKING
    }
    if (block.kind === 'assistant') {
      return STEP_HINT_ANSWERING
    }
    if (block.kind === 'tool') {
      // 工具已回、正文还没开始：下一步是模型接着推理
      return STEP_HINT_THINKING
    }
    if (block.kind === 'route') {
      // 工作流有自己的运行状态：不在这里假装成「思考中」
      if ((block.text || '').trim().startsWith('运行工作流')) {
        return ''
      }
      // 刚分给某位参与者：等它开口，先落在这位的思考上
      return STEP_HINT_THINKING
    }
    if (block.kind === 'error') {
      // 出错不再假装还在工作；本轮收尾后提示消失
      return ''
    }
    if (block.kind === 'plan' || block.kind === 'step') {
      // 计划 / 步骤有自己的卡片与进度：这里不猜它处在哪一拍
      return ''
    }
    // vision / file / system：都是过程素材，继续往前找
  }
  return STEP_HINT_PLANNING
}
