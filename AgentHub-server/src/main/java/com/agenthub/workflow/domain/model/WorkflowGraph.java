package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流的图结构（校验用视图）。所属域：workflow 的 domain。
 * <p>只承载 nodes / edges；viewport 等展示字段留在原始 JSON，不参与校验。
 * <p>运行时限（graph_json 根上的 {@code limits}）也在这里带出来：
 * 它是「工作流自身配置」，随发布快照一起冻结，不属于平台 yml。
 */
@Data
public class WorkflowGraph {

    /** 节点列表，可能为空（空图由校验器报缺 START） */
    private List<WorkflowNode> nodes = new ArrayList<>();
    /** 边列表 */
    private List<WorkflowEdge> edges = new ArrayList<>();

    /**
     * 整段运行上限（分钟）；null = 未配置，跟平台硬顶走。
     *
     * <p>存分钟而不是毫秒：配置页只让用户填分钟，
     * 库里保留同一个单位，避免「界面上 10 分钟、库里 600000」两套口径。
     */
    private Integer runMinutes;

    /** 单节点运行上限（分钟）；null = 不限制单节点，只受整段上限约束 */
    private Integer nodeMinutes;
}
