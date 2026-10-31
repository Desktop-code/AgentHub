package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置凭证 Key。所属域：model。明文只出现在这次请求和对应响应里。
 */
@Data
public class CredentialResetReq {

    @NotBlank(message = "不能为空")
    @Size(max = 512, message = "最长 512")
    private String apiKey;
}
