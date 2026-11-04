package com.agenthub.eval.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * eval_case。所属域：eval 的 infra。列名与 sql/temp/schema-delta-eval.sql 一致。
 * 输入/断言/打分都是 JSON 列，按字符串读写；不进 toString，避免日志打出整篇用例。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("eval_case")
public class EvalCaseEntity extends EvalAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long suiteId;
    private Integer suiteVersion;
    private String caseKey;
    private String title;
    /** JSON 列：标签数组 */
    private String tagsJson;
    @ToString.Exclude
    private String inputJson;
    private String expectJson;
    @ToString.Exclude
    private String assertionsJson;
    @ToString.Exclude
    private String scoringJson;
    private Integer enabled;
    private Integer sortOrder;

    @TableLogic
    private Integer deleted;
}
