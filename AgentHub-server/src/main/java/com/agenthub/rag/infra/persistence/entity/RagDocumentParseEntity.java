package com.agenthub.rag.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * rag_document_parse。所属域：rag 的 infra。
 * <p>
 * 一篇文档只保留一份「本轮入库实际使用」的解析文本（doc_id 唯一），
 * 供文档页左栏原文对照、重切预览与切片偏移映射使用。
 * 不接逻辑删除：删文档 / 删知识库时随业务行物理清理，避免残留全文占用空间。
 */
@Data
@TableName("rag_document_parse")
public class RagDocumentParseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private Long docId;
    /** 解析文本，换行已统一为 \n，字符偏移以此列为准 */
    private String content;
    private Integer charCount;
    /** 结构识别出的标题数 */
    private Integer headerCount;
    /** IR 原型 */
    private String docType;
    /** 实际路由到的切片策略 */
    private String strategy;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
