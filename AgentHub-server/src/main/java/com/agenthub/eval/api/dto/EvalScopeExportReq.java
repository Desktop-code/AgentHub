package com.agenthub.eval.api.dto;

import lombok.Data;

/**
 * EvalScope 导出/触发请求。所属域：eval。
 * <p>{@code trigger=true} 时在落盘后尝试 HTTP 创建外部任务；缺 base_url → SKIPPED。
 */
@Data
public class EvalScopeExportReq {

    /** 公开基准名（如 MMLU / GSM8K）；可空 */
    private String benchmark;
    /** 是否触发外部任务；默认 false（仅导出） */
    private Boolean trigger;
    /** 备注，写入清单 meta */
    private String note;
}
