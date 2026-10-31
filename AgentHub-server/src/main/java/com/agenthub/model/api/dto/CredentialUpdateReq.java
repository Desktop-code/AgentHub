package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改凭证名称或启停。所属域：model。不回显、也不接收旧 Key。
 */
@Data
public class CredentialUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    private Integer status;
}
