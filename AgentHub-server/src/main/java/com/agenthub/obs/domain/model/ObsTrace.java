package com.agenthub.obs.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次提问 / 一次编排运行。所属域：obs。不是表实体。
 * 对话场景 workflowRunId / taskNodeId / rootTraceId 保持空；工作流场景写 workflow_run_id。
 * 时间字段对齐库列 start_time / end_time / create_time / update_time。
 */
@Data
public class ObsTrace {

    private Long id;
    private String traceId;
    private Long sessionId;
    private Long agentId;
    private Long userId;
    private Long appId;
    private ObsChannel channel;
    private String title;
    /** 预留：同一次编排运行（工作流 run 场景写入 workflow_run.id） */
    private String workflowRunId;
    /** 预留：编排节点。一期不写 */
    private String taskNodeId;
    /** 预留：子 trace 指向的根。一期不写 */
    private String rootTraceId;
    private ObsStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer latencyMs;
    private String errorSummary;
    private Integer roundCount;
    private Integer llmCallCount;
    private Integer toolCallCount;
    private Integer retrieveCallCount;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private Integer tokenTotal;
    private Long primaryModelInstanceId;
    private String primaryModelName;
    private String finishReason;
    private String attrsJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
