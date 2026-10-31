package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建或编辑模型实例。所属域：model。
 */
@Data
public class InstanceSaveReq {

    @NotNull(message = "不能为空")
    private Long providerId;

    @NotNull(message = "不能为空")
    private Long credentialId;

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String displayName;

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String modelId;

    @NotBlank(message = "不能为空")
    private String capability;

    private Integer status;

    @Size(max = 2000, message = "最长 2000")
    private String extraJson;
}
