package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新 Agent 知识库绑定。所属域：rag。字段为空表示不修改该字段。
 */
@Data
public class KbBindUpdateReq {

    /** 1 启用 0 停用 */
    private Integer status;

    /** 检索覆盖项 JSON；传空串表示清空覆盖，回落到 KB 默认 */
    @Size(max = 1000, message = "最长 1000")
    private String overridesJson;
}
