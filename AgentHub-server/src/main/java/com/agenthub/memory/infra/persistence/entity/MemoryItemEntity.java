package com.agenthub.memory.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * memory_item。所属域：memory。列与 schema-delta-memory / fact-key 补丁一致，本类不建表。
 * 正文不进 toString。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_item")
public class MemoryItemEntity extends MemoryAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long userId;
    private Long appId;
    private Long sessionId;
    private String scope;
    private String title;
    /** LONG 同键合并；见 sql/schema-delta-memory-fact-key.sql */
    private String factKey;
    @ToString.Exclude
    private String content;
    private String contentHash;
    private Integer importance;
    private Integer status;
    private LocalDateTime expiredTime;
    private String remark;

    @TableLogic
    private Integer deleted;
}
