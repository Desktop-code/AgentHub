package com.agenthub.eval.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 从评测结果提升为新 case。所属域：eval。
 * <p>走 CaseStore 升版；同 result 多次 promote 允许生成多条（不同 case_key）。
 */
@Data
public class EvalPromoteFromResultReq {

    /** 源 eval_result.id */
    @NotNull
    private Long resultId;
    /** 新 case_key；空则服务端生成 bc-{原key}-{resultId} */
    private String caseKey;
    /** 标题；空则带「坏案回流」前缀 */
    private String title;
    /** 备注，写入 expect.note 与 tags */
    private String note;
    /** 非 FAIL 时须 true（如 ERROR）；FAIL 不需要 */
    private Boolean force;
}
