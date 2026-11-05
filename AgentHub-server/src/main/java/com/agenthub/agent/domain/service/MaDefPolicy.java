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
 *   <li>模板必须在六值白名单内（含 COMPETE 竞争选优）；</li>
 *   <li>绑根必须存在、已启用、是 ROUTER，且未被其它启用方案占用；</li>
 *   <li>候选必须非 ROUTER、已启用、允许被编排、运行模式被本方案允许；</li>
 *   <li>候选 route_key 在方案内大小写不敏感唯一；</li>
 *   <li>评审环模板复用 {@link AgentCriticPolicy} 的角色规则，保持与旧 Tab 完全一致；</li>
 *   <li>竞争选优模板至少 2 个启用候选，否则「同题竞争 + 选优」无从谈起。</li>
 * </ul>
 *
 * <p>候选均为一层叶子，因此不存在「多层环」；「禁环」在本模型里等价于
 * 「候选不能是 ROUTER / 不能指向自己」，由上述规则覆盖。
 */
public final class MaDefPolicy {

    /** 单方案候选数硬顶：与 Multi-Agent 定稿「单编排最大子数硬顶 16」一致 */
    public static final int MAX_CANDIDATES = 16;

    private MaDefPolicy() {
    }

    /**
     * 模板白名单校验。
     *
     * @param template 模板码
     * @throws BizException 空或不在六值白名单内
     */
    public static void assertTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "编排模板不能为空");
        }
        if (!AgentRuntimeConfig.validTemplate(template)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的编排模板：" + template + "，仅支持 ROUTER_PARALLEL / FIXED_SEQUENCE / "
                            + "FIXED_PARALLEL / CRITIC_LOOP / ROUTER_THEN_SEQUENCE / COMPETE");
        }
    }

    /**
     * 绑根校验：非空时必须是本租户已启用的 ROUTER，且不允许被编排（ROUTER 恒不可作子）。
     *
     * @param root       绑定的 Agent；null 表示虚拟根，本方法直接放行
     * @param rootAgentId 请求里的绑根 id，用于错误文案
     * @throws BizException 绑根不存在 / 非 ROUTER / 已停用
     */
    public static void assertRootBindable(AgentDef root, Long rootAgentId) {
        if (rootAgentId == null) {
            return;
        }
        if (root == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "绑定的编排根不存在或已删除");
        }
        if (!AgentRunMode.ROUTER.name().equals(root.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "绑定的根必须是运行模式为 ROUTER 的 Agent");
        }
        if (root.getStatus() == null || root.getStatus() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "绑定的编排根「" + displayName(root) + "」已停用，请先启用该 Agent");
        }
        if (root.allowOrchestratedOn()) {
            // ROUTER 在 allowOrchestratedOn() 里恒为 false；这里显式拦一次，防未来规则回退
            throw new BizException(ErrorCode.BAD_REQUEST, "编排根不能开启「允许被编排调用」");
        }
    }

    /**
     * 同一绑根最多一条启用中方案；虚拟根（rootAgentId=null）不受限。
     *
     * @param other 同根下其它启用方案；空表示不冲突
     * @throws BizException 已存在第二条启用方案
     */
    public static void assertRootUnique(Optional<MaDef> other) {
        other.ifPresent(def -> {
            throw new BizException(ErrorCode.DUPLICATE,
                    "该 ROUTER 已绑定启用中的方案「" + def.getName() + "」，同一编排根只能有一条启用方案");
        });
    }

    /**
     * 候选纪律校验：逐个按旧 Tab 的同一套门闩检查，并限制候选数硬顶与 route_key 唯一。
     *
     * @param runtime    本次要落库的方案配置（读允许的子运行模式）
     * @param rootAgentId 绑定的根；虚拟根传 null
     * @param candidates 候选请求（agentId / routeKey 已非空）
     * @param lookup     按 id 取未删除 Agent
     * @throws BizException 任一候选不合法
     */
    public static void assertCandidates(AgentRuntimeConfig runtime, Long rootAgentId,
                                        List<MaCandidate> candidates,
                                        Function<Long, Optional<AgentDef>> lookup) {
        List<MaCandidate> list = candidates == null ? List.of() : candidates;
        if (list.size() > MAX_CANDIDATES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单方案候选最多 " + MAX_CANDIDATES + " 个");
        }
        // 虚拟根也造一个 ROUTER 父壳：AgentRoutePolicy 需要父配置判断子模式是否获准
        AgentDef syntheticParent = new AgentDef();
        syntheticParent.setId(rootAgentId);
        syntheticParent.setRunMode(AgentRunMode.ROUTER.name());
        syntheticParent.setRuntimeConfigJson(runtime == null ? null : runtimeStorageOf(runtime));

        Set<String> keys = new HashSet<>();
        for (MaCandidate candidate : list) {
            if (candidate.getAgentId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "候选子 Agent 不能为空");
            }
            if (candidate.getRouteKey() == null || candidate.getRouteKey().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "候选路由键不能为空");
            }
            String key = candidate.getRouteKey().trim().toLowerCase(Locale.ROOT);
            if (!keys.add(key)) {
                throw new BizException(ErrorCode.DUPLICATE, "候选路由键「" + candidate.getRouteKey() + "」重复");
            }
            if (candidate.getIntentDesc() == null || candidate.getIntentDesc().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "候选「" + candidate.getRouteKey() + "」的意图描述不能为空");
            }
            AgentDef child = lookup.apply(candidate.getAgentId()).orElse(null);
            // 复用现网路由门闩：非自环、非 ROUTER、已启用、允许被编排、子模式获准
            AgentRoutePolicy.assertCanBind(syntheticParent, child);
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
}
