package com.agenthub.model.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * model_credential。所属域：model。只存密文和掩码，没有明文列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_credential")
public class ModelCredentialEntity extends ModelAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String name;
    /** 禁止日志输出 */
    @ToString.Exclude
    private String apiKeyCipher;
    private String apiKeyMask;
    private Integer status;

    @TableLogic
    private Integer deleted;
}
