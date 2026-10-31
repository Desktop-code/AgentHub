package com.agenthub.tool.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具项。所属域：tool。挂在某个工具源下，status=1 才算进入白名单。
 */
@Data
public class ToolItem {

    private Long id;
    private Long sourceId;
    private String name;
    private String title;
    private String description;
    private String inputSchemaJson;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
