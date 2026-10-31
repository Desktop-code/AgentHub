package com.agenthub.agent.domain.model;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * Agent 运行模式。所属域：agent。只描述配置，不执行。
 * 三种模式共用同一套「基本信息 / 模型 / 工具 / 技能 / 记忆 / 运行配置」页签，
 * 差异在运行时编排：REACT 单 Agent、ROUTER 一层分发、PLAN_AND_EXECUTE 先规划再串行执行。
 * Chat / Workflow-DAG / Multi-Agent 不属本枚举，另开页面时也不要塞进来。
 */
public enum AgentRunMode {

    /** ReAct 单 Agent，可作为 Router 的子节点 */
    REACT,
    /** 一层路由父节点，子 Agent 必须再是 REACT */
    ROUTER,
    /**
     * 计划执行：CHAT 槽模型先产出有序步骤，再按 seq 串行复用本 Agent 的工具执行，最后汇总终答。
     * 可选「规划后停住等批准」；不做步骤间并行，也不支持作为 Router 子节点。
     */
    PLAN_AND_EXECUTE;

    /** 面向用户的模式名，用于提示与日志，不进库 */
    private static final String ALL_NAMES = "REACT、ROUTER 或 PLAN_AND_EXECUTE";

    /**
     * @param raw 请求里的模式，允许首尾空白
     * @return 合法枚举
     * @throws BizException 空值或不在枚举内时 BAD_REQUEST
     */
    public static AgentRunMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "运行模式不能为空");
        }
        try {
            return AgentRunMode.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "运行模式只能是 " + ALL_NAMES);
        }
    }

    /**
     * 列表筛选。空白表示不按模式过滤。
     *
     * @param raw 查询参数，可空
     * @return 三种模式之一或 null
     */
    public static String optional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parse(raw).name();
    }

    /**
     * 单 Agent 直接绑定模型/工具/技能/记忆的模式：REACT 与 PLAN_AND_EXECUTE。
     * ROUTER 父节点只做分发，这几类绑定在配置页对它隐藏。
     *
     * @param runMode 库内模式串，可空
     * @return true 表示该模式使用单 Agent 绑定
     */
    public static boolean bindsSingleAgent(String runMode) {
        return REACT.name().equals(runMode) || PLAN_AND_EXECUTE.name().equals(runMode);
    }
}
