package com.agenthub.rag.domain.service;

import com.agenthub.spi.rag.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion。所属域：rag。
 * <p>
 * {@code RRF(d) = Σ 1 / (k + rank_i(d))}，rank 从 1 起。只看名次不看分数，
 * 因此不需要把向量相似度与 BM25 分数对齐量纲——这是它能同时喂两路召回的原因。
 * 同一 chunkId 在多路命中时合并为一条，保留各路分数。
 * <p>
 * 纯静态函数、无依赖，可直接单测。
 */
public final class RrfFusion {

    /** 常用平滑常数 */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合多路已排序的召回结果。
     *
     * @param k           平滑常数，常用 60；小于 1 时按 1 处理
     * @param topN        返回条数，小于 1 时按 1 处理
     * @param rankedLists 各路命中，按相关度降序；允许为空
     * @return 融合后命中，按 RRF 分降序，长度 ≤ topN
     */
    @SafeVarargs
    public static List<SearchResult> fuse(int k, int topN, List<SearchResult>... rankedLists) {
        int smooth = Math.max(1, k);
        int limit = Math.max(1, topN);
        Map<Long, SearchResult> merged = new LinkedHashMap<>();
        Map<Long, Double> scores = new LinkedHashMap<>();
        if (rankedLists != null) {
            for (List<SearchResult> list : rankedLists) {
                if (list == null || list.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    SearchResult hit = list.get(i);
                    if (hit == null || hit.getChunkId() == null) {
                        continue;
                    }
                    Long key = hit.getChunkId();
                    // rank 从 1 起：第 1 名贡献 1/(k+1)
                    scores.merge(key, 1.0d / (smooth + i + 1), Double::sum);
                    SearchResult exist = merged.get(key);
                    if (exist == null) {
                        merged.put(key, hit);
                    } else {
                        mergeInto(exist, hit);
                    }
                }
            }
        }
        List<SearchResult> out = new ArrayList<>(merged.values());
        for (SearchResult hit : out) {
            Double score = scores.get(hit.getChunkId());
            hit.setRrfScore(score == null ? 0d : score);
            // 两路都命中的保留 vector+bm25 标记：它说明这条是被两路共同召回的，
            // 比笼统的 "rrf" 更能帮助排查召回质量；只有单路命中才标 rrf。
            if (!"vector+bm25".equals(hit.getHitBy())) {
                hit.setHitBy("rrf");
            }
        }
        out.sort(Comparator.comparing(SearchResult::getRrfScore, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * 同一切片多路命中：补齐展示字段与各路分数，不覆盖已有非空值。
     * <p>
     * 两路都命中时 hitBy 记为 {@code vector+bm25}，便于排障时看出该条是被两路共同召回的。
     */
    private static void mergeInto(SearchResult target, SearchResult other) {
        if (target.getContent() == null) {
            target.setContent(other.getContent());
        }
        if (target.getTitle() == null) {
            target.setTitle(other.getTitle());
        }
        if (target.getSource() == null) {
            target.setSource(other.getSource());
        }
        if (target.getChunkIndex() == null) {
            target.setChunkIndex(other.getChunkIndex());
        }
        if (target.getDocId() == null) {
            target.setDocId(other.getDocId());
        }
        if (target.getKbId() == null) {
            target.setKbId(other.getKbId());
        }
        if (other.getVectorScore() != null) {
            target.setVectorScore(other.getVectorScore());
        }
        if (other.getBm25Score() != null) {
            target.setBm25Score(other.getBm25Score());
        }
        if (other.getRerankScore() != null) {
            target.setRerankScore(other.getRerankScore());
        }
        if (target.getVectorScore() != null && target.getBm25Score() != null) {
            target.setHitBy("vector+bm25");
        }
    }
}
