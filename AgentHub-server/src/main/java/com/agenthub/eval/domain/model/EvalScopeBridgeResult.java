package com.agenthub.eval.domain.model;

import lombok.Data;

/**
 * EvalScope 桥一次调用结果。所属域：eval 的 domain。
 * <p>仅记账用：path / jobId / message；不写回 gate / verdict。
 */
@Data
public class EvalScopeBridgeResult {

    /** 状态 */
    private EvalScopeStatus status;
    /** 导出目录或 manifest 绝对/相对路径；可空 */
    private String exportPath;
    /** 外部任务句柄；仅 trigger 成功时有 */
    private String jobId;
    /** 人话说明 */
    private String message;

    /**
     * @param status  状态
     * @param message 说明
     * @return 结果
     */
    public static EvalScopeBridgeResult of(EvalScopeStatus status, String message) {
        EvalScopeBridgeResult one = new EvalScopeBridgeResult();
        one.setStatus(status);
        one.setMessage(message);
        return one;
    }

    /**
     * @param path    导出路径
     * @param message 说明
     * @return OK 结果
     */
    public static EvalScopeBridgeResult okExport(String path, String message) {
        EvalScopeBridgeResult one = of(EvalScopeStatus.OK, message);
        one.setExportPath(path);
        return one;
    }

    /**
     * @param jobId   外部任务 id
     * @param message 说明
     * @return OK 结果
     */
    public static EvalScopeBridgeResult okJob(String jobId, String message) {
        EvalScopeBridgeResult one = of(EvalScopeStatus.OK, message);
        one.setJobId(jobId);
        return one;
    }
}
