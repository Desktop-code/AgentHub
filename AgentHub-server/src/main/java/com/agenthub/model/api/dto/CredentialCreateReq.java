package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 给供应商追加一条凭证。所属域：model。
 */
@Data
public class CredentialCreateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 512, message = "最长 512")
    private String apiKey;

    private Integer status;
}
