package com.agenthub.tool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建工具项。所属域：tool。状态固定停用，启用走单独接口。
 */
@Data
public class ItemCreateReq {

    @NotNull(message = "不能为空")
    private Long sourceId;

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String name;

    @Size(max = 128, message = "最长 128")
    private String title;

    @Size(max = 1000, message = "最长 1000")
    private String description;

    @Size(max = 60000, message = "过长")
    private String inputSchemaJson;
}
