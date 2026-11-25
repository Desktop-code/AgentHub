package com.agenthub.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站失败摘要脱敏契约。所属域：common。
 *
 * <p>验收口径（安全相关）：返回文本里**不得**出现本次明文 Key；必须折成单行；
 * 超过 180 字要截断但不得把 Key 残留到截断点之后；空体返回空串。
 */
class SecretBriefTest {

    /** 本次使用的 Key 必须被替换成 ***，且不再出现在任何位置。 */
    @Test
    void masksCurrentKey() {
        String out = SecretBrief.brief("401 unauthorized key=sk-live-abc123", "sk-live-abc123");

        assertFalse(out.contains("sk-live-abc123"), out);
        assertTrue(out.contains("***"), out);
    }

    /** 换行折成空格（不折叠连续空白），保证 error_msg 只有一行。 */
    @Test
    void flattensNewlines() {
        String out = SecretBrief.brief("line1 \r\n line2", null);

        assertFalse(out.contains("\n"), out);
        assertFalse(out.contains("\r"), out);
        assertEquals("line1    line2", out);
    }

    /** 超长按 180 字截断；Key 在截断点之前也必须已抹掉。 */
    @Test
    void truncatesTo180AfterMasking() {
        String key = "k-1234567890";
        String out = SecretBrief.brief(key + "x".repeat(400), key);

        assertEquals(SecretBrief.MAX_LENGTH, out.length());
        assertFalse(out.contains(key), out);
        assertTrue(out.startsWith("***"), out);
    }

    /** 空体与空白体返回空串，调用方据此不拼冒号。 */
    @Test
    void blankBodyReturnsEmpty() {
        assertEquals("", SecretBrief.brief(null, "k"));
        assertEquals("", SecretBrief.brief("   ", "k"));
    }

    /** 没有 Key 时只折行与截断，不做任何正则脱敏。 */
    @Test
    void withoutKeyKeepsText() {
        assertEquals("boom", SecretBrief.brief(" boom ", null));
    }
}
