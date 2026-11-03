package com.agenthub.workflow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 发起试跑请求。所属域：workflow 的 api。
 * <p>两种绑定方式：显式 {@code releaseId}，或 {@code workflowId} + 默认发布指针。
 * <p>禁止传草稿 id：试跑只认已发布快照。
 */
@Data
public class WorkflowRunTrialReq {

    /** 发布版本 id；优先于 workflowId */
    private Long releaseId;
    /** 工作流定义 id；releaseId 为空时用其 defaultReleaseId */
    private Long workflowId;
    /** 预留：是否使用默认发布指针；本刀 releaseId 为空即视为 true */
    private Boolean useDefaultRelease;
    /** 输入样例 JSON，必须是对象；空则 {} */
    private JsonNode input;
}
