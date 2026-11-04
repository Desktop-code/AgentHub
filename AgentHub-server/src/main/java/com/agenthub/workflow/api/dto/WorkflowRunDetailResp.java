package com.agenthub.workflow.api.dto;

import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * run 详情响应。所属域：workflow 的 api。
 * <p>把库里的 JSON 文本解析成 JSON 节点再回前端，避免前端二次 parse。
 */
@Data
public class WorkflowRunDetailResp {

    private Long id;
    private Long workflowId;
    private Long releaseId;
    private Integer releaseVersion;
    private String status;
    private String triggerType;
    /** 应用触发时的 sys_app.id；试跑为空 */
    private Long appId;
    private JsonNode input;
    private JsonNode output;
    private String errorSummary;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private List<WorkflowNodeRunResp> nodeRuns = new ArrayList<>();
    private List<WorkflowHumanTaskResp> humanTasks = new ArrayList<>();

    /**
     * 聚合模型 → 响应。
     *
     * @param detail 详情
     * @param json   用于把 JSON 文本解析成节点
     * @return 响应
     */
    public static WorkflowRunDetailResp of(WorkflowRunDetail detail, ObjectMapper json) {
        WorkflowRunDetailResp resp = new WorkflowRunDetailResp();
        WorkflowRun run = detail.getRun();
        resp.setId(run.getId());
        resp.setWorkflowId(run.getWorkflowId());
        resp.setReleaseId(run.getReleaseId());
        resp.setReleaseVersion(run.getReleaseVersion());
        resp.setStatus(run.getStatus());
        resp.setTriggerType(run.getTriggerType());
        resp.setAppId(run.getAppId());
        resp.setInput(read(json, run.getInputJson()));
        resp.setOutput(read(json, run.getOutputJson()));
        resp.setErrorSummary(run.getErrorSummary());
        resp.setStartTime(run.getStartTime());
        resp.setEndTime(run.getEndTime());
        resp.setCreateTime(run.getCreateTime());
        for (WorkflowNodeRun nodeRun : detail.getNodeRuns()) {
            WorkflowNodeRunResp item = new WorkflowNodeRunResp();
            item.setId(nodeRun.getId());
            item.setNodeId(nodeRun.getNodeId());
            item.setNodeCode(nodeRun.getNodeCode());
            item.setNodeType(nodeRun.getNodeType());
            item.setAttempt(nodeRun.getAttempt());
            item.setStatus(nodeRun.getStatus());
            item.setErrorSummary(nodeRun.getErrorSummary());
            item.setSelectedEdges(nodeRun.getSelectedEdges());
            item.setStartTime(nodeRun.getStartTime());
            item.setEndTime(nodeRun.getEndTime());
            resp.getNodeRuns().add(item);
        }
        for (WorkflowHumanTask task : detail.getOpenHumanTasks()) {
            WorkflowHumanTaskResp item = new WorkflowHumanTaskResp();
            item.setId(task.getId());
            item.setRunId(task.getRunId());
            item.setNodeRunId(task.getNodeRunId());
            item.setNodeCode(task.getNodeCode());
            item.setStatus(task.getStatus());
            item.setAssigneeRule(task.getAssigneeRule());
            item.setTimeoutPolicy(task.getTimeoutPolicy());
            item.setExpireTime(task.getExpireTime());
            item.setResultJson(task.getResultJson());
            resp.getHumanTasks().add(item);
        }
        return resp;
    }

    /**
     * 宽松解析：空串或坏 JSON 一律给 null，详情接口不因历史脏数据 500。
     */
    private static JsonNode read(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }
}
