package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摘要里不能留下密钥、Bearer 或密文。
 */
class TrialTextsTest {

    /**
     * Bearer、sk- 和 iv:cipher 都要变成占位，并按列长截断。
     */
    @Test
    void redactsSecretsAndClips() {
        String raw = "回答 Bearer sk-live-abcdef 以及 api_key=secret-value "
                + "YWJjZGVmZ2hpamtsbW5vcA==:MTIzNDU2Nzg5MGFiY2RlZg== 后面还有很多字";
        String summary = TrialTexts.summary(raw + "x".repeat(600));
        assertFalse(summary.contains("sk-live"));
        assertFalse(summary.contains("secret-value"));
        assertFalse(summary.contains("YWJjZGVmZ2hpamtsbW5vcA"));
        assertTrue(summary.contains("***"));
        assertTrue(summary.length() <= TrialTexts.SUMMARY_LIMIT);
        assertTrue(TrialTexts.preview(raw).length() <= TrialTexts.PREVIEW_LIMIT);
        assertEquals("", TrialTexts.summary("   "));
    }

    /**
     * 标题和列表靠换行区分。摘要不能把它们折成一行。
     */
    @Test
    void summaryKeepsMarkdownLines() {
        String summary = TrialTexts.summary("# 标题\n\n- 第一项\n- **加粗**");
        assertEquals("# 标题\n\n- 第一项\n- **加粗**", summary);
    }

    /**
     * 正文要留下 Markdown 和代码缩进，同时去掉密钥。
     */
    @Test
    void contentKeepsMarkdownAndRedacts() {
        String raw = "# 标题\n\n```java\n    int a = 1;\n```\napi_key=super-secret\n";
        String content = TrialTexts.content(raw, TrialTexts.CONTENT_LIMIT);
        assertTrue(content.contains("# 标题\n"));
        assertTrue(content.contains("    int a = 1;"));
        assertFalse(content.contains("super-secret"));
        assertTrue(content.contains("api_key=***"));
        assertEquals("", TrialTexts.content("  ", 100));
    }

    /**
     * 超长正文截断后仍能看出不完整，并且不超过上限。
     */
    @Test
    void contentTruncatesWithMark() {
        String content = TrialTexts.content("x".repeat(100), 16);
        assertTrue(content.endsWith(TrialTexts.TRUNCATED_MARK));
        assertTrue(content.length() <= 16);
    }

    /**
     * 工具参数里的 Key、Token、密码不能原样展示。普通字段保留。
     */
    @Test
    void redactsToolArguments() {
        String raw = "{\"city\":\"北京\",\"api_key\":\"sk-live-abcdef\",\"password\":\"p@ss\",\"keyword\":\"token\"}";
        String args = TrialTexts.redactArgs(raw);
        assertFalse(args.contains("sk-live"));
        assertFalse(args.contains("p@ss"));
        assertTrue(args.contains("北京"));
        assertTrue(args.contains("keyword"));
        assertEquals("调用工具 weather，参数 " + args, TrialTexts.toolDisplay("weather", raw));
        assertEquals("调用工具 weather", TrialTexts.toolDisplay("weather", "  "));
    }
}
