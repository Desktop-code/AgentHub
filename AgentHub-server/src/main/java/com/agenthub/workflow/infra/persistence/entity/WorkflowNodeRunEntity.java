package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_node_run。所属域：workflow 的 infra。
 * <p>一 run 内每节点一条（本刀 attempt 恒 1）；状态迁移只由引擎与人工处理写。
 */
@Data
@TableName("workflow_node_run")
public class WorkflowNodeRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String nodeId;
    private String nodeCode;
    private String nodeType;
    private Integer attempt;
    private String status;
    private String inputJson;
    private String outputJson;
    private String errorSummary;
    private String selectedEdges;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
