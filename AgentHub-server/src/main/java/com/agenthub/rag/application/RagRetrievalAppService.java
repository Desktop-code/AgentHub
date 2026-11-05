package com.agenthub.rag.application;

import com.agenthub.rag.domain.model.RagChunk;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.RagChunkRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
import com.agenthub.rag.domain.service.RagAccessPolicy;
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
 * query → Embedding(按 KB 的模型) → 向量召回 ∥ BM25 召回（能力支持时）→ RRF 融合 → 截断 → MySQL 回填全文。
 * <p>
 * 关键约定：
 * <ul>
 *   <li><b>向量必做</b>；BM25 与 RRF 只在集合具备 DENSE_BM25 能力时才走，DENSE_ONLY 自动退化为纯向量，不报错；</li>
 *   <li><b>Rerank / 改写默认关</b>；Rerank 开启且配了模型时走 {@link RerankClient}（同连通测矩阵，DASHSCOPE text-rerank）；</li>
 *   <li><b>无权限明确报错</b>：不可见 / 未初始化 / 已停用一律抛业务异常，**绝不返回空列表冒充「没查到」**；</li>
 *   <li><b>防爆</b>：条数上限、单条字符上限、总字符上限三层截断，避免把上下文挤爆；</li>
 *   <li><b>可观测</b>：工具调用内按检索链路写 obs 子步（{@link RagObsSpans}）；无 trace 时全部空操作，
 *       不改变这里的任何算法与返回。</li>
 * </ul>
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
     * @param kbs        知识库
     * @param chunks     切片（回填完整正文）
     * @param provider   向量库
     * @param embeddings 向量化（测试用 mock 替身注入）
     * @param rerank     可选精排；与连通测共用协议×用途矩阵
     */
    public RagRetrievalAppService(RagKnowledgeBaseRepository kbs, RagChunkRepository chunks,
                                  RagProvider provider, EmbeddingClient embeddings, RerankClient rerank) {
        this.kbs = kbs;
        this.chunks = chunks;
        this.provider = provider;
        this.embeddings = embeddings;
        this.rerank = rerank;
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
        emitRewriteSkip(kb, query, overridesJson, topKOverride);
        return retrieveInternal(kb, query, topKOverride, overridesJson, agentRerankModelId, false);
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
        SearchQuery sq = RetrievalConfigResolver.resolve(kb.getRetrievalConfig(), overridesJson,
                topKOverride, kb.getId(), query.trim(), kb.getTenantId());
        // Agent 侧配了重排就整体接管：开关与模型一起以 Agent 为准，避免出现「开了没模型 / 有模型没开」的半截状态
        if (agentRerankModelId != null && agentRerankModelId > 0) {
            sq.setRerankEnabled(true);
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

        // 4) 截断到 topK（精排前先收窄，避免 documents 过大）
        List<SearchResult> top = fused.size() > sq.getTopK()
                ? new ArrayList<>(fused.subList(0, sq.getTopK())) : fused;

        // 5) 用 MySQL 回填完整正文（Milvus 里只是截断副本）
        List<SearchResult> hydrated = hydrateAndClip(top);

        // 6) 可选 Rerank：失败只记日志并保留原序，不让整次检索失败
        //    Agent 级重排在多库场景下延后到合并之后统一做一次，避免 N 次出站
        return maybeRerank(kb, sq, hydrated, agentRerankModelId, deferAgentRerank);
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
     * 写 run 级「查询改写」skip 子步：多库只写一条。
     *
     * @param kbs          本轮检索库
     * @param query        原问
     * @param overrides    各库覆盖项；可空
     * @param topKOverride 工具入参 topK
     */
    private void emitRewriteSkip(List<RagKnowledgeBase> kbs, String query,
                                 Map<Long, String> overrides, int topKOverride) {
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();
        if (!obs.isActive() || kbs == null || kbs.isEmpty() || query == null || query.isBlank()) {
            return;
        }
        boolean requested = false;
        for (RagKnowledgeBase kb : kbs) {
            String override = overrides == null ? null : overrides.get(kb.getId());
            if (RagObsSpans.rewriteRequested(kb.getRetrievalConfig(), override)) {
                requested = true;
                break;
            }
        }
        writeRewriteSkip(obs, query, topKOverride, requested);
    }

    /**
     * 单库入口的改写 skip；与多库共用同一套原因口径。
     *
     * @param kb           知识库
     * @param query        原问
     * @param overridesJson 覆盖项；可空
     * @param topKOverride 工具入参 topK
     */
    private void emitRewriteSkip(RagKnowledgeBase kb, String query, String overridesJson, int topKOverride) {
        RagObsSpans.Session obs = RagObsSpans.currentOrInactive();
        if (!obs.isActive() || kb == null || query == null || query.isBlank()) {
            return;
        }
        writeRewriteSkip(obs, query, topKOverride,
                RagObsSpans.rewriteRequested(kb.getRetrievalConfig(), overridesJson));
    }

    /**
     * 本期运行时未接入查询改写：开关开着记「已开但未执行」，关着记「未开启」。
     */
    private void writeRewriteSkip(RagObsSpans.Session obs, String query, int topKOverride, boolean requested) {
        obs.skipOnce(RagObsSpans.REWRITE, null, query == null ? null : query.trim(),
                topKOverride > 0 ? Integer.valueOf(topKOverride) : null,
                requested ? "rewrite_not_executed" : "rewrite_disabled",
                requested ? "跳过：改写开关已开，但运行时未执行改写" : "跳过：改写未开启");
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
        // run 级改写步骤：运行时不执行改写，按各库开关聚合出唯一的 skip 原因
        emitRewriteSkip(kbs, query, overrides, topKOverride);
        List<SearchResult> all = new ArrayList<>();
        int succeeded = 0;
        RuntimeException firstFailure = null;
        for (RagKnowledgeBase kb : kbs) {
            try {
                String override = overrides == null ? null : overrides.get(kb.getId());
                // Agent 级重排延后：多库各排一次会让出站次数与库数成正比，且各库精排分不可比
                all.addAll(retrieveInternal(kb, query, topKOverride, override, agentRerankModelId, true));
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
        // 合并后按总量截断：避免 N 个库把上下文挤爆，也把精排输入控制在一次出站内
        List<SearchResult> merged = clipTotal(all);
        // Agent 级重排在合并后统一做一次：同一批候选内比分才有意义，出站次数也固定为 1 次
        if (agentRerankModelId == null || agentRerankModelId <= 0) {
            return merged;
        }
        if (rerank == null) {
            RagObsSpans.currentOrInactive().skip(RagObsSpans.RERANK, null, query.trim(),
                    topKOverride > 0 ? Integer.valueOf(topKOverride) : null, "rerank_client_missing",
                    "跳过：精排客户端不可用");
            return merged;
        }
        // 合并后为空时各库的 maybeRerank 已记 no_candidates，这里不再重复
        if (merged.isEmpty()) {
            return merged;
        }
        SearchQuery probe = new SearchQuery();
        probe.setQuery(query.trim());
        probe.setTopK(Math.max(1, topKOverride > 0 ? topKOverride : RetrievalConfigResolver.DEFAULT_TOP_K));
        return rerankHits(agentRerankModelId, probe, merged, null);
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
     * 多库合并后的总量截断。
     */
    private List<SearchResult> clipTotal(List<SearchResult> hits) {
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
