package com.agenthub.model.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建供应商。所属域：model。
 * 凭证可后置到凭证弹窗配置；若同时传 credentialName + apiKey 则仍建首条凭证（兼容旧客户端）。
 */
@Data
public class ProviderCreateReq {

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

    /** 可选；与 apiKey 成对出现时才建首条凭证 */
    @Size(max = 64, message = "最长 64")
    private String credentialName;

    /** 可选；明文只在本次请求出现 */
    @Size(max = 512, message = "最长 512")
    private String apiKey;
}
