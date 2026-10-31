package com.agenthub.tool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具源卡片与详情。所属域：tool。列表、详情、创建、重置一律只填掩码。
 * 出于防泄露考虑，本 DTO 不设明文鉴权头字段：任何接口都不把完整鉴权头回给前端。
 */
@Data
public class SourceResp {

    private Long id;
    private String name;
    private String type;
    private String transport;
    private String baseUrl;
    private String authHeaderMask;
    private Integer status;
    private String remark;
    private LocalDateTime lastSyncTime;
    private Integer lastSyncOk;
    private String lastSyncMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
