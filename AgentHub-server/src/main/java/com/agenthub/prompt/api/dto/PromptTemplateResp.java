package com.agenthub.prompt.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * L2 模板出参。所属域：prompt。详情含 segments；列表可空段。
 */
@Data
public class PromptTemplateResp {

    private Long id;
    private String templateKey;
    private String name;
    private String purpose;
    private String locale;
    private String protocol;
    private Integer version;
    private Integer status;
    private Integer isActive;
    private String remark;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<PromptSegmentResp> segments = new ArrayList<>();
}
