package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * app_workflow_grant。所属域：workflow 的 infra。
 * <p>列名与 sql/temp/schema-delta-app-workflow-grant.sql 一致；授权表随应用/定义物理删除，不做逻辑删除。
 */
@Data
@TableName("app_workflow_grant")
public class AppWorkflowGrantEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long appId;
    private Long workflowDefId;
    /** 空=跟随默认发布指针 */
    private Long releaseId;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
