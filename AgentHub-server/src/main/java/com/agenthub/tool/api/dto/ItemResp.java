package com.agenthub.tool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具项出参。所属域：tool。来源名称和类型从工具源带出。
 */
@Data
public class ItemResp {

    private Long id;
    private Long sourceId;
    private String sourceName;
    private String sourceType;
    private String name;
    private String title;
    private String description;
    private String inputSchemaJson;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
