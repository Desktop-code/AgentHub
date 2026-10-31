package com.agenthub.model.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * model_provider。所属域：model。列名与 sql/schema-delta-model.sql 一致。
 * status 按基座：1 正常，0 停用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_provider")
public class ModelProviderEntity extends ModelAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String protocol;
    private String baseUrl;
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;
}
