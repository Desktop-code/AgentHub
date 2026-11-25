package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标记解析对「JSON 转义后」正文的兼容性。所属域：runtime。
 *
 * <p>为什么必须覆盖这种形状：AgentScope 的 {@code DefaultToolResultConverter}
 * 对 {@code String} 返回值走 {@code toJson}，会把整段工具结果再序列化一次，
 * 于是标记里的引号全被转义成 {@code \"}。这不是我们的工具写错了，
 * 而是框架对 String 返回值的既定处理，解析器必须容忍。
 */
class GeneratedFileMarkerEscapedTest {

    /** 与框架 toJson 一致的序列化，用来还原真实到达解析器的字节 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 整段被 toJson 序列化（引号转义、换行变 \n）后，仍必须能提取出产物。
     * 这是用户在聊天区看不到下载卡片的直接原因。
     */
    @Test
    void extractsFromJsonEscapedText() throws Exception {
        String plain = "文件已成功写入。你可以通过路径 /workspace/starfield.html 在后续任务中引用它。\n"
                + GeneratedFileMarker.mark("starfield.html", 9240);
        // 模拟框架行为：String 返回值被再次 toJson
        String serialized = JSON.writeValueAsString(plain);

        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(serialized);

        assertEquals(1, scan.files().size(), "转义后仍应提取出产物：" + serialized);
        assertEquals("starfield.html", scan.files().get(0).fileName());
        assertEquals(9240L, scan.files().get(0).size());
        // 标记不论原始还是转义形态，都不能残留在回灌文本里
        assertFalse(scan.clean().contains(GeneratedFileMarker.OPEN), scan.clean());
    }

    /**
     * 清洗后不应留下成对的转义引号残骸。
     */
    @Test
    void escapedCleanKeepsReadableText() throws Exception {
        String plain = "写入成功 /workspace/a.md\n" + GeneratedFileMarker.mark("a.md", 12);
        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(JSON.writeValueAsString(plain));

        assertTrue(scan.clean().contains("/workspace/a.md"), scan.clean());
        assertTrue(scan.files().size() == 1);
    }

    /**
     * 未转义的原始形态仍要正常（工具未经框架序列化时，如单测直调）。
     */
    @Test
    void stillHandlesPlainText() {
        GeneratedFileMarker.Scan scan = GeneratedFileMarker.scan(
                "写入成功\n" + GeneratedFileMarker.mark("b.md", 20));

        assertEquals(1, scan.files().size());
        assertEquals("b.md", scan.files().get(0).fileName());
    }
}
