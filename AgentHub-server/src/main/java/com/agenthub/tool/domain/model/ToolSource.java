package com.agenthub.tool.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 工具源。所属域：tool。连接信息和最近一次同步记在这里，工具项另表。
 * authHeaderCipher 只给出站解密，禁止进入响应、操作日志和 toString。
 */
@Data
public class ToolSource {

    private Long id;
    private String name;
    private String type;
    private String transport;
    private String baseUrl;
    /** 密文，禁止进入响应和操作日志 */
    @ToString.Exclude
    private String authHeaderCipher;
    private String authHeaderMask;
    private Integer status;
    private String remark;
    private LocalDateTime lastSyncTime;
    private Integer lastSyncOk;
    private String lastSyncMsg;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
