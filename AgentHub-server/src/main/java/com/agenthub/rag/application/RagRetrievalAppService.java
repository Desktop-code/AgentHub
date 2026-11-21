package com.agenthub.rag.application;

import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.KbSelector;
import com.agenthub.rag.domain.service.RagAccessPolicy;
import com.agenthub.rag.domain.service.RagQueryRewriter;
import com.agenthub.rag.domain.service.RetrievalConfigResolver;
import com.agenthub.rag.domain.service.RrfFusion;
import com.agenthub.spi.rag.EmbeddingClient;
import com.agenthub.spi.rag.RagProvider;
import com.agenthub.spi.rag.RagStoreException;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankException;
import com.agenthub.spi.rag.RerankHit;
import com.agenthub.spi.rag.SearchQuery;
import com.agenthub.spi.rag.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索用例。所属域：rag。③ 期的核心，RAG 检索 Tool 直接调这里。
 * <p>
 * 检索链路：<br>
 * query → 选库(按原问) → 可选查询改写 → Embedding(按 KB 的模型) → 向量召回 ∥ BM25 召回（能力支持时）
 * → RRF 融合 → 截断 → MySQL 回填全文。
 * <p>
 * 关键约定：
 * <ul>
 *   <li><b>向量必做</b>；BM25 与 RRF 只在集合具备 DENSE_BM25 能力时才走，DENSE_ONLY 自动退化为纯向量，不报错；</li>
 *   <li><b>Rerank / 改写默认关</b>；Rerank 开启且配了模型时走 {@link RerankClient}（同连通测矩阵，DASHSCOPE text-rerank）；</li>
 *   <li><b>改写两道门</b>：该库（或入选库之一）{@code rewriteEnabled=开} 且 Agent 的 {@code REWRITE}
 *       用途槽有可用模型。缺一则不改写、按原问句检索且不报错；改写失败也回退原问句。
 *       一次工具调用最多改写一次，且<b>不做</b>「原问 + 改写」双路融合——改写成功后该库召回、
 *       RRF、重排都只用改写句；<b>选库永远用原问句</b>；</li>
 *   <li><b>无权限明确报错</b>：不可见 / 未初始化 / 已停用一律抛业务异常，**绝不返回空列表冒充「没查到」**；</li>
 *   <li><b>防爆</b>：条数上限、单条字符上限、总字符上限三层截断，避免把上下文挤爆；</li>
 *   <li><b>可观测</b>：工具调用内按检索链路写 obs 子步（{@link RagObsSpans}）；无 trace 时全部空操作，
 *       不改变这里的任何算法与返回。</li>
 * </ul>
 * 同一轮（当前 trace）里，相同知识库和相同检索参数的成功结果会复用，不再重复嵌入和召回。
 * 结果只放在这一轮的 trace 上，轮结束清空。调用方仍只传入该专家自己有权的库，缓存不会把别人的库带出来。
 * 本类不做权限之外的越权判断（如部门级过滤）；一期按 KB 可见性放行。
 */
@Service
public class RagRetrievalAppService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalAppService.class);

    /** 单条命中正文上限：超出截断并明确标记，便于模型知道内容不全 */
    public static final int MAX_CONTENT_CHARS = 1200;
    /** 全部命中合计字符上限 */
    public static final int MAX_TOTAL_CHARS = 6000;

    private final RagKnowledgeBaseRepository kbs;
    private final RagChunkRepository chunks;
    private final RagProvider provider;
    private final EmbeddingClient embeddings;
    private final RerankClient rerank;
    /**
     * 查询改写端口。为 null 表示当前装配没有改写能力（评测直调、单测），
     * 此时开关打开也只按原问句检索并在 Trace 里说明原因。
     */
    private final RagQueryRewriter rewriter;

    /**
     * @param kbs        知识库
     * @param chunks     切片（回填完整正文）
     * @param provider   向量库
     * @param embeddings 向量化（测试用 mock 替身注入）
     * @param rerank     可选精排；与连通测共用协议×用途矩阵
     */
    public RagRetrievalAppService(RagKnowledgeBaseRepository kbs, RagChunkRepository chunks,
                                  RagProvider provider, EmbeddingClient embeddings, RerankClient rerank) {
        this(kbs, chunks, provider, embeddings, rerank, null);
    }

    /**
     * 带查询改写的装配。改写是增强项：端口缺省时全链路仍按原问句工作。
     *
     * @param kbs        知识库
     * @param chunks     切片
     * @param provider   向量库
     * @param embeddings 向量化
     * @param rerank     可选精排
     * @param rewriter   查询改写端口，可空
     */
    @Autowired
    public RagRetrievalAppService(RagKnowledgeBaseRepository kbs, RagChunkRepository chunks,
                                  RagProvider provider, EmbeddingClient embeddings, RerankClient rerank,
                                  RagQueryRewriter rewriter) {
        this.kbs = kbs;
        this.chunks = chunks;
        this.provider = provider;
        this.embeddings = embeddings;
        this.rerank = rerank;
        this.rewriter = rewriter;
    }

    /**
     * 检索单个知识库（不带 Agent 级重排覆盖）。
     *
     * @param kb            已通过可见性校验的知识库
     * @param query         检索句
     * @param topKOverride  Tool 入参覆盖的 topK，≤0 表示用 KB 默认
     * @param overridesJson Agent 绑定的覆盖项，可空
     * @return 命中，长度 ≤ topK，已截断；无命中返回空列表（调用方据此拒答）
     * @throws com.agenthub.common.error.BizException 知识库不可用、向量化或向量库失败
     */
    public List<SearchResult> retrieve(RagKnowledgeBase kb, String query, int topKOverride, String overridesJson) {
        return retrieve(kb, query, topKOverride, overridesJson, null);
    }

    /**
     * 检索单个知识库。
     *
     * @param kb                  已通过可见性校验的知识库
     * @param query               检索句
     * @param topKOverride        Tool 入参覆盖的 topK，≤0 表示用 KB 默认
     * @param overridesJson       Agent 绑定的覆盖项，可空
     * @param agentRerankModelId  Agent「知识库」Tab 配的重排实例，可空；
     *                            非空即以它为准并强制开启重排（Agent 配置优先于知识库自带配置）
     * @return 命中，长度 ≤ topK，已截断；无命中返回空列表（调用方据此拒答）
     * @throws com.agenthub.common.error.BizException 知识库不可用、向量化或向量库失败
     */
    public List<SearchResult> retrieve(RagKnowledgeBase kb, String query, int topKOverride, String overridesJson,
                                      Long agentRerankModelId) {
        String effectiveQuery = applyRewriteForSingle(kb, query, overridesJson, topKOverride);
        return retrieveInternal(kb, effectiveQuery, topKOverride, overridesJson, agentRerankModelId, false);
    }

    /**
     * 检索单个知识库的内部实现。
     *
     * @param kb                 当前知识库
     * @param query              检索句
     * @param topKOverride       覆盖条数
     * @param overridesJson      覆盖项 JSON
     * @param agentRerankModelId Agent 级重排实例，可空
     * @param deferAgentRerank   true 表示「Agent 级重排延后到多库合并后再做一次」，
     *                           由 {@link #retrieveMulti} 使用：多库各排一次既浪费出站，
     *                           又会让各库分数不可比（精排分只在同一批文档内可比）
     * @return 命中
     */
    private List<SearchResult> retrieveInternal(RagKnowledgeBase kb, String query, int topKOverride,
                                                String overridesJson, Long agentRerankModelId,
                                                boolean deferAgentRerank) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        SearchQuery sq = resolveQuery(kb, query, topKOverride, overridesJson, agentRerankModelId);
        // 同一轮、同一库、同一组检索参数：直接交还已查过的命中，不再嵌入、不再打向量库。
        // 未授权的库进不了这里——调用方只传入该专家自己校验过的库。
        SameTurnRetrievalMemo memo = SameTurnRetrievalMemo.current();
        SameTurnRetrievalMemo.Key memoKey = memo == null || kb.getId() == null
                ? null : SameTurnRetrievalMemo.key(kb, sq, agentRerankModelId, deferAgentRerank);
        if (memoKey != null) {
            List<SearchResult> reused = memo.find(memoKey);
            if (reused != null) {
                log.info("[RAG] 同轮复用知识库检索 kbId={}", kb.getId());
                return reused;
            }
        }
        // 检索子步只写 obs，不改算法；无 trace 时所有 step 都是空操作
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();

        // 1) 查询向量：维度强校验在 EmbeddingClient 里，错维会直接抛错而不是写入噪声
        RagObsSpans.Step vectorStep = obs.step(RagObsSpans.RETRIEVE_VECTOR, kb.getId(), sq.getQuery(),
                sq.getTopK(), stepInput(sq, "vectorTopK", sq.getVectorTopK(),
                        "scoreThreshold", sq.getScoreThreshold(),
                        "embeddingModelId", kb.getEmbeddingModelId()));
        List<SearchResult> vectorHits;
        int rawVectorCount;
        try {
            float[] vector = embeddings.embedOne(kb.getEmbeddingModelId(), sq.getQuery(), kb.getVectorDimension());
            // 2) 向量召回（必做）
            vectorHits = provider.searchVector(kb, sq.getQuery(), sq.getVectorTopK(), vector);
            rawVectorCount = vectorHits.size();
            // 集合空/错集但 MySQL 仍有切片：属同步故障，绝不能冒充「没查到」
            if (vectorHits.isEmpty()) {
                assertVectorStoreSynced(kb);
            }
            vectorHits = filterByThreshold(vectorHits, sq.getScoreThreshold());
        } catch (RuntimeException ex) {
            vectorStep.fail(ex);
            throw ex;
        }
        Map<String, Object> vectorAttrs = new LinkedHashMap<>();
        vectorAttrs.put("vectorTopK", sq.getVectorTopK());
        vectorAttrs.put("scoreThreshold", sq.getScoreThreshold());
        vectorAttrs.put("embeddingModelId", kb.getEmbeddingModelId());
        // 原始有召回但被阈值全过滤，和「向量库真没东西」是两回事，分开口径
        String vectorEmptyReason = vectorHits.isEmpty()
                ? (rawVectorCount > 0 ? "all_filtered_by_threshold" : "empty_vector")
                : null;
        vectorStep.hits("向量召回", vectorHits, rawVectorCount, vectorEmptyReason, vectorAttrs);

        // 3) BM25 + RRF：只在能力支持时；不支持就走纯向量
        List<SearchResult> fused;
        boolean bm25Supported = provider.supportsBm25(kb);
        if (sq.isRrfEnabled() && bm25Supported) {
            RagObsSpans.Step bm25Step = obs.step(RagObsSpans.RETRIEVE_BM25, kb.getId(), sq.getQuery(),
                    sq.getTopK(), stepInput(sq, "bm25TopK", sq.getBm25TopK()));
            List<SearchResult> bm25Hits;
            try {
                bm25Hits = provider.searchBm25(kb, sq.getQuery(), sq.getBm25TopK());
            } catch (RuntimeException ex) {
                bm25Step.fail(ex);
                throw ex;
            }
            bm25Step.hits("BM25 召回", bm25Hits, null,
                    bm25Hits.isEmpty() ? "empty_bm25" : null, Map.of("bm25TopK", sq.getBm25TopK()));
            if (bm25Hits.isEmpty()) {
                // BM25 无命中不等于失败：直接用向量结果，避免融合后反而变差
                obs.skip(RagObsSpans.RRF, kb.getId(), sq.getQuery(), sq.getTopK(), "bm25_empty",
                        "跳过：BM25 无命中，保留向量结果");
                fused = vectorHits;
            } else {
                RagObsSpans.Step rrfStep = obs.step(RagObsSpans.RRF, kb.getId(), sq.getQuery(),
                        sq.getTopK(), stepInput(sq, "rrfK", sq.getRrfK(), "rrfTopN", sq.getRrfTopN(),
                                "vectorHits", vectorHits.size(), "bm25Hits", bm25Hits.size()));
                try {
                    fused = RrfFusion.fuse(sq.getRrfK(), Math.max(sq.getRrfTopN(), sq.getTopK()),
                            vectorHits, bm25Hits);
                } catch (RuntimeException ex) {
                    rrfStep.fail(ex);
                    throw ex;
                }
                Map<String, Object> rrfAttrs = new LinkedHashMap<>();
                rrfAttrs.put("rrfK", sq.getRrfK());
                rrfAttrs.put("rrfTopN", sq.getRrfTopN());
                rrfAttrs.put("vectorHits", vectorHits.size());
                rrfAttrs.put("bm25Hits", bm25Hits.size());
                rrfStep.hits("RRF 融合", fused, null, fused.isEmpty() ? "empty_fusion" : null, rrfAttrs);
            }
        } else {
            String reason = !sq.isRrfEnabled() ? "rrf_disabled" : "provider_no_bm25";
            String text = !sq.isRrfEnabled() ? "跳过：未开启 RRF/BM25 双路" : "跳过：向量库不支持 BM25";
            obs.skip(RagObsSpans.RETRIEVE_BM25, kb.getId(), sq.getQuery(), sq.getTopK(), reason, text);
            obs.skip(RagObsSpans.RRF, kb.getId(), sq.getQuery(), sq.getTopK(), reason, text);
            fused = vectorHits;
        }

        // 4) 单篇文档限段：必须先于 topK 截断，否则一篇长文占满名额后，其它文档再相关也补不进来
        List<SearchResult> capped = capPerDoc(fused, sq.getMaxChunksPerDoc());

        // 4.5) 截断到 topK（精排前先收窄，避免 documents 过大）
        List<SearchResult> top = capped.size() > sq.getTopK()
                ? new ArrayList<>(capped.subList(0, sq.getTopK())) : capped;

        // 5) 用 MySQL 回填完整正文（Milvus 里只是截断副本）
        List<SearchResult> hydrated = hydrateAndClip(top);

        // 6) 可选 Rerank：失败只记日志并保留原序，不让整次检索失败
        //    Agent 级重排在多库场景下延后到合并之后统一做一次，避免 N 次出站
        List<SearchResult> done = maybeRerank(kb, sq, hydrated, agentRerankModelId, deferAgentRerank);
        if (memoKey != null) {
            // 只记成功返回。异常在上面已经抛出，不会把失败当成可复用的空结果
            memo.save(memoKey, done);
        }
        return done;
    }

    /**
     * 解析检索参数，并把 Agent 级重排开关套上去。
     * <p>
     * 单库检索和多库复用键都走这里，避免两处各写一遍「有模型就强制开启重排」。
     *
     * @param kb                 知识库
     * @param query              检索句；调用方已保证非空
     * @param topKOverride       覆盖条数，≤0 用配置默认
     * @param overridesJson      该库的覆盖项；可空
     * @param agentRerankModelId Agent 重排实例；可空
     * @return 已夹紧的参数
     */
    private static SearchQuery resolveQuery(RagKnowledgeBase kb, String query, int topKOverride,
                                            String overridesJson, Long agentRerankModelId) {
        SearchQuery sq = RetrievalConfigResolver.resolve(kb.getRetrievalConfig(), overridesJson,
                topKOverride, kb.getId(), query.trim(), kb.getTenantId());
        // Agent 侧配了重排就整体接管：开关与模型一起以 Agent 为准，避免出现「开了没模型 / 有模型没开」的半截状态
        if (agentRerankModelId != null && agentRerankModelId > 0) {
            sq.setRerankEnabled(true);
        }
        return sq;
    }

    /**
     * 配置开启时经模型中心客户端精排并写回 rerankScore。
     * <p>
     * 模型来源优先级：Agent「知识库」Tab 的重排绑定 &gt; 知识库自身的 rerankModelId。
     * 两者都没有则直接返回原序（不是错误，重排本来就是可选项）。
     *
     * @param kb                 当前检索库
     * @param sq                 已解析的检索参数
     * @param hits               候选命中
     * @param agentRerankModelId Agent 级重排实例，可空
     * @param deferAgentRerank   true 时跳过 Agent 级重排（交给多库合并后统一做），
     *                           知识库自带重排仍在此执行，因为那是各库自己的检索链路
     * @return 精排后的命中（或原序）
     */
    private List<SearchResult> maybeRerank(RagKnowledgeBase kb, SearchQuery sq, List<SearchResult> hits,
                                           Long agentRerankModelId, boolean deferAgentRerank) {
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();
        if (hits == null || hits.isEmpty()) {
            obs.skip(RagObsSpans.RERANK, kb.getId(), sq.getQuery(), sq.getTopK(), "no_candidates",
                    "跳过：没有可重排的候选");
            return hits == null ? List.of() : hits;
        }
        if (rerank == null) {
            obs.skip(RagObsSpans.RERANK, kb.getId(), sq.getQuery(), sq.getTopK(), "rerank_client_missing",
                    "跳过：精排客户端不可用");
            return hits;
        }
        boolean agentLevel = agentRerankModelId != null && agentRerankModelId > 0;
        if (agentLevel && deferAgentRerank) {
            // 多库合并后统一重排：此处不记 skip，由合并后的 rerank 步骤给出结果
            return hits;
        }
        Long modelId = agentLevel ? agentRerankModelId : kb.getRerankModelId();
        if (!sq.isRerankEnabled()) {
            obs.skip(RagObsSpans.RERANK, kb.getId(), sq.getQuery(), sq.getTopK(), "rerank_disabled",
                    "跳过：重排未开启");
            return hits;
        }
        if (modelId == null) {
            obs.skip(RagObsSpans.RERANK, kb.getId(), sq.getQuery(), sq.getTopK(), "rerank_model_missing",
                    "跳过：重排已开但未绑定重排模型");
            return hits;
        }
        return rerankHits(modelId, sq, hits, kb.getId());
    }

    /**
     * 对一批候选做一次精排：调用客户端并按返回顺序重排，失败保留原序。
     *
     * @param modelId 重排实例 id
     * @param sq      已解析参数（取检索句与 topK）
     * @param hits    候选命中
     * @return 精排后的命中（或原序）
     */
    private List<SearchResult> rerankHits(Long modelId, SearchQuery sq, List<SearchResult> hits, Long kbId) {
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();
        RagObsSpans.Step step = obs.step(RagObsSpans.RERANK, kbId, sq.getQuery(), sq.getTopK(),
                stepInput(sq, "modelInstanceId", modelId, "candidates", hits.size(),
                        "rerankTopN", sq.getTopK()));
        try {
            List<String> docs = new ArrayList<>(hits.size());
            for (SearchResult hit : hits) {
                docs.add(hit.getContent() == null ? "" : hit.getContent());
            }
            List<RerankHit> ranked = rerank.rerank(modelId, sq.getQuery(), docs, sq.getTopK());
            if (ranked == null || ranked.isEmpty()) {
                step.text("重排未返回结果，保留原序",
                        Map.of("modelInstanceId", modelId, "candidateCount", hits.size(), "fallback", true));
                return hits;
            }
            List<SearchResult> ordered = new ArrayList<>(ranked.size());
            for (RerankHit rh : ranked) {
                // 厂商可能返回越界下标；跳过比抛异常更稳，剩下的命中仍可用
                if (rh.index() < 0 || rh.index() >= hits.size()) {
                    continue;
                }
                SearchResult hit = hits.get(rh.index());
                hit.setRerankScore(rh.score());
                hit.setHitBy("rerank");
                ordered.add(hit);
            }
            if (ordered.isEmpty()) {
                step.text("重排结果下标全部越界，保留原序",
                        Map.of("modelInstanceId", modelId, "candidateCount", hits.size(), "fallback", true));
                return hits;
            }
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("modelInstanceId", modelId);
            attrs.put("candidateCount", hits.size());
            attrs.put("fallback", false);
            step.hits("重排结果", ordered, null, null, attrs);
            return ordered;
        } catch (RerankException ex) {
            // 精排是增强项：失败只记日志并保留原序，不让整次检索失败
            log.warn("[RAG] Rerank 失败，保留原序 modelInstanceId={} reason={}", modelId, ex.getMessage());
            step.fail(ex);
            return hits;
        } catch (RuntimeException ex) {
            step.fail(ex);
            throw ex;
        }
    }

    /**
     * 一次改写在检索链路里的结果。所属域：rag 的 application。
     * <p>
     * 只留检索侧真正需要的两项：实际生效的检索句、是否用上了改写句。
     * 跳过 / 失败的原因已写进 Trace，不必在内存对象里再带一份。
     *
     * @param query   实际生效的检索句（成功为改写句，否则原问句）
     * @param applied 是否真的用上了改写句
     */
    private record RewriteOutcome(String query, boolean applied) {

        /**
         * 没有改动问句（门未开 / 模型缺失 / 失败回退）。
         *
         * @param original 原问句
         * @return 结果
         */
        static RewriteOutcome unchanged(String original) {
            return new RewriteOutcome(original, false);
        }
    }

    /**
     * 单库入口的改写：开关开 + REWRITE 槽有模型才出站，失败回退原问句。
     * <p>
     * 单库没有「选库」这一步，因此门禁就是「该库开关」+「模型可用」。
     *
     * @param kb           知识库
     * @param query        用户原问句
     * @param overridesJson 该库的覆盖项；可空
     * @param topKOverride 工具入参 topK
     * @return 实际生效的检索句；不需要或失败时就是原问句
     */
    private String applyRewriteForSingle(RagKnowledgeBase kb, String query, String overridesJson, int topKOverride) {
        if (kb == null || query == null || query.isBlank()) {
            return query;
        }
        boolean requested = RagObsSpans.rewriteRequested(kb.getRetrievalConfig(), overridesJson);
        return executeRewrite(query, requested, topKOverride).query();
    }

    /**
     * 多库入口的改写：先按原问选库（调用方已做完），只要入选库里有一个开了开关就改写一次。
     * <p>
     * 硬约束：<b>一次工具调用最多改写一次</b>。多库共享同一句改写结果，
     * 不会按库各改一遍，也不做「原问 + 改写句」的双路融合。
     *
     * @param targets      选库后实际要检索的库
     * @param query        用户原问句
     * @param overrides    各库覆盖项；可空
     * @param topKOverride 工具入参 topK
     * @return 改写结果；未开门时 {@code applied=false} 且 query 为原问句
     */
    private RewriteOutcome applyRewriteForMulti(List<RagKnowledgeBase> targets, String query,
                                                Map<Long, String> overrides, int topKOverride) {
        if (targets == null || targets.isEmpty() || query == null || query.isBlank()) {
            return RewriteOutcome.unchanged(query);
        }
        boolean requested = false;
        for (RagKnowledgeBase kb : targets) {
            String override = overrides == null ? null : overrides.get(kb.getId());
            if (kb != null && RagObsSpans.rewriteRequested(kb.getRetrievalConfig(), override)) {
                requested = true;
                break;
            }
        }
        // 入选库都不需要改写：根本不调改写模型（不是「调了没用」，是连出站都不发生）
        return executeRewrite(query, requested, topKOverride);
    }

    /**
     * 真正执行一次改写并写 Trace；门未开、端口缺失、无模型、异常都在这里收口成「回退原问」。
     * <p>
     * Trace 口径：<b>每次工具调用只写一条 rewrite 子步</b>，三种终态互斥
     * （成功 / 跳过 / 失败回退，见 {@link RagObsSpans.Session#rewrite}）。
     *
     * @param query        用户原问句
     * @param requested    该次调用的入选库是否至少有一个开了改写开关（第一道门）
     * @param topKOverride 工具入参 topK，仅用于 span 展示
     * @return 改写结果，`query` 字段为实际生效的检索句
     */
    private RewriteOutcome executeRewrite(String query, boolean requested, int topKOverride) {
        String original = query == null ? "" : query.trim();
        Integer topK = topKOverride > 0 ? Integer.valueOf(topKOverride) : null;
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();
        // 第一道门：开关没开就到此为止，连改写端口都不碰
        if (!requested) {
            obs.rewrite(original, original, false, false, "rewrite_disabled", null, null, topK);
            return RewriteOutcome.unchanged(original);
        }
        if (rewriter == null) {
            // 装配缺端口（评测直调、单测）：不是配置错误，也不该在 Trace 里冒充「试过但失败」
            obs.rewrite(original, original, false, false, "rewrite_port_missing", null, null, topK);
            return RewriteOutcome.unchanged(original);
        }
        Long agentId = RagToolContext.agentIdOrNull();
        if (agentId == null) {
            obs.rewrite(original, original, false, false, "rewrite_no_agent", null, null, topK);
            return RewriteOutcome.unchanged(original);
        }
        // 第二道门（模型槽是否可用）在端口内判定：rag 只认 REWRITE 槽，绝不降级 CHAT
        RagQueryRewriter.Caller caller = new RagQueryRewriter.Caller(agentId,
                RagToolContext.sessionIdOrNull(), RagToolContext.operatorIdOrNull());
        // 同一轮、同一 Agent、同一原问：直接复用上次结果，避免为同一句话重复出站与计费
        SameTurnRetrievalMemo memo = SameTurnRetrievalMemo.current();
        RagQueryRewriter.Result result = memo == null ? null : memo.findRewrite(agentId, original);
        if (result != null) {
            log.info("[RAG] 同轮复用查询改写 agentId={} applied={}", agentId, result.applied());
        } else {
            result = rewriter.rewrite(caller, original);
            if (memo != null) {
                memo.saveRewrite(agentId, original, result);
            }
        }
        if (result == null) {
            // 实现方违反「不返回 null」的契约时也不能连累检索
            obs.rewrite(original, original, false, true, "rewrite_failed", null, null, topK);
            return RewriteOutcome.unchanged(original);
        }
        boolean applied = result.applied() && result.query() != null && !result.query().isBlank();
        // 失败回退 = 真的尝试过出站后没拿到可用改写句；与「本来就跳过」分开
        boolean failed = !applied && (result.errorType() != null || requiresAttemptedReason(result.reason()));
        String effective = applied ? result.query().trim() : original;
        // 一次调用只写一条 rewrite 子步：成功 / 跳过 / 失败回退三态互斥
        obs.rewrite(original, effective, applied, failed, result.reason(), result.errorType(),
                result.modelInstanceId(), topK);
        return new RewriteOutcome(effective, applied);
    }

    /**
     * 判断一个「未生效」原因是否属于「确实尝试过出站」。
     * <p>
     * 用来把「本来就跳过」（开关关 / 没模型）与「试了但失败回退」在 Trace 与日志里分开，
     * 否则排障时只能看到一串 not applied，分不清配置问题还是模型问题。
     *
     * @param reason 机器可读原因
     * @return true 表示属于失败回退
     */
    private static boolean requiresAttemptedReason(String reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case "rewrite_timeout", "rewrite_interrupted", "rewrite_model_error",
                    "rewrite_empty_result", "rewrite_too_long", "rewrite_prompt_unavailable",
                    "rewrite_failed" -> true;
            default -> false;
        };
    }

    /**
     * 该库本次实际使用的检索句：开关开且改写成功才用改写句，否则一律原问句。
     *
     * @param kb       知识库
     * @param override 该库覆盖项；可空
     * @param original 用户原问句
     * @param rewrite  本次改写结果
     * @return 检索句
     */
    private static String queryForKb(RagKnowledgeBase kb, String override, String original, RewriteOutcome rewrite) {
        if (rewrite == null || !rewrite.applied() || kb == null) {
            return original;
        }
        // 开关关的库仍用原问：只有明确开了改写的库才换成改写句
        boolean rewritable = RagObsSpans.rewriteRequested(kb.getRetrievalConfig(), override);
        return rewritable ? rewrite.query() : original;
    }

    /**
     * 拼子步入参：检索句 + kbId + 交替出现的键值对。
     * <p>只用于 span 展示，不参与检索计算。
     *
     * @param sq 已解析检索参数
     * @param kv 键, 值, 键, 值…；奇数个时最后一个忽略
     * @return 多行文本
     */
    private static String stepInput(SearchQuery sq, Object... kv) {
        StringBuilder text = new StringBuilder();
        text.append("query: ").append(sq.getQuery()).append('\n');
        text.append("kbId: ").append(sq.getKbId()).append('\n');
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                text.append(kv[i]).append(": ").append(kv[i + 1]).append('\n');
            }
        }
        return text.toString().trim();
    }

    /**
     * 多库检索：逐个库检索后按分数合并。
     * <p>
     * 单库失败可跳过以便其它库继续；但<strong>全部库都失败</strong>时必须把异常抛出——
     * 否则上层会把空列表当成真 NO_HIT，伪装成「未找到相关内容」。
     * <p>
     * 权限校验由调用方完成：这里只接受已经过 {@link RagAccessPolicy} 过滤的库列表。
     *
     * @param kbs         可见且可用的知识库
     * @param query       检索句
     * @param topKOverride 覆盖条数，≤0 用各库默认
     * @param overrides   知识库 id → 覆盖项 JSON，可空
     * @return 合并后命中，已截断；真零命中（至少一库成功且无结果）返回空列表
     * @throws EmbeddingException 全部库均因向量化失败
     * @throws RagStoreException  全部库均因向量库/同步失败
     * @throws RuntimeException   全部库均失败时抛出首个原因
     */
    public List<SearchResult> retrieveMulti(List<RagKnowledgeBase> kbs, String query, int topKOverride,
                                            Map<Long, String> overrides) {
        return retrieveMulti(kbs, query, topKOverride, overrides, null);
    }

    /**
     * 多库检索（带 Agent 级重排覆盖）：逐个库检索后按分数合并。
     * <p>
     * 单库失败可跳过以便其它库继续；但<strong>全部库都失败</strong>时必须把异常抛出——
     * 否则上层会把空列表当成真 NO_HIT，伪装成「未找到相关内容」。
     * <p>
     * 权限校验由调用方完成：这里只接受已经过 {@link RagAccessPolicy} 过滤的库列表。
     *
     * @param kbs                可见且可用的知识库
     * @param query              检索句
     * @param topKOverride       覆盖条数，≤0 用各库默认
     * @param overrides          知识库 id → 覆盖项 JSON，可空
     * @param agentRerankModelId Agent「知识库」Tab 配的重排实例，可空
     * @return 合并后命中，已截断；真零命中（至少一库成功且无结果）返回空列表
     * @throws EmbeddingException 全部库均因向量化失败
     * @throws RagStoreException  全部库均因向量库/同步失败
     * @throws RuntimeException   全部库均失败时抛出首个原因
     */
    public List<SearchResult> retrieveMulti(List<RagKnowledgeBase> kbs, String query, int topKOverride,
                                            Map<Long, String> overrides, Long agentRerankModelId) {
        if (kbs == null || kbs.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        // 先按描述选库：只查与问句相关的库，其它库不进召回、不进精排、不进上下文。
        // 兜底（单库、描述全空、全不匹配）都在 KbSelector 内落到「按绑定范围全搜」。
        // 注意：选库始终用**用户原问句**，不使用改写句——改写只影响召回，不改选库口径。
        KbSelector.Selection selection = KbSelector.select(query, kbs);
        List<RagKnowledgeBase> targets = selection.selected();
        if (targets.isEmpty()) {
            targets = kbs;
        }
        recordSelection(kbs.size(), selection, query, topKOverride);
        // 改写：选库之后、召回之前，一次调用最多一次。
        // 只有「入选库里至少一个开了开关」且「REWRITE 槽有可用模型」才真正出站；
        // 失败 / 未配模型 / 都没开 一律回退原问句继续检索，不让改写把检索打失败。
        RewriteOutcome rewrite = applyRewriteForMulti(targets, query, overrides, topKOverride);
        // 整次多库调用的键按各库参数组成。命中则连合并后的重排都不再出站。
        // 库不同、查询不同、覆盖项或条数不同，键就不同，不会把两次该分开的检索并成一次。
        // 没有本轮 trace 时不组键，检索仍按原顺序逐库出站。
        SameTurnRetrievalMemo memo = SameTurnRetrievalMemo.current();
        List<SameTurnRetrievalMemo.Key> callKey = null;
        if (memo != null) {
            List<SameTurnRetrievalMemo.Key> callKeys = callKeys(targets, query, rewrite, topKOverride, overrides,
                    agentRerankModelId);
            if (callKeys != null) {
                callKey = SameTurnRetrievalMemo.callKey(callKeys);
                List<SearchResult> reused = memo.findCall(callKey);
                if (reused != null) {
                    log.info("[RAG] 同轮复用多库检索 kbCount={}", callKey.size());
                    return reused;
                }
            }
        }
        List<SearchResult> all = new ArrayList<>();
        int succeeded = 0;
        RuntimeException firstFailure = null;
        for (RagKnowledgeBase kb : targets) {
            try {
                String override = overrides == null ? null : overrides.get(kb.getId());
                // 逐库决定用哪句检索：开关关的库仍用原问句，开关开的库才用改写句
                String kbQuery = queryForKb(kb, override, query, rewrite);
                // Agent 级重排延后：多库各排一次会让出站次数与库数成正比，且各库精排分不可比
                all.addAll(retrieveInternal(kb, kbQuery, topKOverride, override, agentRerankModelId, true));
                succeeded++;
            } catch (RuntimeException e) {
                // 单库失败不影响其它库：知识库只是其中一个信息源
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.warn("[RAG] 知识库检索失败，跳过 kbId={} reason={}", kb.getId(), e.getMessage());
            }
        }
        // 全部失败 → 向上抛，让 Tool 走「检索失败」文案，禁止冒充 NO_HIT
        if (succeeded == 0 && firstFailure != null) {
            throw firstFailure;
        }
        all.sort(Comparator.comparingDouble((SearchResult r) -> r.primaryScore()).reversed());
        // 合并后先按条数、再按总字符截断：条数上限来自配置（mergedTopK），
        // 未配时用各库生效 topK 之和，不再只靠 6000 字反推条数
        List<SearchResult> merged = clipMerged(all, mergedTopKOf(targets, overrides), targets);
        // Agent 级重排在合并后统一做一次：同一批候选内比分才有意义，出站次数也固定为 1 次
        if (agentRerankModelId == null || agentRerankModelId <= 0) {
            rememberCall(memo, callKey, merged, succeeded, targets.size());
            return merged;
        }
        if (rerank == null) {
            RagObsSpans.currentOrInactive().skip(RagObsSpans.RERANK, null, query.trim(),
                    topKOverride > 0 ? Integer.valueOf(topKOverride) : null, "rerank_client_missing",
                    "跳过：精排客户端不可用");
            rememberCall(memo, callKey, merged, succeeded, targets.size());
            return merged;
        }
        // 合并后为空时各库的 maybeRerank 已记 no_candidates，这里不再重复
        if (merged.isEmpty()) {
            rememberCall(memo, callKey, merged, succeeded, targets.size());
            return merged;
        }
        SearchQuery probe = new SearchQuery();
        probe.setQuery(query.trim());
        // 精排 top_n 跟实际候选数走：合并后已被条数/字符闸门收窄，多要只会白等
        probe.setTopK(Math.max(1, merged.size()));
        List<SearchResult> ranked = rerankHits(agentRerankModelId, probe, merged, null);
        rememberCall(memo, callKey, ranked, succeeded, targets.size());
        return ranked;
    }

    /**
     * 取本次多库调用的条数上限配置。
     * <p>
     * 逐库解析后取第一个显式配置的 {@code mergedTopK}：多库共用一次合并闸门，
     * 各库给出不同值时以先出现者为准（正常配置下应一致，不一致属于配置错误，
     * 这里不抛异常，避免一条坏配置让整次检索失败）。
     *
     * @param kbs       本次检索的库
     * @param overrides 各库覆盖项；可空
     * @return 合并条数上限；全部未配时为 0（由 clipMerged 用各库 topK 之和兜底）
     */
    private static int mergedTopKOf(List<RagKnowledgeBase> kbs, Map<Long, String> overrides) {
        if (kbs == null) {
            return 0;
        }
        for (RagKnowledgeBase kb : kbs) {
            if (kb == null) {
                continue;
            }
            String override = overrides == null ? null : overrides.get(kb.getId());
            SearchQuery sq = RetrievalConfigResolver.resolve(kb.getRetrievalConfig(), override, 0,
                    kb.getId(), "", kb.getTenantId());
            if (sq.getMergedTopK() > 0) {
                return sq.getMergedTopK();
            }
        }
        return 0;
    }

    /**
     * 写一条「选库」可观察记录：沿用现有 Trace 检索明细，不新做管理页。
     * <p>
     * 只在命中部分库时给出命中清单；兜底全搜时写清原因，便于事后区分
     * 「描述没配」与「配了都不相关」，而不是看起来像选库没生效。
     *
     * @param boundCount   绑定库总数
     * @param selection    选库结果
     * @param query        检索句
     * @param topKOverride 工具入参条数（仅用于 span 的 topK 列）
     */
    private static void recordSelection(int boundCount, KbSelector.Selection selection, String query,
                                        int topKOverride) {
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();
        if (!obs.isActive() || selection == null) {
            return;
        }
        Integer topK = topKOverride > 0 ? Integer.valueOf(topKOverride) : null;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("boundKbCount", boundCount);
        attrs.put("describedKbCount", selection.describedCount());
        attrs.put("outcome", selection.outcome().code());
        if (!selection.matchedIds().isEmpty()) {
            attrs.put("kbIds", selection.matchedIds().stream().map(String::valueOf)
                    .reduce((a, b) -> a + "," + b).orElse(""));
        }
        obs.step(RagObsSpans.SELECT_KB, null, query, topK, null)
                .ok(KbSelector.describe(selection), attrs);
    }

    /**
     * 为一次多库调用预计算各库的复用键。任一库没有 id 就不做整次复用，单库成功仍可按库复用。
     * <p>
     * 键里的查询文本必须是<b>该库实际使用的</b>那句：开关开的库用改写句，关的库用原问句。
     * 否则「开关改了 / 改写结果变了」时会把不同检索并成一次。
     *
     * @param kbs                本次要查的库（已经过调用方的授权过滤）
     * @param query              用户原问句
     * @param rewrite            本次改写结果
     * @param topKOverride       覆盖条数
     * @param overrides          各库覆盖项；可空
     * @param agentRerankModelId Agent 重排实例；可空
     * @return 与库列表等长的键；无法整次复用时为 null
     */
    private static List<SameTurnRetrievalMemo.Key> callKeys(List<RagKnowledgeBase> kbs, String query,
                                                            RewriteOutcome rewrite, int topKOverride,
                                                            Map<Long, String> overrides,
                                                            Long agentRerankModelId) {
        List<SameTurnRetrievalMemo.Key> keys = new ArrayList<>(kbs.size());
        for (RagKnowledgeBase kb : kbs) {
            if (kb == null || kb.getId() == null) {
                return null;
            }
            String override = overrides == null ? null : overrides.get(kb.getId());
            String kbQuery = queryForKb(kb, override, query, rewrite);
            // 与 retrieveInternal 同一套解析；多库路径的 Agent 重排固定延后
            SearchQuery sq = resolveQuery(kb, kbQuery, topKOverride, override, agentRerankModelId);
            keys.add(SameTurnRetrievalMemo.key(kb, sq, agentRerankModelId, true));
        }
        return keys;
    }

    /**
     * 每个库都成功时才记下整次结果。有库失败则不记，下一次仍会重试失败的那一库。
     *
     * @param memo      本轮表；没有 trace 时为 null
     * @param callKey   整次调用的键；无法整次复用时为 null
     * @param hits      最终命中
     * @param succeeded 成功的库数
     * @param total     本次库数
     */
    private static void rememberCall(SameTurnRetrievalMemo memo, List<SameTurnRetrievalMemo.Key> callKey,
                                     List<SearchResult> hits, int succeeded, int total) {
        if (memo != null && callKey != null && succeeded == total) {
            memo.saveCall(callKey, hits);
        }
    }

    /**
     * 向量召回为空时核对 MySQL：库内仍有切片说明集合空/错集/未重入库，属故障而非真零命中。
     *
     * @param kb 当前检索库
     * @throws RagStoreException 有切片却无向量命中
     */
    private void assertVectorStoreSynced(RagKnowledgeBase kb) {
        if (kb == null || kb.getId() == null) {
            return;
        }
        int stored = chunks.countByKb(kb.getId());
        if (stored <= 0) {
            return;
        }
        throw new RagStoreException("向量库无命中但知识库仍有 " + stored
                + " 条切片（可能集合未同步或重建后未重入库），请重建集合并重新入库后再检索");
    }

    /**
     * 按向量分阈值过滤。
     * <p>
     * threshold ≤ 0 时不过滤：0 分也可能是合法命中（例如全部文档都很短时 IP 分偏低）。
     * 若阈值把全部候选滤光，回退保留原召回（只打日志）：避免「库里有相关切片却误报 NO_HIT」。
     */
    private List<SearchResult> filterByThreshold(List<SearchResult> hits, double threshold) {
        if (hits == null || hits.isEmpty() || threshold <= 0) {
            return hits == null ? List.of() : hits;
        }
        List<SearchResult> kept = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            Double score = hit.getVectorScore();
            if (score != null && score >= threshold) {
                kept.add(hit);
            }
        }
        // 阈值过严导致全灭：保留原召回，交给后续 topK 截断，禁止伪装成零命中
        if (kept.isEmpty()) {
            log.warn("[RAG] 向量命中均低于阈值 {}，回退保留原召回 {} 条，避免误报无依据",
                    threshold, hits.size());
            return hits;
        }
        return kept;
    }

    /**
     * 用 MySQL 的完整正文覆盖 Milvus 的截断副本，并做单条截断。
     */
    private List<SearchResult> hydrateAndClip(List<SearchResult> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Long> ids = hits.stream().map(SearchResult::getChunkId).filter(java.util.Objects::nonNull).toList();
        Map<Long, RagChunk> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (RagChunk chunk : chunks.listByIds(ids)) {
                byId.put(chunk.getId(), chunk);
            }
        }
        List<SearchResult> out = new ArrayList<>(hits.size());
        int total = 0;
        for (SearchResult hit : hits) {
            RagChunk chunk = hit.getChunkId() == null ? null : byId.get(hit.getChunkId());
            if (chunk != null) {
                hit.setContent(chunk.getContent());
                hit.setChunkIndex(chunk.getChunkIndex());
                hit.setDocId(chunk.getDocId() == null ? hit.getDocId() : chunk.getDocId());
            }
            // 单条截断：超长片段会挤掉其它命中，且模型也读不完
            hit.setContent(clipText(hit.getContent(), MAX_CONTENT_CHARS));
            int len = hit.getContent() == null ? 0 : hit.getContent().length();
            // 总长截断：累计超预算就停止追加，宁可少给几条也不爆上下文
            if (total + len > MAX_TOTAL_CHARS && !out.isEmpty()) {
                break;
            }
            total += len;
            out.add(hit);
        }
        return out;
    }

    /**
     * 单篇文档限段：同一 docId 最多保留 {@code maxPerDoc} 条，超出按原序丢弃。
     * <p>
     * 为什么在回填前做：此时 Milvus 已给出 docId，不必先把正文从 MySQL 搬回来再丢；
     * 且保持原有相关度顺序，留下的是该文档得分最高的几段。
     * 判重键优先 docId；缺 docId 的旧数据退回「标题 + 来源」，避免同一篇被当成多篇。
     *
     * @param hits      候选命中，已按相关度降序
     * @param maxPerDoc 单篇上限；≤0 表示不限段
     * @return 限段后的命中，顺序不变
     */
    private static List<SearchResult> capPerDoc(List<SearchResult> hits, int maxPerDoc) {
        if (hits == null || hits.isEmpty() || maxPerDoc <= 0) {
            return hits == null ? List.of() : hits;
        }
        Map<String, Integer> used = new HashMap<>();
        List<SearchResult> out = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            String key = docKeyOf(hit);
            // 完全无法识别文档的条目（无 docId 且无标题来源）不参与限段，照常保留
            if (key == null) {
                out.add(hit);
                continue;
            }
            int seen = used.getOrDefault(key, 0);
            if (seen >= maxPerDoc) {
                continue;
            }
            used.put(key, seen + 1);
            out.add(hit);
        }
        return out;
    }

    /**
     * 限段用的文档标识：docId 最可靠；缺失时退回标题/来源，两者都空则返回 null。
     *
     * @param hit 命中
     * @return 文档键；无法识别时为 null
     */
    private static String docKeyOf(SearchResult hit) {
        if (hit == null) {
            return null;
        }
        if (hit.getDocId() != null) {
            return "id:" + hit.getDocId();
        }
        String title = hit.getTitle() == null ? "" : hit.getTitle().trim();
        String source = hit.getSource() == null ? "" : hit.getSource().trim();
        if (title.isEmpty() && source.isEmpty()) {
            return null;
        }
        return "t:" + title + "|" + source;
    }

    /**
     * 合并后的条数闸门：先按条数截，再按总字符截。
     * <p>
     * 条数上限来源（优先级从高到低）：
     * <ol>
     *   <li>配置里的 {@code mergedTopK}（KB 检索参数或 Agent 绑定覆盖项）；</li>
     *   <li>未配时用各库生效 topK 之和 —— 这是「按配置能拿到的最多条数」，
     *       比原先只靠 6000 字反推更可预期。</li>
     * </ol>
     *
     * @param hits       合并后的命中，已按分数降序
     * @param mergedTopK 配置的合并上限；≤0 表示按各库 topK 之和
     * @param kbs        本次检索的库，用于求和
     * @return 截断后的命中
     */
    private static List<SearchResult> clipMerged(List<SearchResult> hits, int mergedTopK,
                                                 List<RagKnowledgeBase> kbs) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        int limit = mergedTopK > 0 ? mergedTopK : mergedLimitOf(kbs);
        return clipTotal(limit > 0 && hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits);
    }

    /**
     * 各库生效 topK 之和，作为未配 {@code mergedTopK} 时的条数上限。
     *
     * @param kbs 本次检索的库；可空
     * @return 条数上限；无法计算时为 0（调用方退化为只按字符截）
     */
    private static int mergedLimitOf(List<RagKnowledgeBase> kbs) {
        if (kbs == null || kbs.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (RagKnowledgeBase kb : kbs) {
            if (kb == null) {
                continue;
            }
            int topK = RetrievalConfigResolver.resolve(kb.getRetrievalConfig(), null, 0,
                    kb.getId(), "", kb.getTenantId()).getTopK();
            sum += Math.max(1, topK);
        }
        return sum;
    }

    /**
     * 多库合并后的总量截断。
     */
    private static List<SearchResult> clipTotal(List<SearchResult> hits) {
        List<SearchResult> out = new ArrayList<>();
        int total = 0;
        for (SearchResult hit : hits) {
            int len = hit.getContent() == null ? 0 : hit.getContent().length();
            if (total + len > MAX_TOTAL_CHARS && !out.isEmpty()) {
                break;
            }
            total += len;
            out.add(hit);
        }
        return out;
    }

    /**
     * 单条正文截断，并明确标注已截断——让模型知道内容不完整，避免据半截内容编造结论。
     *
     * @param text 正文
     * @param max  上限
     * @return 截断后的正文
     */
    static String clipText(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        String mark = "…(已截断)";
        int room = Math.max(1, max - mark.length());
        return text.substring(0, room) + mark;
    }

    /**
     * 取可用于检索的知识库：调用方已完成权限过滤；这里只做空值防御。
     *
     * @param kbId 知识库
     * @return 知识库；不存在时为 null
     */
    public RagKnowledgeBase find(Long kbId) {
        return kbId == null ? null : kbs.findById(kbId).orElse(null);
    }

    /**
     * 判断某 KB 当前能否检索，供 Tool 在调用前给出准确错误。
     *
     * @param kb 知识库
     * @return 不可用原因；可用时返回 null
     */
    public String unusableReason(RagKnowledgeBase kb) {
        return RagAccessPolicy.rejectReason(kb);
    }

    /**
     * 供排障：某 KB 的集合统计。
     *
     * @param kb 知识库
     * @return 统计项
     */
    public Map<String, Object> stats(RagKnowledgeBase kb) {
        try {
            return provider.stats(kb);
        } catch (RagStoreException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.getMessage());
            return out;
        }
    }
}
