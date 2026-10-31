package com.agenthub.prompt.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * prompt_template。所属域：prompt 的 infra。
 * 审计列 create_by / update_by 与现库一致。
 */
@Data
@TableName("prompt_template")
public class PromptTemplateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateKey;
    private String name;
    private String purpose;
    private String locale;
    private String protocol;
    private Integer version;
    private Integer status;
    private Integer isActive;
    private String remark;
    @TableLogic
    private Integer deleted;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
