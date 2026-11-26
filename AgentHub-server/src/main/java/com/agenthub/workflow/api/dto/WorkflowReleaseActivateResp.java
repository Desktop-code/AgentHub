package com.agenthub.workflow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 切换默认发布后的结果。所属域：workflow 的 api。
 * <p>列表接口不下发图；切换要把该版的图交回画布，所以只在这个响应里带 graph。
 */
@Data
public class WorkflowReleaseActivateResp {

    private Long id;
    private Long workflowId;
    private Integer version;
    private String fingerprint;
    private String publishNote;
    private Long publishedBy;
    private LocalDateTime publishTime;
    /** 该版本的完整图，画布据此恢复 */
    private JsonNode graph;
    /** 草稿被覆盖后的更新时间，供下次保存做乐观锁 */
    private LocalDateTime draftUpdateTime;
}
