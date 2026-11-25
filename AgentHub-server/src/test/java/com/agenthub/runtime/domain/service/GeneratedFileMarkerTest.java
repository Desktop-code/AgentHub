package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「文件已生成」标记的提取与清洗。所属域：runtime。
 * 关键约束：标记必须被物理剔除，否则会污染模型上下文与聊天框。
 */
class GeneratedFileMarkerTest {

    /**
     * 标记被完整剔除，正文其余内容原样保留。
     */
    @Test
    void stripsMarkerAndKeepsText() {
        String raw = "文件已成功写入。你可以通过路径 /workspace/report.md 在后续任务中引用它。\n"
                + GeneratedFileMarker.mark("report.md", 2048);

        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(raw);

        assertFalse(scan.clean().contains("FILE_GENERATED"), scan.clean());
        assertTrue(scan.clean().contains("/workspace/report.md"), scan.clean());
        assertEquals(1, scan.files().size());
        assertEquals("report.md", scan.files().get(0).fileName());
        assertEquals(2048L, scan.files().get(0).size());
    }

    /**
     * 没有标记时原样返回，不做任何改写（大段工具结果很常见，不该被无谓拷贝）。
     */
    @Test
    void passesThroughWithoutMarker() {
        String raw = "普通工具结果，没有任何标记";
        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(raw);

        assertEquals(raw, scan.clean());
        assertTrue(scan.files().isEmpty());
    }

    /**
     * 文件名含中文、引号、方括号时仍能正确解析：JSON 由 Jackson 生成，不会破坏标记结构。
     */
    @Test
    void handlesAwkwardFileName() {
        String name = "季度\"报告\"[终版].md";
        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(
                "前缀\n" + GeneratedFileMarker.mark(name, 100) + "\n后缀");

        assertEquals(1, scan.files().size());
        assertEquals(name, scan.files().get(0).fileName());
        // 标记前后的人话都要留下，不能连带吃掉相邻正文
        assertTrue(scan.clean().startsWith("前缀"), scan.clean());
        assertTrue(scan.clean().endsWith("后缀"), scan.clean());
    }

    /**
     * 多个标记一次全提，供一轮内生成多个文件时逐张出卡片。
     */
    @Test
    void extractsMultipleMarkers() {
        String raw = GeneratedFileMarker.mark("a.md", 10) + "\n中间\n" + GeneratedFileMarker.mark("b.json", 20);
        List<GeneratedFile> files = GeneratedFileMarker.extract(raw);

        assertEquals(2, files.size());
        assertEquals("a.md", files.get(0).fileName());
        assertEquals("b.json", files.get(1).fileName());
    }

    /**
     * 形状坏掉的标记只跳过该条，不能让整段工具结果失效。
     */
    @Test
    void skipsMalformedPayload() {
        String raw = "结果\n[FILE_GENERATED]not-json[/FILE_GENERATED]";
        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(raw);

        assertTrue(scan.files().isEmpty());
        assertFalse(scan.clean().contains("FILE_GENERATED"), scan.clean());
    }

    /**
     * 带作者的文件：标记正文同时写出参与者 id / 名称，供回放按落库身份挂卡。
     */
    @Test
    void payloadCarriesParticipantIdentity() {
        String payload = GeneratedFileMarker.payload("report.md", 30, "p-1-writer", "报告撰写专家");

        assertTrue(payload.contains("\"participantId\":\"p-1-writer\""), payload);
        assertTrue(payload.contains("\"participantName\":\"报告撰写专家\""), payload);
        // 没有身份时仍保持旧形状：不写空字段，旧前端照常解析
        String legacy = GeneratedFileMarker.payload("report.md", 30, "", "");
        assertFalse(legacy.contains("participantId"), legacy);
    }

    /**
     * 体积格式化：0 字节不该被当成「未知」。
     */
    @Test
    void formatsSize() {
        assertEquals("0 B", new GeneratedFile("empty.md", 0).humanSize());
        assertEquals("大小未知", new GeneratedFile("a.md", -1).humanSize());
        assertTrue(new GeneratedFile("report.md", 2048).humanSize().contains("KB"));
    }
}
