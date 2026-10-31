package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮 AgentScope 执行的可落库结果。所属域：runtime。
 * traces 按引擎顺序，只含真实的思考和工具结果。工具不带实参。
 */
@Data
public class TrialTurn {

    private String assistantText = "";
    private Integer promptTokens;
    private Integer completionTokens;
    /**
     * 本轮生效的工具结果回灌上限。时间线展示与回灌同截；≤0 表示展示侧不按该项截。
     * obs TOOL span 仍用 traces 里的脱敏全文，不读此字段。
     */
    private int toolResultMaxChars;
    /** 思考与工具。没有思考时不含 REASONING */
    private List<TrialTrace> traces = new ArrayList<>();
    /**
     * 计划待批准：本轮只产出计划，没有终答。
     * 为 true 时调用方不写 assistant 事件、也不把空助手正文推进记忆窗口，
     * 避免时间线里出现一条空回复、也避免窗口摘要被空内容推进。
     */
    private boolean assistantSuppressed;
}
