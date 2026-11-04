package com.agenthub.obs.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 某个会话或某个工作流下的 trace 分页。所属域：obs。
 * userId 为空表示调用方已经确认是管理员，不再按人过滤。
 * sessionId 与 workflowRunIds 互斥使用：对话按会话查，工作流按 run 集合查。
 */
@Data
public class ObsTraceQuery {

    private long current = 1;
    private long size = 20;
    /** 对话侧：会话主键；按工作流查询时为空 */
    private Long sessionId;
    /** 工作流侧：该定义下最近若干 run 的 id（obs_trace.workflow_run_id 集合）；空表示不限 */
    private List<String> workflowRunIds;
    /** 非空时只看这个平台用户 */
    private Long userId;
    /** 模糊匹配 title，可空 */
    private String keyword;
}
