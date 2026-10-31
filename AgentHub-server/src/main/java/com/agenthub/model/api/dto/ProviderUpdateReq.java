package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑供应商。所属域：model。不接收 Key，改 Key 走重置接口。
 */
@Data
public class ProviderUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @NotBlank(message = "不能为空")
    private String protocol;

    @NotBlank(message = "不能为空")
    @Size(max = 512, message = "最长 512")
    private String baseUrl;

    @Size(max = 500, message = "最长 500")
    private String remark;

    private Integer status;
}
