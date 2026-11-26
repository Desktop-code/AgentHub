package com.agenthub.workflow.api.dto;

import lombok.Data;

/**
 * 把线上默认切回某个已发布版本。所属域：workflow 的 api。
 * <p>{@code overwrite=false} 且草稿与当前默认发布不一致时，服务端返回 409，由页面确认后再带 true 重试。
 */
@Data
public class WorkflowReleaseActivateReq {

    /**
     * true：允许用所选版本覆盖当前草稿。
     * 画布上还有未发布改动时，前端应先提醒，用户确认后再传 true。
     */
    private Boolean overwrite;
}
