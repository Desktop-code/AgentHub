package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次工作流运行实例。所属域：workflow 的 domain。对应表 workflow_run。
 * <p>执行真源是 {@link #graphJson}：建 run 时从发布快照或当时的草稿拷贝，
 * 之后发布指针怎么变都不影响已创建的实例。
 * <p>按当前草稿调试时 {@link #releaseId} 与 {@link #releaseVersion} 都为空，
 * 不插入 workflow_release，也不改默认发布指针。
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
    /** workflow_release.id；按当前草稿调试时为空 */
    private Long releaseId;
    /** 发布版本号；按当前草稿调试时为空，记录展示「未发布」 */
    private Integer releaseVersion;
    /**
     * 开始这次未发布调试时，该定义的默认发布版本号（快照）。
     *
     * <p>只对按草稿调试有意义：记录列表要能显示「当时默认 vN + 未发布」，
     * 之后又发布了新版也不改写这条历史。从未发布过、或非草稿调试时为空。
     */
    private Integer draftBaseReleaseVersion;
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
    /**
     * 本次运行生效的整段上限（毫秒）。
     *
     * <p>建 run 时按「工作流配置与平台硬顶取小」算一次并冻结，
     * 之后改工作流配置 / 改 yml 都不影响已在跑的实例。
     */
    private Long runLimitMs;
    /**
     * 本次运行生效的单节点上限（毫秒）；null 或 0 表示未配置。
     */
    private Long nodeLimitMs;
    /**
     * 整段运行的绝对截止时间（活动时间口径）。
     *
     * <p>等待人工 / 等待用户回复期间不计时；恢复调度时把等待时长补回截止时间，
     * 因此刷新、回放看到的是同一个截止，不会从头计时。
     */
    private LocalDateTime runDeadline;
    /**
     * 进入等待（人工 / 用户回复）的时刻；非空表示当前挂起、不计入任何时限。
     */
    private LocalDateTime waitingSince;
    /** 进入 RUNNING 的时间 */
    private LocalDateTime startTime;
    /** 终态时间 */
    private LocalDateTime endTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
