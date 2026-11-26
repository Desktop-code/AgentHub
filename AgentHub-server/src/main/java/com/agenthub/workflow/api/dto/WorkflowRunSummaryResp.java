package com.agenthub.workflow.api.dto;

import com.agenthub.workflow.domain.model.WorkflowRun;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * run 摘要响应。所属域：workflow 的 api。
 * <p>试跑抽屉历史列表用；不带节点明细，避免一次拉太多。
 * <p>草稿调试的 releaseId / releaseVersion 为空表示「未发布」，另带
 * {@link #draftBaseReleaseVersion} 说明当时默认发布到第几版；两者都不为空才是已发布 run。
 */
@Data
public class WorkflowRunSummaryResp {

    private Long id;
    private String status;
    private String triggerType;
    /** 应用触发时的 sys_app.id；试跑为空 */
    private Long appId;
    /** workflow_release.id；按当前草稿调试时为空 */
    private Long releaseId;
    /** 发布版本号；按当前草稿调试时为空 */
    private Integer releaseVersion;
    /**
     * 草稿调试开始时的默认发布版本号快照。
     * <p>为空表示当时从未发布过，或这条不是草稿调试；列表据此显示「vN 未发布」或只显示「未发布」。
     */
    private Integer draftBaseReleaseVersion;
    /** 这次调试开始时的用户问题（input.query）；旧记录或没有 query 时为空串 */
    private String query;
    private String errorSummary;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;

    /**
     * @param run   run 头
     * @param query 这次调试开始时的用户问题；可空
     * @return 摘要
     */
    public static WorkflowRunSummaryResp of(WorkflowRun run, String query) {
        WorkflowRunSummaryResp resp = new WorkflowRunSummaryResp();
        resp.setId(run.getId());
        resp.setStatus(run.getStatus());
        resp.setTriggerType(run.getTriggerType());
        resp.setAppId(run.getAppId());
        resp.setReleaseId(run.getReleaseId());
        resp.setReleaseVersion(run.getReleaseVersion());
        resp.setDraftBaseReleaseVersion(run.getDraftBaseReleaseVersion());
        resp.setQuery(query == null ? "" : query);
        resp.setErrorSummary(run.getErrorSummary());
        resp.setStartTime(run.getStartTime());
        resp.setEndTime(run.getEndTime());
        resp.setCreateTime(run.getCreateTime());
        return resp;
    }
}
