package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * run 详情聚合。所属域：workflow 的 domain。
 * <p>给试跑抽屉一次取齐：run 头 + 全部节点状态 + 未决人工待办。
 * <p>不落库，只做应用层组装；节点顺序按图定义顺序，画布高亮直接照这个序上色。
 */
@Data
public class WorkflowRunDetail {

    private WorkflowRun run;
    private List<WorkflowNodeRun> nodeRuns = new ArrayList<>();
    /** 仍为 OPEN 的人工待办 */
    private List<WorkflowHumanTask> openHumanTasks = new ArrayList<>();
}
