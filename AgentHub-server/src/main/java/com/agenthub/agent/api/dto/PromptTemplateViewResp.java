package com.agenthub.agent.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉死版模板 + 段落（L3 表单渲染用）。所属域：agent。
 * <p>
 * 前端按 {@code mutable} 分栏：0 → 灰底只读；1 → 动态输入框（Label=displayName，提交键=segKey）。
 */
@Data
public class PromptTemplateViewResp {

    private Long id;
    private String templateKey;
    private String name;
    private String purpose;
    private String locale;
    private Integer version;
    private Integer isActive;
    private List<PromptTemplateSegmentResp> segments = new ArrayList<>();
}
