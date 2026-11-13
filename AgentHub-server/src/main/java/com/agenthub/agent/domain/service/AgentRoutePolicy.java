package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 一层 Router 的写入规则。所属域：agent。
 * 只拒绝自引用和子节点不是 REACT / 未被允许的计划执行，不做图上的多层环检测。
 */
public final class AgentRoutePolicy {

    private AgentRoutePolicy() {
    }

    /**
     * 新路由或重新启用时调用。父节点必须当前就是 ROUTER。
     *
     * <p>子侧五道门：非自环、run_mode ∈ 父编排允许集合（REACT；含 PLAN_AND_EXECUTE 时放行）、
     * 已启用、且**允许被编排调用**（allow_orchestrated=1）。
     * 最后一道用于保护「同为 REACT 但不想被挂走」的私有 Agent；关掉开关无需删任何绑定即可拒新挂载。
     *
     * @param parent 未删除的父 Agent
     * @param child  未删除的子 Agent
     * @throws BizException 父节点不是 ROUTER、自己指向自己、子模式未被编排允许、子节点未启用或未允许被编排调用
     */
    public static void assertCanBind(AgentDef parent, AgentDef child) {
        if (parent == null || child == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        if (!AgentRunMode.ROUTER.name().equals(parent.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有 ROUTER 可以配置子 Agent");
        }
        if (parent.getId() != null && parent.getId().equals(child.getId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能把 Agent 配成自己的子节点");
        }
        // 子 ROUTER 仍被彻底禁止（不做 router-in-router）；P&E 只有在父编排显式允许时才可挂
        if (AgentRunMode.ROUTER.name().equals(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子 Agent 不能是 ROUTER，一期不支持多层级路由");
        }
        AgentRuntimeConfig parentRuntime = AgentRuntimeConfig.parse(
                parent.getRuntimeConfigJson(), parent.getRunMode());
        if (!AgentRuntimeConfig.validChildRunMode(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子 Agent 运行模式不受支持，只能是 ReAct 或计划执行");
        }
        if (!parentRuntime.allowsChildRunMode(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "编排未允许子使用"
                    + modeText(child.getRunMode()) + "模式，请先在「编排」Tab 的「允许的子运行模式」中勾选");
        }
        if (child.getStatus() == null || child.getStatus() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能绑定已启用的子 Agent");
        }
        // 私有 Agent 保护：开关在目标 Agent 的基本信息里，关掉后任何编排根都不能把它选为候选
        if (!child.allowOrchestratedOn()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "「" + displayName(child) + "」未允许被编排调用，请先在该 Agent 的基本信息中开启「允许被编排调用」");
        }
    }

    /**
     * 方案（多智能体）候选的写入规则：方案本身就是编排根，不需要再合成父 Agent。
     *
     * <p>子侧门闩与 {@link #assertCanBind(AgentDef, AgentDef)} 完全一致：非 ROUTER、
     * 子模式合法且被本方案允许、已启用、允许被编排调用。父配置直接取方案自己的运行配置。
     *
     * @param parentRuntime 方案运行配置（读允许的子运行模式）；可空，空按默认
     * @param child         未删除的子 Agent；可空
     * @throws BizException 子节点不是 REACT / 未被允许的计划执行、未启用或未允许被编排调用
     */
    public static void assertSchemeChild(AgentRuntimeConfig parentRuntime, AgentDef child) {
        if (child == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        // 子 ROUTER 仍被彻底禁止：方案只做一层路由，不允许 router-in-scheme
        if (AgentRunMode.ROUTER.name().equals(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子 Agent 不能是 ROUTER，一期不支持多层级路由");
        }
        if (!AgentRuntimeConfig.validChildRunMode(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子 Agent 运行模式不受支持，只能是 ReAct 或计划执行");
        }
        AgentRuntimeConfig runtime = parentRuntime == null ? new AgentRuntimeConfig() : parentRuntime;
        if (!runtime.allowsChildRunMode(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "编排未允许子使用"
                    + modeText(child.getRunMode()) + "模式，请先在「编排」Tab 的「允许的子运行模式」中勾选");
        }
        if (child.getStatus() == null || child.getStatus() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能绑定已启用的子 Agent");
        }
        if (!child.allowOrchestratedOn()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "「" + displayName(child) + "」未允许被编排调用，请先在该 Agent 的基本信息中开启「允许被编排调用」");
        }
    }

    /**
     * 错误文案里的模式称呼。
     *
     * @param runMode REACT / PLAN_AND_EXECUTE
     * @return 中文模式名
     */
    private static String modeText(String runMode) {
        return AgentRunMode.PLAN_AND_EXECUTE.name().equals(runMode) ? "计划执行" : "ReAct";
    }

    /** 错误文案里的 Agent 称呼：名称 → 编码 → 主键，避免出现「null 未允许…」。 */
    private static String displayName(AgentDef child) {
        if (child.getName() != null && !child.getName().isBlank()) {
            return child.getName().trim();
        }
        if (child.getCode() != null && !child.getCode().isBlank()) {
            return child.getCode().trim();
        }
        return "该 Agent";
    }
}
