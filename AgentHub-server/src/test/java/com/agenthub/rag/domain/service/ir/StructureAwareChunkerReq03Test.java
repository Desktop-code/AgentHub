package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-03：splitOversize part 元数据、空壳片、末尾 --- 剥离。所属域：rag。
 */
class StructureAwareChunkerReq03Test {

    private final StructureAwareChunker chunker = StructureAwareChunker.createDefault();

    private static final String PAD = "补充说明用于超过空壳过滤阈值，保证本段可作为有效检索正文保留。";

    @Test
    void oversizePartsCarryPartIndex() {
        String longBody = ("这是一段用于撑满切片长度的售后说明文字，包含退货、运费与质检要点，并补充买家须知与仓库处理时效。").repeat(30);
        String text = "# 01-退货政策\n\n" + longBody + "\n";
        List<DocumentChunk> chunks = chunker.chunk("手册.md", text, 450, 100, 150);
        assertFalse(chunks.isEmpty());
        long withPart = chunks.stream()
                .filter(c -> c.getMetadata().get("partIndex") != null || c.getMetadata().get("part_index") != null)
                .count();
        assertTrue(withPart >= 1 || chunks.size() >= 2,
                "O1 超长应再切或产出多片，实际 chunks=" + summarize(chunks));
        assertTrue(chunks.stream().allMatch(c -> c.getContent().length() <= 450),
                "O4 单片一般 ≤450");
        long titled = chunks.stream().filter(c -> c.getSectionTitle() != null && !c.getSectionTitle().isBlank()).count();
        assertTrue(titled * 1.0 / chunks.size() >= 0.95, "O4 title 非空比例 ≥95%");
    }

    @Test
    void decorativeBoldAndTrailingHrNotStandalone() {
        String text = """
                # 七天无理由退货

                **适用范围**

                自签收之日起七个自然日内可申请退货，吊牌未剪、包装完好视为可二次销售。%s

                ---

                # 质检与退回处置

                **质检时效**

                仓库签收后两个工作日内完成质检，大件可延长至五个工作日并短信通知买家。%s
                """.formatted(PAD, PAD);
        List<DocumentChunk> chunks = chunker.chunk("售后合集.md", text, 450, 100, 150);
        assertFalse(chunks.isEmpty(), "应产出切片，实际为空");
        assertTrue(chunks.stream().noneMatch(c -> {
            String body = c.getContent() == null ? "" : c.getContent().trim();
            return body.equals("**适用范围**") || body.equals("**质检时效**");
        }), "O2 不应出现仅粗体副标题的 chunk");
        assertTrue(chunks.stream().noneMatch(c -> {
            String body = c.getContent() == null ? "" : c.getContent();
            return body.trim().endsWith("---") || body.matches("(?s).*\\n---\\s*$");
        }), "O3 content 不以单独 --- 结尾");
    }

    @Test
    void stripTrailingHrUnit() {
        assertTrue(ShellChunkProcessor.stripTrailingHorizontalRules("正文\n---").equals("正文"));
        assertTrue(ShellChunkProcessor.stripTrailingHorizontalRules("正文\n\n***\n").equals("正文"));
        assertTrue(ShellChunkProcessor.isDecorativeOnly("**副标题**"));
        // 用 ASCII 撑长度，避免本机源文件编码差异干扰断言
        String longEnough = "shell-check-" + "x".repeat(80);
        assertFalse(ShellChunkProcessor.isShell(longEnough, 60));
    }

    @Test
    void splitOversizeDirectApiSetsPartMeta() {
        String body = ("段落内容用于撑满长度，包含退货运费质检等说明。").repeat(40);
        DocumentChunk parent = new DocumentChunk(body, 0, "专篇A", Map.of("header_path", "专篇A"));
        List<DocumentChunk> parts = StructureAwareChunker.splitOversize(List.of(parent), 450, 100);
        assertTrue(parts.size() >= 2, "应拆出不少于 2 片，实际=" + parts.size());
        assertTrue(parts.stream().allMatch(p ->
                p.getMetadata().get("partIndex") != null || p.getMetadata().get("part_index") != null));
        assertTrue(parts.stream().allMatch(p -> {
            Object total = p.getMetadata().get("partTotal");
            if (total == null) {
                total = p.getMetadata().get("part_total");
            }
            return total != null && ((Number) total).intValue() == parts.size();
        }));
        assertTrue(parts.stream().allMatch(p -> p.getSectionTitle() != null && p.getSectionTitle().contains("(")));
    }

    private static String summarize(List<DocumentChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (DocumentChunk c : chunks) {
            Object part = c.getMetadata().get("partIndex");
            if (part == null) {
                part = c.getMetadata().get("part_index");
            }
            sb.append('[').append(c.getSectionTitle()).append('|')
                    .append(c.getContent() == null ? 0 : c.getContent().length())
                    .append('|').append(part).append("] ");
        }
        return sb.toString();
    }
}
