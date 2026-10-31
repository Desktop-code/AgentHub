package com.agenthub.obs.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * obs_trace。所属域：obs 的 infra。
 * 列与重建后的 v2 表一致；编排预留列一期写入保持 NULL。
 * 时间列：start_time / end_time / create_time / update_time。
 */
@Data
@TableName("obs_trace")
public class ObsTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Long sessionId;
    private Long agentId;
    private Long userId;
    private Long appId;
    private String channel;
    private String title;
    private String workflowRunId;
    private String taskNodeId;
    private String rootTraceId;
    private String status;
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

    @TableLogic
    private Integer deleted;

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
