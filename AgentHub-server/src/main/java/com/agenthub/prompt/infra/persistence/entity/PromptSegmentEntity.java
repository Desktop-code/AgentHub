package com.agenthub.prompt.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * prompt_template_segment。所属域：prompt 的 infra。
 * 无 create_by；仅 create_time / update_time。
 */
@Data
@TableName("prompt_template_segment")
public class PromptSegmentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private String segKey;
    private String displayName;
    private Integer sortNo;
    private Integer mutable;
    private Integer required;
    @ToString.Exclude
    private String content;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
