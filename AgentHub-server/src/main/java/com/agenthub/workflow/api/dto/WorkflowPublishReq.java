package com.agenthub.workflow.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布入参。所属域：workflow。
 * <p>note 可空；发布永远是「当前草稿 → 新版本快照」。
 */
@Data
public class WorkflowPublishReq {

    /** 发布说明，最多 512 字 */
    @Size(max = 512, message = "发布说明最多 512 字")
    private String note;
}
