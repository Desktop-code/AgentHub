package com.agenthub.eval.api.dto;

import lombok.Data;

/**
 * EvalScope 导出/触发出参。所属域：eval。
 * <p>status 为导出结果；triggerStatus / jobId 仅在请求 trigger 时有意义。
 */
@Data
public class EvalScopeExportResp {

    /** 导出状态：DISABLED 不会出现（未启用直接 BizException） */
    private String status;
    /** manifest 路径 */
    private String exportPath;
    /** 触发状态：OK / SKIPPED / ERROR；未请求触发时为 null */
    private String triggerStatus;
    /** 外部任务 id */
    private String jobId;
    /** 说明 */
    private String message;
}
