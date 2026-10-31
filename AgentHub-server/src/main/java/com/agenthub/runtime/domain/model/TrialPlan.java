package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 ReAct 装配结果。所属域：runtime。不含模型明文 Key。
 */
@Data
public class TrialPlan {

    private Long agentId;
    private String agentName;
    private String runMode;
    /** 已启用且用途为对话的模型实例 */
    private Long modelInstanceId;
    /** 交给模型的系统提示：平台前缀在最前，其后是用户业务段和 Skill 正文 */
    private String systemPrompt;
    /** 本轮可注册的内置工具（已过预算） */
    private List<BuiltinToolSpec> tools = new ArrayList<>();
    /** 本轮可注册的 MCP 工具（已过预算）；与 tools 同一绑定序筛出 */
    private List<McpToolSpec> mcpTools = new ArrayList<>();
    /** 给事件时间线的装配说明，例如源不可用或超预算未暴露 */
    private List<String> notices = new ArrayList<>();
    /** 本轮生效限制；装配时解析，执行时按此拦截 */
    private EffectiveLimits limits;
    /** 本轮提示词注入统计，交给 LLM span */
    private PromptInjectionStats injection;
    /**
     * 本轮已注入系统提示的长期记忆，供工具必填参数补参与臆造校验。
     * 为空表示本轮没有任何长期记忆依据；未注入的记忆不得放进来。
     */
    private List<MemoryFact> toolArgsFacts = new ArrayList<>();
}