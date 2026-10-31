package com.agenthub.prompt.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * L1 平台约束领域模型。所属域：prompt。
 * 对应表 prompt_global_constraint；同 locale 至多一条 is_active=1。
 */
@Data
public class PromptConstraint {

    private Long id;
    private String locale;
    /** 正文可能很长，禁止进 toString / oper_log 全文 */
    @ToString.Exclude
    private String content;
    private Integer version;
    /** 0 草稿 1 已发布 */
    private Integer status;
    /** 同 locale 当前生效 */
    private Integer isActive;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
