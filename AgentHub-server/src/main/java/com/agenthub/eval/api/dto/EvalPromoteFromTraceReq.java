package com.agenthub.eval.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 从观测摘要手填提升为 case（不读 obs 表）。所属域：eval。
 */
@Data
public class EvalPromoteFromTraceReq {

    /** 用户问题 / 输入 message，必填 */
    @NotBlank
    private String message;
    /** 终答摘要，可空 */
    private String finalAnswer;
    /** 会话 id，可空（写入 expect 备注） */
    private String sessionId;
    /** Trace id，可空 */
    private String traceId;
    /** 新 case_key；空则服务端生成 */
    private String caseKey;
    /** 标题 */
    private String title;
    /** 备注 */
    private String note;
}
