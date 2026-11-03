package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 套件出参。所属域：eval。
 * <p>defaultConfig 回的是解析后的 JSON（库内存文本），空配置为 null；
 * readOnly=true 表示 tenant_id=0 的平台样例，前端应隐藏写按钮。
 */
@Data
public class EvalSuiteResp {

    private Long id;
    private Long tenantId;
    private String suiteKey;
    private String name;
    private String description;
    /** 当前版本号；查用例需带上它 */
    private Integer currentVersion;
    private JsonNode defaultConfig;
    private Integer enabled;
    /** true 表示平台内置样例（只读），租户用户不可改 */
    private Boolean readOnly;
    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
