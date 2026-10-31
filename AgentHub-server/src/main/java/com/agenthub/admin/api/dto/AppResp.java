package com.agenthub.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用出参。所属域：admin。appSecret 只在创建或重置时出现一次。
 */
@Data
public class AppResp {

    private Long id;
    private String appKey;
    private String name;
    private Long deptId;
    private String deptName;
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private List<Long> agentIds = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String appSecret;
}
