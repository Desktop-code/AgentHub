package com.agenthub.prompt.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * prompt_global_constraint。所属域：prompt 的 infra。
 * 审计列对齐现库 create_by / update_by（非 created_by）。
 */
@Data
@TableName("prompt_global_constraint")
public class PromptConstraintEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String locale;
    @ToString.Exclude
    private String content;
    private Integer version;
    private Integer status;
    private Integer isActive;
    @TableLogic
    private Integer deleted;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
