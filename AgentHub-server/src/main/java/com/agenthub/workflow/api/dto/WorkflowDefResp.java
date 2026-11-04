package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流定义出参。所属域：workflow。
 * <p>defaultVersion 为空表示尚未发布；status 是生命周期，不是启停开关。
 */
@Data
public class WorkflowDefResp {

    private Long id;
    private String code;
    private String name;
    private String description;
    /** DRAFT_ONLY / HAS_RELEASE */
    private String status;
    /** 默认发布指针，可空 */
    private Long defaultReleaseId;
    /** 默认发布版本号；未发布为空 */
    private Integer defaultVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
