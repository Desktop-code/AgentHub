package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorRow;

import java.util.Collection;
import java.util.List;

/**
 * 记忆向量存储端口。所属域：memory。
 * <p>
 * 调用方：{@code MemoryVectorSupport}（就绪自检）、{@code MemoryVectorWriter}（LONG 双写 + 联删）。
 * 实现方：{@code com.agenthub.memory.infra.milvus}，复用 rag 域已建好的 Milvus 连接。
 * <p>
 * 关键约束：
 * <ul>
 *   <li><b>幂等</b>：集合已存在时必须直接返回成功，重复调用不得重建/清空数据；</li>
 *   <li><b>维度单一来源</b>：建集维度只由调用方透传（平台级 Embedding 的 {@code embedding_dim} 快照），
 *   实现方不得内置默认维度，也不得读 YAML 兜底；</li>
 *   <li><b>失败不吞</b>：初始化/写入失败必须抛 {@link MemoryVectorStoreException}，由上层降级；</li>
 *   <li>本端口只管记忆 collection，禁止读写 RAG 的 {@code agenthub_kb_*} 集合；</li>
 *   <li>检索必须同时过滤 user / agent / 有效标记 / 未过期，正文副本可回传但仅作兜底。</li>
 * </ul>
 */
public interface MemoryVectorStore {

    /**
     * 确保记忆专用集合存在、维度与字段一致且已加载；已存在则跳过创建（幂等）。
     *
     * @param collectionName 记忆 collection 名（须由 {@link MemoryCollectionName#isManaged(String)} 校验）
     * @param dimension      向量维度，来自平台级 Embedding 的维度快照，必须 &gt; 0
     * @throws MemoryVectorStoreException Milvus 不可用、维度/字段与既有集合不一致、建集或加载失败
     */
    void ensureCollection(String collectionName, int dimension);

    /**
     * 读取既有集合的 embedding 维度。用于「维度冲突可观测」：上层据此在状态里给出可读原因，
     * 并决定是否需要走受控重建。
     * <p>
     * 只读：集合不存在、schema 读不到或字段缺失时返回 {@code null}，<b>不抛异常</b>
     * （探测失败不应把就绪判定变成基础设施故障）。
     *
     * @param collectionName 记忆 collection 名
     * @return 既有集合的向量维度；无法判断为 null
     */
    Integer embeddingDimension(String collectionName);

    /**
     * 删除记忆专用集合。<b>仅供受控重建 API 调用</b>：普通 ensure / 写入 / 召回路径
     * 一律不得调用本方法。集合不存在视为成功（幂等）。
     * <p>
     * 实现方必须拒绝记忆命名空间之外的集合名，避免误删 RAG 知识库集合。
     *
     * @param collectionName 记忆 collection 名（须由 {@link MemoryCollectionName#isManaged(String)} 校验）
     * @throws MemoryVectorStoreException 名不受管理、Milvus 不可用或删除失败
     */
    void dropCollection(String collectionName);

    /**
     * 按 {@code memory_id} 覆盖写入一条记忆向量（upsert 幂等，重复写同 id 不会产生重复行）。
     * <p>
     * 调用方须保证向量已 L2 归一化且长度等于建集维度；实现方不做截断或补零。
     *
     * @param collectionName 记忆 collection
     * @param row            待写入行
     * @throws MemoryVectorStoreException Milvus 不可用或写入失败
     */
    void upsert(String collectionName, MemoryVectorRow row);

    /**
     * 按 {@code memory_id} 删除记忆向量。用于逻辑删除、超限淘汰、过期清理的联动删除。
     * <p>
     * 幂等：id 不存在时视为成功；空集合直接返回，不发起出站。
     *
     * @param collectionName 记忆 collection
     * @param memoryIds      待删除的 memory_item.id
     * @throws MemoryVectorStoreException Milvus 不可用或删除失败
     */
    void remove(String collectionName, Collection<Long> memoryIds);

    /**
     * 按用户 + Agent 维度做向量检索，强制过滤 {@code user_id}、{@code agent_id}、
     * {@code deleted == 0} 以及未过期（{@code expired_time == 0 || expired_time > now}）。
     * <p>
     * 命中带主键、分数与 {@code content / importance / expired_time} 三个副本字段：
     * 正文副本只用于「MySQL 回查取不到正文」时兜底，<b>注入内容仍以 MySQL 对齐结果为准</b>
     * （向量库里的行可能已被逻辑删除或过期）。查询向量须已 L2 归一化（与写入同一口径）。
     *
     * @param collectionName 记忆 collection
     * @param userId         平台用户；null 按 0 处理（与写入口径一致）
     * @param agentId        Agent；null 按 0 处理
     * @param queryVector    查询向量（已归一化）
     * @param topK           候选条数上限，&gt;0
     * @return 命中，按分数降序；无命中为空列表
     * @throws MemoryVectorStoreException Milvus 不可用、维度不符或检索失败
     */
    List<MemoryVectorHit> search(String collectionName, Long userId, Long agentId, float[] queryVector, int topK);
}
