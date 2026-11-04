package com.agenthub.workflow.api.dto;

import com.agenthub.workflow.domain.model.WorkflowRun;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * run 摘要响应。所属域：workflow 的 api。
 * <p>试跑抽屉历史列表用；不带节点明细，避免一次拉太多。
 */
@Data
public class WorkflowRunSummaryResp {

    private Long id;
    private String status;
    private String triggerType;
    /** 应用触发时的 sys_app.id；试跑为空 */
    private Long appId;
    private Long releaseId;
    private Integer releaseVersion;
    private String errorSummary;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;

    /**
     * @param run run 头
     * @return 摘要
     */
    public static WorkflowRunSummaryResp of(WorkflowRun run) {
        WorkflowRunSummaryResp resp = new WorkflowRunSummaryResp();
        resp.setId(run.getId());
        resp.setStatus(run.getStatus());
        resp.setTriggerType(run.getTriggerType());
        resp.setAppId(run.getAppId());
        resp.setReleaseId(run.getReleaseId());
        resp.setReleaseVersion(run.getReleaseVersion());
        resp.setErrorSummary(run.getErrorSummary());
        resp.setStartTime(run.getStartTime());
        resp.setEndTime(run.getEndTime());
        resp.setCreateTime(run.getCreateTime());
        return resp;
    }
}
