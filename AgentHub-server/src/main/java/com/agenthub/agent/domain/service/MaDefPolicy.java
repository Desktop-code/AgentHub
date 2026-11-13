package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 多智能体方案保存侧规则。所属域：agent。
 *
 * <p>本类只做「写库前必须成立」的硬校验，与执行器解耦：
 * <ul>
 *   <li>普通方案的协作模式只收成两种：意图路由分发、路由后串行；历史模板在保存时迁过来；</li>
 *   <li>绑根必须存在、已启用、是 ROUTER，且未被其它启用方案占用；</li>
 *   <li>候选必须非 ROUTER、已启用、允许被编排、运行模式被本方案允许；</li>
 *   <li>候选 route_key 在方案内大小写不敏感唯一；</li>
 *   <li>评审环模板复用 {@link AgentCriticPolicy} 的角色规则，保持与旧 Tab 完全一致；</li>
 *   <li>竞争选优模板至少 2 个启用候选，否则「同题竞争 + 选优」无从谈起。</li>
 * </ul>
 *
 * <p>候选均为一层叶子，因此不存在「多层环」；「禁环」在本模型里等价于
 * 「候选不能是 ROUTER / 不能指向自己」，由上述规则覆盖。
 *
 * <p>刀5 新增「另一套编排方案」候选：目标校验见 {@link #assertMaTargets}，
 * 核心是：不得是默认接待、不能自己挂自己、不能互挂成环、必须有方案说明。
 * 方案套方案的深度交给运行时 3 层熔断，保存侧不再按「只下探一层」整棵砍掉。
 */
public final class MaDefPolicy {

    /** 单方案候选数硬顶：与 Multi-Agent 定稿「单编排最大子数硬顶 16」一致 */
    public static final int MAX_CANDIDATES = 16;

    private MaDefPolicy() {
    }

    /**
     * 模板白名单校验（新建 / 编辑普通方案的可选集合）。
     *
     * <p>工作台入口类型 {@code ENTRY_ROUTER} 是数据锁定值，<b>不在</b>白名单里：
     * 页面不能新建入口，也不能把普通方案改成入口。若请求里带着它，直接给可读的原因。
     *
     * @param template 模板码；历史并行类 / 串行类会先收成两种协作模式之一
     * @return 可落库的模板码（{@code ROUTER_PARALLEL} 或 {@code ROUTER_THEN_SEQUENCE}）
     * @throws BizException 空、认不出，或试图把普通方案改成入口
     */
    public static String assertTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "协作模式不能为空");
        }
        if (AgentRuntimeConfig.isEntryRouterTemplate(template.trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口类型由平台数据锁定，不能新建、不能把普通方案改成入口；"
                            + "入口只通过默认接待那一行迁移得到");
        }
        String canonical = AgentRuntimeConfig.canonicalMaTemplate(template);
        if (canonical == null || AgentRuntimeConfig.isEntryRouterTemplate(canonical)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "协作模式只能是「意图路由分发」或「路由后串行」");
        }
        return canonical;
    }

    /**
     * 路由后串行的上下文必须是「继承前置结果」。
     *
     * <p>显式隔离或共享草稿都拒绝，不在保存时悄悄改掉：调用方改完再提交。
     * 没带策略（null）放行，交给归一化补成继承。并行模式不在这里改。
     *
     * @param template 已收成两种模式之一的模板码；入口不锁
     * @param config   合并后的运行配置；可空
     * @throws BizException 串行却收到隔离或共享草稿
     */
    public static void assertSerialContextLocked(String template, AgentRuntimeConfig config) {
        if (!AgentRuntimeConfig.TEMPLATE_ROUTER_THEN_SEQUENCE.equals(
                AgentRuntimeConfig.canonicalMaTemplate(template))) {
            return;
        }
        String policy = config == null ? null : config.getMaContextPolicy();
        if (AgentRuntimeConfig.CONTEXT_ISOLATED.equals(policy)
                || AgentRuntimeConfig.CONTEXT_SHARED_SCRATCH.equals(policy)) {
            throw new BizException(ErrorCode.BAD_REQUEST, AgentRuntimeConfig.SERIAL_CONTEXT_REJECTED);
        }
    }

    /**
     * 入口行的类型锁定：入口不能改成普通方案，也不能被改成别的类型。
     *
     * <p>为什么放在保存侧而不是只靠页面置灰：入口是全平台唯一的一条网关，
     * 一旦被改成普通 ROUTER_PARALLEL，工作台就没有入口了，属于不可恢复的配置事故。
     * 「不新做一套锁定功能」——就复用这一条保存校验，没有额外的锁表 / 锁字段。
     *
     * <p>根绑定不再校验：请求里遗留的 {@code rootAgentId} 一律忽略，
     * 也不要求用户先手清历史值才能保存。
     *
     * @param existing          库内方案；新建传 null
     * @param requestedTemplate 本次请求的模板码
     * @throws BizException 入口行被改类型
     */
    public static void assertEntryLocked(MaDef existing, String requestedTemplate) {
        if (existing == null || !AgentRuntimeConfig.isEntryRouterTemplate(existing.getTemplate())) {
            return;
        }
        if (!AgentRuntimeConfig.isEntryRouterTemplate(requestedTemplate)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "「" + displayName(existing) + "」是对话入口，类型不能改；"
                            + "要新建场景路由请另建普通方案");
        }
    }

    /**
     * 入口行的身份字段锁定：编码、名称、备注（方案说明）三项只读。
     *
     * <p>为什么连名称也要锁：入口在全平台只有这一条，展示名固定为「Router」，
     * 备注写的是「唯一入口 + 哪些动作禁止」；这两项一旦可改，运营就分不清哪条是入口、
     * 也看不清它为什么不能被删除 / 入池，属于身份漂移。模型槽与 ROUTER 提示词不在此列，
     * 那两项仍可正常配置（打开同一套方案编辑页即可）。
     *
     * <p>放行口径：字段为 {@code null}（请求未携带，如只改模型）视为「不改」；
     * 有值但与锁定值不一致时直接拒绝，而不是静默丢弃，避免前端以为改成功了。
     *
     * @param existing    库内方案，必须已确认是入口类型
     * @param code        请求里的编码；可空
     * @param name        请求里的名称；可空
     * @param description 请求里的备注；可空
     * @throws BizException 试图改动入口的编码 / 名称 / 备注
     */
    public static void assertEntryIdentity(MaDef existing, String code, String name, String description) {
        if (existing == null || !AgentRuntimeConfig.isEntryRouterTemplate(existing.getTemplate())) {
            return;
        }
        // 编码是租户内唯一业务键：改了等于换了一条方案，入口不允许换身份
        if (code != null && !code.trim().equals(safeTrim(existing.getCode()))) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口的编码固定为「" + existing.getCode() + "」，不允许修改");
        }
        if (name != null && !name.trim().equals(AgentRuntimeConfig.ENTRY_ROUTER_NAME)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口的名称固定为「" + AgentRuntimeConfig.ENTRY_ROUTER_NAME + "」，不允许修改");
        }
        if (description != null && !description.trim().equals(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口的备注由平台固定，不允许修改；模型与 ROUTER 提示词仍可配置");
        }
    }

    /** 空安全的 trim：入口行的编码理论上不会为空，这里只为错误文案不抛 NPE。 */
    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 候选纪律校验：逐个按旧 Tab 的同一套门闩检查，并限制候选数硬顶与 route_key 唯一。
     *
     * <p>方案就是自己的根：子模式是否获准直接读本方案运行配置，不再为「虚拟根」合成 Agent 父壳。
     *
     * @param runtime    本次要落库的方案配置（读允许的子运行模式）
     * @param candidates 候选请求（agentId / routeKey 已非空）
     * @param lookup     按 id 取未删除 Agent
     * @throws BizException 任一候选不合法
     */
    public static void assertCandidates(AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                                        Function<Long, Optional<AgentDef>> lookup) {
        List<MaCandidate> list = candidates == null ? List.of() : candidates;
        if (list.size() > MAX_CANDIDATES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单方案候选最多 " + MAX_CANDIDATES + " 个");
        }
        Set<String> keys = new HashSet<>();
        for (MaCandidate candidate : list) {
            if (candidate.getRouteKey() == null || candidate.getRouteKey().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "候选路由键不能为空");
            }
            String key = candidate.getRouteKey().trim().toLowerCase(Locale.ROOT);
            if (!keys.add(key)) {
                throw new BizException(ErrorCode.DUPLICATE, "候选路由键「" + candidate.getRouteKey() + "」重复");
            }
            // MA / 工作流目标（刀5 / 刀5B）没有子 Agent：存在性 / 防循环由 assertMaTargets /
            // assertWorkflowTargets 统一校验，这里只查路由键与目标主键是否齐全
            if (candidate.isMaTarget()) {
                if (candidate.getMaDefId() == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "候选目标为编排方案时必须指定方案");
                }
                continue;
            }
            if (candidate.isWorkflowTarget()) {
                if (candidate.getWorkflowDefId() == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "候选目标为工作流时必须指定工作流");
                }
                continue;
            }
            if (candidate.getAgentId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "候选子 Agent 不能为空");
            }
            // 意图说明不再由候选自带：单一真源是 Agent 简介，校验见 assertCandidateDescriptions
            AgentDef child = lookup.apply(candidate.getAgentId()).orElse(null);
            // 复用现网子侧门闩：非 ROUTER、已启用、允许被编排、子模式获准；父配置来自方案自身
            AgentRoutePolicy.assertSchemeChild(runtime, child);
        }
    }

    /**
     * MA 目标校验（刀5）：另一套方案能不能被挂进本方案的路由池。
     *
     * <p>硬规则（与工作台默认接待共用同一套，避免两处口径漂移）：
     * <ol>
     *   <li>目标存在、同租户、已启用；</li>
     *   <li>不能指向自己；</li>
     *   <li>不能是平台默认接待（{@code is_reception_default=1}）——网关不得被当作目标，防止回指自己；</li>
     *   <li>不能与目标互挂，也不能沿方案候选绕回自己；</li>
     *   <li>目标方案必须有说明：说明就是它的意图说明，空说明进池后判路由无从判断；</li>
     *   <li>模板必须是会判路由的模板（ROUTER_PARALLEL / ROUTER_THEN_SEQUENCE）：
     *       固定模板与评审环不判路由，挂 MA 目标没有语义。</li>
     * </ol>
     *
     * @param candidates      本次要保存的候选
     * @param selfMaId        当前方案主键；新建传 null
     * @param template        当前方案的模板码
     * @param tenantId        当前租户
     * @param lookup          按 id 取方案
     * @param candidatesOf    按方案 id 取候选（判「叶子」）
     * @param toleratedMaIds  保存前已绑定的目标方案主键（历史数据允许说明为空）；新建传空集合
     * @throws BizException 任一 MA 目标不合法
     */
    public static void assertMaTargets(List<MaCandidate> candidates, Long selfMaId, String template,
                                       long tenantId, Function<Long, Optional<MaDef>> lookup,
                                       Function<Long, List<MaCandidate>> candidatesOf,
                                       Set<Long> toleratedMaIds) {
        List<MaCandidate> list = candidates == null ? List.of() : candidates;
        Set<Long> tolerated = toleratedMaIds == null ? Set.of() : toleratedMaIds;
        Set<Long> seen = new HashSet<>();
        for (MaCandidate candidate : list) {
            if (!candidate.isMaTarget()) {
                continue;
            }
            Long targetId = candidate.getMaDefId();
            if (!seen.add(targetId)) {
                throw new BizException(ErrorCode.DUPLICATE, "同一编排方案不能重复加入路由池");
            }
            MaDef target = lookup.apply(targetId).orElse(null);
            if (target == null || target.getTenantId() == null || target.getTenantId() != tenantId) {
                throw new BizException(ErrorCode.NOT_FOUND, "要加入的编排方案不存在或不属于当前租户");
            }
            if (selfMaId != null && selfMaId.equals(target.getId())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "路由池不能包含当前方案自己");
            }
            if (target.getEnabled() == null || target.getEnabled() != AgentStatus.ENABLED) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "编排方案「" + displayName(target) + "」已停用，请先启用再作为路由目标");
            }
            if (target.getIsReceptionDefault() != null && target.getIsReceptionDefault() == 1) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "平台默认接待不能被加进路由池：网关只能向内派发，不能再派回默认接待");
            }
            // 入口类型单独再拦一次：即使将来默认位被误清，入口也仍然是全平台唯一网关，不能入池
            if (AgentRuntimeConfig.isEntryRouterTemplate(target.getTemplate())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "「" + displayName(target) + "」是对话入口，不能作为路由目标；"
                                + "入口只负责向内分发，不做任何方案或专家名下的候选");
            }
            // 自己挂自己已在上面拦住；这里拦 A↔B 以及更长的方案回环。专家、工作流不构成环
            assertMountCycle(selfMaId, target, lookup, candidatesOf);
            if (!AgentRuntimeConfig.routesByJudge(template)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "只有会判路由的模板（对话入口 / ROUTER_PARALLEL / ROUTER_THEN_SEQUENCE）"
                                + "才能把编排方案作为候选");
            }
            boolean toleratedTarget = tolerated.contains(target.getId());
            if (!toleratedTarget && (target.getDescription() == null || target.getDescription().isBlank())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "编排方案「" + displayName(target) + "」还没有填写方案说明；"
                                + "意图说明取自方案说明，请先到多智能体补上再加入路由池");
            }
        }
    }

    /**
     * 工作流目标校验（刀5B）：已发布工作流能不能被挂进本方案的路由池。
     *
     * <p>硬规则与工作台默认接待共用同一套口径：
     * <ol>
     *   <li>目标存在、同租户、未删除；</li>
     *   <li>必须是「已有发布」状态并带默认发布指针（{@code status=HAS_RELEASE} 且
     *       {@code default_release_id} 非空）——禁止把草稿挂进路由池；</li>
     *   <li>必须有说明：说明就是它的意图说明，空说明进池后判路由无从判断；</li>
     *   <li>不能重复加入同一方案；</li>
     *   <li>模板必须是会判路由的模板（ROUTER_PARALLEL / ROUTER_THEN_SEQUENCE）。</li>
     * </ol>
     *
     * <p>为什么用 {@link WorkflowTargetInfo} 而不是直接依赖 workflow 域模型：agent 域不应
     * 反向依赖 workflow 域的实体，调用方在 application 层把定义翻译成这条只读快照即可。
     *
     * @param candidates        本次要保存的候选
     * @param template          当前方案的模板码
     * @param tenantId          当前租户
     * @param lookup            按 workflow_def.id 取快照
     * @param toleratedWorkflowIds 保存前已绑定的工作流主键（历史数据允许说明为空）；新建传空集合
     * @throws BizException 任一工作流目标不合法
     */
    public static void assertWorkflowTargets(List<MaCandidate> candidates, String template, long tenantId,
                                             Function<Long, Optional<WorkflowTargetInfo>> lookup,
                                             Set<Long> toleratedWorkflowIds) {
        List<MaCandidate> list = candidates == null ? List.of() : candidates;
        Set<Long> tolerated = toleratedWorkflowIds == null ? Set.of() : toleratedWorkflowIds;
        Set<Long> seen = new HashSet<>();
        for (MaCandidate candidate : list) {
            if (!candidate.isWorkflowTarget()) {
                continue;
            }
            Long targetId = candidate.getWorkflowDefId();
            if (targetId == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "候选目标为工作流时必须指定工作流");
            }
            if (!seen.add(targetId)) {
                throw new BizException(ErrorCode.DUPLICATE, "同一工作流不能重复加入路由池");
            }
            WorkflowTargetInfo target = lookup.apply(targetId).orElse(null);
            if (target == null || target.tenantId() == null || target.tenantId() != tenantId) {
                throw new BizException(ErrorCode.NOT_FOUND, "要加入的工作流不存在或不属于当前租户");
            }
            // 只认「有发布」+ 默认发布指针：草稿态 / 发布被删的工作流跑不起来，不能进池
            if (!WorkflowTargetInfo.STATUS_HAS_RELEASE.equals(target.status())
                    || target.defaultReleaseId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "工作流「" + displayName(target) + "」还没有可用的发布版本，请先发布再作为路由目标");
            }
            boolean toleratedTarget = tolerated.contains(targetId);
            if (!toleratedTarget && (target.description() == null || target.description().isBlank())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "工作流「" + displayName(target) + "」还没有填写说明；"
                                + "意图说明取自工作流说明，请先到编排补上再加入路由池");
            }
            if (!AgentRuntimeConfig.routesByJudge(template)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "只有会判路由的模板（对话入口 / ROUTER_PARALLEL / ROUTER_THEN_SEQUENCE）"
                                + "才能把工作流作为候选");
            }
        }
    }

    /**
     * 工作流目标的只读快照（刀5B）。所属域：agent 域的校验入参，非实体。
     *
     * @param id                workflow_def.id
     * @param tenantId          租户
     * @param name              工作流名称（用于错误文案与候选展示）
     * @param code              工作流编码（用于派生 route_key）
     * @param description       工作流说明，意图说明唯一真源
     * @param status            定义状态，只有 {@link #STATUS_HAS_RELEASE} 才允许进池
     * @param defaultReleaseId  默认发布指针；为空表示无可用发布
     */
    public record WorkflowTargetInfo(Long id, Long tenantId, String name, String code,
                                     String description, String status, Long defaultReleaseId) {

        /** 定义状态：至少有一个已发布版本（与 workflow_def.status 现网取值一致） */
        public static final String STATUS_HAS_RELEASE = "HAS_RELEASE";
    }

    /**
     * 意图说明来源校验：允许被编排的 Agent，简介视为必填。
     *
     * <p>为什么单独一段而不塞进 {@link #assertCandidates}：编辑既有方案时要能把
     * 「历史上就已绑定、但简介为空」的行原样保存（否则运营连改排序都做不了）。
     * 放行名单由调用方按「保存前该方案已有的 agentId」给出，新绑的 Agent 一律要求非空。
     *
     * @param candidates             候选（agentId 已非空）
     * @param lookup                 按 id 取 Agent
     * @param alreadyBoundAgentIds   本次保存前已在本方案里的 agentId；新建方案传空集合
     * @throws BizException 新绑的 Agent 没有简介
     */
    public static void assertCandidateDescriptions(List<MaCandidate> candidates,
                                                   Function<Long, Optional<AgentDef>> lookup,
                                                   Set<Long> alreadyBoundAgentIds) {
        List<MaCandidate> list = candidates == null ? List.of() : candidates;
        Set<Long> tolerated = alreadyBoundAgentIds == null ? Set.of() : alreadyBoundAgentIds;
        for (MaCandidate candidate : list) {
            Long agentId = candidate.getAgentId();
            if (agentId == null || tolerated.contains(agentId)) {
                continue;
            }
            AgentDef child = lookup.apply(agentId).orElse(null);
            if (child == null) {
                // 存在性由 AgentRoutePolicy 给更准确的文案，这里不抢错误
                continue;
            }
            if (child.getDescription() == null || child.getDescription().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "「" + displayName(child) + "」还没有填写简介；意图说明取自 Agent 简介，"
                                + "请先到 Agent 管理补上再绑定");
            }
        }
    }

    /**
     * 评审环角色闸：与旧 Tab 保存路径共用 {@link AgentCriticPolicy}，避免两套口径。
     *
     * @param runtime    方案配置
     * @param candidates 候选
     * @param lookup     子 Agent 查询
     * @throws BizException 执行者不是恰好 1 个，或评审者非法
     */
    public static void assertCriticRoles(AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                                         Function<Long, Optional<AgentDef>> lookup) {
        if (runtime == null || !runtime.isCriticLoop()) {
            return;
        }
        List<AgentRoute> routes = new ArrayList<>();
        for (MaCandidate candidate : candidates == null ? List.<MaCandidate>of() : candidates) {
            AgentRoute route = new AgentRoute();
            route.setId(candidate.getId());
            route.setChildAgentId(candidate.getAgentId());
            route.setSortOrder(candidate.getSortNo());
            route.setStatus(candidate.getEnabled());
            routes.add(route);
        }
        AgentCriticPolicy.assertRoles(runtime, routes, lookup);
    }

    /**
     * 竞争选优候选数闸：模板为 {@code COMPETE} 时，启用候选至少 2 个。
     *
     * <p>为什么在保存侧也拦：1 个候选只是「单 Agent 跑一遍」，用户会误以为竞争选优生效了。
     * 执行侧同样有一道运行时校验（候选被停用 / 撤权后可能临时不足），两处文案一致。
     *
     * @param runtime    方案配置
     * @param candidates 候选（含停用行）
     * @throws BizException 启用候选不足 2 个
     */
    public static void assertCompeteCandidates(AgentRuntimeConfig runtime, List<MaCandidate> candidates) {
        if (runtime == null || !runtime.isCompete()) {
            return;
        }
        long enabled = candidates == null ? 0 : candidates.stream()
                .filter(item -> item.getEnabled() != null && item.getEnabled() == AgentStatus.ENABLED)
                .count();
        if (enabled < AgentRuntimeConfig.MIN_COMPETE_CANDIDATES) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "竞争选优至少需要 " + AgentRuntimeConfig.MIN_COMPETE_CANDIDATES
                            + " 个启用候选（当前 " + enabled + " 个）：请添加或启用更多候选专家");
        }
    }

    /**
     * 候选数硬顶校验（供仅清空候选等旁路使用）。
     *
     * @param count 候选数
     */
    public static void assertCandidateCount(int count) {
        if (count > MAX_CANDIDATES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单方案候选最多 " + MAX_CANDIDATES + " 个");
        }
    }

    /**
     * 把已归一化的运行配置写回 JSON，供 {@link AgentRoutePolicy} 读取子模式白名单。
     */
    private static String runtimeStorageOf(AgentRuntimeConfig runtime) {
        return AgentRuntimeConfig.toStorageJson(runtime, AgentRunMode.ROUTER.name());
    }

    /**
     * 沿方案候选往下走，看会不会绕回当前方案。
     *
     * <p>只跟 MA 边：专家和工作流是叶子，入口 → 方案 → 专家不会被当成环。
     * 直接互挂（A 挂 B 且 B 挂 A）单独说「不能互相挂接」；更长的环给出整条路径。
     *
     * @param selfId       当前方案主键；新建还没有主键时不查
     * @param target       本次要挂上的方案
     * @param lookup       按主键取方案，用来拼中文名
     * @param candidatesOf 按方案主键取候选
     * @throws BizException 互挂或更长的环
     */
    private static void assertMountCycle(Long selfId, MaDef target,
                                         Function<Long, Optional<MaDef>> lookup,
                                         Function<Long, List<MaCandidate>> candidatesOf) {
        if (selfId == null || target == null || target.getId() == null) {
            return;
        }
        List<Long> path = new ArrayList<>();
        path.add(selfId);
        if (!reachesSelf(target.getId(), selfId, candidatesOf, new HashSet<>(), path)) {
            return;
        }
        boolean mutual = path.size() == 3 && selfId.equals(path.get(2)) && target.getId().equals(path.get(1));
        if (mutual) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "方案「" + displayName(lookup.apply(selfId).orElse(null), selfId)
                            + "」与「" + displayName(target) + "」不能互相挂接");
        }
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                chain.append(" → ");
            }
            Long id = path.get(i);
            chain.append(displayName(lookup.apply(id).orElse(null), id));
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "路由池形成循环：" + chain + "，请去掉回指后再保存");
    }

    /**
     * @param current      当前走到的方案
     * @param selfId       要找回去的起点
     * @param candidatesOf 按方案取候选
     * @param visiting     本路径已访问，防止脏数据把校验本身跑死
     * @param path         从起点开始的主键路径；命中时含回到起点的那一跳
     * @return true 表示能回到起点
     */
    private static boolean reachesSelf(Long current, Long selfId,
                                       Function<Long, List<MaCandidate>> candidatesOf,
                                       Set<Long> visiting, List<Long> path) {
        if (current == null) {
            return false;
        }
        if (selfId.equals(current)) {
            path.add(current);
            return true;
        }
        if (!visiting.add(current)) {
            return false;
        }
        path.add(current);
        List<MaCandidate> next = candidatesOf == null ? List.of() : candidatesOf.apply(current);
        if (next != null) {
            for (MaCandidate item : next) {
                if (item.isMaTarget() && reachesSelf(item.getMaDefId(), selfId, candidatesOf, visiting, path)) {
                    return true;
                }
            }
        }
        path.remove(path.size() - 1);
        visiting.remove(current);
        return false;
    }

    /** 错误文案里的方案称呼；定义缺失时退回主键，避免空指针。 */
    private static String displayName(MaDef def, Long id) {
        if (def == null) {
            return id == null ? "该方案" : "#" + id;
        }
        return displayName(def);
    }

    /** 错误文案里的 Agent 称呼：名称 → 编码 → 主键。 */
    private static String displayName(AgentDef agent) {
        if (agent.getName() != null && !agent.getName().isBlank()) {
            return agent.getName().trim();
        }
        if (agent.getCode() != null && !agent.getCode().isBlank()) {
            return agent.getCode().trim();
        }
        return agent.getId() == null ? "该 Agent" : "#" + agent.getId();
    }

    /** 错误文案里的方案 / 工作流称呼：名称 → 编码 → 主键。入口统一叫「Router」。 */
    private static String displayName(MaDef def) {
        if (AgentRuntimeConfig.isEntryRouterTemplate(def.getTemplate())) {
            // 入口的展示名是平台锁定的，不用库里的历史中文名，保证错误文案与列表一致
            return AgentRuntimeConfig.ENTRY_ROUTER_NAME;
        }
        if (def.getName() != null && !def.getName().isBlank()) {
            return def.getName().trim();
        }
        if (def.getCode() != null && !def.getCode().isBlank()) {
            return def.getCode().trim();
        }
        return def.getId() == null ? "该方案" : "#" + def.getId();
    }

    /** 错误文案里的工作流称呼：名称 → 编码 → 主键。 */
    private static String displayName(WorkflowTargetInfo def) {
        if (def.name() != null && !def.name().isBlank()) {
            return def.name().trim();
        }
        if (def.code() != null && !def.code().isBlank()) {
            return def.code().trim();
        }
        return def.id() == null ? "该工作流" : "#" + def.id();
    }
}
