package com.agenthub.model.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商卡片与详情。所属域：model。apiKeyMask 是摘要，不是完整 Key。
 */
@Data
public class ProviderResp {

    private Long id;
    private String name;
    private String protocol;
    private String baseUrl;
    private Integer status;
    private String remark;
    private Integer credentialCount;
    private String apiKeyMask;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<CredentialResp> credentials = new ArrayList<>();
}
