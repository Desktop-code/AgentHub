package com.agenthub.runtime.domain.model;

import com.agenthub.prompt.domain.model.PromptLayout;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 ReAct 装配结果。所属域：runtime。不含模型明文 Key。
 * <p>
 * 系统提示的段落顺序见 {@code PromptInjectionComposer} 类注释；
 * {@link #layout} 带上「受保护前缀长度与各段正文」，供创建 Agent 前的结构校验使用。
 */
@Data
public class TrialPlan {

    private Long agentId;
    private String agentName;
    private String runMode;
    /** 已启用且用途为对话的模型实例 */
    private Long modelInstanceId;
    /** 交给模型的系统提示：平台约束与锁定段在最前，其后是开放段、L3、记忆、Skill 与工具目录 */
    private String systemPrompt;
    /**
     * 系统提示的结构描述：受保护前缀长度与各段渲染正文。
     * 出站前据此校验「锁定段存在且在开放段之前」，不依赖重新拼一遍字符串。
     */
    private PromptLayout layout = PromptLayout.EMPTY;
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
