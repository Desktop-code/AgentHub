package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次工作流运行实例。所属域：workflow 的 domain。对应表 workflow_run。
 * <p>执行真源是 {@link #graphJson}：建 run 时从 workflow_release 拷贝，
 * 之后发布指针怎么变都不影响已创建的实例。
 * <p>状态迁移不允许直接 set 后入库：必须经 {@code WorkflowStateMachine} 校验，
 * 由 {@code WorkflowRunRepository#updateStatus} 持久化。
 */
@Data
public class WorkflowRun {

    /** 主键 */
    private Long id;
    /** 租户；与人待办查询同口径 */
    private Long tenantId;
    /** workflow_def.id */
    private Long workflowId;
    /** workflow_release.id */
    private Long releaseId;
    /** 发布版本号，冗余便于排障 */
    private Integer releaseVersion;
    /** 依赖指纹 */
    private String fingerprint;
    /** 预留 trace 标识；本刀不写 obs span */
    private String traceId;
    /** 状态码（WorkflowRunStatus） */
    private String status;
    /** 触发类型（WorkflowTriggerType）；试跑 TRIAL / 应用 APP / 工作台 WORKBENCH */
    private String triggerType;
    /** 发起人；应用触发时为空（无平台用户） */
    private Long triggeredBy;
    /** 应用触发来源 sys_app.id；试跑为空 */
    private Long appId;
    /** 调用方幂等键；仅 APP 触发写入，同 app 下唯一 */
    private String clientRequestId;
    /**
     * 工作台会话 agent_run_session.id；仅 WORKBENCH 触发写入。
     *
     * <p>两个用途：① 人工恢复后把终态结果追写回同一条工作台会话；
     * ② 同一会话同一工作流未结束（RUNNING / PAUSED_HUMAN）时禁止重复开跑。
     * 试跑 / 应用触发恒为空，不改变现网观测口径。
     */
    private Long workbenchSessionId;
    /** 试跑输入 JSON 文本 */
    private String inputJson;
    /** 终态输出 JSON 文本 */
    private String outputJson;
    /** 失败 / 取消摘要 */
    private String errorSummary;
    /** 建 run 时拷贝的不可变图 JSON */
    private String graphJson;
    /** 进入 RUNNING 的时间 */
    private LocalDateTime startTime;
    /** 终态时间 */
    private LocalDateTime endTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
