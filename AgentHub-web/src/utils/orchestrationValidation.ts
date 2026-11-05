/**
 * 编排关系校验（一层 Router）。所属域：前端 agent。
 *
 * 职责：把「候选列表 + 策略」校验成中文错误 / 提醒，供编排 Tab 的「校验关系图」按钮使用。
 * 约束：
 * - 纯函数，不依赖 Vue / DOM，便于 node:test 直接跑；
 * - 六种模板可持久：ROUTER_PARALLEL / FIXED_SEQUENCE / FIXED_PARALLEL / CRITIC_LOOP /
 *   ROUTER_THEN_SEQUENCE / COMPETE；
 *   子 Agent 只能是「获本编排允许」的 REACT 或 PLAN_AND_EXECUTE（ROUTER 恒禁止）；
 * - 评审环：执行者候选（排除评审者）必须恰好 1 个，评审者必填且不能与执行者相同；
 * - 竞争选优：启用候选至少 2 个，策略 JUDGE_PICK（默认）/ FIRST_SUCCESS；
 * - M4 口径：零候选 / 零启用候选都不允许，固定模板没有判路由兜底，同样必须整次失败；
 * - 一层结构下子不能是 ROUTER，因此不存在更深环；直接自环（子指向父）单独拦。
 * - 上下文策略三值：ISOLATED / PRIOR_SUMMARY_ONLY（仅串行）/ SHARED_SCRATCH（六值模板都可选）。
 */
import type { AgentRouteRow } from '@/types/agent'

/** 模板：判路由 → 多子并行 → 汇总（现网回归基线） */
export const MA_TEMPLATE_ROUTER_PARALLEL = 'ROUTER_PARALLEL'
/** 模板：跳过判路由，按 sort_order 串行 S1→S2→…；后步可见前置终答摘要 */
export const MA_TEMPLATE_FIXED_SEQUENCE = 'FIXED_SEQUENCE'
/** 模板：跳过判路由，全部启用候选吃同一份用户原问并行执行 */
export const MA_TEMPLATE_FIXED_PARALLEL = 'FIXED_PARALLEL'
/** 模板：跳过判路由，恰好 1 个执行者出稿 → 评审专家判定 → 有限回修 */
export const MA_TEMPLATE_CRITIC_LOOP = 'CRITIC_LOOP'
/** 模板：判路由选出有序子集 → 按判定序串行 → 汇总；后步可见前置终答摘要 */
export const MA_TEMPLATE_ROUTER_THEN_SEQUENCE = 'ROUTER_THEN_SEQUENCE'
/** 模板：同一问题并行交给多个候选，选优取 1 个子终答 */
export const MA_TEMPLATE_COMPETE = 'COMPETE'
/** 六值白名单；服务端同口径，非法/空回落 ROUTER_PARALLEL */
export const MA_TEMPLATES = [
  MA_TEMPLATE_ROUTER_PARALLEL,
  MA_TEMPLATE_FIXED_SEQUENCE,
  MA_TEMPLATE_FIXED_PARALLEL,
  MA_TEMPLATE_CRITIC_LOOP,
  MA_TEMPLATE_ROUTER_THEN_SEQUENCE,
  MA_TEMPLATE_COMPETE
] as const

/** 串行模板集合：只有这些模板的「前置摘要」才有意义（与服务端 AgentRuntimeConfig 同口径） */
export const MA_SERIAL_TEMPLATES = [MA_TEMPLATE_FIXED_SEQUENCE, MA_TEMPLATE_ROUTER_THEN_SEQUENCE] as const

/**
 * 判断是否串行模板（固定串行 / 路由后串行）。
 *
 * @param template 已归一的模板码
 * @return true＝后步可吃前置摘要
 */
export function isSerialTemplate(template: string): boolean {
  return (MA_SERIAL_TEMPLATES as readonly string[]).includes(template)
}

/** 评审环轮次默认值：P1 单轮（初稿 → 评审 → 至多一次回修） */
export const DEFAULT_CRITIC_MAX_ROUNDS = 1
/** 评审环轮次硬顶：与服务端 AgentRuntimeConfig.MAX_CRITIC_ROUNDS_CAP 一致 */
export const MAX_CRITIC_ROUNDS_CAP = 3

/** 竞争选优：评选模型选优（默认） */
export const MA_COMPETE_STRATEGY_JUDGE_PICK = 'JUDGE_PICK'
/** 竞争选优：第一个成功子的终答（无评选模型 / 评选失败时兜底） */
export const MA_COMPETE_STRATEGY_FIRST_SUCCESS = 'FIRST_SUCCESS'
/** 竞争选优策略白名单；服务端同口径，非法值回落 JUDGE_PICK */
export const MA_COMPETE_STRATEGIES = [MA_COMPETE_STRATEGY_JUDGE_PICK, MA_COMPETE_STRATEGY_FIRST_SUCCESS] as const
/** 竞争选优最少参与候选数：与服务端 AgentRuntimeConfig.MIN_COMPETE_CANDIDATES 一致 */
export const MIN_COMPETE_CANDIDATES = 2

/** 上下文策略：子互相隔离，只吃 sub_query / 用户原问 */
export const MA_CONTEXT_ISOLATED = 'ISOLATED'
/** 上下文策略：仅串行模板生效——后一步可见前置步骤终答摘要（单步 2000 / 累计 6000 字符） */
export const MA_CONTEXT_PRIOR_SUMMARY_ONLY = 'PRIOR_SUMMARY_ONLY'
/**
 * 上下文策略：本轮编排共享键控草稿区（ma_scratch_get / ma_scratch_put）。
 * 五种模板都可选；与前置摘要是同一字段单选，串行选了它就不再自动拼前置摘要。
 */
export const MA_CONTEXT_SHARED_SCRATCH = 'SHARED_SCRATCH'
/** 上下文策略白名单；服务端同口径，非法值按模板默认回落 */
export const MA_CONTEXT_POLICIES = [
  MA_CONTEXT_ISOLATED,
  MA_CONTEXT_PRIOR_SUMMARY_ONLY,
  MA_CONTEXT_SHARED_SCRATCH
] as const

/** 汇总策略：按顺序拼接 */
export const MA_AGGREGATE_CONCAT = 'CONCAT'
/** 汇总策略：父模型整合 */
export const MA_AGGREGATE_SUMMARIZE = 'SUMMARIZE'
/** 汇总策略：评选模型在多份子终答里投票选一路原文；失败降级 CONCAT */
export const MA_AGGREGATE_VOTE = 'VOTE'
/** 汇总策略白名单；服务端同口径，未知值回落 CONCAT */
export const MA_AGGREGATES = [MA_AGGREGATE_CONCAT, MA_AGGREGATE_SUMMARIZE, MA_AGGREGATE_VOTE] as const

/**
 * 该模板是否支持「投票选优」汇总。
 *
 * <p>与服务端 `AgentRuntimeConfig.supportsVote` 同口径：只放「等齐后统一出终答」的模板。
 * COMPETE 的选优由模板自身（PICK）负责、CRITIC_LOOP 不做汇总、FIXED_SEQUENCE 串行主语义不变。
 *
 * @param template 已归一的模板码
 * @return true＝可配 VOTE
 */
export function supportsVoteTemplate(template: string): boolean {
  return template === MA_TEMPLATE_ROUTER_PARALLEL
    || template === MA_TEMPLATE_FIXED_PARALLEL
    || template === MA_TEMPLATE_ROUTER_THEN_SEQUENCE
}

/** 子运行模式：ReAct（默认，恒在允许集合里） */
export const MA_CHILD_MODE_REACT = 'REACT'
/** 子运行模式：计划执行（P&E），需编排根显式允许 */
export const MA_CHILD_MODE_PLAN = 'PLAN_AND_EXECUTE'
/** 子运行模式白名单；ROUTER 永远不在其中 */
export const MA_CHILD_RUN_MODES = [MA_CHILD_MODE_REACT, MA_CHILD_MODE_PLAN] as const

/**
 * 子运行模式归一：白名单过滤、去重、恒补 REACT、固定顺序。
 *
 * <p>与服务端同口径：空 / 全非法回落 `["REACT"]`；ROUTER 与任何第四值丢弃；
 * 支持被写成单个逗号串的历史脏值（如 `"REACT,PLAN_AND_EXECUTE"`）。
 *
 * @param values 原始配置，可空
 * @return 至少含 REACT 的数组，顺序 REACT → PLAN_AND_EXECUTE
 */
export function normalizeChildRunModes(values?: (string | null | undefined)[] | null): string[] {
  const out: string[] = [MA_CHILD_MODE_REACT]
  if (!values || !values.length) {
    return out
  }
  for (const item of values) {
    if (item == null) {
      continue
    }
    for (const token of String(item).split(/[,;\s]+/)) {
      const mode = token.trim().toUpperCase()
      if (mode === MA_CHILD_MODE_PLAN && !out.includes(MA_CHILD_MODE_PLAN)) {
        out.push(MA_CHILD_MODE_PLAN)
      }
    }
  }
  return out
}

/**
 * 子运行模式中文名。
 *
 * @param mode REACT / PLAN_AND_EXECUTE
 * @return 中文展示名
 */
export function childRunModeText(mode?: string | null): string {
  if (mode === MA_CHILD_MODE_PLAN) {
    return '计划执行'
  }
  if (mode === MA_CHILD_MODE_REACT) {
    return 'ReAct'
  }
  return mode ? String(mode) : '已删除'
}

/**
 * 模板归一：五值白名单外（含空）回落 ROUTER_PARALLEL。
 *
 * @param value 库内或表单里的模板值
 * @return 合法模板码
 */
export function normalizeTemplate(value?: string | null): string {
  return MA_TEMPLATES.includes((value || '') as (typeof MA_TEMPLATES)[number])
    ? String(value)
    : MA_TEMPLATE_ROUTER_PARALLEL
}

/**
 * 上下文策略归一：与服务端同口径。
 *
 * <p>SHARED_SCRATCH 与模板无关，任一模板都原样保留；
 * PRIOR_SUMMARY_ONLY 只对串行模板（固定串行 / 路由后串行）有意义，其它模板收敛回 ISOLATED；
 * 串行未显式选时默认 PRIOR_SUMMARY_ONLY。
 *
 * @param policy   当前策略值
 * @param template 已归一的模板码
 * @return ISOLATED | PRIOR_SUMMARY_ONLY | SHARED_SCRATCH
 */
export function normalizeContextPolicy(policy: string | undefined | null, template: string): string {
  // 共享草稿区是显式选择，不能被并行模板洗掉
  if (policy === MA_CONTEXT_SHARED_SCRATCH) {
    return MA_CONTEXT_SHARED_SCRATCH
  }
  if (!isSerialTemplate(template)) {
    return MA_CONTEXT_ISOLATED
  }
  if (policy === MA_CONTEXT_ISOLATED) {
    return MA_CONTEXT_ISOLATED
  }
  return MA_CONTEXT_PRIOR_SUMMARY_ONLY
}

/**
 * 评审环轮次归一：缺省/非法回落 1，继续夹到 1～3（与服务端同口径）。
 *
 * @param value 表单或库内值
 * @return 1～3 的整数
 */
export function normalizeCriticMaxRounds(value?: number | null): number {
  const n = typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : DEFAULT_CRITIC_MAX_ROUNDS
  if (n < 1) {
    return DEFAULT_CRITIC_MAX_ROUNDS
  }
  return Math.min(n, MAX_CRITIC_ROUNDS_CAP)
}

/**
 * 竞争选优策略归一：缺省/非法回落 JUDGE_PICK（与服务端同口径）。
 *
 * @param value 表单或库内值
 * @return JUDGE_PICK | FIRST_SUCCESS
 */
export function normalizeCompeteStrategy(value?: string | null): string {
  const text = (value || '').trim().toUpperCase()
  return (MA_COMPETE_STRATEGIES as readonly string[]).includes(text) ? text : MA_COMPETE_STRATEGY_JUDGE_PICK
}

/** 校验入参；routes 直接复用现网 RouteResp 字段 */
export interface OrchestrationValidationInput {
  /** 编排根 Agent 主键，用于拦直接自环 */
  parentAgentId: number
  /** 模板码；空按 ROUTER_PARALLEL */
  template?: string
  /** 汇总策略；空按 CONCAT */
  aggregate?: string
  /** 上下文策略；空按模板默认 */
  context?: string
  /** 最大并行子数；空按 3 */
  maxParallelChildren?: number
  /** 本编排允许的子运行模式；空按仅 REACT（与服务端同口径） */
  allowedChildRunModes?: string[]
  /** 评审环：评审专家 agentId；模板=CRITIC_LOOP 时必填 */
  criticAgentId?: number | null
  /** 评审环：最大评审轮次；空按 1 */
  criticMaxRounds?: number
  /** 竞争选优：JUDGE_PICK（默认）/ FIRST_SUCCESS */
  competeStrategy?: string
  /** 候选列表，含停用行 */
  routes: AgentRouteRow[]
}

/** 校验结果；errors 非空即不通过 */
export interface OrchestrationValidationResult {
  ok: boolean
  errors: string[]
  warnings: string[]
  /** 参与判路由的启用候选数 */
  enabledCount: number
}

/**
 * 候选展示名：优先名称，其次编码，最后主键。
 *
 * @param row 路由行
 * @return 用于提示文案的名字
 */
function labelOf(row: AgentRouteRow): string {
  return row.childName?.trim() || row.childCode?.trim() || `#${row.childAgentId}`
}

/**
 * 执行编排关系校验。
 *
 * @param input 模板、汇总、并行上限与候选列表
 * @return 中文错误 / 提醒；errors 为空时 ok=true
 */
export function validateOrchestration(input: OrchestrationValidationInput): OrchestrationValidationResult {
  const errors: string[] = []
  const warnings: string[] = []
  const routes = input.routes || []
  const enabled = routes.filter((row) => row.status === 1)
  // 允许的子运行模式：归一后恒含 REACT，P&E 只有显式勾选才会出现在这里
  const allowedChildModes = normalizeChildRunModes(input.allowedChildRunModes)

  // 策略侧：六值白名单之外直接拦，避免前端选了未知值却落库被服务端悄悄洗掉
  const template = normalizeTemplate(input.template)
  const isCriticTemplate = template === MA_TEMPLATE_CRITIC_LOOP
  const isCompeteTemplate = template === MA_TEMPLATE_COMPETE
  const serialTemplate = isSerialTemplate(template)
  if (input.template && input.template !== template) {
    errors.push(
      `未知协作模式「${input.template}」，只能是 ROUTER_PARALLEL / FIXED_SEQUENCE / FIXED_PARALLEL / CRITIC_LOOP / ROUTER_THEN_SEQUENCE / COMPETE`
    )
  }
  const aggregate = input.aggregate || MA_AGGREGATE_CONCAT
  if (aggregate !== MA_AGGREGATE_CONCAT && aggregate !== MA_AGGREGATE_SUMMARIZE
    && aggregate !== MA_AGGREGATE_VOTE) {
    errors.push(`未知结果合成「${aggregate}」，只能是 CONCAT、SUMMARIZE 或 VOTE`)
  }
  // VOTE 只对支持投票的模板成立；其它模板服务端会收敛回 CONCAT，这里提前提醒避免误解
  const voteAggregate = aggregate === MA_AGGREGATE_VOTE && supportsVoteTemplate(template)
  if (aggregate === MA_AGGREGATE_VOTE && !supportsVoteTemplate(template)) {
    warnings.push('投票选优只对路由并行 / 固定并行 / 路由后串行生效：当前模板保存后会按拼接执行（CONCAT）')
  }
  const context = normalizeContextPolicy(input.context, template)
  if (!serialTemplate && input.context === MA_CONTEXT_PRIOR_SUMMARY_ONLY) {
    // 不按硬错误处理：服务端会收敛回 ISOLATED，这里只提醒用户别误会配置生效了
    warnings.push('前置摘要只对串行模板（固定串行 / 路由后串行）生效：当前模板会按多项隔离执行（ISOLATED）')
  }
  if (input.context && !(MA_CONTEXT_POLICIES as readonly string[]).includes(input.context)) {
    // 未知策略服务端会按模板默认回落；这里只提醒，避免用户以为保存了什么特殊策略
    warnings.push(`未知上下文共享「${input.context}」，保存后按模板默认回落为 ${context}`)
  }
  if (!serialTemplate && context === MA_CONTEXT_SHARED_SCRATCH) {
    // 并行多子是共享草稿区的主场景：说明工具口径，避免用户以为会自动共享完整对话
    warnings.push(
      '共享草稿区：子 Agent 可用 ma_scratch_get / ma_scratch_put 主动读写键控草稿；同键第二次写入会被拒绝，草稿仅本轮有效'
    )
  }
  if (serialTemplate && context === MA_CONTEXT_SHARED_SCRATCH) {
    // 串行模板推荐前置摘要：共享草稿要子主动读写，串行时反而多一次工具往返
    warnings.push(
      '串行模板推荐「仅前置摘要」：选了共享草稿区后不再自动拼接前置摘要，后步需要自己调用 ma_scratch_get 读取'
    )
  }
  const maxParallel = input.maxParallelChildren ?? 3
  if (maxParallel < 1 || maxParallel > 5) {
    errors.push(`并行上限必须在 1～5 之间，当前 ${maxParallel}`)
  }

  // M4：零候选不允许；全部停用同样等于没有可用子
  if (!routes.length) {
    errors.push('没有任何候选子 Agent：至少添加 1 个获本编排允许的子 Agent（M4：不允许零子）')
  } else if (!enabled.length && !isCriticTemplate && !isCompeteTemplate) {
    // 评审环 / 竞争选优的「启用候选」口径各自有更准确的文案，这里不重复报
    errors.push('没有启用的候选子 Agent：判路由需要至少 1 个启用候选')
  }

  // route_key 同父唯一；停用行也检查，避免重新启用时撞唯一约束
  const keySeen = new Map<string, string>()
  for (const row of routes) {
    const name = labelOf(row)
    const key = (row.routeKey || '').trim().toLowerCase()
    if (key) {
      const first = keySeen.get(key)
      if (first) {
        errors.push(`route_key「${row.routeKey}」重复：「${first}」与「${name}」冲突`)
      } else {
        keySeen.set(key, name)
      }
    }

    // 停用行不参与执行：只提醒，不按硬错误处理
    if (row.status !== 1) {
      warnings.push(`候选「${name}」已停用，不会参与执行`)
      continue
    }

    // 直接自环是本层唯一可能形成的环；更深环被「子不得为 ROUTER」拦住
    if (row.childAgentId === input.parentAgentId) {
      errors.push(`候选「${name}」指向自己，形成自环`)
    }
    if (!row.childRunMode) {
      errors.push(`候选「${name}」的子 Agent 已删除或不存在`)
    } else if (row.childRunMode === 'ROUTER') {
      errors.push(`候选「${name}」是 ROUTER：子 Agent 只能是 ReAct 或计划执行，禁止 Router 套 Router`)
    } else if (row.childRunMode !== MA_CHILD_MODE_REACT && row.childRunMode !== MA_CHILD_MODE_PLAN) {
      errors.push(`候选「${name}」是 ${row.childRunMode}：子 Agent 只能是 ReAct 或计划执行`)
    } else if (!allowedChildModes.includes(row.childRunMode)) {
      // 与保存接口同口径：allowed 缩回仅 ReAct 后，仍挂着的 P&E 子必须报 error（保存失败）
      errors.push(
        `候选「${name}」是${childRunModeText(row.childRunMode)}：编排未允许子使用${childRunModeText(row.childRunMode)}模式，请先在策略区勾选`
      )
    }
    if (row.childStatus !== 1) {
      errors.push(`候选「${name}」的子 Agent 已停用：停用子不能参与编排`)
    }
    // 子后来被关掉「允许被编排调用」时，保存前就报 error；undefined 视为允许（兼容旧数据）
    if (row.childAllowOrchestrated === 0) {
      errors.push(`候选「${name}」未允许被编排调用：请先在该 Agent 的基本信息中开启「允许被编排调用」`)
    }
    if (!key) {
      errors.push(`候选「${name}」缺少 route_key，编排无法定位该子`)
    }
    if (!(row.intent || '').trim()) {
      errors.push(`候选「${name}」缺少意图描述`)
    }
  }

  // 评审环：执行者恰好 1 个 + 评审者必填且非自评；与保存/运行时同口径
  if (isCriticTemplate) {
    const criticId = input.criticAgentId ?? null
    // 评审者可出现在候选表：按 id 从执行者计数中排除，避免同一份配置随「是否挂成候选」摇摆
    const executors = enabled.filter((row) => criticId == null || row.childAgentId !== criticId)
    if (criticId == null) {
      errors.push('评审环需要选择评审专家：请在策略区选择 1 个已启用且已开启「允许被编排调用」的 Agent')
    }
    if (routes.length && executors.length !== 1) {
      const criticIsCandidate = criticId != null && enabled.some((row) => row.childAgentId === criticId)
      if (executors.length === 0 && criticIsCandidate) {
        errors.push('评审专家不能与执行者相同：请换一个评审专家，或把候选换成别的执行者')
      } else if (executors.length > 1) {
        errors.push(
          `评审环只允许 1 个执行者候选，当前 ${executors.length} 个（${executors
            .map((row) => labelOf(row))
            .join('、')}）：请停用多余候选，只保留 1 个执行者`
        )
      } else if (executors.length === 0) {
        errors.push('评审环需要恰好 1 个启用中的执行者候选')
      }
    }
    if (input.criticMaxRounds != null) {
      const rounds = normalizeCriticMaxRounds(input.criticMaxRounds)
      if (rounds !== Math.trunc(input.criticMaxRounds)) {
        errors.push(`最大评审轮次必须在 1～3 之间，当前 ${input.criticMaxRounds}`)
      }
    }
    // 说明性提醒：评审环不做多段拼接，评审意见默认只进时间线
    warnings.push('评审环：执行者出稿后由评审专家判定，不同意则回修（默认 1 轮）；终答＝最后一版执行稿')
  }

  // 竞争选优：至少 2 个启用候选 + 策略白名单；与保存/运行时同口径
  if (isCompeteTemplate) {
    if (enabled.length < MIN_COMPETE_CANDIDATES) {
      errors.push(
        `竞争选优至少需要 ${MIN_COMPETE_CANDIDATES} 个启用候选（当前 ${enabled.length} 个）：请添加或启用更多候选专家`
      )
    }
    const strategy = normalizeCompeteStrategy(input.competeStrategy)
    if (input.competeStrategy && strategy !== input.competeStrategy.trim().toUpperCase()) {
      warnings.push(`未知选优策略「${input.competeStrategy}」，保存后按默认 ${MA_COMPETE_STRATEGY_JUDGE_PICK} 回落`)
    }
    warnings.push(
      strategy === MA_COMPETE_STRATEGY_FIRST_SUCCESS
        ? '竞争选优：直接取候选顺序第一个成功子的终答（不调用评选模型）；全部失败才报错'
        : '竞争选优：评选模型比较各子终答后选 1 个赢家；评选模型不可用时自动降级为第一个成功子'
    )
  }

  // SUMMARIZE 依赖父 ROUTER 文本模型；这里拿不到模型绑定，只能提醒用户确认
  if (aggregate === MA_AGGREGATE_SUMMARIZE) {
    warnings.push(
      '结果合成为 SUMMARIZE：请确认父 Agent 已在「模型配置」绑定 ROUTER 用途的文本模型；未绑定时会降级为 CONCAT'
    )
  }
  // VOTE 依赖同一份评选模型；票池只有 1 路时不会调用，这一点在提醒里写清
  if (voteAggregate) {
    warnings.push(
      '投票选优：多个成功子终答交给评选模型选一路原文作为终答（默认用判路由模型）；'
      + '只有 1 路成功时不投票直接出，评选失败则降级为拼接'
    )
  }
  if (!isCriticTemplate && enabled.length > maxParallel) {
    if (template === MA_TEMPLATE_FIXED_PARALLEL) {
      // 固定并行没有判路由兜底：超上限的候选直接不跑，必须让用户看到会少几个
      warnings.push(
        `启用候选 ${enabled.length} 个，超过并行上限 ${maxParallel}：只跑 sort_order 前 ${maxParallel} 个，其余标「已跳过」`
      )
    } else if (template === MA_TEMPLATE_ROUTER_THEN_SEQUENCE) {
      // 路由后串行：并行上限复用为「串行步数上限」，超出的按判定序标「已跳过」
      warnings.push(
        `启用候选 ${enabled.length} 个，超过并行上限 ${maxParallel}：只跑判定顺序前 ${maxParallel} 个，其余标「已跳过」`
      )
    } else if (template === MA_TEMPLATE_ROUTER_PARALLEL) {
      warnings.push(`启用候选 ${enabled.length} 个，超过并行上限 ${maxParallel}：超出的意图不会同时执行`)
    } else if (template === MA_TEMPLATE_COMPETE) {
      // 竞争选优需要同时比较：超上限的候选不参与，必须让用户看到竞争面被缩小
      warnings.push(
        `启用候选 ${enabled.length} 个，超过并行上限 ${maxParallel}：只有 sort_order 前 ${maxParallel} 个参与竞争，其余标「已跳过」`
      )
    } else {
      // 固定串行不设并行上限：全部候选按顺序跑完
      warnings.push(`固定串行按顺序执行全部 ${enabled.length} 个启用候选，不受并行上限限制`)
    }
  }
  // 串行模板提醒：后步可见前置终答摘要，但不带工具原文
  if (serialTemplate && context === MA_CONTEXT_PRIOR_SUMMARY_ONLY) {
    warnings.push(
      template === MA_TEMPLATE_ROUTER_THEN_SEQUENCE
        ? '路由后串行：先判路由选出专家与子问，再按判定顺序串行；后步只看前置终答摘要（单步 2000 / 累计 6000 字符），不含工具原文'
        : '固定串行：后一步可见前置步骤终答摘要（单步 2000 / 累计 6000 字符），不含工具原文'
    )
  }

  return { ok: errors.length === 0, errors, warnings, enabledCount: enabled.length }
}
