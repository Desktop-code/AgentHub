package com.agenthub.eval.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * eval_suite。所属域：eval 的 infra。列名与 sql/temp/schema-delta-eval.sql 一致。
 * default_config_json 是 JSON 列，读写按字符串处理，序列化在应用层。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("eval_suite")
public class EvalSuiteEntity extends EvalAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String suiteKey;
    private String name;
    private String description;
    private Integer currentVersion;
    /** JSON 列：默认 scorer/并发/超时 */
    private String defaultConfigJson;
    private Integer enabled;

    @TableLogic
    private Integer deleted;
}
