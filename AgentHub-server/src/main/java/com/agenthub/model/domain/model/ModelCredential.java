package com.agenthub.model.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 供应商凭证。所属域：model。同一供应商可以有多条。
 * apiKeyCipher 仅供出站解密，禁止打日志；toString 排除密文字段。
 */
@Data
public class ModelCredential {

    private Long id;
    private Long providerId;
    private String name;
    /** 密文，禁止进入响应和操作日志 */
    @ToString.Exclude
    private String apiKeyCipher;
    private String apiKeyMask;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
