package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流的图结构（校验用视图）。所属域：workflow 的 domain。
 * <p>只承载 nodes / edges；viewport 等展示字段留在原始 JSON，不参与校验。
 */
@Data
public class WorkflowGraph {

    /** 节点列表，可能为空（空图由校验器报缺 START） */
    private List<WorkflowNode> nodes = new ArrayList<>();
    /** 边列表 */
    private List<WorkflowEdge> edges = new ArrayList<>();
}
