package com.agenthub.model.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型实例。所属域：model。显式绑定一条凭证，本期没有优先级和权重。
 * lastTestOk：1 成功，0 失败，空表示还没测过。摘要不得含 Key。
 */
@Data
public class ModelInstance {

    private Long id;
    private Long providerId;
    private Long credentialId;
    private String displayName;
    private String modelId;
    private String capability;
    private Integer status;
    private String extraJson;
    /**
     * 逻辑删除标记：0 未删，1 已删。
     * 普通 findById 恒为未删；仅 findByIdIncludingDeleted 可能带回 1。
     */
    private Integer deleted;
    private LocalDateTime lastTestTime;
    private Integer lastTestOk;
    private String lastTestMsg;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
