package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 套件详情出参。所属域：eval。
 * <p>GET /api/eval/suites/{id} 用：套件元数据 + 当前版本的用例。
 * 需要看历史版本时用 {@code /cases?suiteVersion=N} 单独取。
 */
@Data
public class EvalSuiteDetailResp {

    private Long id;
    private Long tenantId;
    private String suiteKey;
    private String name;
    private String description;
    private Integer currentVersion;
    private JsonNode defaultConfig;
    private Integer enabled;
    /** true 表示平台内置样例（只读） */
    private Boolean readOnly;
    /** 当前版本的用例列表，按 sort_order 升序 */
    private List<EvalCaseResp> cases;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
