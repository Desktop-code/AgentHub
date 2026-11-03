package com.agenthub.eval.domain.model;

import lombok.Data;

/**
 * TrialEvalPort.runCase 入参。所属域：eval 的 domain。
 * <p>把 Runner 侧的租户/跑批/用例输入收成一包，避免防腐口参数列表无限膨胀。
 * <p>cachePolicy 默认 bypass：评测通道不读不写问答命中缓存（定稿 §6.3）；
 * 现网 trial 门面若尚未暴露独立 cache 开关，本字段仍写入请求以备对接，Impl 不得因此新开 HTTP。
 */
@Data
public class EvalTrialRequest {

    /** 租户（隔离与审计） */
    private Long tenantId;
    /** 被测 Agent */
    private Long agentId;
    /** 评测跑批 id，写入会话标题以便观测过滤 */
    private Long evalRunId;
    /** 用例键 */
    private String caseKey;
    /** 用例主键，可空 */
    private Long caseId;
    /** 用户消息正文（必填） */
    private String message;
    /** 语言，默认 zh-CN */
    private String locale;
    /**
     * P&amp;E 批准策略：auto_approve（默认）| auto_reject | skip。
     * 命中待批准计划时按此处理，禁止弹人工窗。
     */
    private String planAction;
    /** 缓存策略：bypass（默认）| allow */
    private String cachePolicy;
    /** 单 case 墙钟超时（毫秒） */
    private long timeoutMs;
}
