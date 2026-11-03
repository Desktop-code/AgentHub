package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发布版本出参。所属域：workflow。
 * <p>列表不返回 graph_json（体积大），只给元数据与指纹；指纹用于比对依赖是否变化。
 */
@Data
public class WorkflowReleaseResp {

    private Long id;
    private Long workflowId;
    private Integer version;
    private String fingerprint;
    private String publishNote;
    private Long publishedBy;
    private LocalDateTime publishTime;
}
