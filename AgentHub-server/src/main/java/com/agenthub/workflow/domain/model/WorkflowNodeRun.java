package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单个节点的运行态。所属域：workflow 的 domain。对应表 workflow_node_run。
 * <p>建 run 时按图给每个节点预建一条 PENDING（attempt=1）；
 * 执行到该节点时迁 RUNNING，结束迁 SUCCESS / FAILED / SKIPPED / WAITING_HUMAN。
 * <p>{@link #selectedEdges} 存本节点本次选中的出边 id（逗号分隔）：
 * 既是「边决策摘要」的落库位置，也是 run 恢复时重建边状态的依据。
 */
@Data
public class WorkflowNodeRun {

    private Long id;
    private Long runId;
    /** 画布节点 id（n1/n2…） */
    private String nodeId;
    /** 节点编码 */
    private String nodeCode;
    /** 节点类型码 */
    private String nodeType;
    /** 尝试序号；本刀恒 1（节点重试留 P1） */
    private Integer attempt;
    /** 状态（WorkflowNodeRunStatus） */
    private String status;
    private String inputJson;
    private String outputJson;
    private String errorSummary;
    /**
     * 用户对该节点询问的回复（对话续跑）。
     *
     * <p>落库卡片只存骨架、刷新回放按 runId 现算，回复不能只活在卡片快照里；
     * 这一列是「我答了什么」的持久真源，写入前截到 2000 字。
     */
    private String replyText;
    /** 选中的出边 id，逗号分隔 */
    private String selectedEdges;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
