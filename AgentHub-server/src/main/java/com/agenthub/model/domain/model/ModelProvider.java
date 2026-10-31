package com.agenthub.model.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商。所属域：model。一张卡片对应一行，Key 不挂在这里。
 */
@Data
public class ModelProvider {

    private Long id;
    private String name;
    private String protocol;
    private String baseUrl;
    private Integer status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
