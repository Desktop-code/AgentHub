package com.agenthub.model.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 凭证出参。所属域：model。列表、详情、创建、重置一律只填掩码。
 * 出于防泄露考虑，本 DTO 不设明文字段：任何接口都不把完整 Key 回给前端。
 */
@Data
public class CredentialResp {

    private Long id;
    private Long providerId;
    private String name;
    private String apiKeyMask;
    private Integer status;
    private LocalDateTime createTime;
}
