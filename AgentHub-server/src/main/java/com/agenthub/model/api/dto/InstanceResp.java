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
    /** 1=平台级全局实例；0/空=普通。仅 EMBEDDING / RERANK 允许为 1。 */
    private Integer platformLocked;
    /** 最近一次连通测试成功快照的向量维度；仅 EMBEDDING 有值，null 表示不可用于建集。 */
    private Integer embeddingDim;
    private String extraJson;
    private LocalDateTime lastTestTime;
    /** 1 成功，0 失败，空表示未测。 */
    private Integer lastTestOk;
    private String lastTestMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
