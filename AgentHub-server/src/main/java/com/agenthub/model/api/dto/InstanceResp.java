package com.agenthub.model.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型实例出参。所属域：model。关联凭证只给名称和掩码。最近连通只给结果、时间和摘要。
 */
@Data
public class InstanceResp {

    private Long id;
    private Long providerId;
    private String providerName;
    private Long credentialId;
    private String credentialName;
    private String apiKeyMask;
    private String displayName;
    private String modelId;
    private String capability;
    private Integer status;
    private String extraJson;
    private LocalDateTime lastTestTime;
    /** 1 成功，0 失败，空表示未测。 */
    private Integer lastTestOk;
    private String lastTestMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
