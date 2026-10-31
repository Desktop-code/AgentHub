package com.agenthub.tool.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * tool_source。所属域：tool。列名与 sql/schema-delta-tool.sql 一致。
 * 源 status 默认 1。密文列禁止出现在日志里。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tool_source")
public class ToolSourceEntity extends ToolAuditEntity {

    @TableId(type = IdType.AUTO)
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
    /** 库列 last_sync_time */
    private LocalDateTime lastSyncTime;
    private Integer lastSyncOk;
    private String lastSyncMsg;

    @TableLogic
    private Integer deleted;
}
