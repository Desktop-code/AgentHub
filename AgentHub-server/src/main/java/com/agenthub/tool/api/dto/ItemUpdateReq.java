package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑工具项元数据。所属域：tool。不改调用名、所属源和启停。
 */
@Data
public class ItemUpdateReq {

    @Size(max = 128, message = "最长 128")
    private String title;

    @Size(max = 1000, message = "最长 1000")
    private String description;

    @Size(max = 60000, message = "过长")
    private String inputSchemaJson;
}
