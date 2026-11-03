package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 评审环（CRITIC_LOOP）角色规则。所属域：agent。
 *
 * <p>只有一个模板的一层结构里，评审环需要两个明确角色：
 * <ul>
 *   <li><b>执行者</b>：启用候选 {@code agent_route} 中<b>恰好 1 个</b>可用子（按 sort_order、同序 id 升序）；</li>
 *   <li><b>评审者</b>：{@code maCriticAgentId} 指向的叶子 Agent，不能与执行者相同。</li>
 * </ul>
 *
 * <p>为什么「评审者从候选计数中排除」：评审专家允许出现在候选表里（用户可能先按老习惯挂成候选），
 * 若把它计入执行者个数，同一份配置会随「是否挂成候选」反复在 1 与 2 之间摇摆。
 * 因此计数时一律按 id 排除评审者，规则只对真正的执行者生效。
 *
 * <p>本类只做保存侧（BizException）；运行时把 {@link #criticProblem} 的原因转成 TrialFailure，
 * 保证「保存能过、跑起来才炸」这种不一致不会出现。
 */
public final class AgentCriticPolicy {

    private AgentCriticPolicy() {
    }

    /**
     * 保存编排配置 / 新增候选后的角色校验。非评审环模板直接放行。
     *
     * @param runtime     本次要落库的运行配置（已归一化）
     * @param routes      该编排根现有子路由（含停用行）
     * @param childLookup 子 Agent 查询（不存在返回空）
     * @throws BizException 执行者不是恰好 1 个、未选评审者或评审者不合法
     */
    public static void assertRoles(AgentRuntimeConfig runtime, List<AgentRoute> routes,
                                   Function<Long, Optional<AgentDef>> childLookup) {
        if (runtime == null || !runtime.isCriticLoop()) {
            return;
        }
        Long criticId = runtime.getMaCriticAgentId();
        List<AgentDef> executors = resolveExecutors(runtime, routes, childLookup);
        if (executors.isEmpty()) {
            // 评审者就是唯一候选：按「自评」报错，比「0 个执行者」更贴近用户实际要改的东西
            if (criticId != null && isCandidate(criticId, routes)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "评审专家不能与执行者相同：请换一个评审专家，或把当前候选换成别的执行者");
            }
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评审环需要恰好 1 个启用中的执行者候选，当前 0 个：候选还需运行模式获本编排允许且已开启「允许被编排调用」");
        }
        if (executors.size() > 1) {
            List<String> names = executors.stream().map(AgentCriticPolicy::displayName).toList();
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评审环只允许 1 个执行者候选，当前 " + executors.size() + " 个（"
                            + String.join("、", names) + "）：请停用多余候选，只保留 1 个执行者");
        }
        if (criticId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评审环需要选择评审专家：请在「编排」Tab 的评审专家下拉中选择 1 个已启用的子 Agent");
        }
        AgentDef critic = childLookup.apply(criticId).orElse(null);
        String problem = criticProblem(runtime, critic);
        if (problem != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, problem);
        }
        AgentDef executor = executors.get(0);
        if (executor.getId() != null && executor.getId().equals(critic.getId())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评审专家不能与执行者相同：请换一个评审专家，或把当前候选换成别的执行者");
        }
    }

    /**
     * 评审者合法性：启用、未删、非 ROUTER、运行模式获本编排允许、允许被编排调用。
     *
     * <p>返回中文原因而不是抛异常，是为了让保存侧（BizException）与运行侧（TrialFailure）
     * 共用同一份文案，避免两处各写一套措辞。
     *
     * @param runtime 编排根运行配置
     * @param critic  评审者；空表示未找到（已删除）
     * @return 中文原因；null 表示合法
     */
    public static String criticProblem(AgentRuntimeConfig runtime, AgentDef critic) {
        if (critic == null) {
            return "评审专家不存在或已删除，请重新选择评审专家";
        }
        String name = displayName(critic);
        if (AgentRunMode.ROUTER.name().equals(critic.getRunMode())) {
            return "评审专家不能是 ROUTER：一期不支持多层级路由，请改选 ReAct 或计划执行子 Agent";
        }
        if (critic.getStatus() == null || critic.getStatus() != AgentStatus.ENABLED) {
            return "评审专家「" + name + "」已停用，请先启用该 Agent 再保存评审环";
        }
        if (!AgentRuntimeConfig.validChildRunMode(critic.getRunMode())) {
            return "评审专家「" + name + "」的运行模式不受支持，只能是 ReAct 或计划执行";
        }
        if (runtime != null && !runtime.allowsChildRunMode(critic.getRunMode())) {
            return "编排未允许评审专家使用" + modeText(critic.getRunMode())
                    + "模式，请先在「编排」Tab 的「允许的子运行模式」中勾选";
        }
        if (!critic.allowOrchestratedOn()) {
            return "「" + name + "」未允许被编排调用，请先在该 Agent 的基本信息中开启「允许被编排调用」";
        }
        return null;
    }

    /**
     * 解析执行者候选：启用路由行 → 子存在 → 排除评审者 → 运行模式合法且被允许 → 允许被编排调用。
     *
     * @param runtime     运行配置（读评审者 id 与允许的子运行模式）
     * @param routes      该编排根子路由
     * @param childLookup 子 Agent 查询
     * @return 按 sort_order、同序 id 升序的执行者列表
     */
    public static List<AgentDef> resolveExecutors(AgentRuntimeConfig runtime, List<AgentRoute> routes,
                                                  Function<Long, Optional<AgentDef>> childLookup) {
        List<AgentDef> executors = new ArrayList<>();
        if (routes == null || routes.isEmpty()) {
            return executors;
        }
        Long criticId = runtime == null ? null : runtime.getMaCriticAgentId();
        List<AgentRoute> sorted = new ArrayList<>(routes);
        sorted.sort(Comparator
                .comparing(AgentRoute::getSortOrder, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(AgentRoute::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (AgentRoute route : sorted) {
            if (route.getStatus() == null || route.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (route.getChildAgentId() == null) {
                continue;
            }
            AgentDef child = childLookup.apply(route.getChildAgentId()).orElse(null);
            if (child == null || child.getId() == null) {
                continue;
            }
            // 评审者从候选计数中排除：它出现在候选表不代表它是第二个执行者
            if (criticId != null && criticId.equals(child.getId())) {
                continue;
            }
            if (child.getStatus() == null || child.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (!AgentRuntimeConfig.validChildRunMode(child.getRunMode())
                    || (runtime != null && !runtime.allowsChildRunMode(child.getRunMode()))) {
                continue;
            }
            if (!child.allowOrchestratedOn()) {
                continue;
            }
            executors.add(child);
        }
        return executors;
    }

    /**
     * 判断某个 Agent 是否作为启用候选挂在该编排根下（只看路由行与状态）。
     *
     * @param agentId 待查子 Agent
     * @param routes  子路由
     * @return true＝存在启用路由行指向该 Agent
     */
    private static boolean isCandidate(Long agentId, List<AgentRoute> routes) {
        if (agentId == null || routes == null) {
            return false;
        }
        for (AgentRoute route : routes) {
            if (route.getStatus() == null || route.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (agentId.equals(route.getChildAgentId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param runMode REACT / PLAN_AND_EXECUTE
     * @return 中文模式名（错误文案用）
     */
    private static String modeText(String runMode) {
        return AgentRunMode.PLAN_AND_EXECUTE.name().equals(runMode) ? "计划执行" : "ReAct";
    }

    /** 错误文案里的称呼：名称 → 编码 → 主键，避免出现「null 已停用」。 */
    private static String displayName(AgentDef agent) {
        if (agent == null) {
            return "该 Agent";
        }
        if (agent.getName() != null && !agent.getName().isBlank()) {
            return agent.getName().trim();
        }
        if (agent.getCode() != null && !agent.getCode().isBlank()) {
            return agent.getCode().trim();
        }
        return agent.getId() == null ? "该 Agent" : "#" + agent.getId();
    }
}
