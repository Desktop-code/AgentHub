/**
 * 知识库（RAG）域。所属域：rag。
 * <p>
 * 一期①只做「建知识库 + 初始化 Collection + CRUD」：MySQL 主表 {@code rag_knowledge_base}，
 * 本地 Milvus 一库一集合（PHYSICAL / PUBLIC），标量字段由 {@code RagCollectionSchema} 定死。
 * <p>
 * 分层：api（Controller + DTO）→ application（编排/事务）→ domain（模型 + 仓储接口 + 策略）→ infra（Milvus + 持久化）。
 * 对外只说「知识库」，Collection 名不出本域。禁止在 common / admin / agent 里写 RAG 逻辑。
 * <p>
 * 一期不做：文档入库（②）、检索 Tool（③）、Agent 绑定（④）、LOGICAL 共集合、RESTRICTED 授权、
 * 第三方 Dataset API。
 */
package com.agenthub.rag;
