package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑批出参。所属域：eval。
 * <p>只读视图：状态由 domain 状态机推进，前端不做本地状态推演（避免与库里不一致）。
 * <p>终态后 report / reportPath 由 ReportBuilder 填充；PENDING 时为 null。
 */
@Data
public class EvalRunResp {

    private Long id;
    private Long tenantId;
    private Long suiteId;
    private Integer suiteVersion;
    private Long agentId;
    /** 本刀恒为 null（真指纹在刀2b 计算） */
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
    /** 1 本次启用 OpenJudge / 0 关闭 */
    private Integer openjudgeEnabled;
    /** 本 run 的打分模型实例 id；null 表示不打分（从配置快照回显，前端免解析 JSON） */
    private Long judgeModelInstanceId;
    /** 合并后的配置快照（解析后的 JSON，空为 null） */
    private JsonNode config;
    private String reportPath;
    private JsonNode report;
    private Long triggerUserId;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
