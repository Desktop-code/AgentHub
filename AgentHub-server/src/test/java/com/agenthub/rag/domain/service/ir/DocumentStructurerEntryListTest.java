package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.DocumentChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-01：LIST 优先 + ENTRY_REF 收紧（电商 FAQ 交叉引用列表不得升格为 ENTRY）。
 */
class DocumentStructurerEntryListTest {

    private final DocumentStructurer structurer = new DocumentStructurer();
    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void listCrossRefBecomesListNotEntry() {
        String text = """
                # 七天无理由退货

                自签收之日起 7 个自然日内可申请。

                - 被拒后可修改资料重新提交；仍不服走《平台纠纷介入》
                - 换货、仅退款等类型见《售后类型与发票》
                - 退款时效见《支付与退款说明》
                """;
        StructuredDocument doc = structurer.structure("v2-all-电商FAQ合集.md", text, 450, 100);
        long entryFromSee = doc.getBlocks().stream()
                .filter(b -> b.getBlockType() == BlockType.ENTRY)
                .filter(b -> {
                    String name = b.metaString("entry_name");
                    String content = b.getContent() == null ? "" : b.getContent();
                    return DocumentStructurer.endsWithSeeRef(name)
                            || DocumentStructurer.startsWithListMarker(name)
                            || DocumentStructurer.startsWithListMarker(content);
                })
                .count();
        assertEquals(0, entryFromSee, "见《》列表行不得升格为 ENTRY");
        long listHits = doc.getBlocks().stream()
                .filter(b -> b.getBlockType() == BlockType.LIST)
                .filter(b -> b.getContent() != null && b.getContent().contains("见《"))
                .count();
        // 相邻 LIST 行可能被 IR 合并为同一 LIST 块，条数 ≥2 且无假 ENTRY 即可
        assertTrue(listHits >= 2, "交叉引用应识别为 LIST，实际 listHits=" + listHits);
    }

    @Test
    void plainSeeRefLineNotEntry() {
        String text = """
                # 质检说明

                退款时效见《支付与退款说明》

                仓库签收后 2 个工作日内完成质检。
                """;
        StructuredDocument doc = structurer.structure("faq.md", text, 450, 100);
        assertTrue(doc.getBlocks().stream().noneMatch(b -> b.getBlockType() == BlockType.ENTRY),
                "「…见《》」截断行不得成 ENTRY，应落入段落");
        assertTrue(doc.getBlocks().stream()
                .anyMatch(b -> b.getBlockType() == BlockType.PARAGRAPH
                        && b.getContent() != null
                        && b.getContent().contains("退款时效见")));
    }

    @Test
    void tableCellSeeRefNotEntry() {
        String text = """
                # 对照表

                | 场景 | 说明 |
                | --- | --- |
                | 拒收 | 无理由拒收见《物流说明》 |
                | 换货 | 换货类型见《售后类型与发票》 |
                """;
        StructuredDocument doc = structurer.structure("faq.md", text, 450, 100);
        assertTrue(doc.getBlocks().stream().anyMatch(b -> b.getBlockType() == BlockType.TABLE));
        assertTrue(doc.getBlocks().stream().noneMatch(b -> b.getBlockType() == BlockType.ENTRY),
                "表内「见《》」不得拆成 ENTRY");
    }

    @Test
    void herbEntryRefStillAccepted() {
        String text = """
                人参《本草纲目》
                功效应用：大补元气
                用量用法：3～9g
                """;
        StructuredDocument doc = structurer.structure("中药手册.txt", text, 450, 100);
        StructuredBlock entry = doc.getBlocks().stream()
                .filter(b -> b.getBlockType() == BlockType.ENTRY)
                .findFirst()
                .orElseThrow();
        assertEquals("人参", entry.metaString("entry_name"));
        assertEquals("本草纲目", entry.metaString("source_ref"));
    }

    @Test
    void chunkSectionTitlesPassA1A2() {
        String text = """
                # 七天无理由退货

                自签收之日起 **7 个自然日**内，商品不影响二次销售的，可申请七天无理由退货。

                - 个人定制商品、鲜活易腐、数字化商品（激活后）
                - 被拒后可修改资料重新提交；仍不服走《平台纠纷介入》
                - 换货、仅退款等类型见《售后类型与发票》
                - 退款时效见《支付与退款说明》
                - 无理由拒收 ≠ 物流拦截；拒收见《物流说明》

                # 运费与包邮

                - 单笔实付满 **99 元**包邮
                - 金卡会员包邮门槛见《会员权益》
                """;
        List<DocumentChunk> chunks = chunker.chunk("v2-all-电商FAQ合集.md", text, 450, 100, 150);
        assertFalse(chunks.isEmpty());
        long titled = chunks.stream()
                .map(DocumentChunk::getSectionTitle)
                .filter(t -> t != null && !t.isBlank())
                .count();
        long bulletTitle = chunks.stream()
                .map(DocumentChunk::getSectionTitle)
                .filter(t -> t != null && DocumentStructurer.startsWithListMarker(t))
                .count();
        long seeTitle = chunks.stream()
                .map(DocumentChunk::getSectionTitle)
                .filter(t -> t != null && DocumentStructurer.endsWithSeeRef(t.trim()))
                .count();
        double bulletRatio = titled == 0 ? 0 : 100.0 * bulletTitle / titled;
        double seeRatio = titled == 0 ? 0 : 100.0 * seeTitle / titled;
        assertTrue(bulletRatio < 5.0, "A1 sectionTitle 以列表符开头比例应 <5%，实际=" + bulletRatio + "%");
        assertTrue(seeRatio < 5.0, "A2 sectionTitle 以见/详见结尾比例应 <5%，实际=" + seeRatio + "%");
    }

    @Test
    void acceptEntryCandidateGates() {
        assertFalse(DocumentStructurer.acceptEntryCandidate("- 退款时效见《支付》", "退款时效见"));
        assertFalse(DocumentStructurer.acceptEntryCandidate("退款时效见《支付与退款说明》", "退款时效见"));
        assertFalse(DocumentStructurer.acceptEntryCandidate("| 拒收 | 见《物流》 |", "拒收"));
        assertTrue(DocumentStructurer.acceptEntryCandidate("人参《本草纲目》", "人参"));
        // 即使 resolve 成书名，前缀「…见」仍应在扫描层拒绝（见 plainSeeRefLineNotEntry）
        assertTrue(DocumentStructurer.endsWithSeeRef("退款时效见"));
    }

    @Test
    void resolveEntryNamePrefersBookWhenPrefixEndsWithSee() {
        assertEquals("支付与退款说明",
                DocumentStructurer.resolveEntryName("退款时效见", "支付与退款说明", "退款时效见《支付与退款说明》"));
        assertEquals("人参",
                DocumentStructurer.resolveEntryName("人参", "本草纲目", "人参《本草纲目》"));
    }
}
