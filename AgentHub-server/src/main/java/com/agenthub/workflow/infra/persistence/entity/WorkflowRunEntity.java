package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_run。所属域：workflow 的 infra。
 * <p>列名与 sql/temp/schema-delta-workflow-run.sql 一致；运行态表不做逻辑删除
 * （历史 run 要能查），因此不声明 deleted。
 */
@Data
@TableName("workflow_run")
public class WorkflowRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long workflowId;
    private Long releaseId;
    private Integer releaseVersion;
    private String fingerprint;
    private String traceId;
    private String status;
    private String triggerType;
    private Long triggeredBy;
    /** sys_app.id；试跑为空 */
    private Long appId;
    /** 调用方幂等键；试跑为空 */
    private String clientRequestId;
    /** 工作台会话 agent_run_session.id；仅 trigger_type=WORKBENCH 写入 */
    private Long workbenchSessionId;
    private String inputJson;
    private String outputJson;
    private String errorSummary;
    private String graphJson;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
