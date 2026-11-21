package com.agenthub.rag.application;

import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.service.RagQueryRewriter;
import com.agenthub.spi.rag.SearchQuery;
import com.agenthub.spi.rag.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一轮里跨专家复用的知识库检索结果。所属域：rag。
 * <p>
 * 缓存键是「一个知识库 + 已经解析好的检索参数」，不是专家，也不是整段用户原话。
 * 键里至少有：库 id、查询文本、topK / 向量与 BM25 召回条数 / RRF / 阈值 / 租户、
 * 嵌入模型与维度、库级与 Agent 级重排，以及这次是不是把 Agent 重排延后到多库合并。
 * 查询文本只做检索服务原本就有的 trim，不改写、不合并不同问句。
 * <p>
 * 存放在当前 {@link ObsTraceScope} 的本轮槽里。并行专家绑的是同一个 scope，所以能互相看见。
 * 没有 trace（评测直调、单测未绑轮次）时不缓存。轮结束由 scope {@code clearTurnSlots} 清空，
 * 下一轮是新的 scope，不会串到下一条消息，也不是跨会话或全局缓存。
 * <p>
 * 只保存已经成功返回的命中（含真零命中）。失败不写入，下一位专家仍可再查这一库。
 * 读出的是副本，后到的专家再重排或截断不会改掉已存的那份。
 */
final class SameTurnRetrievalMemo {

    /** 挂在 trace 本轮槽上的名字，只给本类用 */
    private static final String SLOT = "rag.sameTurnRetrieval";

    /** 单库成功结果。键是库和检索参数 */
    private final ConcurrentHashMap<Key, List<SearchResult>> byKb = new ConcurrentHashMap<>();
    /**
     * 一次多库调用的最终结果（合并、截断、Agent 级重排之后）。
     * 库集合或参数不同则键不同，不会把别人的库带出去。
     */
    private final ConcurrentHashMap<List<Key>, List<SearchResult>> byCall = new ConcurrentHashMap<>();
    /**
     * 本轮已做过的查询改写：键是「Agent + 原问句」。
     * <p>
     * 为什么值得单独缓存：改写是唯一一次真实模型出站，比嵌入更贵；
     * 同一轮里多个专家问同一句时，没有这层缓存就会为同一句话重复出站与计费。
     * 改写结果与具体知识库无关（开关只决定用不用它），因此键里不需要库。
     */
    private final ConcurrentHashMap<RewriteKey, RagQueryRewriter.Result> rewrites = new ConcurrentHashMap<>();

    private SameTurnRetrievalMemo() {
    }

    /**
     * 当前轮的复用表。没有绑 trace 时返回 null，调用方按原样出站。
     *
     * @return 本轮表；不在一轮提问里时为 null
     */
    static SameTurnRetrievalMemo current() {
        ObsTraceScope scope = ObsTraceHolder.current();
        if (scope == null) {
            return null;
        }
        Object existing = scope.turnSlot(SLOT);
        if (existing instanceof SameTurnRetrievalMemo memo) {
            return memo;
        }
        SameTurnRetrievalMemo created = new SameTurnRetrievalMemo();
        Object raced = scope.putTurnSlotIfAbsent(SLOT, created);
        return raced instanceof SameTurnRetrievalMemo memo ? memo : created;
    }

    /**
     * 按已解析的检索参数组键。查询文本用解析后的值，不再另做归一。
     *
     * @param kb                 知识库；id 必须已有
     * @param sq                 已夹紧、已套上 Agent 重排开关的参数
     * @param agentRerankModelId Agent 级重排实例；可空
     * @param deferAgentRerank   true 表示 Agent 重排留到多库合并之后
     * @return 缓存键
     */
    static Key key(RagKnowledgeBase kb, SearchQuery sq, Long agentRerankModelId, boolean deferAgentRerank) {
        return new Key(kb.getId(), sq.getQuery(), sq.getTopK(), sq.getVectorTopK(), sq.getBm25TopK(),
                sq.getRrfK(), sq.getRrfTopN(), sq.isRrfEnabled(), sq.isRerankEnabled(),
                Double.doubleToLongBits(sq.getScoreThreshold()), sq.getMaxChunksPerDoc(), sq.getMergedTopK(),
                sq.getTenantId(),
                kb.getEmbeddingModelId(), kb.getVectorDimension(), kb.getRerankModelId(),
                agentRerankModelId, deferAgentRerank);
    }

    /**
     * 多库调用的键：按库 id 排好，避免同一批库因遍历顺序不同而各查一遍。
     * 排序只用于认键，不改变真正检索时的库顺序。
     *
     * @param keys 各库的键；调用方保证非空
     * @return 不可变键
     */
    static List<Key> callKey(List<Key> keys) {
        List<Key> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingLong(Key::kbId));
        return List.copyOf(sorted);
    }

    /**
     * 取单库已成功的命中。没有则 null，空列表表示上次确实没查到。
     *
     * @param key 库和检索参数
     * @return 副本；未命中缓存为 null
     */
    List<SearchResult> find(Key key) {
        List<SearchResult> stored = byKb.get(key);
        return stored == null ? null : copyAll(stored);
    }

    /**
     * 记下单库成功结果。后写覆盖先写，内容相同，不需要为了这个把检索改成串行。
     *
     * @param key  库和检索参数
     * @param hits 本次成功返回的命中
     */
    void save(Key key, List<SearchResult> hits) {
        byKb.put(key, copyAll(hits));
    }

    /**
     * 取一次多库调用的最终结果。
     *
     * @param callKey {@link #callKey} 的结果
     * @return 副本；没有则 null
     */
    List<SearchResult> findCall(List<Key> callKey) {
        List<SearchResult> stored = byCall.get(callKey);
        return stored == null ? null : copyAll(stored);
    }

    /**
     * 记下一次多库调用的最终结果。只在每个库都成功时由调用方写入。
     *
     * @param callKey {@link #callKey} 的结果
     * @param hits    合并并完成重排后的命中
     */
    void saveCall(List<Key> callKey, List<SearchResult> hits) {
        byCall.put(callKey, copyAll(hits));
    }

    /**
     * 取本轮已做过的同一条改写。没有则 null，由调用方决定是否真正出站。
     *
     * @param agentId Agent
     * @param query   用户原问句
     * @return 上次的改写结果；没做过为 null
     */
    RagQueryRewriter.Result findRewrite(Long agentId, String query) {
        if (agentId == null || query == null) {
            return null;
        }
        return rewrites.get(new RewriteKey(agentId, query));
    }

    /**
     * 记下本轮的一次改写结果（成功与失败回退都记）。
     * <p>
     * 失败的也记：同一轮里同一句话再问一次时，没必要再等一次模型超时；
     * 下一轮是新 scope，自然重新尝试。
     *
     * @param agentId Agent
     * @param query   用户原问句
     * @param result  改写结果
     */
    void saveRewrite(Long agentId, String query, RagQueryRewriter.Result result) {
        if (agentId == null || query == null || result == null) {
            return;
        }
        rewrites.put(new RewriteKey(agentId, query), result);
    }

    /**
     * 一个知识库在一组检索参数下的键。所属域：rag。
     *
     * @param kbId                知识库
     * @param query               检索文本（已 trim）
     * @param topK                最终条数
     * @param vectorTopK          向量召回条数
     * @param bm25TopK            BM25 召回条数
     * @param rrfK                RRF 平滑常数
     * @param rrfTopN             RRF 保留条数
     * @param rrfEnabled          是否走双路融合
     * @param rerankEnabled       是否开启重排
     * @param scoreBits           向量阈值的二进制，避免 double 相等比较漂移
     * @param tenantId            租户过滤；可空
     * @param embeddingModelId    嵌入模型
     * @param vectorDimension     向量维度
     * @param kbRerankModelId     库自带重排模型；可空
     * @param agentRerankModelId  Agent 重排模型；可空
     * @param deferAgentRerank    Agent 重排是否延后到多库合并
     */
    record Key(long kbId, String query, int topK, int vectorTopK, int bm25TopK, int rrfK, int rrfTopN,
               boolean rrfEnabled, boolean rerankEnabled, long scoreBits, int maxChunksPerDoc,
               int mergedTopK, Long tenantId,
               Long embeddingModelId, Integer vectorDimension, Long kbRerankModelId,
               Long agentRerankModelId, boolean deferAgentRerank) {
    }

    /**
     * 本轮改写结果的键：Agent + 原问句。所属域：rag。
     * <p>
     * 不含知识库：改写结果与具体库无关，开关只决定「用不用它」，因此同一轮里多库可共用一次改写。
     *
     * @param agentId Agent
     * @param query   用户原问句（已 trim）
     */
    private record RewriteKey(Long agentId, String query) {
    }

    /**
     * 复制命中列表。字符串本身不可变，这里只切断列表和对象，避免后一位专家改分时污染缓存。
     *
     * @param hits 原命中；可空
     * @return 新列表
     */
    private static List<SearchResult> copyAll(List<SearchResult> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<SearchResult> copy = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            if (hit == null) {
                continue;
            }
            SearchResult row = new SearchResult();
            row.setChunkId(hit.getChunkId());
            row.setDocId(hit.getDocId());
            row.setKbId(hit.getKbId());
            row.setTitle(hit.getTitle());
            row.setSource(hit.getSource());
            row.setChunkIndex(hit.getChunkIndex());
            row.setContent(hit.getContent());
            row.setVectorScore(hit.getVectorScore());
            row.setBm25Score(hit.getBm25Score());
            row.setRrfScore(hit.getRrfScore());
            row.setRerankScore(hit.getRerankScore());
            row.setHitBy(hit.getHitBy());
            copy.add(row);
        }
        return List.copyOf(copy);
    }
}
