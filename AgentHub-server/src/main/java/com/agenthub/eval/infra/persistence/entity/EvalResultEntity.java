package com.agenthub.eval.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * eval_result。所属域：eval 的 infra。列名与 sql/temp/schema-delta-eval-run.sql 一致。
 * <p>刻意**不继承** {@link EvalAuditEntity}：定稿 §5.1 的 eval_result 只有 create_time/update_time，
 * 没有 create_by/update_by 列；继承会让 MyBatis-Plus 往不存在的列写值。
 * <p>唯一键 (run_id, case_key)：一次跑批里一个用例只有一行。
 * <p>各 *_json 列按字符串读写；大字段不进 toString，避免日志打出整段产物。
 */
@Data
@TableName("eval_result")
public class EvalResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long runId;
    private String caseKey;
    private Long caseId;
    private Integer sortOrder;
    /** PASS|FAIL|ERROR|SKIPPED；未跑完为 null */
    private String verdict;
    private Integer rulePassed;
    /** OK|SKIPPED|ERROR|DISABLED */
    private String openjudgeStatus;
    @ToString.Exclude
    private String scoresJson;
    @ToString.Exclude
    private String artifactSummaryJson;
    private String sessionId;
    private String traceId;
    private Integer cacheHit;
    @ToString.Exclude
    private String promptBreakdownJson;
    private Integer latencyMs;
    @ToString.Exclude
    private String assertionDetailsJson;
    @ToString.Exclude
    private String openjudgeRawJson;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
