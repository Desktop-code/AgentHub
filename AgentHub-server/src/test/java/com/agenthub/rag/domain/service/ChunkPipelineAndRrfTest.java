package com.agenthub.rag.domain.service;

import com.agenthub.spi.rag.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片管线与 RRF 融合。所属域：rag 的 domain。
 * <p>
 * 切片已切到 IR 五段（非 Recursive 一切了之）；此处保留基础回归 + RRF。
 * 层级路由 / 列表不假 ENTRY / 不串节见 {@code ir.*Test}。
 */
class ChunkPipelineAndRrfTest {

    // ---------------- 切片 ----------------

    /**
     * 长叙述文本必须切成多片，且每片不超过设定上限（oversize 兜底）。
     */
    @Test
    void splitsLongTextWithinLimit() {
        // 无稳定 # 标题 → NARRATIVE；超长再走 oversize
        String text = "这是一段用于撑满切片长度的售后说明文字，包含退货运费与质检要点。".repeat(40);
        List<String> chunks = ChunkPipeline.chunk("note.txt", text, 200, 40, 20);

        assertTrue(chunks.size() > 1, "长文本应切成多片");
        for (String c : chunks) {
            assertTrue(c.length() <= 200 + 60, "单片长度应受控，实际 " + c.length());
        }
    }

    /**
     * 目录行与纯页码行应被清洗掉，不该进入切片。
     */
    @Test
    void removesTocLinesAndPageNumbers() {
        String raw = """
                第一章 总则 ......... 12
                这是正文第一段，包含有效信息，需要足够长度以免被低质片过滤掉。

                42

                这是正文第二段，同样包含有效信息，需要足够长度以免被低质片过滤掉。
                """;
        List<String> chunks = ChunkPipeline.chunk("book.txt", raw, 500, 0, 10);

        String joined = String.join("\n", chunks);
        assertTrue(joined.contains("这是正文第一段"));
        assertTrue(joined.contains("这是正文第二段"));
        // 目录行（标题 + 点线 + 页码）必须被整行清掉
        assertFalse(joined.contains("........."), "目录行应被清掉，实际：" + joined);
        // 纯页码行不应单独成片
        assertTrue(chunks.stream().noneMatch(c -> c.trim().equals("42")));
    }

    /**
     * 短叙述在大 minChunk 下应合并，不碎成多片；全文须超过空壳阈值。
     */
    @Test
    void mergesTinyChunks() {
        String raw = "段一包含足够说明文字用于检索。\n\n段二包含足够说明文字用于检索。\n\n"
                + "段三包含足够说明文字用于检索。\n\n段四包含足够说明文字用于检索。";
        List<String> chunks = ChunkPipeline.chunk("note.txt", raw, 500, 0, 200);

        assertEquals(1, chunks.size());
    }

    /**
     * 空正文返回空列表，不产生空切片。
     */
    @Test
    void emptyTextYieldsNoChunks() {
        assertTrue(ChunkPipeline.chunk("", 450, 100, 150).isEmpty());
        assertTrue(ChunkPipeline.chunk("   \n\n  ", 450, 100, 150).isEmpty());
    }

    /**
     * token 估算：中文按字计（1 字 ≈ 1 token），英文按 4 字符 1 token。
     */
    @Test
    void estimatesTokens() {
        assertEquals(0, ChunkPipeline.estimateTokens(null));
        // 「退货政策说明」6 个汉字 → 6
        assertEquals(6, ChunkPipeline.estimateTokens("退货政策说明"));
        // 8 个英文字符 ≈ 2 token
        assertTrue(ChunkPipeline.estimateTokens("abcdefgh") <= 3);
        // 中英混排：2 个汉字 + 9 个非汉字字符（空格+英文）→ 2 + 9/4 = 4
        assertEquals(4, ChunkPipeline.estimateTokens("退货 abcdefgh"));
    }

    /**
     * 哈希稳定：同文本同值，不同文本不同值。
     */
    @Test
    void hashIsStable() {
        assertEquals(ChunkPipeline.hash("abc"), ChunkPipeline.hash("abc"));
        assertTrue(!ChunkPipeline.hash("abc").equals(ChunkPipeline.hash("abd")));
        assertEquals(64, ChunkPipeline.hash("abc").length(), "SHA-256 十六进制应为 64 位");
    }

    /**
     * 全文只有目录标题：清洗后为空，不产生切片。
     */
    @Test
    void tocOnlyTextYieldsNoChunks() {
        assertTrue(ChunkPipeline.chunk("目录\n", 450, 100, 50).isEmpty());
    }

    // ---------------- RRF ----------------

    /**
     * 两路同 key：合并为一条，且两路分数都保留。
     */
    @Test
    void rrfMergesSameKeyAcrossPaths() {
        List<SearchResult> vector = List.of(hit(1L, 0.9d, null));
        List<SearchResult> bm25 = List.of(hit(1L, null, 3.2d));

        List<SearchResult> fused = RrfFusion.fuse(60, 10, vector, bm25);

        assertEquals(1, fused.size());
        SearchResult only = fused.get(0);
        assertNotNull(only.getRrfScore());
        assertEquals(0.9d, only.getVectorScore());
        assertEquals(3.2d, only.getBm25Score());
        assertEquals("vector+bm25", only.getHitBy());
    }

    /**
     * 两路不同 key：都保留，按 RRF 分排序（两路第 1 名同分，顺序不敏感，只验证都在）。
     */
    @Test
    void rrfKeepsDistinctKeys() {
        List<SearchResult> vector = List.of(hit(1L, 0.9d, null), hit(2L, 0.8d, null));
        List<SearchResult> bm25 = List.of(hit(3L, null, 5.0d));

        List<SearchResult> fused = RrfFusion.fuse(60, 10, vector, bm25);

        assertEquals(3, fused.size());
    }

    /**
     * 空输入：返回空列表，不抛错。
     */
    @Test
    void rrfHandlesEmptyInput() {
        assertTrue(RrfFusion.fuse(60, 10, List.of(), List.of()).isEmpty());
        assertTrue(RrfFusion.fuse(60, 10).isEmpty());
    }

    /**
     * topN 截断生效。
     */
    @Test
    void rrfTruncatesToTopN() {
        List<SearchResult> a = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            a.add(hit((long) i, 1.0d - i * 0.01, null));
        }

        assertEquals(3, RrfFusion.fuse(60, 3, a).size());
    }

    /**
     * 缺 chunkId 的命中被跳过：没有主键无法去重。
     */
    @Test
    void rrfSkipsHitsWithoutKey() {
        SearchResult broken = hit(null, 0.9d, null);
        assertTrue(RrfFusion.fuse(60, 10, List.of(broken)).isEmpty());
    }

    /**
     * primaryScore 优先级：rerank > rrf > vector/bm25 取大。
     */
    @Test
    void primaryScorePrefersRerankThenRrf() {
        SearchResult hit = hit(1L, 0.3d, 9.9d);
        hit.setRrfScore(0.016d);
        assertEquals(0.016d, hit.primaryScore());

        hit.setRerankScore(0.77d);
        assertEquals(0.77d, hit.primaryScore());
    }

    /**
     * 全部缺失时 primaryScore 为 0 而不是抛 NPE。
     */
    @Test
    void primaryScoreDefaultsToZero() {
        SearchResult hit = new SearchResult();
        assertEquals(0d, hit.primaryScore());
        assertNull(hit.getVectorScore());
    }

    // ---------------- 检索参数收紧 ----------------

    /**
     * topK 硬上限：超过 20 被夹到 20，避免把上下文挤爆。
     */
    @Test
    void retrievalTopKIsClamped() {
        var sq = RetrievalConfigResolver.resolve("{\"topK\":999}", null, 0, 1L, "q", 0L);
        assertEquals(RetrievalConfigResolver.MAX_TOP_K, sq.getTopK());
    }

    /**
     * Tool 入参覆盖 KB 默认。
     */
    @Test
    void toolTopKOverridesKbDefault() {
        var sq = RetrievalConfigResolver.resolve("{\"topK\":5}", null, 3, 1L, "q", 0L);
        assertEquals(3, sq.getTopK());
    }

    /**
     * Agent 覆盖项优先于 KB 配置。
     */
    @Test
    void agentOverridesBeatKbConfig() {
        var sq = RetrievalConfigResolver.resolve("{\"topK\":5}", "{\"topK\":2}", 0, 1L, "q", 0L);
        assertEquals(2, sq.getTopK());
    }

    /**
     * 非法 JSON：回落默认，不抛错。
     */
    @Test
    void badJsonFallsBackToDefaults() {
        var sq = RetrievalConfigResolver.resolve("{oops", null, 0, 1L, "q", 0L);
        assertEquals(RetrievalConfigResolver.DEFAULT_TOP_K, sq.getTopK());
    }

    /**
     * 召回条数不得小于最终条数，否则融合后凑不满。
     */
    @Test
    void recallTopKNeverBelowFinalTopK() {
        var sq = RetrievalConfigResolver.resolve("{\"vectorTopK\":1}", null, 5, 1L, "q", 0L);
        assertTrue(sq.getVectorTopK() >= sq.getTopK());
    }

    private SearchResult hit(Long chunkId, Double vectorScore, Double bm25Score) {
        SearchResult hit = new SearchResult();
        hit.setChunkId(chunkId);
        hit.setContent("内容" + chunkId);
        if (vectorScore != null) {
            hit.setVectorScore(vectorScore);
            hit.setHitBy("vector");
        }
        if (bm25Score != null) {
            hit.setBm25Score(bm25Score);
            hit.setHitBy("bm25");
        }
        return hit;
    }
}
