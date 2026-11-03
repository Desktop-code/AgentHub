package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * 用例出参。所属域：eval。
 * <p>所有 JSON 字段回的是解析后的节点；断言只回显，不执行（执行归刀3）。
 */
@Data
public class EvalCaseResp {

    private Long id;
    /** 所属套件版本；历史版本行可回读，但不可原地改 */
    private Integer suiteVersion;
    private String caseKey;
    private String title;
    /** 标签数组 */
    private List<String> tags;
    private JsonNode input;
    private JsonNode expect;
    private JsonNode assertions;
    private JsonNode scoring;
    private Integer enabled;
    private Integer sortOrder;
}
