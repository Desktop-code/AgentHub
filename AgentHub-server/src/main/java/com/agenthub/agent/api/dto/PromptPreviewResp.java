package com.agenthub.agent.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 预览出参。所属域：agent。正文由 PromptAssembler 真实组装得到，不调用任何模型。
 */
@Data
public class PromptPreviewResp {

    private String text;
    private String purpose;
    private String locale;
    private Integer constraintVersion;
    private String templateKey;
    private Integer templateVersion;
    private Integer segmentCount;
    private Integer overriddenSegmentCount;
    private boolean truncated;
    /** 非致命说明（如旧版残留的失效段键被忽略） */
    private List<String> notices = new ArrayList<>();
}
