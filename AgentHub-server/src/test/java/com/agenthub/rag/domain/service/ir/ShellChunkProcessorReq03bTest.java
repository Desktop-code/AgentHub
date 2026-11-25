package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-03b：空壳合并不得跨专篇；表分隔保留；同专篇仍可合并。所属域：rag。
 * <p>
 * 夹具正文有效长度须 &gt; 空壳阈值 60，否则末片空壳会被整段丢弃。
 */
class ShellChunkProcessorReq03bTest {

    /** 统一补足长度，避免踩空壳阈值 */
    private static final String PAD = "补充说明用于超过空壳过滤阈值，保证本段可作为有效检索正文保留。";

    private static final String LONG_A =
            "工单办结后七十二小时内可回访客户确认满意度，勿与售后审核时钟以及纠纷介入时限混淆。" + PAD;
    private static final String LONG_B =
            "价保是指商品降价后的差价补偿，与七天无理由退货不同。买家在价保期内可申请补差无需退货。" + PAD;

    @Test
    void b5_crossArticleShellNotMergedIntoNext() {
        DocumentChunk shortA = chunk(
                "工单短句。",
                "勿与售后审核时钟混淆",
                Map.of("article_title", "平台工单", "header_level", 2, "header_path", "勿与售后审核时钟混淆"));
        DocumentChunk nextB = chunk(
                LONG_B,
                "定义区分",
                Map.of("article_title", "价保与保价", "header_level", 2, "header_path", "定义区分"));

        List<DocumentChunk> out = ShellChunkProcessor.collapse(List.of(shortA, nextB), 60, 60);
        assertEquals(1, out.size(), "跨专篇短片应丢弃空壳，不应并入价保篇");
        assertEquals("定义区分", out.get(0).getSectionTitle());
        assertFalse(out.get(0).getContent().contains("工单短句"),
                "B1/B5 价保正文不得以工单句开头");
        assertTrue(out.get(0).getContent().contains("价保"));
    }

    @Test
    void b5_sameArticleShellMergedKeepNextTitle() {
        DocumentChunk shortA = chunk(
                "详见下列说明。",
                "小节甲",
                Map.of("article_title", "01-退货政策", "header_level", 2, "header_path", "小节甲"));
        DocumentChunk nextB = chunk(
                "自签收之日起七个自然日内，商品不影响二次销售的，可申请七天无理由退货。" + PAD,
                "小节乙",
                Map.of("article_title", "01-退货政策", "header_level", 2, "header_path", "小节乙"));

        List<DocumentChunk> out = ShellChunkProcessor.collapse(List.of(shortA, nextB), 60, 60);
        assertEquals(1, out.size(), "同专篇应合并");
        assertEquals("小节乙", out.get(0).getSectionTitle(), "保留下一片 title");
        assertTrue(out.get(0).getContent().contains("详见下列说明"));
        assertTrue(out.get(0).getContent().contains("七天无理由"));
    }

    @Test
    void b5_higherHeaderLevelForbidsMerge() {
        DocumentChunk shortA = chunk(
                "工单办结后可回访。",
                "勿与售后审核时钟混淆",
                Map.of("article_title", "平台工单", "header_level", 3));
        DocumentChunk nextH1 = chunk(
                LONG_B,
                "价保与保价",
                Map.of("article_title", "价保与保价", "header_level", 1));

        assertFalse(ShellChunkProcessor.canMerge(shortA, nextH1));
        List<DocumentChunk> out = ShellChunkProcessor.collapse(List.of(shortA, nextH1), 60, 60);
        assertTrue(out.stream().noneMatch(c ->
                "价保与保价".equals(c.getSectionTitle()) && c.getContent().contains("工单办结")));
    }

    @Test
    void b4_tableSeparatorNotTreatedAsHr() {
        assertFalse(ShellChunkProcessor.isHorizontalRuleLine("|------|"));
        assertFalse(ShellChunkProcessor.isHorizontalRuleLine("| --- | --- |"));
        assertTrue(ShellChunkProcessor.isHorizontalRuleLine("-------"));
        assertTrue(ShellChunkProcessor.isHorizontalRuleLine("---"));

        String table = """
                | 场景 | 说明 |
                |------|------|
                | 拒收 | 见物流 |
                """;
        String stripped = ShellChunkProcessor.stripTrailingHorizontalRules(table.trim());
        assertTrue(stripped.contains("|------|------|"), "表分隔行不得被当 HR 剥掉");
    }

    @Test
    void b3_sameArticleShortListSectionCanMerge() {
        DocumentChunk a = chunk(
                "补充一句作为同专篇前缀说明。",
                "运费说明",
                Map.of("article_title", "02-运费与包邮", "header_level", 2));
        DocumentChunk b = chunk(
                "- 满 99 包邮\n- 偏远地区除外\n未满则收统一运费八元，生鲜冷链按商品页计费。" + PAD,
                "包邮门槛",
                Map.of("article_title", "02-运费与包邮", "header_level", 2));
        List<DocumentChunk> out = ShellChunkProcessor.collapse(List.of(a, b), 60, 60);
        assertEquals(1, out.size());
        assertEquals("包邮门槛", out.get(0).getSectionTitle());
        assertTrue(out.get(0).getContent().contains("满 99"));
    }

    @Test
    void endToEnd_ticketThenPriceProtectNoGlue() {
        String text = """
                # 平台工单

                ## 勿与售后审核时钟混淆

                %s

                ---

                # 价保与保价

                ## 定义区分

                %s
                """.formatted(LONG_A, LONG_B);
        StructureAwareChunker chunker = StructureAwareChunker.createDefault();
        List<DocumentChunk> chunks = chunker.chunk("v2-all-电商FAQ合集.md", text, 450, 100, 150);
        assertFalse(chunks.isEmpty(), "端到端应产出切片");
        assertTrue(chunks.stream().noneMatch(c -> {
            String title = c.getSectionTitle() == null ? "" : c.getSectionTitle();
            String body = c.getContent() == null ? "" : c.getContent();
            return title.contains("定义区分") && body.contains("工单办结");
        }), "B1/B2 价保定义区分不得带工单前缀");
        assertTrue(chunks.stream().anyMatch(c ->
                c.getContent() != null && c.getContent().contains("价保是指")));
    }

    private static DocumentChunk chunk(String content, String title, Map<String, ?> meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (meta != null) {
            meta.forEach(m::put);
        }
        return new DocumentChunk(content, 0, title, m);
    }
}
