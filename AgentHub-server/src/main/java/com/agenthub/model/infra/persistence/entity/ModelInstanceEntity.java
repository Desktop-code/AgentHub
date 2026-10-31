package com.agenthub.model.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * model_instance。所属域：model。本期没有 priority、weight 列。
 * last_test_* 只由连通测试写入，编辑实例时不覆盖。
 * 时间列：last_test_time / create_time / update_time。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_instance")
public class ModelInstanceEntity extends ModelAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private Long credentialId;
    private String displayName;
    private String modelId;
    private String capability;
    private Integer status;
    private String extraJson;
    /** 最近一次连通测试时间；未测为空。库列 last_test_time。 */
    private LocalDateTime lastTestTime;
    /** 1 成功，0 失败，空表示未测。 */
    private Integer lastTestOk;
    /** 最近测试摘要，禁止含 Key。 */
    private String lastTestMsg;

    @TableLogic
    private Integer deleted;
}
