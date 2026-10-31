package com.agenthub.obs.domain.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 结束一步时写入的正文与专用列。所属域：obs。
 * 只打码、不截断；LLM 必须分列 input/output，禁止只写一段 content。
 * 金额不在此对象；billingLedgerId 仅可选关联计费流水。
 */
@Data
public class SpanClose {

    /** 终态：OK / ERROR / CANCELLED */
    private ObsStatus status;
    /** 输入全文（打码） */
    private String inputText;
    /** 输出全文（打码） */
    private String outputText;
    /** 本步失败摘要（打码） */
    private String errorSummary;

    private Long modelInstanceId;
    private String modelName;
    private String provider;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private Integer tokenTotal;
    private String finishReason;
    /** 可选关联 billing_token_ledger.id */
    private Long billingLedgerId;
    private BigDecimal temperature;
    private Integer maxTokens;

    private Long toolId;
    private String toolName;
    private String toolType;
    private String toolCallId;
    private Integer retryCount;
    /** 1 成功 0 失败 */
    private Integer toolSuccess;

    private String routeDecision;
    private Long childAgentId;

    private String retrieveQuery;
    private Integer retrieveHitCount;
    private Integer retrieveTopK;
    private Long retrieveKbId;

    private String attrsJson;

    /**
     * 仅写输出摘要的简短收尾（ROUTE/AGENT 等）。
     *
     * @param status 终态
     * @param output 输出或决策摘要，可空
     * @return 关闭载荷
     */
    public static SpanClose ofOutput(ObsStatus status, String output) {
        SpanClose close = new SpanClose();
        close.setStatus(status);
        close.setOutputText(output);
        if (status == ObsStatus.ERROR || status == ObsStatus.CANCELLED) {
            close.setErrorSummary(output);
        }
        return close;
    }
}
