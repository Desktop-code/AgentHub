package com.agenthub.workflow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 发起试跑请求。所属域：workflow 的 api。
 * <p>绑定方式：
 * <ul>
 *   <li>{@code useDraft=true}：跑当前已保存草稿，不绑发布版本；</li>
 *   <li>否则显式 {@code releaseId}，或 {@code workflowId} + 默认发布指针。</li>
 * </ul>
 */
@Data
public class WorkflowRunTrialReq {

    /**
     * true 时按当前草稿跑。
     * 不插入发布记录，也不改默认发布指针；与 releaseId 同时出现时以本字段为准。
     */
    private Boolean useDraft;
    /** 发布版本 id；useDraft 不为 true 时优先于 workflowId */
    private Long releaseId;
    /** 工作流定义 id；按草稿跑时必填，否则在 releaseId 为空时用其 defaultReleaseId */
    private Long workflowId;
    /** 预留：是否使用默认发布指针；本刀 releaseId 为空即视为 true */
    private Boolean useDefaultRelease;
    /** 输入样例 JSON，必须是对象；空则 {} */
    private JsonNode input;
}
