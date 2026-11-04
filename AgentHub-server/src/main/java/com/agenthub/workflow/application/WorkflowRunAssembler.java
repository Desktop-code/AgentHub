package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * run 详情组装。所属域：workflow 的 application。
 * <p>试跑接口与人工处理接口都要回同一份结构，集中在这里避免两处字段漂移。
 * <p>只读：不修改状态、不触发调度。
 */
@Component
public class WorkflowRunAssembler {

    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowHumanTaskRepository humanTasks;

    /**
     * @param nodeRuns   节点运行态
     * @param humanTasks 人工待办
     */
    public WorkflowRunAssembler(WorkflowNodeRunRepository nodeRuns, WorkflowHumanTaskRepository humanTasks) {
        this.nodeRuns = nodeRuns;
        this.humanTasks = humanTasks;
    }

    /**
     * @param run run 头
     * @return 详情（节点按图顺序；只带 OPEN 待办）
     */
    public WorkflowRunDetail detail(WorkflowRun run) {
        WorkflowRunDetail detail = new WorkflowRunDetail();
        detail.setRun(run);
        List<WorkflowNodeRun> nodes = nodeRuns.listByRunId(run.getId());
        detail.setNodeRuns(nodes);
        List<WorkflowHumanTask> open = humanTasks.listOpenByRunId(run.getId());
        detail.setOpenHumanTasks(open);
        return detail;
    }
}
