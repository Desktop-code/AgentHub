package com.agenthub.prompt.domain.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * L2 用途模板。所属域：prompt。
 * 对应 prompt_template；详情可携带 segments，列表不必带正文段。
 */
@Data
public class PromptTemplate {

    private Long id;
    private String templateKey;
    private String name;
    private String purpose;
    private String locale;
    private String protocol;
    private Integer version;
    private Integer status;
    private Integer isActive;
    /** 内部备注，永不注入模型 */
    private String remark;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<PromptSegment> segments = new ArrayList<>();
}
