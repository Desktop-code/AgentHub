package com.agenthub.rag.domain.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片 → 解析文本偏移映射。所属域：rag 的 domain。
 * <p>
 * 约束：偏移必须覆盖该片完整正文（标题 + 多段 + 引用块），不能只落标题行；
 * 定位不到时不写偏移，禁止用标题长度伪造一个范围。
 */
class ChunkPresentationOffsetTest {

    /** 复现「标题 + 段落 + 引用块」再接下一个 H3 的节 */
    private static final String SECTION_TEXT = """
            # 第02篇 · 02-物流与配送

            ## 配送与改址

            ### 修改地址（摘要）
            订单「待发货」状态可在线修改 **一次**收货地址；已发货只能联系快递拦截，不保证成功。

            > 拦截时效、费用、二次改址失败处置等细则见《改址与物流拦截细则》。本篇仅保留与旧版一致的摘要，避免与专篇冲突。

            ### 偏远与加价件
            - 偏远地区即使满 99 / 金卡满 79 仍可能收取续重或偏远附加费，以下单页展示为准
            """;

    /** 切片正文：切前清洗后段落之间是单换行，与解析文本的空行不同 */
    private static final String MODIFY_ADDRESS_BODY = "订单「待发货」状态可在线修改 **一次**收货地址；已发货只能联系快递拦截，不保证成功。"
            + "\n> 拦截时效、费用、二次改址失败处置等细则见《改址与物流拦截细则》。本篇仅保留与旧版一致的摘要，避免与专篇冲突。";

    private static final String REMOTE_BODY = "- 偏远地区即使满 99 / 金卡满 79 仍可能收取续重或偏远附加费，以下单页展示为准";

    /**
     * 高亮范围应从本节标题行一直到该片正文结束，包含段落与引用块，且不带亮下一节标题。
     */
    @Test
    void offsetCoversHeadingParagraphAndQuote() {
        DocumentChunk chunk = new DocumentChunk(MODIFY_ADDRESS_BODY, 0, "修改地址（摘要）");

        ChunkPresentation.assignOffsets(List.of(chunk), SECTION_TEXT);

        int start = startOf(chunk);
        int end = endOf(chunk);
        String span = SECTION_TEXT.substring(start, end);

        assertEquals(SECTION_TEXT.indexOf("### 修改地址（摘要）"), start, "范围应从本节标题行行首开始");
        assertTrue(span.startsWith("### 修改地址（摘要）"), "标题必须进范围，实际：" + span);
        assertTrue(span.contains("订单「待发货」状态可在线修改"), "段落必须进范围");
        assertTrue(span.contains("> 拦截时效"), "引用块必须进范围，不能在第一个块元素处截断");
        assertTrue(span.endsWith("避免与专篇冲突。"), "范围应在正文最后一个字符结束，实际：" + span);
        assertFalse(span.contains("偏远与加价件"), "下一节标题不得被带亮");
        assertTrue(span.length() > "修改地址（摘要）".length() * 5, "范围不能退化成一行标题");
    }

    /**
     * 相邻两片各自覆盖本节，不互相串选区。
     */
    @Test
    void adjacentChunksKeepTheirOwnRanges() {
        DocumentChunk first = new DocumentChunk(MODIFY_ADDRESS_BODY, 0, "修改地址（摘要）");
        DocumentChunk second = new DocumentChunk(REMOTE_BODY, 0, "偏远与加价件");

        ChunkPresentation.assignOffsets(List.of(first, second), SECTION_TEXT);

        assertEquals(SECTION_TEXT.indexOf("### 修改地址（摘要）"), startOf(first));
        assertEquals(SECTION_TEXT.indexOf("### 偏远与加价件"), startOf(second));
        assertTrue(endOf(first) <= startOf(second), "两片范围不得重叠到对方正文");
        String secondSpan = SECTION_TEXT.substring(startOf(second), endOf(second));
        assertTrue(secondSpan.startsWith("### 偏远与加价件"));
        assertFalse(secondSpan.contains("修改地址"), "后一片不得带亮前一节");
    }

    /**
     * 表格片的「表头：…」是策略合成前缀，原文只有数据行；偏移要到数据行结束。
     */
    @Test
    void tabularChunkSkipsSyntheticHeaderLine() {
        String text = """
                | 等级 | 权益 |
                |------|------|
                | 普通 | 0～999 元 |
                | 银卡 | 1000 元及以上 |
                """;
        DocumentChunk chunk = new DocumentChunk("表头：| 等级 | 权益 |\n| 银卡 | 1000 元及以上 |", 0, "| 等级 | 权益 |");

        ChunkPresentation.assignOffsets(List.of(chunk), text);

        String span = text.substring(startOf(chunk), endOf(chunk));
        assertEquals("| 银卡 | 1000 元及以上 |", span);
    }

    /**
     * 条目片的「条目名 / 字段名」是策略合成前缀，定位时跳过，范围覆盖字段正文。
     */
    @Test
    void entryChunkSkipsSyntheticPrefixLines() {
        String text = """
                ## 当归

                味甘、辛，性温。归肝、心、脾经。
                """;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("entry_name", "当归");
        meta.put("field_type", "性味");
        DocumentChunk chunk = new DocumentChunk("当归\n性味\n味甘、辛，性温。归肝、心、脾经。", 0, "性味", meta);

        ChunkPresentation.assignOffsets(List.of(chunk), text);

        String span = text.substring(startOf(chunk), endOf(chunk));
        assertEquals("味甘、辛，性温。归肝、心、脾经。", span);
    }

    /**
     * 正文在原文里定位不到时不写偏移：宁可前端不高亮，也不只标一行标题冒充整片。
     */
    @Test
    void doesNotWriteTitleOnlyOffsetWhenBodyMissing() {
        DocumentChunk chunk = new DocumentChunk("这段正文在解析文本里根本不存在", 0, "修改地址（摘要）");

        ChunkPresentation.assignOffsets(List.of(chunk), SECTION_TEXT);

        assertNull(chunk.getMetadata().get("char_start"), "定位不到正文时不应写入起偏移");
        assertNull(chunk.getMetadata().get("char_end"), "定位不到正文时不应写入止偏移");
    }

    private static int startOf(DocumentChunk chunk) {
        Integer start = (Integer) chunk.getMetadata().get("char_start");
        assertNotNull(start, "应写入 char_start");
        return start;
    }

    private static int endOf(DocumentChunk chunk) {
        Integer end = (Integer) chunk.getMetadata().get("char_end");
        assertNotNull(end, "应写入 char_end");
        return end;
    }
}
