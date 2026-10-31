package com.agenthub.rag.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 文档。所属域：rag。对应 rag_document。
 * 只存文件元数据与状态；正文在 rag_chunk，向量在 Milvus，原文件在配置的落盘目录。
 */
@Data
public class RagDocument {

    /** 主键；同时是 Milvus 标量 doc_id */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** 所属知识库 */
    private Long kbId;
    /** 标题，通常取文件名 */
    private String title;
    /** 原文件路径（落盘目录内），或对象存储地址 */
    private String sourceUri;
    /** MIME 或扩展名 */
    private String mime;
    /** 原文件字节数 */
    private Long byteSize;
    /** 原文件 SHA-256，用于重灌去重 */
    private String contentHash;
    /** 切片数 */
    private Integer chunkCount;
    /** PENDING / RUNNING / READY / FAILED */
    private String status;
    /** 失败原因；成功时清空 */
    private String errorMsg;
    /** 重灌代次，从 1 起 */
    private Integer version;
    /** 上传人 */
    private Long createdBy;
    /** 上传时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
