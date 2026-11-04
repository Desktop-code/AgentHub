package com.agenthub.eval.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次评测批跑。所属域：eval 的 domain。对应表 eval_run。
 * <p>关键约束：
 * <ul>
 *   <li>suite_version 在建跑时**钉死**，之后套件升版本不影响本行已记录的版本。</li>
 *   <li>状态只能经 {@code EvalRunStateMachine} 迁移；本类不自己 setStatus。</li>
 *   <li>计数器（total/passed/failed/errored/skipped）与 gate_passed 只由
 *       {@code EvalRunStateMachine.recount} 统一改写，避免多处各算一遍口径打架。</li>
 *   <li>agent_snapshot_hash 本刀（2a）留空；真指纹在刀2b 装配 Trial 时计算。</li>
 * </ul>
 * 线程：可变的领域行对象，一次请求内独占，不跨线程共享。
 */
@Data
public class EvalRun {

    /** 主键 */
    private Long id;
    /** 租户；硬隔离 */
    private Long tenantId;
    /** 套件 */
    private Long suiteId;
    /** 钉死的套件版本 */
    private Integer suiteVersion;
    /** 被测 Agent */
    private Long agentId;
    /** 绑定/L3/runtime/模型槽指纹；本刀可为空（刀2b 再算真值） */
    private String agentSnapshotHash;
    /** 状态；读写一律走状态机 */
    private EvalRunStatus status;
    /** 规则门禁总结果：1 通过 / 0 未通过 / null 尚未出结果 */
    private Integer gatePassed;
    /** 计划用例数（等于建跑时插入的 result 行数） */
    private Integer totalCases;
    /** 通过数 */
    private Integer passed;
    /** 未通过数 */
    private Integer failed;
    /** 基础设施错误数 */
    private Integer errored;
    /** 跳过数（含取消收尾时被打成 SKIPPED 的 case） */
    private Integer skipped;
    /** 本次是否启用 OpenJudge：1 启用 / 0 关闭 */
    private Integer openjudgeEnabled;
    /** 合并后的本次配置快照（JSON 文本） */
    private String configJson;
    /** 报告落盘路径；本刀不产报告，保持 null */
    private String reportPath;
    /** 机读报告 JSON；本刀不产报告，保持 null */
    private String reportJson;
    /** 触发人 */
    private Long triggerUserId;
    /** 基础设施级错误摘要 */
    private String errorMessage;
    /** 进入 RUNNING 的时间 */
    private LocalDateTime startedTime;
    /** 进入终态的时间 */
    private LocalDateTime finishedTime;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 是否已经处于终态；终态不允许再迁移、再取消。
     *
     * @return true 表示 SUCCEEDED / FAILED / PARTIAL
     */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /**
     * 是否还能被取消（PENDING 或 RUNNING）。CANCELING 视作「取消已受理」，另行幂等收尾。
     *
     * @return true 表示可以从当前态发起取消
     */
    public boolean isCancelable() {
        return status == EvalRunStatus.PENDING || status == EvalRunStatus.RUNNING;
    }
}
