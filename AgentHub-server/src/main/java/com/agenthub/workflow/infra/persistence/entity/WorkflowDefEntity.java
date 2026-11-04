package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_def。所属域：workflow 的 infra。
 * <p>列名与 sql/temp/schema-delta-workflow-def.sql 一致；
 * 审计列 created_by / updated_by 与现网 sys_menu 口径一致。
 */
@Data
@TableName("workflow_def")
public class WorkflowDefEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String description;
    private String status;
    private Long defaultReleaseId;
    @TableLogic
    private Integer deleted;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
