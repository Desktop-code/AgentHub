package com.agenthub.rag.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * rag_document。所属域：rag 的 infra。列名与 schema-delta-rag + rename-time-rest 一致。
 * 与知识库主表不同，本表审计列只有 created_by / create_time / update_time，不继承 RagAuditEntity。
 */
@Data
@TableName("rag_document")
public class RagDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private String title;
    private String sourceUri;
    private String mime;
    private Long byteSize;
    /** 原文件 SHA-256，用于重灌去重 */
    private String contentHash;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
