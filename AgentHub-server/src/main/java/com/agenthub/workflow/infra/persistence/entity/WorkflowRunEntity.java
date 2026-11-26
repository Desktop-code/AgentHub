package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_run。所属域：workflow 的 infra。
 * <p>列名与 sql/temp/schema-delta-workflow-run.sql 一致；运行态表不做逻辑删除
 * （历史 run 要能查），因此不声明 deleted。
 */
@Data
@TableName("workflow_run")
public class WorkflowRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long workflowId;
    /**
     * 发布版本 id。按当前草稿调试时必须写成 NULL：
     * 全局插入策略会跳过 null，列又曾经 NOT NULL，省略后插不进去或被默认成 1。
     */
    @TableField(insertStrategy = FieldStrategy.ALWAYS)
    private Long releaseId;
    /** 发布版本号；草稿调试同样显式写 NULL，记录里才标得出「未发布」 */
    @TableField(insertStrategy = FieldStrategy.ALWAYS)
    private Integer releaseVersion;
    /** 草稿调试开始时的默认发布版本号快照；null=当时从未发布（或非草稿调试） */
    @TableField(insertStrategy = FieldStrategy.ALWAYS)
    private Integer draftBaseReleaseVersion;
    private String fingerprint;
    private String traceId;
    private String status;
    private String triggerType;
    private Long triggeredBy;
    /** sys_app.id；试跑为空 */
    private Long appId;
    /** 调用方幂等键；试跑为空 */
    private String clientRequestId;
    /** 工作台会话 agent_run_session.id；仅 trigger_type=WORKBENCH 写入 */
    private Long workbenchSessionId;
    private String inputJson;
    private String outputJson;
    private String errorSummary;
    private String graphJson;
    /** 本次运行整段上限（毫秒）；建 run 时按工作流配置与平台硬顶取小 */
    private Long runLimitMs;
    /** 本次运行单节点上限（毫秒）；空=未配置 */
    private Long nodeLimitMs;
    /** 整段截止（等待不计入，恢复调度时顺延） */
    private LocalDateTime runDeadline;
    /** 进入等待的时刻；非空表示当前挂起不计时 */
    private LocalDateTime waitingSince;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
