package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工待办。所属域：workflow 的 domain。对应表 workflow_human_task。
 * <p>HUMAN 节点进入时写一条 OPEN；approve / reject / 超时策略把它收成 DONE 或 EXPIRED。
 * <p>超时策略随任务落库（{@link #timeoutPolicy}），扫描到期任务时不必回查 graph_json。
 */
@Data
public class WorkflowHumanTask {

    private Long id;
    private Long tenantId;
    private Long runId;
    private Long nodeRunId;
    /** 节点编码，列表展示用 */
    private String nodeCode;
    /** 状态（HumanTaskStatus） */
    private String status;
    /** 处理人规则：空 / USER:1,2 / ROLE:code */
    private String assigneeRule;
    /** 超时策略（HumanTimeoutPolicy） */
    private String timeoutPolicy;
    /** 表单 schema / 上下文快照 */
    private String formJson;
    /** 决定结果：approved / note / 决定来源 */
    private String resultJson;
    private LocalDateTime expireTime;
    private Long decidedBy;
    private LocalDateTime decidedTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
