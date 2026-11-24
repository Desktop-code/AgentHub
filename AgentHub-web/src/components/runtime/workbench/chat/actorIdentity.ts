/**
 * 参与者身份解析：候选池 → 形象 / 展示名 / 简介。所属域：runtime（前端展示）。
 *
 * <p>职责：把会话候选池（入口方案 + 方案内子候选）翻译成「这位参与者长什么样、
 * 对外叫什么、为什么是它」。只做展示层解析，不改投影、不发请求、不读全局状态。
 *
 * <p>为什么单独成模块：同一套解析被三处使用——行首 AI 头像、投影回调
 * （`buildParticipantFeed` 的 `lookOf` / `descriptionOf`）、参与者卡头像与名称。
 * 收在一处后，参与者卡独立成组件时不会顺手复制一份解析逻辑。
 *
 * <p>入参用 getter 而不是值：候选池随 @ 面板 / 配置刷新异步到达，选中线路与
 * 当前 Agent 也会随用户操作变化；用 getter 才能让派生结果跟着刷新。
 */
import type { ChatTurn } from '@/types/runtime'
import type { AvatarConfig } from '@/types/agent'
import type { WorkbenchRouteOption } from '@/types/workbench'
import type { ProcessLane } from '@/components/runtime/trialMessages'
import type { ExpertLookView } from '@/components/runtime/workbench/workbenchThinkTimeline'
import type { AgentRobotShape } from '@/constants/agentRobotShapes'
import { resolveAgentAvatar, resolveFallbackRobotAvatar } from '@/constants/agentLooks'
import { resolveWorkbenchAgentName } from '@/components/runtime/workbench/workbenchMessageMeta'

/** 气泡用的 Agent 形象解析结果 */
export interface AssistantLookView {
  /** 解析出的展示名 */
  name: string
  /** 首字兜底 */
  initial: string
  /** 自定义上传图地址 */
  iconSrc?: string
  /** 线稿图元（未命中机器人时为 undefined） */
  art?: AgentRobotShape[] | null
  /** 底座色：恒为白底方座 */
  color: string
  /** 线稿色 / 首字兜底色 */
  initialColor: string
  /** 自定义上传图：铺满裁切 */
  custom: boolean
}

/** 造一份身份解析所需的外部输入 */
export interface ActorIdentityInput {
  /** 本会话候选池（getter：@ 面板 / 配置刷新后立即生效） */
  routeCandidates: () => WorkbenchRouteOption[]
  /** @ 选定 / 当前强制线路（getter） */
  selectedRoute?: () => WorkbenchRouteOption | null | undefined
  /** 调试皮肤按当前 Agent id 匹配（getter） */
  agentId?: () => number | undefined
  /** 当前线路展示名兜底（getter） */
  routeHint?: () => string | undefined
  /** 本轮思考泳道：多专家同会话时优先按车道里的 Agent 名匹配 */
  lanesOf: (turn: ChatTurn) => ProcessLane[]
}

/** 参与者身份解析结果：行首头像、投影回调、参与者卡共用同一份 */
export interface ActorIdentity {
  /** 名字 → 形象（未配置 / 认不出时走固定机器人兜底） */
  lookOf: (name: string) => ExpertLookView
  /** 名字 → 候选意图说明；投影的网关句引用它 */
  descriptionOf: (name: string) => string
  /** 名字 → 候选（用于目标类型与形象解析） */
  candidateOf: (name: string) => WorkbenchRouteOption | undefined
  /** 名字 → 形象配置（先查入口候选，再查方案内部子候选） */
  avatarConfigOf: (name: string) => AvatarConfig | undefined
  /** 本轮行首 AI 形象（按车道 / 选定线路 / agentId 依次匹配） */
  assistantLook: (turn: ChatTurn) => AssistantLookView
  /** 行首 AI 头像内联样式 */
  avatarStyle: (turn: ChatTurn) => { background: string; color: string }
  /** 工作台 AI 名称行文案 */
  displayName: (turn: ChatTurn) => string
}

/**
 * 从「路由到 {名}」抽出子 Agent 显示名，供红色强调与候选匹配。
 *
 * <p>保留在身份模块里：它只服务于「从思考车道里认人」，与候选池匹配是同一件事。
 *
 * @param title 泳道标题
 * @return 子 Agent 名；不是路由行时为空串
 */
export function routeAgentName(title: string): string {
  const prefix = '路由到 '
  if (!title.startsWith(prefix)) {
    return ''
  }
  return title.slice(prefix.length).trim()
}

/**
 * 造一份参与者身份解析器。
 *
 * <p>投影主链只拿到 `lookOf` / `descriptionOf` 两个函数，入参形状与拆前完全一致；
 * 行首头像与参与者卡拿到的是同一份实现，不会各算各的。
 *
 * @param input 候选池 / 选中线路 / 当前 Agent / 泳道的 getter
 * @return 身份解析结果
 */
export function createActorIdentity(input: ActorIdentityInput): ActorIdentity {
  /** 读当前候选池：每次调用都取最新值，避免把异步到达的候选池冻在 setup 快照里 */
  function candidates(): WorkbenchRouteOption[] {
    return input.routeCandidates() || []
  }

  /**
   * 按展示名取候选项：用于目标类型与形象解析；对不上返回 undefined（按普通 Agent 处理）。
   *
   * @param name 展示名
   * @return 候选；空表示不在本会话候选里
   */
  function candidateOf(name: string): WorkbenchRouteOption | undefined {
    const wanted = (name || '').trim()
    if (!wanted) {
      return undefined
    }
    return candidates().find((item) => (item.agentName || '').trim() === wanted)
  }

  /**
   * 该候选的简介 / 意图说明：判路由命中时后端 reason 只是内部标签，
   * 这是网关句唯一能引用的「为什么是它」事实依据（现网字段 `intentDesc`）。
   *
   * @param name 展示名
   * @return 简介；候选不在本会话或没有简介时为空串
   */
  function descriptionOf(name: string): string {
    return (candidateOf(name)?.intentDesc || '').trim()
  }

  /**
   * 该名字的形象配置：先查入口候选，再查方案内部的子候选。
   *
   * <p>方案内部子专家不在入口候选池里，形象只能从入口方案那一行的 `children` 取；
   * 两者都没有返回 undefined，交给调用方决定兜底（方案用固定机器人，专家用首字）。
   *
   * @param name 展示名
   * @return 形象配置；未配置 / 不认识的参与者为 undefined
   */
  function avatarConfigOf(name: string): AvatarConfig | undefined {
    const top = candidateOf(name)
    if (top) {
      return top.avatarConfig || undefined
    }
    const wanted = (name || '').trim()
    if (!wanted) {
      return undefined
    }
    for (const option of candidates()) {
      const child = (option.children || []).find((item) => (item.agentName || '').trim() === wanted)
      if (child) {
        return child.avatarConfig || undefined
      }
    }
    return undefined
  }

  /**
   * 专家 / 方案形象：已配置的用配置，没配置（或配置认不出图形）的一律给固定机器人。
   *
   * <p>为什么不再退「首字方座」：时间线、方案块、子专家块都是「参与者」，只露一个汉字
   * 既分不清谁是谁，也和单兵专家的机器人形象不在同一套里。兜底用
   * {@link resolveFallbackRobotAvatar} 按名字稳定挑现成 8 套之一，刷新不会变脸。
   *
   * @param name 展示名
   * @return 时间线内的小头像视图
   */
  function lookOf(name: string): ExpertLookView {
    const config = avatarConfigOf(name)
    let view = config ? resolveAgentAvatar(config, name) : null
    if (!view || (!view.imgSrc && !view.art)) {
      // 未配置 / 配置形态认不出：走固定机器人，避免「企」「制」这种首字方座
      view = resolveFallbackRobotAvatar(name)
    }
    return {
      initial: view.initial,
      iconSrc: view.imgSrc || undefined,
      art: view.art,
      color: view.background,
      initialColor: view.initialColor,
      custom: view.isCustom
    }
  }

  /**
   * 解析本轮 AI 气泡形象：候选按 agentId / 名称匹配；多专家同会话时优先用思考车道里的 Agent 名。
   * 未配置形象时与时间线一致，走固定机器人兜底（不再是首字方座）。
   *
   * @param turn 本轮对话
   * @return 行首头像视图
   */
  function assistantLook(turn: ChatTurn): AssistantLookView {
    const pool = candidates()
    let matched: WorkbenchRouteOption | undefined
    // 1) 思考车道里的 Agent 名（多专家时更能对上本轮实际线路）
    for (const lane of input.lanesOf(turn)) {
      if (lane.kind !== 'agent') {
        continue
      }
      const name = routeAgentName(lane.title) || lane.title
      if (!name) {
        continue
      }
      matched = pool.find((item) => (item.agentName || '').trim() === name.trim())
      if (matched) {
        break
      }
    }
    // 2) @ 选定 / 当前强制线路
    if (!matched) {
      const selected = input.selectedRoute?.() || undefined
      if (selected) {
        matched = selected
      }
    }
    // 3) 调试皮肤：按当前 agentId
    const agentId = input.agentId?.()
    if (!matched && agentId != null) {
      matched = pool.find((item) => item.agentId === agentId)
    }
    const name =
      (matched?.agentName || '').trim() ||
      (input.routeHint?.() || '').trim() ||
      '助手'
    // 与时间线共用同一套兜底：没配形象 / 配置认不出图形时给固定机器人，
    // 气泡里也不再只露「企」这种首字方座
    const view = lookOf(name)
    return {
      name,
      initial: view.initial,
      iconSrc: view.iconSrc,
      art: view.art,
      color: view.color,
      initialColor: view.initialColor,
      custom: view.custom
    }
  }

  /**
   * AI 头像样式：白底方座不变色；首字兜底时文字用该套霓虹色。
   *
   * @param turn 本轮
   * @return 内联样式
   */
  function avatarStyle(turn: ChatTurn): { background: string; color: string } {
    const look = assistantLook(turn)
    return {
      background: look.color,
      color: look.initialColor
    }
  }

  /**
   * 工作台 AI 名称行文案：优先取本轮思考车道里真实出现的「路由到 XXX」，
   * 多专家同会话时各条用自己的名称；候选里有规范展示名则用规范名。
   * 没有车道（单专家直答）时退回当前线路展示名；仍为空或占位则「智能助手」。
   *
   * @param turn 本轮助手消息
   * @return 可展示的 Agent 名称
   */
  function displayName(turn: ChatTurn): string {
    const pool = candidates()
    for (const lane of input.lanesOf(turn)) {
      if (lane.kind !== 'agent') {
        continue
      }
      const laneName = routeAgentName(lane.title)
      if (!laneName) {
        continue
      }
      // 车道名可能只是 Agent 简称，候选里能对上时用统一展示名
      const matched = pool.find((item) => (item.agentName || '').trim() === laneName)
      return resolveWorkbenchAgentName(matched?.agentName || laneName)
    }
    // 无车道：沿用头像解析出的线路名，但过滤掉「线路已失效」等状态占位
    return resolveWorkbenchAgentName(assistantLook(turn).name)
  }

  return {
    lookOf,
    descriptionOf,
    candidateOf,
    avatarConfigOf,
    assistantLook,
    avatarStyle,
    displayName
  }
}
