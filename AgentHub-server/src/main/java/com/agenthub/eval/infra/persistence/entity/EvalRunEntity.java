package com.agenthub.eval.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * eval_run。所属域：eval 的 infra。列名与 sql/temp/schema-delta-eval-run.sql 一致。
 * <p>config_json / report_json 是 JSON 列，按字符串读写；序列化在应用层完成。
 * <p>status 用字符串存（与库一致），转枚举的校验在仓储回读时做，避免脏值被静默当成 PENDING。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("eval_run")
public class EvalRunEntity extends EvalAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long suiteId;
    private Integer suiteVersion;
    private Long agentId;
    private String agentSnapshotHash;
    /** PENDING|RUNNING|CANCELING|SUCCEEDED|FAILED|PARTIAL */
    private String status;
    /** 1 通过 / 0 未通过 / null 未出结果 */
    private Integer gatePassed;
    private Integer totalCases;
    private Integer passed;
    private Integer failed;
    private Integer errored;
    private Integer skipped;
    private Integer openjudgeEnabled;
    /** JSON 列：本次合并后的配置快照；不进 toString，避免日志打出大段配置 */
    @ToString.Exclude
    private String configJson;
    private String reportPath;
    /** JSON 列：机读报告；本刀不产，保持 null */
    @ToString.Exclude
    private String reportJson;
    private Long triggerUserId;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;

    @TableLogic
    private Integer deleted;
}
