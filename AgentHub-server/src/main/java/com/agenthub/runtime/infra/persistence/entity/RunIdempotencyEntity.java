package com.agenthub.runtime.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent_run_idempotency。所属域：runtime。
 * <p>
 * 只做幂等占位与终态记录，不做业务实体关联；没有逻辑删除列——
 * 逻辑删除会让 {@code (session_id, idem_key)} 唯一键在「删了再加」时撞历史行。
 * 过期清理走独立 SQL（按 create_time），删的是真行。
 */
@Data
@TableName("agent_run_idempotency")
public class RunIdempotencyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** 客户端幂等键，与 sessionId 组成唯一键 */
    private String idemKey;
    /** 请求内容指纹（SHA-256 十六进制） */
    private String fingerprint;
    /** RUNNING / SUCCESS / FAILED */
    private String state;
    /** 本轮 MessageResp.status，仅终态有值 */
    private String runStatus;
    private String assistantPreview;
    private String errorSummary;
    /** 1 表示本轮停在计划待批准 */
    private Integer planPending;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
