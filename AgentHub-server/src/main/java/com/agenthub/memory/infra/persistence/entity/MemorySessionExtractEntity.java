package com.agenthub.memory.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * memory_session_extract。所属域：memory。
 * 会话级批抽幂等标记；列与 sql/schema-delta-long-extract-guard.sql 一致。
 */
@Data
@TableName("memory_session_extract")
public class MemorySessionExtractEntity {

    @TableId
    private Long sessionId;
    private String status;
    private String reason;
    private LocalDateTime updateTime;
}
