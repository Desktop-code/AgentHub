package com.agenthub.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文本截断契约。所属域：common。
 *
 * <p>验收口径：{@link TextClip#raw} 一个字符都不改（不 trim、不补省略号），
 * {@link TextClip#trimmed} 只 trim；两者的 null 都保持 null（调用方据此区分「没有」与「空串」）。
 * 这两条是既有 11 个调用点的对外行为，任何改动都会影响前端展示与 error_msg 列。
 */
class TextClipTest {

    /** raw：原样截断，空白与换行必须原样保留。 */
    @Test
    void rawKeepsTextVerbatim() {
        assertEquals("  ab ", TextClip.raw("  ab ", 100));
        assertEquals("a", TextClip.raw("abc", 1));
        assertEquals("a\nb", TextClip.raw("a\nb", 3));
    }

    /** raw：null 进 null 出，不能变成空串。 */
    @Test
    void rawKeepsNull() {
        assertNull(TextClip.raw(null, 10));
    }

    /** trimmed：先 trim 再截断，也不补省略号。 */
    @Test
    void trimmedStripsThenCuts() {
        assertEquals("ab", TextClip.trimmed("  ab  ", 100));
        assertEquals("ab", TextClip.trimmed("\n\t ab \r\n", 2));
        assertNull(TextClip.trimmed(null, 10));
    }

    /** 边界：等于上限时原样返回，不越界。 */
    @Test
    void exactMaxIsUnchanged() {
        assertEquals("abcd", TextClip.raw("abcd", 4));
        assertEquals("abcd", TextClip.trimmed("  abcd  ", 4));
    }
}
