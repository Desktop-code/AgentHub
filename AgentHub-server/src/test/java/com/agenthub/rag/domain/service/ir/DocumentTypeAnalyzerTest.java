package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.DocumentChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-02：假 ENTRY 不计分；合集强制 HIERARCHICAL；真 FAQ 不误伤。
 */
class DocumentTypeAnalyzerTest {

    private final DocumentStructurer structurer = new DocumentStructurer();
    private final DocumentTypeAnalyzer analyzer = new DocumentTypeAnalyzer();
    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void ecommerceFaqCorpusForcedHierarchical() {
        String text = """
                # 01-退货政策

                自签收之日起 **7 个自然日**内可申请七天无理由退货。
                - 换货、仅退款等类型见《售后类型与发票》
                - 退款时效见《支付与退款说明》

                ---

                # 08-商品信息与库存

                库存以结算页为准。
                - 预售商品发货时效见《发货说明》

                ---

                # 质检与退回处置

                仓库签收后 **2 个工作日**内完成质检。

                ---

                # 平台纠纷介入

                商家逾期未处理售后可申请平台介入。
                """;
        StructuredDocument ir = structurer.structure("v2-all-电商FAQ合集.md", text, 450, 100);
        DocumentType type = analyzer.analyze(ir);
        assertEquals(DocumentType.HIERARCHICAL, type, "R1 合集应强制 HIERARCHICAL");

        List<DocumentChunk> chunks = chunker.chunk("v2-all-电商FAQ合集.md", text, 450, 100, 150);
        assertFalse(chunks.isEmpty());
        long hierarchical = chunks.stream()
                .filter(c -> DocumentType.HIERARCHICAL.name().equals(c.metaString("source_type")))
                .count();
        assertTrue(hierarchical * 1.0 / chunks.size() >= 0.8, "多数 chunk 应为 HIERARCHICAL");

        assertTrue(chunks.stream().anyMatch(c -> {
            String t = c.getSectionTitle() == null ? "" : c.getSectionTitle();
            return t.contains("退货") || t.contains("01");
        }), "R2 应出现专篇级 sectionTitle");
        assertTrue(chunks.stream().anyMatch(c -> {
            String t = c.getSectionTitle() == null ? "" : c.getSectionTitle();
            return t.contains("质检") || t.contains("商品信息") || t.contains("08");
        }), "R2 应出现其它专篇 title");

        // R3：七天无理由节与质检节不粘在同一 chunk
        boolean glued = chunks.stream().anyMatch(c -> {
            String body = c.getContent() == null ? "" : c.getContent();
            return body.contains("七天无理由") && body.contains("完成质检");
        });
        assertFalse(glued, "R3 退货与质检不应粘在同一 chunk");
    }

    @Test
    void filenameCorpusForcesHierarchicalWithoutStrongFaq() {
        String text = """
                # 篇一
                内容甲。
                ---
                # 篇二
                内容乙。
                ---
                # 篇三
                内容丙。
                """;
        StructuredDocument ir = structurer.structure("售后政策合集.md", text, 450, 100);
        assertTrue(analyzer.shouldForceHierarchical(ir));
        assertEquals(DocumentType.HIERARCHICAL, analyzer.analyze(ir));
    }

    @Test
    void trueFaqNotForcedHierarchical() {
        String text = """
                问：如何退货？
                答：签收 7 日内申请退货退款。

                问：运费谁承担？
                答：无理由退货买家承担退回运费。

                问：质检不通过怎么办？
                答：可在通知后 3 日内申诉。

                问：多久退款到账？
                答：一般 1～3 个工作日。
                """;
        StructuredDocument ir = structurer.structure("售后faq.txt", text, 450, 100);
        assertFalse(analyzer.shouldForceHierarchical(ir), "真 FAQ 不得因标记稀少被强制");
        assertEquals(DocumentType.ENTRY_BASED, analyzer.analyze(ir));
        assertTrue(analyzer.entryScore(ir) >= 2);
    }

    @Test
    void trueFaqNamedCorpusStillNotForced() {
        // 文件名含「合集」但正文是大量问/答 → 豁免强制，仍走 ENTRY_BASED
        String text = """
                问：如何退货？
                答：签收 7 日内申请。

                问：运费谁承担？
                答：买家承担退回运费。

                问：多久到账？
                答：1～3 个工作日。

                问：能否换货？
                答：支持换货，见售后页。
                """;
        StructuredDocument ir = structurer.structure("客服问答合集.md", text, 450, 100);
        assertTrue(analyzer.isStrongFaq(ir));
        assertFalse(analyzer.shouldForceHierarchical(ir));
        assertEquals(DocumentType.ENTRY_BASED, analyzer.analyze(ir));
    }

    @Test
    void herbManualStillEntryBased() {
        String text = """
                矮地茶《本草图经》
                别名 平地木
                功效应用 祛痰止咳。
                用量用法 9～15g。

                朱砂根《本草纲目》
                别名 平盆珠
                功效应用 清热解毒。
                用量用法 3～9g。
                """;
        StructuredDocument ir = structurer.structure("中药手册.txt", text, 350, 50);
        assertEquals(DocumentType.ENTRY_BASED, analyzer.analyze(ir));
        assertTrue(analyzer.countHighQualityEntries(ir) >= 2);
    }

    @Test
    void fakeBookRefWithoutFieldScoresZero() {
        // 构造：无列表符的书名行且无 FIELD（模拟残留交叉引用）
        StructuredDocument doc = new StructuredDocument();
        doc.setFileName("x.md");
        StructuredBlock e = new StructuredBlock();
        e.setBlockId("e-1");
        e.setBlockType(BlockType.ENTRY);
        e.setContent("退款时效见《支付与退款说明》");
        e.putMeta("entry_name", "退款时效见");
        doc.getBlocks().add(e);
        assertFalse(analyzer.isHighQualityEntry(e, doc));
        assertEquals(0, analyzer.entryScore(doc));
    }

    @Test
    void csvStillTabular() {
        String table = """
                药名|功效|用量
                矮地茶|祛痰止咳|9-15g
                朱砂根|清热解毒|3-9g
                """;
        StructuredDocument ir = structurer.structure("药材.csv", table, 350, 50);
        assertEquals(DocumentType.TABULAR, analyzer.analyze(ir));
    }
}
